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

package com.aegis.ime.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream

internal object BackupArchive {

    private const val ENTRY_PREFS = 'P'.code
    private const val ENTRY_PREFS_CHUNKED = 'p'.code
    private const val ENTRY_FILE = 'F'.code
    private const val ENTRY_END = 'X'.code
    private const val COPY_CHUNK = 64 * 1024

    private const val MAX_PREFS_BYTES = 8 * 1024 * 1024

    private const val MAX_CHUNK_BYTES = 16 * 1024 * 1024

    fun fitsLegacyPrefsEntry(blob: ByteArray): Boolean = blob.size <= MAX_PREFS_BYTES

    fun writePrefs(out: DataOutputStream, blob: ByteArray) {
        out.writeByte(ENTRY_PREFS)
        out.writeInt(blob.size)
        out.write(blob)
    }

    fun writePrefsChunked(out: DataOutputStream, blob: ByteArray) {
        out.writeByte(ENTRY_PREFS_CHUNKED)
        var off = 0
        while (off < blob.size) {
            val n = minOf(COPY_CHUNK, blob.size - off)
            out.writeInt(n)
            out.write(blob, off, n)
            off += n
        }
        out.writeInt(0)
    }

    fun writeFile(out: DataOutputStream, name: String, file: File) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        out.writeByte(ENTRY_FILE)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        file.inputStream().use { input ->
            val buf = ByteArray(COPY_CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                out.writeInt(n)
                out.write(buf, 0, n)
            }
            out.writeInt(0)
        }
    }

    fun writeEnd(out: DataOutputStream) {
        out.writeByte(ENTRY_END)
    }

    interface Visitor {
        fun onPrefs(blob: ByteArray)

        fun openFile(relativePath: String): OutputStream
    }

    fun read(input: DataInputStream, visitor: Visitor) {
        val buf = ByteArray(COPY_CHUNK)
        while (true) {
            val tag = input.read()
            if (tag < 0) throw BackupCorruptException("archive ended before the end marker")
            when (tag) {
                ENTRY_END -> return
                ENTRY_PREFS -> {
                    val len = input.readInt()
                    if (len < 0 || len > MAX_PREFS_BYTES) throw BackupCorruptException("bad prefs length $len")
                    val blob = ByteArray(len)
                    input.readFully(blob)
                    visitor.onPrefs(blob)
                }
                ENTRY_PREFS_CHUNKED -> {
                    val blob = ByteArrayOutputStream()
                    copyChunks(input, blob, buf)
                    visitor.onPrefs(blob.toByteArray())
                }
                ENTRY_FILE -> {
                    val nameLen = input.readUnsignedShort()
                    val nameBytes = ByteArray(nameLen)
                    input.readFully(nameBytes)
                    val rawName = String(nameBytes, Charsets.UTF_8)
                    val safe = sanitizedRelativePath(rawName)
                        ?: throw BackupCorruptException("unsafe entry name")
                    visitor.openFile(safe).use { sink -> copyChunks(input, sink, buf) }
                }
                else -> throw BackupCorruptException("unknown entry tag $tag")
            }
        }
    }

    private fun copyChunks(input: DataInputStream, sink: OutputStream, buf: ByteArray) {
        while (true) {
            val len = input.readInt()
            if (len == 0) return
            if (len < 0 || len > MAX_CHUNK_BYTES) throw BackupCorruptException("bad chunk length $len")
            var remaining = len
            while (remaining > 0) {
                val toRead = minOf(remaining, buf.size)
                input.readFully(buf, 0, toRead)
                sink.write(buf, 0, toRead)
                remaining -= toRead
            }
        }
    }

    fun sanitizedRelativePath(name: String): String? {
        if (name.isEmpty() || name.length > 255) return null
        if (name.startsWith("/") || name.contains("\\") || name.contains("..")) return null
        if (name in TOP_LEVEL_FILES) return name
        if (name == "emoji/symbol_usage.txt") return name
        if (name.startsWith("clips/")) {
            val token = name.substring("clips/".length)
            if (token.endsWith(".txt")) {
                val stem = token.removeSuffix(".txt")
                if (stem.isNotEmpty() && stem.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return name
            }
        }
        return null
    }

    private val TOP_LEVEL_FILES = setOf(
        "userdb.txt",
        "userlearn.txt",
        "phrases.txt",
        "clipboard.txt",
        "symbol_usage.txt",
    )
}
