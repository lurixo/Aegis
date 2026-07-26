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
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BoostCurveReportTest {

    private companion object {
        fun env(k: String): String? = System.getenv(k)?.takeIf { it.isNotBlank() }
        val enabled: Boolean get() = env("AEGIS_BOOST_REPORT") == "1"
        val smoke: Boolean get() = env("AEGIS_BOOST_SMOKE") == "1"
        val threads: Int get() = env("AEGIS_BOOST_THREADS")?.toIntOrNull() ?: 4

        const val STRIP_LIMIT = 30
        const val COUNT_CAP = 65536
        const val T9_STRIDE = 37

        fun t9Probe(caseId: String): Boolean = caseId.hashCode().mod(T9_STRIDE) == 0
        val RATIO_TARGETS = listOf(2.0, 5.0, 10.0, 100.0)
        const val RATIO_TOL = 0.05

        val assetsDictFile = File("src/main/assets/aegis_dict.bin")
        val assetsT9File = File("src/main/assets/aegis_t9.bin")
        val assetsJianpinFile = File("src/main/assets/aegis_jianpin.bin")
        val lmFile = File("src/main/assets/aegis_lm.bin")

        val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
        val assetsDict: BinaryDict by lazy { BinaryDict.fromFile(assetsDictFile) }
        val assetsT9: BinaryDict by lazy { BinaryDict.fromFile(assetsT9File) }
        val assetsJianpin: BinaryDict by lazy { BinaryDict.fromFile(assetsJianpinFile) }
        val fullDict: BinaryDict by lazy { BinaryDict.fromFile(File(env("AEGIS_FULLDICT_DIR")!!, "aegis_dict.bin")) }
        val fullT9: BinaryDict by lazy { BinaryDict.fromFile(File(env("AEGIS_FULLDICT_DIR")!!, "aegis_t9.bin")) }
        val fullJianpin: BinaryDict by lazy { BinaryDict.fromFile(File(env("AEGIS_FULLDICT_DIR")!!, "aegis_jianpin.bin")) }
        val octagram: OctagramReader by lazy { OctagramReader.fromFile(File(env("AEGIS_GRAM")!!)) }

        val runStamp: String by lazy {
            val rev = runCatching {
                ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true)
                    .start().inputStream.bufferedReader().readText().trim()
            }.getOrDefault("unknown")
            "run=${System.currentTimeMillis()} git=$rev"
        }
    }

    private fun requireProductionAssets() {
        assumeTrue("gated: set AEGIS_BOOST_REPORT=1", enabled)
        assumeTrue("bundled LM present", lmFile.exists())
        val dir = env("AEGIS_FULLDICT_DIR")
        assumeTrue("AEGIS_FULLDICT_DIR set", !dir.isNullOrEmpty())
        for (n in listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")) {
            assumeTrue("full pack file $n present", File(dir!!, n).exists())
        }
        assumeTrue("AEGIS_GRAM set + present", env("AEGIS_GRAM")?.let { File(it).exists() } == true)
    }

    private fun outDir(): File {
        val p = env("AEGIS_AUDIT_DIR") ?: "build/decode-audit"
        return File(p).apply { mkdirs() }
    }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val syls = (f.get(T9Pinyin) as Set<String>).toList().sorted()
        assertTrue("runtime SYLLABLES set looks like ~415 (drift guard): ${syls.size}", syls.size in 400..430)
        return if (smoke) syls.filterIndexed { i, _ -> i % 20 == 0 } else syls
    }

    private class Ctx(dict: BinaryDict, t9Dict: BinaryDict, jianpin: BinaryDict?, oct: OctagramReader?) {
        val um = UserModel()
        val letter = PinyinDecoder(dict, lm, userModel = um, initialsDict = jianpin, octagram = oct)
        val t9 = PinyinDecoder(t9Dict, lm, userModel = um, octagram = oct, aliasDict = dict)
    }

    private fun stripOf(ctx: Ctx, t9: Boolean, reading: String): List<String> =
        if (!t9) {
            ctx.letter.decodeCovered(reading, STRIP_LIMIT).map { it.word }
        } else {
            ctx.t9.decodeCovered(T9Pinyin.toT9(reading), STRIP_LIMIT).map { it.word }
                .filterNot { w -> w.all { it.code < 128 } }
        }

    private fun aheadAt(ctx: Ctx, t9: Boolean, reading: String, b: String, a: String?, selfReading: String?, c: Int): Boolean {
        ctx.um.removeWord(b)
        if (selfReading != null) repeat(c) { ctx.um.recordWord(selfReading, b, it.toLong(), incrementCount = true) }
        else repeat(c) { ctx.um.record(null, b, it.toLong()) }
        val strip = stripOf(ctx, t9, reading)
        ctx.um.removeWord(b)
        val ib = strip.indexOf(b)
        if (a == null) return ib == 0
        if (ib < 0) return false
        val ia = strip.indexOf(a)
        return ia < 0 || ib < ia
    }

    private fun rankAt(ctx: Ctx, t9: Boolean, reading: String, b: String, selfReading: String?, c: Int): Int {
        ctx.um.removeWord(b)
        if (selfReading != null) repeat(c) { ctx.um.recordWord(selfReading, b, it.toLong(), incrementCount = true) }
        else repeat(c) { ctx.um.record(null, b, it.toLong()) }
        val strip = stripOf(ctx, t9, reading)
        ctx.um.removeWord(b)
        return strip.indexOf(b)
    }

    private fun minCount(pred: (Int) -> Boolean): Pair<Int, Boolean> {
        if (pred(0)) return 0 to true
        var lo = 0
        var hi = 1
        while (true) {
            if (hi >= COUNT_CAP) {
                hi = COUNT_CAP
                if (!pred(hi)) return -1 to true
                break
            }
            if (pred(hi)) break
            lo = hi
            hi = hi shl 1
        }
        while (hi - lo > 1) {
            val m = lo + (hi - lo) / 2
            if (pred(m)) hi = m else lo = m
        }
        val verified = pred(hi) && (hi == 0 || !pred(hi - 1))
        return hi to verified
    }

    private class Sink(file: File, header: String) {
        private val w = file.bufferedWriter()
        private val agg = ConcurrentHashMap<String, MutableList<Int>>()
        val rows = AtomicInteger(0)
        var censored = AtomicInteger(0)
        var unverified = AtomicInteger(0)

        init { w.write("$header\n") }

        @Synchronized fun row(line: String) { w.write(line); w.write("\n"); rows.incrementAndGet() }

        fun record(binKey: String, c: Int, verified: Boolean) {
            if (!verified) unverified.incrementAndGet()
            if (c < 0) { censored.incrementAndGet(); return }
            agg.getOrPut(binKey) { java.util.Collections.synchronizedList(ArrayList()) }.add(c)
        }

        @Synchronized fun close() { w.flush(); w.close() }

        fun summary(): String {
            val sb = StringBuilder()
            for ((k, vRaw) in agg.entries.sortedBy { it.key }) {
                val v = vRaw.sorted()
                fun pct(p: Double) = v[((v.size - 1) * p).toInt()]
                sb.append(
                    "$k\tn=${v.size}\tmin=${v.first()}\tp25=${pct(0.25)}\tmedian=${pct(0.5)}\t" +
                        "p75=${pct(0.75)}\tp90=${pct(0.90)}\tmax=${v.last()}\n"
                )
            }
            sb.append("censored(>${COUNT_CAP})=${censored.get()}\tunverifiedBoundaries=${unverified.get()}\n")
            return sb.toString()
        }
    }

    private fun <T> runParallel(tasks: List<Callable<T>>) {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = pool.invokeAll(tasks)
            for (f in futures) f.get()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun freqDecade(f: Int): String = "1e${f.toString().length - 1}"

    private fun ratioBin(ratio: Double): String? {
        for (t in RATIO_TARGETS) if (ratio >= t * (1 - RATIO_TOL) && ratio <= t * (1 + RATIO_TOL)) {
            return "x${t.toInt()}"
        }
        return null
    }

    @Test fun s1_singles_sameReading_riseCurve() {
        requireProductionAssets()
        val syls = runtimeSyllables()
        val sink = Sink(
            File(outDir(), "boost_s1_singles.tsv"),
            "# $runStamp\nreading\tpairKind\tanchor\trankA\tA\tfreqA\tB\tfreqB\tratio\tcAhead\tcDefault\tt9cAhead\tverified\tnote",
        )
        val tl = ThreadLocal.withInitial { Ctx(fullDict, fullT9, fullJianpin, octagram) }
        val done = AtomicInteger(0)
        val t0 = System.currentTimeMillis()

        runParallel(syls.map { s ->
            Callable {
                val ctx = tl.get()!!
                val singleFreq = LinkedHashMap<String, Int>()
                var aliasWords = emptySet<String>()
                for (wf in fullDict.exact(s)) if (isSingleChar(wf.word)) singleFreq.putIfAbsent(wf.word, wf.freq)
                PinyinDecoder.INPUT_ALIASES[s]?.forEach { target ->
                    for (wf in fullDict.exact(target)) if (isSingleChar(wf.word) && singleFreq.putIfAbsent(wf.word, wf.freq) == null) {
                        aliasWords = aliasWords + wf.word
                    }
                }
                if (singleFreq.size < 2) return@Callable
                val baseline = stripOf(ctx, t9 = false, reading = s)
                val vis = baseline.filter { isSingleChar(it) && it in singleFreq }
                val seen = HashSet<Pair<String, String>>()

                fun measure(pairKind: String, anchor: String, rankA: Int, a: String, b: String) {
                    if (a == b || !seen.add(a to b)) return
                    val fA = singleFreq.getValue(a)
                    val fB = singleFreq.getValue(b)
                    val (cAhead, v1) = minCount { c -> aheadAt(ctx, false, s, b, a, null, c) }
                    var v = v1
                    var cDefault = -2
                    if (rankA == 0 && pairKind == "adjacent") {
                        val (cd, v2) = minCount { c -> aheadAt(ctx, false, s, b, null, null, c) }
                        cDefault = cd; v = v && v2
                    }
                    var t9c = -2
                    if (t9Probe(s + "|" + a + "|" + b)) {
                        val (ct, v3) = minCount { c -> aheadAt(ctx, true, s, b, a, null, c) }
                        t9c = ct; v = v && v3
                    }
                    val ratio = fA.toDouble() / fB
                    val note = if (a in aliasWords || b in aliasWords) "alias" else ""
                    sink.row("$s\t$pairKind\t$anchor\t$rankA\t$a\t$fA\t$b\t$fB\t${"%.3f".format(ratio)}\t$cAhead\t$cDefault\t$t9c\t$v\t$note")
                    sink.record("$pairKind|$anchor|fA=${freqDecade(fA)}", cAhead, v)
                    if (cDefault >= 0) sink.record("$pairKind|toDefault|fA=${freqDecade(fA)}", cDefault, v)
                }

                for (i in 0 until vis.size - 1) {
                    measure("adjacent", if (i == 0) "top" else "deeper", i, vis[i], vis[i + 1])
                }
                val all = singleFreq.entries.filter { it.key !in aliasWords }.sortedByDescending { it.value }
                val anchors = buildList {
                    vis.firstOrNull { it !in aliasWords }?.let { add("top" to it) }
                    if (vis.size >= 4) vis[vis.size / 2].takeIf { it !in aliasWords }?.let { add("mid" to it) }
                }
                for ((label, a) in anchors) {
                    val fA = singleFreq.getValue(a)
                    var perBand = 0
                    for ((b, fB) in all.map { it.key to it.value }) {
                        if (b == a || fB <= 0) continue
                        val bin = ratioBin(fA.toDouble() / fB) ?: continue
                        if (smoke && ++perBand > 3) break
                        measure(bin, label, baseline.indexOf(a), a, b)
                    }
                }
                val d = done.incrementAndGet()
                if (d % 50 == 0) println("S1 $d/${syls.size} syllables, ${System.currentTimeMillis() - t0}ms")
            }
        })
        sink.close()
        File(outDir(), "boost_s1_summary.txt").writeText("# $runStamp\nS1 singles rise-curve\n${sink.summary()}")
        assertTrue("S1 must measure a non-trivial pair universe: ${sink.rows.get()}", sink.rows.get() > 100)
        println("S1 done in ${System.currentTimeMillis() - t0}ms, rows=${sink.rows.get()}")
    }

    @Test fun s2_words_sameKey_riseCurve() {
        requireProductionAssets()
        val syls = runtimeSyllables()
        val sink = Sink(
            File(outDir(), "boost_s2_words.tsv"),
            "# $runStamp\nkey\tpairKind\tanchor\tA\tfreqA\tB\tfreqB\tratio\tdefaultIsA\tcAhead\tcDefault\tt9cAhead\tverified",
        )
        val tl = ThreadLocal.withInitial { Ctx(fullDict, fullT9, fullJianpin, octagram) }
        val done = AtomicInteger(0)
        val keys = AtomicInteger(0)
        val t0 = System.currentTimeMillis()

        runParallel(syls.map { s1 ->
            Callable {
                val ctx = tl.get()!!
                for (s2 in syls) {
                    val key = s1 + s2
                    val multi = fullDict.exact(key).filter { !isSingleChar(it.word) }
                    if (multi.size < 2) continue
                    keys.incrementAndGet()
                    val a = multi[0]
                    val baseline0 = stripOf(ctx, t9 = false, reading = key).firstOrNull() ?: continue
                    val defaultIsA = baseline0 == a.word

                    fun measure(pairKind: String, b: BinaryDict.WordFreq) {
                        val (cAhead, v1) = minCount { c -> aheadAt(ctx, false, key, b.word, a.word, null, c) }
                        var v = v1
                        var cDefault = -2
                        if (defaultIsA && pairKind == "adjacent") {
                            val (cd, v2) = minCount { c -> aheadAt(ctx, false, key, b.word, null, null, c) }
                            cDefault = cd; v = v && v2
                        }
                        var t9c = -2
                        if (t9Probe(key + "|" + b.word)) {
                            val (ct, v3) = minCount { c -> aheadAt(ctx, true, key, b.word, a.word, null, c) }
                            t9c = ct; v = v && v3
                        }
                        val ratio = a.freq.toDouble() / b.freq.coerceAtLeast(1)
                        sink.row(
                            "$key\t$pairKind\ttop\t${a.word}\t${a.freq}\t${b.word}\t${b.freq}\t" +
                                "${"%.3f".format(ratio)}\t$defaultIsA\t$cAhead\t$cDefault\t$t9c\t$v",
                        )
                        sink.record("$pairKind|top|fA=${freqDecade(a.freq)}", cAhead, v)
                        if (cDefault >= 0) sink.record("$pairKind|toDefault|fA=${freqDecade(a.freq)}", cDefault, v)
                    }

                    measure("adjacent", multi[1])
                    var perBand = 0
                    for ((bi, b) in multi.withIndex()) {
                        if (bi == 0) continue
                        val bin = ratioBin(a.freq.toDouble() / b.freq.coerceAtLeast(1)) ?: continue
                        if (smoke && ++perBand > 3) break
                        val (cAhead, v1) = minCount { c -> aheadAt(ctx, false, key, b.word, a.word, null, c) }
                        var v = v1
                        var t9c = -2
                        if (t9Probe(key + "|" + b.word)) {
                            val (ct, v3) = minCount { c -> aheadAt(ctx, true, key, b.word, a.word, null, c) }
                            t9c = ct; v = v && v3
                        }
                        sink.row(
                            "$key\t$bin\ttop\t${a.word}\t${a.freq}\t${b.word}\t${b.freq}\t" +
                                "${"%.3f".format(a.freq.toDouble() / b.freq)}\t$defaultIsA\t$cAhead\t-2\t$t9c\t$v",
                        )
                        sink.record("$bin|top|fA=${freqDecade(a.freq)}", cAhead, v)
                    }
                }
                val d = done.incrementAndGet()
                if (d % 25 == 0) println("S2 $d/${syls.size} first-syllable rows, keys=${keys.get()}, ${System.currentTimeMillis() - t0}ms")
            }
        })
        sink.close()
        File(outDir(), "boost_s2_summary.txt")
            .writeText("# $runStamp\nS2 words rise-curve (2-syllable keys with >=2 words: ${keys.get()})\n${sink.summary()}")
        assertTrue("S2 must measure a non-trivial key universe: ${sink.rows.get()}", sink.rows.get() > 100)
        println("S2 done in ${System.currentTimeMillis() - t0}ms, keys=${keys.get()}, rows=${sink.rows.get()}")
    }

    @Test fun s3_sentenceDefault_vsWord_riseCurve() {
        requireProductionAssets()
        val syls = runtimeSyllables()
        val sink = Sink(
            File(outDir(), "boost_s3_sentence.tsv"),
            "# $runStamp\nkey\tnSyl\tnaturalDefault\tB\tfreqB\tcDefault\tt9cDefault\tverified",
        )
        val tl = ThreadLocal.withInitial { Ctx(fullDict, fullT9, fullJianpin, octagram) }
        val done = AtomicInteger(0)
        val t0 = System.currentTimeMillis()

        fun probe(ctx: Ctx, key: String, nSyl: Int) {
            val words = fullDict.exact(key)
            val multi = words.filter { !isSingleChar(it.word) }
            if (multi.isEmpty()) return
            val nat = stripOf(ctx, t9 = false, reading = key).firstOrNull() ?: return
            if (words.any { it.word == nat }) return
            val b = multi[0]
            if (b.word == nat) return
            val (cDefault, v1) = minCount { c -> aheadAt(ctx, false, key, b.word, null, null, c) }
            var v = v1
            var t9c = -2
            if (t9Probe(key + "|" + b.word)) {
                val (ct, v3) = minCount { c -> aheadAt(ctx, true, key, b.word, null, null, c) }
                t9c = ct; v = v && v3
            }
            sink.row("$key\t$nSyl\t$nat\t${b.word}\t${b.freq}\t$cDefault\t$t9c\t$v")
            sink.record("sentence$nSyl|toDefault|fB=${freqDecade(b.freq)}", cDefault, v)
        }

        runParallel(syls.map { s1 ->
            Callable {
                val ctx = tl.get()!!
                for (s2 in syls) probe(ctx, s1 + s2, 2)
                val d = done.incrementAndGet()
                if (d % 25 == 0) println("S3 $d/${syls.size} first-syllable rows, ${System.currentTimeMillis() - t0}ms")
            }
        })
        val n = syls.size
        runParallel(syls.mapIndexed { i, s1 ->
            Callable {
                val ctx = tl.get()!!
                probe(ctx, s1 + syls[(i * 7 + 3) % n] + syls[(i * 13 + 11) % n], 3)
            }
        })
        sink.close()
        File(outDir(), "boost_s3_summary.txt")
            .writeText("# $runStamp\nS3 sentence-default vs word (cases: ${sink.rows.get()})\n${sink.summary()}")
        println("S3 done in ${System.currentTimeMillis() - t0}ms, rows=${sink.rows.get()}")
    }

    @Test fun s4_selfCreatedWord_recheck() {
        assumeTrue("gated: set AEGIS_BOOST_REPORT=1", enabled)
        assumeTrue("assets pack present", assetsDictFile.exists() && assetsT9File.exists() && assetsJianpinFile.exists() && lmFile.exists())
        val cases = listOf(
            "shide" to "是得", "ceguo" to "测国", "nihaoma" to "你豪马", "shishi" to "是时", "cishi" to "此是",
        )
        val out = StringBuilder("# $runStamp\nS4 self-created-word recheck\n")
        out.append("config\treading\tword\tinDict\trank@c1\tcTo3rd\tcTo2nd\tcTo1st(#0)\ttrajectory(c->rank)\tt9rank@c1\tverified\n")

        data class Config(val name: String, val dict: BinaryDict, val t9: BinaryDict, val jp: BinaryDict?, val oct: OctagramReader?)
        val configs = buildList {
            add(Config("assets-noOctagram", assetsDict, assetsT9, assetsJianpin, null))
            if (env("AEGIS_FULLDICT_DIR") != null && env("AEGIS_GRAM") != null) {
                add(Config("full-production", fullDict, fullT9, fullJianpin, octagram))
            }
        }

        for (cfg in configs) {
            val ctx = Ctx(cfg.dict, cfg.t9, cfg.jp, cfg.oct)
            for ((reading, word) in cases) {
                val inDict = cfg.dict.exact(reading).any { it.word == word }
                val selfReading = if (inDict) null else reading
                val rank1 = rankAt(ctx, false, reading, word, selfReading, 1)
                val (c3, v3) = minCount { c -> rankAt(ctx, false, reading, word, selfReading, c) in 0..2 }
                val (c2, v2) = minCount { c -> rankAt(ctx, false, reading, word, selfReading, c) in 0..1 }
                val (c0, v0) = minCount { c -> rankAt(ctx, false, reading, word, selfReading, c) == 0 }
                val traj = listOf(1, 4, 16, 64, 256, 512, 1024, 1536, 2048, 4096)
                    .joinToString(" ") { "$it->${rankAt(ctx, false, reading, word, selfReading, it)}" }
                val t9rank1 = rankAt(ctx, true, reading, word, selfReading, 1)
                out.append("${cfg.name}\t$reading\t$word\t$inDict\t$rank1\t$c3\t$c2\t$c0\t$traj\t$t9rank1\t${v0 && v2 && v3}\n")
                if (!inDict) {
                    assertTrue("self-created $word recalled at count=1 in ${cfg.name}", rank1 >= 0)
                    assertTrue("fresh self-created $word must not seize #0 at count=1 (${cfg.name})", rank1 >= 1)
                }
            }
        }
        File(outDir(), "boost_s4_userword.txt").writeText(out.toString())
        println(out.toString())
    }
}
