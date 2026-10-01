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
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aegis.ime.R
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.neural.NeuralModelDownload
import com.aegis.ime.neural.NeuralReranker
import com.aegis.ime.neural.NeuralRuntime
import com.aegis.ime.neural.SentenceScorer
import com.aegis.ime.ui.theme.AegisTheme
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private fun manifestJson(name: String, sha256: String, bytes: Long, fileName: String = "model.gguf"): String =
    """
    {"schema":1,"id":"model","name":"$name",
     "file":{"name":"$fileName","url":"${NeuralModelDownload.RELEASE_URL}$fileName","bytes":$bytes,"sha256":"$sha256"},
     "rerank":{"alphaLetters":3,"alphaNine":2,"candidates":10}}
    """.trimIndent()

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NeuralDownloadCardTest {

    @get:Rule val compose = createAndroidComposeRule<DictSettingsActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private val published = NeuralModelDownload.parse(manifestJson("Qwen3-0.6B-Base Q8_0", "f".repeat(64), 639_446_784L))

    @Before @After
    fun clean() {
        AegisToast.reset()
        NeuralRuntime.active = null
        NeuralModelDownload.delete(context.filesDir, prefs)
        NeuralDownloadWork.setIdleStatus(context, LocalizedText.Resource(R.string.neural_status_not_downloaded))
    }

    private fun install(name: String = "Installed model"): NeuralModelDownload.Manifest {
        val installed = NeuralModelDownload.parse(manifestJson(name, "1".repeat(64), 2_048L))
        NeuralModelDownload.modelFile(context.filesDir, installed).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2_048))
        }
        prefs.edit().putString(NeuralModelDownload.MANIFEST_PREF, installed.json).commit()
        return installed
    }

    private fun show(
        resolve: () -> Result<NeuralModelDownload.Manifest> = { Result.success(published) },
        check: (Context) -> NeuralModelDownload.UpdateResult = { error("no update check expected") },
        downloader: (Context, NeuralModelDownload.Manifest) -> Unit = { _, _ -> error("no download expected") },
        cpuSupported: Boolean = true,
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AegisTheme {
                    NeuralDownloadCard(resolve = resolve, check = check, downloader = downloader, cpuSupported = cpuSupported)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var satisfied = condition()
        while (!satisfied && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
            satisfied = condition()
        }
        assertTrue(satisfied)
        compose.waitForIdle()
    }

    private fun shown(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun tapping_download_fetches_the_manifest_and_starts_the_download_right_away() {
        val started = CopyOnWriteArrayList<NeuralModelDownload.Manifest>()
        val fetches = CopyOnWriteArrayList<Int>()
        show(
            resolve = {
                fetches += 1
                Result.success(published)
            },
            downloader = { _, manifest -> started += manifest },
        )
        compose.onNodeWithText(context.getString(R.string.neural_status_not_downloaded)).assertExists()
        assertTrue("nothing is fetched until the user asks", fetches.isEmpty())

        compose.onNodeWithText(context.getString(R.string.download_button)).assertIsEnabled().performClick()
        awaitMain { started.isNotEmpty() }
        assertEquals(listOf(published), started.toList())
        assertEquals(1, fetches.size)
    }

    @Test fun an_unsupported_processor_is_named_and_offers_no_download() {
        show(cpuSupported = false)
        compose.onNodeWithText(context.getString(R.string.neural_status_unsupported)).assertExists()
        compose.onNodeWithText(context.getString(R.string.download_button)).assertIsNotEnabled()
    }

    @Test fun an_unsupported_processor_offers_no_update_check_but_still_deletes() {
        install()
        show(cpuSupported = false)
        compose.onNodeWithText(context.getString(R.string.neural_status_unsupported)).assertExists()
        compose.onNodeWithText(context.getString(R.string.check_model_update_button)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.delete_button)).assertIsEnabled()
    }

    @Test fun a_manifest_that_cannot_be_fetched_names_its_cause_and_starts_nothing() {
        show(resolve = { Result.failure(UnknownHostException("github.com")) })

        compose.onNodeWithText(context.getString(R.string.download_button)).performClick()
        val failed = context.getString(
            R.string.dict_status_metadata_failed_format,
            context.getString(R.string.download_cause_offline),
        )
        awaitMain { shown(failed) }
        compose.onNodeWithText(context.getString(R.string.download_button)).assertIsEnabled()
    }

    @Test fun an_update_check_that_finds_a_newer_model_says_so_and_downloads_it() {
        install()
        val started = AtomicReference<NeuralModelDownload.Manifest>()
        show(
            check = { NeuralModelDownload.UpdateResult(ModelDownload.UpdateCheck.UPDATE, published) },
            downloader = { _, manifest -> started.set(manifest) },
        )
        compose.onNodeWithText(context.getString(R.string.download_button)).assertIsNotEnabled()

        compose.onNodeWithText(context.getString(R.string.check_model_update_button)).assertIsEnabled().performClick()
        awaitMain { started.get() != null }

        assertEquals(published, started.get())
        assertEquals(context.getString(R.string.download_toast_update_found), AegisToast.textForTest())
    }

    @Test fun an_update_check_that_finds_nothing_or_fails_only_says_so() {
        install()
        var state = ModelDownload.UpdateCheck.UP_TO_DATE
        show(check = { NeuralModelDownload.UpdateResult(state) })

        compose.onNodeWithText(context.getString(R.string.check_model_update_button)).performClick()
        awaitMain { AegisToast.shownCountForTest() == 1 }
        assertEquals(context.getString(R.string.download_toast_up_to_date), AegisToast.textForTest())

        state = ModelDownload.UpdateCheck.OFFLINE
        compose.onNodeWithText(context.getString(R.string.check_model_update_button)).performClick()
        awaitMain { AegisToast.shownCountForTest() == 2 }
        assertEquals(context.getString(R.string.download_toast_update_offline), AegisToast.textForTest())
    }

    @Test fun deleting_asks_first_and_only_the_confirmation_removes_the_model() {
        val installed = install()
        show()

        compose.onNodeWithText(context.getString(R.string.delete_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.neural_delete_dialog_body)).assertExists()
        compose.onNodeWithTag("neural_delete_cancel").performClick()
        compose.waitForIdle()
        assertEquals(installed, NeuralDownloadWork.installedModel(context))

        compose.onNodeWithText(context.getString(R.string.delete_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("neural_delete_confirm").performClick()
        compose.waitForIdle()
        assertNull(NeuralDownloadWork.installedModel(context))
        assertFalse(NeuralModelDownload.modelFile(context.filesDir, installed).exists())
        assertFalse(prefs.contains(NeuralModelDownload.MANIFEST_PREF))
        compose.onNodeWithText(context.getString(R.string.neural_status_deleted)).assertExists()
    }

    @Test fun an_installed_model_shows_its_name_and_what_the_keyboard_did_with_it() {
        install(name = "Qwen3 test")
        show()
        compose.onNodeWithText(context.getString(R.string.neural_status_enabled, 0L)).assertExists()
        compose.onNodeWithText(context.getString(R.string.neural_card_model, "Qwen3 test")).assertExists()
        compose.onNodeWithText(context.getString(R.string.neural_state_idle)).assertExists()

        val failing = NeuralReranker(
            NeuralModelDownload.parse(manifestJson("Qwen3 test", "1".repeat(64), 2_048L)).spec,
            { error("bad model") },
            Executor { it.run() },
            Executor { it.run() },
            { _, _ -> },
        ).apply { start() }
        NeuralRuntime.active = failing
        compose.mainClock.advanceTimeBy(1_100L)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.neural_state_failed, "bad model")).assertExists()
    }

    @Test fun usage_lines_show_only_what_the_keyboard_has_done() {
        val installed = install(name = "Qwen3 test")
        show()

        val timedOut = NeuralReranker(installed.spec, { error("not loaded") }, Executor {}, Executor { it.run() }, { _, expire -> expire() })
        timedOut.submit("", nine = false, waitMillis = 300L, paths = { emptyList() }) {}
        NeuralRuntime.active = timedOut
        compose.mainClock.advanceTimeBy(1_100L)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.neural_card_timeouts, 1)).assertExists()
        compose.onNodeWithText(context.getString(R.string.neural_card_runs, 0, 0)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.neural_card_timing, 0L, 0L, 0L)).assertDoesNotExist()

        val scorer = object : SentenceScorer {
            override fun score(context: String, candidates: List<String>) = DoubleArray(candidates.size) { -it.toDouble() }
            override fun cancel() {}
            override fun close() {}
        }
        val ran = NeuralReranker(installed.spec, { scorer }, Executor { it.run() }, Executor { it.run() }, { _, _ -> })
            .apply { start() }
        ran.submit("", nine = false, waitMillis = 300L, paths = { listOf("甲" to -1.0, "乙" to -2.0) }) {}
        NeuralRuntime.active = ran
        compose.mainClock.advanceTimeBy(1_100L)
        compose.waitForIdle()
        val snap = ran.snapshot()
        assertEquals(1, snap.runs)
        compose.onNodeWithText(context.getString(R.string.neural_card_runs, 1, snap.changed)).assertExists()
        compose.onNodeWithText(
            context.getString(R.string.neural_card_timing, snap.lastMillis, snap.meanMillis, snap.maxMillis),
        ).assertExists()
        compose.onNodeWithText(context.getString(R.string.neural_card_timeouts, 0)).assertDoesNotExist()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeuralDownloadWorkTest {

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)

    @Before @After
    fun clean() {
        NeuralModelDownload.delete(context.filesDir, prefs)
        NeuralDownloadWork.setIdleStatus(context, LocalizedText.Resource(R.string.neural_status_not_downloaded))
    }

    private fun startAndJoin(manifest: NeuralModelDownload.Manifest, url: String): DownloadCardSnapshot {
        val holder = AtomicReference<Thread>()
        NeuralDownloadWork.start(context, manifest, startTask = { thread ->
            holder.set(thread)
            thread.start()
        }, url = url)
        holder.get().join(TimeUnit.SECONDS.toMillis(30))
        assertFalse("download worker did not finish", holder.get().isAlive)
        shadowOf(Looper.getMainLooper()).idle()
        return NeuralDownloadWork.snapshot(context)
    }

    private fun serving(body: ByteArray, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/model.gguf") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            start()
        }
        try {
            block("http://127.0.0.1:${server.address.port}/model.gguf")
        } finally {
            server.stop(0)
        }
    }

    @Test fun a_confirmed_download_installs_the_model_and_reports_it_enabled() {
        val body = ByteArray(1_500_000) { (it % 251).toByte() }
        val manifest = NeuralModelDownload.parse(manifestJson("Qwen3", sha256Hex(body), body.size.toLong()))
        serving(body) { url ->
            val snapshot = startAndJoin(manifest, url)

            assertTrue(snapshot.present)
            assertFalse(snapshot.downloading)
            assertEquals(LocalizedText.ResourceLong(R.string.neural_status_enabled, 2L), snapshot.status)
            assertEquals(manifest, NeuralDownloadWork.installedModel(context))
            assertArrayEquals(body, NeuralModelDownload.modelFile(context.filesDir, manifest).readBytes())
        }
    }

    @Test fun a_download_that_fails_its_digest_reports_the_cause_and_installs_nothing() {
        val body = ByteArray(8_192) { (it % 241).toByte() }
        val manifest = NeuralModelDownload.parse(manifestJson("Qwen3", "0".repeat(64), body.size.toLong()))
        serving(body) { url ->
            val snapshot = startAndJoin(manifest, url)

            assertFalse(snapshot.present)
            assertEquals(
                LocalizedText.ResourceNested(R.string.download_status_failed_format, R.string.download_cause_corrupt),
                snapshot.status,
            )
            assertNull(NeuralDownloadWork.installedModel(context))
        }
    }
}
