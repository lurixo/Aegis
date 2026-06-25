package com.aegis.tools

import java.io.BufferedOutputStream
import java.io.File

/**
 * Builds Aegis's in-APK context model: a frequency-weighted **character bigram** over all dict
 * words. This is the best running-context signal derivable purely from the published wanxiang
 * CC-BY data (whose dicts are word lists, not running text — the 32 GB-trained `.gram` octagram
 * remains the optional top-tier download). For each entry (word, freq): every char gets +freq to
 * its unigram; every adjacent char pair gets +freq to its bigram.
 *
 * Binary format (little-endian, magic 'AEGL', version 1) — CSR over chars, mirrored by the app
 * reader `CharBigramLM`:
 *   'A''E''G''L' | i32 version | i32 numChars | i64 totalUni
 *   charCodes : numChars   × i32 (unicode code points, sorted asc)
 *   uniCount  : numChars   × i64
 *   rowTotal  : numChars   × i64  (C(c1,·) = sum of bigram counts leaving c1)
 *   rowStart  : numChars+1 × i32  (offsets into the bigram arrays)
 *   i32 numBigrams
 *   biC2      : numBigrams × i32  (c2 char id; ascending within each row)
 *   biCount   : numBigrams × i64
 */
object LmBuilder {

    private fun pack(c1: Int, c2: Int): Long = (c1.toLong() shl 21) or c2.toLong()

    fun build(rawArgs: Array<String>) {
        val args = Args(rawArgs)
        val out = File(args.required("--out"))
        val inputs = args.positionals.map { File(it) }
        require(inputs.isNotEmpty()) { "no input dict files for lm" }
        val minBigram = args.optional("--min-bigram")?.toLong() ?: 1L // prune rare pairs to shrink

        val uni = HashMap<Int, Long>(1 shl 16)
        val bi = HashMap<Long, Long>(1 shl 22)

        for (file in inputs) {
            println("lm: scanning ${file.name} ...")
            file.bufferedReader().use { r ->
                var inData = false
                while (true) {
                    val line = r.readLine() ?: break
                    if (!inData) { if (line.trim() == "...") inData = true; continue }
                    if (line.isEmpty() || line.startsWith('#')) continue
                    val tab = line.indexOf('\t'); if (tab < 0) continue
                    val word = line.substring(0, tab)
                    val freq = line.lastIndexOf('\t').let { i ->
                        if (i > tab) line.substring(i + 1).trim().toLongOrNull() else null
                    } ?: 1L
                    val cps = word.codePoints().toArray()
                    for (cp in cps) uni.merge(cp, freq, Long::plus)
                    for (i in 0 until cps.size - 1) bi.merge(pack(cps[i], cps[i + 1]), freq, Long::plus)
                }
            }
        }

        val charCodes = uni.keys.toIntArray().also { it.sort() }
        val numChars = charCodes.size
        val idOf = HashMap<Int, Int>(numChars * 2)
        for (i in 0 until numChars) idOf[charCodes[i]] = i
        val uniCount = LongArray(numChars) { uni[charCodes[it]]!! }
        val totalUni = uniCount.sum()

        // group bigrams by c1 id
        val rows = Array(numChars) { ArrayList<LongArray>() } // each: [c2Id, count]
        // rowTotal counts ALL bigrams (true conditional denominator); only kept pairs are written.
        val rowTotal = LongArray(numChars)
        for ((key, count) in bi) {
            val c1id = idOf[(key ushr 21).toInt()]!!
            rowTotal[c1id] += count
            if (count < minBigram) continue
            rows[c1id].add(longArrayOf(idOf[(key and 0x1FFFFF).toInt()]!!.toLong(), count))
        }
        val rowStart = IntArray(numChars + 1)
        var numBigrams = 0
        for (i in 0 until numChars) {
            rows[i].sortBy { it[0] }
            rowStart[i] = numBigrams
            numBigrams += rows[i].size
        }
        rowStart[numChars] = numBigrams

        BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
            os.write(byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'L'.code.toByte()))
            os.writeLeInt(1)
            os.writeLeInt(numChars)
            os.writeLeLong(totalUni)
            for (c in charCodes) os.writeLeInt(c)
            for (v in uniCount) os.writeLeLong(v)
            for (v in rowTotal) os.writeLeLong(v)
            for (v in rowStart) os.writeLeInt(v)
            os.writeLeInt(numBigrams)
            for (i in 0 until numChars) for (e in rows[i]) os.writeLeInt(e[0].toInt())
            for (i in 0 until numChars) for (e in rows[i]) os.writeLeLong(e[1])
        }
        println("wrote ${out.path}: chars=$numChars bigrams=$numBigrams totalUni=$totalUni size=${out.length()} bytes")
    }
}
