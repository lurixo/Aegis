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

package com.aegis.ime.user

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ClipboardPhraseLockTest {

    private val dirs = ArrayList<File>()
    private val gates = ArrayList<CountDownLatch>()

    @After fun letGo() {
        gates.forEach { it.countDown() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newDir(): File = Files.createTempDirectory("phraselock").toFile().also { dirs += it }

    private fun store(dir: File) = ClipboardStore(dir).apply { load() }

    private fun writer(store: ClipboardStore): ExecutorService {
        val field = ClipboardStore::class.java.getDeclaredField("io")
        field.isAccessible = true
        return field.get(store) as ExecutorService
    }

    private fun phraseList(store: ClipboardStore): Any {
        val field = ClipboardStore::class.java.getDeclaredField("phraseCats")
        field.isAccessible = true
        return checkNotNull(field.get(store))
    }

    private fun settle(t: Thread, wanted: Set<Thread.State>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (!t.isAlive || t.state in wanted) return
            Thread.onSpinWait()
        }
    }

    @Test fun a_phrase_edit_waits_while_another_thread_holds_the_phrase_list() {
        val dir = newDir()
        val s = store(dir)
        val started = CountDownLatch(1)
        val editor = Thread {
            started.countDown()
            s.addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("等着写"))
        }.apply { isDaemon = true }

        synchronized(phraseList(s)) {
            editor.start()
            assertTrue("precondition: the editing thread ran", started.await(30, TimeUnit.SECONDS))
            settle(editor, setOf(Thread.State.BLOCKED))
            assertEquals(
                "a phrase edit must wait for the list rather than walk over it",
                Thread.State.BLOCKED,
                editor.state,
            )
            assertEquals("and it must not have changed anything yet", emptyList<String>(), s.phrases())
        }

        editor.join(TimeUnit.SECONDS.toMillis(30))
        assertFalse("the edit must go through once the list is free", editor.isAlive)
        assertEquals(listOf("等着写"), s.phrases())
    }
}
