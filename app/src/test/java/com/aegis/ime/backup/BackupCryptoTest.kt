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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupCryptoTest {

    private fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        val bos = ByteArrayOutputStream()
        BackupCrypto.writeEncrypted(bos, password.toCharArray()) { it.write(plaintext) }
        return bos.toByteArray()
    }

    private fun decrypt(file: ByteArray, password: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.readDecrypted(ByteArrayInputStream(file), password.toCharArray()) { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun expectError(expected: BackupError, block: () -> Unit) {
        try {
            block()
            fail("expected BackupException($expected)")
        } catch (e: BackupException) {
            assertEquals(expected, e.error)
        }
    }

    @Test fun round_trips_small_payload() {
        val plaintext = "hello 世界 — backup".toByteArray()
        assertArrayEquals(plaintext, decrypt(encrypt(plaintext, "correct horse"), "correct horse"))
    }

    @Test fun round_trips_multi_megabyte_payload() {
        val plaintext = ByteArray(3 * 1024 * 1024) { (it * 31 + 7).toByte() }
        assertArrayEquals(plaintext, decrypt(encrypt(plaintext, "pass1234"), "pass1234"))
    }

    @Test fun starts_with_the_magic_marker() {
        val file = encrypt("x".toByteArray(), "pass1234")
        assertArrayEquals(BackupFormat.MAGIC, file.copyOfRange(0, BackupFormat.MAGIC.size))
    }

    @Test fun each_export_uses_a_fresh_salt_and_nonce() {
        val a = encrypt("same".toByteArray(), "pass1234")
        val b = encrypt("same".toByteArray(), "pass1234")
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals("same".toByteArray(), decrypt(a, "pass1234"))
        assertArrayEquals("same".toByteArray(), decrypt(b, "pass1234"))
    }

    @Test fun wrong_password_is_rejected() {
        val file = encrypt("secret data".toByteArray(), "right-password")
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) { decrypt(file, "wrong-password") }
    }

    @Test fun a_flipped_ciphertext_byte_is_rejected() {
        val file = encrypt("secret data".toByteArray(), "pass1234")
        file[file.size - 1] = (file[file.size - 1].toInt() xor 0x01).toByte()
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) { decrypt(file, "pass1234") }
    }

    @Test fun a_tampered_header_field_is_rejected() {
        val file = encrypt("secret".toByteArray(), "pass1234")
        val saltOffset = BackupFormat.MAGIC.size + 1 + 1 + 4 + 1
        file[saltOffset] = (file[saltOffset].toInt() xor 0x01).toByte()
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) { decrypt(file, "pass1234") }
    }

    @Test fun a_truncated_file_is_rejected() {
        val file = encrypt(ByteArray(4096) { it.toByte() }, "pass1234")
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) { decrypt(file.copyOfRange(0, file.size - 20), "pass1234") }
    }

    @Test fun a_non_backup_file_is_reported_as_such() {
        expectError(BackupError.NOT_A_BACKUP) { decrypt(ByteArray(0), "pass1234") }
        expectError(BackupError.NOT_A_BACKUP) { decrypt("this is a plain text file, not a backup".toByteArray(), "pass1234") }
    }

    @Test fun an_unsupported_container_version_is_reported() {
        val file = encrypt("x".toByteArray(), "pass1234")
        file[BackupFormat.MAGIC.size] = 99
        expectError(BackupError.UNSUPPORTED_VERSION) { decrypt(file, "pass1234") }
    }

    @Test fun versionOneIsRejectedBeforeAnyArchiveRestore() {
        val file = encrypt("legacy".toByteArray(), "pass1234")
        file[BackupFormat.MAGIC.size] = 1
        expectError(BackupError.UNSUPPORTED_VERSION) { decrypt(file, "pass1234") }
    }

    @Test fun an_unknown_kdf_id_is_reported() {
        val file = encrypt("x".toByteArray(), "pass1234")
        file[BackupFormat.MAGIC.size + 1] = 42
        expectError(BackupError.UNSUPPORTED_VERSION) { decrypt(file, "pass1234") }
    }

    @Test fun a_wrong_salt_length_is_reported() {
        val file = encrypt("x".toByteArray(), "pass1234")
        val saltLenOffset = BackupFormat.MAGIC.size + 1 + 1 + 4
        file[saltLenOffset] = 8
        expectError(BackupError.UNSUPPORTED_VERSION) { decrypt(file, "pass1234") }
    }

    @Test fun a_non_positive_iteration_count_is_reported() {
        val file = encrypt("x".toByteArray(), "pass1234")
        writeIterations(file, 0)
        expectError(BackupError.UNSUPPORTED_VERSION) { decrypt(file, "pass1234") }
    }

    @Test fun an_absurd_iteration_count_is_reported_before_key_derivation() {
        val file = encrypt("x".toByteArray(), "pass1234")
        writeIterations(file, Int.MAX_VALUE)
        expectError(BackupError.UNSUPPORTED_VERSION) { decrypt(file, "pass1234") }
    }

    private fun writeIterations(file: ByteArray, value: Int) {
        val off = BackupFormat.MAGIC.size + 1 + 1
        file[off] = (value ushr 24).toByte()
        file[off + 1] = (value ushr 16).toByte()
        file[off + 2] = (value ushr 8).toByte()
        file[off + 3] = value.toByte()
    }

    @Test fun the_ciphertext_leaks_neither_plaintext_nor_password() {
        val marker = "TOP-SECRET-MARKER-9137"
        val password = "MyPassphrase!42"
        val file = encrypt("data with $marker inside".toByteArray(), password)
        val haystack = String(file, Charsets.ISO_8859_1)
        assertFalse("plaintext must not appear in the encrypted file", haystack.contains(marker))
        assertFalse("password must not appear in the encrypted file", haystack.contains(password))
    }

    @Test fun round_trips_an_empty_payload_and_still_authenticates() {
        val file = encrypt(ByteArray(0), "pass1234")
        assertArrayEquals(ByteArray(0), decrypt(file, "pass1234"))
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) { decrypt(file, "wrong-one") }
    }
}
