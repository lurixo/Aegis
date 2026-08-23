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

package com.aegis.ime.dict

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OctagramReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gram = System.getenv("AEGIS_GRAM")?.let { File(it) }

    private fun runawayImage(units: Int): File {
        val size = 44 + units * 4
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val format = "Rime::Grammar/1.0".toByteArray(Charsets.US_ASCII)
        b.put(format).put(ByteArray(32 - format.size))
        b.putInt(32, 0)
        b.putInt(36, units)
        b.putInt(40, 4)
        for (i in 0 until units) b.putInt(44 + i * 4, -1)
        return File(tmp.newFolder(), "runaway.gram").apply { writeBytes(b.array()) }
    }

    @Test
    fun aRunawayImageIsRefusedInsteadOfReadingPastItself() {
        val reader = OctagramReader.fromFile(runawayImage(8))
        assertNull("a jump outside the image is not an answer", reader.rawScore("中国"))
        assertNull(reader.rawScore("的"))
        assertNull(reader.rawScore("一个词组"))
    }

    @Test
    fun loadsAndScores() {
        assumeTrue("AEGIS_GRAM points at the .gram", gram != null && gram.exists())
        val r = OctagramReader.fromFile(gram!!)

        val probes = listOf(
            "的时候", "我们", "中国", "时候", "你好", "工作", "一个", "这个",
            "因为", "所以", "中华人民共和国", "时间", "世界", "北京", "可以", "经济发展",
        )
        val sb = StringBuilder("octagram probe (score = log-weight, higher=more frequent)\n")
        var nonNull = 0
        for (p in probes) {
            val s = r.rawScore(p)
            if (s != null) nonNull++
            sb.append("  ${p} -> ${s?.let { "%.3f".format(it) } ?: "null"}\n")
        }
        println(sb)
        File("build/octagram_probe.txt").apply { parentFile?.mkdirs(); writeText(sb.toString()) }
        assertTrue("at least some collocations resolve", nonNull > 0)
    }
}
