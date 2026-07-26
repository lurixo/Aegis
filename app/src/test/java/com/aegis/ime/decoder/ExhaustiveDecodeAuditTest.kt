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
import com.aegis.tools.T2SMerge
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ExhaustiveDecodeAuditTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun letterDecoder(): PinyinDecoder {
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile),
            CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
    }

    private fun t9Decoder(): PinyinDecoder =
        PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile), aliasDict = dict)

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9Dict: BinaryDict by lazy { BinaryDict.fromFile(t9File) }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun t9Singles(key: String): Set<String> =
        t9Dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun allSingles(cands: List<Cand>): Set<String> =
        cands.filter { isSingleChar(it.word) }.map { it.word }.toSet()

    private fun dictWords(source: BinaryDict, key: String): Set<String> =
        source.exact(key).filter { !isSingleChar(it.word) }.map { it.word }.toSet()

    private fun leadingKey(d: PinyinDecoder, input: String, cuts: Set<Int>): String {
        val head = input.substring(0, cuts.filter { it in 1 until input.length }.minOrNull() ?: input.length)
        val end = d.syllables(head).firstOrNull()?.end ?: head.length
        return head.substring(0, end.coerceIn(1, head.length))
    }

    private fun lossFail(
        input: String, layout: String, path: String,
        key: String, oracle: Set<String>, shown: Set<String>,
    ): Fail? {
        val missing = oracle - shown
        if (missing.isEmpty()) return null
        return Fail(
            input, layout, "L1-char-loss", key, path, sample(oracle), sample(missing),
            "$path drops ${missing.size} of ${oracle.size} singles the dictionary holds for '$key'",
        )
    }

    private fun leadingLoss(
        dLetters: PinyinDecoder, dDigits: PinyinDecoder,
        letters: String, digits: String, letterCuts: Set<Int>, digitCuts: Set<Int>,
    ): List<Fail> {
        val freeLetterKey = leadingKey(dLetters, letters, emptySet())
        val freeDigitKey = leadingKey(dDigits, digits, emptySet())
        val atomLetterKey = leadingKey(dLetters, letters, letterCuts)
        val atomDigitKey = leadingKey(dDigits, digits, digitCuts)
        return listOfNotNull(
            lossFail(
                letters, "26key", "decodeCovered", freeLetterKey, dictSingles(freeLetterKey),
                allSingles(dLetters.decodeCovered(letters, 30)),
            ),
            lossFail(
                letters, "26key", "decodeCoveredAtomic", atomLetterKey, dictSingles(atomLetterKey),
                allSingles(dLetters.decodeCoveredAtomic(letters, 30, letterCuts)),
            ),
            lossFail(
                digits, "9key", "decodeCovered", freeDigitKey, t9Singles(freeDigitKey),
                allSingles(dDigits.decodeCovered(digits, 30)),
            ),
            lossFail(
                digits, "9key", "decodeCoveredAtomic", atomDigitKey, t9Singles(atomDigitKey),
                allSingles(dDigits.decodeCoveredAtomic(digits, 30, digitCuts)),
            ),
        )
    }

    private fun sample(s: Collection<String>, n: Int = 8): String =
        s.take(n).joinToString(" ") + if (s.size > n) " …(${s.size})" else ""

    private val COLLOQUIAL_WHITELIST: Map<String, Set<String>> by lazy { mapOf("en" to dictSingles("ng")) }

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val set = f.get(T9Pinyin) as Set<String>
        return set.toList()
    }

    private val mappedForms: Set<String> by lazy {
        javaClass.classLoader!!.getResourceAsStream("tc1_mapped_forms.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
    }
    private val readingScopedMappedForms: Map<String, Set<String>> by lazy {
        val out = HashMap<String, MutableSet<String>>()
        for (line in File("../tools/t2s-data/adjudications.tsv").readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = line.split("\t")
            if (cols.size < 3) continue
            val (source, reading, target) = cols
            if (reading != "*" && source != target) out.getOrPut(source) { HashSet() }.add(reading)
        }
        out
    }

    private fun isStandalone(word: String): Boolean = word.codePointCount(0, word.length) == 1

    private fun mappedAwayUnderReading(word: String, reading: String): Boolean =
        isStandalone(word) && reading in readingScopedMappedForms[word].orEmpty()

    private fun containsMappedForm(word: String): Boolean {
        var i = 0
        while (i < word.length) {
            if (String(Character.toChars(word.codePointAt(i))) in mappedForms) return true
            i += Character.charCount(word.codePointAt(i))
        }
        return false
    }

    private data class Fail(
        val input: String, val layout: String, val inv: String,
        val expectedReading: String, val shownReading: String,
        val expectedChars: String, val shownChars: String, val detail: String,
    )

    private fun outDir(): File {
        val p = System.getenv("AEGIS_AUDIT_DIR") ?: System.getProperty("aegis.audit.dir") ?: "build/decode-audit"
        val d = File(p); d.mkdirs(); return d
    }
    private fun fullEnabled(): Boolean =
        (System.getenv("AEGIS_AUDIT_FULL") ?: System.getProperty("aegis.audit.full")) == "1"

    private val NASALS = setOf("ng", "n", "m")
    private val syllableSet: Set<String> by lazy { runtimeSyllables().toSet() }
    private fun classify(f: Fail): String {
        if (f.layout != "26key" || f.inv != "I1") return "other"
        val exp = f.expectedReading.split("+"); val shown = f.shownReading.split("+")
        if (exp.drop(1).any { it in NASALS }) return "expected-merge"
        for (i in 1 until shown.size) if (shown[i] in NASALS && (shown[i - 1] + shown[i]) in syllableSet) return "nasal-split"
        return "other-reflow"
    }

    private fun writeTsv(file: File, fails: List<Fail>) {
        file.bufferedWriter().use { w ->
            w.write("input\tlayout\tinvariant\texpectedReading\tshownReading\texpectedCharsSample\tshownCharsSample\tdetail\n")
            for (f in fails) w.write(
                "${f.input}\t${f.layout}\t${f.inv}\t${f.expectedReading}\t${f.shownReading}\t${f.expectedChars}\t${f.shownChars}\t${f.detail}\n"
            )
        }
    }

    private fun sweepLeftToRight(d: PinyinDecoder, t9: PinyinDecoder, inputs: List<List<String>>): List<Fail> {
        val fails = ArrayList<Fail>()
        for (sylList in inputs) {
            for (k in sylList.indices) {
                val rest = sylList.subList(k, sylList.size)
                val letters = rest.joinToString("")
                val digits = rest.joinToString("") { T9Pinyin.toT9(it) }
                val letterCuts = HashSet<Int>()
                val digitCuts = HashSet<Int>()
                var la = 0
                var da = 0
                for (j in 0 until rest.size - 1) {
                    la += rest[j].length; letterCuts.add(la)
                    da += T9Pinyin.toT9(rest[j]).length; digitCuts.add(da)
                }
                fails += leadingLoss(d, t9, letters, digits, letterCuts, digitCuts)
            }
        }
        return fails
    }

    private fun sweepN1(d: PinyinDecoder, t9: PinyinDecoder, syls: List<String>): List<Fail> {
        val fails = ArrayList<Fail>()
        for (s in syls) {
            val oracle = dictSingles(s)
            val allowed = COLLOQUIAL_WHITELIST[s] ?: emptySet()
            val digits = T9Pinyin.toT9(s)

            val seg26 = d.syllables(s).map { it.reading }
            if (seg26 != listOf(s)) {
                fails += Fail(s, "26key", "I1", s, seg26.joinToString("+"),
                    sample(oracle), "-", "syllables(S) mis-segments")
            }
            val col9 = T9Pinyin.leftColumnReadings(digits, 26)
            val lock = T9Pinyin.lockFirstReading(digits, s)
            if (s !in col9) {
                fails += Fail(s, "9key", "I1", s, sample(col9),
                    sample(oracle), "-", "leftColumnReadings(toT9(S)) omits S")
            }
            if (lock == null || lock.display != s) {
                fails += Fail(s, "9key", "I1", s, lock?.display ?: "<null>",
                    sample(oracle), "-", "lockFirstReading(toT9(S),S).display != S")
            }

            val homo = d.homophonesAt(s, 0).toSet()
            val homoLeak = homo - oracle - allowed
            if (homoLeak.isNotEmpty()) {
                fails += Fail(s, "26key", "I2", s, seg26.firstOrNull() ?: "-",
                    sample(oracle), sample(homoLeak), "homophonesAt(S,0) has chars not reading S")
            }
            if (oracle.isNotEmpty() && homo.isEmpty()) {
                fails += Fail(s, "26key", "I2", s, seg26.firstOrNull() ?: "-",
                    sample(oracle), "<empty>", "homophonesAt(S,0) empty though dict.exact(S) non-empty")
            }

            for (c in d.decodeCovered(s, 30)) if (containsMappedForm(c.word)) {
                fails += Fail(s, "26key", "TC1-traditional-leak", s, c.word,
                    sample(oracle), c.word, "candidate contains a traditional/variant form the build maps away")
            }
            for (h in homo) if (containsMappedForm(h)) {
                fails += Fail(s, "26key", "TC1-traditional-leak", s, h,
                    sample(oracle), h, "homophone drill contains a mapped-away form")
            }
            for (w in oracle) if (mappedAwayUnderReading(w, s)) {
                fails += Fail(s, "26key", "TC1-traditional-leak", s, w,
                    sample(oracle), w, "standalone entry under a reading the build maps away")
            }
            for (c in t9.decodeCovered(digits, 30)) if (containsMappedForm(c.word)) {
                fails += Fail(s, "9key", "TC1-traditional-leak", s, c.word,
                    sample(oracle), c.word, "candidate contains a traditional/variant form the build maps away")
            }
            for (h in t9.homophonesAt(digits, 0)) if (containsMappedForm(h)) {
                fails += Fail(s, "9key", "TC1-traditional-leak", s, h,
                    sample(oracle), h, "homophone drill contains a mapped-away form")
            }
            if (lock != null) for (c in d.decodeCoveredAtomic(lock.letters, 30)) if (containsMappedForm(c.word)) {
                fails += Fail(s, "9key", "TC1-traditional-leak", s, c.word,
                    sample(oracle), c.word, "locked-reading candidate contains a mapped-away form")
            }

            for (target in PinyinDecoder.INPUT_ALIASES[s].orEmpty()) {
                val topAlias = dict.exact(target).filter { isSingleChar(it.word) }.maxByOrNull { it.freq }?.word ?: continue
                val topNative = dict.exact(s).filter { isSingleChar(it.word) }.maxByOrNull { it.freq }?.word
                val strip = d.decodeCovered(s, 30).map { it.word }
                val ai = strip.indexOf(topAlias)
                if (ai < 0) {
                    fails += Fail(s, "26key", "TEN-alias-presence", s, "-",
                        topAlias, sample(strip.take(12).toSet()), "alias target's top single ($target->$topAlias) missing from candidates")
                } else if (topNative != null && strip.indexOf(topNative) !in 0 until ai) {
                    fails += Fail(s, "26key", "TEN-alias-presence", s, "-",
                        "$topNative<$topAlias", "$topAlias@$ai,$topNative@${strip.indexOf(topNative)}",
                        "borrowed reading outranks the input's own top native char")
                }
                if (topAlias !in homo) {
                    fails += Fail(s, "26key", "TEN-alias-presence", s, "-",
                        topAlias, sample(homo), "alias target's top single missing from homophonesAt(S,0)")
                }
            }

            val atom26 = allSingles(d.decodeCoveredAtomic(s, 30))
            val leak26 = atom26 - oracle - allowed
            if (leak26.isNotEmpty()) {
                fails += Fail(s, "26key", "I3", s, seg26.joinToString("+"),
                    sample(oracle), sample(leak26), "decodeCoveredAtomic(S) singles not reading S")
            }
            if (lock != null) {
                val lockedSingles = allSingles(d.decodeCoveredAtomic(lock.letters, 30))
                val leak9 = lockedSingles - oracle - allowed
                if (leak9.isNotEmpty()) {
                    fails += Fail(s, "9key", "I3", s, lock.display,
                        sample(oracle), sample(leak9),
                        "9-key lock '${s}' (letters='${lock.letters}') yields chars not reading S")
                }
            }

            fails += leadingLoss(d, t9, s, digits, emptySet(), emptySet())
        }
        return fails
    }

    private class WordSweep {
        var words = 0
        var unreachable = 0
        var deepest = -1
        val fails = ArrayList<Fail>()
    }

    private fun strideSample(keys: List<String>, cap: Int): List<String> {
        if (keys.size <= cap) return keys
        val step = keys.size.toDouble() / cap
        return (0 until cap).mapTo(LinkedHashSet()) { keys[(it * step).toInt()] }.toList()
    }

    private fun syllableKeyUniverse(): List<String> {
        val syls = runtimeSyllables().sorted()
        val n = syls.size
        val maxKeyLen = 14
        val out = LinkedHashSet<String>()
        fun add(key: String) { if (key.length in 2..maxKeyLen) out.add(key) }
        for (s in syls) add(s)
        for (i in syls.indices) for (j in syls.indices) {
            add(syls[i] + syls[j])
            add(syls[i] + syls[j] + syls[(i + j) % n])
        }
        return out.toList()
    }

    private fun stratifiedWordKeys(source: BinaryDict, universe: List<String>): List<String> {
        val keysPerLen = 1200
        val densestPerLen = 50
        val byLen = sortedMapOf<Int, MutableList<Pair<String, Int>>>()
        for (key in universe) {
            val words = source.exact(key).count { !isSingleChar(it.word) }
            if (words > 0) byLen.getOrPut(key.length) { ArrayList() }.add(key to words)
        }
        return byLen.values.flatMap { rows ->
            val densest = rows
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .take(densestPerLen).map { it.first }
            (strideSample(rows.map { it.first }.sorted(), keysPerLen) + densest).distinct()
        }
    }

    private fun sweepWords(
        d: PinyinDecoder,
        source: BinaryDict,
        layout: String,
        keys: List<String>,
        path: String = "decodeCovered",
        decode: (String) -> List<Cand> = { d.decodeCovered(it, 30) },
    ): WordSweep {
        val st = WordSweep()
        for (key in keys) {
            val oracle = dictWords(source, key)
            if (oracle.isEmpty()) continue
            st.words += oracle.size
            val shown = decode(key)
            val at = HashMap<String, Int>(shown.size * 2)
            for ((i, c) in shown.withIndex()) at.putIfAbsent(c.word, i)
            for (w in oracle) at[w]?.let { if (it > st.deepest) st.deepest = it }
            val missing = oracle.filterNot { it in at }
            if (missing.isEmpty()) continue
            st.unreachable += missing.size
            st.fails.add(
                Fail(
                    key, layout, "W1-word-loss", key, path,
                    sample(oracle), sample(missing),
                    "$path drops ${missing.size} of ${oracle.size} words the dictionary holds for '$key'",
                ),
            )
        }
        return st
    }

    @Test fun mappedFormList_coversEveryFormThisRepoOwnConversionDataMapsAway() {
        val derived = T2SMerge.load(File("../tools/t2s-data")).mappedSourceForms()
            .filter { it.codePointCount(0, it.length) == 1 }
        val missing = derived.filterNot { it in mappedForms }

        assertTrue(
            "tc1_mapped_forms.txt omits ${missing.size} of ${derived.size} forms tools/t2s-data maps away: ${sample(missing)}",
            missing.isEmpty(),
        )
    }

    @Test fun wordReachability_stratifiedKeys_bothLayouts_writesReport() {
        assumeTrue("assets present", dictFile.exists() && lmFile.exists() && t9File.exists() && jianpinFile.exists())
        val universe = syllableKeyUniverse()
        val letterKeys = stratifiedWordKeys(dict, universe)
        val digitKeys = stratifiedWordKeys(t9Dict, universe.mapTo(LinkedHashSet()) { T9Pinyin.toT9(it) }.toList())

        val letterD = letterDecoder()
        val digitD = t9Decoder()
        val letters = sweepWords(letterD, dict, "26key", letterKeys)
        val digits = sweepWords(digitD, t9Dict, "9key", digitKeys)

        val letterAtomicKeys = letterKeys.filter { letterD.syllables(it).size >= 2 }
        val digitAtomicKeys = digitKeys.filter { digitD.syllables(it).size >= 2 }
        val lettersAtomic = sweepWords(letterD, dict, "26key", letterAtomicKeys, "decodeCoveredAtomic") {
            letterD.decodeCoveredAtomic(it, 30, emptySet())
        }
        val digitsAtomic = sweepWords(digitD, t9Dict, "9key", digitAtomicKeys, "decodeCoveredAtomic") {
            digitD.decodeCoveredAtomic(it, 30, emptySet())
        }
        val fails = letters.fails + digits.fails + lettersAtomic.fails + digitsAtomic.fails

        writeTsv(File(outDir(), "levelA_word_reachability.tsv"), fails.sortedWith(compareBy({ it.layout }, { it.input })))
        File(outDir(), "levelA_word_reachability_summary.txt").writeText(buildString {
            appendLine("Level A — every word the dictionary holds for the typed key must be reachable")
            appendLine("keys swept: 26-key ${letterKeys.size}, 9-key ${digitKeys.size}")
            appendLine("per key length the sample is a uniform stride plus the word-densest keys, " +
                "so the densest keys stay in the sample whatever the stride")
            appendLine("words checked: 26-key ${letters.words}, 9-key ${digits.words}")
            appendLine("words unreachable: 26-key ${letters.unreachable}, 9-key ${digits.unreachable}")
            appendLine("deepest reachable word: 26-key ${letters.deepest}, 9-key ${digits.deepest}")
            appendLine("locked path, keys of at least two syllables: 26-key ${letterAtomicKeys.size}, 9-key ${digitAtomicKeys.size}")
            appendLine("locked path words checked: 26-key ${lettersAtomic.words}, 9-key ${digitsAtomic.words}")
            appendLine("locked path words unreachable: 26-key ${lettersAtomic.unreachable}, 9-key ${digitsAtomic.unreachable}")
            appendLine("locked path deepest reachable word: 26-key ${lettersAtomic.deepest}, 9-key ${digitsAtomic.deepest}")
            appendLine("keys losing at least one word: ${fails.size}")
        })

        assertTrue(
            "words the dictionary holds for the typed key are unreachable: " +
                "26-key ${letters.unreachable}/${letters.words}, 9-key ${digits.unreachable}/${digits.words}; " +
                "locked path 26-key ${lettersAtomic.unreachable}/${lettersAtomic.words}, " +
                "9-key ${digitsAtomic.unreachable}/${digitsAtomic.words}; " +
                "first offenders ${fails.take(6).map { "${it.layout}/${it.input}/${it.shownReading}/${it.shownChars}" }}",
            fails.isEmpty(),
        )
        assertTrue("report written", File(outDir(), "levelA_word_reachability.tsv").length() > 0)
    }

    @Test fun exhaustiveN1_bothLayouts_writesReport() {
        assumeTrue("assets present", dictFile.exists() && lmFile.exists() && t9File.exists() && jianpinFile.exists())
        val syls = runtimeSyllables()
        assertTrue("runtime SYLLABLES set looks like ~415 (drift guard): ${syls.size}",
            syls.size in 400..430 && syls.isNotEmpty())

        val d = letterDecoder()
        val fails = sweepN1(d, t9Decoder(), syls)

        writeTsv(File(outDir(), "levelA_n1.tsv"), fails.sortedWith(compareBy({ it.inv }, { it.layout }, { it.input })))
        val byInvLayout = fails.groupingBy { it.inv to it.layout }.eachCount()
        val failedInputs = fails.map { it.input }.toSet()
        File(outDir(), "levelA_n1_summary.txt").writeText(buildString {
            appendLine("Level A — n=1 exhaustive syllable audit")
            appendLine("syllables tested: ${syls.size}")
            appendLine("syllables holding single characters: letters ${syls.count { dictSingles(it).isNotEmpty() }}" +
                ", digits ${syls.count { t9Singles(T9Pinyin.toT9(it)).isNotEmpty() }}")
            appendLine("TC1 leak scan covers n=1 only: 26-key decodeCovered top-30 and homophonesAt, " +
                "9-key decodeCovered top-30, homophonesAt and the locked-reading path; " +
                "longer inputs are not scanned for mapped-away forms")
            appendLine("TC1 occurrence list: ${mappedForms.size} forms, any codepoint of any candidate; " +
                "reading-scoped list: ${readingScopedMappedForms.values.sumOf { it.size }} form/reading pairs " +
                "over ${readingScopedMappedForms.size} forms, held against the letter dictionary's own " +
                "standalone entries, whose key is the reading; digit and initials keys cannot name a reading")
            appendLine("distinct offending syllables: ${failedInputs.size}")
            appendLine("total invariant violations: ${fails.size}")
            appendLine("per invariant×layout:")
            byInvLayout.toSortedMap(compareBy { it.first + it.second }).forEach { (k, v) ->
                appendLine("  ${k.first} ${k.second}: $v")
            }
        })

        assertTrue("reading-scoped mapped-away list is empty, so the standalone arm covers nothing",
            readingScopedMappedForms.isNotEmpty())
        val tradLeaks = fails.filter { it.inv == "TC1-traditional-leak" }
        assertTrue("no-traditional gate: traditional/variant forms leaked into candidates: ${tradLeaks.take(6)}", tradLeaks.isEmpty())
        assertTrue("n=1 offenders must be 0 after the fix; remaining: ${failedInputs.sorted()}", fails.isEmpty())
        assertTrue("report written", File(outDir(), "levelA_n1.tsv").length() > 0)
    }

    @Test fun leftToRightReachability_bothLayouts_writesReport() {
        assumeTrue("assets present", dictFile.exists() && lmFile.exists() && t9File.exists() && jianpinFile.exists())
        val syls = runtimeSyllables().sorted()
        val pairTails = listOf("hao", "an")
        val tripleStride = 10
        val tripleSlice = syls.filterIndexed { i, _ -> i % tripleStride == 0 }
        val inputs = syls.flatMap { head -> pairTails.map { listOf(head, it) } } +
            tripleSlice.map { head -> listOf(head, "hao", "ma") }

        val fails = sweepLeftToRight(letterDecoder(), t9Decoder(), inputs)

        writeTsv(File(outDir(), "levelA_left_to_right.tsv"), fails.sortedWith(compareBy({ it.layout }, { it.input })))
        File(outDir(), "levelA_left_to_right_summary.txt").writeText(buildString {
            appendLine("Level A — leading-syllable reachability walked left to right")
            appendLine("pairs: ${syls.size} syllables x ${pairTails.size} tails ($pairTails)")
            appendLine("triples: every ${tripleStride}th syllable (${tripleSlice.size} of ${syls.size}) x hao x ma")
            appendLine("syllable positions checked: ${inputs.sumOf { it.size }}")
            appendLine("keyspaces: letters + digits; paths: decodeCovered + decodeCoveredAtomic")
            appendLine("character-loss violations: ${fails.size}")
        })

        assertTrue(
            "characters unreachable at some syllable index: ${fails.take(6).map { "${it.input}/${it.layout}/${it.shownReading}" }}",
            fails.isEmpty(),
        )
        assertTrue("report written", File(outDir(), "levelA_left_to_right.tsv").length() > 0)
    }

    @Test fun exhaustiveN2_allPairs_writesReport() {
        assumeTrue("full sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue("assets present", dictFile.exists() && lmFile.exists() && jianpinFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val fails = ArrayList<Fail>()
        var done = 0
        val total = syls.size.toLong() * syls.size
        for (s1 in syls) {
            val fd1 = T9Pinyin.toT9(s1)
            for (s2 in syls) {
                val input = s1 + s2
                val seg = d.syllables(input).map { it.reading }
                if (seg != listOf(s1, s2)) {
                    fails += Fail(input, "26key", "I1", "$s1+$s2", seg.joinToString("+"),
                        "-", "-", "pair mis-segments")
                }
                val lock = T9Pinyin.lockFirstReading(fd1 + T9Pinyin.toT9(s2), s1)
                if (lock == null || !lock.display.startsWith(s1)) {
                    fails += Fail(input, "9key", "I1", s1, lock?.display ?: "<null>",
                        "-", "-", "lockFirstReading first label != s1 for pair")
                }
            }
            done += syls.size
            if (s1 == syls[syls.size / 4] || s1 == syls[syls.size / 2] || s1 == syls[3 * syls.size / 4]) {
                println("[audit n2] progress ~${done}/${total}")
            }
        }
        writeTsv(File(outDir(), "levelA_n2.tsv"), fails)
        val distinct = fails.map { it.input }.toSet().size
        val nasal = fails.count { classify(it) == "nasal-split" }
        val reflow = fails.count { classify(it) == "other-reflow" }
        val merge = fails.count { classify(it) == "expected-merge" }
        File(outDir(), "levelA_n2_summary.txt").writeText(
            "Level A — n=2 all ordered pairs\npairs tested: $total\noffending pairs: $distinct\ntotal violations: ${fails.size}\n" +
                "I1 26key: ${fails.count { it.inv == "I1" && it.layout == "26key" }}\n" +
                "I1 9key:  ${fails.count { it.inv == "I1" && it.layout == "9key" }}\n" +
                "nasal-split: $nasal\nother-reflow: $reflow\nexpected-merge: $merge\n"
        )
        assertTrue("n=2 nasal-split must be 0 after the fix (got $nasal)", nasal == 0)
    }

    @Test fun coveringN3_writesReport() {
        assumeTrue("full sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue("assets present", dictFile.exists() && lmFile.exists() && jianpinFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val fails = ArrayList<Fail>()
        for (i in syls.indices) {
            val a = syls[i]
            for (j in syls.indices) {
                val b = syls[j]
                val c = syls[(i + j) % syls.size]
                val input = a + b + c
                val seg = d.syllables(input).map { it.reading }
                if (seg != listOf(a, b, c)) {
                    fails += Fail(input, "26key", "I1", "$a+$b+$c", seg.joinToString("+"),
                        "-", "-", "triple mis-segments")
                }
            }
        }
        writeTsv(File(outDir(), "levelA_n3.tsv"), fails)
        val nasal = fails.count { classify(it) == "nasal-split" }
        val reflow = fails.count { classify(it) == "other-reflow" }
        val merge = fails.count { classify(it) == "expected-merge" }
        File(outDir(), "levelA_n3_summary.txt").writeText(
            "Level A — n=3 complete-covering sweep (~415² triples; n>=4 NOT enumerated)\n" +
                "triples tested: ${syls.size.toLong() * syls.size}\noffending triples: ${fails.map { it.input }.toSet().size}\n" +
                "total violations: ${fails.size}\nnasal-split: $nasal\nother-reflow: $reflow\nexpected-merge: $merge\n"
        )
        assertTrue("n=3 nasal-split must be 0 after the fix (got $nasal)", nasal == 0)
    }
}
