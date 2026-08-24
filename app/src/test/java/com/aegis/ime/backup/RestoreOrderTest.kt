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
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreOrderTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "order-pass-01"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        wipe()
        File(filesDir, "backup_staging").deleteRecursively()
        prefs.edit().clear().commit()
    }

    private fun wipe() {
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
    }

    private class RecordingPrefs(
        private val real: SharedPreferences,
        private val beforeCommit: () -> Unit,
    ) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = RecordingEditor(real.edit(), beforeCommit)

        private class RecordingEditor(
            private val real: SharedPreferences.Editor,
            private val beforeCommit: () -> Unit,
        ) : SharedPreferences.Editor by real {
            override fun commit(): Boolean {
                beforeCommit()
                return real.commit()
            }
        }
    }

    private fun seedTheBackupData() {
        UserModel().apply { addManualWord("beifen", "备份", 2_000L) }.save(File(filesDir, "userdb.txt"))
        UserLearning { 2_000L }.apply { observeCommit("备", "份", "fen", 2_000L) }
            .save(File(filesDir, "userlearn.txt"))
        ClipboardStore(filesDir).apply {
            load()
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("备份常用语"))
            record("备份剪贴")
            flushPendingWrites()
            stopSaving()
        }
        SymbolUsageStore(filesDir).apply { load(); record("§", "备份"); awaitPendingWrites() }
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("🎉", "备份"); awaitPendingWrites() }
        prefs.edit().putString("cn_layout", "备份布局").commit()
    }

    private fun export(): ByteArray {
        val bos = ByteArrayOutputStream()
        BackupManager.export(filesDir, prefs, password.toCharArray(), bos)
        return bos.toByteArray()
    }

    private fun storeFiles(): List<String> = listOf(
        "userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt",
        "symbol_usage.txt", "emoji/symbol_usage.txt",
    )

    @Test fun the_settings_are_committed_only_after_every_store_is_in_place() {
        seedTheBackupData()
        val backup = export()
        wipe()
        prefs.edit().clear().commit()

        val onDiskWhenSettingsWereCommitted = ArrayList<String>()
        val recording = RecordingPrefs(prefs) {
            storeFiles().forEach { rel ->
                val f = File(filesDir, rel)
                if (f.isFile && f.length() > 0) onDiskWhenSettingsWereCommitted += rel
            }
        }

        BackupManager.restore(
            filesDir,
            recording,
            password.toCharArray(),
            ByteArrayInputStream(backup),
            BackupManager.Mode.OVERWRITE,
        )

        assertEquals(
            "every store must already carry the backup by the time the settings are committed",
            storeFiles(),
            onDiskWhenSettingsWereCommitted,
        )
        assertEquals("备份布局", prefs.getString("cn_layout", null))
    }

    @Test fun a_store_that_could_not_be_written_leaves_the_settings_alone() {
        seedTheBackupData()
        val backup = export()
        wipe()
        prefs.edit().clear().putString("cn_layout", "本机布局").commit()
        File(filesDir, "symbol_usage.txt").let {
            it.deleteRecursively()
            assertTrue("precondition: the symbol history path is blocked", it.mkdirs())
            File(it, "blocker").writeText("x")
        }

        try {
            BackupManager.restore(
                filesDir,
                prefs,
                password.toCharArray(),
                ByteArrayInputStream(backup),
                BackupManager.Mode.OVERWRITE,
            )
            fail("expected the blocked symbol history to be reported")
        } catch (e: BackupException) {
            assertEquals(BackupError.IO_ERROR, e.error)
        }

        assertEquals(
            "settings are the one thing a restore cannot take back, so they must go last",
            "本机布局",
            prefs.getString("cn_layout", null),
        )
    }
}
