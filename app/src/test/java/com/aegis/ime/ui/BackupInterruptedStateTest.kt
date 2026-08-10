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
import android.os.Looper
import com.aegis.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupInterruptedStateTest {

    private val letItFinish = CountDownLatch(1)

    @Before fun nothingIsRunning() {
        assertFalse("precondition: no backup job is left over from another test", BackupJob.inProgress)
    }

    @After fun nothingIsLeftOwing() {
        drainTheJob()
        assertFalse("a job left running would leak into the next test", BackupJob.inProgress)
        val taken: (BackupUiState.Result) -> Unit = { }
        BackupJob.reportTo(taken)
        BackupJob.stopReportingTo(taken)
    }

    private fun drainTheJob() {
        letItFinish.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (BackupJob.inProgress && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun aJobThatIsStillRunning() {
        val reached = CountDownLatch(1)
        BackupJob.start {
            reached.countDown()
            letItFinish.await(30, TimeUnit.SECONDS)
            BackupUiState.Result(R.string.backup_import_ok_merge)
        }
        assertTrue("precondition: the job really did start", reached.await(30, TimeUnit.SECONDS))
    }

    private fun aJobThatFinishedWithNoPageListening() {
        BackupJob.start { BackupUiState.Result(R.string.backup_import_ok_merge) }
        drainTheJob()
        assertFalse("precondition: the job is over and no page ever heard about it", BackupJob.inProgress)
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

    private fun showing(page: BackupActivity, state: BackupUiState) {
        BackupActivity::class.java.getDeclaredMethod("setUiState", BackupUiState::class.java).run {
            isAccessible = true
            invoke(page, state)
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

    @Test fun a_page_working_on_a_job_writes_down_that_a_job_was_running() {
        aJobThatIsStillRunning()

        val written = stateWrittenDownBy { }

        assertTrue("precondition: a running job holds the page on working", written.getBoolean("backup_working"))
        assertTrue(
            "a job that was running must be written down, or nothing reports it once the process is gone",
            written.getBoolean("backup_working_job"),
        )
    }

    @Test fun a_page_working_with_no_job_of_its_own_writes_down_no_job() {
        val written = stateWrittenDownBy { showing(it, BackupUiState.Working) }

        assertTrue("precondition: the page was left working", written.getBoolean("backup_working"))
        assertFalse(
            "no job was running, so the page must not come back reporting one that was cut off",
            written.getBoolean("backup_working_job"),
        )
    }

    @Test fun a_page_that_was_not_working_writes_down_no_job() {
        aJobThatIsStillRunning()

        val written = stateWrittenDownBy { showing(it, BackupUiState.Menu) }

        assertFalse("precondition: the page was not left working", written.getBoolean("backup_working"))
        assertFalse(
            "the job flag says the page was working on a job, so a page that was not may not carry it",
            written.getBoolean("backup_working_job"),
        )
    }

    @Test fun a_job_that_finished_with_no_page_to_tell_is_not_reported_as_one_that_was_cut_off() {
        aJobThatFinishedWithNoPageListening()

        assertEquals(
            "the result is still waiting to be handed over, so the page must come back working",
            BackupUiState.Working,
            stateAfterProcessDeath(savedState(working = true, job = true)),
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
