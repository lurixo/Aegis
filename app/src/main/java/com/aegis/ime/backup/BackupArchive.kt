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

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest

internal object BackupArchive {

    const val KIND_DATABASE = 1
    const val KIND_PREFERENCE = 2

    private val ARCHIVE_MAGIC = byteArrayOf(0x41, 0x45, 0x47, 0x49, 0x53, 0x41, 0x52, 0x32)
    private const val ARCHIVE_VERSION = 2
    private const val ENTRY_RECORD = 'R'.code
    private const val ENTRY_MANIFEST = 'M'.code
    private const val WRITE_CHUNK_BYTES = 64 * 1024
    private const val MAX_DECLARED_CHUNK_BYTES = 16 * 1024 * 1024
    private const val MAX_RECORDS = 1_000_000
    private const val DIGEST_BYTES = 32

    data class RecordMetadata(
        val name: String,
        val kind: Int,
        val size: Long,
        val sha256: ByteArray,
    )

    class Writer(private val output: DataOutputStream) {
        private val records = ArrayList<RecordMetadata>()
        private val names = HashSet<String>()
        private var finished = false

        init {
            output.write(ARCHIVE_MAGIC)
            output.writeByte(ARCHIVE_VERSION)
        }

        fun writeRecord(name: String, kind: Int, write: (OutputStream) -> Unit) {
            check(!finished)
            require(sanitizedRecordName(name, kind) != null)
            require(names.add(name))
            output.writeByte(ENTRY_RECORD)
            output.writeByte(kind)
            writeName(output, name)
            val record = ChunkedRecordOutput(output)
            write(record)
            record.finish()
            records.add(RecordMetadata(name, kind, record.size, record.digest()))
        }

        fun finish() {
            check(!finished)
            finished = true
            output.writeByte(ENTRY_MANIFEST)
            output.writeInt(records.size)
            val digest = MessageDigest.getInstance("SHA-256")
            for (record in records) writeMetadata(output, digest, record)
            output.write(digest.digest())
            output.flush()
        }
    }

    interface Visitor {
        fun openRecord(name: String, kind: Int): OutputStream
    }

    fun read(input: DataInputStream, visitor: Visitor): List<RecordMetadata> {
        val magic = ByteArray(ARCHIVE_MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(ARCHIVE_MAGIC)) throw BackupCorruptException("bad archive magic")
        if (input.readUnsignedByte() != ARCHIVE_VERSION) throw BackupCorruptException("bad archive version")
        val records = ArrayList<RecordMetadata>()
        val names = HashSet<String>()
        val buffer = ByteArray(WRITE_CHUNK_BYTES)
        while (true) {
            when (val tag = input.read()) {
                ENTRY_RECORD -> {
                    if (records.size >= MAX_RECORDS) throw BackupCorruptException("too many records")
                    val kind = input.readUnsignedByte()
                    val name = readName(input)
                    if (sanitizedRecordName(name, kind) == null || !names.add(name)) {
                        throw BackupCorruptException("invalid record name")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    visitor.openRecord(name, kind).use { sink ->
                        while (true) {
                            val length = input.readInt()
                            if (length == 0) break
                            if (length < 0 || length > MAX_DECLARED_CHUNK_BYTES) {
                                throw BackupCorruptException("bad chunk length $length")
                            }
                            var remaining = length
                            while (remaining > 0) {
                                val count = minOf(remaining, buffer.size)
                                input.readFully(buffer, 0, count)
                                sink.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                size = Math.addExact(size, count.toLong())
                                remaining -= count
                            }
                        }
                    }
                    records.add(RecordMetadata(name, kind, size, digest.digest()))
                }
                ENTRY_MANIFEST -> {
                    readAndVerifyManifest(input, records)
                    if (input.read() != -1) throw BackupCorruptException("trailing archive data")
                    return records
                }
                -1 -> throw BackupCorruptException("archive ended before manifest")
                else -> throw BackupCorruptException("unknown entry tag $tag")
            }
        }
    }

    fun sanitizedRecordName(name: String, kind: Int): String? {
        if (name.isEmpty() || name.length > 255 || name.contains("..") || name.contains('\\')) return null
        return when {
            kind == KIND_DATABASE && name == "database" -> name
            kind == KIND_PREFERENCE && name.matches(Regex("preference/[0-9]{8}")) -> name
            else -> null
        }
    }

    private fun readAndVerifyManifest(input: DataInputStream, actual: List<RecordMetadata>) {
        val count = input.readInt()
        if (count < 0 || count > MAX_RECORDS || count != actual.size) {
            throw BackupCorruptException("bad manifest count $count")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        repeat(count) { index ->
            val expected = readMetadata(input, digest)
            val observed = actual[index]
            if (expected.name != observed.name || expected.kind != observed.kind || expected.size != observed.size ||
                !expected.sha256.contentEquals(observed.sha256)
            ) {
                throw BackupCorruptException("manifest mismatch at record $index")
            }
        }
        val expectedDigest = ByteArray(DIGEST_BYTES)
        input.readFully(expectedDigest)
        if (!expectedDigest.contentEquals(digest.digest())) throw BackupCorruptException("manifest digest mismatch")
    }

    private fun writeMetadata(output: DataOutputStream, digest: MessageDigest, record: RecordMetadata) {
        val bytes = metadataBytes(record)
        output.write(bytes)
        digest.update(bytes)
    }

    private fun readMetadata(input: DataInputStream, digest: MessageDigest): RecordMetadata {
        val kind = input.readUnsignedByte()
        val name = readName(input)
        val size = input.readLong()
        if (size < 0) throw BackupCorruptException("bad record size")
        val hash = ByteArray(DIGEST_BYTES)
        input.readFully(hash)
        val record = RecordMetadata(name, kind, size, hash)
        digest.update(metadataBytes(record))
        return record
    }

    private fun metadataBytes(record: RecordMetadata): ByteArray {
        val nameBytes = record.name.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(1 + 2 + nameBytes.size + 8 + DIGEST_BYTES)
        var offset = 0
        bytes[offset++] = record.kind.toByte()
        bytes[offset++] = (nameBytes.size ushr 8).toByte()
        bytes[offset++] = nameBytes.size.toByte()
        nameBytes.copyInto(bytes, offset)
        offset += nameBytes.size
        for (shift in 56 downTo 0 step 8) bytes[offset++] = (record.size ushr shift).toByte()
        record.sha256.copyInto(bytes, offset)
        return bytes
    }

    private fun writeName(output: DataOutputStream, name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xffff)
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readName(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private class ChunkedRecordOutput(private val output: DataOutputStream) : OutputStream() {
        private val buffer = ByteArray(WRITE_CHUNK_BYTES)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var used = 0
        var size = 0L
            private set

        override fun write(value: Int) {
            if (used == buffer.size) flushBuffer()
            buffer[used++] = value.toByte()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var source = offset
            var remaining = length
            while (remaining > 0) {
                if (used == buffer.size) flushBuffer()
                val count = minOf(remaining, buffer.size - used)
                bytes.copyInto(buffer, used, source, source + count)
                used += count
                source += count
                remaining -= count
            }
        }

        fun finish() {
            flushBuffer()
            output.writeInt(0)
        }

        fun digest(): ByteArray = digest.digest()

        private fun flushBuffer() {
            if (used == 0) return
            output.writeInt(used)
            output.write(buffer, 0, used)
            digest.update(buffer, 0, used)
            size = Math.addExact(size, used.toLong())
            used = 0
        }
    }
}
