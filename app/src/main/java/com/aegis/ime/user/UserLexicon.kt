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

import android.content.SharedPreferences
import com.aegis.ime.dict.EnglishKey
import java.net.IDN
import java.util.Locale

class UserLexicon(private val prefs: SharedPreferences) {
    enum class Kind(val key: String) {
        ENGLISH(PREF_ENGLISH_WORDS), EMAIL(PREF_EMAIL_DOMAINS),
    }

    enum class AddResult { ADDED, EXISTS, INVALID, FAILED }

    private data class Snapshot(val source: Set<String>, val disabled: Set<String>, val entries: List<String>)
    @Volatile private var englishSnapshot: Snapshot? = null
    @Volatile private var emailSnapshot: Snapshot? = null

    fun entries(kind: Kind): List<String> {
        val raw = storedSet(kind.key)
        val disabled = if (kind == Kind.EMAIL) disabledEmailDomains() else emptySet()
        val cached = if (kind == Kind.ENGLISH) englishSnapshot else emailSnapshot
        if (cached != null && cached.source == raw && cached.disabled == disabled) return cached.entries
        val custom = raw.sorted().mapNotNull { normalize(kind, it) }
            .distinctBy { identity(kind, it) }
            .filterNot { kind == Kind.EMAIL && it in COMMON_EMAIL_DOMAINS }
            .sortedWith(compareBy<String> { identity(kind, it) }.thenBy { it })
        val values = if (kind == Kind.EMAIL) COMMON_EMAIL_DOMAINS.filterNot { it in disabled } + custom else custom
        val snapshot = Snapshot(raw.toSet(), disabled, values)
        if (kind == Kind.ENGLISH) englishSnapshot = snapshot else emailSnapshot = snapshot
        return values
    }

    private fun storedSet(key: String): Set<String> =
        runCatching { prefs.getStringSet(key, emptySet()).orEmpty() }.getOrDefault(emptySet())

    private fun disabledEmailDomains(): Set<String> =
        storedSet(PREF_DISABLED_EMAIL_DOMAINS).mapNotNull { normalize(Kind.EMAIL, it) }
            .filterTo(LinkedHashSet()) { it in COMMON_EMAIL_DOMAINS }

    fun add(kind: Kind, raw: String): AddResult = synchronized(writeLock) {
        val value = normalize(kind, raw) ?: return AddResult.INVALID
        val current = entries(kind)
        if (current.any { identity(kind, it) == identity(kind, value) }) return AddResult.EXISTS
        val editor = prefs.edit()
        if (kind == Kind.EMAIL && value in COMMON_EMAIL_DOMAINS) {
            editor.putStringSet(PREF_DISABLED_EMAIL_DOMAINS, disabledEmailDomains() - value)
        } else {
            val custom = current.filterNot { kind == Kind.EMAIL && it in COMMON_EMAIL_DOMAINS }
            editor.putStringSet(kind.key, (custom + value).toSet())
        }
        if (editor.commit()) AddResult.ADDED
        else AddResult.FAILED
    }

    fun remove(kind: Kind, value: String): Boolean = synchronized(writeLock) {
        val target = if (kind == Kind.EMAIL) normalize(kind, value) ?: return true else value
        val current = entries(kind)
        if (target !in current && (kind != Kind.EMAIL || !prefs.contains(EMAIL_COUNT_PREFIX + target))) return true
        val custom = current.filterNot { kind == Kind.EMAIL && it in COMMON_EMAIL_DOMAINS }
        val editor = prefs.edit().putStringSet(kind.key, (custom - target).toSet())
        if (kind == Kind.EMAIL) {
            if (target in COMMON_EMAIL_DOMAINS) editor.putStringSet(PREF_DISABLED_EMAIL_DOMAINS, disabledEmailDomains() + target)
            editor.remove(EMAIL_COUNT_PREFIX + target)
        }
        editor.commit()
    }

    fun resetEmailDefaults(): Boolean = synchronized(writeLock) {
        val editor = prefs.edit().putStringSet(PREF_EMAIL_DOMAINS, emptySet()).putStringSet(PREF_DISABLED_EMAIL_DOMAINS, emptySet())
        for (key in prefs.all.keys) if (key.startsWith(EMAIL_COUNT_PREFIX)) editor.remove(key)
        editor.commit()
    }

    fun englishCompletions(key: String): List<String> =
        entries(Kind.ENGLISH).filter { EnglishKey.normalize(it).startsWith(key) }

    companion object {
        const val PREF_ENGLISH_WORDS = "user_english_words"
        const val PREF_EMAIL_DOMAINS = "user_email_domains"
        const val PREF_DISABLED_EMAIL_DOMAINS = "user_disabled_email_domains"
        const val EMAIL_COUNT_PREFIX = "email_domain_count_"
        val COMMON_EMAIL_DOMAINS = listOf(
            "qq.com", "163.com", "gmail.com", "outlook.com", "126.com",
            "icloud.com", "hotmail.com", "foxmail.com", "yahoo.com", "sina.com",
        )
        private val writeLock = Any()

        private fun identity(kind: Kind, value: String): String =
            if (kind == Kind.ENGLISH) EnglishKey.normalize(value) else value

        internal fun normalize(kind: Kind, raw: String): String? {
            val value = raw.trim()
            if (kind == Kind.ENGLISH) {
                if (value.isEmpty() || value.length > 128 || value.any {
                        !(it in '0'..'9' || it in " '-’" ||
                            (it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN) ||
                            Character.getType(it) == Character.NON_SPACING_MARK.toInt())
                    }) return null
                if (EnglishKey.normalize(value).firstOrNull() !in 'a'..'z') return null
                return value
            }
            val domain = runCatching {
                IDN.toASCII(value.removePrefix("@"), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            }.getOrNull() ?: return null
            if (domain.length > 253) return null
            val labels = domain.split('.')
            if (labels.size < 2 || labels.any { label ->
                    label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-' ||
                        label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
                } || labels.last().none { it in 'a'..'z' }) return null
            return domain
        }
    }
}
