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

object UserDictEdit {

    fun add(userDb: File, word: String, reading: String, now: Long): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.addWord(reading, word, now) }
        return runCatching {
            val m = UserModel().apply { if (userDb.exists()) load(userDb) }
            m.addManualWord(reading, word, now)
            m.save(userDb)
        }.isSuccess
    }

    fun remove(userDb: File, reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.removeWord(reading, word) }
        return runCatching {
            val m = UserModel().apply { if (userDb.exists()) load(userDb) }
            m.removeWord(reading, word)
            m.save(userDb)
            val userLearn = File(userDb.absoluteFile.parentFile, "userlearn.txt")
            val learning = UserLearning().apply { if (userLearn.exists()) load(userLearn) }
            learning.removeWord(word)
            if (learning.dirty) learning.save(userLearn)
        }.isSuccess
    }

    fun applyImport(userDb: File, importFile: File, merge: Boolean, now: Long): Boolean {
        UserDictHot.host?.let { return it.importUserDict(importFile, merge, now) }
        return UserDictImport.apply(importFile, userDb, merge, now)
    }

    fun flushBeforeExport(): Boolean = UserDictHot.host?.flush() ?: true

    fun list(userDb: File): List<UserModel.Entry> {
        UserDictHot.host?.let { return it.entries() }
        return UserModel().apply { if (userDb.exists()) load(userDb) }.userWordEntries()
    }

    class Summary(val entries: List<UserModel.Entry>, val forgotten: Int)

    fun summary(userDb: File): Summary {
        UserDictHot.host?.let { host ->
            return Summary(host.entries(), host.forgottenCount() ?: forgottenFromFile(userDb))
        }
        val m = UserModel().apply { if (userDb.exists()) load(userDb) }
        return Summary(m.userWordEntries(), m.forgottenCount)
    }

    private fun forgottenFromFile(userDb: File): Int {
        if (!userDb.exists()) return 0
        return runCatching { UserModel().apply { load(userDb) }.forgottenCount }.getOrDefault(0)
    }
}
