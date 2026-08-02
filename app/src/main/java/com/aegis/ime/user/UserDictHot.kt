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

object UserDictHot {

    interface Host {
        fun addWord(reading: String, word: String, now: Long): Boolean

        fun removeWord(reading: String, word: String): Boolean

        fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean

        fun entries(): List<UserModel.Entry>

        fun entryCount(query: String): Long = UserDictSearch.filter(entries(), query).size.toLong()

        fun entryPage(query: String, offset: Int, limit: Int): List<UserModel.Entry> =
            UserDictSearch.filter(entries(), query).drop(offset).take(limit)

        fun entryPageSnapshot(
            query: String,
            offset: Int,
            limit: Int,
            expectedVersion: Long? = null,
        ): PersistedPage<UserModel.Entry> = if (expectedVersion != null && expectedVersion != 0L) {
            PersistedPage(emptyList(), 0L, restartRequired = true)
        } else {
            val filtered = UserDictSearch.filter(entries(), query)
            PersistedPage(filtered.drop(offset).take(limit), 0L, filtered.size.toLong())
        }

        fun flush()
    }

    @Volatile
    var host: Host? = null
}
