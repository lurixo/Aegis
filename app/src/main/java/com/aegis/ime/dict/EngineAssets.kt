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

/**
 * debug.16 (engine hot-reload): a content signature of the DOWNLOADED engine assets, so the IME can notice a
 * freshly-downloaded / updated / deleted pack and rebuild the decode engine on the next field focus instead of
 * waiting for an IME cold start. The tracked files are exactly the ones the engine consumes from `downloaded/`
 * via `downloadedOverride` ([ModelDownload.DICT_PACK_FILES]) plus the optional octagram model
 * ([ModelDownload.GRAM_NAME]); the bundled-asset `aegis_lm.bin` is never downloaded, so it is not tracked.
 *
 * The logic is a pure function of the filesystem snapshot (mtime + size of each file) so it is unit-testable
 * and so any byte/timestamp change flips the signature.
 */
object EngineAssets {

    /** The downloadable files the engine loads from `downloaded/` (single source of truth = the cards' targets). */
    val ASSET_NAMES: List<String> = ModelDownload.DICT_PACK_FILES + ModelDownload.GRAM_NAME

    /** Return the downloaded resource file the engine should prefer, or null when it is absent/incomplete. */
    fun downloadedOverride(downloadedDir: File, name: String, minBytes: Long = 1L): File? =
        File(downloadedDir, name).takeIf { it.exists() && it.length() >= minBytes }

    /**
     * A signature of [ASSET_NAMES] in [downloadedDir]: `name=mtime/size` per file (`name=0/0` when absent),
     * joined. A new download, an in-place update, or a delete each changes the string. Pure over the snapshot.
     */
    fun signature(downloadedDir: File): String =
        ASSET_NAMES.joinToString(";") { name ->
            val f = File(downloadedDir, name)
            if (f.exists()) "$name=${f.lastModified()}/${f.length()}" else "$name=0/0"
        }

    /** Rebuild the engine iff the downloaded-asset signature changed since the live engine was last built. */
    fun needsReload(builtSig: String, currentSig: String): Boolean = builtSig != currentSig
}
