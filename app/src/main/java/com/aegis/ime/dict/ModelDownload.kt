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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

object ModelDownload {

    const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"

    const val REPO_URL = "https://github.com/amzxyz/RIME-LMDG"

    const val GRAM_NAME = "wanxiang-lts-zh-hans.gram"

    const val VALIDATOR_PREF = "gram_validator"

    fun destFile(filesDir: File): File = File(File(filesDir, "downloaded"), GRAM_NAME)

    fun partFile(filesDir: File): File = File(File(filesDir, "downloaded"), "$GRAM_NAME.part")

    fun isDownloaded(filesDir: File): Boolean = destFile(filesDir).let { it.exists() && it.length() > 1024 }

    fun bytesToDisplayMb(bytes: Long): Long = Math.round(bytes / 1_000_000.0)

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

    enum class CheckFailure { OFFLINE, SERVER, PARSE }

    enum class UpdateCheck { OFFLINE, UP_TO_DATE, UPDATE, SERVER_ERROR, PARSE_ERROR }

    private fun CheckFailure.toUpdateCheck(): UpdateCheck = when (this) {
        CheckFailure.OFFLINE -> UpdateCheck.OFFLINE
        CheckFailure.SERVER -> UpdateCheck.SERVER_ERROR
        CheckFailure.PARSE -> UpdateCheck.PARSE_ERROR
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    internal fun classifyRequestFailure(t: Throwable): CheckFailure = when (t) {
        is HttpStatusException -> CheckFailure.SERVER
        is java.net.UnknownHostException -> CheckFailure.OFFLINE
        is java.net.NoRouteToHostException -> CheckFailure.OFFLINE
        is java.net.PortUnreachableException -> CheckFailure.OFFLINE
        is java.net.ConnectException ->
            if (t.hasExplicitOfflineConnectSignal()) CheckFailure.OFFLINE else CheckFailure.SERVER
        else -> CheckFailure.SERVER
    }

    private fun Throwable.hasExplicitOfflineConnectSignal(): Boolean =
        generateSequence(this) { it.cause }
            .any { error ->
                error is java.net.UnknownHostException ||
                    error is java.net.NoRouteToHostException ||
                    error is java.net.PortUnreachableException ||
                    error.message?.hasOfflineConnectSignal() == true
            }

    private fun String.hasOfflineConnectSignal(): Boolean =
        lowercase(Locale.ROOT).let {
            "network is unreachable" in it ||
                "network unreachable" in it ||
                "no route to host" in it ||
                "host is unreachable" in it ||
                "host unreachable" in it ||
                "enetunreach" in it ||
                "ehostunreach" in it
        }

    sealed interface ValidatorProbe {
        data class Reached(val validator: String?) : ValidatorProbe
        data class Failed(val failure: CheckFailure) : ValidatorProbe
    }

    fun remoteValidatorProbe(url: String): ValidatorProbe {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            val code = conn.responseCode
            if (code !in 200..299) ValidatorProbe.Failed(CheckFailure.SERVER)
            else ValidatorProbe.Reached(
                conn.getHeaderField("ETag")
                    ?: conn.getHeaderField("Last-Modified")
                    ?: conn.contentLengthLong.takeIf { it > 0L }?.let { "size:$it" },
            )
        } catch (e: Exception) {
            ValidatorProbe.Failed(classifyRequestFailure(e))
        } finally {
            conn?.disconnect()
        }
    }

    fun updateAvailable(local: String?, remote: String?): Boolean = !(remote != null && remote == local)

    fun modelUpdateAction(present: Boolean, local: String?, probe: ValidatorProbe): UpdateCheck? {
        if (!present) return null
        return when (probe) {
            is ValidatorProbe.Failed -> probe.failure.toUpdateCheck()
            is ValidatorProbe.Reached -> when {
                probe.validator == null -> UpdateCheck.SERVER_ERROR
                probe.validator == local -> UpdateCheck.UP_TO_DATE
                else -> UpdateCheck.UPDATE
            }
        }
    }

    fun purge(filesDir: File): Boolean {
        val a = destFile(filesDir).delete()
        val b = partFile(filesDir).delete()
        return a || b
    }


    const val DICT_REPO_URL = "https://github.com/amzxyz/rime-wanxiang"

    const val DICT_LATEST_TAG = "dict-latest"

    const val DICT_LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/lurixo/Aegis/releases/tags/$DICT_LATEST_TAG"

    const val DICT_NAME = "aegis_dict_pack.zip"
    const val FALLBACK_DICT_NAME = "aegis_dict_pack_debug13.zip"
    const val FALLBACK_DICT_URL =
        "https://github.com/lurixo/Aegis/releases/download/v0.1.0-debug.13/$FALLBACK_DICT_NAME"
    const val FALLBACK_DICT_SHA256 = "d048435631623513a9d6a6ccb877a6ba06fb15a293ade72bb101d1e0d4feaa60"

    val DICT_PACK_FILES = listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")

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

    fun isDictDownloaded(filesDir: File): Boolean =
        DICT_PACK_FILES.all { File(downloadedDir(filesDir), it).let { f -> f.exists() && f.length() > 1024 } }

    fun installInProgress(filesDir: File): Boolean =
        dictZipFile(filesDir).exists() ||
            dictPartFile(filesDir).exists() ||
            legacyDictZipFile(filesDir).exists() ||
            legacyDictPartFile(filesDir).exists() ||
            partFile(filesDir).exists()

    fun installDictPack(filesDir: File, expectedSha256: String = FALLBACK_DICT_SHA256): Boolean {
        val zip = dictZipFile(filesDir)
        if (!zip.exists()) return false
        if (!sha256Of(zip).equals(expectedSha256, ignoreCase = true)) { zip.delete(); return false }
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

    fun resolveDictionaryDownloadAsset(): DictionaryAsset =
        resolveDictionaryDownloadAsset { fetchText(DICT_LATEST_RELEASE_API_URL) }

    internal fun resolveDictionaryDownloadAsset(fetch: () -> String): DictionaryAsset =
        runCatching { latestDictionaryAssetFromRelease(fetch()) }.getOrNull() ?: FALLBACK_DICT_ASSET

    fun checkDictionaryUpdate(current: DictionaryInstallMetadata): DictionaryUpdateCheck =
        dictionaryUpdateFromFetch({ fetchText(DICT_LATEST_RELEASE_API_URL) }, current)

    internal fun dictionaryUpdateFromFetch(
        fetch: () -> String,
        current: DictionaryInstallMetadata,
    ): DictionaryUpdateCheck {
        val json = try {
            fetch()
        } catch (t: Exception) {
            return DictionaryUpdateCheck(classifyRequestFailure(t).toUpdateCheck())
        }
        return try {
            dictionaryUpdateFromLatestReleaseJson(json, current)
        } catch (t: Exception) {
            DictionaryUpdateCheck(UpdateCheck.PARSE_ERROR)
        }
    }

    internal fun dictionaryUpdateFromLatestReleaseJson(
        releaseJson: String,
        current: DictionaryInstallMetadata,
    ): DictionaryUpdateCheck {
        val release = parseGitHubRelease(releaseJson)
            ?: return DictionaryUpdateCheck(UpdateCheck.PARSE_ERROR)
        val asset = dictionaryPackAssetIn(release)
            ?: return DictionaryUpdateCheck(UpdateCheck.SERVER_ERROR)
        return if (isNewerDictionaryAsset(asset, current)) DictionaryUpdateCheck(UpdateCheck.UPDATE, asset)
        else DictionaryUpdateCheck(UpdateCheck.UP_TO_DATE)
    }

    internal fun latestDictionaryAssetFromRelease(releaseJson: String): DictionaryAsset? =
        parseGitHubRelease(releaseJson)?.let(::dictionaryPackAssetIn)

    private fun dictionaryPackAssetIn(release: GitHubRelease): DictionaryAsset? =
        release.assets.firstNotNullOfOrNull { dictionaryAssetFrom(release, it) }

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
        return !asset.sha256.equals(currentSha, ignoreCase = true)
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

    private fun parseGitHubRelease(releaseJson: String): GitHubRelease? {
        val r = JSONObject(releaseJson)
        val tagName = r.optStringOrNull("tag_name") ?: return null
        val releaseUrl = r.optStringOrNull("html_url") ?: return null
        val publishedAt = r.optStringOrNull("published_at") ?: return null
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
        return GitHubRelease(
            tagName = tagName,
            releaseUrl = releaseUrl,
            prerelease = r.optBoolean("prerelease", false),
            publishedAt = publishedAt,
            assets = assets,
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    internal fun fetchText(url: String): String {
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
            if (conn.responseCode !in 200..299) throw HttpStatusException(conn.responseCode)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
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
        if (legacyDictZipFile(filesDir).delete()) removed = true
        if (legacyDictPartFile(filesDir).delete()) removed = true
        return removed
    }
}
