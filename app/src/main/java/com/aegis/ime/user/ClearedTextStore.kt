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
import java.util.concurrent.atomic.AtomicLong

class ClearedTextStore(dir: File) {

    private val file = File(dir, "cleared_text.txt")
    private val tmpTag = TMP_TAGS.incrementAndGet()
    private var kept: CharSequence? = null

    fun keep(text: CharSequence) {
        if (text.isEmpty()) return
        kept = text
        runCatching { AtomicFileSwap.write(file, tmpTag, text.toString()) }
    }

    fun held(): CharSequence? = kept ?: runCatching {
        if (file.isFile) file.readText().ifEmpty { null } else null
    }.getOrNull()

    fun forget() {
        kept = null
        runCatching { file.delete() }
    }

    private companion object {
        val TMP_TAGS = AtomicLong(0)
    }
}
