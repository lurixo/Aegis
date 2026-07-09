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

    class Index internal constructor(private val rows: List<Row>) {
        private val entries = rows.map { it.entry }

        fun filter(query: String): List<UserModel.Entry> {
            val q = query.trim()
            if (q.isEmpty()) return entries
            val qLower = q.lowercase()
            val letters = pinyinLetters(qLower)
            val pinyinQuery = letters.isNotEmpty() &&
                qLower.all { it.isWhitespace() || it == '\'' || it in 'a'..'z' }
            val out = ArrayList<UserModel.Entry>()
            for (row in rows) {
                if (row.wordLower.contains(qLower) || (pinyinQuery && row.reading.contains(letters))) {
                    out.add(row.entry)
                }
            }
            return out
        }
    }

    class Row internal constructor(
        val entry: UserModel.Entry,
        val wordLower: String,
        val reading: String,
    )

    fun index(entries: List<UserModel.Entry>): Index =
        Index(entries.map { Row(it, it.word.lowercase(), it.reading) })

    fun filter(entries: List<UserModel.Entry>, query: String): List<UserModel.Entry> {
        return index(entries).filter(query)
    }

    private fun pinyinLetters(qLower: String): String {
        val letters = StringBuilder(qLower.length)
        for (ch in qLower) {
            if (ch in 'a'..'z') letters.append(ch)
        }
        return letters.toString()
    }
}
