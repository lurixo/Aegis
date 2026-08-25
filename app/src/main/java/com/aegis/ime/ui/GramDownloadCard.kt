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
import com.aegis.ime.SettingsHotApply
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.ui.theme.SettingsMotion

internal data class DownloadCardPreview(val present: Boolean, val checking: Boolean = false, val status: String? = null)

@Composable
internal fun GramDownloadCard(
    preview: DownloadCardPreview? = null,
    probe: (String) -> ModelDownload.ValidatorProbe = ModelDownload::remoteValidatorProbe,
    downloader: (Context, String) -> Unit = GramDownloadWork::start,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    val dest = ModelDownload.destFile(context.filesDir)
    val location = dest.parentFile?.absolutePath ?: dest.absolutePath
    val initial = remember { GramDownloadWork.snapshot(context) }
    var present by remember { mutableStateOf(preview?.present ?: initial.present) }
    var status by remember { mutableStateOf(preview?.status?.let(LocalizedText::Raw) ?: initial.status) }
    var progress by remember { mutableStateOf(if (preview == null) initial.progress else null) }
    var downloading by remember { mutableStateOf(if (preview == null) initial.downloading else false) }
    var checking by remember { mutableStateOf(preview?.checking ?: false) }
    var redownloadOffered by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    val updateUnknownToast = stringResource(R.string.download_toast_update_unknown)
    val updateOfflineToast = stringResource(R.string.download_toast_update_offline)
    val updateTimeoutToast = stringResource(R.string.download_toast_update_timeout)
    val updateServerErrorToast = stringResource(R.string.download_toast_update_server_error)
    val updateParseErrorToast = stringResource(R.string.download_toast_update_parse_error)
    val upToDateToast = stringResource(R.string.download_toast_up_to_date)
    val updateFoundToast = stringResource(R.string.download_toast_update_found)
    val downloadFailedToast = stringResource(R.string.gram_status_download_failed)

    val handler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(context, preview) {
        if (preview != null) onDispose { }
        else {
            val dispose = GramDownloadWork.observe(context) { snap ->
                present = snap.present
                downloading = snap.downloading
                progress = snap.progress
                status = snap.status
            }
            onDispose { dispose() }
        }
    }

    fun startDownload(url: String = ModelDownload.GRAM_URL) {
        redownloadOffered = false
        if (preview == null) downloader(context, url)
    }

    fun showCheckFailure(msgRes: Int, message: String) {
        status = LocalizedText.Resource(msgRes)
        AegisToast.show(message)
    }

    fun checkUpdate() {
        checking = true
        val task = Thread {
            val checked = runCatching {
                val remote = probe(ModelDownload.GRAM_URL)
                val local = prefs.all[ModelDownload.VALIDATOR_PREF] as? String
                local to remote
            }
            handler.post {
                checking = false
                val action = checked.fold(
                    onSuccess = { (local, remote) ->
                        ModelDownload.modelUpdateAction(present, local, remote)
                    },
                    onFailure = { ModelDownload.UpdateCheck.PARSE_ERROR },
                )
                redownloadOffered = action == ModelDownload.UpdateCheck.UNKNOWN
                when (action) {
                    null -> {}
                    ModelDownload.UpdateCheck.UNKNOWN -> showCheckFailure(R.string.download_toast_update_unknown, updateUnknownToast)
                    ModelDownload.UpdateCheck.OFFLINE -> showCheckFailure(R.string.download_toast_update_offline, updateOfflineToast)
                    ModelDownload.UpdateCheck.TIMEOUT -> showCheckFailure(R.string.download_toast_update_timeout, updateTimeoutToast)
                    ModelDownload.UpdateCheck.SERVER_ERROR -> showCheckFailure(R.string.download_toast_update_server_error, updateServerErrorToast)
                    ModelDownload.UpdateCheck.PARSE_ERROR -> showCheckFailure(R.string.download_toast_update_parse_error, updateParseErrorToast)
                    ModelDownload.UpdateCheck.UP_TO_DATE -> {
                        status = LocalizedText.Resource(R.string.gram_status_update_current)
                        AegisToast.show(upToDateToast)
                    }
                    ModelDownload.UpdateCheck.UPDATE -> {
                        AegisToast.show(updateFoundToast)
                        startDownload(ModelDownload.GRAM_URL)
                    }
                }
            }
        }.apply { isDaemon = true }
        if (runCatching { task.start() }.isFailure) {
            checking = false
            showCheckFailure(R.string.gram_status_download_failed, downloadFailedToast)
        }
    }

    fun deleteModel() {
        val purged = ModelDownload.purge(context.filesDir)
        present = ModelDownload.isDownloaded(context.filesDir)
        if (!present) {
            prefs.edit()
                .remove(ModelDownload.VALIDATOR_PREF)
                .remove(ModelDownload.GRAM_SHA256_PREF)
                .remove(ModelDownload.GRAM_SIZE_PREF)
                .commit()
            SettingsHotApply.noteEnginePackChanged(prefs)
        }
        progress = if (present) 1f else 0f
        status = when {
            purged -> LocalizedText.Resource(R.string.gram_status_deleted)
            present -> LocalizedText.ResourceLong(
                R.string.gram_status_enabled,
                ModelDownload.bytesToDisplayMb(ModelDownload.installedGramBytes(context.filesDir)),
            )
            else -> LocalizedText.Resource(R.string.gram_status_not_downloaded)
        }
        GramDownloadWork.setIdleStatus(context, status)
    }

    AppSection {
        Column(
            modifier = Modifier.padding(AppSpacing.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
        ) {
            Text(stringResource(R.string.gram_card_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.gram_card_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.download_storage_format, location),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    context.openActivityExternalLink(ModelDownload.REPO_URL)
                },
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    stringResource(R.string.gram_source_link),
                    style = MaterialTheme.typography.labelLarge,
                )
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
                if (checking) stringResource(R.string.download_status_checking_update) else status.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppPrimaryButton(
                    text = stringResource(R.string.download_button),
                    enabled = !downloading && (!present || redownloadOffered),
                    onClick = { startDownload() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                )
                if (present) {
                    AppPrimaryButton(
                        text = stringResource(R.string.check_model_update_button),
                        enabled = !downloading && !checking,
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
            title = { Text(stringResource(R.string.gram_delete_dialog_title)) },
            text = { Text(stringResource(R.string.gram_delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteModel()
                        pendingDelete = false
                    },
                    modifier = Modifier.testTag("gram_delete_confirm"),
                ) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = false },
                    modifier = Modifier.testTag("gram_delete_cancel"),
                ) {
                    Text(stringResource(R.string.download_delete_cancel))
                }
            },
        )
    }
}
