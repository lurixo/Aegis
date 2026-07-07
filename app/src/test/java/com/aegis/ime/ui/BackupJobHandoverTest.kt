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
import com.aegis.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupJobHandoverTest {

    private val drain: (BackupUiState.Result) -> Unit = {}

    @After fun leaveNothingParked() {
        BackupJob.reportTo(drain)
        BackupJob.stopReportingTo(drain)
    }

    private fun page(): Pair<(BackupUiState.Result) -> Unit, List<BackupUiState.Result>> {
        val seen = ArrayList<BackupUiState.Result>()
        return { result: BackupUiState.Result -> seen.add(result); Unit } to seen
    }

    private fun runJobReporting(messageRes: Int) {
        val ran = CountDownLatch(1)
        BackupJob.start {
            ran.countDown()
            BackupUiState.Result(messageRes)
        }
        assertTrue("precondition: the job body really ran", ran.await(30, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (BackupJob.inProgress && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("precondition: the job finished", BackupJob.inProgress)
    }

    @Test fun a_job_that_finished_with_nobody_watching_is_kept_for_the_page_to_come_back_to() {
        runJobReporting(R.string.backup_import_ok_merge)

        val (page, seen) = page()
        BackupJob.reportTo(page)

        assertEquals(
            "a result nobody was there for must reach the page that comes back, not be dropped",
            listOf(BackupUiState.Result(R.string.backup_import_ok_merge)),
            seen,
        )
        BackupJob.stopReportingTo(page)
    }

    @Test fun a_kept_result_is_handed_over_once_and_never_again() {
        runJobReporting(R.string.backup_export_ok)

        val (first, firstSeen) = page()
        BackupJob.reportTo(first)
        assertEquals("precondition: the first page took the result", 1, firstSeen.size)
        BackupJob.stopReportingTo(first)

        val (second, secondSeen) = page()
        BackupJob.reportTo(second)

        assertEquals(
            "a result the user has already been shown must not be shown again on the next visit",
            emptyList<BackupUiState.Result>(),
            secondSeen,
        )
        BackupJob.stopReportingTo(second)
    }

    @Test fun a_job_that_finished_while_the_page_was_watching_goes_straight_to_it() {
        val (open, openSeen) = page()
        BackupJob.reportTo(open)

        runJobReporting(R.string.backup_import_ok_overwrite)

        assertEquals(
            listOf(BackupUiState.Result(R.string.backup_import_ok_overwrite)),
            openSeen,
        )
        BackupJob.stopReportingTo(open)

        val (later, laterSeen) = page()
        BackupJob.reportTo(later)
        assertEquals(
            "a result already delivered must not be kept for a later visit as well",
            emptyList<BackupUiState.Result>(),
            laterSeen,
        )
        BackupJob.stopReportingTo(later)
    }
}
