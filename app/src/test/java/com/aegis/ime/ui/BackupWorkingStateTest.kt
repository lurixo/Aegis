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

import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupWorkingStateTest {

    @get:Rule val compose = createAndroidComposeRule<BackupActivity>()

    private val reached = CountDownLatch(1)
    private val letItFinish = CountDownLatch(1)

    private fun label(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun aRestoreThatIsStillRunning() {
        BackupJob.start {
            reached.countDown()
            letItFinish.await(30, TimeUnit.SECONDS)
            BackupUiState.Result(R.string.backup_import_ok_overwrite)
        }
        assertTrue("precondition: the job really did start", reached.await(30, TimeUnit.SECONDS))
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

    @After fun letEverythingGo() {
        drainTheJob()
        assertFalse("a job left running would leak into the next test", BackupJob.inProgress)
    }

    @Test fun a_restore_still_running_is_still_shown_as_running_after_the_screen_turns() {
        aRestoreThatIsStillRunning()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText(label(R.string.backup_working)).assertExists()
        compose.onNodeWithText(label(R.string.backup_import_button)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.backup_export_button)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun a_restore_still_running_never_traps_you_on_the_page() {
        aRestoreThatIsStillRunning()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.backup_working)).assertExists()

        compose.onNodeWithContentDescription(label(R.string.settings_back)).performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue("a restore that hangs must never hold the page shut", compose.activity.isFinishing)
    }

    @Test fun a_restore_that_finished_after_the_screen_turned_still_reports_to_the_page() {
        aRestoreThatIsStillRunning()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        drainTheJob()
        compose.waitForIdle()

        compose.onNodeWithText(label(R.string.backup_import_ok_overwrite)).assertExists()
        compose.onNodeWithText(label(R.string.backup_working)).assertDoesNotExist()
    }

    @Test fun an_export_waiting_on_the_file_picker_is_still_shown_as_running_after_the_screen_turns() {
        BackupActivity::class.java
            .getDeclaredMethod("beginExport", String::class.java)
            .apply { isAccessible = true }
            .invoke(compose.activity, "backup-pass-01")
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.backup_working)).assertExists()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText(label(R.string.backup_working)).assertExists()
        compose.onNodeWithText(label(R.string.backup_export_button)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun a_page_opened_with_nothing_running_is_not_shown_as_running() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText(label(R.string.backup_working)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.backup_import_button)).performScrollTo().assertIsEnabled()
    }
}
