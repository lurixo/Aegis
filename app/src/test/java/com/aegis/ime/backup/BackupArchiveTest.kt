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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

class BackupArchiveTest {

    private class Collected {
        var prefs: ByteArray? = null
        val sinks = LinkedHashMap<String, ByteArrayOutputStream>()
        fun bytes(name: String): ByteArray? = sinks[name]?.toByteArray()
    }

    private fun readAll(bytes: ByteArray): Collected {
        val collected = Collected()
        BackupArchive.read(
            DataInputStream(ByteArrayInputStream(bytes)),
            object : BackupArchive.Visitor {
                override fun onPrefs(blob: ByteArray) { collected.prefs = blob }
                override fun openFile(relativePath: String): OutputStream =
                    ByteArrayOutputStream().also { collected.sinks[relativePath] = it }
            },
        )
        return collected
    }

    @Test fun round_trips_prefs_and_files() {
        val dir = Files.createTempDirectory("archive").toFile()
        val a = File(dir, "userdb.txt").apply { writeText("W\t你好\t3\t100\n") }
        val b = File(dir, "clip.bin").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }

        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            BackupArchive.writePrefs(out, byteArrayOf(1, 2, 3, 4))
            BackupArchive.writeFile(out, "userdb.txt", a)
            BackupArchive.writeFile(out, "clips/deadbeef.txt", b)
            BackupArchive.writeEnd(out)
        }

        val collected = readAll(bos.toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), collected.prefs)
        assertArrayEquals(a.readBytes(), collected.bytes("userdb.txt"))
        assertArrayEquals(b.readBytes(), collected.bytes("clips/deadbeef.txt"))
    }

    @Test fun round_trips_a_prefs_blob_far_beyond_the_legacy_entry_cap() {
        val blob = ByteArray(9 * 1024 * 1024 + 12_345) { (it * 31 + 7).toByte() }
        assertFalse(BackupArchive.fitsLegacyPrefsEntry(blob))

        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            BackupArchive.writePrefsChunked(out, blob)
            BackupArchive.writeEnd(out)
        }

        assertArrayEquals(blob, readAll(bos.toByteArray()).prefs)
    }

    @Test fun a_chunked_prefs_entry_stays_aligned_with_later_entries() {
        val dir = Files.createTempDirectory("archive4").toFile()
        val f = File(dir, "u").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val blob = ByteArray(200_000) { it.toByte() }
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            BackupArchive.writePrefsChunked(out, blob)
            BackupArchive.writeFile(out, "userdb.txt", f)
            BackupArchive.writeEnd(out)
        }
        val collected = readAll(bos.toByteArray())
        assertArrayEquals(blob, collected.prefs)
        assertArrayEquals(byteArrayOf(7, 8, 9), collected.bytes("userdb.txt"))
    }

    @Test fun a_legacy_prefs_entry_over_the_cap_is_still_corrupt() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeByte('P'.code)
            out.writeInt(9 * 1024 * 1024)
        }
        assertCorrupt { readAll(bos.toByteArray()) }
    }

    @Test fun an_absurd_chunk_length_in_a_prefs_entry_is_corrupt() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeByte('p'.code)
            out.writeInt(64 * 1024 * 1024)
        }
        assertCorrupt { readAll(bos.toByteArray()) }
    }

    @Test fun a_truncated_chunked_prefs_entry_is_rejected() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            BackupArchive.writePrefsChunked(out, ByteArray(300_000) { it.toByte() })
            BackupArchive.writeEnd(out)
        }
        val full = bos.toByteArray()
        try {
            readAll(full.copyOfRange(0, full.size - 100_000))
            fail("expected the truncated archive to be rejected")
        } catch (_: java.io.IOException) {
        }
    }

    @Test fun consecutive_file_entries_stay_aligned() {
        val dir = Files.createTempDirectory("archive2").toFile()
        val f1 = File(dir, "a").apply { writeBytes(byteArrayOf(10, 20, 30)) }
        val f2 = File(dir, "b").apply { writeBytes(byteArrayOf(40, 50)) }
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            BackupArchive.writeFile(out, "userdb.txt", f1)
            BackupArchive.writeFile(out, "phrases.txt", f2)
            BackupArchive.writeEnd(out)
        }
        val collected = readAll(bos.toByteArray())
        assertArrayEquals(byteArrayOf(10, 20, 30), collected.bytes("userdb.txt"))
        assertArrayEquals(byteArrayOf(40, 50), collected.bytes("phrases.txt"))
    }

    @Test fun round_trips_an_empty_file() {
        val dir = Files.createTempDirectory("archive3").toFile()
        val empty = File(dir, "e").apply { writeBytes(ByteArray(0)) }
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            BackupArchive.writeFile(out, "clipboard.txt", empty)
            BackupArchive.writeEnd(out)
        }
        assertArrayEquals(ByteArray(0), readAll(bos.toByteArray()).bytes("clipboard.txt"))
    }

    @Test fun missing_end_marker_is_corrupt() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out -> BackupArchive.writePrefs(out, byteArrayOf(9)) }
        assertCorrupt { readAll(bos.toByteArray()) }
    }

    @Test fun unknown_entry_tag_is_corrupt() {
        assertCorrupt { readAll(byteArrayOf('Q'.code.toByte())) }
    }

    @Test fun allows_only_known_relative_paths() {
        for (ok in listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt", "emoji/symbol_usage.txt", "clips/AB12cd.txt")) {
            assertEquals(ok, BackupArchive.sanitizedRelativePath(ok))
        }
        for (bad in listOf(
            "../userdb.txt",
            "/etc/passwd",
            "clips/../userdb.txt",
            "clips/sub/dir.txt",
            "clips/名字.txt",
            "clips/.txt",
            "emoji/other.txt",
            "downloaded/aegis_dict.bin",
            "random.txt",
            "clips\\win.txt",
            "",
        )) {
            assertNull("must reject $bad", BackupArchive.sanitizedRelativePath(bad))
        }
    }

    private fun assertCorrupt(block: () -> Unit) {
        try {
            block()
            fail("expected BackupCorruptException")
        } catch (_: BackupCorruptException) {
        }
    }
}
