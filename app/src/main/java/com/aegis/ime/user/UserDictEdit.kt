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
        val m = UserModel().apply { if (userDb.exists()) load(userDb) }
        m.addManualWord(reading, word, now)
        m.save(userDb)
        return true
    }

    fun remove(userDb: File, reading: String, word: String): Boolean {
        if (word.isBlank()) return false
        val m = UserModel().apply { if (userDb.exists()) load(userDb) }
        m.removeWord(reading, word)
        m.save(userDb)
        return true
    }

    fun list(userDb: File): List<UserModel.Entry> =
        UserModel().apply { if (userDb.exists()) load(userDb) }.userWordEntries()
}
