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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream

class BackupArchiveTest {

    private data class Record(val name: String, val kind: Int, val bytes: ByteArray)

    private fun archive(records: List<Record>, finish: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        val writer = BackupArchive.Writer(DataOutputStream(bytes))
        for (record in records) writer.writeRecord(record.name, record.kind) { it.write(record.bytes) }
        if (finish) writer.finish()
        return bytes.toByteArray()
    }

    private fun readAll(bytes: ByteArray): Pair<Map<String, ByteArray>, List<BackupArchive.RecordMetadata>> {
        val records = LinkedHashMap<String, ByteArrayOutputStream>()
        val metadata = BackupArchive.read(
            DataInputStream(ByteArrayInputStream(bytes)),
            object : BackupArchive.Visitor {
                override fun openRecord(name: String, kind: Int): OutputStream =
                    ByteArrayOutputStream().also { records[name] = it }
            },
        )
        return records.mapValues { it.value.toByteArray() } to metadata
    }

    @Test
    fun recordsAndManifestRoundTripAcrossManyNormalChunks() {
        val database = ByteArray(250_000) { (it * 31).toByte() }
        val preference = "setting-value".toByteArray()
        val archive = archive(
            listOf(
                Record("database", BackupArchive.KIND_DATABASE, database),
                Record("preference/00000000", BackupArchive.KIND_PREFERENCE, preference),
            ),
        )

        val (records, metadata) = readAll(archive)
        assertArrayEquals(database, records["database"])
        assertArrayEquals(preference, records["preference/00000000"])
        assertEquals(2, metadata.size)
        assertEquals(database.size.toLong(), metadata[0].size)
        assertEquals(preference.size.toLong(), metadata[1].size)
        assertEquals(32, metadata[0].sha256.size)
    }

    @Test
    fun emptyRecordRoundTrips() {
        val (records, metadata) = readAll(
            archive(listOf(Record("database", BackupArchive.KIND_DATABASE, ByteArray(0)))),
        )
        assertArrayEquals(ByteArray(0), records["database"])
        assertEquals(0L, metadata.single().size)
    }

    @Test
    fun missingManifestIsCorrupt() {
        assertCorrupt {
            readAll(archive(listOf(Record("database", BackupArchive.KIND_DATABASE, byteArrayOf(1))), finish = false))
        }
    }

    @Test
    fun recordOrManifestTamperingIsCorrupt() {
        val bytes = archive(listOf(Record("database", BackupArchive.KIND_DATABASE, ByteArray(100) { it.toByte() })))
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        assertCorrupt { readAll(bytes) }
    }

    @Test
    fun trailingArchiveDataIsCorrupt() {
        val bytes = archive(listOf(Record("database", BackupArchive.KIND_DATABASE, byteArrayOf(1, 2, 3))))
        assertCorrupt { readAll(bytes + byteArrayOf(9)) }
    }

    @Test
    fun declaredChunksBeyondTheSixteenMiBDefenseAreRejectedBeforeAllocation() {
        val bytes = archive(listOf(Record("database", BackupArchive.KIND_DATABASE, byteArrayOf(1))))
        val chunkLengthOffset = 8 + 1 + 1 + 1 + 2 + "database".length
        val maliciousLength = 16 * 1024 * 1024 + 1
        for (index in 0 until 4) {
            bytes[chunkLengthOffset + index] = (maliciousLength ushr (24 - index * 8)).toByte()
        }
        assertCorrupt { readAll(bytes) }
    }

    @Test
    fun manifestRecordCountsBeyondTheDefenseAreRejected() {
        val bytes = archive(emptyList())
        val countOffset = 8 + 1 + 1
        val maliciousCount = 1_000_001
        for (index in 0 until 4) {
            bytes[countOffset + index] = (maliciousCount ushr (24 - index * 8)).toByte()
        }
        assertCorrupt { readAll(bytes) }
    }

    @Test
    fun namesAreRestrictedToTheVersionTwoSchema() {
        for ((name, kind) in listOf(
            "database" to BackupArchive.KIND_DATABASE,
            "preference/00000000" to BackupArchive.KIND_PREFERENCE,
            "preference/99999999" to BackupArchive.KIND_PREFERENCE,
        )) {
            assertEquals(name, BackupArchive.sanitizedRecordName(name, kind))
        }
        for ((name, kind) in listOf(
            "../database" to BackupArchive.KIND_DATABASE,
            "/database" to BackupArchive.KIND_DATABASE,
            "userdb.txt" to BackupArchive.KIND_DATABASE,
            "preference/1" to BackupArchive.KIND_PREFERENCE,
            "preference/../00000000" to BackupArchive.KIND_PREFERENCE,
            "database" to BackupArchive.KIND_PREFERENCE,
            "random" to 99,
        )) {
            assertNull(BackupArchive.sanitizedRecordName(name, kind))
        }
    }

    @Test
    fun writerRejectsDuplicateNames() {
        val bytes = ByteArrayOutputStream()
        val writer = BackupArchive.Writer(DataOutputStream(bytes))
        writer.writeRecord("database", BackupArchive.KIND_DATABASE) {}
        var rejected = false
        try {
            writer.writeRecord("database", BackupArchive.KIND_DATABASE) {}
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun assertCorrupt(block: () -> Unit) {
        try {
            block()
            fail("expected BackupCorruptException")
        } catch (_: BackupCorruptException) {
        }
    }
}
