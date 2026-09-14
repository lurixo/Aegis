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
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.aegis.ime.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class ClipboardImageTooLargeException : IOException("clipboard image exceeds 20 MiB")

object ClipboardImages {

    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private const val MAX_IMAGE_PIXELS = 100_000_000L
    private val extensions = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif",
        "image/webp" to "webp", "image/heic" to "heic", "image/heif" to "heif",
        "image/avif" to "avif", "image/bmp" to "bmp", "image/x-ms-bmp" to "bmp",
    )

    internal data class Imported(val file: File, val hash: String, val mimeType: String, val created: Boolean)

    internal fun extension(mimeType: String): String? = extensions[mimeType]

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

    fun uri(context: Context, entry: ClipEntry): Uri? {
        val image = entry.imageFile() ?: return null
        return runCatching { FileProvider.getUriForFile(context, context.packageName + ".clipboard.images", image) }.getOrNull()
    }

    fun clipData(context: Context, entry: ClipEntry): ClipData? {
        val uri = uri(context, entry) ?: return null
        val mime = entry.mimeType ?: return null
        return ClipData(context.getString(R.string.clip_image_label), arrayOf(mime), ClipData.Item(uri))
    }

    internal fun importImage(directory: File, resolver: ContentResolver, uri: Uri, declaredType: String?): Imported {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException("clipboard image must use a content URI")
        if (declaredType != null && !declaredType.startsWith("image/")) throw IOException("clipboard content is not an image")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("clipboard image directory creation failed")
        val staged = File.createTempFile("capture-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val source = resolver.openInputStream(uri) ?: throw IOException("clipboard image could not be read")
            source.use { input ->
                FileOutputStream(staged).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > MAX_IMAGE_BYTES) throw ClipboardImageTooLargeException()
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                    out.fd.sync()
                }
            }
            val mime = validateFile(staged)
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val dest = File(directory, "$hash.${extension(mime)}")
            val created = !dest.isFile
            if (created) AtomicFileSwap.replace(staged, dest)
            return Imported(dest, hash, mime, created)
        } finally {
            staged.delete()
        }
    }

    internal fun validateFile(file: File, expectedMime: String? = null): String {
        if (file.length() > MAX_IMAGE_BYTES) throw ClipboardImageTooLargeException()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val mime = options.outMimeType ?: throw IOException("clipboard image format is unreadable")
        if (extension(mime) == null || options.outWidth <= 0 || options.outHeight <= 0 ||
            options.outWidth.toLong() * options.outHeight > MAX_IMAGE_PIXELS ||
            (expectedMime != null && mime != expectedMime)) throw IOException("clipboard image format is unsupported")
        return mime
    }

    fun thumbnail(entry: ClipEntry, maxPixels: Int): Bitmap? = runCatching {
        val file = entry.imageFile() ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 ||
            options.outWidth.toLong() * options.outHeight > MAX_IMAGE_PIXELS) return null
        val limit = maxOf(1, maxPixels)
        var sample = 1
        while (options.outWidth / sample > limit || options.outHeight / sample > limit) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        BitmapFactory.decodeFile(file.path, options)
    }.getOrNull()
}
