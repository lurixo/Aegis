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

package com.aegis.tools

import java.io.File

/**
 * Builds the English dictionary (BinaryDict format) from a `word count` frequency list.
 * Key = the word's letters only, lowercased (so "dont" matches "don't"); value = the original
 * word; freq = count. Reuses the shared external-sort + binary writer.
 */
object EnBuilder {
    fun build(rawArgs: Array<String>) {
        val args = Args(rawArgs)
        val out = File(args.required("--out"))
        val input = File(args.positionals.first())

        val tmp = File.createTempFile("aegis-en-", ".tsv").apply { deleteOnExit() }
        val tmpSorted = File.createTempFile("aegis-en-sorted-", ".tsv").apply { deleteOnExit() }

        var words = 0L
        tmp.bufferedWriter().use { w ->
            input.bufferedReader().forEachLine { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) return@forEachLine
                val word = line.substring(0, sp).trim()
                val freq = line.substring(sp + 1).trim().toIntOrNull() ?: return@forEachLine
                val key = buildString { for (c in word.lowercase()) if (c in 'a'..'z') append(c) }
                if (key.isEmpty()) return@forEachLine
                w.write(key); w.write("\t"); w.write(word); w.write("\t"); w.write(freq.toString()); w.write("\n")
                words++
            }
        }
        externalSort(tmp, tmpSorted)
        val (numKeys, numEntries) = writeBinary(tmpSorted, out, Int.MAX_VALUE)
        println("wrote ${out.path}: keys=$numKeys entries=$numEntries from $words words")
    }
}
