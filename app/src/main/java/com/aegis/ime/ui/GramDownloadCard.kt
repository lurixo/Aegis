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
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aegis.ime.R
import com.aegis.ime.SettingsHotApply
import com.aegis.ime.dict.ModelDownload

internal data class DownloadCardPreview(val present: Boolean, val checking: Boolean = false, val status: String? = null)

@Composable
internal fun GramDownloadCard(preview: DownloadCardPreview? = null) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    val dest = ModelDownload.destFile(context.filesDir)
    val location = dest.parentFile?.absolutePath ?: dest.absolutePath
    val initial = remember { GramDownloadWork.snapshot(context) }
    var present by remember { mutableStateOf(preview?.present ?: initial.present) }
    var status by remember { mutableStateOf(preview?.status?.let(LocalizedText::Raw) ?: initial.status) }
    var progress by remember { mutableStateOf(if (preview == null) initial.progress else 0f) }
    var downloading by remember { mutableStateOf(if (preview == null) initial.downloading else false) }
    var checking by remember { mutableStateOf(preview?.checking ?: false) }

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

    fun startDownload() {
        if (preview == null) GramDownloadWork.start(context)
    }

    fun showCheckFailure(msgRes: Int) {
        status = LocalizedText.Resource(msgRes)
        Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
    }

    fun checkUpdate() {
        checking = true
        Thread {
            val probe = ModelDownload.remoteValidatorProbe(ModelDownload.GRAM_URL)
            val local = prefs.getString(ModelDownload.VALIDATOR_PREF, null)
            handler.post {
                checking = false
                when (ModelDownload.modelUpdateAction(present, local, probe)) {
                    null -> {}
                    ModelDownload.UpdateCheck.OFFLINE -> showCheckFailure(R.string.download_toast_update_offline)
                    ModelDownload.UpdateCheck.SERVER_ERROR -> showCheckFailure(R.string.download_toast_update_server_error)
                    ModelDownload.UpdateCheck.PARSE_ERROR -> showCheckFailure(R.string.download_toast_update_parse_error)
                    ModelDownload.UpdateCheck.UP_TO_DATE -> {
                        status = LocalizedText.Resource(R.string.gram_status_update_current)
                        Toast.makeText(context, R.string.download_toast_up_to_date, Toast.LENGTH_SHORT).show()
                    }
                    ModelDownload.UpdateCheck.UPDATE -> {
                        Toast.makeText(context, R.string.download_toast_update_found, Toast.LENGTH_SHORT).show()
                        startDownload()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.gram_card_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.gram_card_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.download_storage_format, location),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(ModelDownload.REPO_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    stringResource(R.string.gram_source_link),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (checking) stringResource(R.string.download_status_checking_update) else status.asString(),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !downloading && !present,
                    onClick = { startDownload() },
                ) { Text(stringResource(R.string.download_button)) }
                if (present) {
                    Button(
                        enabled = !downloading && !checking,
                        onClick = { checkUpdate() },
                    ) { Text(stringResource(R.string.check_model_update_button)) }
                }
                OutlinedButton(
                    enabled = !downloading && !checking && present,
                    onClick = {
                        ModelDownload.purge(context.filesDir)
                        prefs.edit { remove(ModelDownload.VALIDATOR_PREF) }
                        SettingsHotApply.noteEnginePackChanged(prefs)
                        present = false
                        progress = 0f
                        status = LocalizedText.Resource(R.string.gram_status_deleted)
                        GramDownloadWork.setIdleStatus(status)
                    },
                ) { Text(stringResource(R.string.delete_button)) }
            }
        }
    }
}
