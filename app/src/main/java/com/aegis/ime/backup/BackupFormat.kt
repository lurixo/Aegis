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

internal object BackupFormat {
    val MAGIC: ByteArray = byteArrayOf(0x41, 0x45, 0x47, 0x49, 0x53, 0x42, 0x4B, 0x31)

    const val HEADER_VERSION = 1

    const val HEADER_VERSION_CHUNKED_PREFS = 2

    const val KDF_PBKDF2_HMAC_SHA256 = 1

    const val PBKDF2_ITERATIONS = 600_000

    const val PBKDF2_MAX_ITERATIONS = 10_000_000

    const val SALT_LEN = 16
    const val NONCE_LEN = 12
    const val GCM_TAG_BITS = 128
    const val AES_KEY_BITS = 256

    const val HEADER_LEN = 8 + 1 + 1 + 4 + 1 + SALT_LEN + 1 + NONCE_LEN
}

internal enum class BackupError {
    NOT_A_BACKUP,

    UNSUPPORTED_VERSION,

    WRONG_PASSWORD_OR_CORRUPT,

    IO_ERROR,
}

internal class BackupException(
    val error: BackupError,
    cause: Throwable? = null,
) : Exception(error.name, cause)
