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

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object BackupCrypto {

    private val secureRandom = SecureRandom()

    fun writeEncrypted(
        rawOut: OutputStream,
        password: CharArray,
        version: Int = BackupFormat.HEADER_VERSION,
        writePlaintext: (OutputStream) -> Unit,
    ) {
        val salt = randomBytes(BackupFormat.SALT_LEN)
        val nonce = randomBytes(BackupFormat.NONCE_LEN)
        val iterations = BackupFormat.PBKDF2_ITERATIONS
        val header = buildHeader(salt, nonce, iterations, version)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(password, salt, iterations)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BackupFormat.GCM_TAG_BITS, nonce))
        cipher.updateAAD(header)

        rawOut.write(header)
        CipherOutputStream(rawOut, cipher).use { cipherOut -> writePlaintext(cipherOut) }
    }

    fun readDecrypted(rawIn: InputStream, password: CharArray, readPlaintext: (InputStream) -> Unit) {
        val header = readAndValidateHeader(rawIn)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(password, header.salt, header.iterations)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BackupFormat.GCM_TAG_BITS, header.nonce))
        cipher.updateAAD(header.bytes)

        val verifying = GcmVerifyingInputStream(rawIn, cipher)
        readPlaintext(verifying)
        verifying.finish()
    }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, BackupFormat.AES_KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun buildHeader(salt: ByteArray, nonce: ByteArray, iterations: Int, version: Int): ByteArray {
        val h = ByteArray(BackupFormat.HEADER_LEN)
        var i = 0
        BackupFormat.MAGIC.copyInto(h, 0); i += BackupFormat.MAGIC.size
        h[i++] = version.toByte()
        h[i++] = BackupFormat.KDF_PBKDF2_HMAC_SHA256.toByte()
        h[i++] = (iterations ushr 24).toByte()
        h[i++] = (iterations ushr 16).toByte()
        h[i++] = (iterations ushr 8).toByte()
        h[i++] = iterations.toByte()
        h[i++] = salt.size.toByte()
        salt.copyInto(h, i); i += salt.size
        h[i++] = nonce.size.toByte()
        nonce.copyInto(h, i)
        return h
    }

    private class Header(val bytes: ByteArray, val salt: ByteArray, val nonce: ByteArray, val iterations: Int)

    private fun readAndValidateHeader(rawIn: InputStream): Header {
        val h = ByteArray(BackupFormat.HEADER_LEN)
        if (!readFully(rawIn, h)) throw BackupException(BackupError.NOT_A_BACKUP)
        for (i in BackupFormat.MAGIC.indices) {
            if (h[i] != BackupFormat.MAGIC[i]) throw BackupException(BackupError.NOT_A_BACKUP)
        }
        var i = BackupFormat.MAGIC.size
        val version = h[i++].toInt() and 0xFF
        if (version != BackupFormat.HEADER_VERSION && version != BackupFormat.HEADER_VERSION_CHUNKED_PREFS) {
            throw BackupException(BackupError.UNSUPPORTED_VERSION)
        }
        val kdf = h[i++].toInt() and 0xFF
        if (kdf != BackupFormat.KDF_PBKDF2_HMAC_SHA256) throw BackupException(BackupError.UNSUPPORTED_VERSION)
        val iterations = ((h[i].toInt() and 0xFF) shl 24) or ((h[i + 1].toInt() and 0xFF) shl 16) or
            ((h[i + 2].toInt() and 0xFF) shl 8) or (h[i + 3].toInt() and 0xFF)
        i += 4
        if (iterations <= 0 || iterations > BackupFormat.PBKDF2_MAX_ITERATIONS) {
            throw BackupException(BackupError.UNSUPPORTED_VERSION)
        }
        val saltLen = h[i++].toInt() and 0xFF
        if (saltLen != BackupFormat.SALT_LEN) throw BackupException(BackupError.UNSUPPORTED_VERSION)
        val salt = h.copyOfRange(i, i + saltLen); i += saltLen
        val nonceLen = h[i++].toInt() and 0xFF
        if (nonceLen != BackupFormat.NONCE_LEN) throw BackupException(BackupError.UNSUPPORTED_VERSION)
        val nonce = h.copyOfRange(i, i + nonceLen)
        return Header(h, salt, nonce, iterations)
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}

internal class GcmVerifyingInputStream(
    private val source: InputStream,
    private val cipher: Cipher,
) : InputStream() {

    private val readBuf = ByteArray(READ_CHUNK)
    private var pending: ByteArray = EMPTY
    private var pendingPos = 0
    private var sourceEof = false
    private var finalized = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pendingPos >= pending.size) {
            if (!fill()) return -1
        }
        val n = minOf(pending.size - pendingPos, len)
        System.arraycopy(pending, pendingPos, b, off, n)
        pendingPos += n
        return n
    }

    private fun fill(): Boolean {
        if (finalized) return false
        while (true) {
            if (sourceEof) {
                finalized = true
                pending = try {
                    cipher.doFinal()
                } catch (e: AEADBadTagException) {
                    throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, e)
                } catch (e: GeneralSecurityException) {
                    throw BackupException(BackupError.WRONG_PASSWORD_OR_CORRUPT, e)
                }
                pendingPos = 0
                return pending.isNotEmpty()
            }
            val n = source.read(readBuf)
            if (n < 0) {
                sourceEof = true
                continue
            }
            if (n == 0) continue
            val out = cipher.update(readBuf, 0, n)
            if (out != null && out.isNotEmpty()) {
                pending = out
                pendingPos = 0
                return true
            }
        }
    }

    fun finish() {
        while (!finalized) {
            if (!fill()) break
            pendingPos = pending.size
        }
    }

    override fun close() {}

    private companion object {
        const val READ_CHUNK = 64 * 1024
        val EMPTY = ByteArray(0)
    }
}

internal class BackupCorruptException(message: String) : IOException(message)
