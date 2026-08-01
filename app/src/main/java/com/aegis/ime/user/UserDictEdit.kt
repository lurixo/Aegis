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

    private fun rootOf(userDb: File): File = requireNotNull(userDb.absoluteFile.parentFile)

    fun add(userDb: File, word: String, reading: String, now: Long): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.addWord(reading, word, now) }
        return runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                val model = UserModel(database = database)
                model.addManualWord(reading, word, now).also { if (it) database.checkpointLastGood() }
            }
        }.getOrDefault(false)
    }

    fun remove(userDb: File, reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.removeWord(reading, word) }
        return runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                val model = UserModel(database = database)
                if (!model.removeWord(reading, word)) return@use false
                UserLearning(database = database).removeWord(word)
                database.checkpointLastGood()
                true
            }
        }.getOrDefault(false)
    }

    fun applyImport(userDb: File, importFile: File, merge: Boolean, now: Long): Boolean {
        UserDictHot.host?.let { return it.importUserDict(importFile, merge, now) }
        if (!importFile.isFile || importFile.length() <= 0L) return false
        return runCatching {
            val incoming = UserModel().apply { load(importFile) }
            if (incoming.isEmpty()) return@runCatching false
            UserDataMigration.open(rootOf(userDb)).use { database ->
                val model = UserModel(database = database)
                val applied = if (merge) model.importFrom(importFile, now)
                else {
                    model.replaceFromStorage(incoming.storageSnapshot())
                    true
                }
                if (applied) database.checkpointLastGood()
                applied
            }
        }.getOrDefault(false)
    }

    fun flushBeforeExport(userDb: File? = null) {
        UserDictHot.host?.let {
            it.flush()
            return
        }
        if (userDb == null) return
        runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                UserModel(database = database).save(userDb)
            }
        }
    }

    fun list(userDb: File): List<UserModel.Entry> {
        UserDictHot.host?.let { return it.entries() }
        return page(userDb, "", 0, UserModel.RUNTIME_PAGE_SIZE)
    }

    fun count(userDb: File, query: String): Long {
        UserDictHot.host?.let { return it.entryCount(query) }
        return runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                UserModel(database = database).entryCount(query)
            }
        }.getOrDefault(0L)
    }

    fun page(userDb: File, query: String, offset: Int, limit: Int): List<UserModel.Entry> {
        UserDictHot.host?.let { return it.entryPage(query, offset, limit) }
        return runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                UserModel(database = database).entryPage(query, offset, limit)
            }
        }.getOrDefault(emptyList())
    }
}
