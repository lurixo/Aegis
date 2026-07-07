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
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.historyText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class RestoreUsesTheLiveClipboardStoreTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "backup-pass-01"
    private val stores = ArrayList<ClipboardStore>()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        UserDictHot.host = null
        LiveUserData.clipboardHost = null
        LiveUserData.onRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.restoreInProgress = false
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
        File(filesDir, "backup_staging").deleteRecursively()
        prefs.edit().clear().commit()
    }

    @After fun tearDown() {
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        stores.forEach { it.stopSaving() }
    }

    private fun clip(dir: File = filesDir) = ClipboardStore(dir).apply { load() }.also { stores += it }

    private fun export(): ByteArray {
        val bos = ByteArrayOutputStream()
        BackupManager.export(filesDir, prefs, password.toCharArray(), bos)
        return bos.toByteArray()
    }

    private fun restore(bytes: ByteArray) =
        BackupManager.restore(filesDir, prefs, password.toCharArray(), ByteArrayInputStream(bytes), BackupManager.Mode.OVERWRITE)

    private fun wipe() {
        listOf("phrases.txt", "clipboard.txt").forEach { File(filesDir, it).deleteRecursively() }
        File(filesDir, "clips").deleteRecursively()
    }

    @Test fun a_restore_writes_the_clipboard_through_the_store_that_owns_the_file() {
        clip().apply { record("备份里的一条"); flushPendingWrites() }
        val backup = export()
        wipe()

        val live = clip()
        LiveUserData.clipboardHost = live
        assertTrue("precondition: the live store starts out empty", live.historyText().isEmpty())

        restore(backup)

        assertEquals(
            "a second store over the same file leaves the running one showing stale data",
            listOf("备份里的一条"),
            live.historyText(),
        )
    }

    @Test fun a_restore_writes_the_phrases_through_the_store_that_owns_the_file() {
        clip().apply {
            addCategory("工作")
            addPhrasesTo("工作", listOf("已收到"))
            flushPendingWrites()
        }
        val backup = export()
        wipe()

        val live = clip()
        LiveUserData.clipboardHost = live
        assertTrue("precondition: the live store starts out with no phrases", live.phrases().isEmpty())

        restore(backup)

        assertEquals(listOf("已收到"), live.phrasesIn("工作"))
    }

    @Test fun a_restore_leaves_a_running_store_over_another_folder_alone() {
        clip().apply {
            record("备份里的一条")
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("备份里的常用语"))
            flushPendingWrites()
        }
        val backup = export()
        wipe()

        val elsewhere = File(filesDir, "another_profile").apply { deleteRecursively(); mkdirs() }
        val stranger = clip(elsewhere).apply {
            record("别处的一条")
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("别处的常用语"))
            flushPendingWrites()
        }
        LiveUserData.clipboardHost = stranger

        restore(backup)

        assertEquals(
            "a store that owns another folder must be left holding its own history",
            listOf("别处的一条"),
            stranger.historyText(),
        )
        assertEquals(listOf("别处的常用语"), stranger.phrases())
        val restored = clip()
        assertEquals("and the folder the restore was aimed at must hold the archive", listOf("备份里的一条"), restored.historyText())
        assertTrue("备份里的常用语" in restored.phrases())
    }

    @Test fun a_restore_still_lands_when_no_store_has_claimed_the_files() {
        clip().apply {
            record("备份里的一条")
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("备份里的常用语"))
            flushPendingWrites()
        }
        val backup = export()
        wipe()

        restore(backup)

        val reloaded = clip()
        assertEquals(listOf("备份里的一条"), reloaded.historyText())
        assertTrue("备份里的常用语" in reloaded.phrases())
    }

    @Test fun a_restore_never_writes_the_clipboard_through_a_store_of_its_own() {
        val src = File("src/main/java/com/aegis/ime/backup/BackupManager.kt").readText()
        val applyClipboard = body(src, "private fun applyClipboard(")
        val applyPhrases = body(src, "private fun applyPhrases(")
        for ((name, fn) in listOf("applyClipboard" to applyClipboard, "applyPhrases" to applyPhrases)) {
            assertTrue(
                "$name must write through the store that owns filesDir",
                fn.contains("LiveUserData.withClipboardStore(filesDir)"),
            )
            assertEquals(
                "$name must not build its own store over filesDir",
                fn.windowed("withClipboardStore(filesDir)".length) { it == "withClipboardStore(filesDir)" }.count { it },
                fn.windowed("ClipboardStore(filesDir)".length) { it == "ClipboardStore(filesDir)" }.count { it },
            )
        }
    }

    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("signature not found: $signature", start >= 0)
        var i = src.indexOf('{', start)
        var depth = 0
        val out = StringBuilder()
        while (i < src.length) {
            val c = src[i]
            if (c == '{') depth++
            if (depth > 0) out.append(c)
            if (c == '}') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        return out.toString()
    }
}
