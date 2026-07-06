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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelDownloadTest {

    private fun tempFilesDir(): File =
        File.createTempFile("filesdir", "").apply { delete(); mkdirs() }

    @Test
    fun purgeRemovesModelAndPartAndIsIdempotent() {
        val base = tempFilesDir()
        val gram = ModelDownload.destFile(base)
        val part = ModelDownload.partFile(base)
        gram.parentFile?.mkdirs()
        gram.writeText("model")
        part.writeText("leftover")

        assertTrue("first purge removes leftovers", ModelDownload.purge(base))
        assertFalse(gram.exists())
        assertFalse("interrupted .part is cleaned too", part.exists())
        assertFalse("second purge has nothing to remove", ModelDownload.purge(base))

        base.deleteRecursively()
    }

    @Test
    fun installInProgressBracketsTheDictZipAndAnyPart() {
        val base = tempFilesDir()
        ModelDownload.DICT_PACK_FILES.forEach { File(base, "downloaded/$it").apply { parentFile?.mkdirs(); writeText("bin") } }
        assertFalse("settled tree is not 'installing'", ModelDownload.installInProgress(base))

        val zip = ModelDownload.dictZipFile(base).apply { writeText("zip") }
        assertTrue("dict zip present → install in flight", ModelDownload.installInProgress(base))
        zip.delete()
        assertFalse(ModelDownload.installInProgress(base))

        val zipPart = ModelDownload.dictPartFile(base).apply { writeText("part") }
        assertTrue("zip .part present → still downloading", ModelDownload.installInProgress(base))
        zipPart.delete()

        val gramPart = ModelDownload.partFile(base).apply { writeText("part") }
        assertTrue("gram .part present → model downloading", ModelDownload.installInProgress(base))
        gramPart.delete()
        assertFalse(ModelDownload.installInProgress(base))

        base.deleteRecursively()
    }

    @Test
    fun updateAvailableOnlySuppressedByConfirmedMatch() {
        assertFalse(ModelDownload.updateAvailable(local = "etag-1", remote = "etag-1"))
        assertTrue(ModelDownload.updateAvailable(local = "etag-1", remote = "etag-2"))
        assertTrue(ModelDownload.updateAvailable(local = null, remote = "etag-2"))
        assertTrue(ModelDownload.updateAvailable(local = "etag-1", remote = null))
        assertTrue(ModelDownload.updateAvailable(local = null, remote = null))
    }

    @Test
    fun sizeDisplayIsDecimalMegabytesNotBinaryMebibytes() {
        assertEquals("gram model reads as decimal MB", 421L, ModelDownload.bytesToDisplayMb(420_556_844L))
        assertEquals("dict pack reads as decimal MB", 255L, ModelDownload.bytesToDisplayMb(254_961_874L))

        assertEquals("gram was 401 under the old MiB divisor", 401L, 420_556_844L / 1_048_576L)
        assertEquals("dict was 243 under the old MiB divisor", 243L, 254_961_874L / 1_048_576L)
        assertNotEquals(420_556_844L / 1_048_576L, ModelDownload.bytesToDisplayMb(420_556_844L))

        assertEquals("rounds up past .5", 255L, ModelDownload.bytesToDisplayMb(254_500_000L))
        assertEquals("rounds down below .5", 254L, ModelDownload.bytesToDisplayMb(254_499_999L))
        assertEquals("exact MB", 100L, ModelDownload.bytesToDisplayMb(100_000_000L))
        assertEquals("zero bytes", 0L, ModelDownload.bytesToDisplayMb(0L))
    }
}
