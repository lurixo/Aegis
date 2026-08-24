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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LockedOrderBaselineDump {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }

    private fun decoder(): PinyinDecoder =
        PinyinDecoder(dict, lm, initialsDict = BinaryDict.fromFile(jianpinFile))

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val syls = (f.get(T9Pinyin) as Set<String>).toList().sorted()
        assertTrue("runtime SYLLABLES set looks like ~415 (drift guard): ${syls.size}", syls.size in 400..430)
        return syls
    }

    private fun emit(
        decoder: PinyinDecoder,
        layout: String,
        sylKeys: List<String>,
        input: String,
        cuts: Set<Int>,
        context: String,
        sink: StringBuilder,
    ) {
        val tag = sylKeys.joinToString("+") + if (context.isEmpty()) "" else "|$context"
        val cands = decoder.decodeCoveredAtomic(input, 30, cuts, context)
        val sequence = StringBuilder()
        for (c in cands) {
            sequence.append(c.word).append('\u0001').append(c.coveredLen).append('\u0000')
        }
        sink.append(LockedOrderDigest.of("$layout|$tag")).append('\t')
            .append(LockedOrderDigest.of(sequence.toString())).append('\n')
    }

    private fun letter(decoder: PinyinDecoder, context: String, syls: List<String>, sink: StringBuilder) {
        val input = syls.joinToString("")
        val cuts = HashSet<Int>()
        var acc = 0
        for (k in 0 until syls.size - 1) { acc += syls[k].length; cuts.add(acc) }
        emit(decoder, "26-key", syls, input, cuts, context, sink)
    }

    private fun nineKey(decoder: PinyinDecoder, context: String, syls: List<String>, sink: StringBuilder) {
        val head = syls.dropLast(1)
        val tail = T9Pinyin.preedit(T9Pinyin.toT9(syls.last()))
        val letters = head.joinToString("") + tail.replace("'", "")
        val cuts = HashSet<Int>()
        var acc = 0
        for (r in head) { acc += r.length; if (acc < letters.length) cuts.add(acc) }
        val sylKeys = head + tail.split("'").filter { it.isNotEmpty() }
        emit(decoder, "9-key", sylKeys, letters, cuts, context, sink)
    }

    private fun wholeReading(decoder: PinyinDecoder, context: String, s: String, sink: StringBuilder) {
        emit(decoder, "26-key/noCuts", listOf(s), s, emptySet(), context, sink)
    }

    @Test fun writeLockedSequenceBaselineWhenAsked() {
        val target = System.getenv("AEGIS_LOCKED_DUMP")
        assumeTrue("set AEGIS_LOCKED_DUMP to write the locked-sequence baseline", target != null)
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val syls = runtimeSyllables()
        val d = decoder()
        val rows = ArrayList<String>()
        for (s in syls) for (context in LOCKED_CONTEXTS) {
            val sink = StringBuilder()
            wholeReading(d, context, s, sink)
            rows.add(sink.toString().trimEnd('\n'))
        }
        rows.sort()
        val out = File(target!!)
        out.parentFile?.mkdirs()
        out.writeText(
            HEADER + rows.joinToString("\n") + "\n",
        )
        println("[locked-dump] cells=${rows.size} target=$target")
        assertTrue("locked-sequence baseline written", out.length() > 0)
    }

    private companion object {
        val LOCKED_CONTEXTS = listOf("", "\u6211", "\u6211\u4eec")
        const val HEADER =
            "# locked-sequence baseline of the whole-reading locked arm\n" +
                "# key=sha256_64(layout|tag) value=sha256_64(word U+0001 coveredLen U+0000 ...)\n" +
                "# regenerate at the revision that introduces this file, app/src/main untouched:\n" +
                "# AEGIS_LOCKED_DUMP=<path> gradlew :app:testDebugUnitTest --tests '*LockedOrderBaselineDump*'\n"
    }
}

internal object LockedOrderDigest {
    const val RESOURCE = "/locked-sequence-2.tsv"
    const val CELLS = 1254
    const val SHA256 = "6a5c032ac32abaa58422e6286f4cc1dbce9ed65b50b7a7656c63ce3a0ea888cd"

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun of(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        return java.lang.Long.toUnsignedString(value, 16)
    }
}
