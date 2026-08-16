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

package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CandidateLedgerTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val clock = 1_700_000_000_000L
    private val limit = 30
    private val committedTail = "的"

    private fun assets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun fullSweep(): Boolean =
        (System.getenv("AEGIS_AUDIT_FULL") ?: System.getProperty("aegis.audit.full")) == "1"

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9Dict: BinaryDict by lazy { BinaryDict.fromFile(t9File) }
    private val jianpinDict: BinaryDict by lazy { BinaryDict.fromFile(jianpinFile) }
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }

    private fun taught(): UserModel = UserModel { clock }.apply {
        repeat(500) { recordWord("nimen", "拟门", clock, incrementCount = true) }
        repeat(500) { recordWord("64636", "拟门", clock, incrementCount = true) }
    }

    private fun chained(): UserLearning = UserLearning { clock }.apply {
        repeat(128) {
            var prev: String? = null
            for ((word, reading) in listOf("你" to "ni", "门" to "men")) {
                observeCommit(prev, word, reading, clock)
                prev = word
            }
            observeBreak()
        }
    }

    private class Arm(
        val name: String,
        val decoder: PinyinDecoder,
        val source: BinaryDict,
        val t9: Boolean,
        val lockedDecoder: PinyinDecoder,
    )

    private fun lockedView(arm: Arm): Arm =
        Arm(arm.name, arm.lockedDecoder, dict, t9 = false, lockedDecoder = arm.lockedDecoder)

    private fun arms(): List<Arm> {
        val um = taught()
        val ul = chained()
        val plainLetters = PinyinDecoder(dict, lm, initialsDict = jianpinDict)
        val userLetters = PinyinDecoder(dict, lm, userModel = um, initialsDict = jianpinDict, userLearning = ul)
        return listOf(
            Arm("26key", plainLetters, dict, t9 = false, lockedDecoder = plainLetters),
            Arm("9key", PinyinDecoder(t9Dict, lm, aliasDict = dict), t9Dict, t9 = true, lockedDecoder = plainLetters),
            Arm("26key+user", userLetters, dict, t9 = false, lockedDecoder = userLetters),
            Arm(
                "9key+user",
                PinyinDecoder(t9Dict, lm, userModel = um, aliasDict = dict, userLearning = ul),
                t9Dict,
                t9 = true,
                lockedDecoder = userLetters,
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        return (f.get(T9Pinyin) as Set<String>).toList().sorted()
    }

    private val reportedRegressionKeys = listOf("bielun", "biebu", "nihao", "bianhao", "aihao", "duohao")

    private fun letterKeys(): List<String> {
        val syls = runtimeSyllables()
        val tails = if (fullSweep()) listOf("hao", "lun", "bu", "an") else listOf("hao", "lun")
        val out = LinkedHashSet<String>()
        out += reportedRegressionKeys
        out += syls
        for (t in tails) for (s in syls) out += s + t
        return out.toList()
    }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private fun leadingLens(arm: Arm, key: String): List<Int> =
        if (arm.t9) T9Pinyin.leadingSyllableDigitLens(key) else T9Pinyin.leadingSyllableLetterLens(key)

    private fun declaredSingleSources(arm: Arm, key: String): Set<String> {
        val cap = PinyinDecoder.completionCap(limit)
        val out = HashSet<String>()
        for (q in 1..key.length) out += arm.decoder.homophoneFreqs(key.substring(0, q)).map { it.first }
        out += arm.source.prefixByFreq(key, cap).map { it.word }
        out += arm.source.exact(key).map { it.word }
        if (!arm.t9) {
            out += jianpinDict.prefixByFreq(key, cap).map { it.word }
            out += jianpinDict.exact(key).map { it.word }
        }
        return out
    }

    private fun cutsOf(arm: Arm, key: String): Set<Int> {
        val cut = arm.decoder.syllables(key).firstOrNull()?.end ?: 0
        return if (cut in 1 until key.length) setOf(cut) else emptySet()
    }

    private fun pinnedCharacters(arm: Arm, key: String, path: String, cuts: Set<Int>): Set<String> {
        val out = LinkedHashSet<String>()
        if (path == "free" || path == "freeCtx") {
            for (q in leadingLens(arm, key)) {
                if (q in 1..key.length) out += arm.decoder.homophoneFreqs(key.substring(0, q)).map { it.first }
            }
            return out
        }
        val end = (arm.decoder.syllables(key, cuts).firstOrNull()?.end ?: key.length).coerceIn(1, key.length)
        out += arm.decoder.homophoneFreqs(key.substring(0, end)).map { it.first }
        return out
    }

    private class Ledger {
        var probes = 0
        var candidates = 0L
        var pinned = 0L
        val repeats = ArrayList<String>()
        val dropped = ArrayList<String>()
        val unaccounted = ArrayList<String>()
    }

    private fun sample(rows: Collection<String>, n: Int = 8): String =
        rows.take(n).joinToString(" | ") + if (rows.size > n) " …(${rows.size})" else ""

    private fun reconcile(): Ledger {
        val out = Ledger()
        for (arm in arms()) {
            for (raw in letterKeys()) {
                val key = if (arm.t9) T9Pinyin.toT9(raw) else raw
                val cuts = cutsOf(arm, key)
                val locked = lockedView(arm)
                val lockedCuts = cutsOf(locked, raw)
                val runs = ArrayList<Triple<String, List<Cand>, Pair<Arm, String>>>(5)
                runs += Triple("free", arm.decoder.decodeCovered(key, limit), arm to key)
                runs += Triple("freeCtx", arm.decoder.decodeCovered(key, limit, emptySet(), committedTail), arm to key)
                runs += Triple("cut", arm.decoder.decodeCovered(key, limit, cuts), arm to key)
                runs += Triple(
                    "locked",
                    locked.decoder.decodeCoveredAtomic(raw, limit, lockedCuts),
                    locked to raw,
                )
                for ((path, cands, view) in runs) {
                    val probeArm = view.first
                    val probeKey = view.second
                    val probeCuts = if (path == "locked") lockedCuts else cuts
                    out.probes++
                    out.candidates += cands.size
                    val counts = HashMap<String, Int>(cands.size * 2)
                    for (c in cands) counts[c.word] = (counts[c.word] ?: 0) + 1
                    val repeated = counts.filterValues { it > 1 }
                    if (repeated.isNotEmpty()) {
                        out.repeats += "${arm.name}/$path/$probeKey repeats " +
                            repeated.entries.take(6).joinToString(" ") { (w, n) ->
                                "$w x$n(cov=${cands.filter { it.word == w }.joinToString("/") { c -> c.coveredLen.toString() }})"
                            }
                    }
                    val pinned = pinnedCharacters(probeArm, probeKey, path, probeCuts)
                    out.pinned += pinned.size
                    val missing = pinned.filterNot { counts.containsKey(it) }
                    if (missing.isNotEmpty()) {
                        out.dropped +=
                            "${arm.name}/$path/$probeKey drops ${missing.size} of ${pinned.size}: ${sample(missing, 6)}"
                    }
                    val declared = declaredSingleSources(probeArm, probeKey)
                    val strays = cands.map { it.word }.filter { isSingleChar(it) && it !in declared }.distinct()
                    if (strays.isNotEmpty()) {
                        out.unaccounted +=
                            "${arm.name}/$path/$probeKey lists ${strays.size} unaccounted: ${sample(strays, 6)}"
                    }
                }
            }
        }
        return out
    }

    private val ledger: Ledger by lazy { reconcile() }

    private fun writeReport(led: Ledger) {
        val dir = File(System.getenv("AEGIS_AUDIT_DIR") ?: "build/decode-audit").apply { mkdirs() }
        File(dir, "candidate_ledger_summary.txt").writeText(
            buildString {
                appendLine("Candidate ledger — every listed word reconciles against a declared source")
                appendLine("sweep: ${if (fullSweep()) "full (AEGIS_AUDIT_FULL=1)" else "representative"}")
                appendLine("probes (arm x key x path): ${led.probes}")
                appendLine("candidate rows compared: ${led.candidates}")
                appendLine("dictionary items pinned to exactly one row: ${led.pinned}")
                appendLine("keys listing a word twice: ${led.repeats.size}")
                appendLine("keys dropping a pinned item: ${led.dropped.size}")
                appendLine("keys listing a character no declared source holds: ${led.unaccounted.size}")
            },
        )
    }

    @Test fun noCandidateIsListedTwiceOnEitherKeyboardOnAnyPath() {
        assets()
        val led = ledger
        writeReport(led)
        assertTrue("the ledger must actually compare something: probes=${led.probes}", led.probes >= 400)
        assertTrue(
            "a candidate word may appear at most once in one list; " +
                "${led.repeats.size} of ${led.probes} probes list one twice: ${sample(led.repeats)}",
            led.repeats.isEmpty(),
        )
    }

    @Test fun everyLeadingSyllableCharacterIsListedExactlyOnce() {
        assets()
        val led = ledger
        assertTrue(
            "the pinned set must be non-trivial: ${led.pinned} items over ${led.probes} probes",
            led.pinned >= 10_000,
        )
        assertTrue(
            "every character the dictionary reads as a leading syllable of the typed key must be listed; " +
                "${led.dropped.size} keys drop one: ${sample(led.dropped)}",
            led.dropped.isEmpty(),
        )
        assertTrue(
            "and it must be listed once, not twice: ${sample(led.repeats)}",
            led.repeats.isEmpty(),
        )
    }

    @Test fun everyListedCharacterComesFromADeclaredSource() {
        assets()
        val led = ledger
        assertTrue(
            "every single-character candidate must be a homophone of some prefix of the typed key, " +
                "a completion the dictionary holds for it, or an initials match; " +
                "${led.unaccounted.size} keys list one nothing holds: ${sample(led.unaccounted)}",
            led.unaccounted.isEmpty(),
        )
    }
}
