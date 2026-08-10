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

import android.os.Bundle
import com.aegis.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun savedState(working: Boolean, job: Boolean) = Bundle().apply {
        putBoolean("backup_working", working)
        putBoolean("backup_working_job", job)
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
}
