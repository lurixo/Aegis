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

import org.junit.Assert.assertFalse
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
}
