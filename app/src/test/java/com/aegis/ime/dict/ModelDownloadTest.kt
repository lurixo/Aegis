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

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        gram.parentFile?.mkdirs()
        gram.writeText("model")
        part.writeText("leftover")

        assertTrue("first purge removes leftovers", ModelDownload.purge(base))
        assertFalse(gram.exists())
        assertFalse("interrupted .part is cleaned too", part.exists())
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
    fun abandonedTransactionsAreReconciledWithoutRemovingInstalledResources() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val gram = ModelDownload.destFile(base).apply { writeBytes(ByteArray(2_048) { 1 }) }
        val installed = ModelDownload.DICT_PACK_FILES.associateWith { name ->
            File(downloaded, name).apply { writeBytes(ByteArray(2_048) { name.length.toByte() }) }
        }
        ModelDownload.partFile(base).writeBytes(ByteArray(3_000))
        ModelDownload.dictPartFile(base).writeBytes(ByteArray(5_000))
        File(downloaded, ModelDownload.FALLBACK_DICT_NAME).writeBytes(ByteArray(6_000))
        File(downloaded, "${ModelDownload.FALLBACK_DICT_NAME}.part").writeBytes(ByteArray(7_000))
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
        assertFalse(File(downloaded, ModelDownload.FALLBACK_DICT_NAME).exists())
        assertFalse(File(downloaded, "${ModelDownload.FALLBACK_DICT_NAME}.part").exists())
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
                ModelDownload.UpdateCheck.UPDATE,
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
            var attemptedValidator: String? = null

            val result = ModelDownload.downloadModel(
                "http://127.0.0.1:${server.address.port}/asset",
                target,
                { _, _ -> },
            ) { validator ->
                attemptedValidator = validator
                false
            }

            assertFalse(result.ok)
            assertEquals("candidate-model", attemptedValidator)
            assertArrayEquals(old, target.readBytes())
            assertFalse(ModelDownload.partFile(base).exists())
            assertFalse(File(target.parentFile, "${target.name}.backup").exists())
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
        )
        val zip = ModelDownload.dictZipFile(base)
        writeZip(zip, replacements)
        val sha = ModelDownload.sha256Of(zip)
        assertTrue(ModelDownload.recordPendingDictionarySha(base, sha))

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
    fun unknownInstalledDictionaryDoesNotInferTheFallbackPackHash() {
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
    fun unidentifiedInstalledModelAlwaysOffersTheUpdate() {
        assertEquals(
            ModelDownload.UpdateCheck.UPDATE,
            ModelDownload.modelUpdateAction(
                true,
                null,
                ModelDownload.ValidatorProbe.Reached("remote-etag"),
            ),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UPDATE,
            ModelDownload.modelUpdateAction(
                true,
                "size:2048",
                ModelDownload.ValidatorProbe.Reached("size:2048"),
            ),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UPDATE,
            ModelDownload.modelUpdateAction(true, "remote-etag", ModelDownload.ValidatorProbe.Reached(null)),
        )
    }

    @Test
    fun updateAvailableOnlySuppressedByConfirmedMatch() {
        assertFalse(ModelDownload.updateAvailable(local = "etag-1", remote = "etag-1"))
        assertTrue(ModelDownload.updateAvailable(local = "etag-1", remote = "etag-2"))
        assertTrue(ModelDownload.updateAvailable(local = null, remote = "etag-2"))
        assertTrue(ModelDownload.updateAvailable(local = "etag-1", remote = null))
        assertTrue(ModelDownload.updateAvailable(local = null, remote = null))
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
