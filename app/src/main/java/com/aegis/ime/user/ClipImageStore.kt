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

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

class ClipImageStore(private val dir: File) {

    private fun imageDir(): File = File(dir, "clipboard_images").apply { mkdirs() }

    fun save(resolver: ContentResolver, uri: Uri, seed: Long): String? {
        val out = File(imageDir(), "$seed.${extFromMime(resolver.getType(uri))}")
        val ok = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { o ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@runCatching false
                        o.write(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!ok) { runCatching { out.delete() }; return null }
        prune()
        return out.absolutePath
    }

    private fun prune() {
        val files = runCatching { imageDir().listFiles()?.sortedBy { it.lastModified() } }.getOrNull() ?: return
        val excess = files.size - MAX_IMAGES
        if (excess > 0) files.take(excess).forEach { runCatching { it.delete() } }
    }

    fun thumbnail(path: String, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > maxPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun isStoredImage(path: String): Boolean = runCatching {
        val f = File(path).canonicalFile
        f.isFile && f.parentFile?.canonicalFile == imageDir().canonicalFile
    }.getOrDefault(false)

    fun delete(path: String) { runCatching { File(path).delete() } }

    fun clear() { runCatching { imageDir().listFiles()?.forEach { it.delete() } } }

    fun withinCap(bytes: Long): Boolean = bytes < 0 || bytes <= MAX_BYTES

    private fun extFromMime(mime: String?): String = when (mime) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "png"
    }

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_IMAGES = 100

        fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }
}
