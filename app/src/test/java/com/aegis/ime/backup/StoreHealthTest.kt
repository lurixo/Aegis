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

package com.aegis.ime.backup

import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreHealthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val closedOff = ArrayList<File>()

    @Before fun freshRoot() {
        root = tmp.newFolder()
    }

    @After fun letTheFilesGo() {
        closedOff.forEach { it.setReadable(true, true) }
        UserDictHot.host = null
        LiveUserData.clipboardHost = null
    }

    private fun closeOff(name: String) {
        val f = File(root, name)
        assertTrue("precondition: $name could be closed off", f.setReadable(false, false))
        closedOff += f
    }

    private fun seedEverything() {
        UserModel().apply { addManualWord("nihao", "你好", 1_000L) }.save(File(root, "userdb.txt"))
        UserLearning { 1_000L }.apply { observeCommit("备", "份", "fen", 1_000L) }.save(File(root, "userlearn.txt"))
        ClipboardStore(root).apply {
            load()
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("常用语"))
            record("剪贴板")
            flushPendingWrites()
            stopSaving()
        }
        SymbolUsageStore(root).apply { load(); record("★", "符号") }
        SymbolUsageStore(File(root, "emoji").apply { mkdirs() }).apply { load(); record("😀", "smileys") }
        SymbolUsageStore.flushPendingWrites()
    }

    @Test fun a_store_that_was_never_written_has_nothing_to_report() {
        assertEquals(emptySet<BackupItem>(), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_root_where_everything_reads_back_reports_nothing_unreadable() {
        seedEverything()
        assertEquals(emptySet<BackupItem>(), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_dictionary_that_cannot_be_parsed_is_not_readable() {
        seedEverything()
        File(root, "userdb.txt").writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n")
        assertFalse(StoreHealth.readable(root, BackupItem.DICTIONARY, liveStores = false))
        assertEquals(setOf(BackupItem.DICTIONARY), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_learning_file_that_cannot_be_parsed_is_not_readable() {
        seedEverything()
        File(root, "userlearn.txt").writeText("not a learning file at all\n")
        assertFalse(StoreHealth.readable(root, BackupItem.LEARNING, liveStores = false))
        assertEquals(setOf(BackupItem.LEARNING), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_phrase_file_that_cannot_be_read_is_not_readable() {
        seedEverything()
        closeOff("phrases.txt")
        assertFalse(StoreHealth.readable(root, BackupItem.PHRASES, liveStores = false))
        assertEquals(setOf(BackupItem.PHRASES), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_clipboard_index_that_cannot_be_read_is_not_readable() {
        seedEverything()
        closeOff("clipboard.txt")
        assertFalse(StoreHealth.readable(root, BackupItem.CLIPBOARD, liveStores = false))
        assertEquals(setOf(BackupItem.CLIPBOARD), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_symbol_history_that_cannot_be_read_is_not_readable() {
        seedEverything()
        closeOff("symbol_usage.txt")
        assertFalse(StoreHealth.readable(root, BackupItem.SYMBOL_USAGE, liveStores = false))
        assertEquals(setOf(BackupItem.SYMBOL_USAGE), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun an_emoji_history_that_cannot_be_read_is_not_readable() {
        seedEverything()
        closeOff("emoji/symbol_usage.txt")
        assertFalse(StoreHealth.readable(root, BackupItem.EMOJI_USAGE, liveStores = false))
        assertEquals(setOf(BackupItem.EMOJI_USAGE), StoreHealth.unreadableIn(root, liveStores = false))
    }

    private fun garble(name: String) {
        val f = File(root, name)
        f.writeBytes(f.readBytes().dropLast(1).toByteArray() + byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xFF.toByte()))
    }

    private fun truncateMidCharacter(name: String) {
        val f = File(root, name)
        f.writeBytes(f.readBytes() + "尾".toByteArray(Charsets.UTF_8).dropLast(1).toByteArray())
    }

    @Test fun a_phrase_file_whose_bytes_went_bad_is_not_readable() {
        seedEverything()
        garble("phrases.txt")
        assertFalse(StoreHealth.readable(root, BackupItem.PHRASES, liveStores = false))
        assertEquals(setOf(BackupItem.PHRASES), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_clipboard_index_whose_bytes_went_bad_is_not_readable() {
        seedEverything()
        garble("clipboard.txt")
        assertFalse(StoreHealth.readable(root, BackupItem.CLIPBOARD, liveStores = false))
        assertEquals(setOf(BackupItem.CLIPBOARD), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_symbol_history_whose_bytes_went_bad_is_not_readable() {
        seedEverything()
        garble("symbol_usage.txt")
        assertFalse(StoreHealth.readable(root, BackupItem.SYMBOL_USAGE, liveStores = false))
        assertEquals(setOf(BackupItem.SYMBOL_USAGE), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun an_emoji_history_whose_bytes_went_bad_is_not_readable() {
        seedEverything()
        garble("emoji/symbol_usage.txt")
        assertFalse(StoreHealth.readable(root, BackupItem.EMOJI_USAGE, liveStores = false))
        assertEquals(setOf(BackupItem.EMOJI_USAGE), StoreHealth.unreadableIn(root, liveStores = false))
    }

    @Test fun a_store_cut_off_part_way_through_a_character_is_not_readable() {
        seedEverything()
        listOf("phrases.txt", "clipboard.txt", "symbol_usage.txt", "emoji/symbol_usage.txt").forEach {
            truncateMidCharacter(it)
        }
        assertEquals(
            setOf(BackupItem.PHRASES, BackupItem.CLIPBOARD, BackupItem.SYMBOL_USAGE, BackupItem.EMOJI_USAGE),
            StoreHealth.unreadableIn(root, liveStores = false),
        )
    }

    @Test fun the_clipboard_store_that_owns_the_files_answers_for_them() {
        seedEverything()
        closeOff("phrases.txt")
        closeOff("clipboard.txt")
        val live = ClipboardStore(root).apply { load() }
        LiveUserData.clipboardHost = live
        try {
            assertFalse("precondition: the live store could read neither file", live.phrasesReadable)
            assertFalse(live.historyReadable)
            File(root, "phrases.txt").setReadable(true, true)
            File(root, "clipboard.txt").setReadable(true, true)

            assertEquals(
                "the store holding the files knows they went bad even after the files come back",
                setOf(BackupItem.PHRASES, BackupItem.CLIPBOARD),
                StoreHealth.unreadableIn(root, liveStores = true),
            )
            assertEquals(
                "without the live stores the check reads the files as they are now",
                emptySet<BackupItem>(),
                StoreHealth.unreadableIn(root, liveStores = false),
            )
        } finally {
            live.stopSaving()
        }
    }

    @Test fun asking_after_a_store_never_writes_to_it() {
        seedEverything()
        val files = listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt")
            .map { File(root, it) } + File(root, "emoji/symbol_usage.txt")
        val before = files.associateWith { it.readBytes().toList() to it.lastModified() }

        StoreHealth.unreadableIn(root, liveStores = false)

        for (f in files) {
            assertEquals("${f.name} content", before.getValue(f).first, f.readBytes().toList())
            assertEquals("${f.name} timestamp", before.getValue(f).second, f.lastModified())
        }
    }
}
