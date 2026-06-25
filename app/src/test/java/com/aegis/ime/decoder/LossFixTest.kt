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

package com.aegis.ime.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal object FullDictTestAssets {
    const val DICT = "aegis_dict.bin"
    const val T9 = "aegis_t9.bin"
    const val LM = "aegis_lm.bin"
    const val JIANPIN = "aegis_jianpin.bin"

    val productionNames = listOf(DICT, T9, LM, JIANPIN)
    private val configuredDirectory = System.getenv("AEGIS_FULLDICT_DIR")
        ?.let { File(it) }
    val directory: File = configuredDirectory ?: File("src/main/assets")

    fun file(name: String): File = File(directory, name)

    fun available(vararg required: File): Boolean =
        available(required.map { it.name }, configuredDirectory != null) { file(it).exists() }

    fun available(
        required: Collection<String>,
        configured: Boolean,
        exists: (String) -> Boolean,
    ): Boolean {
        if (configured) {
            val missing = productionNames.filterNot(exists)
            if (missing.isNotEmpty()) {
                throw AssertionError("AEGIS_FULLDICT_DIR missing production assets: ${missing.joinToString()}")
            }
        }
        return required.all(exists)
    }
}

class LossFixTest {

    @Test fun explicitFullDictDirectoryFailsClosedForEveryMissingAsset() {
        for (missing in FullDictTestAssets.productionNames) {
            val error = assertThrows(AssertionError::class.java) {
                FullDictTestAssets.available(
                    FullDictTestAssets.productionNames,
                    configured = true,
                ) { it != missing }
            }
            assertTrue(error.message.orEmpty().contains(missing))
        }
        assertFalse(
            FullDictTestAssets.available(
                FullDictTestAssets.productionNames,
                configured = false,
            ) { false },
        )
    }
}
