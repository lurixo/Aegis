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
import java.util.concurrent.atomic.AtomicBoolean

object ModelDownload {

    const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"

    const val REPO_URL = "https://github.com/amzxyz/RIME-LMDG"

    const val GRAM_NAME = "wanxiang-lts-zh-hans.gram"

    const val VALIDATOR_PREF = "gram_validator"

    fun destFile(filesDir: File): File = File(File(filesDir, "downloaded"), GRAM_NAME)

    fun partFile(filesDir: File): File = File(File(filesDir, "downloaded"), "$GRAM_NAME.part")

    fun isDownloaded(filesDir: File): Boolean = destFile(filesDir).let { it.exists() && it.length() > 1024 }

    data class DownloadResult(val ok: Boolean, val validator: String?)

    private val inFlight = AtomicBoolean(false)

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): DownloadResult {
        if (!inFlight.compareAndSet(false, true)) return DownloadResult(false, null)
        var conn: HttpURLConnection? = null
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            dest.parentFile?.mkdirs()
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
            inFlight.set(false)
        }
    }

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

    fun updateAvailable(local: String?, remote: String?): Boolean = !(remote != null && remote == local)

    fun purge(filesDir: File): Boolean {
        val a = destFile(filesDir).delete()
        val b = partFile(filesDir).delete()
        return a || b
    }
}
