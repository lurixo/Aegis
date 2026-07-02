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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

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
      * Chinese IME behavior note.
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
            else conn.getHeaderField("ETag")
                ?: conn.getHeaderField("Last-Modified")
                ?: conn.contentLengthLong.takeIf { it > 0L }?.let { "size:$it" }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
      * Chinese IME behavior note.
     * equals the local one. So: differing validators → update; remote unknown/unreachable → fall back
     * to allowing a forced re-download; only an exact match suppresses it.
     */
    fun updateAvailable(local: String?, remote: String?): Boolean = !(remote != null && remote == local)

    /**
     *  [updateAvailable]'s 2-valued "offer unless confirmed-equal" (which conflates offline with an update). */
    enum class UpdateCheck { OFFLINE, UP_TO_DATE, UPDATE }

    /**
      * Chinese IME behavior note.
     * [present] guards the late HEAD callback: if the user deleted the pack mid-check it is false → null
     * (discard the stale result; NEVER re-download what was just deleted). Otherwise: an unreachable remote
      * Chinese IME behavior note.
     * a differing remote is UPDATE.
     */
    fun updateAction(present: Boolean, local: String?, remote: String?): UpdateCheck? = when {
        !present -> null
        remote == null -> UpdateCheck.OFFLINE
        remote == local -> UpdateCheck.UP_TO_DATE
        else -> UpdateCheck.UPDATE
    }

    /** Thorough delete: the model file plus any interrupted .part leftover (callers also clear the
     *  stored validator). Returns true if anything was removed. Idempotent. */
    fun purge(filesDir: File): Boolean {
        val a = destFile(filesDir).delete()
        val b = partFile(filesDir).delete()
        return a || b
    }

    // --- Optional full dictionary pack ---------------------------------------------------------------

    const val DICT_REPO_URL = "https://github.com/amzxyz/rime-wanxiang"

    const val DICT_RELEASES_API_URL = "https://api.github.com/repos/lurixo/Aegis/releases?per_page=100"

    const val DICT_NAME = "aegis_dict_pack.zip"
    const val FALLBACK_DICT_NAME = "aegis_dict_pack_debug13.zip"
    const val FALLBACK_DICT_URL =
        "https://github.com/lurixo/Aegis/releases/download/v0.1.0-debug.13/$FALLBACK_DICT_NAME"
    const val FALLBACK_DICT_SHA256 = "d048435631623513a9d6a6ccb877a6ba06fb15a293ade72bb101d1e0d4feaa60"

    /** The 3 files the pack carries → the names the engine picks up via downloadedOverride. */
    val DICT_PACK_FILES = listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")

    /** SharedPreferences keys (prefs "aegis") storing the installed dict pack's release metadata. */
    const val DICT_VALIDATOR_PREF = "dict_validator"
    const val DICT_SHA256_PREF = "dict_sha256"
    const val DICT_ASSET_NAME_PREF = "dict_asset_name"
    const val DICT_ASSET_URL_PREF = "dict_asset_url"
    const val DICT_RELEASE_TAG_PREF = "dict_release_tag"
    const val DICT_RELEASE_PUBLISHED_PREF = "dict_release_published_at"

    data class DictionaryAsset(
        val url: String,
        val assetName: String,
        val sizeBytes: Long,
        val sha256: String,
        val releaseTag: String,
        val releaseUrl: String,
        val prerelease: Boolean,
        val publishedAt: String,
    )

    data class DictionaryInstallMetadata(
        val sha256: String? = null,
        val publishedAt: String? = null,
    )

    data class DictionaryUpdateCheck(
        val state: UpdateCheck,
        val asset: DictionaryAsset? = null,
    )

    val FALLBACK_DICT_ASSET = DictionaryAsset(
        url = FALLBACK_DICT_URL,
        assetName = FALLBACK_DICT_NAME,
        sizeBytes = 98_214_288L,
        sha256 = FALLBACK_DICT_SHA256,
        releaseTag = "v0.1.0-debug.13",
        releaseUrl = "https://github.com/lurixo/Aegis/releases/tag/v0.1.0-debug.13",
        prerelease = true,
        publishedAt = "2026-06-28T18:46:56Z",
    )

    private fun downloadedDir(filesDir: File) = File(filesDir, "downloaded")
    fun dictZipFile(filesDir: File): File = File(downloadedDir(filesDir), DICT_NAME)
    fun dictPartFile(filesDir: File): File = File(downloadedDir(filesDir), "$DICT_NAME.part")
    private fun legacyDictZipFile(filesDir: File): File = File(downloadedDir(filesDir), FALLBACK_DICT_NAME)
    private fun legacyDictPartFile(filesDir: File): File = File(downloadedDir(filesDir), "$FALLBACK_DICT_NAME.part")

    /** "Downloaded" = all 3 extracted .bin are present (the zip itself is deleted after a successful install). */
    fun isDictDownloaded(filesDir: File): Boolean =
        DICT_PACK_FILES.all { File(downloadedDir(filesDir), it).let { f -> f.exists() && f.length() > 1024 } }

    /**
     * debug.16 (engine hot-reload guard): is a dict-pack or gram download/install currently in flight? The
     * engine hot-reload must NOT read `downloaded/` mid-install, or it could build from a partially-extracted
     * (mixed old/new) .bin set. The dict zip is deleted only AFTER all 3 .bin are atomically renamed
     * ([installDictPack] line order), so the zip's presence (or its `.part`) brackets the ENTIRE verify+extract
     * window airtight; the gram's single-file rename is already atomic, but its `.part` is included for symmetry.
     */
    fun installInProgress(filesDir: File): Boolean =
        dictZipFile(filesDir).exists() ||
            dictPartFile(filesDir).exists() ||
            legacyDictZipFile(filesDir).exists() ||
            legacyDictPartFile(filesDir).exists() ||
            partFile(filesDir).exists()

    /**
     * Verify the downloaded zip against [expectedSha256] and extract its 3 entries into downloaded/ renamed to
     * [DICT_PACK_FILES]; the (98 MB) zip is deleted afterwards. Returns true only when all 3 landed. A sha256
     * mismatch (corruption / tamper) is REJECTED — the zip is deleted, nothing extracted. Blocking — call off
     * the main thread.
     */
    fun installDictPack(filesDir: File, expectedSha256: String = FALLBACK_DICT_SHA256): Boolean {
        val zip = dictZipFile(filesDir)
        if (!zip.exists()) return false
        if (!sha256Of(zip).equals(expectedSha256, ignoreCase = true)) { zip.delete(); return false }
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

    fun resolveDictionaryDownloadAsset(current: DictionaryInstallMetadata = DictionaryInstallMetadata()): DictionaryAsset =
        runCatching { dictionaryUpdateFromReleasesJson(fetchText(DICT_RELEASES_API_URL), current).asset }
            .getOrNull()
            ?: FALLBACK_DICT_ASSET

    fun checkDictionaryUpdate(current: DictionaryInstallMetadata): DictionaryUpdateCheck =
        runCatching { dictionaryUpdateFromReleasesJson(fetchText(DICT_RELEASES_API_URL), current) }
            .getOrDefault(DictionaryUpdateCheck(UpdateCheck.OFFLINE))

    internal fun dictionaryUpdateFromReleasesJson(
        releasesJson: String,
        current: DictionaryInstallMetadata,
    ): DictionaryUpdateCheck {
        val releases = parseGitHubReleases(releasesJson)
        for (prerelease in listOf(true, false)) {
            val asset = latestUsableDictionaryAsset(releases, prerelease) ?: continue
            if (isNewerDictionaryAsset(asset, current)) return DictionaryUpdateCheck(UpdateCheck.UPDATE, asset)
        }
        return DictionaryUpdateCheck(UpdateCheck.UP_TO_DATE)
    }

    private data class GitHubRelease(
        val tagName: String,
        val releaseUrl: String,
        val prerelease: Boolean,
        val publishedAt: String,
        val assets: List<GitHubAsset>,
    )

    private data class GitHubAsset(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val digest: String?,
    )

    private fun latestUsableDictionaryAsset(releases: List<GitHubRelease>, prerelease: Boolean): DictionaryAsset? =
        releases
            .filter { it.prerelease == prerelease }
            .sortedWith(compareByDescending<GitHubRelease> { instantOrEpoch(it.publishedAt) }.thenByDescending { it.tagName })
            .firstNotNullOfOrNull { release ->
                release.assets.firstNotNullOfOrNull { asset -> dictionaryAssetFrom(release, asset) }
            }

    private fun dictionaryAssetFrom(release: GitHubRelease, asset: GitHubAsset): DictionaryAsset? {
        val name = asset.name
        val url = asset.url
        if (!isDictionaryZipAsset(name, url)) return null
        val sha256 = normalizeSha256(asset.digest) ?: return null
        if (asset.sizeBytes <= 0L || release.releaseUrl.isBlank() || release.publishedAt.isBlank()) return null
        return DictionaryAsset(
            url = url,
            assetName = name,
            sizeBytes = asset.sizeBytes,
            sha256 = sha256,
            releaseTag = release.tagName,
            releaseUrl = release.releaseUrl,
            prerelease = release.prerelease,
            publishedAt = release.publishedAt,
        )
    }

    private fun isNewerDictionaryAsset(asset: DictionaryAsset, current: DictionaryInstallMetadata): Boolean {
        val currentSha = normalizeSha256(current.sha256) ?: return true
        if (asset.sha256.equals(currentSha, ignoreCase = true)) return false
        val currentPublished = current.publishedAt?.let(::instantOrNull)
        val assetPublished = instantOrNull(asset.publishedAt)
        return currentPublished == null || assetPublished == null || assetPublished.isAfter(currentPublished)
    }

    private fun isDictionaryZipAsset(name: String, url: String): Boolean {
        val n = name.lowercase()
        val u = url.lowercase()
        return n.endsWith(".zip") &&
            ("dict" in n || "dictionary" in n) &&
            !n.endsWith(".apk") &&
            !u.endsWith(".apk") &&
            u.startsWith("https://github.com/lurixo/aegis/releases/download/")
    }

    internal fun normalizeSha256(value: String?): String? {
        val raw = value?.trim()?.lowercase()?.removePrefix("sha256:") ?: return null
        return raw.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    private fun parseGitHubReleases(json: String): List<GitHubRelease> {
        val array = JSONArray(json)
        val releases = ArrayList<GitHubRelease>(array.length())
        for (i in 0 until array.length()) {
            val r = array.getJSONObject(i)
            val assetsArray = r.optJSONArray("assets") ?: JSONArray()
            val assets = ArrayList<GitHubAsset>(assetsArray.length())
            for (j in 0 until assetsArray.length()) {
                val a = assetsArray.getJSONObject(j)
                assets += GitHubAsset(
                    name = a.optStringOrNull("name") ?: continue,
                    url = a.optStringOrNull("browser_download_url") ?: continue,
                    sizeBytes = a.optLong("size", -1L),
                    digest = a.optStringOrNull("digest"),
                )
            }
            releases += GitHubRelease(
                tagName = r.optStringOrNull("tag_name") ?: continue,
                releaseUrl = r.optStringOrNull("html_url") ?: continue,
                prerelease = r.optBoolean("prerelease", false),
                publishedAt = r.optStringOrNull("published_at") ?: continue,
                assets = assets,
            )
        }
        return releases
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun instantOrEpoch(value: String): Instant = instantOrNull(value) ?: Instant.EPOCH

    private fun instantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private fun fetchText(url: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Aegis-resource-updater")
            }
            if (conn.responseCode !in 200..299) throw IOException("GET $url failed: ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
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
        if (legacyDictZipFile(filesDir).delete()) removed = true
        if (legacyDictPartFile(filesDir).delete()) removed = true
        return removed
    }
}
