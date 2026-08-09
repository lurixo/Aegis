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

package com.aegis.ime.dict

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadTest {

    private fun tempFilesDir(): File =
        File.createTempFile("filesdir", "").apply { delete(); mkdirs() }

    @Test
    fun purgeRemovesModelAndPartAndIsIdempotent() {
        val base = tempFilesDir()
        val gram = ModelDownload.destFile(base)
        val part = ModelDownload.partFile(base)
        val sidecar = File(part.parentFile, "${part.name}.meta")
        gram.parentFile?.mkdirs()
        gram.writeText("model")
        part.writeText("leftover")
        sidecar.writeText("identity")

        assertTrue("first purge removes leftovers", ModelDownload.purge(base))
        assertFalse(gram.exists())
        assertFalse("interrupted .part is cleaned too", part.exists())
        assertFalse("the partial identity sidecar is cleaned too", sidecar.exists())
        assertTrue("second purge still confirms absence", ModelDownload.purge(base))

        base.deleteRecursively()
    }

    @Test
    fun purgeOnlyReportsSuccessWhenEveryManagedPathIsAbsent() {
        val base = tempFilesDir()
        val model = ModelDownload.destFile(base).apply {
            mkdirs()
            File(this, "retained").writeText("model")
        }
        val downloaded = File(base, "downloaded")
        val dict = File(downloaded, ModelDownload.DICT_PACK_FILES.first()).apply {
            mkdirs()
            File(this, "retained").writeText("dictionary")
        }

        assertFalse(ModelDownload.purge(base))
        assertFalse(ModelDownload.purgeDict(base))
        assertTrue(model.exists())
        assertTrue(dict.exists())

        base.deleteRecursively()
    }

    @Test
    fun deletingTheDictionaryAlsoRemovesTheBundledEraCopiesOutsideTheManagedDirectory() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val bundledEraNames = ModelDownload.DICT_PACK_FILES + listOf("aegis_en.bin", "aegis_fuzzy.bin")
        ModelDownload.DICT_PACK_FILES.forEach { name ->
            File(downloaded, name).writeBytes(ByteArray(2_048) { 1 })
        }
        bundledEraNames.forEach { name ->
            File(base, name).writeBytes(ByteArray(4_096) { 2 })
            File(base, "$name.part").writeBytes(ByteArray(512))
        }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText("d".repeat(64))
        val grammar = File(base, ModelDownload.GRAM_NAME).apply { writeBytes(ByteArray(2_048) { 3 }) }

        assertTrue(ModelDownload.isDictDownloaded(base))
        assertTrue(ModelDownload.purgeDict(base))

        assertFalse(ModelDownload.isDictDownloaded(base))
        bundledEraNames.forEach { name ->
            assertFalse(File(base, name).exists())
            assertFalse(File(base, "$name.part").exists())
        }
        assertTrue("the bundled grammar model is not dictionary state", grammar.exists())
        assertEquals(2_048L, grammar.length())

        base.deleteRecursively()
    }

    @Test
    fun purgeOnlyConfirmsSuccessOnceTheBundledEraCopiesAreGone() {
        val base = tempFilesDir()
        val blocked = File(base, ModelDownload.DICT_PACK_FILES.first()).apply {
            mkdirs()
            File(this, "retained").writeText("cache")
        }

        assertFalse(ModelDownload.purgeDict(base))
        assertTrue(blocked.exists())
        assertTrue(blocked.deleteRecursively())

        val debugEra = File(base, "aegis_en.bin").apply {
            mkdirs()
            File(this, "retained").writeText("cache")
        }

        assertFalse(ModelDownload.purgeDict(base))
        assertTrue(debugEra.exists())
        assertTrue(debugEra.deleteRecursively())
        assertTrue(ModelDownload.purgeDict(base))

        base.deleteRecursively()
    }

    @Test
    fun reconciliationRemovesTheBundledEraCopiesOnAnUpgradedInstall() {
        val base = tempFilesDir()
        val bundledEraNames = ModelDownload.DICT_PACK_FILES + listOf("aegis_en.bin", "aegis_fuzzy.bin")
        bundledEraNames.forEach { name ->
            File(base, name).writeBytes(ByteArray(4_096) { 2 })
        }

        ModelDownload.reconcileInterruptedDownloads(base)

        bundledEraNames.forEach { name -> assertFalse(File(base, name).exists()) }
        assertFalse(ModelDownload.isDictDownloaded(base))
        assertEquals(0L, ModelDownload.installedDictionaryBytes(base))

        base.deleteRecursively()
    }

    @Test
    fun abandonedTransactionsAreReconciledWithoutRemovingInstalledResources() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val gram = ModelDownload.destFile(base).apply { writeBytes(ByteArray(2_048) { 1 }) }
        val installed = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            File(downloaded, name).apply { writeBytes(ByteArray(2_048) { name.length.toByte() }) }
        }
        ModelDownload.partFile(base).writeBytes(ByteArray(3_000))
        ModelDownload.dictPartFile(base).writeBytes(ByteArray(5_000))
        File(downloaded, "aegis_dict_pack_debug13.zip").writeBytes(ByteArray(6_000))
        File(downloaded, "aegis_dict_pack_debug13.zip.part").writeBytes(ByteArray(7_000))
        ModelDownload.DICT_PACK_FILES.forEach { name ->
            File(downloaded, "$name.part").writeBytes(ByteArray(1_500))
        }
        File(downloaded, "dict-install").apply { mkdirs(); File(this, "payload").writeBytes(ByteArray(1_500)) }

        assertFalse(ModelDownload.installInProgress(base))
        assertTrue(ModelDownload.isDownloaded(base))
        assertTrue(ModelDownload.isDictDownloaded(base))
        assertEquals(2_048L, gram.length())
        installed.forEach { (name, file) ->
            assertEquals(2_048L, file.length())
            assertFalse(File(downloaded, "$name.part").exists())
            assertFalse(File(downloaded, "$name.backup").exists())
        }
        assertFalse(ModelDownload.partFile(base).exists())
        assertFalse(ModelDownload.dictZipFile(base).exists())
        assertFalse(ModelDownload.dictPartFile(base).exists())
        assertFalse(File(downloaded, "aegis_dict_pack_debug13.zip").exists())
        assertFalse(File(downloaded, "aegis_dict_pack_debug13.zip.part").exists())
        assertFalse(File(downloaded, "dict-install").exists())

        base.deleteRecursively()
    }

    @Test
    fun sharedDownloadFailureCleansStagingAndPreservesBothTargets() {
        val base = tempFilesDir()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val targets = listOf(ModelDownload.destFile(base), ModelDownload.dictZipFile(base))
            targets.forEachIndexed { index, target ->
                val old = ByteArray(2_048) { (index + 1).toByte() }
                target.parentFile?.mkdirs()
                target.writeBytes(old)
                File(target.parentFile, "${target.name}.part").writeBytes(ByteArray(8_192) { 9 })

                val result = ModelDownload.download(
                    "http://127.0.0.1:${server.address.port}/asset",
                    target,
                ) { _, _ -> }

                assertFalse(result.ok)
                assertEquals(ModelDownload.TransferFailure.SERVER, result.failure)
                assertEquals(503, (result.error as ModelDownload.HttpStatusException).code)
                assertArrayEquals(old, target.readBytes())
                assertFalse(File(target.parentFile, "${target.name}.part").exists())
            }
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun sharedDownloadReplacesInsteadOfAppendingWithoutSynthesizingAValidator() {
        val base = tempFilesDir()
        val body = ByteArray(4_096) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val target = ModelDownload.destFile(base)
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(2_048) { 1 })
            ModelDownload.partFile(base).writeBytes(ByteArray(12_000) { 2 })

            val result = ModelDownload.download(
                "http://127.0.0.1:${server.address.port}/asset",
                target,
            ) { _, _ -> }

            assertTrue(result.ok)
            assertNull(result.validator)
            assertArrayEquals(body, target.readBytes())
            assertFalse(ModelDownload.partFile(base).exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun sharedDownloadReportsAnInstallFailureWhenTheTargetCannotBeReplaced() {
        val base = tempFilesDir()
        val body = ByteArray(4_096) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val target = ModelDownload.dictZipFile(base)
            assertTrue(target.mkdirs())
            File(target, "occupied").writeBytes(ByteArray(8))

            val result = ModelDownload.download(
                "http://127.0.0.1:${server.address.port}/asset",
                target,
            ) { _, _ -> }

            assertFalse(result.ok)
            assertEquals(ModelDownload.TransferFailure.INSTALL, result.failure)
            assertEquals(body.size.toLong(), result.bytesRead)
            assertNotNull("an install failure keeps its throwable", result.error)
            assertFalse(ModelDownload.dictPartFile(base).exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun modelProbeDoesNotUseContentLengthAsAValidator() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.responseHeaders.add("Content-Length", "4096")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val probe = ModelDownload.remoteValidatorProbe(
                "http://127.0.0.1:${server.address.port}/asset",
            )

            assertEquals(ModelDownload.ValidatorProbe.Reached(null), probe)
            assertEquals(
                ModelDownload.UpdateCheck.UNKNOWN,
                ModelDownload.modelUpdateAction(true, null, probe),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun malformedNormalSizeModelDownloadPreservesTheInstalledModel() {
        val base = tempFilesDir()
        val body = ByteBuffer.wrap(validGramBytes(4_096, 7)).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(36, 1)
        }.array()
        val old = validGramBytes(2_048, 1)
        val server = assetServer(body, "candidate-model")
        server.start()
        try {
            val target = ModelDownload.destFile(base)
            target.parentFile?.mkdirs()
            target.writeBytes(old)
            var persisted = false

            val result = ModelDownload.downloadModel(
                "http://127.0.0.1:${server.address.port}/asset",
                target,
                { _, _ -> },
            ) {
                persisted = true
                true
            }

            assertFalse(result.ok)
            assertEquals(ModelDownload.TransferFailure.INSTALL, result.failure)
            assertFalse(persisted)
            assertArrayEquals(old, target.readBytes())
            assertFalse(ModelDownload.partFile(base).exists())
            assertFalse(File(target.parentFile, "${target.name}.backup").exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun modelPreferenceCommitFailureRestoresTheInstalledModel() {
        val base = tempFilesDir()
        val body = validGramBytes(4_096, 2)
        val old = validGramBytes(2_048, 1)
        val server = assetServer(body, "candidate-model")
        server.start()
        try {
            val target = ModelDownload.destFile(base)
            target.parentFile?.mkdirs()
            target.writeBytes(old)
            var attemptedSnapshot: ModelDownload.ModelSnapshot? = null

            val result = ModelDownload.downloadModel(
                "http://127.0.0.1:${server.address.port}/asset",
                target,
                { _, _ -> },
            ) { snapshot ->
                attemptedSnapshot = snapshot
                false
            }

            assertFalse(result.ok)
            assertEquals(ModelDownload.TransferFailure.INSTALL, result.failure)
            val snapshot = requireNotNull(attemptedSnapshot)
            assertEquals("candidate-model", snapshot.validator)
            val expectedSha = MessageDigest.getInstance("SHA-256").digest(body)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            assertEquals(expectedSha, snapshot.sha256)
            assertEquals(body.size.toLong(), snapshot.sizeBytes)
            assertArrayEquals(old, target.readBytes())
            assertFalse(ModelDownload.partFile(base).exists())
            assertFalse(File(target.parentFile, "${target.name}.backup").exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun truncatedDownloadIsReportedAsIncomplete() {
        val base = tempFilesDir()
        val declared = 4_096L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, declared)
            exchange.responseBody.use { it.write(ByteArray(1_500)) }
            exchange.close()
        }
        server.start()
        try {
            val target = ModelDownload.destFile(base)
            target.parentFile?.mkdirs()

            val result = ModelDownload.download(
                "http://127.0.0.1:${server.address.port}/asset",
                target,
            ) { _, _ -> }

            assertFalse(result.ok)
            assertEquals(ModelDownload.TransferFailure.INCOMPLETE, result.failure)
            assertEquals("a body that never arrived hands the caller no bytes", 0L, result.bytesRead)
            assertEquals("and no length either", -1L, result.contentLength)
            assertTrue("the cause is the connection ending early", result.error is SocketException)
            assertFalse(target.exists())
            assertFalse(ModelDownload.partFile(base).exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun invalidShortDownloadPreservesTheInstalledModel() {
        val base = tempFilesDir()
        val body = ByteArray(16) { 2 }
        val old = ByteArray(2_048) { 1 }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val target = ModelDownload.destFile(base)
            target.parentFile?.mkdirs()
            target.writeBytes(old)

            val result = ModelDownload.download(
                "http://127.0.0.1:${server.address.port}/asset",
                target,
            ) { _, _ -> }

            assertFalse(result.ok)
            assertNull("a body delivered in full was not truncated", result.failure)
            assertEquals(body.size.toLong(), result.bytesRead)
            assertEquals(body.size.toLong(), result.contentLength)
            assertNull(result.error)
            assertArrayEquals(old, target.readBytes())
            assertFalse(ModelDownload.partFile(base).exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun incompleteDictionaryUpdatePreservesTheInstalledPack() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val old = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            ByteArray(2_048) { name.length.toByte() }.also { File(downloaded, name).writeBytes(it) }
        }
        val zip = ModelDownload.dictZipFile(base)
        writeZip(
            zip,
            mapOf(
                "aegis_dict.bin" to ByteArray(3_000) { 1 },
                "aegis_t9.bin" to ByteArray(3_000) { 2 },
            ),
        )
        val sha = ModelDownload.sha256Of(zip)

        assertFalse(ModelDownload.installDictPack(base, sha))
        old.forEach { (name, bytes) -> assertArrayEquals(bytes, File(downloaded, name).readBytes()) }
        assertFalse(zip.exists())
        assertFalse(File(downloaded, "dict-install").exists())
        ModelDownload.DICT_PACK_FILES.forEach { assertFalse(File(downloaded, "$it.part").exists()) }
        base.deleteRecursively()
    }

    @Test
    fun aGenerationWithoutTheLanguageModelStaysInstalledAndTakesItOnTheNextUpdate() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        ModelDownload.DICT_BIN_FILES.forEach { name ->
            File(downloaded, name).writeBytes(ByteArray(2_048) { 7 })
        }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText("a".repeat(64))

        assertTrue("the three tables are a complete generation", ModelDownload.isDictDownloaded(base))
        ModelDownload.reconcileInterruptedDownloads(base)

        assertTrue("reconciliation must not discard it", ModelDownload.isDictDownloaded(base))
        ModelDownload.DICT_BIN_FILES.forEach { assertTrue(File(downloaded, it).exists()) }
        assertNull(EngineAssets.downloadedOverride(downloaded, ModelDownload.LM_NAME))
        assertFalse(
            "a generation without the model is not a complete pack",
            ModelDownload.isDictPackComplete(base),
        )

        val model = ByteArray(3_000) { 4 }
        val replacements = ModelDownload.DICT_BIN_FILES.mapIndexed { index, name ->
            name to ByteArray(3_000) { (index + 1).toByte() }
        }.toMap() + (ModelDownload.LM_NAME to model)
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)

        assertTrue(ModelDownload.installDictPack(base, ModelDownload.sha256Of(zip)))
        replacements.forEach { (name, bytes) -> assertArrayEquals(bytes, File(downloaded, name).readBytes()) }
        assertEquals(
            File(downloaded, ModelDownload.LM_NAME).absolutePath,
            EngineAssets.downloadedOverride(downloaded, ModelDownload.LM_NAME)?.absolutePath,
        )
        assertTrue(ModelDownload.isDictPackComplete(base))
        base.deleteRecursively()
    }

    @Test
    fun aPackWithoutTheLanguageModelInstallsAndKeepsTheInstalledOne() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val model = ByteArray(3_000) { 9 }
        File(downloaded, ModelDownload.LM_NAME).writeBytes(model)
        val replacements = ModelDownload.DICT_BIN_FILES.mapIndexed { index, name ->
            name to ByteArray(3_000) { (index + 1).toByte() }
        }.toMap()
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)

        assertTrue(ModelDownload.installDictPack(base, ModelDownload.sha256Of(zip)))

        replacements.forEach { (name, bytes) -> assertArrayEquals(bytes, File(downloaded, name).readBytes()) }
        assertArrayEquals(
            "a pack that omits the model must not drop the installed one",
            model,
            File(downloaded, ModelDownload.LM_NAME).readBytes(),
        )
        assertTrue(ModelDownload.isDictDownloaded(base))
        base.deleteRecursively()
    }

    @Test
    fun completeDictionaryUpdateReplacesThePackAndCleansTransactions() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEach { name ->
            File(downloaded, name).writeBytes(ByteArray(2_048) { 9 })
        }
        val replacements = mapOf(
            "aegis_dict.bin" to ByteArray(3_000) { 1 },
            "aegis_t9.bin" to ByteArray(3_000) { 2 },
            "aegis_jianpin.bin" to ByteArray(3_000) { 3 },
            "aegis_lm.bin" to ByteArray(3_000) { 4 },
        )
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)
        val sha = ModelDownload.sha256Of(zip)

        assertTrue(ModelDownload.installDictPack(base, sha))
        replacements.forEach { (name, bytes) -> assertArrayEquals(bytes, File(downloaded, name).readBytes()) }
        assertEquals(sha, ModelDownload.installedDictionaryFileSha(base))
        assertFalse(zip.exists())
        assertFalse(File(downloaded, "dict-install").exists())
        ModelDownload.DICT_PACK_FILES.forEach {
            assertFalse(File(downloaded, "$it.part").exists())
            assertFalse(File(downloaded, "$it.backup").exists())
        }
        base.deleteRecursively()
    }

    @Test
    fun interruptedDictionaryReplacementRestoresTheInstalledPack() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val old = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            ByteArray(2_048) { name.length.toByte() }.also {
                File(downloaded, "$name.backup").writeBytes(it)
            }
        }
        ModelDownload.DICT_PACK_FILES.forEachIndexed { index, name ->
            File(downloaded, name).writeBytes(ByteArray(3_000) { (index + 1).toByte() })
            File(downloaded, "$name.part").writeBytes(ByteArray(1_500))
        }
        ModelDownload.dictZipFile(base).writeBytes(ByteArray(4_000))
        ModelDownload.dictPartFile(base).writeBytes(ByteArray(5_000))
        File(downloaded, "dict-install").apply {
            mkdirs()
            File(this, "payload").writeBytes(ByteArray(1_500))
        }

        ModelDownload.reconcileInterruptedDownloads(base)

        old.forEach { (name, bytes) ->
            assertArrayEquals(bytes, File(downloaded, name).readBytes())
            assertFalse(File(downloaded, "$name.backup").exists())
            assertFalse(File(downloaded, "$name.part").exists())
        }
        assertFalse(ModelDownload.dictZipFile(base).exists())
        assertFalse(ModelDownload.dictPartFile(base).exists())
        assertFalse(File(downloaded, "dict-install").exists())
        base.deleteRecursively()
    }

    @Test
    fun completeDictionaryGenerationSurvivesMetadataCrash() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val sha = "a".repeat(64)
        val replacements = ModelDownload.DICT_PACK_FILES.mapIndexed { index, name ->
            name to ByteArray(3_000) { (index + 1).toByte() }
        }.toMap()
        replacements.forEach { (name, bytes) ->
            File(downloaded, name).writeBytes(bytes)
            File(downloaded, "$name.backup").writeBytes(ByteArray(2_048) { 9 })
        }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText(sha)
        ModelDownload.dictZipFile(base).writeBytes(ByteArray(4_000))

        ModelDownload.reconcileInterruptedDownloads(base)

        replacements.forEach { (name, bytes) ->
            assertArrayEquals(bytes, File(downloaded, name).readBytes())
            assertFalse(File(downloaded, "$name.backup").exists())
        }
        assertEquals(sha, ModelDownload.installedDictionaryFileSha(base))
        assertFalse(ModelDownload.dictZipFile(base).exists())
        base.deleteRecursively()
    }

    @Test
    fun firstDictionaryGenerationSurvivesMetadataCrash() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val sha = "b".repeat(64)
        val replacements = ModelDownload.DICT_PACK_FILES.mapIndexed { index, name ->
            name to ByteArray(3_000) { (index + 1).toByte() }
        }.toMap()
        replacements.forEach { (name, bytes) -> File(downloaded, name).writeBytes(bytes) }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText(sha)
        ModelDownload.dictZipFile(base).writeBytes(ByteArray(4_000))

        ModelDownload.reconcileInterruptedDownloads(base)

        replacements.forEach { (name, bytes) ->
            assertArrayEquals(bytes, File(downloaded, name).readBytes())
        }
        assertEquals(sha, ModelDownload.installedDictionaryFileSha(base))
        assertFalse(ModelDownload.dictZipFile(base).exists())
        base.deleteRecursively()
    }

    @Test
    fun aPendingMarkerThatCannotBeWrittenKeepsItsCause() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val marker = File(downloaded, "aegis_dict_pack.pending.sha256").apply { mkdirs() }
        File(marker, "occupant").writeText("x")

        val outcome = ModelDownload.recordPendingDictionarySha(base, "a".repeat(64))

        assertTrue(outcome is ModelDownload.PendingMarker.NotWritten)
        assertTrue(
            "the cause the marker keeps must be the write that failed",
            (outcome as ModelDownload.PendingMarker.NotWritten).error is IOException,
        )
        assertFalse(ModelDownload.unmarkedDictionaryRecoveryRequired(base))

        base.deleteRecursively()
    }

    @Test
    fun verifiedCanonicalArchiveRecoveryReplacesAMixedDictionaryPack() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEachIndexed { index, name ->
            File(downloaded, name).writeBytes(ByteArray(2_048) { (index + 7).toByte() })
        }
        val replacements = mapOf(
            "aegis_dict.bin" to ByteArray(3_000) { 1 },
            "aegis_t9.bin" to ByteArray(3_000) { 2 },
            "aegis_jianpin.bin" to ByteArray(3_000) { 3 },
            "aegis_lm.bin" to ByteArray(3_000) { 4 },
        )
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)
        val sha = ModelDownload.sha256Of(zip)
        val archive = zip.readBytes()
        assertTrue(zip.delete())
        assertEquals(ModelDownload.PendingMarker.Recorded, ModelDownload.recordPendingDictionarySha(base, sha))
        zip.writeBytes(archive)

        ModelDownload.recoverInterruptedDictionaryInstall(base)

        replacements.forEach { (name, bytes) -> assertArrayEquals(bytes, File(downloaded, name).readBytes()) }
        assertEquals(sha, ModelDownload.installedDictionaryFileSha(base))
        assertFalse(zip.exists())
        assertFalse(File(downloaded, "dict-install").exists())
        base.deleteRecursively()
    }

    @Test
    fun unmarkedCanonicalArchiveNeverLeavesAPartiallyReplacedGenerationActive() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val old = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            ByteArray(2_048) { name.length.toByte() }.also { File(downloaded, name).writeBytes(it) }
        }
        val replacements = mapOf(
            "aegis_dict.bin" to ByteArray(3_000) { 1 },
            "aegis_t9.bin" to ByteArray(3_000) { 2 },
            "aegis_jianpin.bin" to ByteArray(3_000) { 3 },
            "aegis_lm.bin" to ByteArray(3_000) { 4 },
        )
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)
        val replacedName = ModelDownload.DICT_PACK_FILES.first()
        File(downloaded, replacedName).writeBytes(replacements.getValue(replacedName))
        assertArrayEquals(replacements.getValue(replacedName), File(downloaded, replacedName).readBytes())
        ModelDownload.DICT_PACK_FILES.drop(1).forEach { name ->
            assertArrayEquals(old.getValue(name), File(downloaded, name).readBytes())
        }

        ModelDownload.recoverInterruptedDictionaryInstall(base)

        ModelDownload.DICT_PACK_FILES.forEach { assertFalse(File(downloaded, it).exists()) }
        assertFalse(zip.exists())
        assertNull(ModelDownload.installedDictionaryFileSha(base))
        assertFalse(ModelDownload.isDictDownloaded(base))
        base.deleteRecursively()
    }

    @Test
    fun failedUnmarkedArchiveCleanupRetainsMarkerAndBlocksActivationUntilRetry() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEachIndexed { index, name ->
            File(downloaded, name).writeBytes(ByteArray(2_048) { (index + 1).toByte() })
        }
        val zip = ModelDownload.dictZipFile(base).apply { writeBytes(ByteArray(4_000)) }
        val sidecar = File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).apply { mkdirs() }
        val residue = File(sidecar, "residue").apply { writeText("x") }
        assertTrue(ModelDownload.isDictDownloaded(base))
        ModelDownload.DICT_PACK_FILES.forEach { name ->
            assertNull(EngineAssets.downloadedOverride(downloaded, name))
        }

        ModelDownload.recoverInterruptedDictionaryInstall(base)

        ModelDownload.DICT_PACK_FILES.forEach { name -> assertFalse(File(downloaded, name).exists()) }
        assertTrue(sidecar.exists())
        assertTrue(zip.exists())
        ModelDownload.reconcileInterruptedDownloads(base)
        assertTrue(zip.exists())

        assertTrue(residue.delete())
        ModelDownload.recoverInterruptedDictionaryInstall(base)

        assertFalse(sidecar.exists())
        assertFalse(zip.exists())
        assertFalse(ModelDownload.isDictDownloaded(base))
        base.deleteRecursively()
    }

    @Test
    fun unmarkedArchiveDoesNotReplaceASidecarGeneration() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val oldSha = "1".repeat(64)
        val old = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            ByteArray(2_048) { name.length.toByte() }.also { File(downloaded, name).writeBytes(it) }
        }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText(oldSha)
        val zip = ModelDownload.dictZipFile(base)
        writeZip(
            zip,
            mapOf(
                "aegis_dict.bin" to ByteArray(3_000) { 1 },
                "aegis_t9.bin" to ByteArray(3_000) { 2 },
                "aegis_jianpin.bin" to ByteArray(3_000) { 3 },
                "aegis_lm.bin" to ByteArray(3_000) { 4 },
            ),
        )

        ModelDownload.recoverInterruptedDictionaryInstall(base)

        old.forEach { (name, bytes) -> assertArrayEquals(bytes, File(downloaded, name).readBytes()) }
        assertFalse(zip.exists())
        assertEquals(oldSha, ModelDownload.installedDictionaryFileSha(base))
        base.deleteRecursively()
    }

    @Test
    fun interruptedDictionaryBackupPhaseRestoresTheInstalledPack() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val old = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            ByteArray(2_048) { name.length.toByte() }
        }
        val movedName = ModelDownload.DICT_PACK_FILES.first()
        old.forEach { (name, bytes) ->
            val suffix = if (name == movedName) ".backup" else ""
            File(downloaded, "$name$suffix").writeBytes(bytes)
        }
        ModelDownload.dictZipFile(base).writeBytes(ByteArray(4_000))
        File(downloaded, "dict-install").apply { mkdirs() }

        ModelDownload.reconcileInterruptedDownloads(base)

        old.forEach { (name, bytes) ->
            assertArrayEquals(bytes, File(downloaded, name).readBytes())
            assertFalse(File(downloaded, "$name.backup").exists())
        }
        assertFalse(ModelDownload.dictZipFile(base).exists())
        assertFalse(File(downloaded, "dict-install").exists())
        base.deleteRecursively()
    }

    @Test
    fun dictionaryMetadataFailureRestoresTheInstalledPack() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val oldSha = "c".repeat(64)
        val old = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            ByteArray(2_048) { name.length.toByte() }.also { File(downloaded, name).writeBytes(it) }
        }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText(oldSha)
        val replacements = mapOf(
            "aegis_dict.bin" to ByteArray(3_000) { 1 },
            "aegis_t9.bin" to ByteArray(3_000) { 2 },
            "aegis_jianpin.bin" to ByteArray(3_000) { 3 },
            "aegis_lm.bin" to ByteArray(3_000) { 4 },
        )
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)

        assertFalse(ModelDownload.installDictPack(base, ModelDownload.sha256Of(zip)) { false })

        old.forEach { (name, bytes) ->
            assertArrayEquals(bytes, File(downloaded, name).readBytes())
            assertFalse(File(downloaded, "$name.backup").exists())
        }
        assertEquals(oldSha, ModelDownload.installedDictionaryFileSha(base))
        assertFalse(zip.exists())
        assertFalse(File(downloaded, "dict-install").exists())
        base.deleteRecursively()
    }

    @Test
    fun dictionaryGenerationReadWaitsForTransactionRollback() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val old = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            ByteArray(2_048) { name.length.toByte() }.also { File(downloaded, name).writeBytes(it) }
        }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText("c".repeat(64))
        val replacements = mapOf(
            "aegis_dict.bin" to ByteArray(3_000) { 1 },
            "aegis_t9.bin" to ByteArray(3_000) { 2 },
            "aegis_jianpin.bin" to ByteArray(3_000) { 3 },
            "aegis_lm.bin" to ByteArray(3_000) { 4 },
        )
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readerDone = CountDownLatch(1)
        val snapshot = AtomicReference<Pair<String, List<ByteArray>>>()
        var installed = true
        val installer = Thread {
            installed = ModelDownload.installDictPack(base, ModelDownload.sha256Of(zip)) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                false
            }
        }
        installer.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val reader = Thread {
            snapshot.set(
                ModelDownload.withDictionaryGeneration {
                    EngineAssets.signature(downloaded) to
                        ModelDownload.DICT_PACK_FILES.map { File(downloaded, it).readBytes() }
                },
            )
            readerDone.countDown()
        }
        reader.start()
        assertFalse(readerDone.await(200, TimeUnit.MILLISECONDS))

        release.countDown()
        installer.join(2_000)
        assertFalse(installed)
        assertTrue(readerDone.await(2, TimeUnit.SECONDS))
        reader.join(2_000)
        assertEquals(EngineAssets.signature(downloaded), snapshot.get().first)
        ModelDownload.DICT_PACK_FILES.forEachIndexed { index, name ->
            assertArrayEquals(old.getValue(name), snapshot.get().second[index])
        }
        base.deleteRecursively()
    }

    @Test
    fun reconciliationRetriesResidueCleanupInTheSameProcess() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEach { name ->
            File(downloaded, name).writeBytes(ByteArray(2_048) { 1 })
        }
        File(downloaded, ModelDownload.DICT_INSTALLED_SHA_NAME).writeText("d".repeat(64))
        val backup = File(downloaded, "${ModelDownload.DICT_PACK_FILES.first()}.backup").apply {
            mkdirs()
        }
        val residue = File(backup, "residue").apply { writeText("x") }

        ModelDownload.reconcileInterruptedDownloads(base)
        assertTrue(backup.exists())
        assertTrue(residue.delete())
        ModelDownload.reconcileInterruptedDownloads(base)
        assertFalse(backup.exists())
        base.deleteRecursively()
    }

    @Test
    fun dictionaryDeleteDoesNotRaceAnInstallTransaction() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        ModelDownload.DICT_PACK_FILES.forEach { name ->
            File(downloaded, name).writeBytes(ByteArray(2_048) { 9 })
        }
        val replacements = mapOf(
            "aegis_dict.bin" to ByteArray(3_000) { 1 },
            "aegis_t9.bin" to ByteArray(3_000) { 2 },
            "aegis_jianpin.bin" to ByteArray(3_000) { 3 },
            "aegis_lm.bin" to ByteArray(3_000) { 4 },
        )
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)
        val sha = ModelDownload.sha256Of(zip)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var installed = false
        val thread = Thread {
            installed = ModelDownload.installDictPack(base, sha) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        thread.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        assertFalse(ModelDownload.purgeDict(base))

        release.countDown()
        thread.join(2_000)
        assertTrue(installed)
        replacements.forEach { (name, bytes) -> assertArrayEquals(bytes, File(downloaded, name).readBytes()) }
        assertEquals(sha, ModelDownload.installedDictionaryFileSha(base))
        base.deleteRecursively()
    }

    @Test
    fun unknownInstalledDictionaryDoesNotInferAPackHash() {
        val base = tempFilesDir()
        assertNull(ModelDownload.resolvedInstalledDictionarySha(base, null))
        ModelDownload.DICT_PACK_FILES.forEach { name ->
            File(base, "downloaded/$name").apply { parentFile?.mkdirs(); writeBytes(ByteArray(2_048)) }
        }
        assertNull(ModelDownload.resolvedInstalledDictionarySha(base, null))
        assertNull(ModelDownload.resolvedInstalledDictionarySha(base, "invalid"))
        val storedSha = "e".repeat(64)
        assertEquals(storedSha, ModelDownload.resolvedInstalledDictionarySha(base, storedSha))
        val installedSha = "d".repeat(64)
        File(base, "downloaded/${ModelDownload.DICT_INSTALLED_SHA_NAME}").writeText(installedSha)
        assertEquals(installedSha, ModelDownload.resolvedInstalledDictionarySha(base, "invalid"))
        base.deleteRecursively()
    }

    @Test
    fun unidentifiedInstalledModelReportsUnknownRatherThanAnUpdate() {
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.modelUpdateAction(
                true,
                null,
                ModelDownload.ValidatorProbe.Reached("remote-etag"),
            ),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.modelUpdateAction(
                true,
                "size:2048",
                ModelDownload.ValidatorProbe.Reached("size:2048"),
            ),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.modelUpdateAction(true, "remote-etag", ModelDownload.ValidatorProbe.Reached(null)),
        )
    }

    @Test
    fun validatorComparisonSeparatesUnknownFromNewer() {
        assertEquals(
            ModelDownload.UpdateCheck.UP_TO_DATE,
            ModelDownload.validatorComparison(local = "etag-1", remote = "etag-1"),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UPDATE,
            ModelDownload.validatorComparison(local = "etag-1", remote = "etag-2"),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.validatorComparison(local = null, remote = "etag-2"),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.validatorComparison(local = "etag-1", remote = null),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.validatorComparison(local = null, remote = null),
        )
    }

    @Test
    fun sizeDisplayUsesRoundedDecimalMegabytes() {
        assertEquals(1L, ModelDownload.bytesToDisplayMb(1_499_999L))
        assertEquals(2L, ModelDownload.bytesToDisplayMb(1_500_000L))
        assertEquals("exact MB", 100L, ModelDownload.bytesToDisplayMb(100_000_000L))
        assertEquals("zero bytes", 0L, ModelDownload.bytesToDisplayMb(0L))
    }

    @Test
    fun installedResourceSizesFollowTheFilesEachCardActivates() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val gram = ModelDownload.destFile(base).apply { writeBytes(ByteArray(1_499_999)) }
        File(downloaded, "unrelated.bin").writeBytes(ByteArray(200_000))
        File(downloaded, "${ModelDownload.GRAM_NAME}.part").writeBytes(ByteArray(300_000))
        assertEquals(1_499_999L, ModelDownload.installedGramBytes(base))

        val lengths = listOf(600_000, 700_000, 800_000)
        ModelDownload.DICT_PACK_FILES.zip(lengths).forEach { (name, length) ->
            File(downloaded, name).writeBytes(ByteArray(length))
        }
        ModelDownload.dictZipFile(base).writeBytes(ByteArray(900_000))
        assertEquals(2_100_000L, ModelDownload.installedDictionaryBytes(base))

        gram.writeBytes(ByteArray(1_500_000))
        File(downloaded, ModelDownload.DICT_PACK_FILES.first()).writeBytes(ByteArray(900_000))
        assertEquals(1_500_000L, ModelDownload.installedGramBytes(base))
        assertEquals(2_400_000L, ModelDownload.installedDictionaryBytes(base))
        assertEquals(2L, ModelDownload.bytesToDisplayMb(ModelDownload.installedGramBytes(base)))
        assertEquals(2L, ModelDownload.bytesToDisplayMb(ModelDownload.installedDictionaryBytes(base)))
        base.deleteRecursively()
    }

    @Test
    fun interruptedModelDownloadResumesWithARangeRequestAndTransfersOnlyTheRemainder() {
        val base = tempFilesDir()
        val body = validGramBytes(200_000, 5)
        val cut = 80_000
        val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            requests += exchange.requestHeaders.getFirst("Range") to exchange.requestHeaders.getFirst("If-Range")
            if (requests.size == 1) serveTruncated(exchange, body, cut, etag = "model-1")
            else serveRemainder(exchange, body, cut, etag = "model-1")
        }
        server.start()
        try {
            val target = ModelDownload.destFile(base)
            val part = ModelDownload.partFile(base)
            val sidecar = File(part.parentFile, "${part.name}.meta")
            val url = "http://127.0.0.1:${server.address.port}/asset"

            val first = ModelDownload.downloadModel(url, target, { _, _ -> }) { true }

            assertFalse(first.ok)
            assertEquals(ModelDownload.TransferFailure.INCOMPLETE, first.failure)
            assertEquals(cut.toLong(), first.bytesRead)
            assertEquals(0L, first.resumedFrom)
            assertEquals(cut.toLong(), part.length())
            assertTrue(sidecar.exists())

            var snapshot: ModelDownload.ModelSnapshot? = null
            val second = ModelDownload.downloadModel(url, target, { _, _ -> }) { snapshot = it; true }

            assertTrue(second.ok)
            assertEquals(cut.toLong(), second.resumedFrom)
            assertEquals(body.size.toLong(), second.bytesRead)
            assertEquals(listOf<String?>(null, "bytes=$cut-"), requests.map { it.first })
            assertEquals(listOf<String?>(null, "model-1"), requests.map { it.second })
            assertArrayEquals(body, target.readBytes())
            assertEquals("model-1", requireNotNull(snapshot).validator)
            assertEquals(sha256Hex(body), snapshot!!.sha256)
            assertFalse(part.exists())
            assertFalse(sidecar.exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun aServerThatIgnoresRangeRestartsTheTransferFromZeroWithoutAppending() {
        val base = tempFilesDir()
        val body = ByteArray(100_000) { (it % 249).toByte() }
        val cut = 40_000
        val ranges = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            if (ranges.size == 1) serveTruncated(exchange, body, cut)
            else serveFull(exchange, body)
        }
        server.start()
        try {
            val zip = ModelDownload.dictZipFile(base)
            val part = ModelDownload.dictPartFile(base)
            val url = "http://127.0.0.1:${server.address.port}/asset"
            val sha = sha256Hex(body)

            val first = ModelDownload.download(url, zip, sha) { _, _ -> }

            assertFalse(first.ok)
            assertEquals(ModelDownload.TransferFailure.INCOMPLETE, first.failure)
            assertEquals(cut.toLong(), part.length())

            val second = ModelDownload.download(url, zip, sha) { _, _ -> }

            assertTrue(second.ok)
            assertEquals(0L, second.resumedFrom)
            assertEquals("bytes=$cut-", ranges[1])
            assertArrayEquals(body, zip.readBytes())
            assertFalse(part.exists())
            assertFalse(File(part.parentFile, "${part.name}.meta").exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun aPartialBoundToADifferentExpectedArchiveIsDiscardedBeforeTheRequest() {
        val base = tempFilesDir()
        val v1 = ByteArray(120_000) { (it % 241).toByte() }
        val v2 = ByteArray(110_000) { (it % 239 + 7).toByte() }
        val ranges = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            if (ranges.size == 1) serveTruncated(exchange, v1, 50_000)
            else serveFull(exchange, v2)
        }
        server.start()
        try {
            val zip = ModelDownload.dictZipFile(base)
            val part = ModelDownload.dictPartFile(base)
            val url = "http://127.0.0.1:${server.address.port}/asset"

            val first = ModelDownload.download(url, zip, sha256Hex(v1)) { _, _ -> }

            assertFalse(first.ok)
            assertEquals(50_000L, part.length())

            val second = ModelDownload.download(url, zip, sha256Hex(v2)) { _, _ -> }

            assertTrue(second.ok)
            assertEquals(0L, second.resumedFrom)
            assertNull("a partial of another archive must not be continued", ranges[1])
            assertArrayEquals(v2, zip.readBytes())
            assertFalse(part.exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun aRangeResponseForADifferentEntityIsRefusedAndTheNextAttemptStartsClean() {
        val base = tempFilesDir()
        val v1 = ByteArray(200_000) { (it % 251).toByte() }
        val v2 = ByteArray(200_000) { (it % 253 + 2).toByte() }
        val cut = 80_000
        val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            requests += exchange.requestHeaders.getFirst("Range") to exchange.requestHeaders.getFirst("If-Range")
            when (requests.size) {
                1 -> serveTruncated(exchange, v1, cut, etag = "model-1")
                2 -> serveRemainder(exchange, v2, cut, etag = "model-2")
                else -> serveFull(exchange, v2, etag = "model-2")
            }
        }
        server.start()
        try {
            val target = ModelDownload.destFile(base)
            val part = ModelDownload.partFile(base)
            val sidecar = File(part.parentFile, "${part.name}.meta")
            val url = "http://127.0.0.1:${server.address.port}/asset"

            val first = ModelDownload.download(url, target) { _, _ -> }

            assertFalse(first.ok)
            assertEquals(cut.toLong(), part.length())

            val second = ModelDownload.download(url, target) { _, _ -> }

            assertFalse("a 206 for another entity must not be appended", second.ok)
            assertNotNull(second.error)
            assertFalse(target.exists())
            assertFalse(part.exists())
            assertFalse(sidecar.exists())

            val third = ModelDownload.download(url, target) { _, _ -> }

            assertTrue(third.ok)
            assertEquals(0L, third.resumedFrom)
            assertEquals("model-1", requests[1].second)
            assertNull("the refused partial must not be offered again", requests[2].first)
            assertArrayEquals(v2, target.readBytes())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun aSplicedArchiveThatEvadesTransportChecksFailsTheDigestGate() {
        val base = tempFilesDir()
        val source = File(base, "origin.zip")
        writeZip(
            source,
            ModelDownload.DICT_PACK_FILES.mapIndexed { index, name ->
                name to ByteArray(40_000).also { Random(index + 1).nextBytes(it) }
            }.toMap(),
        )
        val v1 = source.readBytes()
        assertTrue(source.delete())
        val expectedSha = sha256Hex(v1)
        val cut = v1.size / 2
        val v2 = v1.copyOf().also { swapped ->
            for (i in cut until swapped.size) swapped[i] = (swapped[i] + 1).toByte()
        }
        val ranges = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            if (ranges.size == 1) serveTruncated(exchange, v1, cut)
            else serveRemainder(exchange, v2, cut)
        }
        server.start()
        try {
            val zip = ModelDownload.dictZipFile(base)
            val url = "http://127.0.0.1:${server.address.port}/asset"

            val first = ModelDownload.download(url, zip, expectedSha) { _, _ -> }
            assertFalse(first.ok)

            val second = ModelDownload.download(url, zip, expectedSha) { _, _ -> }

            assertTrue("the transport layer alone cannot see the swap", second.ok)
            assertEquals(cut.toLong(), second.resumedFrom)
            assertEquals("bytes=$cut-", ranges[1])

            assertFalse("the digest gate has the last word", ModelDownload.installDictPack(base, expectedSha))
            assertFalse(ModelDownload.isDictDownloaded(base))
            ModelDownload.DICT_PACK_FILES.forEach { name ->
                assertFalse(File(File(base, "downloaded"), name).exists())
            }
            assertFalse(zip.exists())
            assertFalse(ModelDownload.dictPartFile(base).exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    @Test
    fun reconciliationKeepsABoundPartialAndDropsAnUnboundOne() {
        val base = tempFilesDir()
        val body = ByteArray(100_000) { (it % 245).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange -> serveTruncated(exchange, body, 30_000, etag = "model-1") }
        server.start()
        try {
            val target = ModelDownload.destFile(base)
            val part = ModelDownload.partFile(base)
            val sidecar = File(part.parentFile, "${part.name}.meta")

            val first = ModelDownload.download("http://127.0.0.1:${server.address.port}/asset", target) { _, _ -> }

            assertFalse(first.ok)
            assertEquals(30_000L, part.length())
            assertTrue(sidecar.exists())

            ModelDownload.reconcileInterruptedDownloads(base)
            assertTrue("a bound partial survives reconciliation", part.exists())
            assertTrue(sidecar.exists())

            sidecar.writeText("not the recorded identity")
            ModelDownload.reconcileInterruptedDownloads(base)
            assertFalse("an unbound partial is discarded", part.exists())
            assertFalse(sidecar.exists())

            val dictPart = ModelDownload.dictPartFile(base).apply { parentFile?.mkdirs(); writeBytes(ByteArray(5_000)) }
            val dictSidecar = File(dictPart.parentFile, "${dictPart.name}.meta").apply { writeText("stale") }
            assertTrue(ModelDownload.purgeDict(base))
            assertFalse(dictPart.exists())
            assertFalse(dictSidecar.exists())
        } finally {
            server.stop(0)
            base.deleteRecursively()
        }
    }

    private fun serveTruncated(exchange: HttpExchange, body: ByteArray, cut: Int, etag: String? = null) {
        if (etag != null) exchange.responseHeaders.add("ETag", etag)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body, 0, cut) }
        exchange.close()
    }

    private fun serveRemainder(exchange: HttpExchange, body: ByteArray, offset: Int, etag: String? = null) {
        if (etag != null) exchange.responseHeaders.add("ETag", etag)
        exchange.responseHeaders.add("Content-Range", "bytes $offset-${body.size - 1}/${body.size}")
        exchange.sendResponseHeaders(206, (body.size - offset).toLong())
        exchange.responseBody.use { it.write(body, offset, body.size - offset) }
        exchange.close()
    }

    private fun serveFull(exchange: HttpExchange, body: ByteArray, etag: String? = null) {
        if (etag != null) exchange.responseHeaders.add("ETag", etag)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun writeZip(dest: File, entries: Map<String, ByteArray>) {
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun assetServer(body: ByteArray, validator: String?): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/asset") { exchange ->
                if (validator != null) exchange.responseHeaders.add("ETag", validator)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
        }

    private fun validGramBytes(size: Int, marker: Byte): ByteArray =
        ByteBuffer.wrap(ByteArray(size)).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("Rime::Grammar/1.0".toByteArray(Charsets.US_ASCII))
            putInt(36, (size - 44) / 4)
            putInt(40, 4)
            put(size - 1, marker)
        }.array()
}
