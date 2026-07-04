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

/**
 * In-process bridge that lets the settings UI edit the LIVE user dictionary the IME is decoding with,
 * instead of only rewriting userdb.txt for the next onStartInput to pick up.
 *
 * The settings Activity and the IME service run in the same process, so when the service is up and its
 * initial userdb load has finished it registers a [Host] backed by its live [UserModel]. Every settings-page
 * edit (manual add/delete, import) then mutates that live model directly — the model bumps its version, the
 * decoder rebuilds its recall index on the next keystroke, and the change is visible without waiting for a
 * field refocus. Each mutation is saved to userdb.txt immediately, so the file and the model never diverge.
 *
 * When no host is registered (IME not running, or its initial load still in flight) the callers fall back
 * to the file-based [UserDictEdit]/[UserDictImport] path, which the service reconciles via its existing
 * mtime check on the next onStartInput. That fallback cannot be immediate — there is no live engine to
 * update — so nothing is lost.
 */
object UserDictHot {

    /** Live user-dictionary operations, implemented by the IME service over its in-use [UserModel]. */
    interface Host {
        /** Add [word] under pinyin [reading] to the live model and persist. */
        fun addWord(reading: String, word: String, now: Long): Boolean

        /** Remove [word] under [reading] from the live model and persist. */
        fun removeWord(reading: String, word: String): Boolean

        /** Apply an import file to the live model (merge or overwrite) and persist. False = invalid import,
         *  nothing changed. */
        fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean

        /** The live recall entries (settings list model). */
        fun entries(): List<UserModel.Entry>

        /** Persist any unsaved in-memory learning so the on-disk file is current (used before export). */
        fun flush()
    }

    /** The registered live host, or null when the IME service is not serving one. */
    @Volatile
    var host: Host? = null
}
