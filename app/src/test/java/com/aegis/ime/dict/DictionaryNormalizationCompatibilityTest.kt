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

    private fun key(joined: String, syllables: List<String>, keyType: String): String = when (keyType) {
        "digit" -> Pinyin.toT9(joined)
        "initials" -> syllables.joinToString("") { it.substring(0, 1) }
        else -> joined
    }

    private fun fixture(sourceText: String = fullSource): Fixture {
        val root = File.createTempFile("dict-normalization", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val t2s = File(root, "t2s").apply { mkdirs() }
        File(t2s, "TSCharacters.txt").writeText("價\t价\n")
        File(t2s, "TSPhrases.txt").writeText("電腦\t电脑\n甲乙\t甲\n")
        File(t2s, "variant_to_simplified.tsv").writeText("価\t价\n仮\t假\n枱\t台\n")
        File(t2s, "adjudications.tsv").writeText("")
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
