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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PrefsCodecTest {

    private fun roundTrip(key: String, value: Any): Pair<String, PrefsCodec.Value> {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { PrefsCodec.writeEntry(it, key, value) }
        return PrefsCodec.readEntry(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test
    fun everySupportedTypeRoundTripsAsOneRecord() {
        assertEquals("flag" to PrefsCodec.Value.Bool(true), roundTrip("flag", true))
        assertEquals("count" to PrefsCodec.Value.Integer(42), roundTrip("count", 42))
        assertEquals("stamp" to PrefsCodec.Value.LongVal(1_700_000_000_000L), roundTrip("stamp", 1_700_000_000_000L))
        assertEquals("ratio" to PrefsCodec.Value.FloatVal(1.5f), roundTrip("ratio", 1.5f))
        assertEquals("layout" to PrefsCodec.Value.Str("alpha"), roundTrip("layout", "alpha"))
        assertEquals(
            "symbols" to PrefsCodec.Value.StrSet(setOf("！", "？", "。")),
            roundTrip("symbols", setOf("！", "？", "。")),
        )
    }

    @Test
    fun supportDetectionRejectsUnknownOrMixedValues() {
        assertTrue(PrefsCodec.supported("yes"))
        assertTrue(PrefsCodec.supported(setOf("a", "b")))
        assertFalse(PrefsCodec.supported(Any()))
        assertFalse(PrefsCodec.supported(null))
        assertFalse(PrefsCodec.supported(setOf("a", 1)))
    }

    @Test
    fun stringsCrossEveryOldEightMiBBoundaryMilestone() {
        val oldLimit = 8 * 1024 * 1024
        for (length in listOf(oldLimit - 1, oldLimit, oldLimit + 1, oldLimit * 2)) {
            val value = "x".repeat(length)
            assertEquals("string length $length", "large" to PrefsCodec.Value.Str(value), roundTrip("large", value))
        }
    }

    @Test
    fun trailingRecordDataIsRejected() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            PrefsCodec.writeEntry(output, "key", "value")
            output.writeByte(1)
        }
        try {
            PrefsCodec.readEntry(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            fail("expected corrupt preference record")
        } catch (_: BackupCorruptException) {
        }
    }

    @Test
    fun negativeLengthsUnknownTypesAndTruncatedStringsAreRejected() {
        val malformed = listOf(
            byteArrayOf(-1, -1, -1, -1),
            byteArrayOf(0, 0, 0, 1, 0, 'k'.code.toByte(), 99),
            byteArrayOf(0, 0, 0, 1, 0, 'k'.code.toByte(), 'S'.code.toByte(), 0, 0, 0, 2, 0, 'x'.code.toByte()),
        )
        for (bytes in malformed) {
            try {
                PrefsCodec.readEntry(DataInputStream(ByteArrayInputStream(bytes)))
                fail("expected corrupt preference record")
            } catch (_: Exception) {
            }
        }
    }
}
