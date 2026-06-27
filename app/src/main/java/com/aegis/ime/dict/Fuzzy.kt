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

/**
 * Fuzzy pinyin (模糊拼音). Each confusion rule has its own toggle, so the user can keep, say, 前后鼻音
 * but drop 平翘舌 (E4). Because a single pre-built index bakes in *all* rules at once, per-rule
 * matching is done by query-time variant expansion against the **exact** dictionary instead: for an
 * input we enumerate every spelling it is confusable with under the *enabled* rules and look each up.
 *
 * [normalize] (all rules) stays identical to `tools/Pinyin.fuzzyNormalize` for host-side parity.
 */
object Fuzzy {

    /** A confusion rule: the long spelling (e.g. "zh"/"ang") collapses to the short one ("z"/"an"). */
    data class Rule(val key: String, val long: String, val short: String)

    /** The supported rules — 平翘舌 zh/ch/sh↔z/c/s and 前后鼻音 ang/eng/ing↔an/en/in. */
    val RULES: List<Rule> = listOf(
        Rule("zh", "zh", "z"),
        Rule("ch", "ch", "c"),
        Rule("sh", "sh", "s"),
        Rule("ang", "ang", "an"),
        Rule("eng", "eng", "en"),
        Rule("ing", "ing", "in"),
    )

    private val ALL_KEYS: Set<String> = RULES.mapTo(LinkedHashSet()) { it.key }

    /** SharedPreferences key (prefs "aegis") for a rule's per-item toggle, e.g. "fuzzy_zh". */
    fun prefKey(ruleKey: String): String = "fuzzy_$ruleKey"

    /** Master default: fuzzy ships OFF (it can degrade input quality, so it is opt-in). */
    const val DEFAULT_ON: Boolean = false

    private const val MAX_VARIANTS = 64   // hard ceiling on the confusion class we enumerate
    private const val TOGGLE_BITS = 6     // toggle at most this many sites per rule (2^6 = 64)
    private const val MAX_FUZZY_LEN = 40  // never expand fuzzy for buffers longer than this

    /** Whole-string collapse under *all* rules — kept identical to tools/Pinyin.fuzzyNormalize. */
    fun normalize(s: String): String = collapse(s, ALL_KEYS)

    /** Forward-collapse [s] to its canonical (short) form under the [enabled] rules only. */
    fun collapse(s: String, enabled: Set<String>): String {
        var r = s
        for (rule in RULES) if (rule.key in enabled) r = r.replace(rule.long, rule.short)
        return r
    }

    /**
     * Every spelling confusable with [s] under the [enabled] rules (includes [s] itself), for lookup
     * in the exact dict. We first collapse to the canonical short form, then expand each collapsed
     * site back to both spellings — collapsing first sidesteps the an⊂ang / en⊂eng / in⊂ing nesting
     * trap that toggling the raw string would hit. The result is exactly the confusion class (a few
     * members may be non-syllables, which simply miss in the dict). Bounded by [cap].
     */
    fun variants(s: String, enabled: Set<String>, cap: Int = MAX_VARIANTS): List<String> {
        val active = RULES.filter { it.key in enabled }
        // Fast path + length guard: never expand an absurdly long buffer (★HIGH crash/ANR guard).
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
        set.add(s) // guarantee the original spelling survives even if the cap truncated expansion
        return set.toList()
    }

    /**
     * Add to [out] the strings made by toggling subsets of [short] occurrences in [s] to [long],
     * stopping once [out] reaches [cap]. Only the first [TOGGLE_BITS] occurrences are toggled (any
     * beyond stay short), so we never materialize the full 2^n set — this bounds cost and, crucially,
     * avoids the `1 shl n` Int overflow a long fuzzy run ("zzzz…", n≥31) would otherwise hit.
     */
    private fun expandSitesInto(s: String, short: String, long: String, cap: Int, out: MutableSet<String>) {
        val pos = ArrayList<Int>(TOGGLE_BITS)
        var i = s.indexOf(short)
        while (i >= 0 && pos.size < TOGGLE_BITS) { pos.add(i); i = s.indexOf(short, i + short.length) }
        if (pos.isEmpty()) { out.add(s); return }
        val n = pos.size // <= TOGGLE_BITS, so (1 shl n) is small and never overflows
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
