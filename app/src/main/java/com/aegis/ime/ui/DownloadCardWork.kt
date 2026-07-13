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
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.aegis.ime.R
import com.aegis.ime.SettingsHotApply
import com.aegis.ime.dict.ModelDownload

internal data class DownloadCardSnapshot(
    val present: Boolean,
    val downloading: Boolean,
    val progress: Float?,
    val status: LocalizedText,
)

internal class DownloadRuntime(
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
                true
            }
        }
        if (!shouldStart) {
            emit()
            return
        }
        val task = Thread {
            val finalStatus = runCatching { worker(app, ::updateProgress, ::updateRunningStatus) }
                .getOrDefault(failureStatus)
            finish(app, finalStatus)
        }.apply { isDaemon = true }
        if (runCatching { startTask(task) }.isFailure) {
            finish(app, failureStatus)
            return
        }
        emit()
    }

    fun setIdleStatus(status: LocalizedText) {
        synchronized(lock) {
            running = false
            progress = null
            statusOverride = status
        }
        emit()
    }

    private fun updateProgress(value: Float) {
        synchronized(lock) { progress = value.coerceIn(0f, 1f) }
        emit()
    }

    private fun finish(context: Context, status: LocalizedText) {
        synchronized(lock) {
            running = false
            progress = if (isPresent(context)) 1f else 0f
            statusOverride = status
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
        val status = when {
            running -> statusOverride ?: downloadStatus
            statusOverride != null -> statusOverride!!
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

    fun snapshot(context: Context): DownloadCardSnapshot = runtime.snapshot(context)
    fun observe(context: Context, observer: (DownloadCardSnapshot) -> Unit): () -> Unit =
        runtime.observe(context, observer)

    fun start(context: Context) {
        runtime.start(context) { app, onProgress, _ ->
            val prefs = app.getSharedPreferences("aegis", Context.MODE_PRIVATE)
            val dest = ModelDownload.destFile(app.filesDir)
            var lastPct = -1
            val result = ModelDownload.download(ModelDownload.GRAM_URL, dest) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct / 100f)
                    }
                }
            }
            if (result.ok) {
                prefs.edit { putString(ModelDownload.VALIDATOR_PREF, result.validator) }
                SettingsHotApply.noteEnginePackChanged(prefs)
                LocalizedText.ResourceLong(
                    R.string.gram_status_enabled,
                    ModelDownload.bytesToDisplayMb(ModelDownload.installedGramBytes(app.filesDir)),
                )
            } else {
                LocalizedText.Resource(R.string.gram_status_download_failed)
            }
        }
    }

    fun setIdleStatus(status: LocalizedText) = runtime.setIdleStatus(status)
}

internal object DictDownloadWork {
    private val runtime = DownloadRuntime(
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

    fun snapshot(context: Context): DownloadCardSnapshot = runtime.snapshot(context)
    fun observe(context: Context, observer: (DownloadCardSnapshot) -> Unit): () -> Unit =
        runtime.observe(context, observer)

    fun start(context: Context, asset: ModelDownload.DictionaryAsset? = null) {
        runtime.start(context) { app, onProgress, onStatus ->
            val prefs = app.getSharedPreferences("aegis", Context.MODE_PRIVATE)
            val selected = asset ?: ModelDownload.resolveDictionaryDownloadAsset()
            val zip = ModelDownload.dictZipFile(app.filesDir)
            var lastPct = -1
            val result = ModelDownload.download(selected.url, zip) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct / 100f)
                    }
                }
            }
            if (result.ok) onStatus(LocalizedText.Resource(R.string.dict_status_verifying_extracting))
            val installed = result.ok && ModelDownload.installDictPack(app.filesDir, selected.sha256)
            when {
                installed -> {
                    prefs.edit {
                        putString(ModelDownload.DICT_VALIDATOR_PREF, result.validator)
                        putString(ModelDownload.DICT_SHA256_PREF, selected.sha256)
                        putString(ModelDownload.DICT_ASSET_NAME_PREF, selected.assetName)
                        putString(ModelDownload.DICT_ASSET_URL_PREF, selected.url)
                        putString(ModelDownload.DICT_RELEASE_TAG_PREF, selected.releaseTag)
                        putString(ModelDownload.DICT_RELEASE_PUBLISHED_PREF, selected.publishedAt)
                    }
                    SettingsHotApply.noteEnginePackChanged(prefs)
                    LocalizedText.ResourceLong(
                        R.string.dict_status_enabled,
                        ModelDownload.bytesToDisplayMb(ModelDownload.installedDictionaryBytes(app.filesDir)),
                    )
                }
                !result.ok -> LocalizedText.Resource(R.string.dict_status_download_failed)
                else -> LocalizedText.Resource(R.string.dict_status_install_failed)
            }
        }
    }

    fun setIdleStatus(status: LocalizedText) = runtime.setIdleStatus(status)
}
