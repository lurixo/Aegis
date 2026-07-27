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

package com.aegis.ime.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aegis.ime.R
import com.aegis.ime.SettingsHotApply
import com.aegis.ime.dict.ModelDownload

private const val DOWNLOAD_LOG_TAG = "AegisDownload"

private fun logDownloadFailure(what: String, detail: String, error: Throwable? = null) {
    val thrown = error?.let { " ${it.javaClass.name}: ${it.message}" }.orEmpty()
    Log.w(DOWNLOAD_LOG_TAG, "$what: $detail$thrown", error)
}

private fun transferFailureStatus(
    result: ModelDownload.DownloadResult,
    fallback: Int,
): LocalizedText {
    val cause = when (result.failure) {
        ModelDownload.TransferFailure.OFFLINE -> R.string.download_cause_offline
        ModelDownload.TransferFailure.TIMEOUT -> R.string.download_cause_timeout
        ModelDownload.TransferFailure.SERVER -> R.string.download_cause_server
        ModelDownload.TransferFailure.INCOMPLETE -> R.string.download_cause_incomplete
        ModelDownload.TransferFailure.INSTALL -> R.string.download_cause_install
        null -> return LocalizedText.Resource(fallback)
    }
    return LocalizedText.ResourceNested(R.string.download_status_failed_format, cause)
}

internal fun metadataFailureStatus(error: Throwable): LocalizedText {
    val cause = when (ModelDownload.identifyRequestFailure(error)) {
        ModelDownload.CheckFailure.OFFLINE -> R.string.download_cause_offline
        ModelDownload.CheckFailure.TIMEOUT -> R.string.download_cause_timeout
        ModelDownload.CheckFailure.SERVER, ModelDownload.CheckFailure.PARSE ->
            R.string.download_cause_server
        null -> return LocalizedText.Resource(R.string.dict_status_metadata_failed)
    }
    return LocalizedText.ResourceNested(R.string.dict_status_metadata_failed_format, cause)
}

private fun logTransferFailure(what: String, result: ModelDownload.DownloadResult) {
    val cause = result.failure?.name ?: "unclassified"
    logDownloadFailure(
        what,
        "transfer failed cause=$cause bytes=${result.bytesRead}/${result.contentLength}" +
            if (result.resumedFrom > 0L) " resumedFrom=${result.resumedFrom}" else "",
        result.error,
    )
}

private fun logResumedTransfer(what: String, result: ModelDownload.DownloadResult) {
    if (result.resumedFrom > 0L) {
        Log.i(DOWNLOAD_LOG_TAG, "$what: resumed transfer at byte ${result.resumedFrom} of ${result.contentLength}")
    }
}

internal data class DownloadCardSnapshot(
    val present: Boolean,
    val downloading: Boolean,
    val progress: Float?,
    val status: LocalizedText,
)

internal class DownloadRuntime(
    private val resource: String,
    private val isPresent: (Context) -> Boolean,
    private val doneStatus: (Context) -> LocalizedText,
    private val notDownloadedStatus: LocalizedText,
    private val failureStatus: LocalizedText,
    private val downloadStatus: LocalizedText = LocalizedText.Resource(R.string.download_status_downloading),
) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val observers = LinkedHashMap<(DownloadCardSnapshot) -> Unit, Context>()
    private var running = false
    private var progress: Float? = null
    private var statusOverride: LocalizedText? = null
    private var statusPresent: Boolean? = null

    fun snapshot(context: Context): DownloadCardSnapshot {
        val app = context.applicationContext
        return synchronized(lock) { snapshotLocked(app) }
    }

    fun observe(context: Context, observer: (DownloadCardSnapshot) -> Unit): () -> Unit {
        val app = context.applicationContext
        val initial = synchronized(lock) {
            observers[observer] = app
            snapshotLocked(app)
        }
        observer(initial)
        return { synchronized(lock) { observers.remove(observer) } }
    }

    fun start(
        context: Context,
        startTask: (Thread) -> Unit = Thread::start,
        worker: (Context, (Float) -> Unit, (LocalizedText) -> Unit) -> LocalizedText,
    ) {
        val app = context.applicationContext
        val shouldStart = synchronized(lock) {
            if (running) false
            else {
                running = true
                progress = null
                statusOverride = downloadStatus
                statusPresent = null
                true
            }
        }
        if (!shouldStart) {
            emit()
            return
        }
        val task = Thread {
            val finalStatus = runCatching { worker(app, ::updateProgress, ::updateRunningStatus) }
                .getOrElse { error ->
                    logDownloadFailure(resource, "download task failed", error)
                    failureStatus
                }
            finish(app, finalStatus)
        }.apply { isDaemon = true }
        val started = runCatching { startTask(task) }
            .onFailure { logDownloadFailure(resource, "download task not started", it) }
            .isSuccess
        if (!started) {
            finish(app, failureStatus)
            return
        }
        emit()
    }

    fun setIdleStatus(context: Context, status: LocalizedText) {
        val app = context.applicationContext
        synchronized(lock) {
            running = false
            progress = null
            statusOverride = status
            statusPresent = isPresent(app)
        }
        emit()
    }

    private fun updateProgress(value: Float) {
        synchronized(lock) { progress = value.coerceIn(0f, 1f) }
        emit()
    }

    private fun finish(context: Context, status: LocalizedText) {
        val present = isPresent(context)
        synchronized(lock) {
            running = false
            progress = if (present) 1f else 0f
            statusOverride = status
            statusPresent = present
        }
        emit()
    }

    private fun updateRunningStatus(status: LocalizedText) {
        synchronized(lock) {
            if (running) statusOverride = status
        }
        emit()
    }

    private fun snapshotLocked(context: Context): DownloadCardSnapshot {
        val present = isPresent(context)
        if (!running && statusPresent != null && statusPresent != present) {
            progress = null
            statusOverride = null
            statusPresent = null
        }
        val status = when {
            running -> statusOverride ?: downloadStatus
            statusOverride != null && statusPresent == present -> statusOverride!!
            present -> doneStatus(context)
            else -> notDownloadedStatus
        }
        return DownloadCardSnapshot(
            present = present,
            downloading = running,
            progress = progress,
            status = status,
        )
    }

    private fun emit() {
        val targets = synchronized(lock) { observers.map { (listener, context) -> listener to snapshotLocked(context) } }
        val notify = { targets.forEach { (listener, snapshot) -> listener(snapshot) } }
        if (Looper.myLooper() == Looper.getMainLooper()) notify() else main.post(notify)
    }
}

internal object GramDownloadWork {
    private val runtime = DownloadRuntime(
        resource = "model",
        isPresent = { ModelDownload.isDownloaded(it.filesDir) },
        doneStatus = { context ->
            LocalizedText.ResourceLong(
                R.string.gram_status_enabled,
                ModelDownload.bytesToDisplayMb(ModelDownload.installedGramBytes(context.filesDir)),
            )
        },
        notDownloadedStatus = LocalizedText.Resource(R.string.gram_status_not_downloaded),
        failureStatus = LocalizedText.Resource(R.string.gram_status_download_failed),
    )

    fun snapshot(context: Context): DownloadCardSnapshot {
        ModelDownload.reconcileInterruptedDownloads(context.filesDir)
        return runtime.snapshot(context)
    }

    fun observe(context: Context, observer: (DownloadCardSnapshot) -> Unit): () -> Unit {
        ModelDownload.reconcileInterruptedDownloads(context.filesDir)
        return runtime.observe(context, observer)
    }

    fun start(context: Context, url: String = ModelDownload.GRAM_URL) {
        ModelDownload.reconcileInterruptedDownloads(context.filesDir)
        runtime.start(context) { app, onProgress, _ ->
            val prefs = app.getSharedPreferences("aegis", Context.MODE_PRIVATE)
            val dest = ModelDownload.destFile(app.filesDir)
            var lastPct = -1
            val result = ModelDownload.downloadModel(url, dest, { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct / 100f)
                    }
                }
            }) { snapshot -> persistModelSnapshot(prefs, snapshot) }
            logResumedTransfer("model", result)
            if (result.ok) {
                SettingsHotApply.noteEnginePackChanged(prefs)
                LocalizedText.ResourceLong(
                    R.string.gram_status_enabled,
                    ModelDownload.bytesToDisplayMb(ModelDownload.installedGramBytes(app.filesDir)),
                )
            } else {
                logTransferFailure("model", result)
                transferFailureStatus(result, R.string.gram_status_download_failed)
            }
        }
    }

    fun setIdleStatus(context: Context, status: LocalizedText) = runtime.setIdleStatus(context, status)

    internal fun persistModelSnapshot(
        prefs: SharedPreferences,
        snapshot: ModelDownload.ModelSnapshot,
    ): Boolean {
        val previous = prefs.all[ModelDownload.VALIDATOR_PREF] as? String
        val previousSha = prefs.all[ModelDownload.GRAM_SHA256_PREF] as? String
        val previousSize = prefs.all[ModelDownload.GRAM_SIZE_PREF] as? Long
        val editor = prefs.edit()
        if (snapshot.validator == null) editor.remove(ModelDownload.VALIDATOR_PREF)
        else editor.putString(ModelDownload.VALIDATOR_PREF, snapshot.validator)
        editor.putString(ModelDownload.GRAM_SHA256_PREF, snapshot.sha256)
        editor.putLong(ModelDownload.GRAM_SIZE_PREF, snapshot.sizeBytes)
        if (editor.commit()) return true
        val rollback = prefs.edit()
        if (previous == null) rollback.remove(ModelDownload.VALIDATOR_PREF)
        else rollback.putString(ModelDownload.VALIDATOR_PREF, previous)
        if (previousSha == null) rollback.remove(ModelDownload.GRAM_SHA256_PREF)
        else rollback.putString(ModelDownload.GRAM_SHA256_PREF, previousSha)
        if (previousSize == null) rollback.remove(ModelDownload.GRAM_SIZE_PREF)
        else rollback.putLong(ModelDownload.GRAM_SIZE_PREF, previousSize)
        rollback.apply()
        return false
    }
}

internal object DictDownloadWork {
    private val runtime = DownloadRuntime(
        resource = "dictionary",
        isPresent = { ModelDownload.isDictDownloaded(it.filesDir) },
        doneStatus = { context ->
            LocalizedText.ResourceLong(
                R.string.dict_status_enabled,
                ModelDownload.bytesToDisplayMb(ModelDownload.installedDictionaryBytes(context.filesDir)),
            )
        },
        notDownloadedStatus = LocalizedText.Resource(R.string.dict_status_not_downloaded),
        failureStatus = LocalizedText.Resource(R.string.dict_status_download_failed),
    )

    fun snapshot(context: Context): DownloadCardSnapshot {
        ModelDownload.reconcileInterruptedDownloads(context.filesDir)
        return runtime.snapshot(context)
    }

    fun observe(context: Context, observer: (DownloadCardSnapshot) -> Unit): () -> Unit {
        ModelDownload.reconcileInterruptedDownloads(context.filesDir)
        return runtime.observe(context, observer)
    }

    fun start(
        context: Context,
        asset: ModelDownload.DictionaryAsset? = null,
        startTask: (Thread) -> Unit = Thread::start,
    ) {
        ModelDownload.reconcileInterruptedDownloads(context.filesDir)
        runtime.start(context, startTask) worker@ { app, onProgress, onStatus ->
            ModelDownload.recoverInterruptedDictionaryInstall(app.filesDir)
            val prefs = app.getSharedPreferences("aegis", Context.MODE_PRIVATE)
            val selected = asset ?: ModelDownload.resolveDictionaryDownloadAsset().getOrElse { error ->
                logDownloadFailure("dictionary", "metadata unavailable", error)
                return@worker metadataFailureStatus(error)
            }
            val recoveredSha = ModelDownload.installedDictionaryFileSha(app.filesDir)
            if (
                asset == null &&
                ModelDownload.isDictDownloaded(app.filesDir) &&
                recoveredSha != null &&
                recoveredSha.equals(selected.sha256, ignoreCase = true)
            ) {
                prefs.edit()
                    .remove(ModelDownload.DICT_VALIDATOR_PREF)
                    .putString(ModelDownload.DICT_SHA256_PREF, selected.sha256)
                    .putString(ModelDownload.DICT_ASSET_NAME_PREF, selected.assetName)
                    .putString(ModelDownload.DICT_ASSET_URL_PREF, selected.url)
                    .putString(ModelDownload.DICT_RELEASE_TAG_PREF, selected.releaseTag)
                    .putString(ModelDownload.DICT_RELEASE_PUBLISHED_PREF, selected.publishedAt)
                    .commit()
                SettingsHotApply.noteEnginePackChanged(prefs)
                return@worker LocalizedText.ResourceLong(
                    R.string.dict_status_enabled,
                    ModelDownload.bytesToDisplayMb(ModelDownload.installedDictionaryBytes(app.filesDir)),
                )
            }
            when (val marker = ModelDownload.recordPendingDictionarySha(app.filesDir, selected.sha256)) {
                ModelDownload.PendingMarker.Recorded -> Unit
                ModelDownload.PendingMarker.UnfinishedInstall -> {
                    logDownloadFailure("dictionary", "pending marker refused, unfinished install present")
                    return@worker LocalizedText.Resource(R.string.dict_status_download_blocked)
                }
                is ModelDownload.PendingMarker.NotWritten -> {
                    logDownloadFailure("dictionary", "pending marker not written", marker.error)
                    return@worker LocalizedText.Resource(R.string.dict_status_download_failed)
                }
            }
            val zip = ModelDownload.dictZipFile(app.filesDir)
            var lastPct = -1
            val result = ModelDownload.download(selected.url, zip, selected.sha256) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct / 100f)
                    }
                }
            }
            logResumedTransfer("dictionary", result)
            if (!result.ok) {
                logTransferFailure("dictionary", result)
                ModelDownload.clearPendingDictionarySha(app.filesDir)
            }
            if (result.ok) onStatus(LocalizedText.Resource(R.string.dict_status_verifying_extracting))
            val installed = result.ok && ModelDownload.installDictPack(app.filesDir, selected.sha256) {
                prefs.edit()
                    .putString(ModelDownload.DICT_VALIDATOR_PREF, result.validator)
                    .putString(ModelDownload.DICT_SHA256_PREF, selected.sha256)
                    .putString(ModelDownload.DICT_ASSET_NAME_PREF, selected.assetName)
                    .putString(ModelDownload.DICT_ASSET_URL_PREF, selected.url)
                    .putString(ModelDownload.DICT_RELEASE_TAG_PREF, selected.releaseTag)
                    .putString(ModelDownload.DICT_RELEASE_PUBLISHED_PREF, selected.publishedAt)
                    .commit()
            }
            when {
                installed -> {
                    SettingsHotApply.noteEnginePackChanged(prefs)
                    LocalizedText.ResourceLong(
                        R.string.dict_status_enabled,
                        ModelDownload.bytesToDisplayMb(ModelDownload.installedDictionaryBytes(app.filesDir)),
                    )
                }
                !result.ok -> transferFailureStatus(result, R.string.dict_status_download_failed)
                else -> {
                    logDownloadFailure("dictionary", "verification or extraction failed")
                    LocalizedText.Resource(R.string.dict_status_install_failed)
                }
            }
        }
    }

    fun setIdleStatus(context: Context, status: LocalizedText) = runtime.setIdleStatus(context, status)
}
