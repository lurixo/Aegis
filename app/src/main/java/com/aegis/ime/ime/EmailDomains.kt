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

package com.aegis.ime.ime

import android.content.SharedPreferences

class EmailDomains(private val prefs: SharedPreferences? = null) {
    private val counts = HashMap<String, Long>()

    fun suggestions(): List<String> = catalog().sortedByDescending(::count)

    fun contains(domain: String): Boolean = domain in catalog()

    private fun catalog(): List<String> =
        COMMON_EMAIL_DOMAINS

    fun record(domain: String) {
        if (!contains(domain)) return
        val next = count(domain).coerceAtMost(Long.MAX_VALUE - 1) + 1
        if (prefs == null) counts[domain] = next
        else prefs.edit().putLong(EMAIL_COUNT_PREFIX + domain, next).apply()
    }

    private fun count(domain: String): Long {
        val stored = prefs ?: return counts[domain] ?: 0L
        return runCatching { stored.getLong(EMAIL_COUNT_PREFIX + domain, 0L) }.getOrDefault(0L).coerceAtLeast(0L)
    }

    companion object {
        const val EMAIL_COUNT_PREFIX = "email_domain_count_"
        val COMMON_EMAIL_DOMAINS = listOf(
            "qq.com", "163.com", "gmail.com", "outlook.com", "126.com",
            "icloud.com", "hotmail.com", "foxmail.com", "yahoo.com", "sina.com",
        )
        internal fun context(before: CharSequence): String? {
            if (before.length < 2 || before.last() != '@') return null
            val previous = before[before.length - 2]
            if (previous.isWhitespace() || previous == '@') return null
            return before.toString()
        }
    }
}
