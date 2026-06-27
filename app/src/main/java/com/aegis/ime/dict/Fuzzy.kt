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

package com.aegis.ime.dict

object Fuzzy {

    data class Rule(val key: String, val long: String, val short: String)

    val RULES: List<Rule> = listOf(
        Rule("zh", "zh", "z"),
        Rule("ch", "ch", "c"),
        Rule("sh", "sh", "s"),
        Rule("ang", "ang", "an"),
        Rule("eng", "eng", "en"),
        Rule("ing", "ing", "in"),
    )

    private val ALL_KEYS: Set<String> = RULES.mapTo(LinkedHashSet()) { it.key }

    fun prefKey(ruleKey: String): String = "fuzzy_$ruleKey"

    const val DEFAULT_ON: Boolean = false

    private const val MAX_VARIANTS = 64
    private const val TOGGLE_BITS = 6
    private const val MAX_FUZZY_LEN = 40

    fun normalize(s: String): String = collapse(s, ALL_KEYS)

    fun collapse(s: String, enabled: Set<String>): String {
        var r = s
        for (rule in RULES) if (rule.key in enabled) r = r.replace(rule.long, rule.short)
        return r
    }

    fun variants(s: String, enabled: Set<String>, cap: Int = MAX_VARIANTS): List<String> {
        val active = RULES.filter { it.key in enabled }
        if (active.isEmpty() || s.length > MAX_FUZZY_LEN) return listOf(s)
        var set: LinkedHashSet<String> = linkedSetOf(collapse(s, enabled))
        for (rule in active) {
            val next = LinkedHashSet<String>()
            for (v in set) {
                expandSitesInto(v, rule.short, rule.long, cap, next)
                if (next.size >= cap) break
            }
            set = next
            if (set.size >= cap) break
        }
        set.add(s)
        return set.toList()
    }

    private fun expandSitesInto(s: String, short: String, long: String, cap: Int, out: MutableSet<String>) {
        val pos = ArrayList<Int>(TOGGLE_BITS)
        var i = s.indexOf(short)
        while (i >= 0 && pos.size < TOGGLE_BITS) { pos.add(i); i = s.indexOf(short, i + short.length) }
        if (pos.isEmpty()) { out.add(s); return }
        val n = pos.size
        for (mask in 0 until (1 shl n)) {
            val sb = StringBuilder(s.length + n)
            var prev = 0
            for (k in 0 until n) {
                sb.append(s, prev, pos[k])
                sb.append(if ((mask shr k) and 1 == 1) long else short)
                prev = pos[k] + short.length
            }
            sb.append(s, prev, s.length)
            out.add(sb.toString())
            if (out.size >= cap) return
        }
    }
}
