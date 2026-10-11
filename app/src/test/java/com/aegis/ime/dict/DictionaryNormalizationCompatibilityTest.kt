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

import com.aegis.tools.LmBuilder
import com.aegis.tools.Pinyin
import com.aegis.tools.T2SConversion
import com.aegis.tools.T2SMerge
import java.io.File
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryNormalizationCompatibilityTest {

    @Test fun all_dictionary_key_types_reject_incompatible_variant_readings_and_malformed_words() {
        val fixture = fixture()
        for (keyType in listOf("letter", "digit", "initials")) {
            val output = File(fixture.root, "aegis-$keyType.bin")
            com.aegis.tools.main(
                arrayOf(
                    "--out", output.path,
                    "--min-freq", "1",
                    "--keytype", keyType,
                    "--t2s-data", fixture.t2s.path,
                    fixture.source.path,
                ),
            )
            val dict = BinaryDict.fromFile(output)
            val si = key("si", listOf("si"), keyType)
            val jia = key("jia", listOf("jia"), keyType)
            val fan = key("fan", listOf("fan"), keyType)
            val dianNao = key("diannao", listOf("dian", "nao"), keyType)
            val zhong = key("zhong", listOf("zhong"), keyType)
            val chong = key("chong", listOf("chong"), keyType)
            val huaXue = key("huaxue", listOf("hua", "xue"), keyType)
            val hua = key("hua", listOf("hua"), keyType)

            assertFalse("$keyType must reject 価 si -> 价", dict.exact(si).any { it.word == "价" })
            assertTrue("$keyType keeps compatible 価/價 jia -> 价", dict.exact(jia).any { it.word == "价" })
            assertFalse("$keyType must reject 仮 fan -> 假", dict.exact(fan).any { it.word == "假" })
            assertTrue("$keyType keeps an aligned phrase mapping", dict.exact(dianNao).any { it.word == "电脑" })
            assertTrue("$keyType keeps the first legitimate polyphonic reading", dict.exact(zhong).any { it.word == "重" })
            assertTrue("$keyType keeps the second legitimate polyphonic reading", dict.exact(chong).any { it.word == "重" })
            assertFalse("$keyType rejects mappings without target reading evidence", dict.exact(si).any { it.word == "台" })
            assertFalse("$keyType rejects a length-changing phrase mapping", dict.exact(key("jiayi", listOf("jia", "yi"), keyType)).any { it.word == "甲" })
            assertEquals("$keyType keeps only the well-formed chemistry word", listOf("化学"), dict.exact(huaXue).map { it.word })
            assertFalse("$keyType rejects a word/syllable count mismatch", dict.exact(hua).any { it.word == "化学" })
        }
    }

    @Test fun language_model_uses_the_same_rejection_rule_as_the_dictionary_builders() {
        val fixture = fixture(
            """
            ---
            ...
            价	jià	1
            你	nǐ	1
            価	sì	1000
            """.trimIndent() + "\n",
        )
        val output = File(fixture.root, "aegis-lm.bin")
        LmBuilder.build(
            arrayOf(
                "--out", output.path,
                "--t2s-data", fixture.t2s.path,
                fixture.source.path,
            ),
        )
        val lm = CharBigramLM.fromFile(output)
        val expected = ln(0.4) + ln(1.0) - ln(2.0)

        assertEquals(
            "the rejected 価 si row contributes no frequency to 价",
            expected,
            lm.logCond("你".codePointAt(0), "价".codePointAt(0)),
            1e-9,
        )
    }

    @Test fun phrase_rulings_keep_a_character_that_its_reading_alone_would_convert() {
        val fixture = fixture(
            "---\n...\n" +
                "借\tjiè\t100\n" +
                "藉\tjí\t50\n" +
                "慰藉\twèi jiè\t40\n" +
                "精神慰藉\tjīng shén wèi jiè\t30\n" +
                "慰藉了\twèi jiè le\t20\n" +
                "凭藉\tpíng jiè\t10\n",
            adjudications = "藉\tjie\t借\n藉\t*\t藉\n慰藉\t*\t慰藉\n",
        )
        for (keyType in listOf("letter", "digit", "initials")) {
            val output = File(fixture.root, "aegis-$keyType.bin")
            com.aegis.tools.main(
                arrayOf(
                    "--out", output.path,
                    "--min-freq", "1",
                    "--keytype", keyType,
                    "--t2s-data", fixture.t2s.path,
                    fixture.source.path,
                ),
            )
            val dict = BinaryDict.fromFile(output)
            fun words(vararg syllables: String) =
                dict.exact(key(syllables.joinToString(""), syllables.toList(), keyType)).map { it.word }.toSet()

            assertEquals("$keyType keeps the ruled phrase", setOf("慰藉"), words("wei", "jie") intersect setOf("慰藉", "慰借"))
            assertEquals(
                "$keyType keeps the ruled phrase at the end of a longer word",
                setOf("精神慰藉"),
                words("jing", "shen", "wei", "jie") intersect setOf("精神慰藉", "精神慰借"),
            )
            assertEquals(
                "$keyType keeps the ruled phrase at the start of a longer word",
                setOf("慰藉了"),
                words("wei", "jie", "le") intersect setOf("慰藉了", "慰借了"),
            )
            assertEquals(
                "$keyType still converts the reading outside a ruled phrase",
                setOf("凭借"),
                words("ping", "jie") intersect setOf("凭藉", "凭借"),
            )
        }
    }

    @Test fun checked_in_rulings_keep_the_cushion_and_comfort_words_and_convert_the_borrowing_ones() {
        val t2s = T2SMerge.load(File("../tools/t2s-data"), mapOf("借" to setOf("jie")))
        val kept = listOf(
            "慰藉" to listOf("wei", "jie"),
            "自我慰藉" to listOf("zi", "wo", "wei", "jie"),
            "蕴藉" to listOf("yun", "jie"),
            "枕藉" to listOf("zhen", "jie"),
            "死亡枕藉" to listOf("si", "wang", "zhen", "jie"),
            "相与枕藉乎舟中" to listOf("xiang", "yu", "zhen", "jie", "hu", "zhou", "zhong"),
            "眠花藉柳" to listOf("mian", "hua", "jie", "liu"),
            "狼藉" to listOf("lang", "ji"),
        )
        for ((word, syllables) in kept) assertEquals(word, t2s.convert(word, syllables).word)
        val converted = listOf(
            Triple("藉口", listOf("jie", "kou"), "借口"),
            Triple("凭藉", listOf("ping", "jie"), "凭借"),
            Triple("藉由", listOf("jie", "you"), "借由"),
            Triple("藉故", listOf("jie", "gu"), "借故"),
            Triple("藉助", listOf("jie", "zhu"), "借助"),
        )
        for ((word, syllables, target) in converted) assertEquals(target, t2s.convert(word, syllables).word)
    }

    @Test fun both_form_rulings_add_the_written_word_beside_its_normalized_form() {
        val fixture = fixture(
            "---\n...\n" +
                "借\tjiè\t100\n" +
                "藉\tjiè\t50\n" +
                "藉\tjí\t30\n" +
                "藉口\tjiè kǒu\t20\n" +
                "藉口镇\tjiè kǒu zhèn\t10\n" +
                "去藉口镇\tqù jiè kǒu zhèn\t5\n",
            adjudications = "藉\tjie\t借\n藉\t*\t藉\n藉\t+\t藉\n藉口镇\t+\t藉口镇\n",
        )
        for (keyType in listOf("letter", "digit", "initials")) {
            val output = File(fixture.root, "aegis-$keyType.bin")
            com.aegis.tools.main(
                arrayOf(
                    "--out", output.path,
                    "--min-freq", "1",
                    "--keytype", keyType,
                    "--t2s-data", fixture.t2s.path,
                    fixture.source.path,
                ),
            )
            val dict = BinaryDict.fromFile(output)
            fun entries(vararg syllables: String) =
                dict.exact(key(syllables.joinToString(""), syllables.toList(), keyType)).associate { it.word to it.freq }

            val jie = entries("jie")
            assertEquals("$keyType keeps the written character at its own weight", 50, jie["藉"])
            assertEquals("$keyType merges the normalized character into the existing entry", 150, jie["借"])
            assertEquals(
                "$keyType keeps both forms of the ruled word at the row's weight",
                mapOf("藉口镇" to 10, "借口镇" to 10),
                entries("jie", "kou", "zhen").filterKeys { it in setOf("藉口镇", "借口镇") },
            )
            assertEquals(
                "$keyType still only normalizes an unruled word",
                setOf("借口"),
                entries("jie", "kou").keys intersect setOf("藉口", "借口"),
            )
            assertEquals(
                "$keyType still only normalizes a longer word containing a ruled word",
                setOf("去借口镇"),
                entries("qu", "jie", "kou", "zhen").keys intersect setOf("去藉口镇", "去借口镇"),
            )
        }
    }

    @Test fun language_model_counts_both_forms_like_the_dictionary_builders() {
        fun writtenCount(adjudications: String): Long {
            val fixture = fixture("---\n...\n借\tjiè\t100\n藉\tjiè\t50\n", adjudications)
            val output = File(fixture.root, "aegis-lm.bin")
            LmBuilder.build(arrayOf("--out", output.path, "--t2s-data", fixture.t2s.path, fixture.source.path))
            return CharBigramLM.fromFile(output).unigramCount("藉".codePointAt(0))
        }

        assertEquals("without the ruling the row only counts as 借", 0L, writtenCount("藉\tjie\t借\n藉\t*\t藉\n"))
        assertEquals("the ruled row also counts as written", 50L, writtenCount("藉\tjie\t借\n藉\t*\t藉\n藉\t+\t藉\n"))
    }

    @Test fun checked_in_rulings_keep_both_forms_of_the_three_undecided_words() {
        val t2s = T2SMerge.load(File("../tools/t2s-data"), mapOf("借" to setOf("jie")))
        val both = listOf(
            Triple("藉", listOf("jie"), "借"),
            Triple("非是藉秋风", listOf("fei", "shi", "jie", "qiu", "feng"), "非是借秋风"),
            Triple("藉口镇", listOf("jie", "kou", "zhen"), "借口镇"),
        )
        for ((word, syllables, normalized) in both) {
            assertEquals(T2SConversion(normalized, word), t2s.convert(word, syllables))
        }
        val single = listOf(
            Triple("藉", listOf("ji"), "藉"),
            Triple("藉口", listOf("jie", "kou"), "借口"),
            Triple("去藉口镇", listOf("qu", "jie", "kou", "zhen"), "去借口镇"),
            Triple("慰藉", listOf("wei", "jie"), "慰藉"),
        )
        for ((word, syllables, normalized) in single) {
            assertEquals(T2SConversion(normalized), t2s.convert(word, syllables))
        }
    }

    private fun key(joined: String, syllables: List<String>, keyType: String): String = when (keyType) {
        "digit" -> Pinyin.toT9(joined)
        "initials" -> syllables.joinToString("") { it.substring(0, 1) }
        else -> joined
    }

    private fun fixture(sourceText: String = fullSource, adjudications: String = ""): Fixture {
        val root = File.createTempFile("dict-normalization", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val t2s = File(root, "t2s").apply { mkdirs() }
        File(t2s, "TSCharacters.txt").writeText("價\t价\n")
        File(t2s, "TSPhrases.txt").writeText("電腦\t电脑\n甲乙\t甲\n")
        File(t2s, "variant_to_simplified.tsv").writeText("価\t价\n仮\t假\n枱\t台\n")
        File(t2s, "adjudications.tsv").writeText(adjudications)
        val source = File(root, "source.dict.yaml").apply { writeText(sourceText) }
        return Fixture(root, t2s, source)
    }

    private data class Fixture(val root: File, val t2s: File, val source: File)

    private companion object {
        val fullSource =
            "---\n...\n" +
                "价\tjià\t100\n" +
                "假\tjiǎ\t100\n" +
                "电\tdiàn\t100\n" +
                "脑\tnǎo\t100\n" +
                "重\tzhòng\t80\n" +
                "重\tchóng\t70\n" +
                "価\tsì\t1000\n" +
                "価\tjià\t90\n" +
                "價\tjià\t80\n" +
                "仮\tfǎn\t1000\n" +
                "電腦\tdiàn nǎo\t60\n" +
                "枱\tsì\t1000\n" +
                "甲乙\tjiǎ yǐ\t1000\n" +
                "化学\thuà xué\t50\n" +
                "化学huaxue\thuà xué\t1000\n" +
                "化 学\thuà xué\t1000\n" +
                "化\u0001学\thuà xué\t1000\n" +
                "化学\thuà\t1000\n"
    }
}
