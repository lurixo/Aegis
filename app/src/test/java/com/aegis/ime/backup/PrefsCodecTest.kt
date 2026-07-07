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
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

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

    @Test fun round_trips_a_value_far_beyond_eight_megabytes() {
        val big = "符".repeat(4 * 1024 * 1024)
        val decoded = PrefsCodec.decode(PrefsCodec.encode(mapOf("custom_symbols" to big, "layout" to "alpha")))
        assertEquals(PrefsCodec.Value.Str(big), decoded["custom_symbols"])
        assertEquals(PrefsCodec.Value.Str("alpha"), decoded["layout"])
    }

    @Test fun round_trips_a_string_set_member_beyond_eight_megabytes() {
        val big = "x".repeat(9 * 1024 * 1024)
        val decoded = PrefsCodec.decode(PrefsCodec.encode(mapOf("bag" to setOf(big, "small"))))
        assertEquals(PrefsCodec.Value.StrSet(setOf(big, "small")), decoded["bag"])
    }

    @Test fun a_string_length_beyond_the_blob_is_corrupt() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(1)
            out.writeInt(3)
            out.write("key".toByteArray())
            out.writeByte('S'.code)
            out.writeInt(Int.MAX_VALUE)
            out.write(byteArrayOf(1, 2, 3))
        }
        assertCorrupt { PrefsCodec.decode(bos.toByteArray()) }
    }

    @Test fun a_negative_string_length_is_corrupt() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(1)
            out.writeInt(-5)
        }
        assertCorrupt { PrefsCodec.decode(bos.toByteArray()) }
    }

    @Test fun an_unknown_value_type_is_corrupt() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(1)
            out.writeInt(3)
            out.write("key".toByteArray())
            out.writeByte('?'.code)
        }
        assertCorrupt { PrefsCodec.decode(bos.toByteArray()) }
    }

    @Test fun round_trips_an_empty_map() {
        assertTrue(PrefsCodec.decode(PrefsCodec.encode(emptyMap())).isEmpty())
    }

    private fun assertCorrupt(block: () -> Unit) {
        try {
            block()
            fail("expected BackupCorruptException")
        } catch (_: BackupCorruptException) {
        }
    }
}
