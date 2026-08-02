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
import java.io.OutputStream

object UserDictEdit {

    private fun rootOf(userDb: File): File = requireNotNull(userDb.absoluteFile.parentFile)

    fun add(userDb: File, word: String, reading: String, now: Long): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.addWord(reading, word, now) }
        return runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                val model = UserModel(database = database)
                model.addManualWord(reading, word, now)
            }
        }.getOrDefault(false)
    }

    fun remove(userDb: File, reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        UserDictHot.host?.let { return it.removeWord(reading, word) }
        return runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                database.removeUserReadingAndLearning(reading, word)
            }
        }.getOrDefault(false)
    }

    fun applyImport(userDb: File, importFile: File, merge: Boolean, now: Long): Boolean {
        UserDictHot.host?.let { return it.importUserDict(importFile, merge, now) }
        if (!importFile.isFile || importFile.length() <= 0L) return false
        return runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                importFile.inputStream().use { database.importUserDictionary(it, merge) }
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

    fun export(userDb: File, output: OutputStream): Boolean = runCatching {
        UserDictHot.host?.flush()
        UserDataMigration.open(rootOf(userDb)).use { database -> database.writeUserDictionary(output) }
        true
    }.getOrDefault(false)

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

    fun pageSnapshot(
        userDb: File,
        query: String,
        offset: Int,
        limit: Int,
        expectedVersion: Long? = null,
    ): PersistedPage<UserModel.Entry> {
        UserDictHot.host?.let { return it.entryPageSnapshot(query, offset, limit, expectedVersion) }
        return runCatching {
            UserDataMigration.open(rootOf(userDb)).use { database ->
                UserModel(database = database).entryPageSnapshot(query, offset, limit, expectedVersion)
            }
        }.getOrElse { PersistedPage(emptyList(), expectedVersion ?: 0L, restartRequired = true) }
    }
}
