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

import android.content.Context
import android.content.SharedPreferences
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreJournalTest {

    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        SymbolUsageStore.flushPendingWrites()
        val context: Context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        UserDictHot.host = null
        LiveUserData.restoreInProgress = false
        LiveUserData.restoreTrouble = null
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
        File(filesDir, "restore_journal").deleteRecursively()
        prefs.edit().clear().commit()
    }

    private fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (item in BackupItem.entries) {
            val f = File(filesDir, item.relativePath)
            out[item.relativePath] = if (f.isFile) f.readText() else "<absent>"
        }
        out["clips"] = File(filesDir, "clips").listFiles().orEmpty()
            .sortedBy { it.name }
            .joinToString(",") { it.name + ":" + it.readText().take(16) }
        out["<settings>"] = prefs.all.toSortedMap().toString()
        return out
    }

    private fun aRestoreWritesOverEverything() {
        File(filesDir, "userdb.txt").writeText("W\tbeifen\t备份\t1\t2000\n")
        File(filesDir, "userlearn.txt").writeText("")
        File(filesDir, "phrases.txt").writeText("C\t默认\nP\t备份常用语\n")
        File(filesDir, "clipboard.txt").writeText("备份剪贴\n")
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "symbol_usage.txt").writeText("§\t备份")
        File(filesDir, "emoji").mkdirs()
        File(filesDir, "emoji/symbol_usage.txt").writeText("🎉\t备份")
        prefs.edit().clear().putString("cn_layout", "备份布局").commit()
    }

    @Test fun a_store_that_was_not_there_before_is_taken_away_again() {
        prefs.edit().putString("cn_layout", "本机布局").commit()
        val before = snapshot()
        RestoreJournal.open(filesDir, prefs)
        aRestoreWritesOverEverything()

        assertTrue(RestoreJournal.finishAnyInterrupted(filesDir, prefs))

        assertEquals("a store the device never had must not be left behind", before, snapshot())
    }

    @Test fun the_big_clip_sidecars_a_device_never_had_are_taken_away_again() {
        prefs.edit().putString("cn_layout", "本机布局").commit()
        val clips = File(filesDir, "clips")
        assertFalse("precondition: the device carries no sidecars of its own", clips.exists())
        val before = snapshot()
        RestoreJournal.open(filesDir, prefs)
        assertTrue(clips.mkdirs())
        File(clips, "1.txt").writeText("备份大块")
        assertFalse("precondition: the restore laid down sidecars of its own", before == snapshot())

        assertTrue(RestoreJournal.finishAnyInterrupted(filesDir, prefs))

        assertEquals("a history the device never had must not be left behind", before, snapshot())
        assertFalse(clips.exists())
    }
}
