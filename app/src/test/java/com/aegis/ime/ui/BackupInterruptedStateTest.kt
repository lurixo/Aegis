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

import android.net.Uri
import android.os.Bundle
import com.aegis.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupInterruptedStateTest {

    @Before fun nothingIsRunning() {
        assertFalse("precondition: no backup job is left over from another test", BackupJob.inProgress)
    }

    private fun savedState(working: Boolean, job: Boolean, importPending: Boolean = false) = Bundle().apply {
        putBoolean("backup_working", working)
        putBoolean("backup_working_job", job)
        putBoolean("backup_import_pending", importPending)
    }

    private fun stateAfterProcessDeath(saved: Bundle): BackupUiState {
        val controller = Robolectric.buildActivity(BackupActivity::class.java).create(saved)
        try {
            return BackupActivity::class.java.getDeclaredMethod("getUiState").run {
                isAccessible = true
                invoke(controller.get()) as BackupUiState
            }
        } finally {
            controller.destroy()
        }
    }

    private fun stateWrittenDownBy(setUpPage: (BackupActivity) -> Unit): Bundle {
        val controller = Robolectric.buildActivity(BackupActivity::class.java).create()
        try {
            setUpPage(controller.get())
            return Bundle().also { controller.saveInstanceState(it) }
        } finally {
            controller.destroy()
        }
    }

    private fun pickedFile(page: BackupActivity, uri: Uri?) {
        BackupActivity::class.java.getDeclaredField("pendingImportUri").run {
            isAccessible = true
            set(page, uri)
        }
    }

    @Test fun a_backup_whose_process_is_gone_is_not_left_turning_on_the_page() {
        assertEquals(
            "a job no process is running any more must be reported, not shown as still working",
            BackupUiState.Result(R.string.backup_job_interrupted),
            stateAfterProcessDeath(savedState(working = true, job = true)),
        )
    }

    @Test fun an_export_still_waiting_on_the_file_picker_comes_back_as_working() {
        assertEquals(
            "the picker still owes an answer, so the page must stay as it was left",
            BackupUiState.Working,
            stateAfterProcessDeath(savedState(working = true, job = false)),
        )
    }

    @Test fun a_page_that_was_not_working_comes_back_on_the_menu() {
        assertEquals(
            BackupUiState.Menu,
            stateAfterProcessDeath(savedState(working = false, job = false)),
        )
    }

    @Test fun an_import_asking_for_the_password_when_the_process_went_is_not_dropped_without_a_word() {
        assertEquals(
            "an import the page can no longer carry out must be reported, not dropped on the menu",
            BackupUiState.Result(R.string.backup_import_interrupted),
            stateAfterProcessDeath(savedState(working = false, job = false, importPending = true)),
        )
    }

    @Test fun a_job_that_was_running_is_reported_ahead_of_the_import_that_started_it() {
        assertEquals(
            "a job that really ran has more to say than the file it was handed",
            BackupUiState.Result(R.string.backup_job_interrupted),
            stateAfterProcessDeath(savedState(working = true, job = true, importPending = true)),
        )
    }

    @Test fun a_page_still_working_is_left_working_whatever_file_it_was_holding() {
        assertEquals(
            "the page was still working, so it must come back as it was left",
            BackupUiState.Working,
            stateAfterProcessDeath(savedState(working = true, job = false, importPending = true)),
        )
    }

    @Test fun a_page_holding_a_file_to_import_writes_that_down() {
        val written = stateWrittenDownBy { pickedFile(it, Uri.parse("content://com.aegis.ime.test/picked")) }

        assertTrue(
            "a page that would lose the file it was handed must write down that it was holding one",
            written.getBoolean("backup_import_pending"),
        )
    }

    @Test fun a_page_holding_no_file_writes_down_no_import() {
        val written = stateWrittenDownBy { pickedFile(it, null) }

        assertFalse(
            "a page that was handed nothing must not come back reporting an import",
            written.getBoolean("backup_import_pending"),
        )
    }

    @Test fun the_file_a_page_was_handed_is_never_written_down_itself() {
        val uri = "content://com.android.providers.downloads.documents/document/primary%3ADownload%2Fa.aegisbak"
        val written = stateWrittenDownBy { pickedFile(it, Uri.parse(uri)) }

        for (key in written.keySet()) {
            assertFalse(
                "the path a user picked from must not be written down, $key carried it",
                written.get(key).toString().contains("Download"),
            )
        }
    }
}
