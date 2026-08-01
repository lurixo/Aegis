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
import org.junit.Test
import java.io.File
import java.io.InputStream

class UserDictImportTest {

    @Test
    fun stagingCrossesEveryOldFourMiBBoundaryMilestone() {
        val oldLimit = 4L * 1024L * 1024L
        for (size in listOf(oldLimit - 1L, oldLimit, oldLimit + 1L, oldLimit * 2L)) {
            val staged = File.createTempFile("imp-stage", ".txt")
            assertTrue("staging $size bytes", UserDictImport.stage(SizedInputStream(size), staged))
            assertEquals(size, staged.length())
            staged.delete()
        }
    }

    private class SizedInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            remaining--
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }
}
