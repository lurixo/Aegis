package com.aegis.ime.engine

import com.aegis.ime.dict.BinaryDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EnglishEngineTest {

    private val enFile = File("src/main/assets/aegis_en.bin")

    private fun engine(): EnglishEngine {
        assumeTrue("en dict present", enFile.exists())
        return EnglishEngine(BinaryDict.fromFile(enFile))
    }

    @Test
    fun completes() {
        val e = engine()
        assertTrue("hel -> hello/help", e.suggest("hel", 10).any { it == "hello" || it == "help" })
        assertEquals("exact word ranks first", "hello", e.suggest("hello", 10).firstOrNull())
    }

    @Test
    fun corrects() {
        val e = engine()
        assertTrue("teh -> the", e.suggest("teh", 10).contains("the"))
        assertTrue("helo -> hello", e.suggest("helo", 10).contains("hello"))
        assertTrue("recieve -> receive", e.suggest("recieve", 10).contains("receive"))
    }

    @Test
    fun preservesCase() {
        val e = engine()
        assertTrue("Capitalized", e.suggest("Hel", 10).any { it == "Hello" || it == "Help" })
    }
}
