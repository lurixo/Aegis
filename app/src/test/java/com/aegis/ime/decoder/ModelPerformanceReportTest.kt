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
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.dict.OctagramReader
import java.io.File
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ModelPerformanceReportTest {

    private data class Timings(val p50: Double, val p95: Double, val max: Double)

    private fun rssKb(): Long =
        File("/proc/self/status").takeIf { it.isFile }?.useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
        } ?: -1L

    private fun summarize(values: List<Long>): Timings {
        val ordered = values.sorted()
        fun at(fraction: Double): Double {
            val index = ((ordered.size - 1) * fraction).toInt().coerceIn(0, ordered.lastIndex)
            return ordered[index] / 1_000_000.0
        }
        return Timings(at(0.50), at(0.95), ordered.last() / 1_000_000.0)
    }

    private fun pairs(): List<Pair<String, String>> =
        File("src/test/resources/eval_set.tsv").readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }

    @Test fun report_current_snapshot_host_startup_memory_and_decode_latency() {
        val gram = System.getenv("AEGIS_GRAM")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("AEGIS_GRAM points to a current grammar snapshot", gram?.isFile == true)
        val modelFile = requireNotNull(gram)
        val beforeLoad = rssKb()
        lateinit var octagram: OctagramReader
        val loadNs = measureNanoTime { octagram = OctagramReader.fromFile(modelFile) }
        val afterLoad = rssKb()

        val dict = BinaryDict.fromFile(File("src/main/assets/aegis_dict.bin"))
        val t9 = BinaryDict.fromFile(File("src/main/assets/aegis_t9.bin"))
        val lm = CharBigramLM.fromFile(File("src/main/assets/aegis_lm.bin"))
        val letterDecoder = PinyinDecoder(dict, lm, octagram = octagram)
        val t9Decoder = PinyinDecoder(t9, lm, octagram = octagram, aliasDict = dict)
        val cases = pairs()
        cases.take(20).forEach { (reading, _) ->
            letterDecoder.decodeCovered(reading, 30)
            t9Decoder.decodeCovered(T9Pinyin.toT9(reading), 30)
        }

        val letterTimes = ArrayList<Long>(cases.size)
        val t9Times = ArrayList<Long>(cases.size)
        for ((reading, _) in cases) {
            letterTimes += measureNanoTime { letterDecoder.decodeCovered(reading, 30) }
            t9Times += measureNanoTime { t9Decoder.decodeCovered(T9Pinyin.toT9(reading), 30) }
        }
        val afterDecode = rssKb()
        val letter = summarize(letterTimes)
        val nine = summarize(t9Times)
        val report = buildString {
            appendLine("scope=Linux JVM host; not Android device or frame evidence")
            appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            appendLine("java=${System.getProperty("java.version")}")
            appendLine("grammar_size_bytes=${modelFile.length()}")
            appendLine("grammar_sha256=${ModelDownload.sha256Of(modelFile)}")
            appendLine("grammar_load_ms=${"%.3f".format(loadNs / 1_000_000.0)}")
            appendLine("rss_kb_before_load=$beforeLoad")
            appendLine("rss_kb_after_load=$afterLoad")
            appendLine("rss_kb_after_decode=$afterDecode")
            appendLine("cases=${cases.size}")
            appendLine("letters_ms_p50=${"%.3f".format(letter.p50)} p95=${"%.3f".format(letter.p95)} max=${"%.3f".format(letter.max)}")
            appendLine("t9_ms_p50=${"%.3f".format(nine.p50)} p95=${"%.3f".format(nine.p95)} max=${"%.3f".format(nine.max)}")
        }
        File("build/model_performance_report.txt").apply {
            parentFile?.mkdirs()
            writeText(report)
        }
        println(report)
        assertEquals(cases.size, letterTimes.size)
        assertEquals(cases.size, t9Times.size)
        assertTrue(letter.max >= letter.p95)
        assertTrue(nine.max >= nine.p95)
    }
}
