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

package com.aegis.ime.ui

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import com.aegis.ime.backup.BackupManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<BackupActivity>()

    @Test fun shows_export_and_import_actions() {
        compose.onNodeWithText(str(R.string.backup_default_password_title)).assertExists()
        compose.onNodeWithText(str(R.string.backup_export_button)).assertExists()
        compose.onNodeWithText(str(R.string.backup_import_button)).assertExists()
    }

    @Test fun removing_default_password_requires_second_step_confirmation() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("aegis_backup_default_password", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putInt("version", 1)
            .putString("iv", "AA==")
            .putString("ciphertext", "AA==")
            .commit()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.backup_default_password_remove_button)).performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue("first remove click must not clear the saved password", prefs.contains("ciphertext"))
        compose.onNodeWithText(str(R.string.backup_default_password_remove_title)).assertExists()

        compose.onNodeWithText(str(R.string.backup_default_password_remove_confirm_button)).performClick()
        compose.waitForIdle()

        assertFalse("confirmation must clear the saved password", prefs.contains("ciphertext"))
        compose.onNodeWithText(str(R.string.backup_default_password_remove_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.backup_default_password_removed)).assertExists()
    }

    private fun writeExport(uri: Uri): Result<Any?> {
        val method = BackupActivity::class.java
            .getDeclaredMethod("writeExport", Uri::class.java, CharArray::class.java)
        method.isAccessible = true
        return runCatching { method.invoke(compose.activity, uri, "backup-pass-01".toCharArray()) }
    }

    private fun registerDocument(uri: Uri, sink: ByteArrayOutputStream) {
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerOutputStream(uri, sink)
    }

    @Test fun an_export_whose_document_was_never_committed_is_not_reported_as_written() {
        val uri = Uri.parse("content://com.aegis.ime.test/refused-on-close")
        registerDocument(
            uri,
            object : ByteArrayOutputStream() {
                override fun close() = throw IOException("the document was never committed")
            },
        )

        val written = writeExport(uri)

        assertTrue(
            "a document the provider only commits on close must not be reported as an exported backup",
            written.isFailure,
        )
    }

    @Test fun an_export_over_a_longer_file_leaves_none_of_the_old_one_behind() {
        val target = File(RuntimeEnvironment.getApplication().cacheDir, "over-a-longer-file.aegisbak")
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(200_000))
        val stale = target.length()

        val written = writeExport(Uri.fromFile(target))

        assertTrue("precondition: the export itself must go through", written.isSuccess)
        assertTrue(
            "an export must not leave the tail of a longer file behind, was ${target.length()} of $stale bytes",
            target.length() < stale,
        )
    }

    @Test fun an_export_whose_document_was_committed_is_reported_as_written() {
        val uri = Uri.parse("content://com.aegis.ime.test/taken")
        val sink = ByteArrayOutputStream()
        registerDocument(uri, sink)

        val written = writeExport(uri)

        assertTrue(written.isSuccess)
        assertNotNull("a finished export must come back with a report", written.getOrNull())
        assertTrue(written.getOrNull() is BackupManager.ExportReport)
        assertTrue("and the document must carry the backup", sink.size() > 0)
    }

    private fun drainTheJob() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (BackupJob.inProgress && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun letEverythingGo() {
        drainTheJob()
        assertFalse("a job left running would leak into the next test", BackupJob.inProgress)
    }

    private fun beginExport(password: String) {
        BackupActivity::class.java
            .getDeclaredMethod("beginExport", String::class.java)
            .apply { isAccessible = true }
            .invoke(compose.activity, password)
    }

    private fun pickExportTarget(uri: Uri?) {
        BackupActivity::class.java
            .getDeclaredMethod("onExportTarget", Uri::class.java)
            .apply { isAccessible = true }
            .invoke(compose.activity, uri)
    }

    @Test fun an_export_that_lost_its_password_when_the_page_was_rebuilt_says_so() {
        beginExport("backup-pass-01")
        compose.waitForIdle()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        pickExportTarget(Uri.parse("content://com.aegis.ime.test/chosen-after-rebuild"))
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.backup_export_interrupted)).assertExists()
    }

    @Test fun an_export_that_still_had_its_password_is_not_reported_as_interrupted() {
        val uri = Uri.parse("content://com.aegis.ime.test/kept-its-password")
        registerDocument(uri, ByteArrayOutputStream())

        beginExport("backup-pass-01")
        compose.waitForIdle()
        pickExportTarget(uri)
        drainTheJob()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.backup_export_interrupted)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.backup_export_ok)).assertExists()
    }

    @Test fun cancelling_the_file_picker_still_says_nothing() {
        beginExport("backup-pass-01")
        compose.waitForIdle()

        pickExportTarget(null)
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.backup_export_interrupted)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.backup_working)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.backup_export_button)).performScrollTo().assertExists()
    }

    private fun beginImport(password: String, mode: BackupManager.Mode) {
        BackupActivity::class.java
            .getDeclaredMethod("beginImport", String::class.java, BackupManager.Mode::class.java)
            .apply { isAccessible = true }
            .invoke(compose.activity, password, mode)
    }

    @Test fun an_import_that_lost_the_file_it_was_given_says_so() {
        beginImport("backup-pass-01", BackupManager.Mode.OVERWRITE)
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.backup_import_interrupted)).assertExists()
    }

    @Test fun back_arrow_finishes_the_activity() {
        compose.onNodeWithContentDescription(str(R.string.settings_back)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.isFinishing)
    }
}
