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

import com.aegis.ime.user.LiveUserData
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal object PhraseTransferIo {
    fun exportPhrases(filesDir: File, openOutput: () -> OutputStream?): Result<Boolean> = runCatching {
        val bytes = LiveUserData.withClipboardStore(filesDir) { it.exportPhrasesText() }
            .toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return@runCatching false
        val output = openOutput() ?: throw IOException("the picked file could not be opened for writing")
        output.use {
            it.write(bytes)
            it.flush()
        }
        true
    }
}
