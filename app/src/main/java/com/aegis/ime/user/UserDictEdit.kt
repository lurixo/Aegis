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
            val added = m.addManualWord(reading, word, now)
            if (added) m.save(userDb)
            added
        }.getOrDefault(false)
    }

    fun remove(userDb: File, reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.removeWord(reading, word) }
        return runCatching {
            val userLearn = File(userDb.absoluteFile.parentFile, "userlearn.txt")
            val learning = UserLearning().apply { if (userLearn.exists()) load(userLearn) }
            if (!learning.readable) return false
            val m = UserModel().apply { if (userDb.exists()) load(userDb) }
            m.removeWord(reading, word)
            m.save(userDb)
            learning.removeWord(word)
            if (!learning.dirty) return true
            if (runCatching { learning.save(userLearn) }.isSuccess) return true
            m.addTombstone(word, "") && runCatching { m.save(userDb) }.isSuccess
        }.getOrDefault(false)
    }

    fun removeAll(userDb: File, entries: List<UserModel.Entry>): Boolean {
        if (entries.isEmpty()) return true
        UserDictHot.host?.let { host ->
            return entries.map { host.removeWord(it.reading, it.word) }.all { it }
        }
        return runCatching {
            val userLearn = File(userDb.absoluteFile.parentFile, "userlearn.txt")
            val learning = UserLearning().apply { if (userLearn.exists()) load(userLearn) }
            if (!learning.readable) return false
            val m = UserModel().apply { if (userDb.exists()) load(userDb) }
            for (entry in entries) m.removeWord(entry.reading, entry.word)
            m.save(userDb)
            for (entry in entries) learning.removeWord(entry.word)
            if (!learning.dirty) return true
            if (runCatching { learning.save(userLearn) }.isSuccess) return true
            entries.map { m.addTombstone(it.word, "") }.all { it } &&
                runCatching { m.save(userDb) }.isSuccess
        }.getOrDefault(false)
    }

    fun list(userDb: File): List<UserModel.Entry> {
        UserDictHot.host?.let { return it.entries() }
        return UserModel().apply { if (userDb.exists()) load(userDb) }.userWordEntries()
    }

    class Summary(
        val entries: List<UserModel.Entry>,
        val words: Int,
        val forgotten: Int,
        val readable: Boolean = true,
    )

    fun summary(userDb: File): Summary {
        UserDictHot.host?.let { host ->
            val fallback = if (host.wordCount() == null || host.forgottenCount() == null) {
                fromFile(userDb)
            } else {
                null
            }
            return Summary(
                host.entries(),
                host.wordCount() ?: fallback?.distinctWordCount() ?: 0,
                host.forgottenCount() ?: fallback?.forgottenCount ?: 0,
                host.dictionaryReadable(),
            )
        }
        val m = UserModel()
        val read = runCatching { if (userDb.exists()) m.load(userDb) }.isSuccess
        if (!read) return Summary(emptyList(), 0, 0, readable = false)
        return Summary(m.userWordEntries(), m.distinctWordCount(), m.forgottenCount)
    }

    private fun fromFile(userDb: File): UserModel? {
        if (!userDb.exists()) return null
        return runCatching { UserModel().apply { load(userDb) } }.getOrNull()
    }
}
