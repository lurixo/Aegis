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

object EngineAssets {

    val ASSET_NAMES: List<String> = ModelDownload.DICT_PACK_FILES + ModelDownload.GRAM_NAME

    fun downloadedOverride(downloadedDir: File, name: String, minBytes: Long = 1L): File? =
        if (
            name in ModelDownload.DICT_PACK_FILES &&
            downloadedDir.parentFile?.let { ModelDownload.unmarkedDictionaryRecoveryRequired(it) } == true
        ) {
            null
        } else {
            File(downloadedDir, name).takeIf { it.exists() && it.length() >= minBytes }
        }

    fun signature(downloadedDir: File): String =
        ASSET_NAMES.joinToString(";") { name ->
            val f = File(downloadedDir, name)
            if (f.exists()) "$name=${f.lastModified()}/${f.length()}" else "$name=0/0"
        }

    fun needsReload(builtSig: String, currentSig: String): Boolean = builtSig != currentSig
}
