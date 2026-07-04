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

object UserDictSearch {

    fun filter(entries: List<UserModel.Entry>, query: String): List<UserModel.Entry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        val letters = buildString(q.length) {
            for (ch in q.lowercase()) if (ch in 'a'..'z') append(ch)
        }
        val pinyinQuery = letters.isNotEmpty() &&
            q.all { it.isWhitespace() || it == '\'' || it.lowercaseChar() in 'a'..'z' }
        return entries.filter { e ->
            e.word.contains(q, ignoreCase = true) || (pinyinQuery && e.reading.contains(letters))
        }
    }
}
