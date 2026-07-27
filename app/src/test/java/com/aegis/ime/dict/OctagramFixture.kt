// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.dict

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

object OctagramFixture {

    fun reader(entries: Map<String, Double>): OctagramReader = OctagramReader.fromFile(write(entries))

    fun write(entries: Map<String, Double>): File {
        val root = Node()
        for ((text, score) in entries) {
            require(text.isNotEmpty())
            var node = root
            for (b in OctagramReader.encode(text)) {
                node = node.children.getOrPut(b.toInt() and 0xFF) { Node() }
            }
            node.value = (score * 10000.0).roundToInt()
        }

        val units = ArrayList<Int>(1024)
        val used = ArrayList<Boolean>(1024)
        val usedBase = HashSet<Int>()
        fun ensure(index: Int) {
            while (units.size <= index) {
                units.add(0)
                used.add(false)
            }
        }
        ensure(0)
        used[0] = true

        val queue = ArrayDeque<Pair<Node, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (node, id) = queue.removeFirst()
            if (node.value == null && node.children.isEmpty()) continue
            var offset = 1
            while (true) {
                val base = id xor offset
                ensure(base)
                var free = base !in usedBase && (node.value == null || !used[base])
                if (free) {
                    for (label in node.children.keys) {
                        val slot = base xor label
                        ensure(slot)
                        if (used[slot]) {
                            free = false
                            break
                        }
                    }
                }
                if (free) break
                offset++
            }
            require(offset < (1 shl 21))
            val base = id xor offset
            usedBase.add(base)
            if (node.value != null) {
                used[base] = true
                units[base] = (node.value!! and 0x7FFFFFFF) or (1 shl 31)
            }
            for ((label, child) in node.children) {
                val slot = base xor label
                used[slot] = true
                units[slot] = label
                queue.add(child to slot)
            }
            units[id] = units[id] or (offset shl 10) or (if (node.value != null) 1 shl 8 else 0)
        }

        while (units.size % 256 != 0) units.add(0)

        val out = ByteArrayOutputStream()
        fun le(v: Int) {
            out.write(v)
            out.write(v ushr 8)
            out.write(v ushr 16)
            out.write(v ushr 24)
        }
        val format = "Rime::Grammar/1.0".toByteArray(Charsets.US_ASCII)
        out.write(format)
        repeat(32 - format.size) { out.write(0) }
        le(0)
        le(units.size)
        le(4)
        for (u in units) le(u)

        val file = File.createTempFile("aegis_gram_fixture", ".gram")
        file.deleteOnExit()
        file.writeBytes(out.toByteArray())
        return file
    }

    private class Node {
        val children = sortedMapOf<Int, Node>()
        var value: Int? = null
    }
}
