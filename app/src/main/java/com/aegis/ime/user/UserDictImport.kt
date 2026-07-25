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

package com.aegis.ime.user

import java.io.File
import java.io.InputStream

object UserDictImport {

    fun apply(importFile: File, userDb: File, merge: Boolean, now: Long): Boolean {
        if (!importFile.isFile || importFile.length() !in 1..UserModel.MAX_FILE_BYTES) return false
        return runCatching {
            if (merge) {
                val target = UserModel().apply { if (userDb.exists()) load(userDb) }
                if (!target.importFrom(importFile, now)) return false
                target.save(userDb)
                true
            } else {
                val incoming = UserModel().apply { load(importFile) }
                if (incoming.isEmpty()) return false
                incoming.save(userDb)
                true
            }
        }.getOrDefault(false)
    }

    fun stage(input: InputStream, file: File): Boolean {
        file.delete()
        return runCatching {
            var total = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            file.outputStream().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > UserModel.MAX_FILE_BYTES) throw IllegalArgumentException("userdb exceeds size limit")
                    output.write(buffer, 0, read)
                }
            }
            total > 0L
        }.getOrElse {
            file.delete()
            false
        }
    }
}
