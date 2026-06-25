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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadTest {

    private fun tempFilesDir(): File =
        File.createTempFile("filesdir", "").apply { delete(); mkdirs() }

    @Test
    fun purgeRemovesModelAndPartAndIsIdempotent() {
        val base = tempFilesDir()
        val gram = ModelDownload.destFile(base)
        val part = ModelDownload.partFile(base)
        val sidecar = File(part.parentFile, "${part.name}.meta")
        gram.parentFile?.mkdirs()
        gram.writeText("model")
        part.writeText("leftover")
        sidecar.writeText("identity")

        assertTrue("first purge removes leftovers", ModelDownload.purge(base))
        assertFalse(gram.exists())
        assertFalse("interrupted .part is cleaned too", part.exists())
        assertFalse("the partial identity sidecar is cleaned too", sidecar.exists())
        assertTrue("second purge still confirms absence", ModelDownload.purge(base))

        base.deleteRecursively()
    }

    @Test
    fun unidentifiedInstalledModelReportsUnknownRatherThanAnUpdate() {
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.modelUpdateAction(
                true,
                null,
                ModelDownload.ValidatorProbe.Reached("remote-etag"),
            ),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.modelUpdateAction(
                true,
                "size:2048",
                ModelDownload.ValidatorProbe.Reached("size:2048"),
            ),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.modelUpdateAction(true, "remote-etag", ModelDownload.ValidatorProbe.Reached(null)),
        )
    }

    @Test
    fun validatorComparisonSeparatesUnknownFromNewer() {
        assertEquals(
            ModelDownload.UpdateCheck.UP_TO_DATE,
            ModelDownload.validatorComparison(local = "etag-1", remote = "etag-1"),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UPDATE,
            ModelDownload.validatorComparison(local = "etag-1", remote = "etag-2"),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.validatorComparison(local = null, remote = "etag-2"),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.validatorComparison(local = "etag-1", remote = null),
        )
        assertEquals(
            ModelDownload.UpdateCheck.UNKNOWN,
            ModelDownload.validatorComparison(local = null, remote = null),
        )
    }

    @Test
    fun sizeDisplayUsesRoundedDecimalMegabytes() {
        assertEquals(1L, ModelDownload.bytesToDisplayMb(1_499_999L))
        assertEquals(2L, ModelDownload.bytesToDisplayMb(1_500_000L))
        assertEquals("exact MB", 100L, ModelDownload.bytesToDisplayMb(100_000_000L))
        assertEquals("zero bytes", 0L, ModelDownload.bytesToDisplayMb(0L))
    }

    @Test
    fun installedResourceSizesFollowTheFilesEachCardActivates() {
        val base = tempFilesDir()
        val downloaded = File(base, "downloaded").apply { mkdirs() }
        val gram = ModelDownload.destFile(base).apply { writeBytes(ByteArray(1_499_999)) }
        File(downloaded, "unrelated.bin").writeBytes(ByteArray(200_000))
        File(downloaded, "${ModelDownload.GRAM_NAME}.part").writeBytes(ByteArray(300_000))
        assertEquals(1_499_999L, ModelDownload.installedGramBytes(base))

        val lengths = listOf(600_000, 700_000, 800_000)
        ModelDownload.DICT_PACK_FILES.zip(lengths).forEach { (name, length) ->
            File(downloaded, name).writeBytes(ByteArray(length))
        }
        ModelDownload.dictZipFile(base).writeBytes(ByteArray(900_000))
        assertEquals(2_100_000L, ModelDownload.installedDictionaryBytes(base))

        gram.writeBytes(ByteArray(1_500_000))
        File(downloaded, ModelDownload.DICT_PACK_FILES.first()).writeBytes(ByteArray(900_000))
        assertEquals(1_500_000L, ModelDownload.installedGramBytes(base))
        assertEquals(2_400_000L, ModelDownload.installedDictionaryBytes(base))
        assertEquals(2L, ModelDownload.bytesToDisplayMb(ModelDownload.installedGramBytes(base)))
        assertEquals(2L, ModelDownload.bytesToDisplayMb(ModelDownload.installedDictionaryBytes(base)))
        base.deleteRecursively()
    }
}
