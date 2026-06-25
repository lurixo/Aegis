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

package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import java.io.ByteArrayOutputStream
import java.io.File

object EngineFixture {

    data class Row(val key: String, val word: String, val freq: Int)

    fun build(rows: List<Row>): BinaryDict {
        val byKey = LinkedHashMap<String, MutableList<Row>>()
        for (r in rows) byKey.getOrPut(r.key) { ArrayList() }.add(r)
        val keys = byKey.keys.sortedWith(compareBy({ it }, { it }))
        val keyBlob = ByteArrayOutputStream()
        val wordBlob = ByteArrayOutputStream()
        val keyArr = ArrayList<Int>()
        val entryArr = ArrayList<Int>()
        var numEntries = 0
        var totalFreq = 0L
        val seenPerKey = HashSet<String>()
        for (key in keys) {
            val kb = key.toByteArray(Charsets.US_ASCII)
            keyArr.add(keyBlob.size()); keyArr.add(kb.size); keyArr.add(numEntries)
            keyBlob.write(kb)
            seenPerKey.clear()
            for (row in byKey.getValue(key).sortedByDescending { it.freq }) {
                if (!seenPerKey.add(row.word)) continue
                val wb = row.word.toByteArray(Charsets.UTF_8)
                entryArr.add(wordBlob.size()); entryArr.add(wb.size); entryArr.add(row.freq)
                wordBlob.write(wb)
                numEntries++
                totalFreq += row.freq.toLong()
            }
        }
        val keyBytes = keyBlob.toByteArray()
        val wordBytes = wordBlob.toByteArray()
        val out = ByteArrayOutputStream()
        fun le(v: Int) { out.write(v); out.write(v ushr 8); out.write(v ushr 16); out.write(v ushr 24) }
        fun leLong(v: Long) { for (s in 0 until 64 step 8) out.write((v ushr s).toInt() and 0xFF) }
        out.write(byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'D'.code.toByte()))
        le(2)
        le(keys.size)
        le(numEntries)
        leLong(totalFreq)
        le(keyBytes.size); out.write(keyBytes)
        le(wordBytes.size); out.write(wordBytes)
        for (v in keyArr) le(v)
        for (v in entryArr) le(v)

        val file = File.createTempFile("aegis_fixture", ".bin")
        file.deleteOnExit()
        file.writeBytes(out.toByteArray())
        return BinaryDict.fromFile(file)
    }
}
