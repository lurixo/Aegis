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
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.PriorityQueue

internal object PrefixIndexBuilder {
    private const val LIMIT = 64

    private data class Hit(val word: String, val frequency: Int, val tieRank: Int, val order: Int)

    private class TopHits {
        private val byWord = HashMap<String, Hit>()
        private val worstFirst = PriorityQueue<Hit>(LIMIT, ::compareWorstFirst)

        fun offer(hit: Hit) {
            val existing = byWord[hit.word]
            if (existing != null) {
                if (compareBestFirst(existing, hit) <= 0) return
                worstFirst.remove(existing)
                byWord.remove(existing.word)
            }
            if (worstFirst.size < LIMIT) {
                worstFirst.add(hit)
                byWord[hit.word] = hit
                return
            }
            val worst = worstFirst.peek() ?: return
            if (compareBestFirst(hit, worst) < 0) {
                worstFirst.remove()
                byWord.remove(worst.word)
                worstFirst.add(hit)
                byWord[hit.word] = hit
            }
        }

        fun sorted(): List<Hit> = worstFirst.toList().sortedWith(::compareBestFirst)
    }

    fun build(rawArgs: Array<String>) {
        val args = Args(rawArgs)
        val dictionary = File(args.required("--dict"))
        val output = File(args.required("--out"))
        val mode = args.required("--mode")
        require(mode == "letter" || mode == "digit") { "--mode must be letter or digit" }
        require(dictionary.isFile) { "missing dictionary: ${dictionary.absolutePath}" }

        val dictionarySha = sha256(dictionary)
        val dictionaryFingerprint = sampledSha256(dictionary)
        val prefixes = linkedMapOf<String, TopHits>()
        val exacts = exactKeys(mode).associateWithTo(linkedMapOf()) { TopHits() }
        RandomAccessFile(dictionary, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.LITTLE_ENDIAN)
            requireMagic(buf, "AEGD")
            require(buf.getInt(4) == 2) { "unsupported dictionary version" }
            val numKeys = buf.getInt(8)
            val numEntries = buf.getInt(12)
            val keyBlobLength = buf.getInt(24)
            val keyBlobOffset = 28
            val wordBlobLengthPosition = keyBlobOffset + keyBlobLength
            val wordBlobLength = buf.getInt(wordBlobLengthPosition)
            val wordBlobOffset = wordBlobLengthPosition + Int.SIZE_BYTES
            val keyArrayOffset = wordBlobOffset + wordBlobLength
            val entryArrayOffset = keyArrayOffset + numKeys * 12

            fun entryStart(keyIndex: Int): Int = buf.getInt(keyArrayOffset + keyIndex * 12 + 8)
            fun readKey(keyIndex: Int): String {
                val offset = buf.getInt(keyArrayOffset + keyIndex * 12)
                val length = buf.getInt(keyArrayOffset + keyIndex * 12 + 4)
                val bytes = ByteArray(length)
                for (index in bytes.indices) bytes[index] = buf.get(keyBlobOffset + offset + index)
                return String(bytes, Charsets.US_ASCII)
            }
            fun readWord(entryIndex: Int): Pair<String, Int> {
                val record = entryArrayOffset + entryIndex * 12
                val offset = buf.getInt(record)
                val length = buf.getInt(record + 4)
                val frequency = buf.getInt(record + 8)
                val bytes = ByteArray(length)
                for (index in bytes.indices) bytes[index] = buf.get(wordBlobOffset + offset + index)
                return String(bytes, Charsets.UTF_8) to frequency
            }

            for (keyIndex in 0 until numKeys) {
                val key = readKey(keyIndex)
                val matching = prefixesFor(key, mode)
                if (matching.isEmpty()) continue
                val start = entryStart(keyIndex)
                val end = if (keyIndex + 1 < numKeys) entryStart(keyIndex + 1) else numEntries
                for (entryIndex in start until end) {
                    val (word, frequency) = readWord(entryIndex)
                    val hit = Hit(word, frequency, supplementarySingleTieRank(word), entryIndex)
                    for (prefix in matching) prefixes.getOrPut(prefix, ::TopHits).offer(hit)
                    exacts[key]?.offer(hit)
                }
            }
        }

        output.parentFile?.mkdirs()
        BufferedOutputStream(output.outputStream(), 1 shl 16).use { stream ->
            stream.write("AEGP".toByteArray(Charsets.US_ASCII))
            stream.writeLeInt(4)
            stream.write(hexBytes(dictionarySha))
            stream.write(hexBytes(dictionaryFingerprint))
            stream.writeLeLong(dictionary.length())
            val records = buildList {
                prefixes.entries.sortedBy { it.key }.forEach { add(1 to it) }
                exacts.entries.sortedBy { it.key }.forEach { add(2 to it) }
            }
            stream.writeLeInt(records.size)
            for ((kind, record) in records) {
                stream.write(kind)
                val prefixBytes = record.key.toByteArray(Charsets.US_ASCII)
                stream.writeLeInt(prefixBytes.size)
                stream.write(prefixBytes)
                val sorted = record.value.sorted()
                stream.writeLeInt(sorted.size)
                for (hit in sorted) {
                    val wordBytes = hit.word.toByteArray(Charsets.UTF_8)
                    stream.writeLeInt(wordBytes.size)
                    stream.write(wordBytes)
                    stream.writeLeInt(hit.frequency)
                }
            }
        }
        println(
            "wrote ${output.path}: mode=$mode prefixes=${prefixes.size} exacts=${exacts.size} " +
                "bytes=${output.length()} " +
                "dictionarySha=$dictionarySha dictionaryFingerprint=$dictionaryFingerprint",
        )
    }

    private fun prefixesFor(key: String, mode: String): List<String> = when (mode) {
        "letter" -> key.firstOrNull()?.takeIf { it in 'a'..'z' }?.toString()?.let(::listOf).orEmpty()
        else -> {
            if (key.isEmpty() || key.any { it !in '2'..'9' }) emptyList()
            else (1..minOf(3, key.length)).map { end -> key.substring(0, end) }
        }
    }

    private fun exactKeys(mode: String): List<String> = when (mode) {
        "letter" -> ('a'..'z').map(Char::toString)
        else -> buildList {
            var level = listOf("")
            repeat(3) {
                level = level.flatMap { prefix -> ('2'..'9').map { digit -> prefix + digit } }
                addAll(level)
            }
        }
    }

    private fun supplementarySingleTieRank(word: String): Int =
        if (
            word.isNotEmpty() && word.codePointCount(0, word.length) == 1 &&
            Character.isSupplementaryCodePoint(word.codePointAt(0))
        ) 1 else 0

    private fun compareWorstFirst(left: Hit, right: Hit): Int {
        val frequency = left.frequency.compareTo(right.frequency)
        if (frequency != 0) return frequency
        val tie = right.tieRank.compareTo(left.tieRank)
        if (tie != 0) return tie
        return right.order.compareTo(left.order)
    }

    private fun compareBestFirst(left: Hit, right: Hit): Int {
        val frequency = right.frequency.compareTo(left.frequency)
        if (frequency != 0) return frequency
        val tie = left.tieRank.compareTo(right.tieRank)
        if (tie != 0) return tie
        return left.order.compareTo(right.order)
    }

    private fun requireMagic(buffer: ByteBuffer, expected: String) {
        for (index in expected.indices) {
            require(buffer.get(index) == expected[index].code.toByte()) { "bad dictionary magic" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sampledSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(file.length()).array())
        RandomAccessFile(file, "r").use { source ->
            val maximumStart = (source.length() - SAMPLE_BYTES).coerceAtLeast(0L)
            val samples = if (maximumStart == 0L) 1 else SAMPLE_COUNT
            repeat(samples) { index ->
                val position = if (samples == 1) 0L else maximumStart * index / (samples - 1)
                val count = minOf(SAMPLE_BYTES.toLong(), source.length() - position).toInt()
                val bytes = ByteArray(count)
                source.seek(position)
                source.readFully(bytes)
                digest.update(
                    ByteBuffer.allocate(Long.SIZE_BYTES + Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(position).putInt(count).array(),
                )
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private const val SAMPLE_COUNT = 64
    private const val SAMPLE_BYTES = 1_024
}
