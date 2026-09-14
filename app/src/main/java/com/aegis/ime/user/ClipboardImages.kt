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

import android.content.ClipData
import android.content.ContentResolver

object ClipboardImages {

    private val extensions = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif",
        "image/webp" to "webp", "image/heic" to "heic", "image/heif" to "heif",
        "image/avif" to "avif", "image/bmp" to "bmp", "image/x-ms-bmp" to "bmp",
    )

    internal fun isImageFileName(name: String): Boolean =
        name.substringAfterLast('.', "") in extensions.values && ClipEntry.isSidecarHash(name.substringBeforeLast('.'))

    fun imageMimeType(resolver: ContentResolver, clip: ClipData, item: ClipData.Item): String? {
        val uri = item.uri ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val resolved = runCatching { resolver.getType(uri) }.getOrNull()
        if (resolved?.startsWith("image/") == true) return resolved
        return (0 until clip.description.mimeTypeCount).asSequence()
            .map { clip.description.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
    }
}
