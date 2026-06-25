package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class T9DecoderTest {

    private val t9File = File("src/main/assets/aegis_t9.bin")

    private fun decoder(): PinyinDecoder {
        assumeTrue("T9 dict asset present", t9File.exists())
        return PinyinDecoder(BinaryDict.fromFile(t9File))
    }

    @Test
    fun decodesT9() {
        val d = decoder()
        assertTrue("64426 -> 你好", d.decode("64426", 30).contains("你好"))
        assertTrue("23744 -> 测试", d.decode("23744", 30).contains("测试"))
        assertEquals("我是中国人", d.decode("9674494664486736", 30).firstOrNull())
    }
}
