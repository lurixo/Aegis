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

    data class Rule(val key: String, val long: String, val short: String, val initial: Boolean = false)

    val RULES: List<Rule> = listOf(
        Rule("zh", "zh", "z"),
        Rule("ch", "ch", "c"),
        Rule("sh", "sh", "s"),
        Rule("ang", "ang", "an"),
        Rule("eng", "eng", "en"),
        Rule("ing", "ing", "in"),
        Rule("n_l", "n", "l", initial = true),
        Rule("f_h", "f", "h", initial = true),
        Rule("l_r", "l", "r", initial = true),
        Rule("k_g", "k", "g", initial = true),
    )

    private val ALL_KEYS: Set<String> = RULES.mapTo(LinkedHashSet()) { it.key }

    private val FINAL_KEYS: Set<String> = RULES.filter { !it.initial }.mapTo(LinkedHashSet()) { it.key }

    fun prefKey(ruleKey: String): String = "fuzzy_$ruleKey"

    const val DEFAULT_ON: Boolean = false

    fun activeRules(masterOn: Boolean, enabled: (String) -> Boolean): Set<String> =
        if (!masterOn) emptySet()
        else RULES.filter { enabled(it.key) }.mapTo(LinkedHashSet()) { it.key }

    private const val MAX_VARIANTS = 64
    private const val TOGGLE_BITS = 6
    private const val MAX_FUZZY_LEN = 40

    fun normalize(s: String): String = collapse(s, FINAL_KEYS)

    fun collapse(s: String, enabled: Set<String>): String {
        var r = s
        for (rule in RULES) if (rule.key in enabled) r = r.replace(rule.long, rule.short)
        return r
    }

    fun variants(s: String, enabled: Set<String>, cap: Int = MAX_VARIANTS): List<String> {
        val active = RULES.filter { it.key in enabled }
        if (active.isEmpty() || s.length > MAX_FUZZY_LEN) return listOf(s)
        val finalRules = active.filter { !it.initial }
        val initialRules = active.filter { it.initial }

        val finalKeys = finalRules.mapTo(HashSet()) { it.key }
        var finals: LinkedHashSet<String> = linkedSetOf(collapse(s, finalKeys))
        for (rule in finalRules) {
            val next = LinkedHashSet<String>()
            for (v in finals) {
                expandSitesInto(v, rule.short, rule.long, cap, next)
                if (next.size >= cap) break
            }
            finals = next
            if (finals.size >= cap) break
        }

        val base = LinkedHashSet<String>()
        base.add(s)
        for (v in finals) { if (base.size >= cap) break; base.add(v) }

        return if (initialRules.isEmpty()) base.toList()
        else initialClosure(base, initialRules, cap).toList()
    }

    private fun initialClosure(base: Set<String>, rules: List<Rule>, cap: Int): LinkedHashSet<String> {
        val out = LinkedHashSet(base)
        if (out.size >= cap) return out
        val queue = ArrayDeque(base.toList())
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.isEmpty()) continue
            val c0 = cur[0]
            for (rule in rules) {
                val swapped = when (c0) {
                    rule.long[0] -> rule.short + cur.substring(1)
                    rule.short[0] -> rule.long + cur.substring(1)
                    else -> null
                }
                if (swapped != null && out.add(swapped)) {
                    if (out.size >= cap) return out
                    queue.addLast(swapped)
                }
            }
        }
        return out
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
