package com.aegis.ime.dict

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OctagramReaderTest {

    private val gram = System.getenv("AEGIS_GRAM")?.let { File(it) }

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
        File("build/octagram_probe.txt").apply { parentFile.mkdirs(); writeText(sb.toString()) }
        assertTrue("at least some collocations resolve", nonNull > 0)
    }
}
