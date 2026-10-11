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

import com.aegis.tools.Pinyin
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DictBuilderSupplementTest {

    @Test fun supplement_words_enter_every_key_type_unless_a_main_table_already_has_the_word() {
        val fixture = fixture(fullSupplement)
        for (keyType in listOf("letter", "digit", "initials")) {
            val plain = build(fixture, keyType, null)
            val dict = build(fixture, keyType, fixture.supplement)

            assertEquals(
                "$keyType keeps both readings of an added word and folds an exact duplicate to its largest weight",
                BinaryDict.WordFreq("银行", 20),
                dict.exact(key(listOf("yin", "hang"), keyType)).single { it.word == "银行" },
            )
            assertEquals(
                BinaryDict.WordFreq("银行", 30),
                dict.exact(key(listOf("yin", "xing"), keyType)).single { it.word == "银行" },
            )
            assertEquals(
                "$keyType gives a row without a weight column the weight 1",
                BinaryDict.WordFreq("明日方舟", 1),
                dict.exact(key(listOf("ming", "ri", "fang", "zhou"), keyType)).single { it.word == "明日方舟" },
            )
            assertFalse(
                "$keyType skips every supplement reading of a word a main table already has",
                dict.exact(key(listOf("zhong", "qing"), keyType)).any { it.word == "重庆" },
            )
            assertEquals(
                "$keyType keeps the main table weight of a skipped word",
                BinaryDict.WordFreq("重庆", 500),
                dict.exact(key(listOf("chong", "qing"), keyType)).single { it.word == "重庆" },
            )
            assertEquals(
                "$keyType rejects a supplement row whose word the traditional-to-simplified merge would rewrite",
                listOf(BinaryDict.WordFreq("电脑", 60)),
                dict.exact(key(listOf("dian", "nao"), keyType)).filter { it.word == "电脑" || it.word == "電腦" },
            )
            assertFalse(
                "$keyType drops supplement rows that break the row rules",
                dict.exact(key(listOf("fang", "zhou"), keyType)).any { it.word.startsWith("方舟") },
            )
            assertEquals(
                "$keyType adds exactly the three surviving supplement rows",
                plain.totalFreq + 20 + 30 + 1,
                dict.totalFreq,
            )
        }
    }

    @Test fun a_supplement_row_rewritten_only_under_one_reading_is_rejected_while_its_other_reading_is_added() {
        val fixture = fixture(
            "---\nname: aegis-domain\n...\n" +
                "慰藉\twei ji\t10\n" +
                "慰藉\twei jie\t40\n" +
                "狼藉\tlang jie\t40\n",
        )
        for (keyType in listOf("letter", "digit", "initials")) {
            val plain = build(fixture, keyType, null)
            val dict = build(fixture, keyType, fixture.supplement)

            assertEquals(
                "$keyType keeps the reading that leaves the word as written",
                BinaryDict.WordFreq("慰藉", 10),
                dict.exact(key(listOf("wei", "ji"), keyType)).single { it.word == "慰藉" },
            )
            assertEquals(
                "$keyType adds neither the rewritten word nor the reading that triggers the rewrite",
                listOf(BinaryDict.WordFreq("慰藉", 10)),
                listOf(listOf("wei", "ji"), listOf("wei", "jie"), listOf("lang", "jie"))
                    .flatMap { dict.exact(key(it, keyType)) }
                    .filter { it.word in setOf("慰藉", "慰借", "狼藉", "狼借") }
                    .distinct(),
            )
            assertEquals("$keyType adds exactly the one surviving supplement row", plain.totalFreq + 10, dict.totalFreq)
        }
    }

    @Test fun a_supplement_that_only_repeats_main_table_words_leaves_the_dictionary_bytes_unchanged() {
        val fixture = fixture(
            "---\nname: aegis-domain\n...\n" +
                "重庆\tzhong qing\t900\n" +
                "电脑\tdian nao\t900\n" +
                "重\tzhong\t900\n",
        )
        for (keyType in listOf("letter", "digit", "initials")) {
            val plain = File(fixture.root, "plain-$keyType.bin")
            val supplemented = File(fixture.root, "supplemented-$keyType.bin")
            run(fixture, keyType, null, plain)
            run(fixture, keyType, fixture.supplement, supplemented)

            assertArrayEquals(keyType, plain.readBytes(), supplemented.readBytes())
        }
    }

    private fun build(fixture: Fixture, keyType: String, supplement: File?): BinaryDict {
        val output = File(fixture.root, "aegis-$keyType-${supplement != null}.bin")
        run(fixture, keyType, supplement, output)
        return BinaryDict.fromFile(output)
    }

    private fun run(fixture: Fixture, keyType: String, supplement: File?, output: File) {
        val supplementArgs = if (supplement == null) emptyList() else listOf("--supplement", supplement.path)
        com.aegis.tools.main(
            (
                listOf(
                    "--out", output.path,
                    "--min-freq", "1",
                    "--keytype", keyType,
                    "--t2s-data", fixture.t2s.path,
                ) + supplementArgs + listOf(fixture.source.path)
            ).toTypedArray(),
        )
    }

    private fun key(syllables: List<String>, keyType: String): String = when (keyType) {
        "digit" -> Pinyin.toT9(syllables.joinToString(""))
        "initials" -> syllables.joinToString("") { it.substring(0, 1) }
        else -> syllables.joinToString("")
    }

    private fun fixture(supplementText: String): Fixture {
        val root = File.createTempFile("dict-supplement", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val t2s = File(root, "t2s").apply { mkdirs() }
        File(t2s, "TSCharacters.txt").writeText("")
        File(t2s, "TSPhrases.txt").writeText("電腦\t电脑\n")
        File(t2s, "variant_to_simplified.tsv").writeText("")
        File(t2s, "adjudications.tsv").writeText("藉\tjie\t借\n藉\t*\t藉\n")
        val source = File(root, "source.dict.yaml").apply {
            writeText(
                "---\n...\n" +
                    "电\tdiàn\t100\n" +
                    "脑\tnǎo\t100\n" +
                    "电脑\tdiàn nǎo\t60\n" +
                    "重\tzhòng\t80\n" +
                    "重\tchóng\t70\n" +
                    "重庆\tchóng qìng\t500\n" +
                    "银\tyín\t50\n" +
                    "行\tháng\t50\n" +
                    "行\txíng\t40\n" +
                    "借\tjiè\t30\n",
            )
        }
        val supplement = File(root, "aegis-domain.dict.yaml").apply { writeText(supplementText) }
        return Fixture(root, t2s, source, supplement)
    }

    private data class Fixture(val root: File, val t2s: File, val source: File, val supplement: File)

    private companion object {
        const val fullSupplement =
            "---\nname: aegis-domain\n...\n" +
                "重庆\tzhong qing\t900\n" +
                "重庆\tchong qing\t900\n" +
                "電腦\tdian nao\t900\n" +
                "银行\tyin hang\t10\n" +
                "银行\tyín xíng\t30\n" +
                "银行\tyin hang\t20\n" +
                "方舟ark\tfang zhou\t10\n" +
                "方舟\t方 舟\t10\n" +
                "明日方舟\tming ri fang zhou\n"
    }
}
