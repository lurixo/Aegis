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

package com.aegis.ime.ime

import com.aegis.ime.user.PersistedPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistedManagementPagingTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun <T> stablePage(items: List<T>, offset: Int, limit: Int, expectedVersion: Long?): PersistedPage<T> =
        if (expectedVersion != null && expectedVersion != VERSION) {
            PersistedPage(emptyList(), VERSION, restartRequired = true)
        } else {
            PersistedPage(items.drop(offset).take(limit), VERSION, items.size.toLong())
        }

    @Test fun custom_symbols_are_all_reachable_while_only_one_bounded_page_is_materialized() {
        val items = (0 until 321).map { "custom-%03d".format(it) }
        val panel = CustomSymbolPanel(context).apply {
            addPalette = emptyList()
            currentPageProvider = { offset, limit, version -> stablePage(items, offset, limit, version) }
            containsCurrent = { it in items }
            refresh()
        }

        val seen = ArrayList<String>()
        for (page in 0..5) {
            assertEquals(page, panel.addedPageForTest())
            val labels = panel.addedChipLabelsForTest()
            assertTrue(labels.size <= 56)
            seen.addAll(labels.map { it.removeSuffix(" ✕") })
            if (page < 5) assertTrue(panel.nextAddedPageForTest())
        }
        assertEquals(items, seen)
        assertFalse(panel.nextAddedPageForTest())
    }

    @Test fun emoji_recents_are_all_reachable_with_a_seventy_cell_bound() {
        val items = (0 until 321).map { "emoji-%03d".format(it) }
        val panel = EmojiView(context).apply {
            recentPageProvider = { offset, limit, version -> stablePage(items, offset, limit, version) }
            openCategoryForTest(0)
        }

        val seen = ArrayList<String>()
        for (page in 0..4) {
            assertEquals(page, panel.recentPageForTest())
            assertTrue(panel.gridCellCountForTest() <= 70)
            seen.addAll(panel.gridCellTextsForTest())
            if (page < 4) assertTrue(panel.nextRecentPageForTest())
        }
        assertEquals(items, seen)
        assertTrue(panel.emojiCellsAllocatedForTest() <= 70)
        assertFalse(panel.nextRecentPageForTest())
    }

    @Test fun symbol_recents_are_all_reachable_with_a_seventy_tile_bound() {
        val items = (0 until 321).map { "symbol-%03d".format(it) }
        val panel = SymbolsView(context).apply {
            recentPageProvider = { offset, limit, version -> stablePage(items, offset, limit, version) }
            openCategoryForTest(0)
        }

        val seen = ArrayList<String>()
        for (page in 0..4) {
            assertEquals(page, panel.recentPageForTest())
            assertTrue(panel.gridCellCountForTest() <= 70)
            seen.addAll(panel.gridCellTextsForTest())
            if (page < 4) assertTrue(panel.nextRecentPageForTest())
        }
        assertEquals(items, seen)
        assertTrue(panel.tilesAllocatedForTest() <= 70)
        assertFalse(panel.nextRecentPageForTest())
    }

    @Test fun phrase_categories_are_all_reachable_and_sort_rows_stay_page_bounded() {
        val categories = (0 until 321).map { "category-%03d".format(it) }
        val panel = ClipboardView(context).apply {
            categoryPageSnapshotProvider = { offset, limit, version -> stablePage(categories, offset, limit, version) }
            categoryIndexProvider = { name -> categories.indexOf(name).takeIf { it >= 0 }?.toLong() }
            showPhraseTab(categories.first())
        }

        val seen = ArrayList<String>()
        for (page in 0..13) {
            assertEquals(page, panel.categoryPageForTest())
            val names = panel.categoryNamesForTest()
            assertTrue(names.size <= 24)
            seen.addAll(names)
            if (page < 13) assertTrue(panel.nextCategoryPageForTest())
        }
        assertEquals(categories, seen)
        assertFalse(panel.nextCategoryPageForTest())
        panel.enterCategorySortModeForTest()
        assertTrue(panel.catSortRowsAllocatedForTest() <= 24)
    }

    @Test fun phrase_entries_traverse_a_snapshot_and_restart_after_a_concurrent_write() {
        val categories = listOf("category")
        val phrases = (0 until 181).map { "phrase-%03d".format(it) }.toMutableList()
        var version = VERSION
        val panel = ClipboardView(context).apply {
            categoryPageSnapshotProvider = { offset, limit, expected ->
                if (expected != null && expected != version) PersistedPage(emptyList(), version, restartRequired = true)
                else PersistedPage(categories.drop(offset).take(limit), version, categories.size.toLong())
            }
            categoryIndexProvider = { name -> categories.indexOf(name).takeIf { it >= 0 }?.toLong() }
            phrasePageSnapshotProvider = { _, offset, limit, expected ->
                if (expected != null && expected != version) PersistedPage(emptyList(), version, restartRequired = true)
                else PersistedPage(phrases.drop(offset).take(limit), version, phrases.size.toLong())
            }
            showPhraseTab(categories.first())
        }

        val seen = ArrayList<String>()
        for (page in 0..3) {
            assertEquals(page, panel.entryPageForTest())
            val entries = panel.entryTextsForTest()
            assertTrue(entries.size <= 60)
            seen.addAll(entries)
            if (page < 3) assertTrue(panel.nextEntryPageForTest())
        }
        assertEquals(phrases, seen)

        phrases.add(0, "concurrent")
        version++
        assertEquals(listOf("concurrent") + phrases.drop(1).take(59), panel.entryTextsForTest())
        assertEquals(0, panel.entryPageForTest())
    }

    private companion object {
        const val VERSION = 7L
    }
}
