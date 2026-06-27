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
import com.aegis.ime.R
import com.aegis.ime.SettingsHotApply
import com.aegis.ime.dict.EngineAssets
import com.aegis.ime.dict.ModelDownload
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        resource = "test",
        isPresent = { false },
        doneStatus = { done },
        notDownloadedStatus = absent,
        failureStatus = failed,
    )

    private fun capture(holder: AtomicReference<Thread>): (Thread) -> Unit = { thread ->
        holder.set(thread)
        thread.start()
    }

    private fun awaitWorker(holder: AtomicReference<Thread>) {
        holder.get()?.let { thread ->
            thread.join(TimeUnit.SECONDS.toMillis(30))
            assertFalse("download worker did not reach a terminal state", thread.isAlive)
        }
    }

    private fun joinWorker(start: ((Thread) -> Unit) -> Unit) {
        val holder = AtomicReference<Thread>()
        start(capture(holder))
        awaitWorker(holder)
    }

    private fun startDictionary(asset: ModelDownload.DictionaryAsset?): DownloadCardSnapshot {
        joinWorker { startTask -> DictDownloadWork.start(context, asset, startTask = startTask) }
        return DictDownloadWork.snapshot(context)
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
        val holder = AtomicReference<Thread>()

        runtime.start(context, capture(holder)) { _, onProgress, _ ->
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
        awaitWorker(holder)
        val terminal = runtime.snapshot(context)
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
        joinWorker { startTask -> runtime.start(context, startTask) { _, _, _ -> runs += 1; done } }
        assertFalse(runtime.snapshot(context).downloading)
        assertEquals(1, runs)
    }

    @Test fun worker_failure_ends_and_allows_an_immediate_retry() {
        val runtime = runtime()
        joinWorker { startTask -> runtime.start(context, startTask) { _, _, _ -> error("worker failed") } }
        val failedSnapshot = runtime.snapshot(context)
        assertFalse(failedSnapshot.downloading)
        assertEquals(failed, failedSnapshot.status)

        joinWorker { startTask -> runtime.start(context, startTask) { _, _, _ -> done } }
        val retried = runtime.snapshot(context)
        assertFalse(retried.downloading)
        assertEquals(done, retried.status)
    }

    @Test fun idle_status_override_yields_when_the_persisted_presence_changes() {
        var present = false
        val runtime = DownloadRuntime(
            resource = "test",
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

    @Test fun model_snapshot_commit_failure_restores_the_previous_preferences() {
        val prefs = context.getSharedPreferences("model-validator-failure", 0)
        prefs.edit()
            .putString(ModelDownload.VALIDATOR_PREF, "installed-model")
            .putString(ModelDownload.GRAM_SHA256_PREF, "installed-sha")
            .putLong(ModelDownload.GRAM_SIZE_PREF, 123L)
            .commit()
        val failing = CommitFailingPreferences(prefs)

        assertFalse(
            GramDownloadWork.persistModelSnapshot(
                failing,
                ModelDownload.ModelSnapshot("candidate-model", "candidate-sha", 456L),
            ),
        )
        assertEquals("installed-model", prefs.getString(ModelDownload.VALIDATOR_PREF, null))
        assertEquals("installed-sha", prefs.getString(ModelDownload.GRAM_SHA256_PREF, null))
        assertEquals(123L, prefs.getLong(ModelDownload.GRAM_SIZE_PREF, -1L))
    }

    @Test fun model_snapshot_persists_validator_digest_and_size_together() {
        val prefs = context.getSharedPreferences("model-snapshot-success", 0)
        prefs.edit().clear().commit()
        val snapshot = ModelDownload.ModelSnapshot("remote-model", "a".repeat(64), 420_012_076L)

        assertTrue(GramDownloadWork.persistModelSnapshot(prefs, snapshot))
        assertEquals("remote-model", prefs.getString(ModelDownload.VALIDATOR_PREF, null))
        assertEquals("a".repeat(64), prefs.getString(ModelDownload.GRAM_SHA256_PREF, null))
        assertEquals(420_012_076L, prefs.getLong(ModelDownload.GRAM_SIZE_PREF, -1L))
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
            listOf(600_000, 700_000, 800_000, 2_048).zip(ModelDownload.DICT_PACK_FILES).forEach { (length, name) ->
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

    @Test fun a_failed_dictionary_transfer_names_only_the_cause_it_established() {
        val base = context.filesDir
        assertTrue(ModelDownload.purgeDict(base))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/unavailable") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            createContext("/short") { exchange ->
                val body = ByteArray(16)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
        }
        server.start()
        try {
            val port = server.address.port
            val serverError = startDictionary(dictionaryAsset("http://127.0.0.1:$port/unavailable"))
            val shortBody = startDictionary(dictionaryAsset("http://127.0.0.1:$port/short"))

            assertEquals(
                LocalizedText.ResourceNested(
                    R.string.download_status_failed_format,
                    R.string.download_cause_server,
                ),
                serverError.status,
            )
            assertEquals(
                "a complete body that is too small was not truncated",
                LocalizedText.Resource(R.string.dict_status_download_failed),
                shortBody.status,
            )
            assertNotEquals(serverError.status, shortBody.status)
            assertNotEquals(
                LocalizedText.Resource(R.string.dict_status_download_blocked),
                serverError.status,
            )
            assertNotEquals(
                LocalizedText.Resource(R.string.dict_status_metadata_failed),
                serverError.status,
            )
            assertFalse(ModelDownload.isDictDownloaded(base))
        } finally {
            server.stop(0)
            ModelDownload.purgeDict(base)
            DictDownloadWork.setIdleStatus(context, LocalizedText.Resource(R.string.dict_status_not_downloaded))
        }
    }

    @Test fun a_failed_dictionary_metadata_lookup_names_only_the_cause_it_established() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/unavailable") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            createContext("/garbage") { exchange ->
                val body = "<html>not the update document</html>".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
        }
        server.start()
        try {
            val port = server.address.port
            val serverError = ModelDownload.resolveDictionaryDownloadAsset {
                ModelDownload.fetchText("http://127.0.0.1:$port/unavailable")
            }.exceptionOrNull()!!
            val unreadable = ModelDownload.resolveDictionaryDownloadAsset {
                ModelDownload.fetchText("http://127.0.0.1:$port/garbage")
            }.exceptionOrNull()!!

            assertEquals(
                LocalizedText.ResourceNested(
                    R.string.dict_status_metadata_failed_format,
                    R.string.download_cause_server,
                ),
                metadataFailureStatus(serverError),
            )
            assertEquals(
                LocalizedText.ResourceNested(
                    R.string.dict_status_metadata_failed_format,
                    R.string.download_cause_offline,
                ),
                metadataFailureStatus(UnknownHostException("Unable to resolve host \"github.com\"")),
            )
            assertEquals(
                LocalizedText.ResourceNested(
                    R.string.dict_status_metadata_failed_format,
                    R.string.download_cause_timeout,
                ),
                metadataFailureStatus(SocketTimeoutException("timeout")),
            )
            assertEquals(
                "a response that cannot be read does not establish a cause",
                LocalizedText.Resource(R.string.dict_status_metadata_failed),
                metadataFailureStatus(unreadable),
            )
            assertNotEquals(
                metadataFailureStatus(serverError),
                metadataFailureStatus(unreadable),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test fun an_unusable_staging_path_is_not_reported_as_a_server_failure() {
        val base = context.filesDir
        assertTrue(ModelDownload.purgeDict(base))
        val body = ByteArray(4_096)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/pack") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
        }
        server.start()
        val staging = ModelDownload.dictPartFile(base)
        try {
            assertTrue(staging.mkdirs())
            File(staging, "occupied").writeBytes(ByteArray(8))

            val outcome = startDictionary(dictionaryAsset("http://127.0.0.1:${server.address.port}/pack"))

            assertEquals(
                "a local staging failure is not the server's fault",
                LocalizedText.Resource(R.string.dict_status_download_failed),
                outcome.status,
            )
            assertTrue(staging.isDirectory)
            assertFalse(ModelDownload.isDictDownloaded(base))
        } finally {
            server.stop(0)
            staging.deleteRecursively()
            ModelDownload.purgeDict(base)
            DictDownloadWork.setIdleStatus(context, LocalizedText.Resource(R.string.dict_status_not_downloaded))
        }
    }

    private fun dictionaryAsset(url: String) = ModelDownload.DictionaryAsset(
        url = url,
        assetName = "aegis_dict_pack_dict-latest.zip",
        sizeBytes = 16L,
        sha256 = "a".repeat(64),
        releaseTag = "dict-latest",
        releaseUrl = "https://github.com/lurixo/Aegis/releases/tag/dict-latest",
        prerelease = false,
        publishedAt = null,
    )

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
        val asset = ModelDownload.DictionaryAsset(
            url = "http://127.0.0.1:${server.address.port}/dict",
            assetName = "aegis_dict_pack_dict-latest.zip",
            sizeBytes = body.size.toLong(),
            sha256 = sha256,
            releaseTag = "dict-latest",
            releaseUrl = "https://github.com/lurixo/Aegis/releases/tag/dict-latest",
            prerelease = true,
            publishedAt = null,
        )
        server.start()
        try {
            assertTrue(ModelDownload.unmarkedDictionaryRecoveryRequired(base))
            assertNull(EngineAssets.downloadedOverride(downloaded, survivorName, minBytes = 0L))

            val blocked = startDictionary(asset)

            assertFalse(blocked.downloading)
            assertEquals(LocalizedText.Resource(R.string.dict_status_download_blocked), blocked.status)
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

            val installed = startDictionary(asset)

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

    @Test fun an_interrupted_dictionary_download_resumes_and_installs_the_verified_pack() {
        val base = context.filesDir
        assertTrue(ModelDownload.purgeDict(base))
        val downloaded = File(base, "downloaded")
        val replacements = ModelDownload.DICT_PACK_FILES.mapIndexed { index, name ->
            name to ByteArray(60_000).also { Random(index + 41).nextBytes(it) }
        }.toMap()
        val body = dictionaryArchive(replacements)
        val cut = body.size / 2
        val archive = File.createTempFile("archive", ".zip").apply { writeBytes(body) }
        val sha256 = ModelDownload.sha256Of(archive)
        archive.delete()
        val ranges = CopyOnWriteArrayList<String?>()
        val served = CopyOnWriteArrayList<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/dict") { exchange ->
                ranges += exchange.requestHeaders.getFirst("Range")
                exchange.responseHeaders.add("ETag", "dict-pack-1")
                if (ranges.size == 1) {
                    served += cut
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body, 0, cut) }
                } else {
                    served += body.size - cut
                    exchange.responseHeaders.add("Content-Range", "bytes $cut-${body.size - 1}/${body.size}")
                    exchange.sendResponseHeaders(206, (body.size - cut).toLong())
                    exchange.responseBody.use { it.write(body, cut, body.size - cut) }
                }
                exchange.close()
            }
        }
        server.start()
        val asset = ModelDownload.DictionaryAsset(
            url = "http://127.0.0.1:${server.address.port}/dict",
            assetName = "aegis_dict_pack_dict-latest.zip",
            sizeBytes = body.size.toLong(),
            sha256 = sha256,
            releaseTag = "dict-latest",
            releaseUrl = "https://github.com/lurixo/Aegis/releases/tag/dict-latest",
            prerelease = false,
            publishedAt = null,
        )
        try {
            val part = ModelDownload.dictPartFile(base)

            val interrupted = startDictionary(asset)

            assertEquals(
                LocalizedText.ResourceNested(
                    R.string.download_status_failed_format,
                    R.string.download_cause_incomplete,
                ),
                interrupted.status,
            )
            assertEquals(cut.toLong(), part.length())

            val betweenAttempts = DictDownloadWork.snapshot(context)
            assertFalse(betweenAttempts.present)
            assertTrue("reconciliation keeps the bound partial", part.exists())

            val resumed = startDictionary(asset)

            assertTrue(resumed.present)
            assertEquals(listOf<String?>(null, "bytes=$cut-"), ranges.toList())
            assertEquals(listOf(cut, body.size - cut), served.toList())
            assertTrue(ModelDownload.isDictDownloaded(base))
            assertEquals(sha256, ModelDownload.installedDictionaryFileSha(base))
            replacements.forEach { (name, bytes) ->
                assertArrayEquals(bytes, File(downloaded, name).readBytes())
            }
            assertFalse(part.exists())
            assertFalse(ModelDownload.dictZipFile(base).exists())
        } finally {
            server.stop(0)
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

    @Test fun a_pending_marker_that_cannot_be_written_is_not_reported_as_an_unfinished_install() {
        val base = context.filesDir
        ModelDownload.purgeDict(base)
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val marker = File(downloaded, "aegis_dict_pack.pending.sha256").apply { mkdirs() }
        File(marker, "occupant").writeText("x")
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/dict") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, 0L)
                exchange.close()
            }
        }
        server.start()
        try {
            assertFalse(ModelDownload.dictZipFile(base).exists())
            assertFalse(ModelDownload.unmarkedDictionaryRecoveryRequired(base))

            val failed = startDictionary(dictionaryAsset("http://127.0.0.1:${server.address.port}/dict"))

            assertEquals(LocalizedText.Resource(R.string.dict_status_download_failed), failed.status)
            assertNotEquals(LocalizedText.Resource(R.string.dict_status_download_blocked), failed.status)
            assertEquals(0, requests.get())
            assertFalse(ModelDownload.dictPartFile(base).exists())
        } finally {
            server.stop(0)
            marker.deleteRecursively()
            ModelDownload.purgeDict(base)
            DictDownloadWork.setIdleStatus(context, LocalizedText.Resource(R.string.dict_status_not_downloaded))
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
