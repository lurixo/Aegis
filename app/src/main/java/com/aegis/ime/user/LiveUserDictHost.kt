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

class LiveUserDictHost internal constructor(
    private val model: UserModel,
    private val userDb: File,
    private val userLearning: UserLearning? = null,
    private val userLearnFile: File? = null,
    private val database: UserDataDatabase? = null,
    private val onSaved: (mtime: Long) -> Unit = {},
) : UserDictHot.Host {

    override fun addWord(reading: String, word: String, now: Long): Boolean {
        if (word.isBlank()) return false
        if (!model.addManualWord(reading, word, now)) return false
        save()
        return true
    }

    override fun removeWord(reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        database?.let { backing ->
            if (!backing.removeUserReadingAndLearning(reading, word)) return false
            model.reloadFromStorage()
            userLearning?.reloadFromStorage()
            notifyDatabaseSaved()
            return true
        }
        if (!model.removeWord(reading, word)) return false
        userLearning?.removeWord(word)
        save()
        return true
    }

    override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
        if (!importFile.exists() || importFile.length() == 0L) return false
        try {
            if (merge) {
                if (!model.importFrom(importFile, now)) return false
            } else if (database != null) {
                model.reload(importFile)
            } else {
                val incoming = UserModel().apply { load(importFile) }
                if (incoming.isEmpty()) return false
                model.replaceFromStorage(incoming.storageSnapshot())
            }
        } catch (_: Exception) {
            return false
        }
        save()
        return true
    }

    override fun entries(): List<UserModel.Entry> = model.entryPage("", 0, UserModel.RUNTIME_PAGE_SIZE)

    override fun entryCount(query: String): Long = model.entryCount(query)

    override fun entryPage(query: String, offset: Int, limit: Int): List<UserModel.Entry> =
        model.entryPage(query, offset, limit)

    override fun flush() {
        if (database != null) {
            notifyDatabaseSaved()
            return
        }
        if (!model.dirty && userLearning?.dirty != true) return
        if (model.dirty) model.save(userDb)
        if (userLearning?.dirty == true) {
            userLearnFile?.let { userLearning.save(it) }
        }
        onSaved(userDb.lastModified())
    }

    private fun save() {
        if (database != null) {
            notifyDatabaseSaved()
            return
        }
        model.save(userDb)
        if (userLearning?.dirty == true) {
            userLearnFile?.let { userLearning.save(it) }
        }
        onSaved(userDb.lastModified())
    }

    private fun notifyDatabaseSaved() {
        runCatching {
            onSaved(File(userDb.absoluteFile.parentFile, UserDataDatabase.DATABASE_NAME).lastModified())
        }
    }
}
