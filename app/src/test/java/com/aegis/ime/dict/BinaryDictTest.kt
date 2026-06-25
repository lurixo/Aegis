package com.aegis.ime.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Exercises the mmap loader on the JVM against the committed demo dict (no device needed). */
class BinaryDictTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")

    @Test
    fun looksUpCommonWords() {
        assumeTrue("demo dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)

        assertTrue("nihao -> 你好", dict.query("nihao", 30).contains("你好"))
        assertTrue("zhongguo -> 中国", dict.query("zhongguo", 30).contains("中国"))
        assertTrue("ceshi -> 测试", dict.query("ceshi", 30).contains("测试"))
        assertTrue("shuru -> 输入", dict.query("shuru", 30).contains("输入"))

        // single syllable, frequency-ordered: 啊 outranks 阿
        val a = dict.query("a", 30)
        assertTrue("a -> 啊", a.contains("啊"))
        assertTrue("啊 before 阿", a.indexOf("啊") < a.indexOf("阿"))

        // prefix expansion: "bei" surfaces words whose key extends it (e.g. beijing)
        assertTrue("prefix bei non-empty", dict.query("bei", 30).isNotEmpty())

        // limit + empty
        assertTrue("limit honored", dict.query("a", 2).size <= 2)
        assertEquals("empty -> none", emptyList<String>(), dict.query("", 30))
    }
}
