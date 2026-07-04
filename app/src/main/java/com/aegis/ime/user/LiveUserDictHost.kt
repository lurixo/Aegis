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
 * [UserDictHot.Host] over the IME service's live [UserModel]: settings-page edits mutate the model the
 * decoder is reading (version bump → recall index rebuild on the next keystroke) and are saved to
 * [userDb] in the same call, so file and memory stay in step.
 *
 * The dirty case is the whole point: while the user is typing, the live model can hold unsaved learning
 * (deltas since the last save). A file-level edit plus reload would drop one side or the other — either
 * the reload discards the unsaved learning, or the next save clobbers the file edit. Mutating the live
 * model instead keeps both: the edit and the learning are in the same model when it is saved here.
 *
 * Import semantics mirror the file-based [UserDictImport.apply] (missing/empty file → false, nothing
 * changed; overwrite additionally rejects a file with no valid entries):
 *  - merge: [UserModel.importFrom] the RAW import file into the live model — unsaved learning survives.
 *  - overwrite: replace the live model's contents from the import file. Dropping unsaved learning here is
 *    the user's explicit intent ("overwrite replaces the learning dictionary").
 *
 * [onSaved] reports the post-save userdb mtime so the service can keep its reload watermark current
 * (otherwise its next onStartInput would see a newer mtime and reload redundantly).
 */
class LiveUserDictHost(
    private val model: UserModel,
    private val userDb: File,
    private val onSaved: (mtime: Long) -> Unit = {},
) : UserDictHot.Host {

    override fun addWord(reading: String, word: String, now: Long): Boolean {
        if (word.isBlank()) return false
        model.addManualWord(reading, word, now)
        save()
        return true
    }

    override fun removeWord(reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        model.removeWord(reading, word)
        save()
        return true
    }

    override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
        if (!importFile.exists() || importFile.length() == 0L) return false
        if (merge) {
            model.importFrom(importFile, now)
        } else {
            val incoming = UserModel().apply { load(importFile) }
            if (incoming.isEmpty()) return false // junk file must never wipe the live dictionary
            model.reload(importFile)
        }
        save()
        return true
    }

    override fun entries(): List<UserModel.Entry> = model.userWordEntries()

    override fun flush() {
        if (model.dirty) save()
    }

    private fun save() {
        model.save(userDb)
        onSaved(userDb.lastModified())
    }
}
