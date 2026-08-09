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

import com.aegis.ime.dict.CharBigramLM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DynamicLambdaTest {

    private fun cp(c: Char) = c.code

    private fun lm(): CharBigramLM {
        val uni = mapOf(
            '词' to 800L, '库' to 900L, '苦' to 850L, '哭' to 800L, '酷' to 700L,
            '不' to 950L, '是' to 950L, '时' to 920L, '实' to 900L, '事' to 860L,
            '想' to 930L, '相' to 900L, '向' to 980L, '香' to 800L,
            '九' to 900L, '就' to 880L, '键' to 900L, '见' to 880L, '间' to 840L,
            '字' to 900L, '自' to 860L, '子' to 880L,
        ).mapKeys { it.key.code }
        val bi = mapOf(
            (cp('词') to cp('库')) to 5000L,
            (cp('不') to cp('是')) to 5000L,
            (cp('想') to cp('哭')) to 5000L,
            (cp('九') to cp('键')) to 3000L,
            (cp('不') to cp('时')) to 200L,
            (cp('想') to cp('相')) to 400L,
        )
        return EngineFixture.buildLm(uni, bi)
    }

    private val model = lm()

    private fun decoder(lambda: Double) = PinyinDecoder(EngineFixture.dict(), model, lambda)

    private fun render(lambda: Double, context: String): String {
        val decoder = decoder(lambda)
        return INPUTS.joinToString("\n") { input ->
            "covered|$input -> " +
                decoder.decodeCovered(input, 12, context = context).joinToString(",") { it.word } +
                "\natomic|$input -> " +
                decoder.decodeCoveredAtomic(input, 12, context = context).joinToString(",") { it.word }
        }
    }

    @Test fun the_word_bigram_still_ranks_while_nothing_has_been_committed() {
        for (weight in OTHER_WEIGHTS) {
            assertNotEquals("lambda=$weight must change ranking with no committed text", render(0.0, ""), render(weight, ""))
        }
    }

    @Test fun the_word_bigram_stops_ranking_once_han_text_has_been_committed() {
        val off = HAN_CONTEXTS.associateWith { render(0.0, it) }
        for (context in HAN_CONTEXTS) {
            for (weight in OTHER_WEIGHTS) {
                assertEquals("lambda=$weight must not change ranking after \"$context\"", off.getValue(context), render(weight, context))
            }
        }
    }

    @Test fun a_committed_tail_that_carries_no_han_reads_as_a_fresh_start() {
        for (context in NON_HAN_CONTEXTS) {
            assertEquals("\"$context\" must decode as an empty context", render(PinyinDecoder.DEFAULT_LAMBDA, ""), render(PinyinDecoder.DEFAULT_LAMBDA, context))
            assertNotEquals("lambda must still rank after \"$context\"", render(0.0, context), render(4.0, context))
        }
    }

    @Test fun the_shipped_default_matches_a_zero_weight_once_text_has_been_committed() {
        for (context in HAN_CONTEXTS) {
            assertEquals(render(0.0, context), render(PinyinDecoder.DEFAULT_LAMBDA, context))
        }
    }

    private fun renderSentences(lambda: Double, context: String): String {
        val decoder = decoder(lambda)
        return SENTENCE_INPUTS.joinToString("\n") { input ->
            "$input -> " + decoder.decodeCovered(input, 12, context = context).joinToString(",") {
                "${it.word}/${it.coveredLen}"
            }
        }
    }

    @Test fun the_free_segmentation_sentence_drops_the_word_bigram_once_text_has_been_committed() {
        for (context in HAN_CONTEXTS) {
            val off = renderSentences(0.0, context)
            for (weight in OTHER_WEIGHTS) {
                assertEquals("lambda=$weight must not move the sentence path after \"$context\"", off, renderSentences(weight, context))
            }
        }
    }

    private fun sentenceOf(decoder: PinyinDecoder, input: String, context: String): String? =
        BEST_SENTENCE.invoke(decoder, input, emptySet<Int>(), decoder.parseContext(context)) as String?

    @Test fun the_free_segmentation_sentence_still_uses_the_word_bigram_at_a_fresh_start() {
        val shipped = decoder(PinyinDecoder.DEFAULT_LAMBDA)
        val off = decoder(0.0)
        for ((input, expected) in FRESH_START_SENTENCES) {
            val (withWeight, withoutWeight) = expected
            assertEquals("bestSentence(\"$input\") at the shipped weight", withWeight, sentenceOf(shipped, input, ""))
            assertEquals("bestSentence(\"$input\") at weight zero", withoutWeight, sentenceOf(off, input, ""))
        }
        assertNotEquals(
            "the table must hold at least one input the weight actually moves",
            FRESH_START_SENTENCES.values.map { it.first },
            FRESH_START_SENTENCES.values.map { it.second },
        )
    }

    @Test fun the_free_segmentation_sentence_still_reads_the_committed_tail_after_it() {
        val shipped = decoder(PinyinDecoder.DEFAULT_LAMBDA)
        for ((key, expected) in COMMITTED_SENTENCES) {
            val (context, input) = key
            assertEquals("bestSentence(\"$input\") after \"$context\"", expected, sentenceOf(shipped, input, context))
        }
    }

    private companion object {
        val INPUTS = listOf("ku", "shi", "ci", "jian", "zi", "xiang", "xiangku", "bushi", "jiujian")
        val SYLLABLES = listOf("ci", "ku", "zi", "bu", "shi", "xian", "xi", "an", "xiang", "xia", "jiu", "jian")
        val SENTENCE_INPUTS = SYLLABLES.flatMap { a -> SYLLABLES.map { b -> a + b } }
        val OTHER_WEIGHTS = listOf(PinyinDecoder.DEFAULT_LAMBDA, 1.0, 4.0)
        val HAN_CONTEXTS = listOf("想", "词", "不", "九", "我们想")
        val NON_HAN_CONTEXTS = listOf("", "abc", "hello, ", "1234", "。")
        val FRESH_START_SENTENCES = linkedMapOf(
            "cici" to ("次词" to "次次"),
            "cixian" to ("次现" to "次西安"),
            "kuci" to ("库词" to "库次"),
            "xianci" to ("现词" to "西安次"),
            "shixian" to ("实现" to "实现"),
        )
        val COMMITTED_SENTENCES = linkedMapOf(
            ("想" to "ci") to "词",
            ("想" to "ku") to "哭",
            ("不" to "ci") to "词",
            ("想" to "cici") to "词次",
            ("不" to "cici") to "词次",
        )
        val BEST_SENTENCE = PinyinDecoder::class.java.getDeclaredMethod(
            "bestSentence", String::class.java, Set::class.java, PinyinDecoder.Ctx::class.java,
        ).apply { isAccessible = true }
    }
}
