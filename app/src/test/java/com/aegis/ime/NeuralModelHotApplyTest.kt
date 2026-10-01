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

package com.aegis.ime

import android.content.Context
import android.os.Looper
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.neural.NeuralModelDownload
import com.aegis.ime.neural.NeuralModelSpec
import com.aegis.ime.neural.NeuralReranker
import com.aegis.ime.neural.NeuralRuntime
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeuralModelHotApplyTest {

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)

    @Before @After fun clean() {
        NeuralModelDownload.delete(context.filesDir, prefs)
        NeuralRuntime.active = null
    }

    private fun startKeyboard(): AegisInputMethodService =
        Robolectric.buildService(AegisInputMethodService::class.java).create().get()

    private fun controllerOf(service: AegisInputMethodService): KeyboardController =
        service.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(service) as KeyboardController

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private fun record(sha: String, alphaLetters: Double, writeFile: Boolean = true): NeuralModelDownload.Manifest {
        val name = "model-$sha.gguf"
        val manifest = NeuralModelDownload.parse(
            """
            {"schema":1,"id":"m-$sha","name":"Model $sha",
             "file":{"name":"$name","url":"${NeuralModelDownload.RELEASE_URL}$name","bytes":2048,"sha256":"${sha.repeat(64)}"},
             "rerank":{"alphaLetters":$alphaLetters,"alphaNine":2,"candidates":10}}
            """.trimIndent(),
        )
        if (writeFile) {
            NeuralModelDownload.modelFile(context.filesDir, manifest).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(2_048))
            }
        }
        prefs.edit().putString(NeuralModelDownload.MANIFEST_PREF, manifest.json).commit()
        return manifest
    }

    @Test fun a_model_installed_before_the_keyboard_starts_is_used_with_its_manifest_parameters() {
        val installed = record("a", alphaLetters = 3.0)

        val controller = controllerOf(startKeyboard())

        val reranker = controller.neural
        assertNotNull(reranker)
        assertEquals(installed.spec, reranker!!.spec)
        assertSame(reranker, NeuralRuntime.active)
    }

    @Test fun installing_updating_and_deleting_the_model_take_effect_in_the_running_keyboard() {
        val controller = controllerOf(startKeyboard())
        assertNull("no model, no reranking", controller.neural)

        val first = record("a", alphaLetters = 3.0)
        drain()
        val firstReranker = controller.neural!!
        assertEquals(first.spec, firstReranker.spec)
        assertSame(firstReranker, NeuralRuntime.active)

        val second = record("b", alphaLetters = 4.5)
        drain()
        val secondReranker = controller.neural!!
        assertNotSame(firstReranker, secondReranker)
        assertEquals(NeuralModelSpec("Model b", alphaLetters = 4.5, alphaNine = 2.0, candidates = 10), secondReranker.spec)
        assertEquals(second.spec, secondReranker.spec)
        assertEquals(NeuralReranker.Phase.CLOSED, firstReranker.snapshot().phase)
        assertSame(secondReranker, NeuralRuntime.active)

        NeuralModelDownload.delete(context.filesDir, prefs)
        drain()
        assertNull(controller.neural)
        assertNull(NeuralRuntime.active)
        assertEquals(NeuralReranker.Phase.CLOSED, secondReranker.snapshot().phase)
    }

    @Test fun a_model_change_that_lands_after_the_model_thread_stopped_is_dropped() {
        val service = startKeyboard()
        val worker = service.javaClass.getDeclaredField("neuralWorker").apply { isAccessible = true }
            .get(service) as ExecutorService

        record("d", alphaLetters = 3.0)
        worker.shutdown()
        drain()

        assertNull(controllerOf(service).neural)
        assertNull(NeuralRuntime.active)
    }

    @Test fun a_record_without_its_model_file_leaves_reranking_off() {
        record("c", alphaLetters = 3.0, writeFile = false)

        val controller = controllerOf(startKeyboard())

        assertNull(controller.neural)
        assertNull(NeuralRuntime.active)
    }
}
