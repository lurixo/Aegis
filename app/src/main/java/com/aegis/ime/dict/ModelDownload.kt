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
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

object ModelDownload {

    const val GRAM_NAME = "wanxiang-lts-zh-hans.gram"

    fun destFile(filesDir: File): File = File(File(filesDir, "downloaded"), GRAM_NAME)

    fun installedGramBytes(filesDir: File): Long = destFile(filesDir).length()

    fun partFile(filesDir: File): File = File(File(filesDir, "downloaded"), "$GRAM_NAME.part")

    fun bytesToDisplayMb(bytes: Long): Long = Math.round(bytes / 1_000_000.0)

    enum class TransferFailure { OFFLINE, TIMEOUT, SERVER, INCOMPLETE, CORRUPT, INSTALL }

    data class DownloadResult(
        val ok: Boolean,
        val validator: String?,
        val failure: TransferFailure? = null,
        val bytesRead: Long = 0L,
        val contentLength: Long = -1L,
        val error: Throwable? = null,
        val resumedFrom: Long = 0L,
    )

    data class ModelSnapshot(val validator: String?, val sha256: String, val sizeBytes: Long)

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val dictionaryRecoveryLock = ReentrantLock()

    fun download(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (Long, Long) -> Unit,
    ): DownloadResult =
        downloadStaged(url, dest, expectedSha256, onProgress) { staged, _ ->
            moveReplacing(staged, dest)
            true
        }

    internal fun downloadModel(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        persistSnapshot: (ModelSnapshot) -> Boolean,
    ): DownloadResult = downloadStaged(url, dest, null, onProgress) { staged, validator ->
        if (runCatching { OctagramReader.fromFile(staged) }.isFailure) return@downloadStaged false
        replaceModel(
            staged,
            dest,
            ModelSnapshot(validator, sha256Of(staged), staged.length()),
            persistSnapshot,
        )
    }

    private fun downloadStaged(
        url: String,
        dest: File,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit,
        install: (File, String?) -> Boolean,
    ): DownloadResult {
        val key = dest.absolutePath
        if (!inFlight.add(key)) return DownloadResult(false, null)
        var conn: HttpURLConnection? = null
        val tmp = File(dest.parentFile, dest.name + ".part")
        val meta = partMetaOf(tmp)
        var total = -1L
        var done = 0L
        var resumedFrom = 0L
        return try {
            dest.parentFile?.mkdirs()
            val wanted = normalizeSha256(expectedSha256)
            val resume = resumeIdentity(tmp, meta, url, expectedSha256)
            if (resume == null) {
                tmp.delete()
                meta.delete()
            }
            val offset = if (resume != null) tmp.length() else 0L
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (resume != null) {
                    setRequestProperty("Range", "bytes=$offset-")
                    resume.validator?.let { setRequestProperty("If-Range", it) }
                }
            }
            if (conn.responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                tmp.delete()
                meta.delete()
            }
            if (conn.responseCode !in 200..299) throw HttpStatusException(conn.responseCode)
            val validator = trustworthyValidator(conn.getHeaderField("ETag"))
                ?: trustworthyValidator(conn.getHeaderField("Last-Modified"))
            if (resume != null && conn.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val range = parseContentRange(conn.getHeaderField("Content-Range"))
                val consistent = range != null &&
                    range.first == offset &&
                    range.second == resume.sizeBytes &&
                    (resume.validator == null || validator == resume.validator)
                if (!consistent) {
                    tmp.delete()
                    meta.delete()
                    throw IOException("partial content does not continue the stored partial file")
                }
                total = resume.sizeBytes
                done = offset
                resumedFrom = offset
            } else {
                total = conn.contentLengthLong
                val identity = PartialIdentity(
                    url = url,
                    sizeBytes = total,
                    sha256 = wanted,
                    validator = validator?.takeIf { !it.startsWith("W/") },
                )
                if (total > 0L && (identity.sha256 != null || identity.validator != null)) {
                    if (!writePartialIdentity(meta, identity)) meta.delete()
                } else {
                    meta.delete()
                }
            }
            conn.inputStream.use { input ->
                FileOutputStream(tmp, resumedFrom > 0L).use { out ->
                    if (done > 0L) onProgress(done, total)
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    out.fd.sync()
                }
            }
            when {
                total >= 0L && done != total ->
                    DownloadResult(false, null, TransferFailure.INCOMPLETE, done, total, resumedFrom = resumedFrom)
                done <= 1024L ->
                    DownloadResult(false, null, bytesRead = done, contentLength = total, resumedFrom = resumedFrom)
                wanted != null && !sha256Of(tmp).equals(wanted, ignoreCase = true) -> {
                    tmp.delete()
                    meta.delete()
                    DownloadResult(false, null, TransferFailure.CORRUPT, done, total, resumedFrom = resumedFrom)
                }
                else -> installStaged(tmp, validator, done, total, resumedFrom, install)
            }
        } catch (e: Exception) {
            val failure = identifyRequestFailure(e)?.toTransferFailure()
                ?: TransferFailure.INCOMPLETE.takeIf { e.hasTruncatedTransferSignal() }
            DownloadResult(false, null, failure, done, total, e, resumedFrom)
        } finally {
            discardUnresumablePartial(tmp)
            conn?.disconnect()
            inFlight.remove(key)
        }
    }

    private data class PartialIdentity(
        val url: String,
        val sizeBytes: Long,
        val sha256: String?,
        val validator: String?,
    )

    private fun partMetaOf(part: File): File = File(part.parentFile, "${part.name}.meta")

    private fun readPartialIdentity(meta: File): PartialIdentity? = runCatching {
        val lines = meta.readText().split('\n')
        if (lines.size != 4) return@runCatching null
        PartialIdentity(
            url = lines[0],
            sizeBytes = lines[1].toLong(),
            sha256 = normalizeSha256(lines[2].takeIf { it != "-" }),
            validator = lines[3].takeIf { it != "-" },
        )
    }.getOrNull()?.takeIf { it.sizeBytes > 0L && (it.sha256 != null || it.validator != null) }

    private fun writePartialIdentity(meta: File, identity: PartialIdentity): Boolean = runCatching {
        meta.writeText(
            listOf(
                identity.url,
                identity.sizeBytes.toString(),
                identity.sha256 ?: "-",
                identity.validator ?: "-",
            ).joinToString("\n"),
        )
    }.isSuccess

    private fun resumeIdentity(tmp: File, meta: File, url: String, expectedSha256: String?): PartialIdentity? =
        readPartialIdentity(meta)?.takeIf { identity ->
            identity.url == url &&
                identity.sha256 == normalizeSha256(expectedSha256) &&
                tmp.isFile &&
                tmp.length() > 0L &&
                tmp.length() < identity.sizeBytes
        }

    private fun parseContentRange(value: String?): Pair<Long, Long>? {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val entity = match.groupValues[3].toLongOrNull() ?: return null
        return start to entity
    }

    private fun discardUnresumablePartial(part: File) {
        val meta = partMetaOf(part)
        val identity = readPartialIdentity(meta)
        val resumable = identity != null && part.isFile && part.length() > 0L && part.length() < identity.sizeBytes
        if (!resumable) {
            part.delete()
            meta.delete()
        }
    }

    private fun installStaged(
        staged: File,
        validator: String?,
        done: Long,
        total: Long,
        resumedFrom: Long,
        install: (File, String?) -> Boolean,
    ): DownloadResult = try {
        if (install(staged, validator)) {
            DownloadResult(true, validator, bytesRead = done, contentLength = total, resumedFrom = resumedFrom)
        } else {
            DownloadResult(false, null, TransferFailure.INSTALL, done, total, resumedFrom = resumedFrom)
        }
    } catch (e: Exception) {
        DownloadResult(false, null, TransferFailure.INSTALL, done, total, e, resumedFrom)
    }

    private fun replaceModel(
        staged: File,
        dest: File,
        snapshot: ModelSnapshot,
        persistSnapshot: (ModelSnapshot) -> Boolean,
    ): Boolean {
        val backup = File(dest.parentFile, "${dest.name}.backup")
        backup.delete()
        var backedUp = false
        var installed = false
        return try {
            if (dest.exists()) {
                moveReplacing(dest, backup)
                backedUp = true
            }
            moveReplacing(staged, dest)
            installed = true
            if (!persistSnapshot(snapshot)) throw IOException("snapshot commit failed")
            backup.delete()
            true
        } catch (t: Throwable) {
            if (installed) dest.delete()
            if (backedUp && backup.exists()) runCatching { moveReplacing(backup, dest) }
            false
        }
    }

    enum class CheckFailure { OFFLINE, TIMEOUT, SERVER, PARSE }

    enum class UpdateCheck { OFFLINE, TIMEOUT, UP_TO_DATE, UPDATE, UNKNOWN, SERVER_ERROR, PARSE_ERROR }

    private fun CheckFailure.toUpdateCheck(): UpdateCheck = when (this) {
        CheckFailure.OFFLINE -> UpdateCheck.OFFLINE
        CheckFailure.TIMEOUT -> UpdateCheck.TIMEOUT
        CheckFailure.SERVER -> UpdateCheck.SERVER_ERROR
        CheckFailure.PARSE -> UpdateCheck.PARSE_ERROR
    }

    private fun CheckFailure.toTransferFailure(): TransferFailure = when (this) {
        CheckFailure.OFFLINE -> TransferFailure.OFFLINE
        CheckFailure.TIMEOUT -> TransferFailure.TIMEOUT
        CheckFailure.SERVER, CheckFailure.PARSE -> TransferFailure.SERVER
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

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

    private fun Throwable.hasTruncatedTransferSignal(): Boolean =
        generateSequence(this) { it.cause }
            .any { error ->
                error is java.io.EOFException ||
                    (error is java.io.IOException && error.message?.hasTruncationSignal() == true)
            }

    private fun String.hasTruncationSignal(): Boolean =
        lowercase(Locale.ROOT).let {
            "unexpected end of file" in it || "unexpected end of stream" in it || "premature" in it
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
    internal const val DICT_INSTALLED_SHA_NAME = "aegis_dict_pack.sha256"
    internal const val DICT_PENDING_SHA_NAME = "aegis_dict_pack.pending.sha256"

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
    fun dictPartFile(filesDir: File): File = File(downloadedDir(filesDir), "$DICT_NAME.part")
    private fun dictInstalledShaFile(filesDir: File) = File(downloadedDir(filesDir), DICT_INSTALLED_SHA_NAME)
    private fun dictPendingShaFile(filesDir: File) = File(downloadedDir(filesDir), DICT_PENDING_SHA_NAME)

    fun isDictDownloaded(filesDir: File): Boolean =
        DICT_BIN_FILES.all { File(downloadedDir(filesDir), it).let { f -> f.exists() && f.length() > 1024 } }

    internal fun resolvedInstalledDictionarySha(
        filesDir: File,
        stored: String?,
    ): String? {
        installedDictionaryFileSha(filesDir)?.let { return it }
        if (dictInstalledShaFile(filesDir).exists()) return null
        return normalizeSha256(stored)
    }

    internal fun installedDictionaryFileSha(filesDir: File): String? =
        runCatching { normalizeSha256(dictInstalledShaFile(filesDir).readText()) }.getOrNull()

    sealed interface PendingMarker {
        data object Recorded : PendingMarker
        data object UnfinishedInstall : PendingMarker
        data class NotWritten(val error: Throwable) : PendingMarker
    }

    internal fun recordPendingDictionarySha(filesDir: File, value: String): PendingMarker =
        dictionaryRecoveryLock.withLock {
            if (unmarkedDictionaryRecoveryRequired(filesDir)) {
                return@withLock PendingMarker.UnfinishedInstall
            }
            runCatching {
                val sha256 = requireNotNull(normalizeSha256(value)) { "unrecognised dictionary sha256" }
                downloadedDir(filesDir).mkdirs()
                syncWrite(dictPendingShaFile(filesDir), sha256)
                PendingMarker.Recorded
            }.getOrElse { PendingMarker.NotWritten(it) }
        }

    private fun pendingDictionarySha(filesDir: File): String? =
        runCatching { normalizeSha256(dictPendingShaFile(filesDir).readText()) }.getOrNull()

    internal fun unmarkedDictionaryRecoveryRequired(filesDir: File): Boolean =
        dictZipFile(filesDir).exists() &&
            pendingDictionarySha(filesDir) == null &&
            (!isDictDownloaded(filesDir) || installedDictionaryFileSha(filesDir) == null)

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

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun syncWrite(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
