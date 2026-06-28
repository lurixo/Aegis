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

/** Streams the optional enhancement model into filesDir/downloaded/ (picked up by the IME next session). */
object ModelDownload {

    /** Public wanxiang octagram LTS release. */
    const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"

    /** Upstream source repository for the grammar model (shown as a tappable link on the model card). */
    const val REPO_URL = "https://github.com/amzxyz/RIME-LMDG"

    const val GRAM_NAME = "wanxiang-lts-zh-hans.gram"

    /** SharedPreferences key (prefs "aegis") storing the downloaded file's remote validator. */
    const val VALIDATOR_PREF = "gram_validator"

    fun destFile(filesDir: File): File = File(File(filesDir, "downloaded"), GRAM_NAME)

    /** The in-flight temp file for [destFile] — a leftover means a previous download was interrupted. */
    fun partFile(filesDir: File): File = File(File(filesDir, "downloaded"), "$GRAM_NAME.part")

    fun isDownloaded(filesDir: File): Boolean = destFile(filesDir).let { it.exists() && it.length() > 1024 }

    /** Outcome of [download]: success flag + the server validator (ETag/Last-Modified) of what landed. */
    data class DownloadResult(val ok: Boolean, val validator: String?)

    /** Single-flight guard, keyed PER destination: a concurrent (e.g. double-click / post-rotation) download
     *  must not open a second truncating stream on the SAME .part and interleave-corrupt the file. B2:
     *  keyed by dest (not a single global flag) so the model (.gram) and the dict pack — different files —
     *  can download independently (B5), while each file is still protected against its own double-start. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Download [url] to [dest] (via a .part temp + atomic rename), reporting (bytesDone, total).
     * Blocking — call off the main thread. The atomic rename onto the single [dest] path means a
     * re-download / 更新 overwrites in place rather than accumulating duplicates. Returns the server
     * validator so the caller can record it and later detect a newer remote release. A second call
     * while one is already running returns ok=false immediately (it does not touch the .part).
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): DownloadResult {
        val key = dest.absolutePath
        if (!inFlight.add(key)) return DownloadResult(false, null) // this dest is already downloading
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

    // --- B2 (debug.13): the optional FULL dictionary pack (14 tables, freq≥1) ----------------------------
    // A second downloadable asset, INDEPENDENT of the .gram model: its own URL / zip / recorded validator, so
    // the two cards check + download separately (B5). The asset is a ZIP (98 MB) whose 3 entries are sha256-
    // verified, extracted, and renamed to the 3 .bin the engine loads via downloadedOverride. The generic
    // download / remoteValidator / updateAvailable above are shared verbatim.
    // The dict pack is published as the v0.1.0-debug.13 release
    // asset; DICT_NAME + DICT_SHA256 match the produced pack.

    /** The dict-pack release asset (debug.13 full 14-table pack), verified against DICT_SHA256. */
    const val DICT_URL =
        "https://github.com/lurixo/Aegis/releases/download/v0.1.0-debug.13/aegis_dict_pack_debug13.zip"

    /** PLACEHOLDER — the dict pack's release page, shown as the card's tappable 直达链接 (B4). */
    const val DICT_REPO_URL = "https://github.com/lurixo/aegis/releases"

    /** The downloaded zip's filename + its expected sha256 (the debug.13 dict pack). */
    const val DICT_NAME = "aegis_dict_pack_debug13.zip"
    const val DICT_SHA256 = "d048435631623513a9d6a6ccb877a6ba06fb15a293ade72bb101d1e0d4feaa60"

    /** The 3 files the pack carries → the names the engine picks up via downloadedOverride. */
    val DICT_PACK_FILES = listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")

    /** SharedPreferences key (prefs "aegis") storing the downloaded dict pack's remote validator. */
    const val DICT_VALIDATOR_PREF = "dict_validator"

    private fun downloadedDir(filesDir: File) = File(filesDir, "downloaded")
    fun dictZipFile(filesDir: File): File = File(downloadedDir(filesDir), DICT_NAME)
    fun dictPartFile(filesDir: File): File = File(downloadedDir(filesDir), "$DICT_NAME.part")

    /** "Downloaded" = all 3 extracted .bin are present (the zip itself is deleted after a successful install). */
    fun isDictDownloaded(filesDir: File): Boolean =
        DICT_PACK_FILES.all { File(downloadedDir(filesDir), it).let { f -> f.exists() && f.length() > 1024 } }

    /**
     * Verify the downloaded zip against [DICT_SHA256] and extract its 3 entries into downloaded/ renamed to
     * [DICT_PACK_FILES]; the (98 MB) zip is deleted afterwards. Returns true only when all 3 landed. A sha256
     * mismatch (corruption / tamper) is REJECTED — the zip is deleted, nothing extracted. Blocking — call off
     * the main thread.
     */
    fun installDictPack(filesDir: File): Boolean {
        val zip = dictZipFile(filesDir)
        if (!zip.exists()) return false
        if (!sha256Of(zip).equals(DICT_SHA256, ignoreCase = true)) { zip.delete(); return false }
        val produced = runCatching { extractDictPack(zip, downloadedDir(filesDir)) }.getOrDefault(emptySet())
        zip.delete() // the unpacked .bin are what the engine loads; the zip is not needed afterwards
        val ok = DICT_PACK_FILES.all { it in produced }
        if (!ok) {
            // Partial / failed extract: never leave a half-written or mixed dict set the engine would load
            // (e.g. a fresh aegis_dict.bin beside a truncated aegis_t9.bin). Remove every downloaded .bin
            // (+ any stray .part) so the IME falls back uniformly to the bundled seed; re-download is allowed.
            DICT_PACK_FILES.forEach {
                File(downloadedDir(filesDir), it).delete()
                File(downloadedDir(filesDir), "$it.part").delete()
            }
        }
        return ok
    }

    /**
     * Extract [zip]'s entries into [dir], mapping each to one of [DICT_PACK_FILES] by filename keyword (so the
     * pack's entries are renamed to the names the engine loads). Output paths are ALWAYS a fixed target name
     * under [dir], so a malicious entry path ("../…") can never escape. Each entry is written to a `<target>.part`
     * temp and atomically renamed onto the live target only after the full copy succeeds — so an interrupted or
     * disk-full extract never leaves a TRUNCATED .bin at the name the engine consumes (the live target keeps its
     * previous content, or stays absent). Returns the set of target names fully written.
     */
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
                        part.delete() // leave no truncated temp behind; propagate so installDictPack cleans up
                        throw t
                    }
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        return produced
    }

    /** Map a zip entry to its target .bin by keyword (jianpin/t9 checked before dict, since dict is the default). */
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

    /** Thorough delete of the dict pack: the 3 extracted .bin (+ any per-file .part) + leftover zip / .part. Idempotent. */
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
