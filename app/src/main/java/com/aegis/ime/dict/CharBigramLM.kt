package com.aegis.ime.dict

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ln

/**
 * Memory-mapped character bigram model (stupid-backoff) produced by `tools/LmBuilder`.
 * Provides ln P(curChar | prevChar) for cross-word transitions in the decoder.
 *
 * Format (little-endian, magic 'AEGL') — CSR over chars; see LmBuilder for the full layout.
 */
class CharBigramLM private constructor(private val buf: ByteBuffer) {

    private val numChars: Int
    private val totalUni: Long
    private val charCodesOff: Int
    private val uniCountOff: Int
    private val rowTotalOff: Int
    private val rowStartOff: Int
    private val biC2Off: Int
    private val biCountOff: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.get(0) == 'A'.code.toByte() && buf.get(1) == 'E'.code.toByte() &&
            buf.get(2) == 'G'.code.toByte() && buf.get(3) == 'L'.code.toByte()) { "bad lm magic" }
        numChars = buf.getInt(8)
        totalUni = buf.getLong(12)
        charCodesOff = 20
        uniCountOff = charCodesOff + numChars * 4
        rowTotalOff = uniCountOff + numChars * 8
        rowStartOff = rowTotalOff + numChars * 8
        val numBigramsOff = rowStartOff + (numChars + 1) * 4
        biC2Off = numBigramsOff + 4
        val numBigrams = buf.getInt(numBigramsOff)
        biCountOff = biC2Off + numBigrams * 4
    }

    private val lnTotalUni = ln(totalUni.coerceAtLeast(1).toDouble())

    /** id of a code point, or -1 if absent. */
    private fun charId(cp: Int): Int {
        var lo = 0
        var hi = numChars
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getInt(charCodesOff + mid * 4)
            if (v < cp) lo = mid + 1 else hi = mid
        }
        return if (lo < numChars && buf.getInt(charCodesOff + lo * 4) == cp) lo else -1
    }

    private fun uniLogP(id: Int): Double =
        if (id < 0) -lnTotalUni else ln(buf.getLong(uniCountOff + id * 8).toDouble()) - lnTotalUni

    /** ln P(curCp | prevCp) with stupid backoff to the unigram. */
    fun logCond(prevCp: Int, curCp: Int): Double {
        val id1 = charId(prevCp)
        val id2 = charId(curCp)
        if (id1 >= 0 && id2 >= 0) {
            val start = buf.getInt(rowStartOff + id1 * 4)
            val end = buf.getInt(rowStartOff + (id1 + 1) * 4)
            val cnt = bigramCount(start, end, id2)
            if (cnt > 0) {
                val rowTotal = buf.getLong(rowTotalOff + id1 * 8)
                return ln(cnt.toDouble()) - ln(rowTotal.toDouble())
            }
        }
        return BACKOFF_LN + uniLogP(id2)
    }

    private fun bigramCount(start: Int, end: Int, c2Id: Int): Long {
        var lo = start
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getInt(biC2Off + mid * 4)
            if (v < c2Id) lo = mid + 1 else hi = mid
        }
        return if (lo < end && buf.getInt(biC2Off + lo * 4) == c2Id) buf.getLong(biCountOff + lo * 8) else 0L
    }

    companion object {
        /** stupid-backoff discount (α≈0.4). */
        private val BACKOFF_LN = ln(0.4)

        fun fromFile(file: File): CharBigramLM =
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                CharBigramLM(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }

        fun fromAssets(context: Context, assetName: String): CharBigramLM {
            val outFile = File(context.filesDir, assetName)
            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open(assetName).use { input ->
                    outFile.outputStream().use { input.copyTo(it) }
                }
            }
            return fromFile(outFile)
        }
    }
}
