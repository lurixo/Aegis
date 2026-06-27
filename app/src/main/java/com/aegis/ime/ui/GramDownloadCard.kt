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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aegis.ime.dict.ModelDownload

@Composable
internal fun GramDownloadCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    val dest = ModelDownload.destFile(context.filesDir)
    val location = dest.parentFile?.absolutePath ?: dest.absolutePath
    fun doneLabel() = "已下载（${dest.length() / 1048576} MB），下次切换到 Aegis 生效"

    var present by remember { mutableStateOf(dest.exists() && dest.length() > 1024) }
    var status by remember { mutableStateOf(if (present) doneLabel() else "未下载") }
    var progress by remember { mutableStateOf(0f) }
    var downloading by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf(false) }

    val handler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(present) {
        if (present && !downloading) {
            checking = true
            Thread {
                val remote = ModelDownload.remoteValidator(ModelDownload.GRAM_URL)
                val local = prefs.getString(ModelDownload.VALIDATOR_PREF, null)
                handler.post {
                    checking = false
                    updateAvailable = ModelDownload.updateAvailable(local, remote)
                }
            }.apply { isDaemon = true }.start()
        } else {
            updateAvailable = false
        }
    }

    fun startDownload() {
        downloading = true
        progress = 0f
        status = "下载中…"
        var lastPct = -1
        Thread {
            val result = ModelDownload.download(ModelDownload.GRAM_URL, dest) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; handler.post { progress = pct / 100f } }
                }
            }
            handler.post {
                downloading = false
                present = dest.exists() && dest.length() > 1024
                if (result.ok) {
                    prefs.edit { putString(ModelDownload.VALIDATOR_PREF, result.validator) }
                    updateAvailable = false
                    status = doneLabel()
                } else {
                    status = "下载失败"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("增强模型（万象离线大模型）", style = MaterialTheme.typography.titleMedium)
            Text(
                "可选下载 ~401 MB。下载后中文候选明显更准（内部评测 top-1 +约 9 分）；仅存本机，输入过程仍全程离线。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "存放位置：$location（应用私有目录，文件管理器不可见，仅本机、可删除）。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (present && checking) "$status（正在检查更新…）" else status,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !downloading && !present,
                    onClick = { startDownload() },
                ) { Text("下载") }
                if (present) {
                    Button(
                        enabled = !downloading && !checking && updateAvailable,
                        onClick = { startDownload() },
                    ) { Text(if (updateAvailable) "更新" else "已是最新") }
                }
                OutlinedButton(
                    enabled = !downloading && present,
                    onClick = {
                        ModelDownload.purge(context.filesDir)
                        prefs.edit { remove(ModelDownload.VALIDATOR_PREF) }
                        present = false
                        updateAvailable = false
                        progress = 0f
                        status = "未下载（重启输入法后释放已加载的内存模型）"
                    },
                ) { Text("删除") }
            }
        }
    }
}
