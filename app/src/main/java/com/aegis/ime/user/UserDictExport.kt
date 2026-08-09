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

package com.aegis.ime.user

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object UserDictExport {

    fun copyWithoutTombstones(source: InputStream, sink: OutputStream) {
        val reader = source.buffered()
        val writer = sink.buffered()
        val header = readRow(reader)
        if (header != null) {
            writer.write(steppedDownFrom(header))
            while (true) {
                val row = readRow(reader) ?: break
                if (!isTombstone(row)) writer.write(row)
            }
        }
        writer.flush()
    }

    private fun readRow(reader: InputStream): ByteArray? {
        val row = ByteArrayOutputStream()
        while (true) {
            val b = reader.read()
            if (b < 0) break
            row.write(b)
            if (b == NEWLINE) break
        }
        return if (row.size() == 0) null else row.toByteArray()
    }

    private fun isTombstone(row: ByteArray): Boolean =
        row.size >= TOMBSTONE_ROW.size && TOMBSTONE_ROW.indices.all { row[it] == TOMBSTONE_ROW[it] }

    private fun steppedDownFrom(header: ByteArray): ByteArray {
        val end = if (header.last().toInt() == NEWLINE) header.size - 1 else header.size
        if (end != TOMBSTONE_HEADER.size) return header
        if (!TOMBSTONE_HEADER.indices.all { header[it] == TOMBSTONE_HEADER[it] }) return header
        return HEADER + header.copyOfRange(end, header.size)
    }

    private val TOMBSTONE_HEADER = "aegis-userdb 4".toByteArray(Charsets.US_ASCII)
    private val HEADER = "aegis-userdb 3".toByteArray(Charsets.US_ASCII)
    private val TOMBSTONE_ROW = "D\t".toByteArray(Charsets.US_ASCII)
    private const val NEWLINE = '\n'.code
}
