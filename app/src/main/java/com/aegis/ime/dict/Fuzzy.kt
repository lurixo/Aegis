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
    enum class Kind { INITIAL, FINAL, SYLLABLE }
    data class Rule(val key: String, val long: String, val short: String, val kind: Kind)

    val RULES: List<Rule> = listOf(
        Rule("zh", "zh", "z", Kind.INITIAL),
        Rule("ch", "ch", "c", Kind.INITIAL),
        Rule("sh", "sh", "s", Kind.INITIAL),
        Rule("n_l", "n", "l", Kind.INITIAL),
        Rule("f_h", "h", "f", Kind.INITIAL),
        Rule("l_r", "r", "l", Kind.INITIAL),
        Rule("k_g", "k", "g", Kind.INITIAL),
        Rule("ang", "ang", "an", Kind.FINAL),
        Rule("eng", "eng", "en", Kind.FINAL),
        Rule("ing", "ing", "in", Kind.FINAL),
        Rule("iang", "iang", "ian", Kind.FINAL),
        Rule("uang", "uang", "uan", Kind.FINAL),
        Rule("un_ong", "un", "ong", Kind.FINAL),
        Rule("on_ong", "on", "ong", Kind.FINAL),
        Rule("un_iong", "un", "iong", Kind.FINAL),
        Rule("eng_ong", "eng", "ong", Kind.FINAL),
        Rule("an_ai", "an", "ai", Kind.FINAL),
        Rule("hui_fei", "hui", "fei", Kind.SYLLABLE),
        Rule("hu_fu", "hu", "fu", Kind.SYLLABLE),
        Rule("huang_wang", "huang", "wang", Kind.SYLLABLE),
    )

    val DEFAULT_RULE_KEYS: Set<String> = setOf("zh", "ch", "sh", "ang", "eng", "ing")
    const val DEFAULT_ON: Boolean = false
    private const val MAX_VARIANTS = 64
    private const val MAX_FUZZY_LEN = 40
    private val initials = listOf("zh", "ch", "sh") + "bpmfdtnlgkhjqxrzcsyw".map { it.toString() }

    fun prefKey(ruleKey: String): String = "fuzzy_$ruleKey"
    fun activeRules(masterOn: Boolean, enabled: (String) -> Boolean): Set<String> =
        if (!masterOn) emptySet() else RULES.filter { enabled(it.key) }.mapTo(LinkedHashSet()) { it.key }

    private fun initialOf(s: String): String =
        if (s in setOf("n", "m", "ng")) "" else initials.firstOrNull { s.startsWith(it) }.orEmpty()

    private fun swap(s: String, rule: Rule, reverse: Boolean = true): String? {
        val initial = initialOf(s)
        val part = when (rule.kind) {
            Kind.INITIAL -> initial
            Kind.FINAL -> s.substring(initial.length)
            Kind.SYLLABLE -> s
        }
        val replacement = when {
            part == rule.long -> rule.short
            reverse && part == rule.short -> rule.long
            else -> return null
        }
        return when (rule.kind) {
            Kind.INITIAL -> replacement + s.substring(initial.length)
            Kind.FINAL -> initial + replacement
            Kind.SYLLABLE -> replacement
        }
    }

    private class Table(val keys: Set<String>, val active: List<Rule>, val inputs: Set<String>)
    @Volatile private var cache: Table? = null

    private fun table(enabled: Set<String>): Table {
        cache?.let { if (it.keys == enabled) return it }
        val active = RULES.filter { it.key in enabled }
        val inputs = LinkedHashSet(PinyinSyllables.ALL)
        for (s in PinyinSyllables.ALL) for (rule in active) swap(s, rule)?.let(inputs::add)
        return Table(enabled.toSet(), active, inputs).also { cache = it }
    }

    fun inputSyllables(enabled: Set<String>): Set<String> = table(enabled).inputs

    fun syllableVariants(s: String, enabled: Set<String>, cap: Int = MAX_VARIANTS): List<String> {
        if (cap <= 0) return emptyList()
        val t = table(enabled)
        val seen = linkedSetOf(s)
        val out = linkedSetOf(s)
        val queue = ArrayDeque<String>().apply { add(s) }
        while (queue.isNotEmpty() && out.size < cap) {
            val current = queue.removeFirst()
            for (rule in t.active) {
                val next = swap(current, rule) ?: continue
                if (next !in t.inputs || !seen.add(next)) continue
                if (next in PinyinSyllables.ALL) out.add(next)
                if (out.size >= cap) break
                queue.add(next)
            }
        }
        return out.toList()
    }

    private fun segments(s: String, inputs: Set<String>): List<String>? {
        val best = arrayOfNulls<List<String>>(s.length + 1)
        best[s.length] = emptyList()
        for (i in s.lastIndex downTo 0) {
            if (s[i] == '\'') {
                best[i + 1]?.let { best[i] = listOf("'") + it }
                continue
            }
            for (end in minOf(s.length, i + 6) downTo i + 1) {
                val part = s.substring(i, end)
                if (part !in inputs) continue
                val tail = best[end] ?: continue
                val candidate = listOf(part) + tail
                if (best[i] == null || candidate.size < best[i]!!.size) best[i] = candidate
            }
        }
        return best[0]
    }

    fun variants(s: String, enabled: Set<String>, cap: Int = MAX_VARIANTS): List<String> {
        if (cap <= 0) return emptyList()
        if (enabled.isEmpty() || s.length > MAX_FUZZY_LEN) return listOf(s)
        val parts = segments(s, table(enabled).inputs) ?: return listOf(s)
        var out = linkedSetOf("")
        for (part in parts) {
            val choices = syllableVariants(part, enabled, cap)
            val next = linkedSetOf<String>()
            for (prefix in out) {
                for (choice in choices) {
                    next.add(prefix + choice)
                    if (next.size >= cap) break
                }
                if (next.size >= cap) break
            }
            out = next
        }
        return out.toList()
    }

    fun normalize(s: String): String = collapse(s, DEFAULT_RULE_KEYS)

    fun collapse(s: String, enabled: Set<String>): String {
        val t = table(enabled)
        return (segments(s, t.inputs) ?: return s).joinToString("") { part ->
            var result = part
            for (rule in t.active) result = swap(result, rule, reverse = false) ?: result
            result
        }
    }
}
