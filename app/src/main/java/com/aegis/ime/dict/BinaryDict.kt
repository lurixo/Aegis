package com.aegis.ime.dict

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class BinaryDict private constructor(private val buf: ByteBuffer) {

    private val numKeys: Int
    private val numEntries: Int
    private val keyBlobOff: Int
    private val wordBlobOff: Int
    private val keyArrOff: Int
    private val entryArrOff: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.get(0) == 'A'.code.toByte() && buf.get(1) == 'E'.code.toByte() &&
            buf.get(2) == 'G'.code.toByte() && buf.get(3) == 'D'.code.toByte()) { "bad magic" }
        numKeys = buf.getInt(8)
        numEntries = buf.getInt(12)
        val keyBlobLen = buf.getInt(16)
        keyBlobOff = 20
        val wordBlobLenPos = 20 + keyBlobLen
        val wordBlobLen = buf.getInt(wordBlobLenPos)
        wordBlobOff = wordBlobLenPos + 4
        keyArrOff = wordBlobOff + wordBlobLen
        entryArrOff = keyArrOff + numKeys * 12
    }

    fun query(input: String, limit: Int): List<String> {
        if (input.isEmpty() || numKeys == 0) return emptyList()
        val q = input.toByteArray(Charsets.US_ASCII)
        val out = LinkedHashSet<String>()
        var i = lowerBound(q)
        while (i < numKeys && out.size < limit && startsWith(i, q)) {
            addWords(i, out, limit)
            i++
        }
        return out.toList()
    }

    private fun keyOffset(i: Int) = buf.getInt(keyArrOff + i * 12)
    private fun keyLen(i: Int) = buf.getInt(keyArrOff + i * 12 + 4)
    private fun entryStart(i: Int) = buf.getInt(keyArrOff + i * 12 + 8)

    private fun lowerBound(q: ByteArray): Int {
        var lo = 0
        var hi = numKeys
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareKey(mid, q) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compareKey(i: Int, q: ByteArray): Int {
        val off = keyBlobOff + keyOffset(i)
        val len = keyLen(i)
        val n = minOf(len, q.size)
        var k = 0
        while (k < n) {
            val a = buf.get(off + k).toInt() and 0xFF
            val b = q[k].toInt() and 0xFF
            if (a != b) return a - b
            k++
        }
        return len - q.size
    }

    private fun startsWith(i: Int, q: ByteArray): Boolean {
        val len = keyLen(i)
        if (len < q.size) return false
        val off = keyBlobOff + keyOffset(i)
        for (k in q.indices) {
            if ((buf.get(off + k).toInt() and 0xFF) != (q[k].toInt() and 0xFF)) return false
        }
        return true
    }

    private fun addWords(i: Int, out: MutableSet<String>, limit: Int) {
        val es = entryStart(i)
        val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
        var j = es
        while (j < ee && out.size < limit) {
            val wo = buf.getInt(entryArrOff + j * 12)
            val wl = buf.getInt(entryArrOff + j * 12 + 4)
            out.add(readWord(wo, wl))
            j++
        }
    }

    private fun readWord(wordOffset: Int, len: Int): String {
        val bytes = ByteArray(len)
        val base = wordBlobOff + wordOffset
        for (k in 0 until len) bytes[k] = buf.get(base + k)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        fun fromFile(file: File): BinaryDict =
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                BinaryDict(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }

        fun fromAssets(context: Context, assetName: String): BinaryDict {
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
