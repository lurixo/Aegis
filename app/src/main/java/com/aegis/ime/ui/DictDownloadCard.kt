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

@Composable
internal fun DictDownloadCard(
    preview: DownloadCardPreview? = null,
    check: (ModelDownload.DictionaryInstallMetadata) -> ModelDownload.DictionaryUpdateCheck =
        ModelDownload::checkDictionaryUpdate,
    downloader: (Context, ModelDownload.DictionaryAsset?) -> Unit = DictDownloadWork::start,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    val zip = ModelDownload.dictZipFile(context.filesDir)
    val location = zip.parentFile?.absolutePath ?: zip.absolutePath
    val initial = remember { DictDownloadWork.snapshot(context) }
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
    val downloadFailedToast = stringResource(R.string.dict_status_download_failed)

    val handler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(context, preview) {
        if (preview != null) onDispose { }
        else {
            val dispose = DictDownloadWork.observe(context) { snap ->
                present = snap.present
                downloading = snap.downloading
                progress = snap.progress
                status = snap.status
            }
            onDispose { dispose() }
        }
    }

    fun currentInstallMetadata(): ModelDownload.DictionaryInstallMetadata {
        ModelDownload.recoverInterruptedDictionaryInstall(context.filesDir)
        val storedSha = prefs.all[ModelDownload.DICT_SHA256_PREF] as? String
        val fileSha = ModelDownload.installedDictionaryFileSha(context.filesDir)
        val versionUnknown = ModelDownload.dictionaryVersionUnknown(context.filesDir)
        val resolvedSha = ModelDownload.resolvedInstalledDictionarySha(
            context.filesDir,
            storedSha,
        )
        if (resolvedSha != storedSha || versionUnknown) {
            val editor = prefs.edit().putString(ModelDownload.DICT_SHA256_PREF, resolvedSha)
            if (fileSha != null || versionUnknown) {
                editor
                    .remove(ModelDownload.DICT_VALIDATOR_PREF)
                    .remove(ModelDownload.DICT_ASSET_NAME_PREF)
                    .remove(ModelDownload.DICT_ASSET_URL_PREF)
                    .remove(ModelDownload.DICT_RELEASE_TAG_PREF)
                    .remove(ModelDownload.DICT_RELEASE_PUBLISHED_PREF)
            }
            editor.commit()
        }
        return ModelDownload.DictionaryInstallMetadata(
            sha256 = resolvedSha,
            publishedAt = prefs.all[ModelDownload.DICT_RELEASE_PUBLISHED_PREF] as? String,
            complete = ModelDownload.isDictPackComplete(context.filesDir),
        )
    }

    fun startDownload(asset: ModelDownload.DictionaryAsset? = null) {
        redownloadOffered = false
        if (preview == null) downloader(context, asset)
    }

    fun showCheckFailure(message: String) {
        AegisToast.show(message)
    }

    fun checkUpdate() {
        checking = true
        val task = Thread {
            val checked = runCatching { check(currentInstallMetadata()) }
            handler.post {
                checking = false
                val result = checked.getOrElse { error ->
                    ModelDownload.DictionaryUpdateCheck(
                        when (ModelDownload.identifyRequestFailure(error)) {
                            ModelDownload.CheckFailure.OFFLINE -> ModelDownload.UpdateCheck.OFFLINE
                            ModelDownload.CheckFailure.TIMEOUT -> ModelDownload.UpdateCheck.TIMEOUT
                            ModelDownload.CheckFailure.SERVER -> ModelDownload.UpdateCheck.SERVER_ERROR
                            ModelDownload.CheckFailure.PARSE -> ModelDownload.UpdateCheck.PARSE_ERROR
                            null -> ModelDownload.UpdateCheck.UNKNOWN
                        },
                    )
                }
                val action = if (present) result.state else null
                redownloadOffered = action == ModelDownload.UpdateCheck.UNKNOWN
                when (action) {
                    null -> {}
                    ModelDownload.UpdateCheck.UNKNOWN -> showCheckFailure(updateUnknownToast)
                    ModelDownload.UpdateCheck.OFFLINE -> showCheckFailure(updateOfflineToast)
                    ModelDownload.UpdateCheck.TIMEOUT -> showCheckFailure(updateTimeoutToast)
                    ModelDownload.UpdateCheck.SERVER_ERROR -> showCheckFailure(updateServerErrorToast)
                    ModelDownload.UpdateCheck.PARSE_ERROR -> showCheckFailure(updateParseErrorToast)
                    ModelDownload.UpdateCheck.UP_TO_DATE -> AegisToast.show(upToDateToast)
                    ModelDownload.UpdateCheck.UPDATE -> {
                        AegisToast.show(updateFoundToast)
                        startDownload(result.asset)
                    }
                }
            }
        }.apply { isDaemon = true }
        if (runCatching { task.start() }.isFailure) {
            checking = false
            showCheckFailure(downloadFailedToast)
        }
    }

    fun deleteDict() {
        if (ModelDownload.dictionaryTransactionInProgress(context.filesDir)) return
        val purged = ModelDownload.purgeDict(context.filesDir)
        if (
            !purged &&
            ModelDownload.dictionaryTransactionInProgress(context.filesDir)
        ) return
        present = ModelDownload.isDictDownloaded(context.filesDir)
        if (!present) {
            prefs.edit()
                .remove(ModelDownload.DICT_VALIDATOR_PREF)
                .remove(ModelDownload.DICT_SHA256_PREF)
                .remove(ModelDownload.DICT_ASSET_NAME_PREF)
                .remove(ModelDownload.DICT_ASSET_URL_PREF)
                .remove(ModelDownload.DICT_RELEASE_TAG_PREF)
                .remove(ModelDownload.DICT_RELEASE_PUBLISHED_PREF)
                .commit()
            SettingsHotApply.noteEnginePackChanged(prefs)
        }
        progress = if (present) 1f else 0f
        status = when {
            purged -> LocalizedText.Resource(R.string.dict_status_deleted)
            present -> LocalizedText.ResourceLong(
                R.string.dict_status_enabled,
                ModelDownload.bytesToDisplayMb(ModelDownload.installedDictionaryBytes(context.filesDir)),
            )
            else -> LocalizedText.Resource(R.string.dict_status_not_downloaded)
        }
        DictDownloadWork.setIdleStatus(context, status)
    }

    AppSection {
        Column(
            modifier = Modifier.padding(AppSpacing.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
        ) {
            Text(stringResource(R.string.dict_card_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dict_card_description),
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
                    context.openActivityExternalLink(ModelDownload.DICT_REPO_URL)
                },
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(stringResource(R.string.dict_source_link), style = MaterialTheme.typography.labelLarge)
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
                status.asString(),
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
                        text = stringResource(
                            if (checking) R.string.download_status_checking_update else R.string.check_dict_update_button,
                        ),
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
            title = { Text(stringResource(R.string.dict_delete_dialog_title)) },
            text = { Text(stringResource(R.string.dict_delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDict()
                        pendingDelete = false
                    },
                    modifier = Modifier.testTag("dict_delete_confirm"),
                ) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = false },
                    modifier = Modifier.testTag("dict_delete_cancel"),
                ) {
                    Text(stringResource(R.string.download_delete_cancel))
                }
            },
        )
    }
}
