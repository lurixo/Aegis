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
package com.aegis.ime.ime

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InFlightTapTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class Host : ImeHost {
        val commits = ArrayList<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = ""
    }

    @Test fun a_candidate_tap_lands_while_the_next_decode_is_still_on_the_worker() {
        val workerQ = ArrayDeque<Runnable>()
        val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) })
        fun drain() {
            while (workerQ.isNotEmpty() || mainQ.isNotEmpty()) {
                while (workerQ.isNotEmpty()) workerQ.removeFirst().run()
                while (mainQ.isNotEmpty()) mainQ.removeFirst().run()
            }
        }
        val engine = DictEngine(
            EngineFixture.build(
                listOf(EngineFixture.Row("ni", "你", 900), EngineFixture.Row("hao", "好", 900)),
            ),
            null,
            null,
        )
        val host = Host()
        val c = KeyboardController(host, engine, lane).apply { attachView(InputView(ctx)) }
        c.switchTextLayoutForTest(nine = false)
        "ni".forEach { ch -> c.onKey(Key(ch.toString(), output = ch.toString())) }
        drain()
        val idx = c.candidateWords().indexOf("你")
        assertTrue("the fixture offers 你 for ni", idx >= 0)

        c.onKey(Key("h", output = "h"))
        assertTrue("the next decode is genuinely in flight", lane.pending)

        c.onPickCandidate(idx)
        assertTrue(
            "the tap must act on the list that is on screen",
            "你" in c.preeditForTest(),
        )
        drain()
        assertTrue("the late decode cannot undo the pick", "你" in c.preeditForTest())
    }
}
