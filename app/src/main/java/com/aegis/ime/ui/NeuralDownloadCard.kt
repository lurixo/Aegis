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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.neural.NeuralCpu
import com.aegis.ime.neural.NeuralModelDownload
import com.aegis.ime.neural.NeuralReranker
import com.aegis.ime.neural.NeuralRuntime
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.ui.theme.SettingsMotion
import kotlinx.coroutines.delay

@Composable
internal fun NeuralDownloadCard(
    resolve: () -> Result<NeuralModelDownload.Manifest> = { NeuralModelDownload.resolve() },
    check: (Context) -> NeuralModelDownload.UpdateResult = { ctx ->
        NeuralModelDownload.checkUpdate(ctx.filesDir, ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE))
    },
    downloader: (Context, NeuralModelDownload.Manifest) -> Unit = NeuralDownloadWork::start,
    cpuSupported: Boolean = NeuralCpu.supported,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    val location = NeuralModelDownload.modelDir(context.filesDir).absolutePath
    val initial = remember { NeuralDownloadWork.snapshot(context) }
    var present by remember { mutableStateOf(initial.present) }
    var status by remember { mutableStateOf(initial.status) }
    var progress by remember { mutableStateOf(initial.progress) }
    var downloading by remember { mutableStateOf(initial.downloading) }
    var model by remember { mutableStateOf(NeuralDownloadWork.installedModel(context)) }
    var fetching by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    var reranker by remember { mutableStateOf(NeuralRuntime.active?.snapshot()) }
    val updateUnknownToast = stringResource(R.string.download_toast_update_unknown)
    val updateOfflineToast = stringResource(R.string.download_toast_update_offline)
    val updateTimeoutToast = stringResource(R.string.download_toast_update_timeout)
    val updateServerErrorToast = stringResource(R.string.download_toast_update_server_error)
    val updateParseErrorToast = stringResource(R.string.download_toast_update_parse_error)
    val upToDateToast = stringResource(R.string.download_toast_up_to_date)
    val updateFoundToast = stringResource(R.string.download_toast_update_found)
    val downloadFailedToast = stringResource(R.string.neural_status_download_failed)

    val handler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(context) {
        val dispose = NeuralDownloadWork.observe(context) { snap ->
            present = snap.present
            downloading = snap.downloading
            progress = snap.progress
            status = snap.status
            model = if (snap.present) NeuralDownloadWork.installedModel(context) else null
        }
        onDispose { dispose() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            reranker = NeuralRuntime.active?.snapshot()
            delay(STATS_REFRESH_MILLIS)
        }
    }

    fun requestDownload() {
        fetching = true
        val task = Thread {
            val resolved = runCatching { resolve().getOrThrow() }
            handler.post {
                fetching = false
                resolved.fold(
                    onSuccess = { downloader(context, it) },
                    onFailure = { NeuralDownloadWork.setIdleStatus(context, metadataFailureStatus(it)) },
                )
            }
        }.apply { isDaemon = true }
        if (runCatching { task.start() }.isFailure) {
            fetching = false
            AegisToast.show(downloadFailedToast)
        }
    }

    fun checkUpdate() {
        checking = true
        val task = Thread {
            val checked = runCatching { check(context) }
            handler.post {
                checking = false
                val result = checked.getOrElse { error ->
                    NeuralModelDownload.UpdateResult(
                        when (ModelDownload.identifyRequestFailure(error)) {
                            ModelDownload.CheckFailure.OFFLINE -> ModelDownload.UpdateCheck.OFFLINE
                            ModelDownload.CheckFailure.TIMEOUT -> ModelDownload.UpdateCheck.TIMEOUT
                            ModelDownload.CheckFailure.SERVER -> ModelDownload.UpdateCheck.SERVER_ERROR
                            ModelDownload.CheckFailure.PARSE -> ModelDownload.UpdateCheck.PARSE_ERROR
                            null -> ModelDownload.UpdateCheck.UNKNOWN
                        },
                    )
                }
                model = NeuralDownloadWork.installedModel(context)
                when (if (present) result.state else null) {
                    null -> {}
                    ModelDownload.UpdateCheck.UNKNOWN -> AegisToast.show(updateUnknownToast)
                    ModelDownload.UpdateCheck.OFFLINE -> AegisToast.show(updateOfflineToast)
                    ModelDownload.UpdateCheck.TIMEOUT -> AegisToast.show(updateTimeoutToast)
                    ModelDownload.UpdateCheck.SERVER_ERROR -> AegisToast.show(updateServerErrorToast)
                    ModelDownload.UpdateCheck.PARSE_ERROR -> AegisToast.show(updateParseErrorToast)
                    ModelDownload.UpdateCheck.UP_TO_DATE -> AegisToast.show(upToDateToast)
                    ModelDownload.UpdateCheck.UPDATE -> result.manifest?.let { newer ->
                        AegisToast.show(updateFoundToast)
                        downloader(context, newer)
                    }
                }
            }
        }.apply { isDaemon = true }
        if (runCatching { task.start() }.isFailure) {
            checking = false
            AegisToast.show(downloadFailedToast)
        }
    }

    fun deleteModel() {
        val deleted = NeuralModelDownload.delete(context.filesDir, prefs)
        val remaining = NeuralDownloadWork.installedModel(context)
        model = remaining
        present = remaining != null
        progress = if (present) 1f else 0f
        status = when {
            deleted -> LocalizedText.Resource(R.string.neural_status_deleted)
            remaining != null -> LocalizedText.ResourceLong(
                R.string.neural_status_enabled,
                ModelDownload.bytesToDisplayMb(remaining.bytes),
            )
            else -> LocalizedText.Resource(R.string.neural_status_not_downloaded)
        }
        NeuralDownloadWork.setIdleStatus(context, status)
    }

    AppSection {
        Column(
            modifier = Modifier.padding(AppSpacing.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
        ) {
            Text(stringResource(R.string.neural_card_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.neural_card_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.download_storage_format, location),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { context.openActivityExternalLink(NeuralModelDownload.SOURCE_URL) },
                shape = MaterialTheme.shapes.extraSmall,
                interactionSource = rememberSettingsPressSource(),
            ) {
                Text(stringResource(R.string.neural_source_link), style = MaterialTheme.typography.labelLarge)
            }
            AnimatedVisibility(
                visible = downloading,
                enter = SettingsMotion.revealEnter(),
                exit = SettingsMotion.collapseExit(),
            ) {
                val currentProgress = progress
                if (currentProgress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { currentProgress }, modifier = Modifier.fillMaxWidth())
                }
            }
            Text(
                when {
                    !cpuSupported -> stringResource(R.string.neural_status_unsupported)
                    fetching -> stringResource(R.string.neural_status_fetching)
                    else -> status.asString()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cpuSupported) {
                model?.let { installed ->
                    NeuralModelState(installed.spec.name, reranker)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppPrimaryButton(
                    text = stringResource(R.string.download_button),
                    enabled = cpuSupported && !downloading && !fetching && !checking && !present,
                    onClick = { requestDownload() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                )
                if (present) {
                    AppPrimaryButton(
                        text = stringResource(
                            if (checking) R.string.download_status_checking_update else R.string.check_model_update_button,
                        ),
                        enabled = cpuSupported && !downloading && !checking,
                        onClick = { checkUpdate() },
                        modifier = Modifier.weight(2f).fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(2f))
                }
                AppPrimaryButton(
                    text = stringResource(R.string.delete_button),
                    enabled = !downloading && !checking && present,
                    onClick = { pendingDelete = true },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (pendingDelete) {
        AegisAlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(stringResource(R.string.neural_delete_dialog_title)) },
            text = { Text(stringResource(R.string.neural_delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteModel()
                        pendingDelete = false
                    },
                    modifier = Modifier.testTag("neural_delete_confirm"),
                ) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = false },
                    modifier = Modifier.testTag("neural_delete_cancel"),
                ) {
                    Text(stringResource(R.string.download_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun NeuralModelState(name: String, current: NeuralReranker.Snapshot?) {
    val state = when (current?.phase) {
        null -> stringResource(R.string.neural_state_idle)
        NeuralReranker.Phase.LOADING -> stringResource(R.string.neural_state_loading)
        NeuralReranker.Phase.READY -> stringResource(R.string.neural_state_ready, current.loadMillis)
        NeuralReranker.Phase.FAILED -> stringResource(R.string.neural_state_failed, current.error.orEmpty())
        NeuralReranker.Phase.CLOSED -> stringResource(R.string.neural_state_closed)
    }
    Text(
        stringResource(R.string.neural_card_model, name),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (current != null && current.runs > 0) {
        Text(
            stringResource(R.string.neural_card_runs, current.runs, current.changed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.neural_card_timing, current.lastMillis, current.meanMillis, current.maxMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (current != null && current.timeouts > 0) {
        Text(
            stringResource(R.string.neural_card_timeouts, current.timeouts),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val STATS_REFRESH_MILLIS = 1_000L
