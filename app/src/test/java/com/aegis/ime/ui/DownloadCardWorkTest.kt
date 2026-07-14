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

import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aegis.ime.R
import com.aegis.ime.SettingsHotApply
import com.aegis.ime.dict.EngineAssets
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.ui.theme.AegisTheme
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

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

    private fun awaitDictionaryTerminal(): DownloadCardSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var snapshot = DictDownloadWork.snapshot(context)
        while (snapshot.downloading && System.nanoTime() < deadline) {
            Thread.yield()
            snapshot = DictDownloadWork.snapshot(context)
        }
        return snapshot
    }

    private fun dictionaryArchive(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
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

    @Test fun idle_status_override_yields_when_the_persisted_presence_changes() {
        var present = false
        val runtime = DownloadRuntime(
            isPresent = { present },
            doneStatus = { done },
            notDownloadedStatus = absent,
            failureStatus = failed,
        )
        runtime.setIdleStatus(context, failed)
        assertEquals(failed, runtime.snapshot(context).status)

        present = true
        val reconciled = runtime.snapshot(context)
        assertTrue(reconciled.present)
        assertEquals(done, reconciled.status)

        present = false
        assertEquals(absent, runtime.snapshot(context).status)
    }

    @Test fun model_validator_commit_failure_restores_the_previous_preference() {
        val prefs = context.getSharedPreferences("model-validator-failure", 0)
        prefs.edit().putString(ModelDownload.VALIDATOR_PREF, "installed-model").commit()
        val failing = CommitFailingPreferences(prefs)

        assertFalse(GramDownloadWork.persistModelValidator(failing, "candidate-model"))
        assertEquals("installed-model", prefs.getString(ModelDownload.VALIDATOR_PREF, null))
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

    @Test fun dictionary_recovery_blocks_marked_download_until_live_member_cleanup_succeeds() {
        val base = context.filesDir
        assertTrue(ModelDownload.purgeDict(base))
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val survivorName = ModelDownload.DICT_PACK_FILES.first()
        val survivor = File(downloaded, survivorName).apply { mkdirs() }
        val residue = File(survivor, "residue").apply { writeText("x") }
        ModelDownload.DICT_PACK_FILES.drop(1).forEachIndexed { index, name ->
            File(downloaded, name).writeBytes(ByteArray(2_048) { (index + 1).toByte() })
        }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText("invalid")
        val replacements = ModelDownload.DICT_PACK_FILES.mapIndexed { index, name ->
            name to ByteArray(2_048).also { Random(index + 1).nextBytes(it) }
        }.toMap()
        val body = dictionaryArchive(replacements)
        val zip = ModelDownload.dictZipFile(base).apply { writeBytes(body) }
        val sha256 = ModelDownload.sha256Of(zip)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/dict") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
        }
        val asset = ModelDownload.FALLBACK_DICT_ASSET.copy(
            url = "http://127.0.0.1:${server.address.port}/dict",
            sizeBytes = body.size.toLong(),
            sha256 = sha256,
        )
        server.start()
        try {
            assertTrue(ModelDownload.unmarkedDictionaryRecoveryRequired(base))
            assertNull(EngineAssets.downloadedOverride(downloaded, survivorName, minBytes = 0L))

            DictDownloadWork.start(context, asset)
            val blocked = awaitDictionaryTerminal()

            assertFalse(blocked.downloading)
            assertEquals(LocalizedText.Resource(R.string.dict_status_download_failed), blocked.status)
            assertEquals(0, requests.get())
            assertTrue(survivor.exists())
            ModelDownload.DICT_PACK_FILES.drop(1).forEach { name ->
                assertFalse(File(downloaded, name).exists())
            }
            assertFalse(File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).exists())
            assertTrue(zip.exists())
            assertTrue(ModelDownload.unmarkedDictionaryRecoveryRequired(base))
            assertFalse(ModelDownload.dictPartFile(base).exists())
            assertNull(EngineAssets.downloadedOverride(downloaded, survivorName, minBytes = 0L))
            ModelDownload.reconcileInterruptedDownloads(base)
            assertTrue(survivor.exists())
            assertTrue(zip.exists())
            assertTrue(ModelDownload.unmarkedDictionaryRecoveryRequired(base))

            assertTrue(residue.delete())
            ModelDownload.recoverInterruptedDictionaryInstall(base)

            ModelDownload.DICT_PACK_FILES.forEach { name ->
                assertFalse(File(downloaded, name).exists())
            }
            assertFalse(File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).exists())
            assertFalse(zip.exists())
            assertFalse(ModelDownload.unmarkedDictionaryRecoveryRequired(base))

            DictDownloadWork.start(context, asset)
            val installed = awaitDictionaryTerminal()

            assertFalse(installed.downloading)
            assertTrue(installed.present)
            assertEquals(1, requests.get())
            assertTrue(ModelDownload.isDictDownloaded(base))
            assertEquals(sha256, ModelDownload.installedDictionaryFileSha(base))
            replacements.forEach { (name, bytes) ->
                assertArrayEquals(bytes, File(downloaded, name).readBytes())
                assertEquals(
                    File(downloaded, name).absolutePath,
                    EngineAssets.downloadedOverride(downloaded, name)?.absolutePath,
                )
            }
            assertFalse(zip.exists())
        } finally {
            server.stop(0)
            ModelDownload.DICT_PACK_FILES.forEach { name -> File(downloaded, name).deleteRecursively() }
            File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).deleteRecursively()
            ModelDownload.purgeDict(base)
            DictDownloadWork.setIdleStatus(context, LocalizedText.Resource(R.string.dict_status_not_downloaded))
            context.getSharedPreferences("aegis", 0).edit()
                .remove(ModelDownload.DICT_VALIDATOR_PREF)
                .remove(ModelDownload.DICT_SHA256_PREF)
                .remove(ModelDownload.DICT_ASSET_NAME_PREF)
                .remove(ModelDownload.DICT_ASSET_URL_PREF)
                .remove(ModelDownload.DICT_RELEASE_TAG_PREF)
                .remove(ModelDownload.DICT_RELEASE_PUBLISHED_PREF)
                .remove(SettingsHotApply.ENGINE_PACK_TOUCH_PREF)
                .commit()
        }
    }

    private class CommitFailingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    editor.apply()
                    return false
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ResourceUpdateCardTest {

    @get:Rule val compose = createAndroidComposeRule<DictSettingsActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", android.content.Context.MODE_PRIVATE)

    @After
    fun clean() {
        ShadowToast.reset()
        prefs.edit()
            .remove(ModelDownload.VALIDATOR_PREF)
            .remove(ModelDownload.DICT_SHA256_PREF)
            .remove(ModelDownload.DICT_RELEASE_PUBLISHED_PREF)
            .commit()
        ModelDownload.purge(context.filesDir)
        ModelDownload.purgeDict(context.filesDir)
        GramDownloadWork.setIdleStatus(context, LocalizedText.Resource(R.string.gram_status_not_downloaded))
        DictDownloadWork.setIdleStatus(context, LocalizedText.Resource(R.string.dict_status_not_downloaded))
    }

    @Test
    fun grammarCardCompletesRedirectedCheckWithMalformedLocalState() {
        val requests = CopyOnWriteArrayList<String>()
        val downloaded = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/model") { exchange ->
            requests += exchange.requestMethod
            exchange.responseHeaders.add("Location", "$base/asset")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/asset") { exchange ->
            requests += exchange.requestMethod
            exchange.responseHeaders.add("ETag", "remote-model")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            ModelDownload.destFile(context.filesDir).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(2_048))
            }
            prefs.edit().putInt(ModelDownload.VALIDATOR_PREF, 7).commit()
            ShadowToast.reset()
            compose.runOnUiThread {
                compose.activity.setContent {
                    AegisTheme {
                        GramDownloadCard(
                            probe = { ModelDownload.remoteValidatorProbe("$base/model") },
                            downloader = { _, url -> downloaded.set(url) },
                        )
                    }
                }
            }
            compose.waitForIdle()

            compose.onNodeWithText(context.getString(R.string.check_model_update_button)).assertIsEnabled().performClick()
            awaitMain { downloaded.get() != null }

            assertEquals(listOf("HEAD", "HEAD"), requests.toList())
            assertEquals(ModelDownload.GRAM_URL, downloaded.get())
            assertEquals(context.getString(R.string.download_toast_update_found), ShadowToast.getTextOfLatestToast())
            compose.onNodeWithText(context.getString(R.string.check_model_update_button)).assertIsEnabled()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unidentifiedGrammarFileOffersTheUpdateWithoutAdoptingTheHeadValidator() {
        val downloads = AtomicInteger()
        ModelDownload.destFile(context.filesDir).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2_048))
        }
        prefs.edit().remove(ModelDownload.VALIDATOR_PREF).commit()
        ShadowToast.reset()
        compose.runOnUiThread {
            compose.activity.setContent {
                AegisTheme {
                    GramDownloadCard(
                        probe = { ModelDownload.ValidatorProbe.Reached("current-model") },
                        downloader = { _, _ -> downloads.incrementAndGet() },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.check_model_update_button)).performClick()
        awaitMain { downloads.get() == 1 }

        assertFalse(prefs.contains(ModelDownload.VALIDATOR_PREF))
        assertTrue(ModelDownload.isDownloaded(context.filesDir))
        assertEquals(context.getString(R.string.download_toast_update_found), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun dictionaryCardUsesRedirectedManifestAndHandsOffItsAsset() {
        val requests = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        val downloaded = AtomicReference<ModelDownload.DictionaryAsset>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/metadata") { exchange ->
            requests += requestOf(exchange)
            exchange.responseHeaders.add("Location", "$base/manifest")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/manifest") { exchange ->
            requests += requestOf(exchange)
            val body = dictionaryManifest().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val dir = ModelDownload.destFile(context.filesDir).parentFile!!.apply { mkdirs() }
            ModelDownload.DICT_PACK_FILES.forEach { File(dir, it).writeBytes(ByteArray(2_048)) }
            prefs.edit()
                .putInt(ModelDownload.DICT_SHA256_PREF, 7)
                .putInt(ModelDownload.DICT_RELEASE_PUBLISHED_PREF, 7)
                .commit()
            ShadowToast.reset()
            compose.runOnUiThread {
                compose.activity.setContent {
                    AegisTheme {
                        DictDownloadCard(
                            check = { ModelDownload.checkDictionaryUpdate("$base/metadata", it) },
                            downloader = { _, asset -> downloaded.set(asset) },
                        )
                    }
                }
            }
            compose.waitForIdle()

            compose.onNodeWithText(context.getString(R.string.check_dict_update_button)).assertIsEnabled().performClick()
            awaitMain { downloaded.get() != null }

            assertEquals(listOf("GET", "GET"), requests.map { it.first })
            assertTrue(requests.all { it.second == "application/vnd.github+json" })
            assertTrue(requests.all { it.third == "Aegis-resource-updater" })
            assertEquals(DICT_ASSET_URL, downloaded.get().url)
            assertEquals(DICT_SHA, downloaded.get().sha256)
            assertNull(downloaded.get().publishedAt)
            assertEquals(context.getString(R.string.download_toast_update_found), ShadowToast.getTextOfLatestToast())
            compose.onNodeWithText(context.getString(R.string.check_dict_update_button)).assertIsEnabled()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unknownDictionaryFilesRequireTheTrustedManifestUpdate() {
        val checked = AtomicReference<ModelDownload.DictionaryInstallMetadata?>()
        val downloaded = AtomicReference<ModelDownload.DictionaryAsset?>()
        val dir = ModelDownload.destFile(context.filesDir).parentFile!!.apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEach { File(dir, it).writeBytes(ByteArray(2_048)) }
        prefs.edit().remove(ModelDownload.DICT_SHA256_PREF).commit()
        ShadowToast.reset()
        compose.runOnUiThread {
            compose.activity.setContent {
                AegisTheme {
                    DictDownloadCard(
                        check = {
                            checked.set(it)
                            ModelDownload.dictionaryUpdateFromFetch({ dictionaryManifest() }, it)
                        },
                        downloader = { _, asset -> downloaded.set(asset) },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.check_dict_update_button)).performClick()
        awaitMain { checked.get() != null && downloaded.get() != null }

        assertNull(checked.get()!!.sha256)
        assertFalse(prefs.contains(ModelDownload.DICT_SHA256_PREF))
        assertEquals(DICT_SHA, downloaded.get()!!.sha256)
        assertTrue(ModelDownload.isDictDownloaded(context.filesDir))
        assertEquals(context.getString(R.string.download_toast_update_found), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun unknownDictionaryVersionClearsStaleReleaseMetadata() {
        val checked = AtomicReference<ModelDownload.DictionaryInstallMetadata?>()
        val dir = ModelDownload.destFile(context.filesDir).parentFile!!.apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEach { File(dir, it).writeBytes(ByteArray(2_048)) }
        File(dir, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText("unknown")
        prefs.edit()
            .remove(ModelDownload.DICT_SHA256_PREF)
            .putString(ModelDownload.DICT_ASSET_NAME_PREF, "old.zip")
            .putString(ModelDownload.DICT_ASSET_URL_PREF, "https://example.invalid/old.zip")
            .putString(ModelDownload.DICT_RELEASE_TAG_PREF, "old")
            .putString(ModelDownload.DICT_RELEASE_PUBLISHED_PREF, "2026-01-01T00:00:00Z")
            .commit()
        ShadowToast.reset()
        compose.runOnUiThread {
            compose.activity.setContent {
                AegisTheme {
                    DictDownloadCard(
                        check = {
                            checked.set(it)
                            ModelDownload.DictionaryUpdateCheck(ModelDownload.UpdateCheck.UP_TO_DATE)
                        },
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.check_dict_update_button)).performClick()
        awaitMain { checked.get() != null && ShadowToast.shownToastCount() == 1 }

        assertNull(checked.get()!!.sha256)
        assertNull(checked.get()!!.publishedAt)
        assertFalse(prefs.contains(ModelDownload.DICT_SHA256_PREF))
        assertFalse(prefs.contains(ModelDownload.DICT_ASSET_NAME_PREF))
        assertFalse(prefs.contains(ModelDownload.DICT_RELEASE_PUBLISHED_PREF))
        assertEquals(context.getString(R.string.download_toast_up_to_date), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun timeoutOutcomeCleansUpAndAllowsImmediateRetry() {
        val probes = AtomicInteger()
        val downloads = AtomicInteger()
        ModelDownload.destFile(context.filesDir).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2_048))
        }
        prefs.edit().putString(ModelDownload.VALIDATOR_PREF, "local-model").commit()
        ShadowToast.reset()
        compose.runOnUiThread {
            compose.activity.setContent {
                AegisTheme {
                    GramDownloadCard(
                        probe = {
                            probes.incrementAndGet()
                            ModelDownload.ValidatorProbe.Failed(ModelDownload.CheckFailure.TIMEOUT)
                        },
                        downloader = { _, _ -> downloads.incrementAndGet() },
                    )
                }
            }
        }
        compose.waitForIdle()

        val button = compose.onNodeWithText(context.getString(R.string.check_model_update_button))
        button.assertIsEnabled().performClick()
        awaitMain { probes.get() == 1 && ShadowToast.shownToastCount() == 1 }
        compose.onNodeWithText(context.getString(R.string.download_toast_update_timeout)).assertExists()
        button.assertIsEnabled().performClick()
        awaitMain { probes.get() == 2 && ShadowToast.shownToastCount() == 2 }

        assertEquals(0, downloads.get())
        assertEquals(context.getString(R.string.download_toast_update_timeout), ShadowToast.getTextOfLatestToast())
        button.assertIsEnabled()
    }

    @Test
    fun grammarWorkerExceptionCleansUpAndAllowsImmediateRetry() {
        val calls = AtomicInteger()
        ModelDownload.destFile(context.filesDir).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2_048))
        }
        ShadowToast.reset()
        compose.runOnUiThread {
            compose.activity.setContent {
                AegisTheme {
                    GramDownloadCard(
                        probe = {
                            calls.incrementAndGet()
                            error("worker failed")
                        },
                        downloader = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()

        val button = compose.onNodeWithText(context.getString(R.string.check_model_update_button))
        button.assertIsEnabled().performClick()
        awaitMain { calls.get() == 1 && ShadowToast.shownToastCount() == 1 }
        compose.onNodeWithText(context.getString(R.string.download_toast_update_parse_error)).assertExists()
        assertEquals(context.getString(R.string.download_toast_update_parse_error), ShadowToast.getTextOfLatestToast())
        button.assertIsEnabled().performClick()
        awaitMain { calls.get() == 2 && ShadowToast.shownToastCount() == 2 }

        assertEquals(context.getString(R.string.download_toast_update_parse_error), ShadowToast.getTextOfLatestToast())
        button.assertIsEnabled()
    }

    @Test
    fun dictionaryWorkerExceptionCleansUpAndAllowsImmediateRetry() {
        val calls = AtomicInteger()
        val dir = ModelDownload.destFile(context.filesDir).parentFile!!.apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEach { File(dir, it).writeBytes(ByteArray(2_048)) }
        ShadowToast.reset()
        compose.runOnUiThread {
            compose.activity.setContent {
                AegisTheme {
                    DictDownloadCard(
                        check = {
                            calls.incrementAndGet()
                            error("worker failed")
                        },
                        downloader = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()

        val button = compose.onNodeWithText(context.getString(R.string.check_dict_update_button))
        button.assertIsEnabled().performClick()
        awaitMain { calls.get() == 1 && ShadowToast.shownToastCount() == 1 }
        compose.onNodeWithText(context.getString(R.string.download_toast_update_parse_error)).assertExists()
        assertEquals(context.getString(R.string.download_toast_update_parse_error), ShadowToast.getTextOfLatestToast())
        button.assertIsEnabled().performClick()
        awaitMain { calls.get() == 2 && ShadowToast.shownToastCount() == 2 }

        assertEquals(context.getString(R.string.download_toast_update_parse_error), ShadowToast.getTextOfLatestToast())
        button.assertIsEnabled()
    }

    private fun requestOf(exchange: HttpExchange): Triple<String, String?, String?> =
        Triple(
            exchange.requestMethod,
            exchange.requestHeaders.getFirst("Accept"),
            exchange.requestHeaders.getFirst("User-Agent"),
        )

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

    private fun dictionaryManifest(): String =
        """
        {
          "schema_version": 1,
          "kind": "dictionary_update",
          "asset": {
            "name": "aegis_dict_pack_dict-latest.zip",
            "url": "$DICT_ASSET_URL",
            "release_tag": "dict-latest",
            "release_url": "https://github.com/lurixo/Aegis/releases/tag/dict-latest",
            "prerelease": false,
            "published_at": null,
            "sha256": "$DICT_SHA",
            "size_bytes": 98236647
          },
          "source": {
            "repo": "https://github.com/amzxyz/rime-wanxiang",
            "ref_type": "tag",
            "tag": "v16.1.0",
            "branch": null,
            "commit": "6c792a2e68c8382f9c63e8bed74c5cf247f1b1a9"
          }
        }
        """.trimIndent()

    private companion object {
        const val DICT_ASSET_URL =
            "https://github.com/lurixo/Aegis/releases/download/dict-latest/aegis_dict_pack_dict-latest.zip"
        const val DICT_SHA = "53b6d4c98f4431777dd0c7cbbc397d0738631c5697df2f3a4d401d316c411182"
    }
}
