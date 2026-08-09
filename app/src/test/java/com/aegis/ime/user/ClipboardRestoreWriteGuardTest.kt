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

package com.aegis.ime.user

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClipboardRestoreWriteGuardTest {

    private val dirs = ArrayList<File>()
    private val stores = ArrayList<ClipboardStore>()

    @After fun letGo() {
        LiveUserData.restoreInProgress = false
        stores.forEach { it.stopSaving() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newDir(): File = Files.createTempDirectory("cliprestoreguard").toFile().also { dirs += it }

    private fun store(dir: File) = ClipboardStore(dir).apply { load() }.also { stores += it }

    private fun sideFiles(dir: File): List<String> =
        File(dir, "clips").listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

    private fun big(marker: String): String = marker + "大".repeat(ClipboardStore.BIG_THRESHOLD)

    @Test fun a_panel_delete_during_a_restore_never_overwrites_what_the_restore_just_wrote() {
        val dir = newDir()
        val live = store(dir)
        live.record("旧一")
        live.record("旧二")
        live.flushPendingWrites()

        LiveUserData.restoreInProgress = true
        store(dir).importHistory(clipEntries("恢复一", "恢复二"), merge = false)
        live.deleteAll(listOf(live.historyKeys().first()))
        live.flushPendingWrites()

        assertEquals(listOf("恢复一", "恢复二"), store(dir).historyText())
    }

    @Test fun a_panel_delete_during_a_restore_never_sweeps_a_restored_side_file() {
        val dir = newDir()
        val live = store(dir)
        live.record("旧一")
        live.flushPendingWrites()

        val restored = big("恢复的大剪贴")
        LiveUserData.restoreInProgress = true
        store(dir).importHistory(clipEntries(restored), merge = false)
        assertEquals("precondition: the restore left a side file behind", 1, sideFiles(dir).size)

        live.deleteAll(listOf(live.historyKeys().first()))
        live.flushPendingWrites()

        assertEquals(
            "the sweep in a write is driven by the snapshot it carries, so an old snapshot deletes live data",
            1,
            sideFiles(dir).size,
        )
        assertEquals(listOf(restored), store(dir).historyText())
    }

    @Test fun a_phrase_edit_during_a_restore_never_overwrites_restored_phrases() {
        val dir = newDir()
        val live = store(dir)
        live.addCategory("甲")
        live.addPhrasesTo("甲", listOf("旧短语"))
        live.flushPendingWrites()

        LiveUserData.restoreInProgress = true
        store(dir).importPhrasesText("C\t乙\nP\t恢复短语\n", merge = false)
        live.addPhrasesTo("甲", listOf("恢复期新增"))
        live.flushPendingWrites()

        val reloaded = store(dir)
        assertEquals(listOf("恢复短语"), reloaded.phrasesIn("乙"))
        assertFalse("甲" in reloaded.categories())
    }

    @Test fun recording_a_clip_during_a_restore_never_overwrites_restored_history() {
        val dir = newDir()
        val live = store(dir)
        live.record("旧一")
        live.flushPendingWrites()

        LiveUserData.restoreInProgress = true
        store(dir).importHistory(clipEntries("恢复一"), merge = false)
        live.record("恢复期复制的")
        live.flushPendingWrites()

        assertEquals(listOf("恢复一"), store(dir).historyText())
    }

    @Test fun recording_a_symbol_during_a_restore_never_overwrites_the_restored_history() {
        val dir = newDir()
        val live = SymbolUsageStore(dir).apply { load(); record("★", "符号") }
        SymbolUsageStore.flushPendingWrites()

        LiveUserData.restoreInProgress = true
        SymbolUsageStore(dir).apply { load() }
            .importEntries(listOf(SymbolUsageStore.Entry("恢", "备份")), merge = false)
        live.record("→", "符号")
        SymbolUsageStore.flushPendingWrites()

        assertEquals(listOf("恢"), SymbolUsageStore(dir).apply { load() }.recent())
    }

    @Test fun a_panel_delete_during_a_restore_says_it_was_not_written() {
        val dir = newDir()
        val live = store(dir)
        live.record("旧一")
        live.record("旧二")
        live.flushPendingWrites()

        LiveUserData.restoreInProgress = true

        assertFalse("a delete nobody wrote must not be reported as done", live.deleteAll(listOf(live.historyKeys().first())))
        assertFalse(live.clearHistory())
    }

    @Test fun a_panel_delete_outside_a_restore_says_it_was_written() {
        val dir = newDir()
        val live = store(dir)
        live.record("旧一")
        live.record("旧二")
        live.flushPendingWrites()

        assertTrue(live.deleteAll(listOf(live.historyKeys().first())))
        assertTrue(live.clearHistory())
        assertTrue("a delete with nothing to remove has nothing to report", live.deleteAll(listOf("不存在")))
    }

    @Test fun the_panel_writes_again_once_the_restore_is_over() {
        val dir = newDir()
        val live = store(dir)
        live.record("旧一")
        live.flushPendingWrites()

        LiveUserData.restoreInProgress = true
        store(dir).importHistory(clipEntries("恢复一"), merge = false)
        LiveUserData.restoreInProgress = false

        live.load()
        live.record("恢复后复制的")
        live.addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("恢复后的常用语"))
        live.flushPendingWrites()

        val reloaded = store(dir)
        assertEquals(listOf("恢复后复制的", "恢复一"), reloaded.historyText())
        assertTrue("恢复后的常用语" in reloaded.phrases())
    }
}
