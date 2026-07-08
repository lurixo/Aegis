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
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface BackupDefaultPasswordStore {
    fun hasPassword(): Boolean
    fun prepareForAuth()
    fun save(password: String)
    fun read(): String?
    fun clear()
}

internal fun backupDefaultPasswordStore(context: Context): BackupDefaultPasswordStore =
    SharedPrefsBackupDefaultPasswordStore(
        context.getSharedPreferences(BACKUP_DEFAULT_PASSWORD_PREFS, Context.MODE_PRIVATE),
        AndroidKeystoreBackupPasswordCipher(),
    )

internal class SharedPrefsBackupDefaultPasswordStore(
    private val prefs: SharedPreferences,
    private val cipher: BackupPasswordCipher,
) : BackupDefaultPasswordStore {
    override fun hasPassword(): Boolean =
        prefs.getInt(KEY_VERSION, 0) == STORE_VERSION &&
            prefs.getString(KEY_IV, null) != null &&
            prefs.getString(KEY_CIPHERTEXT, null) != null

    override fun prepareForAuth() {
        cipher.prepare()
    }

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
    fun prepare()
    fun encrypt(plain: ByteArray): BackupPasswordCiphertext
    fun decrypt(encrypted: BackupPasswordCiphertext): ByteArray
    fun clear()
}

internal data class BackupPasswordCiphertext(val iv: ByteArray, val ciphertext: ByteArray)

internal class AndroidKeystoreBackupPasswordCipher : BackupPasswordCipher {
    override fun prepare() {
        key()
    }

    override fun encrypt(plain: ByteArray): BackupPasswordCiphertext {
        val cipher = Cipher.getInstance(KEY_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return BackupPasswordCiphertext(cipher.iv, cipher.doFinal(plain))
    }

    override fun decrypt(encrypted: BackupPasswordCiphertext): ByteArray {
        val cipher = Cipher.getInstance(KEY_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, encrypted.iv))
        return cipher.doFinal(encrypted.ciphertext)
    }

    override fun clear() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                KEY_AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}

private const val BACKUP_DEFAULT_PASSWORD_PREFS = "aegis_backup_default_password"
private const val STORE_VERSION = 1
private const val KEY_VERSION = "version"
private const val KEY_IV = "iv"
private const val KEY_CIPHERTEXT = "ciphertext"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "aegis_backup_default_password_v1"
private const val KEY_TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_SIZE_BITS = 256
private const val GCM_TAG_BITS = 128
private const val KEY_AUTH_VALIDITY_SECONDS = 30
