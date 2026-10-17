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
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.neural.NeuralModelDownload
import com.aegis.ime.neural.NeuralReranker
import com.aegis.ime.neural.NeuralRuntime
import com.aegis.ime.neural.SentenceScorer
import java.io.File
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class RerankContextWiringTest {

    private class WarmScorer : SentenceScorer {
        val warmed: MutableList<String> = Collections.synchronizedList(ArrayList())
        val prefetchedOn: MutableList<Thread> = Collections.synchronizedList(ArrayList())
        override fun score(context: String, candidates: List<String>): DoubleArray? = null
        override fun warm(context: String) {
            warmed.add(context)
        }
        override fun prefetch() {
            prefetchedOn.add(Thread.currentThread())
        }
        override fun cancel() {}
        override fun resume() {}
        override fun close() {}
    }

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private val services = ArrayList<AegisInputMethodService>()
    private val modelBytes = 2_048

    @Before fun clean() {
        NeuralModelDownload.delete(context.filesDir, prefs)
        NeuralRuntime.active = null
    }

    @After fun stop() {
        services.forEach { it.onDestroy() }
        services.clear()
        shadowOf(Looper.getMainLooper()).idle()
        clean()
    }

    private fun <T> field(service: AegisInputMethodService, name: String): T =
        service.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(service) as T
        }

    private fun install(): File {
        val name = "model-a.gguf"
        val manifest = NeuralModelDownload.parse(
            """
            {"schema":1,"id":"m-a","name":"Model a",
             "file":{"name":"$name","url":"${NeuralModelDownload.RELEASE_URL}$name","bytes":$modelBytes,"sha256":"${"a".repeat(64)}"},
             "rerank":{"alphaLetters":3,"alphaNine":2,"candidates":10}}
            """.trimIndent(),
        )
        val file = NeuralModelDownload.modelFile(context.filesDir, manifest).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(modelBytes))
        }
        prefs.edit().putString(NeuralModelDownload.MANIFEST_PREF, manifest.json).commit()
        return file
    }

    private fun start(): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).create().get()
        services += service
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && Thread.getAllStackTraces().keys.any { it.name == "aegis-dict-load" && it.isAlive }) Thread.yield()
        shadowOf(Looper.getMainLooper()).idle()
        field<ExecutorService>(service, "neuralWorker").submit {}.get(10, TimeUnit.SECONDS)
        return service
    }

    private fun ready(
        service: AegisInputMethodService,
        scorer: SentenceScorer,
        worker: Executor = Executor { it.run() },
        clock: () -> Long = System::nanoTime,
    ): NeuralReranker {
        val controller = field<KeyboardController>(service, "controller")
        val reranker = NeuralReranker(controller.neural!!.spec, { scorer }, worker, Executor { it.run() }, { _, _ -> }, nanoTime = clock)
        controller.neural = reranker
        reranker.start()
        return reranker
    }

    private fun show(service: AegisInputMethodService) {
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 21
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
    }

    @Test fun showing_the_keyboard_loads_the_reranking_context_once() {
        install()
        val service = start()
        val scorer = WarmScorer()
        assertTrue(ready(service, scorer = scorer).ready)
        assertTrue(scorer.warmed.isEmpty())

        show(service)
        assertEquals(listOf(""), scorer.warmed.toList())

        show(service)
        assertEquals(listOf(""), scorer.warmed.toList())
    }

    @Test fun showing_the_keyboard_prefetches_the_model_off_the_main_thread_at_most_once_in_ten_seconds() {
        install()
        val service = start()
        val scorer = WarmScorer()
        val elsewhere = Executor { task -> Thread(task, "rerank-test").apply { start() }.join() }
        var now = 0L
        assertTrue(ready(service, scorer = scorer, worker = elsewhere, clock = { now }).ready)
        assertTrue(scorer.prefetchedOn.isEmpty())

        show(service)
        assertEquals(1, scorer.prefetchedOn.size)
        assertTrue(scorer.prefetchedOn.none { it === Looper.getMainLooper().thread })

        now = TimeUnit.SECONDS.toNanos(10) - 1
        show(service)
        assertEquals(1, scorer.prefetchedOn.size)

        now = TimeUnit.SECONDS.toNanos(10)
        show(service)
        assertEquals(2, scorer.prefetchedOn.size)
        assertEquals(listOf("rerank-test", "rerank-test"), scorer.prefetchedOn.map { it.name })
    }

    @Test fun showing_the_keyboard_prefetches_nothing_while_the_model_loads() {
        install()
        val service = start()
        val scorer = WarmScorer()
        val controller = field<KeyboardController>(service, "controller")
        controller.neural = NeuralReranker(controller.neural!!.spec, { scorer }, Executor {}, Executor { it.run() }, { _, _ -> })
        show(service)
        assertTrue(scorer.prefetchedOn.isEmpty())
        assertTrue(scorer.warmed.isEmpty())
    }
}
