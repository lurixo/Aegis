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

internal object UserDataMigration {

    fun open(root: File): UserDataDatabase {
        val database = UserDataDatabase.open(root)
        val userDb = File(root, "userdb.txt")
        val userLearn = File(root, "userlearn.txt")
        val identities = linkedMapOf(
            "userdb" to UserDataDatabase.fileIdentity(userDb),
            "userlearn" to UserDataDatabase.fileIdentity(userLearn),
        )
        val legacyUser = if (userDb.isFile) {
            runCatching { UserModel().apply { load(userDb) }.storageSnapshot() }
                .onFailure { identities["userdb_status"] = "invalid:${it.javaClass.simpleName}" }
                .getOrNull()
        } else {
            null
        }
        val legacyLearning = if (userLearn.isFile) {
            UserLearning().let { model ->
                model.load(userLearn)
                if (model.lastFailure == null) model.storageSnapshot()
                else null.also { identities["userlearn_status"] = "invalid" }
            }
        } else {
            null
        }
        database.migrateLegacy(legacyUser, legacyLearning, identities)
        return database
    }
}
