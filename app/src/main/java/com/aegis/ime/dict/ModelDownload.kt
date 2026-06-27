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
import java.net.HttpURLConnection
import java.net.URL

/** Streams the optional enhancement model into filesDir/downloaded/ (picked up by the IME next session). */
object ModelDownload {

    /** Public wanxiang octagram LTS release. */
    const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"

    const val GRAM_NAME = "wanxiang-lts-zh-hans.gram"

    /** SharedPreferences key (prefs "aegis") storing the downloaded file's remote validator. */
    const val VALIDATOR_PREF = "gram_validator"

    fun destFile(filesDir: File): File = File(File(filesDir, "downloaded"), GRAM_NAME)

    /** The in-flight temp file for [destFile] — a leftover means a previous download was interrupted. */
    fun partFile(filesDir: File): File = File(File(filesDir, "downloaded"), "$GRAM_NAME.part")

    fun isDownloaded(filesDir: File): Boolean = destFile(filesDir).let { it.exists() && it.length() > 1024 }

    /** Outcome of [download]: success flag + the server validator (ETag/Last-Modified) of what landed. */
    data class DownloadResult(val ok: Boolean, val validator: String?)

    /**
     * Download [url] to [dest] (via a .part temp + atomic rename), reporting (bytesDone, total).
     * Blocking — call off the main thread. The atomic rename onto the single [dest] path means a
     * re-download / 更新 overwrites in place rather than accumulating duplicates. Returns the server
     * validator so the caller can record it and later detect a newer remote release.
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): DownloadResult {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            if (conn.responseCode !in 200..299) return DownloadResult(false, null)
            val validator = conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            dest.delete()
            DownloadResult(tmp.renameTo(dest), validator)
        } catch (e: Exception) {
            tmp.delete()
            DownloadResult(false, null)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * HEAD [url] and return its current validator (ETag, else Last-Modified), or null if the request
     * fails or the server exposes neither. Blocking — call off the main thread.
     */
    fun remoteValidator(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            if (conn.responseCode !in 200..299) null
            else conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified")
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Whether to offer an 更新 (update). True unless we positively confirmed the remote validator
     * equals the local one. So: differing validators → update; remote unknown/unreachable → fall back
     * to allowing a forced re-download; only an exact match suppresses it.
     */
    fun updateAvailable(local: String?, remote: String?): Boolean = !(remote != null && remote == local)

    /** Thorough delete: the model file plus any interrupted .part leftover (callers also clear the
     *  stored validator). Returns true if anything was removed. Idempotent. */
    fun purge(filesDir: File): Boolean {
        val a = destFile(filesDir).delete()
        val b = partFile(filesDir).delete()
        return a || b
    }
}
