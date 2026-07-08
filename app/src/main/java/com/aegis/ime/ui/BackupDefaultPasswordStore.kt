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

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit

internal interface BackupDefaultPasswordStore {
    fun hasPassword(): Boolean
    fun save(password: String)
    fun read(): String?
    fun clear()
}

internal class SharedPrefsBackupDefaultPasswordStore(
    private val prefs: SharedPreferences,
    private val cipher: BackupPasswordCipher,
) : BackupDefaultPasswordStore {
    override fun hasPassword(): Boolean =
        prefs.getInt(KEY_VERSION, 0) == STORE_VERSION &&
            prefs.getString(KEY_IV, null) != null &&
            prefs.getString(KEY_CIPHERTEXT, null) != null

    override fun save(password: String) {
        val plain = password.encodeToByteArray()
        val encrypted = try {
            cipher.encrypt(plain)
        } finally {
            plain.fill(0)
        }
        prefs.edit {
            putInt(KEY_VERSION, STORE_VERSION)
            putString(KEY_IV, encrypted.iv.base64())
            putString(KEY_CIPHERTEXT, encrypted.ciphertext.base64())
        }
    }

    override fun read(): String? {
        if (!hasPassword()) return null
        val iv = prefs.getString(KEY_IV, null)?.base64Bytes() ?: return null
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null)?.base64Bytes() ?: return null
        val plain = cipher.decrypt(BackupPasswordCiphertext(iv, ciphertext))
        return try {
            plain.toString(Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    override fun clear() {
        prefs.edit { clear() }
        cipher.clear()
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.base64Bytes(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}

internal interface BackupPasswordCipher {
    fun encrypt(plain: ByteArray): BackupPasswordCiphertext
    fun decrypt(encrypted: BackupPasswordCiphertext): ByteArray
    fun clear()
}

internal data class BackupPasswordCiphertext(val iv: ByteArray, val ciphertext: ByteArray)

private const val STORE_VERSION = 1
private const val KEY_VERSION = "version"
private const val KEY_IV = "iv"
private const val KEY_CIPHERTEXT = "ciphertext"
