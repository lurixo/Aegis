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
    private val onSaved: (userDbMtime: Long?, userLearnMtime: Long?) -> Unit = { _, _ -> },
) : UserDictHot.Host {

    override fun addWord(reading: String, word: String, now: Long): Boolean {
        if (word.isBlank()) return false
        model.addManualWord(reading, word, now)
        return save()
    }

    override fun removeWord(reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        model.removeWord(reading, word)
        userLearning?.removeWord(word)
        return save()
    }

    override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
        if (!importFile.exists() || importFile.length() == 0L) return false
        try {
            if (merge) {
                if (!model.importFrom(importFile, now)) return false
            } else {
                val incoming = UserModel().apply { load(importFile, sweepStale = false) }
                if (incoming.isEmpty()) return false
                model.reload(importFile)
            }
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: IOException) {
            return false
        }
        return save()
    }

    override fun entries(): List<UserModel.Entry> = model.userWordEntries()

    override fun forgottenCount(): Int = model.forgottenCount

    override fun learnedEntries(): List<UserLearning.Formed> = userLearning?.formedEntries().orEmpty()

    override fun hasLearnedData(): Boolean = userLearning?.isEmpty() == false

    override fun removeLearned(word: String, reading: String): Boolean {
        userLearning?.removeFormed(word, reading)
        return saveLearning()
    }

    override fun clearLearned(): Boolean {
        userLearning?.clear()
        return saveLearning()
    }

    override fun flush(): Boolean {
        if (!model.dirty && userLearning?.dirty != true) return true
        return persist(writeUserDb = model.dirty)
    }

    private fun save(): Boolean = persist(writeUserDb = true)

    private fun saveLearning(): Boolean = persist(writeUserDb = false)

    private fun persist(writeUserDb: Boolean): Boolean {
        var savedUserDbMtime: Long? = null
        var savedUserLearnMtime: Long? = null
        val userDbWritten = !writeUserDb || runCatching {
            model.save(userDb)
            savedUserDbMtime = userDb.lastModified()
        }.isSuccess
        val learningWritten = runCatching { savedUserLearnMtime = saveDirtyLearning() }.isSuccess
        if (savedUserDbMtime != null || savedUserLearnMtime != null) {
            onSaved(savedUserDbMtime, savedUserLearnMtime)
        }
        return userDbWritten && learningWritten
    }

    private fun saveDirtyLearning(): Long? {
        val learning = userLearning ?: return null
        val file = userLearnFile ?: return null
        if (!learning.dirty) return null
        learning.save(file)
        return file.lastModified()
    }
}
