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
import java.util.Locale
import org.json.JSONObject

object ModelDownload {

    const val GRAM_NAME = "wanxiang-lts-zh-hans.gram"

    fun destFile(filesDir: File): File = File(File(filesDir, "downloaded"), GRAM_NAME)

    fun installedGramBytes(filesDir: File): Long = destFile(filesDir).length()

    fun partFile(filesDir: File): File = File(File(filesDir, "downloaded"), "$GRAM_NAME.part")

    fun bytesToDisplayMb(bytes: Long): Long = Math.round(bytes / 1_000_000.0)

    private fun partMetaOf(part: File): File = File(part.parentFile, "${part.name}.meta")

    enum class CheckFailure { OFFLINE, TIMEOUT, SERVER, PARSE }

    enum class UpdateCheck { OFFLINE, TIMEOUT, UP_TO_DATE, UPDATE, UNKNOWN, SERVER_ERROR, PARSE_ERROR }

    private fun CheckFailure.toUpdateCheck(): UpdateCheck = when (this) {
        CheckFailure.OFFLINE -> UpdateCheck.OFFLINE
        CheckFailure.TIMEOUT -> UpdateCheck.TIMEOUT
        CheckFailure.SERVER -> UpdateCheck.SERVER_ERROR
        CheckFailure.PARSE -> UpdateCheck.PARSE_ERROR
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    internal fun classifyRequestFailure(t: Throwable): CheckFailure =
        identifyRequestFailure(t) ?: CheckFailure.SERVER

    internal fun identifyRequestFailure(t: Throwable): CheckFailure? = when (t) {
        is HttpStatusException -> CheckFailure.SERVER
        else -> when {
            t.hasTimeoutSignal() -> CheckFailure.TIMEOUT
            t is java.net.UnknownHostException -> CheckFailure.OFFLINE
            t is java.net.NoRouteToHostException -> CheckFailure.OFFLINE
            t is java.net.PortUnreachableException -> CheckFailure.OFFLINE
            t is java.net.ConnectException && t.hasExplicitOfflineConnectSignal() -> CheckFailure.OFFLINE
            else -> null
        }
    }

    private fun Throwable.hasTimeoutSignal(): Boolean =
        generateSequence(this) { it.cause }
            .any { error ->
                error is java.net.SocketTimeoutException ||
                    (error is java.net.ConnectException && error.message?.hasTimeoutSignal() == true)
            }

    private fun String.hasTimeoutSignal(): Boolean =
        lowercase(Locale.ROOT).let { "timed out" in it || "etimedout" in it }

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
            else {
                ValidatorProbe.Reached(
                    trustworthyValidator(conn.getHeaderField("ETag"))
                        ?: trustworthyValidator(conn.getHeaderField("Last-Modified")),
                )
            }
        } catch (e: Exception) {
            ValidatorProbe.Failed(classifyRequestFailure(e))
        } finally {
            conn?.disconnect()
        }
    }

    fun validatorComparison(local: String?, remote: String?): UpdateCheck {
        val localValidator = trustworthyValidator(local)
        val remoteValidator = trustworthyValidator(remote)
        return when {
            localValidator == null || remoteValidator == null -> UpdateCheck.UNKNOWN
            remoteValidator == localValidator -> UpdateCheck.UP_TO_DATE
            else -> UpdateCheck.UPDATE
        }
    }

    fun modelUpdateAction(
        present: Boolean,
        local: String?,
        probe: ValidatorProbe,
    ): UpdateCheck? {
        if (!present) return null
        return when (probe) {
            is ValidatorProbe.Failed -> probe.failure.toUpdateCheck()
            is ValidatorProbe.Reached -> validatorComparison(local, probe.validator)
        }
    }

    private fun trustworthyValidator(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("size:", ignoreCase = true) }

    fun purge(filesDir: File): Boolean {
        destFile(filesDir).delete()
        partFile(filesDir).delete()
        partMetaOf(partFile(filesDir)).delete()
        return !destFile(filesDir).exists() && !partFile(filesDir).exists() &&
            !partMetaOf(partFile(filesDir)).exists()
    }

    const val DICT_LATEST_TAG = "dict-latest"

    const val DICT_UPDATE_URL =
        "https://github.com/lurixo/Aegis/releases/download/$DICT_LATEST_TAG/aegis-dictionary-update.json"

    const val DICT_NAME = "aegis_dict_pack.zip"

    const val LM_NAME = "aegis_lm.bin"

    const val EN_NAME = "aegis_english.bin"

    val DICT_BIN_FILES = listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")

    val DICT_PACK_FILES = DICT_BIN_FILES + LM_NAME

    val DICT_OPTIONAL_FILES = listOf(EN_NAME)

    val DICT_MANAGED_FILES = DICT_PACK_FILES + DICT_OPTIONAL_FILES

    fun installedDictionaryBytes(filesDir: File): Long =
        DICT_MANAGED_FILES.sumOf { File(downloadedDir(filesDir), it).length() }

    data class DictionaryAsset(
        val url: String,
        val assetName: String,
        val sizeBytes: Long,
        val sha256: String,
        val releaseTag: String,
        val releaseUrl: String,
        val prerelease: Boolean,
        val publishedAt: String?,
    )

    data class DictionaryInstallMetadata(
        val sha256: String? = null,
        val publishedAt: String? = null,
        val complete: Boolean = true,
    )

    data class DictionaryUpdateCheck(
        val state: UpdateCheck,
        val asset: DictionaryAsset? = null,
    )

    private fun downloadedDir(filesDir: File) = File(filesDir, "downloaded")
    fun dictZipFile(filesDir: File): File = File(downloadedDir(filesDir), DICT_NAME)

    internal fun resolveDictionaryDownloadAsset(fetch: () -> String): Result<DictionaryAsset> =
        runCatching { dictionaryAssetFromUpdateJson(fetch()) }

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
            val asset = dictionaryAssetFromUpdateJson(json)
            val comparison = dictionaryComparison(asset, current)
            if (comparison == UpdateCheck.UPDATE) DictionaryUpdateCheck(comparison, asset)
            else DictionaryUpdateCheck(comparison)
        } catch (t: Exception) {
            DictionaryUpdateCheck(UpdateCheck.PARSE_ERROR)
        }
    }

    internal fun dictionaryAssetFromUpdateJson(updateJson: String): DictionaryAsset {
        val update = JSONObject(updateJson)
        require(update.getInt("schema_version") == 1)
        require(update.getString("kind") == "dictionary_update")
        val asset = update.getJSONObject("asset")
        val name = asset.getString("name")
        val url = asset.getString("url")
        val sha256 = requireNotNull(normalizeSha256(asset.getString("sha256")))
        val sizeBytes = asset.getLong("size_bytes")
        val releaseTag = asset.getString("release_tag")
        val releaseUrl = asset.getString("release_url")
        val prerelease = asset.getBoolean("prerelease")
        require(
            sizeBytes > 0L &&
                name == "aegis_dict_pack_$DICT_LATEST_TAG.zip" &&
                url == "https://github.com/lurixo/Aegis/releases/download/$DICT_LATEST_TAG/$name" &&
                releaseTag == DICT_LATEST_TAG &&
                releaseUrl == "https://github.com/lurixo/Aegis/releases/tag/$DICT_LATEST_TAG" &&
                !prerelease
        )
        return DictionaryAsset(
            url = url,
            assetName = name,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            releaseTag = releaseTag,
            releaseUrl = releaseUrl,
            prerelease = prerelease,
            publishedAt = asset.optStringOrNull("published_at"),
        )
    }

    private fun dictionaryComparison(
        asset: DictionaryAsset,
        current: DictionaryInstallMetadata,
    ): UpdateCheck {
        val currentSha = normalizeSha256(current.sha256) ?: return UpdateCheck.UNKNOWN
        return if (current.complete && asset.sha256.equals(currentSha, ignoreCase = true)) UpdateCheck.UP_TO_DATE
        else UpdateCheck.UPDATE
    }

    internal fun normalizeSha256(value: String?): String? {
        val raw = value?.trim()?.lowercase()?.removePrefix("sha256:") ?: return null
        return raw.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
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
}
