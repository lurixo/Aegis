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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GuessFallbackTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private class Case(val word: String, val letters: String, val digits: String)

    private val corpus = listOf(
        Case("中国", "zhonguo", "94664476"),
        Case("中国", "zhognguo", "96464486"),
        Case("你好", "nihoa", "64462"),
        Case("我爱你", "wsoaini", "9562464"),
        Case("北京", "beijign", "2354464"),
    )

    private fun letterDecoder(): PinyinDecoder {
        assumeTrue(
            "26-key dict + LM + jianpin assets present",
            FullDictTestAssets.available(dictFile, lmFile, jianpinFile),
        )
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile),
            CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
    }

    private fun t9Decoder(): PinyinDecoder {
        assumeTrue("T9 dict + 26-key dict + LM assets present", FullDictTestAssets.available(t9File, dictFile, lmFile))
        return PinyinDecoder(
            BinaryDict.fromFile(t9File),
            CharBigramLM.fromFile(lmFile),
            aliasDict = BinaryDict.fromFile(dictFile),
            fuzzyVariants = { s, rules -> T9Pinyin.fuzzyVariants(s, rules) },
        )
    }

    private fun words(decoder: PinyinDecoder, input: String): List<String> =
        decoder.decodeCovered(input, LIMIT).map { it.word }

    @Test fun letter_typos_put_the_best_exact_correction_first() {
        val decoder = letterDecoder()
        for (case in corpus) {
            assertFalse("${case.letters} must be a broken input", PinyinCorrection.fullySegmentable(case.letters))
            val out = words(decoder, case.letters)
            println("guess[letters] ${case.letters} -> $out")
            assertEquals("${case.letters} should promote the exact correction", case.word, out.first())
        }
    }

    @Test fun nine_key_typos_put_the_best_exact_correction_first() {
        val decoder = t9Decoder()
        for (case in corpus) {
            assertFalse("${case.digits} must be a broken input", PinyinCorrection.fullySegmentable(case.digits))
            val out = words(decoder, case.digits)
            println("guess[digits] ${case.digits} -> $out")
            assertEquals("${case.digits} should promote the exact correction", case.word, out.first())
        }
    }

    @Test fun nine_key_broken_short_inputs_are_not_empty() {
        val decoder = t9Decoder()
        for (digits in listOf("77", "44")) {
            assertFalse(PinyinCorrection.fullySegmentable(digits))
            val out = words(decoder, digits)
            println("guess[digits] $digits -> $out")
            assertTrue("$digits should still yield candidates", out.isNotEmpty())
        }
    }

    @Test fun clean_inputs_receive_no_guess_insertion() {
        val letters = letterDecoder()
        val digits = t9Decoder()
        for ((decoder, input) in listOf(
            letters to "nihao",
            letters to "zhon",
            letters to "zh",
            letters to "zhongg",
            letters to "nihap",
            digits to "64426",
            digits to "9466",
            digits to "644",
        )) {
            assertTrue(PinyinCorrection.acceptable(input))
            assertEquals("$input is clean and must not be guessed", emptyList<String>(), decoder.guessWords(input, LIMIT))
            val out = decoder.decodeCovered(input, LIMIT)
            println("clean $input -> ${out.map { it.word }}")
            assertTrue(out.isNotEmpty())
        }
        for (case in corpus) {
            val letterGuesses = letters.guessWords(case.letters, LIMIT)
            assertTrue("${case.letters} should produce guesses", letterGuesses.isNotEmpty())
            assertTrue(letterGuesses.size <= GUESS_WINDOW)
            val letterOut = words(letters, case.letters)
            assertTrue("guesses must be listed for ${case.letters}", letterGuesses.all { it in letterOut })
            val digitGuesses = digits.guessWords(case.digits, LIMIT)
            assertTrue("${case.digits} should produce guesses", digitGuesses.isNotEmpty())
            assertTrue(digitGuesses.all { it in words(digits, case.digits) })
        }
    }

    @Test fun promoted_corrections_carry_the_repaired_reading() {
        val letter = letterDecoder().decodeCovered("zhonguo", LIMIT).first()
        assertEquals("中国", letter.word)
        assertEquals("zhongguo", letter.correctedReading)

        val digit = t9Decoder().decodeCovered("94664476", LIMIT).first()
        assertEquals("中国", digit.word)
        assertEquals("zhongguo", digit.correctedReading)
    }

    @Test fun locked_prefix_guesses_use_the_remaining_active_segment() {
        val decoder = letterDecoder()
        val letterGuess = decoder.guessLockedWords("ni", "hoa", false, setOf(2), "", GUESS_WINDOW).map { it.word }
        println("locked[letters] ni + hoa -> $letterGuess")
        assertTrue("locked ni + hoa should guess 你好, was $letterGuess", "你好" in letterGuess)
        val digitGuess = decoder.guessLockedWords("ni", "462", true, setOf(2), "", GUESS_WINDOW).map { it.word }
        println("locked[digits] ni + 462 -> $digitGuess")
        assertTrue("locked ni + 462 should guess something", digitGuess.isNotEmpty())
        assertTrue(decoder.guessLockedWords("ni", "hao", false, setOf(2), "", GUESS_WINDOW).isEmpty())
        assertTrue(decoder.guessLockedWords("ni", "", false, setOf(2), "", GUESS_WINDOW).isEmpty())
        assertTrue("a legal unfinished T9 tail must not be force-corrected", PinyinCorrection.acceptable("639"))
        assertTrue(decoder.guessLockedWords("wo", "639", true, setOf(2), "", GUESS_WINDOW).isEmpty())
    }

    @Test fun guessing_stays_cheap_on_ten_key_inputs() {
        val letters = letterDecoder()
        val digits = t9Decoder()
        for ((decoder, input) in listOf(letters to "zhonguoren", digits to "9466448677")) {
            assertFalse(PinyinCorrection.fullySegmentable(input))
            words(decoder, input)
            val start = System.nanoTime()
            repeat(5) { words(decoder, input) }
            val perCallMs = (System.nanoTime() - start) / 5 / 1_000_000.0
            println("guess timing $input -> ${"%.1f".format(perCallMs)} ms per decodeCovered")
            assertTrue("guessing should stay well under 200 ms for $input, was $perCallMs ms", perCallMs < 200.0)
        }
    }

    @Test fun corrected_readings_match_the_selected_words_on_both_layouts() {
        val letters = letterDecoder()
        val digits = t9Decoder()
        val accepts = PinyinDecoder::class.java.getDeclaredMethod("readsAs", String::class.java, String::class.java, HashMap::class.java)
        accepts.isAccessible = true
        for (case in corpus) for ((decoder, input) in listOf(letters to case.letters, digits to case.digits)) {
            val guesses = decoder.guessWords(input, LIMIT).toSet()
            for (candidate in decoder.decodeCovered(input, LIMIT).filter { it.word in guesses }) {
                val reading = requireNotNull(candidate.correctedReading)
                assertTrue("$input -> ${candidate.word} must not learn $reading",
                    accepts.invoke(letters, candidate.word, reading, HashMap<String, Set<String>>()) as Boolean)
            }
        }
    }

    private companion object {
        const val LIMIT = 30
        const val GUESS_WINDOW = 3
    }
}
