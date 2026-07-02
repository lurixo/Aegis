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
  * Chinese IME behavior note.
  * Chinese IME behavior note.
 * matching is done by query-time variant expansion against the **exact** dictionary instead: for an
 * input we enumerate every spelling it is confusable with under the *enabled* rules and look each up.
 *
 * [normalize] (all rules) stays identical to `tools/Pinyin.fuzzyNormalize` for host-side parity.
 */
object Fuzzy {

    /**
     * A confusion rule. [long]/[short] are the two confusable spellings (e.g. "zh"↔"z", "ang"↔"an").
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     * ([initial] = false) are position-free and collapse/expand by substring.
     */
    data class Rule(val key: String, val long: String, val short: String, val initial: Boolean = false)

    /**
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     * independently at the first character only (nan↔lan, fan↔han, lan↔ran, kan↔gan); they are NOT folded
     * into the whole-string collapse, because their letters overlap (n_l's short 'l' is l_r's long 'l')
     * and a global collapse would chain n→l→r and destroy the whole confusion class (★HIGH, debug.13).
     * Each rule keeps its own independent toggle; master + per-rule still ship OFF by default ([DEFAULT_ON]).
     */
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

    /** Chinese IME behavior note. */
    private val FINAL_KEYS: Set<String> = RULES.filter { !it.initial }.mapTo(LinkedHashSet()) { it.key }

    /** SharedPreferences key (prefs "aegis") for a rule's per-item toggle, e.g. "fuzzy_zh". */
    fun prefKey(ruleKey: String): String = "fuzzy_$ruleKey"

    /** Master default: fuzzy ships OFF because it can degrade input quality, so it remains opt-in. */
    const val DEFAULT_ON: Boolean = false

    /**
     * debug.16 (fuzzy hot-toggle): the active rule-key set selected by the prefs, as a PURE function so it is
     * unit-testable without a Context. Master off ⇒ no rules; otherwise every rule whose per-item toggle
     * [enabled] is on. The service wraps this over SharedPreferences and the decoder reads the result at query
     * time ([variants]), so flipping a toggle takes effect on the next focus with no engine rebuild.
     */
    fun activeRules(masterOn: Boolean, enabled: (String) -> Boolean): Set<String> =
        if (!masterOn) emptySet()
        else RULES.filter { enabled(it.key) }.mapTo(LinkedHashSet()) { it.key }

    private const val MAX_VARIANTS = 64   // hard ceiling on the confusion class we enumerate
    private const val TOGGLE_BITS = 6     // toggle at most this many sites per rule (2^6 = 64)
    private const val MAX_FUZZY_LEN = 40  // never expand fuzzy for buffers longer than this

    /**
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     * here: their letters overlap, so folding them in would chain n→l→r and is meaningless as a canonical.
     */
    fun normalize(s: String): String = collapse(s, FINAL_KEYS)

    /**
     * Forward-collapse [s] to its canonical (short) form under the [enabled] rules, applied in RULES order.
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     */
    fun collapse(s: String, enabled: Set<String>): String {
        var r = s
        for (rule in RULES) if (rule.key in enabled) r = r.replace(rule.long, rule.short)
        return r
    }

    /**
     * Every spelling confusable with [s] under the [enabled] rules (includes [s] itself), for lookup in the
     * exact dict. Two independent stages so the rule families don't corrupt each other (★HIGH, debug.13):
     *
      * Chinese IME behavior note.
     *     each site back to both spellings. Collapsing first sidesteps the an⊂ang / en⊂eng / in⊂ing nesting
     *     trap; because these rules' letters are disjoint the collapse never chains.
      * Chinese IME behavior note.
     *     and unioned — NEVER collapsed through a shared letter. So nan→lan→ran resolves while -n/-ng finals
     *     and interior syllables stay untouched (no `lal/rar` garbage, no regression of stage 1).
     *
     * Bounded by [cap]; the original spelling always survives.
     */
    fun variants(s: String, enabled: Set<String>, cap: Int = MAX_VARIANTS): List<String> {
        val active = RULES.filter { it.key in enabled }
        // Fast path + length guard: never expand an absurdly long buffer (★HIGH crash/ANR guard).
        if (active.isEmpty() || s.length > MAX_FUZZY_LEN) return listOf(s)
        val finalRules = active.filter { !it.initial }
        val initialRules = active.filter { it.initial }

        // Chinese IME behavior note.
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

        // Assemble the base set: original first (it must always survive), then stage-1 variants, bounded.
        val base = LinkedHashSet<String>()
        base.add(s)
        for (v in finals) { if (base.size >= cap) break; base.add(v) }

        // Chinese IME behavior note.
        return if (initialRules.isEmpty()) base.toList()
        else initialClosure(base, initialRules, cap).toList()
    }

    /**
      * Chinese IME behavior note.
     * directions. Each rule is applied independently and unioned — never collapsed through a shared letter —
     * so nan→lan→ran resolves while finals and interior syllables are left alone. BFS to a fixpoint, bounded
     * by [cap]; every original spelling in [base] survives (the closure only adds).
     */
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
