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

    internal const val VARIANT_BATCH_SIZE = 64

    fun normalize(s: String): String = collapse(s, FINAL_KEYS)

    fun collapse(s: String, enabled: Set<String>): String {
        var r = s
        for (rule in RULES) if (rule.key in enabled) r = r.replace(rule.long, rule.short)
        return r
    }

    fun variants(s: String, enabled: Set<String>, cap: Int = VARIANT_BATCH_SIZE): List<String> {
        if (cap <= 0) return emptyList()
        val cursor = variantCursor(s, enabled)
        val out = ArrayList<String>(cap)
        while (out.size < cap) {
            val variant = cursor.next() ?: break
            out.add(variant)
        }
        return out
    }

    internal fun variantCursor(
        s: String,
        enabled: Set<String>,
        maximumLength: Int = Int.MAX_VALUE,
    ): VariantCursor = VariantCursor(s, enabled, maximumLength)

    internal class VariantCursor internal constructor(
        private val input: String,
        enabled: Set<String>,
        private val maximumLength: Int,
    ) {
        private data class Token(val short: String, val long: String?)

        private val initialRules = RULES.filter { it.initial && it.key in enabled }
        private val tokens: List<Token>
        private val choiceCount: Int
        private val maximumToggleCount: Int
        private val emitted = HashSet<String>()
        private var originalPending = true
        private var toggleCount = 0
        private var toggles = IntArray(0)
        private var combinationsExhausted = false
        private var currentInitials = emptyList<String>()
        private var currentInitialIndex = 0
        private var pending: String? = null

        init {
            val finalRules = RULES.filter { !it.initial && it.key in enabled }
            val canonical = collapse(input, finalRules.mapTo(HashSet()) { it.key })
            val built = ArrayList<Token>()
            var choices = 0
            var offset = 0
            while (offset < canonical.length) {
                val rule = finalRules.firstOrNull { canonical.startsWith(it.short, offset) }
                if (rule == null) {
                    built.add(Token(canonical[offset].toString(), null))
                    offset++
                } else {
                    built.add(Token(rule.short, rule.long))
                    choices++
                    offset += rule.short.length
                }
            }
            tokens = built
            choiceCount = choices
            val minimumExpansion = built.mapNotNull { token ->
                token.long?.let { it.length - token.short.length }
            }.filter { it > 0 }.minOrNull() ?: 0
            val available = maximumLength.toLong() - canonical.length.toLong()
            maximumToggleCount = if (minimumExpansion == 0) {
                choiceCount
            } else {
                minOf(choiceCount.toLong(), (available / minimumExpansion).coerceAtLeast(0L)).toInt()
            }
            if (canonical.length > maximumLength) combinationsExhausted = true
            pending = pull()
        }

        fun peek(): String? = pending

        fun next(): String? {
            val item = pending
            pending = pull()
            return item
        }

        private fun pull(): String? {
            while (true) {
                if (originalPending) {
                    originalPending = false
                    if (emitted.add(input)) return input
                }
                while (currentInitialIndex < currentInitials.size) {
                    val candidate = currentInitials[currentInitialIndex++]
                    if (emitted.add(candidate)) return candidate
                }
                val base = nextFinalVariant() ?: return null
                currentInitials = initialClosure(base, initialRules)
                currentInitialIndex = 0
            }
        }

        private fun nextFinalVariant(): String? {
            while (!combinationsExhausted) {
                val selected = BooleanArray(choiceCount)
                for (index in toggles) selected[index] = true
                val out = StringBuilder(input.length + toggleCount)
                var choice = 0
                for (token in tokens) {
                    val long = token.long
                    if (long == null) {
                        out.append(token.short)
                    } else {
                        out.append(if (selected[choice++]) long else token.short)
                    }
                }
                advanceCombination()
                if (out.length <= maximumLength) return out.toString()
            }
            return null
        }

        private fun advanceCombination() {
            if (toggleCount == 0) {
                if (maximumToggleCount == 0) {
                    combinationsExhausted = true
                } else {
                    toggleCount = 1
                    toggles = intArrayOf(0)
                }
                return
            }
            var index = toggleCount - 1
            while (index >= 0 && toggles[index] == choiceCount - toggleCount + index) index--
            if (index >= 0) {
                toggles[index]++
                for (next in index + 1 until toggleCount) toggles[next] = toggles[next - 1] + 1
            } else if (toggleCount < maximumToggleCount) {
                toggleCount++
                toggles = IntArray(toggleCount) { it }
            } else {
                combinationsExhausted = true
            }
        }
    }

    private fun initialClosure(base: String, rules: List<Rule>): List<String> {
        if (rules.isEmpty() || base.isEmpty()) return listOf(base)
        val out = linkedSetOf(base)
        val queue = ArrayDeque(listOf(base))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val c0 = cur[0]
            for (rule in rules) {
                val swapped = when (c0) {
                    rule.long[0] -> rule.short + cur.substring(1)
                    rule.short[0] -> rule.long + cur.substring(1)
                    else -> null
                }
                if (swapped != null && out.add(swapped)) {
                    queue.addLast(swapped)
                }
            }
        }
        return out.toList()
    }
}
