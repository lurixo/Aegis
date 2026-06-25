package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 简拼 (initial-letter abbreviation): zg -> 中国, bjdx -> 北京大学 should surface as candidates. */
class JianpinTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    @Test
    fun jianpinCandidates() {
        assumeTrue(dictFile.exists() && lmFile.exists() && jianpinFile.exists())
        val d = PinyinDecoder(
            BinaryDict.fromFile(dictFile),
            CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
        assertTrue("zg -> 中国", d.decode("zg", 12).contains("中国"))
        assertTrue("bjdx -> 北京大学", d.decode("bjdx", 12).contains("北京大学"))
        assertTrue("wm -> 我们", d.decode("wm", 12).contains("我们"))
    }
}
