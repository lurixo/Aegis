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
import android.content.ClipDescription
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * U22 image clipboard: copies captured clip images into filesDir/clipboard_images (for persistence + a
 * controllable FileProvider URI) and decodes down-sampled thumbnails for the panel. A single-image byte cap
 * keeps a huge paste from filling storage (U9 removed only the *count* limit). No network, on-device only.
 */
class ClipImageStore(private val dir: File) {

    private fun imageDir(): File = File(dir, "clipboard_images").apply { mkdirs() }

    /**
     * Copy the clip image at [uri] into the image dir, named with [seed]. Returns the saved absolute path,
     * or null if it can't be read or exceeds [MAX_BYTES]. Call OFF the main thread (does I/O).
     */
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
                        if (total > MAX_BYTES) return@runCatching false // too big — bail (file deleted below)
                        o.write(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!ok) { runCatching { out.delete() }; return null }
        prune() // U22/B3: bound the dir so repeated image copies can't grow it without limit
        return out.absolutePath
    }

    /**
     * E3: bound the image dir primarily by TOTAL BYTES ([MAX_TOTAL_BYTES]) — with a large file-count backstop
     * ([MAX_IMAGES]) — deleting oldest first. Bounding by bytes (not a flat count) lets many small images
     * coexist while a few large ones can't fill storage. A stale history entry then shows a graceful fallback.
     */
    private fun prune() {
        var files = runCatching { imageDir().listFiles()?.sortedBy { it.lastModified() } }.getOrNull() ?: return
        val excessCount = files.size - MAX_IMAGES
        if (excessCount > 0) { files.take(excessCount).forEach { runCatching { it.delete() } }; files = files.drop(excessCount) }
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > MAX_TOTAL_BYTES && i < files.size) {
            total -= files[i].length(); runCatching { files[i].delete() }; i++
        }
    }

    /** Decode a DOWN-SAMPLED thumbnail (longest side ≈ [maxPx]) — never the full bitmap (OOM guard). */
    fun thumbnail(path: String, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > maxPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /**
     * M-1: an entry is a REAL image only if its path is an existing file directly inside our
     * clipboard_images dir — so a text clip that merely starts with the image marker (or a marker whose
     * file was pruned) is NOT rendered/pasted as an image. canonicalFile also guards path traversal.
     */
    fun isStoredImage(path: String): Boolean = runCatching {
        val f = File(path).canonicalFile
        f.isFile && f.parentFile?.canonicalFile == imageDir().canonicalFile
    }.getOrDefault(false)

    fun delete(path: String) { runCatching { File(path).delete() } }

    /** Wipe every saved image (used when the whole history is cleared). */
    fun clear() { runCatching { imageDir().listFiles()?.forEach { it.delete() } } }

    /** Whether a single clip would exceed the cap, given a known byte size (-1 = unknown → allow, save guards). */
    fun withinCap(bytes: Long): Boolean = bytes < 0 || bytes <= MAX_BYTES

    private fun extFromMime(mime: String?): String = when (mime) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "png"
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024          // E3: single-image cap 8 → 32 MB (huge ones still toast)
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024   // E3: bound the whole image dir by total bytes
        private const val MAX_IMAGES = 1000              // E3: count backstop only (was the primary 100 cap)

        /** image/png etc. inferred from the saved file's extension (for commitContent's ClipDescription). */
        fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }

        /**
         * BUG3: whether a field that advertises [accepts] (EditorInfo.contentMimeTypes) can take an image of
         * [mime] via commitContent. Empty/absent advertisement → not supported (the field accepts paste but not
         * the rich-content API) → the caller falls back to the system clipboard. [accepts] entries may be
         * wildcard patterns, so [mime] (the concrete type) is passed FIRST to compareMimeTypes.
         */
        fun imageAccepted(accepts: Array<String>, mime: String): Boolean =
            accepts.any { ClipDescription.compareMimeTypes(mime, it) }

        /**
         * BUG3: the delivery decision for an image. commitContent is used only when the field accepts it AND
         * the commit succeeds; otherwise the caller must fall back to the system clipboard (so a field that
         * only supports paste still gets the image). Returns true = committed; false = fall back. [commit] is
         * not even invoked when the field doesn't advertise image support (subcase A).
         */
        inline fun deliverImage(accepts: Array<String>, mime: String, commit: () -> Boolean): Boolean =
            imageAccepted(accepts, mime) && commit()

        /** BUG3: whether an incoming clipboard [clipUri] is the image WE just placed on the system clipboard
         *  ([selfWrite]) — so the clip listener skips re-recording it as a duplicate. Null-safe. */
        fun isSelfWrite(clipUri: Uri?, selfWrite: Uri?): Boolean = clipUri != null && clipUri == selfWrite

        /** BUG3: a system-clipboard [ClipData] carrying the image at [uri] (a content:// FileProvider URI).
         *  ClipData.newUri records the URI; the ClipboardService grants read to the pasting app at paste time
         *  (our FileProvider has grantUriPermissions=true), so "long-press → paste" can read the image. */
        fun imageClip(resolver: ContentResolver, uri: Uri): ClipData = ClipData.newUri(resolver, "clip image", uri)
    }
}
