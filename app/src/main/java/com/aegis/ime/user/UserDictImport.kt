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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object UserDictImport {

    fun stage(input: InputStream, file: File): Boolean {
        val staged = File(file.parentFile, file.name + ".tmp")
        staged.delete()
        return runCatching {
            var total = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            staged.outputStream().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    output.write(buffer, 0, read)
                }
            }
            if (total <= 0L) return@runCatching false
            try {
                Files.move(
                    staged.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            total > 0L
        }.getOrElse {
            staged.delete()
            false
        }
    }
}
