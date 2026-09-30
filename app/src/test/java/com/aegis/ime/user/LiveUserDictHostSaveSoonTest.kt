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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveUserDictHostSaveSoonTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun a_learned_word_reaches_disk_while_the_keyboard_is_still_up() {
        val model = UserModel()
        val db = File(tmp.root, "userdb.txt")
        val saved = CountDownLatch(1)
        val host = LiveUserDictHost(model, db, onSaved = { written, _ -> if (written != null) saved.countDown() })
        try {
            model.record(null, "其规划", 1_000L)
            host.saveSoon()
            assertTrue("a learned word must be written without a lifecycle event", saved.await(10, TimeUnit.SECONDS))
            assertTrue(db.readText().contains("其规划"))
        } finally {
            host.stopSaving()
        }
    }

    @Test fun picks_close_together_share_one_write() {
        val model = UserModel()
        val db = File(tmp.root, "userdb.txt")
        val writes = AtomicInteger()
        val saved = CountDownLatch(1)
        val host = LiveUserDictHost(model, db, onSaved = { written, _ ->
            if (written != null) {
                writes.incrementAndGet()
                saved.countDown()
            }
        })
        try {
            model.record(null, "甲乙", 1_000L)
            host.saveSoon()
            model.record(null, "丙丁", 1_000L)
            host.saveSoon()
            assertTrue(saved.await(10, TimeUnit.SECONDS))
            val drained = CountDownLatch(1)
            assertTrue(host.handOff { drained.countDown() })
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
            val text = db.readText()
            assertTrue(text.contains("甲乙") && text.contains("丙丁"))
        } finally {
            host.stopSaving()
        }
    }
}
