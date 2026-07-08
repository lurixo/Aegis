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

package com.aegis.ime.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupDefaultPasswordStoreTest {

    @Test fun stores_ciphertext_not_plaintext_and_round_trips() {
        val prefs = prefs()
        val store = SharedPrefsBackupDefaultPasswordStore(prefs, ReversingCipher())

        store.save("secret1")

        assertTrue(store.hasPassword())
        assertEquals("secret1", store.read())
        val storedValues = prefs.all.values.joinToString("|") { it.toString() }
        assertFalse(storedValues.contains("secret1"))
    }

    @Test fun clear_removes_saved_state_and_key_material() {
        val prefs = prefs()
        val cipher = ReversingCipher()
        val store = SharedPrefsBackupDefaultPasswordStore(prefs, cipher)

        store.save("secret1")
        store.clear()

        assertFalse(store.hasPassword())
        assertTrue(prefs.all.isEmpty())
        assertTrue(cipher.cleared)
    }

    private fun prefs() =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("backup_default_password_store_test", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
}

private class ReversingCipher : BackupPasswordCipher {
    var cleared = false

    override fun prepare() = Unit

    override fun encrypt(plain: ByteArray): BackupPasswordCiphertext =
        BackupPasswordCiphertext(byteArrayOf(1, 2, 3), plain.reversedArray())

    override fun decrypt(encrypted: BackupPasswordCiphertext): ByteArray =
        encrypted.ciphertext.reversedArray()

    override fun clear() {
        cleared = true
    }
}
