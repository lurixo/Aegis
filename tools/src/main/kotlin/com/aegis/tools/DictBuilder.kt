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

package com.aegis.tools

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File

fun main(rawArgs: Array<String>) {
    when (rawArgs.firstOrNull()) {
        "lm" -> { LmBuilder.build(rawArgs.copyOfRange(1, rawArgs.size)); return }
        "en" -> { EnBuilder.build(rawArgs.copyOfRange(1, rawArgs.size)); return }
        "prefix-index" -> { PrefixIndexBuilder.build(rawArgs.copyOfRange(1, rawArgs.size)); return }
    }
    val args = Args(rawArgs)
    val out = File(args.required("--out"))
    val inputs = args.positionals.map { File(it) }
    require(inputs.isNotEmpty()) { "no input dict files given" }
    val minFreq = args.optional("--min-freq")?.toInt() ?: 0
    val keyType = args.optional("--keytype") ?: "letter"
    val maxPerKey = args.optional("--max-per-key")?.toInt() ?: Int.MAX_VALUE
    val keepSyllableSingles = args.optional("--keep-syllable-singles")?.toInt() ?: 0
    val t2s = args.optional("--t2s-data")?.let { T2SMerge.load(File(it), collectSourceReadings(inputs)) }
    val syllablesOut = args.optional("--syllables")?.let { File(it) }
    val coverageOut = args.optional("--coverage")?.let { File(it) }

    val tmpRecords = File.createTempFile("aegis-dict-", ".tsv").apply { deleteOnExit() }
    val tmpSorted = File.createTempFile("aegis-dict-sorted-", ".tsv").apply { deleteOnExit() }

    val syllableCounts = HashMap<String, Long>()
    var totalRows = 0L
    var kept = 0L
    var skippedNonAscii = 0L
    var skippedMalformed = 0L
    var skippedNormalization = 0L
    var skippedLowFreq = 0L

    val completeness = if (keepSyllableSingles > 0) SyllableCompleteness(keepSyllableSingles) else null
    tmpRecords.bufferedWriter().use { w ->
        for (file in inputs) {
            println("parsing ${file.name} ...")
            file.bufferedReader().use { r -> parseDict(r, w, minFreq, keyType, syllableCounts, completeness, t2s) { kind ->
                when (kind) {
                    Skip.ROW -> totalRows++
                    Skip.KEPT -> kept++
                    Skip.NON_ASCII -> skippedNonAscii++
                    Skip.MALFORMED -> skippedMalformed++
                    Skip.NORMALIZATION -> skippedNormalization++
                    Skip.LOW_FREQ -> skippedLowFreq++
                }
            } }
        }
        completeness?.emitTopUps(w, keyType)
    }
    println(
        "parsed rows=$totalRows kept=$kept skippedNonAscii=$skippedNonAscii " +
            "skippedMalformed=$skippedMalformed skippedNormalization=$skippedNormalization skippedLowFreq=$skippedLowFreq"
    )

    val tmpByWord = File.createTempFile("aegis-dict-byword-", ".tsv").apply { deleteOnExit() }
    val tmpMerged = File.createTempFile("aegis-dict-merged-", ".tsv").apply { deleteOnExit() }
    externalSortByKeyWord(tmpRecords, tmpByWord)
    val mergedCount = mergeAdjacentDuplicates(tmpByWord, tmpMerged)
    println("canonical merge: $mergedCount duplicate (key, word) rows folded")
    tmpMerged.copyTo(tmpRecords, overwrite = true)
    if (t2s != null) {
        println(
            "t2s: converted words=${t2s.convertedWords} phraseHits=${t2s.phraseHits} charHits=${t2s.charHits} " +
                "readingOverrides=${t2s.overrideHits} rejectedMisaligned=${t2s.rejectedMisaligned} " +
                "rejectedMissingReading=${t2s.rejectedMissingReading} " +
                "rejectedIncompatibleReading=${t2s.rejectedIncompatibleReading}"
        )
    }
    externalSort(tmpRecords, tmpSorted)
    println("sorted -> ${tmpSorted.length()} bytes")

    val (numKeys, numEntries) = writeBinary(tmpSorted, out, maxPerKey, t2s?.mappedSourceForms())
    println("wrote ${out.path}: keys=$numKeys entries=$numEntries size=${out.length()} bytes")

    syllablesOut?.let { writeSyllables(it, syllableCounts) }
    coverageOut?.let { writeCoverage(it, syllableCounts) }
}

private enum class Skip { ROW, KEPT, NON_ASCII, MALFORMED, NORMALIZATION, LOW_FREQ }

internal enum class NormalizedRowReject { NON_ASCII, MALFORMED, NORMALIZATION }

internal data class NormalizedDictRow(
    val word: String,
    val syllables: List<String>,
    val freq: Int,
    val sourceTag: String,
)

internal fun scanNormalizedDict(
    r: BufferedReader,
    t2s: T2SMerge?,
    onRow: (NormalizedDictRow) -> Unit,
    onRejected: (NormalizedRowReject) -> Unit,
) {
    var inData = false
    while (true) {
        val line = r.readLine() ?: break
        if (!inData) {
            if (line.trim() == "...") inData = true
            continue
        }
        if (line.isEmpty() || line.startsWith('#')) continue
        val cols = line.split('\t')
        if (cols.size < 2) continue
        val rawWord = cols[0]
        val syllables = Pinyin.stripTones(cols[1]).split(' ').filter { it.isNotEmpty() }
        if (syllables.isEmpty() || syllables.any { !Pinyin.isAsciiSyllable(it) }) {
            onRejected(NormalizedRowReject.NON_ASCII)
            continue
        }
        if (sourceWordIsMalformed(rawWord, syllables.size)) {
            onRejected(NormalizedRowReject.MALFORMED)
            continue
        }
        val word = if (t2s == null) rawWord else {
            val converted = t2s.convert(rawWord, syllables)
            if (converted.word == null) {
                onRejected(NormalizedRowReject.NORMALIZATION)
                continue
            }
            converted.word
        }
        onRow(
            NormalizedDictRow(
                word,
                syllables,
                cols.getOrNull(2)?.trim()?.toIntOrNull() ?: 1,
                if (t2s != null && word != rawWord) rawWord else "",
            ),
        )
    }
}

private fun parseDict(
    r: BufferedReader,
    w: BufferedWriter,
    minFreq: Int,
    keyType: String,
    syllableCounts: HashMap<String, Long>,
    completeness: SyllableCompleteness?,
    t2s: T2SMerge?,
    tally: (Skip) -> Unit,
) {
    scanNormalizedDict(r, t2s, { row ->
        tally(Skip.ROW)
        val freq = row.freq.coerceAtLeast(1)
        if (freq < minFreq) {
            completeness?.offerBelowThreshold(row.syllables, row.word, freq)
            tally(Skip.LOW_FREQ)
        } else {
            completeness?.noteKept(row.syllables, row.word)
            for (s in row.syllables) syllableCounts.merge(s, 1L, Long::plus)
            val letterKey = row.syllables.joinToString("")
            val key = when (keyType) {
                "digit" -> Pinyin.toT9(letterKey)
                "initials" -> row.syllables.joinToString("") { it.substring(0, 1) }
                else -> letterKey
            }
            w.write(key); w.write("\t"); w.write(row.word); w.write("\t"); w.write(freq.toString())
            if (row.sourceTag.isNotEmpty()) { w.write("\t"); w.write(row.sourceTag) }
            w.write("\n")
            tally(Skip.KEPT)
        }
    }, { rejection ->
        tally(Skip.ROW)
        tally(
            when (rejection) {
                NormalizedRowReject.NON_ASCII -> Skip.NON_ASCII
                NormalizedRowReject.MALFORMED -> Skip.MALFORMED
                NormalizedRowReject.NORMALIZATION -> Skip.NORMALIZATION
            }
        )
    })
}

internal fun collectSourceReadings(inputs: List<File>): Map<String, Set<String>> {
    val readings = HashMap<String, HashSet<String>>()
    for (file in inputs) {
        var inData = false
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (!inData) {
                    if (line.trim() == "...") inData = true
                } else if (line.isNotEmpty() && !line.startsWith('#')) {
                    val cols = line.split('\t')
                    if (cols.size >= 2) {
                        val word = cols[0]
                        val syllables = Pinyin.stripTones(cols[1]).split(' ').filter { it.isNotEmpty() }
                        if (syllables.size == 1 && Pinyin.isAsciiSyllable(syllables[0]) &&
                            !sourceWordIsMalformed(word, 1)
                        ) {
                            readings.getOrPut(word) { HashSet() }.add(syllables[0])
                        }
                    }
                }
            }
        }
    }
    return readings
}

private fun sourceWordIsMalformed(word: String, syllableCount: Int): Boolean {
    val codePoints = word.codePoints().toArray()
    if (codePoints.size != syllableCount) return true
    return codePoints.any { Character.isWhitespace(it) || Character.isISOControl(it) }
}

private class SyllableCompleteness(private val target: Int) {
    private fun isSingleChar(w: String) = w.codePointCount(0, w.length) == 1
    private val keptBySyllable = HashMap<String, HashSet<String>>()
    private val belowBySyllable = HashMap<String, HashMap<String, Int>>()

    fun noteKept(syllables: List<String>, word: String) {
        if (syllables.size != 1 || !isSingleChar(word)) return
        keptBySyllable.getOrPut(syllables[0]) { HashSet() }.add(word)
    }

    fun offerBelowThreshold(syllables: List<String>, word: String, freq: Int) {
        if (syllables.size != 1 || !isSingleChar(word)) return
        belowBySyllable.getOrPut(syllables[0]) { HashMap() }.merge(word, freq, ::maxOf)
    }

    fun emitTopUps(w: BufferedWriter, keyType: String) {
        var syllablesToppedUp = 0
        var entries = 0
        for ((syllable, below) in belowBySyllable.entries.sortedBy { it.key }) {
            val kept = keptBySyllable[syllable].orEmpty()
            val needed = target - kept.size
            if (needed <= 0) continue
            val key = when (keyType) {
                "digit" -> Pinyin.toT9(syllable)
                "initials" -> syllable.substring(0, 1)
                else -> syllable
            }
            val picks = below.entries.asSequence()
                .filter { it.key !in kept }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(needed)
            var any = false
            for ((word, freq) in picks) {
                w.write(key); w.write("\t"); w.write(word); w.write("\t"); w.write(freq.toString()); w.write("\n")
                entries++; any = true
            }
            if (any) syllablesToppedUp++
        }
        println("syllable completeness: topped up $syllablesToppedUp syllables with $entries single-char entries (minimum $target)")
    }
}

internal fun externalSortByKeyWord(input: File, output: File) {
    val pb = ProcessBuilder("sort", "-t", "\t", "-k1,1", "-k2,2")
        .redirectInput(input).redirectOutput(output)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.environment()["LC_ALL"] = "C"
    check(pb.start().waitFor() == 0) { "sort (key,word) failed" }
}

internal fun mergeAdjacentDuplicates(input: File, output: File): Long {
    var folded = 0L
    input.bufferedReader().use { r ->
        output.bufferedWriter().use { w ->
            var curKey: String? = null
            var curWord: String? = null
            var untaggedMax = 0L
            var haveUntagged = false
            val perSource = HashMap<String, Long>()
            var rows = 0
            fun flush() {
                if (curKey != null) {
                    var f = if (haveUntagged) untaggedMax else 0L
                    for (v in perSource.values) f += v
                    w.write(curKey); w.write("\t"); w.write(curWord); w.write("\t")
                    w.write(f.coerceAtMost(Int.MAX_VALUE.toLong()).toString()); w.write("\n")
                    if (rows > 1) folded += rows - 1
                }
                untaggedMax = 0L; haveUntagged = false; perSource.clear(); rows = 0
            }
            while (true) {
                val line = r.readLine() ?: break
                val c = line.split("\t")
                if (c.size < 3) continue
                val key = c[0]; val word = c[1]
                val freq = c[2].toLongOrNull() ?: continue
                val src = c.getOrNull(3) ?: ""
                if (key != curKey || word != curWord) { flush(); curKey = key; curWord = word }
                rows++
                if (src.isEmpty()) { haveUntagged = true; if (freq > untaggedMax) untaggedMax = freq }
                else perSource.merge(src, freq, ::maxOf)
            }
            flush()
        }
    }
    return folded
}

internal fun externalSort(input: File, output: File) {
    val pb = ProcessBuilder("sort", "-t", "\t", "-k1,1", "-k3,3nr")
        .redirectInput(input)
        .redirectOutput(output)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.environment()["LC_ALL"] = "C"
    val code = pb.start().waitFor()
    check(code == 0) { "sort failed with exit code $code" }
}

internal fun writeBinary(sorted: File, out: File, maxPerKey: Int, mappedForms: Set<String>? = null): Pair<Int, Int> {
    val keyBlob = ByteArrayOutputStream(1 shl 20)
    val wordBlob = ByteArrayOutputStream(1 shl 22)
    val keyArr = IntList()
    val entryArr = IntList()

    var numKeys = 0
    var numEntries = 0
    var totalFreq = 0L
    var curKey: String? = null
    var curWords: HashSet<String>? = null
    var perKeyCount = 0
    var leakCount = 0L
    val leakSamples = LinkedHashMap<String, String>()

    sorted.bufferedReader().use { r ->
        while (true) {
            val line = r.readLine() ?: break
            if (line.isEmpty()) continue
            val t1 = line.indexOf('\t'); if (t1 < 0) continue
            val t2 = line.indexOf('\t', t1 + 1); if (t2 < 0) continue
            val key = line.substring(0, t1)
            val word = line.substring(t1 + 1, t2)
            val freq = line.substring(t2 + 1).toIntOrNull() ?: continue
            if (key != curKey) {
                curKey = key
                curWords = HashSet()
                perKeyCount = 0
                val kb = key.toByteArray(Charsets.US_ASCII)
                keyArr.add(keyBlob.size()); keyArr.add(kb.size); keyArr.add(numEntries)
                keyBlob.write(kb)
                numKeys++
            }
            if (curWords!!.add(word) && perKeyCount < maxPerKey) {
                if (mappedForms != null && containsMappedForm(word, mappedForms)) {
                    leakCount++
                    if (leakSamples.size < 20) leakSamples.putIfAbsent(word, key)
                }
                val wb = word.toByteArray(Charsets.UTF_8)
                entryArr.add(wordBlob.size()); entryArr.add(wb.size); entryArr.add(freq)
                wordBlob.write(wb)
                numEntries++
                perKeyCount++
                totalFreq += freq.toLong()
            }
        }
    }

    if (mappedForms != null && leakCount > 0L) {
        val examples = leakSamples.entries.joinToString(", ") { "${it.value}->${it.key}" }
        error(
            "t2s guard: $leakCount emitted words still contain a traditional/variant form the merge " +
                "maps away — the t2s merge did not cover the full vocabulary. Examples: $examples"
        )
    }

    val keyBytes = keyBlob.toByteArray()
    val wordBytes = wordBlob.toByteArray()
    BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
        os.write(byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'D'.code.toByte()))
        os.writeLeInt(2)
        os.writeLeInt(numKeys)
        os.writeLeInt(numEntries)
        os.writeLeLong(totalFreq)
        os.writeLeInt(keyBytes.size); os.write(keyBytes)
        os.writeLeInt(wordBytes.size); os.write(wordBytes)
        for (i in 0 until keyArr.size) os.writeLeInt(keyArr[i])
        for (i in 0 until entryArr.size) os.writeLeInt(entryArr[i])
    }
    return numKeys to numEntries
}

private fun containsMappedForm(word: String, forms: Set<String>): Boolean {
    var i = 0
    while (i < word.length) {
        val cp = word.codePointAt(i)
        if (String(Character.toChars(cp)) in forms) return true
        i += Character.charCount(cp)
    }
    return false
}

private fun writeSyllables(file: File, counts: Map<String, Long>) {
    file.bufferedWriter().use { w ->
        counts.entries.sortedBy { it.key }.forEach { w.write("${it.key}\t${it.value}\n") }
    }
    println("wrote ${file.path}: ${counts.size} distinct syllables")
}

private fun writeCoverage(file: File, counts: Map<String, Long>) {
    val data = counts.keys.toSortedSet()
    val canon = Pinyin.canonicalSyllables
    val dataNotCanon = data.filter { it !in canon }.sorted()
    val canonNotData = canon.filter { it !in data }.sorted()
    file.bufferedWriter().use { w ->
        w.write("# wanxiang syllable coverage (advisory)\n")
        w.write("distinct syllables in data : ${data.size}\n")
        w.write("canonical syllables        : ${canon.size}\n\n")
        w.write("## in DATA but not in canonical list (${dataNotCanon.size}) — foreign/erroneous or canonical-list gaps\n")
        dataNotCanon.forEach { w.write("  $it\t${counts[it]}\n") }
        w.write("\n## in canonical list but MISSING from data (${canonNotData.size}) — possible dict gaps\n")
        canonNotData.forEach { w.write("  $it\n") }
    }
    println("wrote ${file.path}: dataNotCanon=${dataNotCanon.size} canonNotData=${canonNotData.size}")
}


internal fun java.io.OutputStream.writeLeInt(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
}

internal fun java.io.OutputStream.writeLeLong(v: Long) {
    writeLeInt((v and 0xFFFFFFFFL).toInt()); writeLeInt((v ushr 32).toInt())
}

private class IntList {
    private var a = IntArray(1 shl 16)
    var size = 0; private set
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    operator fun get(i: Int) = a[i]
}

internal class Args(argv: Array<String>) {
    val positionals = ArrayList<String>()
    private val named = HashMap<String, String>()
    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            if (a.startsWith("--")) { named[a] = argv.getOrElse(i + 1) { "" }; i += 2 }
            else { positionals.add(a); i += 1 }
        }
    }
    fun required(k: String) = named[k] ?: error("missing $k")
    fun optional(k: String) = named[k]
}
