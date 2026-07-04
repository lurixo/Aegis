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
 * Manual edits to the learning dictionary from the settings screen. Operates on the SAME userdb.txt the
 * IME reads (and that import/export share), loading the current file, applying one change, and saving it
 * back — so a manually added/removed word lands in the same store the automatic learning uses and takes
 * effect on the next input session (like an import). Kept apart from the live in-IME [UserModel] instance:
 * the settings UI runs while no field is focused, and the IME reloads userdb on the next onStartInput.
 */
object UserDictEdit {

    /** Add [word] under optional pinyin [reading] (letters). Returns false when the word is blank. */
    fun add(userDb: File, word: String, reading: String, now: Long): Boolean {
        if (word.isBlank()) return false
        val m = UserModel().apply { if (userDb.exists()) load(userDb) }
        m.addManualWord(reading, word, now)
        m.save(userDb)
        return true
    }

    /** Remove [word] under [reading] only (its boost/predictions go when no reading recalls it any more).
     *  Returns false when the word is blank. */
    fun remove(userDb: File, reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        val m = UserModel().apply { if (userDb.exists()) load(userDb) }
        m.removeWord(reading, word)
        m.save(userDb)
        return true
    }

    /** The current custom (recall) words, most-used first — the settings list model. */
    fun list(userDb: File): List<UserModel.Entry> =
        UserModel().apply { if (userDb.exists()) load(userDb) }.userWordEntries()
}
