package com.aegis.tools

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Builds Aegis's prebuilt dictionary from wanxiang Rime dict YAML files.
 *
 * Pipeline (streaming + external sort, so peak RAM stays low even on the 1.4M-row base dict):
 *   1. parse each dict → emit `tonelessKey \t word \t freq` to a temp file; collect syllable stats
 *   2. `LC_ALL=C sort` by key asc, freq desc (disk-backed)
 *   3. stream the sorted file → emit the binary [aegis_dict.bin]
 *   4. write the syllable inventory + an advisory coverage diff vs canonical pinyin
 *
 * Binary format (little-endian) — mirrored by the app loader `BinaryDict`:
 *   'A''E''G''D' | i32 version=2 | i32 numKeys | i32 numEntries | i64 totalFreq
 *   i32 keyBlobLen  | keyBlob  (ascii, distinct keys concatenated, sorted asc)
 *   i32 wordBlobLen | wordBlob (utf-8, one slice per entry)
 *   keyArr  : numKeys   × (i32 keyOffset, i32 keyLen, i32 entryStart)
 *   entryArr: numEntries× (i32 wordOffset, i32 wordLen, i32 freq)
 * Entries for key i span [entryStart_i, entryStart_{i+1} | numEntries). Within a key: freq desc.
 */
fun main(rawArgs: Array<String>) {
    val args = Args(rawArgs)
    val out = File(args.required("--out"))
    val inputs = args.positionals.map { File(it) }
    require(inputs.isNotEmpty()) { "no input dict files given" }
    val minFreq = args.optional("--min-freq")?.toInt() ?: 0
    val keyType = args.optional("--keytype") ?: "letter" // "letter" (26-key) or "digit" (T9)
    val syllablesOut = args.optional("--syllables")?.let { File(it) }
    val coverageOut = args.optional("--coverage")?.let { File(it) }

    val tmpRecords = File.createTempFile("aegis-dict-", ".tsv").apply { deleteOnExit() }
    val tmpSorted = File.createTempFile("aegis-dict-sorted-", ".tsv").apply { deleteOnExit() }

    val syllableCounts = HashMap<String, Long>()
    var totalRows = 0L
    var kept = 0L
    var skippedNonAscii = 0L
    var skippedLowFreq = 0L

    tmpRecords.bufferedWriter().use { w ->
        for (file in inputs) {
            println("parsing ${file.name} ...")
            file.bufferedReader().use { r -> parseDict(r, w, minFreq, keyType, syllableCounts) { kind ->
                when (kind) {
                    Skip.ROW -> totalRows++
                    Skip.KEPT -> kept++
                    Skip.NON_ASCII -> { totalRows++; skippedNonAscii++ }
                    Skip.LOW_FREQ -> { totalRows++; skippedLowFreq++ }
                }
            } }
        }
    }
    println("parsed rows=$totalRows kept=$kept skippedNonAscii=$skippedNonAscii skippedLowFreq=$skippedLowFreq")

    externalSort(tmpRecords, tmpSorted)
    println("sorted -> ${tmpSorted.length()} bytes")

    val (numKeys, numEntries) = writeBinary(tmpSorted, out)
    println("wrote ${out.path}: keys=$numKeys entries=$numEntries size=${out.length()} bytes")

    syllablesOut?.let { writeSyllables(it, syllableCounts) }
    coverageOut?.let { writeCoverage(it, syllableCounts) }
}

private enum class Skip { ROW, KEPT, NON_ASCII, LOW_FREQ }

private fun parseDict(
    r: BufferedReader,
    w: BufferedWriter,
    minFreq: Int,
    keyType: String,
    syllableCounts: HashMap<String, Long>,
    tally: (Skip) -> Unit,
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
        tally(Skip.ROW)
        val word = cols[0]
        val pinyin = cols[1]
        val freq = cols.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
        val syllables = Pinyin.stripTones(pinyin).split(' ').filter { it.isNotEmpty() }
        if (syllables.isEmpty() || syllables.any { !Pinyin.isAsciiSyllable(it) }) {
            tally(Skip.NON_ASCII); continue
        }
        if (freq < minFreq) { tally(Skip.LOW_FREQ); continue }
        for (s in syllables) syllableCounts.merge(s, 1L, Long::plus)
        val letterKey = syllables.joinToString("")
        val key = if (keyType == "digit") Pinyin.toT9(letterKey) else letterKey
        w.write(key); w.write("\t"); w.write(word); w.write("\t"); w.write(freq.toString()); w.write("\n")
        tally(Skip.KEPT)
    }
}

private fun externalSort(input: File, output: File) {
    val pb = ProcessBuilder("sort", "-t", "\t", "-k1,1", "-k3,3nr")
        .redirectInput(input)
        .redirectOutput(output)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.environment()["LC_ALL"] = "C"
    val code = pb.start().waitFor()
    check(code == 0) { "sort failed with exit code $code" }
}

private fun writeBinary(sorted: File, out: File): Pair<Int, Int> {
    val keyBlob = ByteArrayOutputStream(1 shl 20)
    val wordBlob = ByteArrayOutputStream(1 shl 22)
    val keyArr = IntList()    // (keyOffset, keyLen, entryStart) triples
    val entryArr = IntList()  // (wordOffset, wordLen, freq) triples

    var numKeys = 0
    var numEntries = 0
    var totalFreq = 0L
    var curKey: String? = null
    var curWords: HashSet<String>? = null

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
                val kb = key.toByteArray(Charsets.US_ASCII)
                keyArr.add(keyBlob.size()); keyArr.add(kb.size); keyArr.add(numEntries)
                keyBlob.write(kb)
                numKeys++
            }
            if (curWords!!.add(word)) {
                val wb = word.toByteArray(Charsets.UTF_8)
                entryArr.add(wordBlob.size()); entryArr.add(wb.size); entryArr.add(freq)
                wordBlob.write(wb)
                numEntries++
                totalFreq += freq.toLong()
            }
        }
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

// --- helpers ---

private fun java.io.OutputStream.writeLeInt(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
}

private fun java.io.OutputStream.writeLeLong(v: Long) {
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

private class Args(argv: Array<String>) {
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
