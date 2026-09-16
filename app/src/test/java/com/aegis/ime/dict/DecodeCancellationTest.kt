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

import com.aegis.ime.decoder.EngineFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeCancellationTest {

    @Test fun checkpoints_do_nothing_outside_a_cancellable_attempt() {
        repeat(1_000) { DecodeCancellation.checkpoint() }
        val inner = DecodeCancellation.attempt({ false }) { 3 }
        assertEquals(3, inner?.getOrNull())
        DecodeCancellation.checkpoint()
    }

    @Test fun a_cancelled_attempt_yields_nothing_while_real_failures_are_kept() {
        var polls = 0
        val cancelled = DecodeCancellation.attempt({ ++polls >= 2 }) {
            DecodeCancellation.checkpoint()
            DecodeCancellation.checkpoint()
            error("unreachable")
        }
        assertNull("a cancelled attempt has no result to deliver", cancelled)
        assertEquals(2, polls)
        val boom = IllegalStateException("boom")
        val failed = DecodeCancellation.attempt({ false }) { throw boom }
        assertSame(boom, failed?.exceptionOrNull())
    }

    @Test fun the_token_belongs_to_the_thread_that_runs_the_attempt() {
        var seenElsewhere = 0
        val result = DecodeCancellation.attempt({ true }) {
            val other = Thread {
                DecodeCancellation.checkpoint()
                seenElsewhere++
            }
            other.start()
            other.join()
            DecodeCancellation.checkpoint()
            seenElsewhere
        }
        assertNull(result)
        assertEquals("another thread's checkpoint is not cancelled by this attempt", 1, seenElsewhere)
    }

    @Test fun a_prefix_scan_stops_inside_its_key_loop() {
        val rows = (0 until 1_500).map { EngineFixture.Row("sh" + it.toString().padStart(4, '0'), "词$it", 1_500 - it) }
        val dict = EngineFixture.build(rows)
        val expected = dict.prefixByFreq("sh", 5)
        var polls = 0
        val cancelled = DecodeCancellation.attempt({ ++polls > 2 }) { dict.prefixByFreq("sh", 5) }
        assertNull("the scan over 1500 keys is interrupted", cancelled)
        assertEquals("it polled every few hundred keys", 3, polls)
        var fullPolls = 0
        val full = DecodeCancellation.attempt({ fullPolls++; false }) { dict.prefixByFreq("sh", 5) }
        assertEquals(expected, full?.getOrNull())
        assertTrue("a full scan of 1500 keys polls a handful of times, not per key: $fullPolls", fullPolls in 3..16)
    }
}
