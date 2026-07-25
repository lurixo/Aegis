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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictBuilderCompletenessTest {

    @Test fun thresholded_seed_keeps_the_requested_minimum_singles_per_syllable() {
        val dir = File.createTempFile("dict-completeness", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val source = File(dir, "source.dict.yaml").apply {
            writeText(
                """
                ---
                ...
                甲	jia	500
                乙	jia	300
                丙	jia	200
                丁	jia	100
                """.trimIndent() + "\n",
            )
        }
        val output = File(dir, "aegis_dict.bin")

        com.aegis.tools.main(
            arrayOf(
                "--out", output.path,
                "--min-freq", "400",
                "--keep-syllable-singles", "3",
                source.path,
            ),
        )

        assertEquals(listOf("甲", "乙", "丙"), BinaryDict.fromFile(output).exact("jia").map { it.word })
    }

    @Test fun full_dictionary_clamps_non_positive_source_frequencies_instead_of_dropping_entries() {
        val dir = File.createTempFile("dict-non-positive", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val source = File(dir, "source.dict.yaml").apply {
            writeText(
                """
                ---
                ...
                𤭢	cèi	0
                覅	fiào	-1
                """.trimIndent() + "\n",
            )
        }
        val output = File(dir, "aegis_dict.bin")

        com.aegis.tools.main(
            arrayOf(
                "--out", output.path,
                "--min-freq", "1",
                source.path,
            ),
        )

        val dict = BinaryDict.fromFile(output)
        assertEquals(BinaryDict.WordFreq("𤭢", 1), dict.exact("cei").single())
        assertEquals(BinaryDict.WordFreq("覅", 1), dict.exact("fiao").single())
    }

    @Test fun source_backed_rare_readings_are_canonical() {
        assertTrue(listOf("cei", "fiao", "tei").all { it in com.aegis.tools.Pinyin.canonicalSyllables })
    }

    @Test fun decomposed_tones_and_format_marks_do_not_drop_source_rows() {
        val dir = File.createTempFile("dict-pinyin-normalization", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val source = File(dir, "source.dict.yaml").apply {
            writeText(
                "---\n...\n" +
                    "呣\tm\u0300\t4\n" +
                    "啷咯情歌\tlǎng gē qíng gē\u200B\u200B\t76\n",
            )
        }
        val output = File(dir, "aegis_dict.bin")

        com.aegis.tools.main(arrayOf("--out", output.path, "--min-freq", "1", source.path))

        val dict = BinaryDict.fromFile(output)
        assertEquals(BinaryDict.WordFreq("呣", 4), dict.exact("m").single())
        assertEquals(BinaryDict.WordFreq("啷咯情歌", 76), dict.exact("langgeqingge").single())
    }
}
