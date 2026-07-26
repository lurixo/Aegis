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

    const val GRAM_URL =
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"

    const val REPO_URL = "https://github.com/amzxyz/RIME-LMDG"

    const val GRAM_NAME = "wanxiang-lts-zh-hans.gram"

    const val VALIDATOR_PREF = "gram_validator"
    const val GRAM_SHA256_PREF = "gram_sha256"
    const val GRAM_SIZE_PREF = "gram_size_bytes"

    fun destFile(filesDir: File): File = File(File(filesDir, "downloaded"), GRAM_NAME)

    fun installedGramBytes(filesDir: File): Long = destFile(filesDir).length()

    fun partFile(filesDir: File): File = File(File(filesDir, "downloaded"), "$GRAM_NAME.part")

    fun isDownloaded(filesDir: File): Boolean = destFile(filesDir).let { it.exists() && it.length() > 1024 }

    fun bytesToDisplayMb(bytes: Long): Long = Math.round(bytes / 1_000_000.0)

    data class DownloadResult(val ok: Boolean, val validator: String?)
    data class ModelSnapshot(val validator: String?, val sha256: String, val sizeBytes: Long)

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val installingDicts = ConcurrentHashMap.newKeySet<String>()
    private val recoveringDicts = ConcurrentHashMap.newKeySet<String>()
    private val dictionaryRecoveryLock = ReentrantLock()

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): DownloadResult =
        downloadStaged(url, dest, onProgress) { staged, _ ->
            moveReplacing(staged, dest)
            true
        }

    internal fun downloadModel(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        persistSnapshot: (ModelSnapshot) -> Boolean,
    ): DownloadResult = downloadStaged(url, dest, onProgress) { staged, validator ->
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
        onProgress: (Long, Long) -> Unit,
        install: (File, String?) -> Boolean,
    ): DownloadResult {
        val key = dest.absolutePath
        if (!inFlight.add(key)) return DownloadResult(false, null)
        var conn: HttpURLConnection? = null
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            dest.parentFile?.mkdirs()
            tmp.delete()
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            if (conn.responseCode !in 200..299) throw HttpStatusException(conn.responseCode)
            val total = conn.contentLengthLong
            val validator = trustworthyValidator(conn.getHeaderField("ETag"))
                ?: trustworthyValidator(conn.getHeaderField("Last-Modified"))
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total >= 0L && done != total) throw IOException("incomplete download")
            if (done <= 1024L) throw IOException("invalid download")
            if (!install(tmp, validator)) throw IOException("install failed")
            DownloadResult(true, validator)
        } catch (e: Exception) {
            DownloadResult(false, null)
        } finally {
            tmp.delete()
            conn?.disconnect()
            inFlight.remove(key)
        }
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

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    internal fun classifyRequestFailure(t: Throwable): CheckFailure = when (t) {
        is HttpStatusException -> CheckFailure.SERVER
        else -> when {
            t.hasTimeoutSignal() -> CheckFailure.TIMEOUT
            t is java.net.UnknownHostException -> CheckFailure.OFFLINE
            t is java.net.NoRouteToHostException -> CheckFailure.OFFLINE
            t is java.net.PortUnreachableException -> CheckFailure.OFFLINE
            t is java.net.ConnectException && t.hasExplicitOfflineConnectSignal() -> CheckFailure.OFFLINE
            else -> CheckFailure.SERVER
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
        return !destFile(filesDir).exists() && !partFile(filesDir).exists()
    }


    const val DICT_REPO_URL = "https://github.com/amzxyz/rime-wanxiang"

    const val DICT_LATEST_TAG = "dict-latest"

    const val DICT_UPDATE_URL =
        "https://github.com/lurixo/Aegis/releases/download/$DICT_LATEST_TAG/aegis-dictionary-update.json"

    const val DICT_NAME = "aegis_dict_pack.zip"
    internal const val DICT_INSTALLED_SHA_NAME = "aegis_dict_pack.sha256"
    private const val DICT_PENDING_SHA_NAME = "aegis_dict_pack.pending.sha256"
    private const val LEGACY_DICT_ZIP_NAME = "aegis_dict_pack_debug13.zip"

    val DICT_PACK_FILES = listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")

    fun installedDictionaryBytes(filesDir: File): Long =
        DICT_PACK_FILES.sumOf { File(downloadedDir(filesDir), it).length() }

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
        val publishedAt: String?,
    )

    data class DictionaryInstallMetadata(
        val sha256: String? = null,
        val publishedAt: String? = null,
    )

    data class DictionaryUpdateCheck(
        val state: UpdateCheck,
        val asset: DictionaryAsset? = null,
    )

    private fun downloadedDir(filesDir: File) = File(filesDir, "downloaded")
    fun dictZipFile(filesDir: File): File = File(downloadedDir(filesDir), DICT_NAME)
    fun dictPartFile(filesDir: File): File = File(downloadedDir(filesDir), "$DICT_NAME.part")
    private fun legacyDictZipFile(filesDir: File): File = File(downloadedDir(filesDir), LEGACY_DICT_ZIP_NAME)
    private fun legacyDictPartFile(filesDir: File): File = File(downloadedDir(filesDir), "$LEGACY_DICT_ZIP_NAME.part")
    private fun dictStagingDir(filesDir: File) = File(downloadedDir(filesDir), "dict-install")
    private fun dictBackupFile(filesDir: File, name: String) = File(downloadedDir(filesDir), "$name.backup")
    private fun dictInstalledShaFile(filesDir: File) = File(downloadedDir(filesDir), DICT_INSTALLED_SHA_NAME)
    private fun dictPendingShaFile(filesDir: File) = File(downloadedDir(filesDir), DICT_PENDING_SHA_NAME)

    fun isDictDownloaded(filesDir: File): Boolean =
        DICT_PACK_FILES.all { File(downloadedDir(filesDir), it).let { f -> f.exists() && f.length() > 1024 } }

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

    internal fun dictionaryVersionUnknown(filesDir: File): Boolean =
        dictInstalledShaFile(filesDir).exists() && installedDictionaryFileSha(filesDir) == null

    internal fun recordPendingDictionarySha(filesDir: File, value: String): Boolean {
        val sha256 = normalizeSha256(value) ?: return false
        return dictionaryRecoveryLock.withLock {
            if (unmarkedDictionaryRecoveryRequired(filesDir)) return@withLock false
            runCatching {
                downloadedDir(filesDir).mkdirs()
                dictPendingShaFile(filesDir).writeText(sha256)
                true
            }.getOrDefault(false)
        }
    }

    private fun pendingDictionarySha(filesDir: File): String? =
        runCatching { normalizeSha256(dictPendingShaFile(filesDir).readText()) }.getOrNull()

    internal fun unmarkedDictionaryRecoveryRequired(filesDir: File): Boolean =
        dictZipFile(filesDir).exists() &&
            pendingDictionarySha(filesDir) == null &&
            (!isDictDownloaded(filesDir) || installedDictionaryFileSha(filesDir) == null)

    internal fun clearPendingDictionarySha(filesDir: File) {
        dictPendingShaFile(filesDir).delete()
    }

    fun reconcileInterruptedDownloads(filesDir: File) {
        if (!dictionaryRecoveryLock.tryLock()) return
        try {
            val key = filesDir.absolutePath
            if (destFile(filesDir).absolutePath !in inFlight) partFile(filesDir).delete()
            val dictActive = dictZipFile(filesDir).absolutePath in inFlight ||
                key in installingDicts ||
                key in recoveringDicts
            if (dictActive) {
                return
            }

            val transactionFiles = DICT_PACK_FILES + DICT_INSTALLED_SHA_NAME
            val backups = transactionFiles.associateWith { dictBackupFile(filesDir, it) }
            val hadBackups = backups.values.any(File::exists)
            val completeNewGeneration = isDictDownloaded(filesDir) && installedDictionaryFileSha(filesDir) != null
            val recoveryRequired = hadBackups && !completeNewGeneration
            if (recoveryRequired) {
                backups.forEach { (name, backup) ->
                    if (backup.exists()) {
                        val live = File(downloadedDir(filesDir), name)
                        runCatching { moveReplacing(backup, live) }
                    }
                }
            }
            if (recoveryRequired && backups.values.any(File::exists)) {
                return
            }
            val pendingArchive = dictZipFile(filesDir).exists()
            if (!isDictDownloaded(filesDir) && !pendingArchive) {
                DICT_PACK_FILES.forEach { File(downloadedDir(filesDir), it).delete() }
                dictInstalledShaFile(filesDir).delete()
            }
            backups.values.forEach { it.delete() }
            DICT_PACK_FILES.forEach { File(downloadedDir(filesDir), "$it.part").delete() }
            dictStagingDir(filesDir).deleteRecursively()
            if (completeNewGeneration || recoveryRequired) dictZipFile(filesDir).delete()
            if (!dictZipFile(filesDir).exists()) clearPendingDictionarySha(filesDir)
            dictPartFile(filesDir).delete()
            if (isDictDownloaded(filesDir)) legacyDictZipFile(filesDir).delete()
            legacyDictPartFile(filesDir).delete()
        } finally {
            dictionaryRecoveryLock.unlock()
        }
    }

    internal fun recoverInterruptedDictionaryInstall(filesDir: File) {
        dictionaryRecoveryLock.withLock {
            reconcileInterruptedDownloads(filesDir)
            val key = filesDir.absolutePath
            val zip = dictZipFile(filesDir)
            legacyDictZipFile(filesDir).delete()
            legacyDictPartFile(filesDir).delete()
            if (!zip.exists()) return
            if (zip.absolutePath in inFlight || key in installingDicts) return
            recoveringDicts.add(key)
            try {
                val expectedSha = pendingDictionarySha(filesDir)
                if (expectedSha == null) {
                    if (unmarkedDictionaryRecoveryRequired(filesDir)) {
                        DICT_PACK_FILES.forEach { File(downloadedDir(filesDir), it).delete() }
                        dictInstalledShaFile(filesDir).delete()
                        if (
                            DICT_PACK_FILES.none { File(downloadedDir(filesDir), it).exists() } &&
                            !dictInstalledShaFile(filesDir).exists()
                        ) {
                            zip.delete()
                        }
                    } else {
                        zip.delete()
                    }
                    return
                }
                val installed = runCatching { installDictPack(filesDir, expectedSha) }.getOrDefault(false)
                if (!installed && !isDictDownloaded(filesDir)) {
                    DICT_PACK_FILES.forEach { File(downloadedDir(filesDir), it).delete() }
                    dictInstalledShaFile(filesDir).delete()
                }
            } finally {
                clearPendingDictionarySha(filesDir)
                recoveringDicts.remove(key)
            }
        }
    }

    fun installInProgress(filesDir: File): Boolean {
        reconcileInterruptedDownloads(filesDir)
        return destFile(filesDir).absolutePath in inFlight ||
            dictZipFile(filesDir).absolutePath in inFlight ||
            dictionaryRecoveryLock.isLocked ||
            filesDir.absolutePath in installingDicts ||
            filesDir.absolutePath in recoveringDicts
    }

    internal fun dictionaryTransactionInProgress(filesDir: File): Boolean =
        dictionaryRecoveryLock.isLocked ||
            filesDir.absolutePath in installingDicts ||
            filesDir.absolutePath in recoveringDicts ||
            dictZipFile(filesDir).absolutePath in inFlight

    internal fun <T> withDictionaryGeneration(block: () -> T): T =
        dictionaryRecoveryLock.withLock(block)

    fun installDictPack(
        filesDir: File,
        expectedSha256: String,
        persistMetadata: () -> Boolean = { true },
    ): Boolean = dictionaryRecoveryLock.withLock {
        installDictPackLocked(filesDir, expectedSha256, persistMetadata)
    }

    private fun installDictPackLocked(
        filesDir: File,
        expectedSha256: String,
        persistMetadata: () -> Boolean,
    ): Boolean {
        val installKey = filesDir.absolutePath
        if (!installingDicts.add(installKey)) return false
        val zip = dictZipFile(filesDir)
        val staging = dictStagingDir(filesDir)
        return try {
            if (!zip.exists()) return false
            val normalizedSha = normalizeSha256(expectedSha256) ?: return false
            if (!sha256Of(zip).equals(normalizedSha, ignoreCase = true)) return false
            staging.deleteRecursively()
            val produced = runCatching { extractDictPack(zip, staging) }.getOrDefault(emptySet())
            val complete = DICT_PACK_FILES.all { name ->
                name in produced && File(staging, name).let { it.exists() && it.length() > 1024 }
            }
            if (!complete) return false
            File(staging, DICT_INSTALLED_SHA_NAME).writeText(normalizedSha)

            val backedUp = ArrayList<String>()
            val installed = ArrayList<String>()
            try {
                val transactionFiles = DICT_PACK_FILES + DICT_INSTALLED_SHA_NAME
                transactionFiles.forEach { name ->
                    val live = File(downloadedDir(filesDir), name)
                    val backup = dictBackupFile(filesDir, name)
                    if (backup.exists()) throw IOException("dictionary recovery pending")
                    if (live.exists()) {
                        moveReplacing(live, backup)
                        backedUp += name
                    }
                }
                transactionFiles.forEach { name ->
                    moveReplacing(File(staging, name), File(downloadedDir(filesDir), name))
                    installed += name
                }
                if (!persistMetadata()) throw IOException("metadata commit failed")
            } catch (t: Throwable) {
                installed.forEach { File(downloadedDir(filesDir), it).delete() }
                backedUp.asReversed().forEach { name ->
                    val backup = dictBackupFile(filesDir, name)
                    if (backup.exists()) runCatching {
                        moveReplacing(backup, File(downloadedDir(filesDir), name))
                    }
                }
                return false
            }
            backedUp.forEach { dictBackupFile(filesDir, it).delete() }
            true
        } finally {
            zip.delete()
            clearPendingDictionarySha(filesDir)
            staging.deleteRecursively()
            installingDicts.remove(installKey)
        }
    }

    fun resolveDictionaryDownloadAsset(): DictionaryAsset? =
        resolveDictionaryDownloadAsset { fetchText(DICT_UPDATE_URL) }

    internal fun resolveDictionaryDownloadAsset(fetch: () -> String): DictionaryAsset? =
        runCatching { dictionaryAssetFromUpdateJson(fetch()) }.getOrNull()

    fun checkDictionaryUpdate(current: DictionaryInstallMetadata): DictionaryUpdateCheck =
        checkDictionaryUpdate(DICT_UPDATE_URL, current)

    internal fun checkDictionaryUpdate(
        metadataUrl: String,
        current: DictionaryInstallMetadata,
    ): DictionaryUpdateCheck = dictionaryUpdateFromFetch({ fetchText(metadataUrl) }, current)

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
        return if (asset.sha256.equals(currentSha, ignoreCase = true)) UpdateCheck.UP_TO_DATE
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
                        moveReplacing(part, finalFile)
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

    private fun moveReplacing(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun purgeDict(filesDir: File): Boolean {
        if (!dictionaryRecoveryLock.tryLock()) return false
        try {
            val key = filesDir.absolutePath
            if (
                key in installingDicts ||
                key in recoveringDicts ||
                dictZipFile(filesDir).absolutePath in inFlight
            ) return false
            DICT_PACK_FILES.forEach {
                File(downloadedDir(filesDir), it).delete()
                File(downloadedDir(filesDir), "$it.part").delete()
                dictBackupFile(filesDir, it).delete()
            }
            dictInstalledShaFile(filesDir).delete()
            dictBackupFile(filesDir, DICT_INSTALLED_SHA_NAME).delete()
            clearPendingDictionarySha(filesDir)
            dictStagingDir(filesDir).deleteRecursively()
            dictZipFile(filesDir).delete()
            dictPartFile(filesDir).delete()
            legacyDictZipFile(filesDir).delete()
            legacyDictPartFile(filesDir).delete()
            return DICT_PACK_FILES.none {
                File(downloadedDir(filesDir), it).exists() ||
                    File(downloadedDir(filesDir), "$it.part").exists() ||
                    dictBackupFile(filesDir, it).exists()
            } && !dictInstalledShaFile(filesDir).exists() &&
                !dictBackupFile(filesDir, DICT_INSTALLED_SHA_NAME).exists() &&
                !dictPendingShaFile(filesDir).exists() &&
                !dictStagingDir(filesDir).exists() &&
                !dictZipFile(filesDir).exists() &&
                !dictPartFile(filesDir).exists() &&
                !legacyDictZipFile(filesDir).exists() &&
                !legacyDictPartFile(filesDir).exists()
        } finally {
            dictionaryRecoveryLock.unlock()
        }
    }
}
