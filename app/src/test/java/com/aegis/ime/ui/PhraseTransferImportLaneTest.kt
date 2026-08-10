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
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhraseTransferImportLaneTest {

    private val app = RuntimeEnvironment.getApplication()
    private val phraseFile = File(app.filesDir, "phrases.txt")
    private var gate: CountDownLatch? = null
    private var live: ClipboardStore? = null

    @Before fun start() {
        ShadowToast.reset()
        phraseFile.delete()
        LiveUserData.clipboardHost = null
    }

    @After fun clean() {
        gate?.countDown()
        live?.stopSaving()
        LiveUserData.clipboardHost = null
        phraseFile.delete()
        ShadowToast.reset()
    }

    private fun writerOf(store: ClipboardStore): ExecutorService {
        val field = ClipboardStore::class.java.getDeclaredField("io")
        field.isAccessible = true
        return field.get(store) as ExecutorService
    }

    private fun liveStoreWithBusyWriter(): ClipboardStore {
        val store = ClipboardStore(app.filesDir).apply { load() }.also { live = it; LiveUserData.clipboardHost = it }
        val entered = CountDownLatch(1)
        val held = CountDownLatch(1).also { gate = it }
        writerOf(store).execute {
            entered.countDown()
            held.await(30, TimeUnit.SECONDS)
        }
        assertTrue("precondition: the phrase writer is occupied", entered.await(10, TimeUnit.SECONDS))
        return store
    }

    private fun activity(): PhraseTransferActivity =
        Robolectric.buildActivity(PhraseTransferActivity::class.java).create().get()

    private fun applyImport(target: PhraseTransferActivity, text: String, merge: Boolean) {
        PhraseTransferActivity::class.java
            .getDeclaredMethod("applyImport", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(target, text, merge)
    }

    private fun awaitToast(): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast()?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    @Test(timeout = 60_000) fun an_import_the_writer_cannot_finish_never_holds_the_screen() {
        liveStoreWithBusyWriter()
        val screen = activity()

        val startedAt = System.nanoTime()
        applyImport(screen, "C\t甲\nP\t导入的常用语\n", merge = false)
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(
            "handing over an import waited ${waitedMillis}ms on a writer that had not finished",
            waitedMillis < 2_000,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(
            "nothing may be said about an import while the file has not taken it",
            ShadowToast.getTextOfLatestToast(),
        )

        gate?.countDown()

        assertEquals(
            "the import must be reported only once the file really took it",
            app.getString(R.string.phrase_transfer_toast_import_overwritten),
            awaitToast(),
        )
        assertTrue("导入的常用语" in ClipboardStore(app.filesDir).apply { load() }.phrases())
    }

    @Test(timeout = 60_000) fun an_import_the_file_refused_is_never_reported_as_one_it_took() {
        val store = ClipboardStore(app.filesDir).apply { load() }.also { live = it; LiveUserData.clipboardHost = it }
        val blocker = store.tempFileFor(phraseFile)
        assertTrue("precondition: the import cannot reach the disk", blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())
        val screen = activity()

        applyImport(screen, "C\t甲\nP\t进不去的\n", merge = false)

        assertEquals(
            "an import the file refused must not look to the user like one it took",
            app.getString(R.string.phrase_transfer_toast_import_write_failed),
            awaitToast(),
        )
    }
}
