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

import com.aegis.ime.R
import com.aegis.ime.dict.ModelDownload
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadCardWorkTest {

    private val context = RuntimeEnvironment.getApplication()
    private val done = LocalizedText.Raw("done")
    private val absent = LocalizedText.Raw("absent")
    private val failed = LocalizedText.Raw("failed")

    private fun runtime() = DownloadRuntime(
        isPresent = { false },
        doneStatus = { done },
        notDownloadedStatus = absent,
        failureStatus = failed,
    )

    private fun awaitTerminal(runtime: DownloadRuntime): DownloadCardSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var snapshot = runtime.snapshot(context)
        while (snapshot.downloading && System.nanoTime() < deadline) {
            Thread.yield()
            snapshot = runtime.snapshot(context)
        }
        return snapshot
    }

    @Test fun active_download_is_indeterminate_until_known_progress_arrives() {
        val allowProgress = CountDownLatch(1)
        val progressReported = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val runtime = runtime()

        runtime.start(context) { _, onProgress, _ ->
            allowProgress.await(2, TimeUnit.SECONDS)
            onProgress(0.4f)
            progressReported.countDown()
            allowFinish.await(2, TimeUnit.SECONDS)
            done
        }

        val initial = runtime.snapshot(context)
        assertTrue(initial.downloading)
        assertNull(initial.progress)
        allowProgress.countDown()
        assertTrue(progressReported.await(2, TimeUnit.SECONDS))
        assertEquals(0.4f, runtime.snapshot(context).progress!!, 0f)
        allowFinish.countDown()
        val terminal = awaitTerminal(runtime)
        assertFalse(terminal.downloading)
        assertEquals(done, terminal.status)
    }

    @Test fun task_launch_failure_ends_and_allows_an_immediate_retry() {
        val runtime = runtime()
        var runs = 0

        runtime.start(
            context,
            startTask = { throw IllegalStateException("launch failed") },
        ) { _, _, _ -> runs += 1; done }

        val failedSnapshot = runtime.snapshot(context)
        assertFalse(failedSnapshot.downloading)
        assertEquals(failed, failedSnapshot.status)
        val completed = CountDownLatch(1)
        runtime.start(context) { _, _, _ -> runs += 1; completed.countDown(); done }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertFalse(awaitTerminal(runtime).downloading)
        assertEquals(1, runs)
    }

    @Test fun worker_failure_ends_and_allows_an_immediate_retry() {
        val runtime = runtime()
        val failedWorkerRan = CountDownLatch(1)
        runtime.start(context) { _, _, _ -> failedWorkerRan.countDown(); error("worker failed") }
        assertTrue(failedWorkerRan.await(2, TimeUnit.SECONDS))
        val failedSnapshot = awaitTerminal(runtime)
        assertFalse(failedSnapshot.downloading)
        assertEquals(failed, failedSnapshot.status)

        val completed = CountDownLatch(1)
        runtime.start(context) { _, _, _ -> completed.countDown(); done }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val retried = awaitTerminal(runtime)
        assertFalse(retried.downloading)
        assertEquals(done, retried.status)
    }

    @Test fun model_and_dictionary_cards_report_their_activated_files_and_refresh_after_changes() {
        val base = context.filesDir
        ModelDownload.purge(base)
        ModelDownload.purgeDict(base)
        try {
            ModelDownload.destFile(base).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(1_500_000))
            }
            val downloaded = ModelDownload.destFile(base).parentFile!!
            listOf(600_000, 700_000, 800_000).zip(ModelDownload.DICT_PACK_FILES).forEach { (length, name) ->
                File(downloaded, name).writeBytes(ByteArray(length))
            }
            File(downloaded, "unrelated.bin").writeBytes(ByteArray(5_000_000))
            assertEquals(
                LocalizedText.ResourceLong(R.string.gram_status_enabled, 2L),
                GramDownloadWork.snapshot(context).status,
            )
            assertEquals(
                LocalizedText.ResourceLong(R.string.dict_status_enabled, 2L),
                DictDownloadWork.snapshot(context).status,
            )

            ModelDownload.destFile(base).writeBytes(ByteArray(2_500_000))
            File(downloaded, ModelDownload.DICT_PACK_FILES.first()).writeBytes(ByteArray(1_600_000))
            assertEquals(
                LocalizedText.ResourceLong(R.string.gram_status_enabled, 3L),
                GramDownloadWork.snapshot(context).status,
            )
            assertEquals(
                LocalizedText.ResourceLong(R.string.dict_status_enabled, 3L),
                DictDownloadWork.snapshot(context).status,
            )
        } finally {
            ModelDownload.purge(base)
            ModelDownload.purgeDict(base)
            File(ModelDownload.destFile(base).parentFile, "unrelated.bin").delete()
        }
    }
}
