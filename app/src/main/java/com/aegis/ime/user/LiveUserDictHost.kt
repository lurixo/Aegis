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
import java.io.IOException

class LiveUserDictHost(
    private val model: UserModel,
    private val userDb: File,
    private val userLearning: UserLearning? = null,
    private val userLearnFile: File? = null,
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
        userLearning?.removeWord(word)
        save()
        return true
    }

    override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
        if (!importFile.exists() || importFile.length() == 0L) return false
        try {
            if (merge) {
                if (!model.importFrom(importFile, now)) return false
            } else {
                val incoming = UserModel().apply { load(importFile) }
                if (incoming.isEmpty()) return false
                model.reload(importFile)
            }
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: IOException) {
            return false
        }
        save()
        return true
    }

    override fun entries(): List<UserModel.Entry> = model.userWordEntries()

    override fun flush() {
        if (!model.dirty && userLearning?.dirty != true) return
        if (model.dirty) model.save(userDb)
        if (userLearning?.dirty == true) {
            userLearnFile?.let { userLearning.save(it) }
        }
        onSaved(userDb.lastModified())
    }

    private fun save() {
        model.save(userDb)
        if (userLearning?.dirty == true) {
            userLearnFile?.let { userLearning.save(it) }
        }
        onSaved(userDb.lastModified())
    }
}
