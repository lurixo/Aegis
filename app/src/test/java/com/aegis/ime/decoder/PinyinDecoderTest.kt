package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Decoder sanity on the JVM against the committed demo dict. */
class PinyinDecoderTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")

    private fun decoder(): PinyinDecoder {
        assumeTrue("demo dict asset present", dictFile.exists())
        return PinyinDecoder(BinaryDict.fromFile(dictFile))
    }

    @Test
    fun decodesSentences() {
        val d = decoder()
        fun top(s: String) = d.decode(s, 30).firstOrNull()
        assertEquals("测试", top("ceshi"))
        assertEquals("你好世界", top("nihaoshijie"))
        assertEquals("我是中国人", top("woshizhongguoren"))
        assertEquals("北京大学", top("beijingdaxue"))
        assertEquals("输入法", top("shurufa"))
    }
}
