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

import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.UserDataMigration
import java.io.File
import java.io.OutputStream

internal object PhraseTransferIo {
    fun exportPhrases(filesDir: File, openOutput: () -> OutputStream?): Boolean = runCatching {
        val bytes = UserDataMigration.open(filesDir).use { database ->
            ClipboardStore(filesDir, database).also { it.load() }
                .exportPhrasesText()
                .toByteArray(Charsets.UTF_8)
        }
        if (bytes.isEmpty()) return@runCatching false
        val output = openOutput() ?: return@runCatching false
        output.use {
            it.write(bytes)
            it.flush()
        }
        true
    }.getOrDefault(false)
}
