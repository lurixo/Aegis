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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): DownloadResult {
        val key = dest.absolutePath
        if (!inFlight.add(key)) return DownloadResult(false, null)
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
            inFlight.remove(key)
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


    const val DICT_URL =
        "https://github.com/lurixo/aegis/releases/download/dict-full/aegis_dict_pack_debug13.zip"

    const val DICT_REPO_URL = "https://github.com/lurixo/aegis/releases"

    const val DICT_NAME = "aegis_dict_pack_debug13.zip"
    const val DICT_SHA256 = "d048435631623513a9d6a6ccb877a6ba06fb15a293ade72bb101d1e0d4feaa60"

    val DICT_PACK_FILES = listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")

    const val DICT_VALIDATOR_PREF = "dict_validator"

    private fun downloadedDir(filesDir: File) = File(filesDir, "downloaded")
    fun dictZipFile(filesDir: File): File = File(downloadedDir(filesDir), DICT_NAME)
    fun dictPartFile(filesDir: File): File = File(downloadedDir(filesDir), "$DICT_NAME.part")

    fun isDictDownloaded(filesDir: File): Boolean =
        DICT_PACK_FILES.all { File(downloadedDir(filesDir), it).let { f -> f.exists() && f.length() > 1024 } }

    fun installDictPack(filesDir: File): Boolean {
        val zip = dictZipFile(filesDir)
        if (!zip.exists()) return false
        if (!sha256Of(zip).equals(DICT_SHA256, ignoreCase = true)) { zip.delete(); return false }
        val produced = runCatching { extractDictPack(zip, downloadedDir(filesDir)) }.getOrDefault(emptySet())
        zip.delete()
        val ok = DICT_PACK_FILES.all { it in produced }
        if (!ok) {
            DICT_PACK_FILES.forEach {
                File(downloadedDir(filesDir), it).delete()
                File(downloadedDir(filesDir), "$it.part").delete()
            }
        }
        return ok
    }

    internal fun extractDictPack(zip: File, dir: File): Set<String> {
        dir.mkdirs()
        val produced = HashSet<String>()
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) targetFor(e.name)?.let { target ->
                    val finalFile = File(dir, target)
                    val part = File(dir, "$target.part")
                    part.delete()
                    try {
                        part.outputStream().use { out -> zin.copyTo(out) }
                        finalFile.delete()
                        if (!part.renameTo(finalFile)) throw java.io.IOException("rename failed: $target")
                        produced.add(target)
                    } catch (t: Throwable) {
                        part.delete()
                        throw t
                    }
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        return produced
    }

    private fun targetFor(entryName: String): String? {
        val n = entryName.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return when {
            "jianpin" in n -> "aegis_jianpin.bin"
            "t9" in n -> "aegis_t9.bin"
            "dict" in n -> "aegis_dict.bin"
            else -> null
        }
    }

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun purgeDict(filesDir: File): Boolean {
        var removed = false
        DICT_PACK_FILES.forEach {
            if (File(downloadedDir(filesDir), it).delete()) removed = true
            if (File(downloadedDir(filesDir), "$it.part").delete()) removed = true
        }
        if (dictZipFile(filesDir).delete()) removed = true
        if (dictPartFile(filesDir).delete()) removed = true
        return removed
    }
}
