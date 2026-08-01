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
import com.aegis.ime.user.UserDataMigration
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal object PhraseTransferIo {
    fun exportPhrases(
        filesDir: File,
        preferences: SharedPreferences? = null,
        openOutput: () -> OutputStream?,
    ): Boolean = runCatching {
        val output = openOutput() ?: return@runCatching false
        output.use {
            UserDataMigration.open(filesDir, preferences).use { database -> database.writePhrases(it) }
            it.flush()
        }
        true
    }.getOrDefault(false)

    fun importPhrases(
        filesDir: File,
        preferences: SharedPreferences?,
        input: InputStream,
        merge: Boolean,
    ): Boolean = runCatching {
        UserDataMigration.open(filesDir, preferences).use { database ->
            database.importPhrases(input, merge)
        }
    }.getOrDefault(false)
}
