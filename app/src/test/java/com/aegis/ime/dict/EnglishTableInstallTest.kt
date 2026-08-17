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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EnglishTableInstallTest {

    private fun tempDir(): File =
        File.createTempFile("aegis-pack", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun writeZip(dest: File, entries: Map<String, ByteArray>) {
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun packEntries(): Map<String, ByteArray> = mapOf(
        "NOTICE.txt" to "notice".toByteArray(),
        "aegis_dict_full.bin" to "dict".toByteArray(),
        "aegis_t9_full.bin" to "t9".toByteArray(),
        "aegis_jianpin_full.bin" to "jianpin".toByteArray(),
        ModelDownload.LM_NAME to "lm".toByteArray(),
        ModelDownload.EN_PACK_ENTRY to "english".toByteArray(),
    )

    @Test
    fun the_current_external_english_table_is_parseable() {
        val configured = System.getenv("AEGIS_ENGLISH")?.takeIf { it.isNotBlank() }
        assumeTrue("AEGIS_ENGLISH set for the real-table gate", configured != null)
        val file = File(configured!!)
        assertTrue("AEGIS_ENGLISH points to a non-trivial file", file.isFile && file.length() > 1024)

        val table = BinaryDict.fromFile(file)

        assertTrue("the real English table has positive total frequency", table.totalFreq > 0L)
        assertTrue(
            "the real English table carries the known word key",
            table.exact("word").any { it.word == "word" && it.freq > 0 },
        )
        assertTrue("the real English table serves prefix completions", table.prefixByFreq("hel", 16).isNotEmpty())
    }

    @Test
    fun the_english_entry_installs_under_its_own_runtime_name() {
        val dir = tempDir()
        val zip = File(dir, "pack.zip")
        writeZip(zip, packEntries())

        val produced = ModelDownload.extractDictPack(zip, File(dir, "staging"))

        assertTrue(ModelDownload.EN_NAME in produced)
        assertEquals("english", File(dir, "staging/${ModelDownload.EN_NAME}").readText())
        assertEquals("dict", File(dir, "staging/aegis_dict.bin").readText())
        assertEquals("t9", File(dir, "staging/aegis_t9.bin").readText())
        assertEquals("jianpin", File(dir, "staging/aegis_jianpin.bin").readText())
    }

    @Test
    fun a_pack_without_the_english_entry_still_installs_the_chinese_tables() {
        val dir = tempDir()
        val zip = File(dir, "pack.zip")
        writeZip(zip, packEntries() - ModelDownload.EN_PACK_ENTRY)

        val produced = ModelDownload.extractDictPack(zip, File(dir, "staging"))

        assertFalse(ModelDownload.EN_NAME in produced)
        assertEquals(ModelDownload.DICT_PACK_FILES.toSet(), produced)
    }

    @Test
    fun the_english_names_never_route_onto_a_chinese_table() {
        assertTrue(ModelDownload.routesOnlyToItself(ModelDownload.EN_PACK_ENTRY))
        assertTrue(ModelDownload.routesOnlyToItself(ModelDownload.EN_NAME))
        assertFalse("a name carrying dict is installed as the pinyin table by older releases",
            ModelDownload.routesOnlyToItself("aegis_en_dict.bin"))
        assertFalse(ModelDownload.routesOnlyToItself("aegis_english_t9.bin"))
        assertFalse(ModelDownload.routesOnlyToItself("AEGIS_EN_JIANPIN.BIN"))
        assertFalse(ModelDownload.routesOnlyToItself(ModelDownload.LM_NAME))
    }

    @Test
    fun the_english_runtime_name_is_not_a_bundled_era_leftover() {
        val bundledEraNames = ModelDownload.DICT_PACK_FILES + listOf("aegis_en.bin", "aegis_fuzzy.bin")
        assertFalse(ModelDownload.EN_NAME in bundledEraNames)
    }

    @Test
    fun the_english_table_joins_the_managed_set_without_widening_completeness() {
        assertEquals(ModelDownload.DICT_PACK_FILES + ModelDownload.EN_NAME, ModelDownload.DICT_MANAGED_FILES)
        assertFalse(ModelDownload.EN_NAME in ModelDownload.DICT_PACK_FILES)
        assertFalse(ModelDownload.EN_NAME in ModelDownload.DICT_BIN_FILES)
    }

    @Test
    fun purging_the_dictionary_takes_the_english_table_with_it() {
        val filesDir = tempDir()
        val downloaded = File(filesDir, "downloaded").apply { mkdirs() }
        ModelDownload.DICT_MANAGED_FILES.forEach { File(downloaded, it).writeBytes(ByteArray(2048)) }

        assertTrue(ModelDownload.purgeDict(filesDir))
        assertFalse(File(downloaded, ModelDownload.EN_NAME).exists())
    }

    @Test
    fun installing_a_pack_without_english_drops_a_previously_installed_english_table() {
        val filesDir = tempDir()
        val downloaded = File(filesDir, "downloaded").apply { mkdirs() }
        File(downloaded, ModelDownload.EN_NAME).writeBytes(ByteArray(2048))
        val zip = ModelDownload.dictZipFile(filesDir)
        writeZip(zip, (packEntries() - ModelDownload.EN_PACK_ENTRY).mapValues { ByteArray(2048) })

        assertTrue(ModelDownload.installDictPack(filesDir, ModelDownload.sha256Of(zip)))
        assertFalse(File(downloaded, ModelDownload.EN_NAME).exists())
        assertTrue(ModelDownload.isDictPackComplete(filesDir))
    }

    @Test
    fun installing_a_pack_with_english_lands_it_next_to_the_chinese_tables() {
        val filesDir = tempDir()
        File(filesDir, "downloaded").mkdirs()
        val zip = ModelDownload.dictZipFile(filesDir)
        writeZip(zip, packEntries().mapValues { ByteArray(2048) })

        assertTrue(ModelDownload.installDictPack(filesDir, ModelDownload.sha256Of(zip)))
        assertTrue(File(File(filesDir, "downloaded"), ModelDownload.EN_NAME).exists())
        assertTrue(ModelDownload.isDictPackComplete(filesDir))
    }
}
