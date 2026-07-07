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

package com.aegis.ime.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsCodecTest {

    @Test fun round_trips_every_supported_type() {
        val source = linkedMapOf<String, Any?>(
            "flag" to true,
            "count" to 42,
            "stamp" to 1_700_000_000_000L,
            "ratio" to 1.5f,
            "layout" to "alpha",
            "symbols" to setOf("！", "？", "。"),
        )
        val decoded = PrefsCodec.decode(PrefsCodec.encode(source))

        assertEquals(6, decoded.size)
        assertEquals(PrefsCodec.Value.Bool(true), decoded["flag"])
        assertEquals(PrefsCodec.Value.Integer(42), decoded["count"])
        assertEquals(PrefsCodec.Value.LongVal(1_700_000_000_000L), decoded["stamp"])
        assertEquals(PrefsCodec.Value.FloatVal(1.5f), decoded["ratio"])
        assertEquals(PrefsCodec.Value.Str("alpha"), decoded["layout"])
        assertEquals(PrefsCodec.Value.StrSet(setOf("！", "？", "。")), decoded["symbols"])
    }

    @Test fun skips_unsupported_value_types() {
        val decoded = PrefsCodec.decode(PrefsCodec.encode(mapOf("keep" to "yes", "drop" to Any(), "null" to null)))
        assertTrue(decoded.containsKey("keep"))
        assertFalse(decoded.containsKey("drop"))
        assertFalse(decoded.containsKey("null"))
    }

    @Test fun round_trips_a_value_longer_than_64k() {
        val big = "x".repeat(70_000)
        val decoded = PrefsCodec.decode(PrefsCodec.encode(mapOf("custom_symbols" to big)))
        assertEquals(PrefsCodec.Value.Str(big), decoded["custom_symbols"])
    }

    @Test fun round_trips_an_empty_map() {
        assertTrue(PrefsCodec.decode(PrefsCodec.encode(emptyMap())).isEmpty())
    }
}
