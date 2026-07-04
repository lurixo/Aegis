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

object UserLearnEdit {

    fun list(userLearn: File): List<UserLearning.Formed> {
        UserDictHot.host?.let { return it.learnedEntries() }
        return loaded(userLearn).formedEntries()
    }

    fun hasData(userLearn: File): Boolean {
        UserDictHot.host?.let { return it.hasLearnedData() }
        return !loaded(userLearn).isEmpty()
    }

    class View(val entries: List<UserLearning.Formed>, val hasData: Boolean, val readable: Boolean = true)

    fun view(userLearn: File): View {
        UserDictHot.host?.let { return View(it.learnedEntries(), it.hasLearnedData(), it.learnedReadable()) }
        val learning = UserLearning()
        val read = runCatching { if (userLearn.exists()) learning.load(userLearn) }.isSuccess
        if (!read || !learning.readable) return View(emptyList(), false, readable = false)
        return View(learning.formedEntries(), !learning.isEmpty())
    }

    fun remove(userLearn: File, word: String, reading: String): Boolean {
        UserDictHot.host?.let { return it.removeLearned(word, reading) }
        return runCatching {
            val learning = loaded(userLearn)
            if (!learning.readable) return false
            learning.removeFormed(word, reading)
            if (!learning.dirty) return true
            if (runCatching { learning.save(userLearn) }.isSuccess) return true
            owe(File(userLearn.absoluteFile.parentFile, "userdb.txt"), word, reading)
            false
        }.getOrDefault(false)
    }

    fun removeAll(userLearn: File, entries: List<UserLearning.Formed>): Boolean {
        if (entries.isEmpty()) return true
        UserDictHot.host?.let { host ->
            return entries.map { host.removeLearned(it.word, it.reading) }.all { it }
        }
        return runCatching {
            val learning = loaded(userLearn)
            if (!learning.readable) return false
            for (entry in entries) learning.removeFormed(entry.word, entry.reading)
            if (!learning.dirty) return true
            if (runCatching { learning.save(userLearn) }.isSuccess) return true
            oweAll(File(userLearn.absoluteFile.parentFile, "userdb.txt"), entries)
            false
        }.getOrDefault(false)
    }

    private fun owe(userDb: File, word: String, reading: String): Boolean = runCatching {
        val m = UserModel().apply { if (userDb.exists()) load(userDb, sweepStale = false) }
        m.addTombstone(word, reading) && runCatching { m.save(userDb) }.isSuccess
    }.getOrDefault(false)

    private fun oweAll(userDb: File, entries: List<UserLearning.Formed>): Boolean = runCatching {
        val m = UserModel().apply { if (userDb.exists()) load(userDb, sweepStale = false) }
        entries.map { m.addTombstone(it.word, it.reading) }.all { it } &&
            runCatching { m.save(userDb) }.isSuccess
    }.getOrDefault(false)

    fun clear(userLearn: File): Boolean {
        UserDictHot.host?.let { return it.clearLearned() }
        return runCatching {
            val learning = loaded(userLearn)
            learning.clear()
            if (learning.dirty) learning.save(userLearn)
            forgetOwed(File(userLearn.absoluteFile.parentFile, "userdb.txt"))
        }.isSuccess
    }

    private fun forgetOwed(userDb: File) {
        if (!userDb.exists()) return
        runCatching {
            val m = UserModel().apply { load(userDb, sweepStale = false) }
            if (m.dropTombstones(m.tombstones())) m.save(userDb)
        }
    }

    private fun loaded(userLearn: File): UserLearning =
        UserLearning().apply { if (userLearn.exists()) load(userLearn) }
}
