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

package com.aegis.ime.neural

import android.content.Context
import android.content.SharedPreferences
import com.aegis.ime.dict.ModelDownload
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeuralModelDownloadTest {

    private val context = RuntimeEnvironment.getApplication()
    private val prefs: SharedPreferences = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private lateinit var filesDir: File

    @Before fun setUp() {
        filesDir = File.createTempFile("filesdir", "").apply { delete(); mkdirs() }
        prefs.edit().remove(NeuralModelDownload.MANIFEST_PREF).commit()
    }

    @After fun tearDown() {
        prefs.edit().remove(NeuralModelDownload.MANIFEST_PREF).commit()
        filesDir.deleteRecursively()
    }

    private fun manifest(
        sha256: String = PUBLISHED_SHA,
        bytes: Long = 639_446_784L,
        name: String = "qwen3-0.6b-base-q8_0.gguf",
        url: String = NeuralModelDownload.RELEASE_URL + name,
        schema: Int = 1,
        rerank: String = """{"alphaLetters":3,"alphaNine":2,"candidates":10}""",
    ): String =
        """
        {"schema":$schema,"id":"qwen3-0.6b-base-q8_0","name":"Qwen3-0.6B-Base Q8_0",
         "file":{"name":"$name","url":"$url","bytes":$bytes,"sha256":"$sha256"},
         "license":{"spdx":"Apache-2.0","name":"MODEL-LICENSE.txt","url":"${NeuralModelDownload.RELEASE_URL}MODEL-LICENSE.txt","sha256":"${"c".repeat(64)}"},
         "rerank":$rerank,
         "source":{"repo":"Qwen/Qwen3-0.6B-Base","revision":"da87bfb608c14b7cf20ba1ce41287e8de496c0cd"},
         "conversion":{"tool":"llama.cpp","version":"v0.5.0","outtype":"bf16","quantization":"Q8_0"}}
        """.trimIndent()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun modelServer(body: ByteArray): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/model.gguf") { exchange ->
                exchange.responseHeaders.add("ETag", "\"model\"")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            start()
        }

    private fun installedBody(body: ByteArray, name: String = "qwen3-0.6b-base-q8_0.gguf"): NeuralModelDownload.Manifest {
        val parsed = NeuralModelDownload.parse(manifest(sha256 = sha256Hex(body), bytes = body.size.toLong(), name = name))
        NeuralModelDownload.modelFile(filesDir, parsed).apply {
            parentFile?.mkdirs()
            writeBytes(body)
        }
        prefs.edit().putString(NeuralModelDownload.MANIFEST_PREF, parsed.json).commit()
        return parsed
    }

    @Test fun the_published_manifest_parses_into_the_model_file_and_its_reranking_parameters() {
        assertEquals(
            "https://github.com/lurixo/Aegis/releases/download/model-latest/aegis-model-update.json",
            NeuralModelDownload.MANIFEST_URL,
        )
        val parsed = NeuralModelDownload.parse(manifest())
        assertEquals("qwen3-0.6b-base-q8_0", parsed.id)
        assertEquals("qwen3-0.6b-base-q8_0.gguf", parsed.fileName)
        assertEquals(
            "https://github.com/lurixo/Aegis/releases/download/model-latest/qwen3-0.6b-base-q8_0.gguf",
            parsed.url,
        )
        assertEquals(639_446_784L, parsed.bytes)
        assertEquals(PUBLISHED_SHA, parsed.sha256)
        assertEquals(NeuralModelSpec("Qwen3-0.6B-Base Q8_0", alphaLetters = 3.0, alphaNine = 2.0, candidates = 10), parsed.spec)
        assertEquals(
            NeuralModelSpec.MAX_CANDIDATES,
            NeuralModelDownload.parse(manifest(rerank = """{"alphaLetters":3,"alphaNine":2,"candidates":99}""")).spec.candidates,
        )
    }

    @Test fun manifests_outside_the_contract_are_rejected() {
        val rejected = mapOf(
            "schema 2" to manifest(schema = 2),
            "upper-case digest" to manifest(sha256 = PUBLISHED_SHA.uppercase()),
            "prefixed digest" to manifest(sha256 = "sha256:${PUBLISHED_SHA.drop(7)}"),
            "short digest" to manifest(sha256 = PUBLISHED_SHA.drop(1)),
            "non-hex digest" to manifest(sha256 = "g".repeat(64)),
            "zero bytes" to manifest(bytes = 0L),
            "negative bytes" to manifest(bytes = -1L),
            "another release" to manifest(url = "https://github.com/lurixo/Aegis/releases/download/dict-latest/qwen3-0.6b-base-q8_0.gguf"),
            "another host" to manifest(url = "https://example.com/model-latest/qwen3-0.6b-base-q8_0.gguf"),
            "plain http" to manifest(url = "http://github.com/lurixo/Aegis/releases/download/model-latest/qwen3-0.6b-base-q8_0.gguf"),
            "bare release url" to manifest(url = NeuralModelDownload.RELEASE_URL),
            "slash in the file name" to manifest(name = "../qwen3.gguf", url = NeuralModelDownload.RELEASE_URL + "qwen3.gguf"),
            "backslash in the file name" to manifest(name = "a\\\\qwen3.gguf", url = NeuralModelDownload.RELEASE_URL + "qwen3.gguf"),
            "hidden file name" to manifest(name = ".gguf"),
            "not a gguf" to manifest(name = "qwen3.bin"),
            "no rerank parameters" to manifest().replace(Regex(""""rerank":\{[^}]*\},"""), ""),
            "not json" to "<html>",
        )
        for ((label, json) in rejected) {
            assertTrue("$label must be rejected", NeuralModelDownload.resolve { json }.isFailure)
        }
        assertTrue(NeuralModelDownload.resolve { manifest() }.isSuccess)
    }

    @Test fun a_verified_download_is_installed_alone_and_recorded() {
        val body = ByteArray(8_192) { (it % 251).toByte() }
        val parsed = NeuralModelDownload.parse(manifest(sha256 = sha256Hex(body), bytes = body.size.toLong()))
        val dir = NeuralModelDownload.modelDir(filesDir).apply { mkdirs() }
        val previous = File(dir, "previous.gguf").apply { writeBytes(ByteArray(4_096) { 1 }) }
        val stalePart = File(dir, "previous.gguf.part").apply { writeBytes(ByteArray(2_048) { 2 }) }
        val server = modelServer(body)
        try {
            val progress = ArrayList<Long>()
            val result = NeuralModelDownload.install(
                filesDir,
                prefs,
                parsed,
                { done, _ -> progress += done },
                url = "http://127.0.0.1:${server.address.port}/model.gguf",
            )

            assertTrue(result.ok)
            assertEquals(body.size.toLong(), progress.last())
            val installed = File(File(File(filesDir, "downloaded"), "neural"), "qwen3-0.6b-base-q8_0.gguf")
            assertArrayEquals(body, installed.readBytes())
            assertEquals(listOf(installed.name), dir.list()!!.toList())
            assertFalse(previous.exists())
            assertFalse(stalePart.exists())
            assertEquals(parsed.json, prefs.getString(NeuralModelDownload.MANIFEST_PREF, null))
            assertEquals(parsed, NeuralModelDownload.installed(filesDir, prefs))
        } finally {
            server.stop(0)
        }
    }

    @Test fun a_download_that_fails_its_digest_installs_nothing_and_keeps_the_current_model() {
        val current = ByteArray(4_096) { 3 }
        val kept = installedBody(current, name = "current.gguf")
        val body = ByteArray(8_192) { (it % 241).toByte() }
        val wrong = NeuralModelDownload.parse(manifest(sha256 = "0".repeat(64), bytes = body.size.toLong()))
        val server = modelServer(body)
        try {
            val result = NeuralModelDownload.install(
                filesDir,
                prefs,
                wrong,
                { _, _ -> },
                url = "http://127.0.0.1:${server.address.port}/model.gguf",
            )

            assertFalse(result.ok)
            assertEquals(ModelDownload.TransferFailure.CORRUPT, result.failure)
            assertFalse(NeuralModelDownload.modelFile(filesDir, wrong).exists())
            assertFalse(File(NeuralModelDownload.modelDir(filesDir), "${wrong.fileName}.part").exists())
            assertArrayEquals(current, NeuralModelDownload.modelFile(filesDir, kept).readBytes())
            assertEquals(kept, NeuralModelDownload.installed(filesDir, prefs))
        } finally {
            server.stop(0)
        }
    }

    @Test fun a_download_whose_size_differs_from_the_manifest_is_not_installed() {
        val body = ByteArray(8_192) { (it % 239).toByte() }
        val parsed = NeuralModelDownload.parse(manifest(sha256 = sha256Hex(body), bytes = body.size + 1L))
        val server = modelServer(body)
        try {
            val result = NeuralModelDownload.install(
                filesDir,
                prefs,
                parsed,
                { _, _ -> },
                url = "http://127.0.0.1:${server.address.port}/model.gguf",
            )

            assertFalse(result.ok)
            assertEquals(ModelDownload.TransferFailure.INSTALL, result.failure)
            assertFalse(NeuralModelDownload.modelFile(filesDir, parsed).exists())
            assertNull(prefs.getString(NeuralModelDownload.MANIFEST_PREF, null))
            assertNull(NeuralModelDownload.installed(filesDir, prefs))
        } finally {
            server.stop(0)
        }
    }

    @Test fun a_recorded_model_counts_as_installed_only_while_its_file_is_whole() {
        val parsed = installedBody(ByteArray(4_096) { 5 })
        assertEquals(parsed, NeuralModelDownload.installed(filesDir, prefs))

        NeuralModelDownload.modelFile(filesDir, parsed).writeBytes(ByteArray(4_000))
        assertNull(NeuralModelDownload.installed(filesDir, prefs))

        NeuralModelDownload.modelFile(filesDir, parsed).delete()
        assertNull(NeuralModelDownload.installed(filesDir, prefs))

        prefs.edit().putString(NeuralModelDownload.MANIFEST_PREF, "{}").commit()
        assertNull(NeuralModelDownload.installed(filesDir, prefs))
    }

    @Test fun an_update_check_compares_the_published_digest_with_the_installed_one() {
        val body = ByteArray(4_096) { 6 }
        val parsed = installedBody(body)

        val same = NeuralModelDownload.checkUpdate(filesDir, prefs) { parsed.json }
        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, same.state)
        assertNull(same.manifest)

        val newer = manifest(sha256 = "1".repeat(64), bytes = 5_000L)
        val update = NeuralModelDownload.checkUpdate(filesDir, prefs) { newer }
        assertEquals(ModelDownload.UpdateCheck.UPDATE, update.state)
        assertEquals("1".repeat(64), update.manifest?.sha256)
        assertEquals(parsed.json, prefs.getString(NeuralModelDownload.MANIFEST_PREF, null))
    }

    @Test fun an_update_check_without_an_installed_model_offers_the_published_one() {
        val result = NeuralModelDownload.checkUpdate(filesDir, prefs) { manifest() }
        assertEquals(ModelDownload.UpdateCheck.UPDATE, result.state)
        assertEquals(PUBLISHED_SHA, result.manifest?.sha256)
    }

    @Test fun new_reranking_parameters_for_the_same_model_are_taken_without_a_download() {
        val body = ByteArray(4_096) { 7 }
        installedBody(body)
        val retuned = manifest(
            sha256 = sha256Hex(body),
            bytes = body.size.toLong(),
            rerank = """{"alphaLetters":4.5,"alphaNine":1.5,"candidates":12}""",
        )

        val result = NeuralModelDownload.checkUpdate(filesDir, prefs) { retuned }

        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, result.state)
        val installed = NeuralModelDownload.installed(filesDir, prefs)!!
        assertEquals(4.5, installed.spec.alphaLetters, 0.0)
        assertEquals(1.5, installed.spec.alphaNine, 0.0)
        assertEquals(12, installed.spec.candidates)
        assertArrayEquals(body, NeuralModelDownload.modelFile(filesDir, installed).readBytes())
    }

    @Test fun update_check_failures_keep_their_cause() {
        installedBody(ByteArray(4_096) { 8 })
        assertEquals(
            ModelDownload.UpdateCheck.OFFLINE,
            NeuralModelDownload.checkUpdate(filesDir, prefs) { throw UnknownHostException("github.com") }.state,
        )
        assertEquals(
            ModelDownload.UpdateCheck.TIMEOUT,
            NeuralModelDownload.checkUpdate(filesDir, prefs) { throw SocketTimeoutException("read timed out") }.state,
        )
        assertEquals(
            ModelDownload.UpdateCheck.PARSE_ERROR,
            NeuralModelDownload.checkUpdate(filesDir, prefs) { manifest(schema = 2) }.state,
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/aegis-model-update.json") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        try {
            val result = NeuralModelDownload.checkUpdate(filesDir, prefs) {
                ModelDownload.fetchText("http://127.0.0.1:${server.address.port}/aegis-model-update.json")
            }
            assertEquals(ModelDownload.UpdateCheck.SERVER_ERROR, result.state)
            assertNull(result.manifest)
        } finally {
            server.stop(0)
        }
    }

    @Test fun deleting_removes_the_model_its_partial_download_and_the_record() {
        val parsed = installedBody(ByteArray(4_096) { 9 })
        val dir = NeuralModelDownload.modelDir(filesDir)
        File(dir, "${parsed.fileName}.part").writeBytes(ByteArray(2_048))
        File(dir, "${parsed.fileName}.part.meta").writeText("identity")

        assertTrue(NeuralModelDownload.delete(filesDir, prefs))

        assertFalse(dir.exists())
        assertFalse(prefs.contains(NeuralModelDownload.MANIFEST_PREF))
        assertNull(NeuralModelDownload.installed(filesDir, prefs))
        assertTrue("deleting again still confirms absence", NeuralModelDownload.delete(filesDir, prefs))
    }

    private companion object {
        const val PUBLISHED_SHA = "fd15a5badaf0d117d1d62832b8fba0009c79b7b52cf62fddd2867ecbf25ae5a9"
    }
}
