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
 * Manual edits to the learning dictionary from the settings screen, against the SAME userdb.txt the IME
 * reads (and that import/export share). Every operation dispatches through [UserDictHot]: while the IME
 * service is running (the settings Activity shares its process) the edit goes straight into the LIVE
 * [UserModel] the decoder is using — visible on the next keystroke — and is persisted in the same call.
 * When no live host is registered the operation falls back to the file (load → apply → save), which the
 * IME reconciles via its mtime check on the next onStartInput.
 */
object UserDictEdit {

    /** Add [word] under optional pinyin [reading] (letters). Returns false when the word is blank. */
    fun add(userDb: File, word: String, reading: String, now: Long): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.addWord(reading, word, now) }
        val m = UserModel().apply { if (userDb.exists()) load(userDb) }
        m.addManualWord(reading, word, now)
        m.save(userDb)
        return true
    }

    /** Remove [word] under [reading] only (its boost/predictions go when no reading recalls it any more).
     *  Returns false when the word is blank. */
    fun remove(userDb: File, reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.removeWord(reading, word) }
        val m = UserModel().apply { if (userDb.exists()) load(userDb) }
        m.removeWord(reading, word)
        m.save(userDb)
        return true
    }

    /** Apply an import file (merge or overwrite). Live host when registered, else [UserDictImport.apply];
     *  both validate the import and never wipe the dictionary on a junk file. */
    fun applyImport(userDb: File, importFile: File, merge: Boolean, now: Long): Boolean {
        UserDictHot.host?.let { return it.importUserDict(importFile, merge, now) }
        return UserDictImport.apply(importFile, userDb, merge, now)
    }

    /** Persist any unsaved live learning so [userDb] is current before it is copied out (export). */
    fun flushBeforeExport() {
        UserDictHot.host?.flush()
    }

    /** The current custom (recall) words, most-used first — the settings list model. */
    fun list(userDb: File): List<UserModel.Entry> {
        UserDictHot.host?.let { return it.entries() }
        return UserModel().apply { if (userDb.exists()) load(userDb) }.userWordEntries()
    }
}
