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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aegis.ime.dict.ModelDownload

internal data class DownloadCardPreview(val present: Boolean, val checking: Boolean = false, val status: String? = null)

@Composable
internal fun GramDownloadCard(preview: DownloadCardPreview? = null) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    val dest = ModelDownload.destFile(context.filesDir)
    val location = dest.parentFile?.absolutePath ?: dest.absolutePath
    fun doneLabel() = "✅ 已启用：增强语法生效中（已下载 ${dest.length() / 1048576} MB，仅存本机；切换到 Aegis 后加载）"
    val notDownloadedLabel = "⚠ 增强语法未启用 —— 需下载约 401 MB 模型后才生效"

    var present by remember { mutableStateOf(preview?.present ?: (dest.exists() && dest.length() > 1024)) }
    var status by remember { mutableStateOf(preview?.status ?: if (present) doneLabel() else notDownloadedLabel) }
    var progress by remember { mutableStateOf(0f) }
    var downloading by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(preview?.checking ?: false) }

    val handler = remember { Handler(Looper.getMainLooper()) }

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
                    status = doneLabel()
                } else {
                    status = "下载失败"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun checkUpdate() {
        checking = true
        Thread {
            val checked = runCatching {
                ModelDownload.remoteValidator(ModelDownload.GRAM_URL) to
                    prefs.getString(ModelDownload.VALIDATOR_PREF, null)
            }.getOrNull()
            val remote = checked?.first
            val local = checked?.second
            handler.post {
                checking = false
                when (ModelDownload.updateAction(present, local, remote)) {
                    null -> {}
                    ModelDownload.UpdateCheck.OFFLINE -> {
                        status = "无法检查更新（网络不可用）"
                        Toast.makeText(context, "无法检查更新（网络不可用）", Toast.LENGTH_SHORT).show()
                    }
                    ModelDownload.UpdateCheck.UP_TO_DATE -> {
                        status = "已是最新，无更新（增强模型已是最新版本）"
                        Toast.makeText(context, "已是最新，无更新", Toast.LENGTH_SHORT).show()
                    }
                    ModelDownload.UpdateCheck.UPDATE -> {
                        Toast.makeText(context, "发现更新，开始更新", Toast.LENGTH_SHORT).show()
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
            Text("增强模型（万象离线大模型）", style = MaterialTheme.typography.titleMedium)
            Text(
                "可选下载 ~401 MB。下载后中文候选明显更准（内部评测 top-1 +约 9 分）；仅存本机，输入过程仍全程离线。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "存放位置：$location（应用私有目录，文件管理器不可见，仅本机、可删除）。",
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
                    "模型来源：amzxyz/RIME-LMDG ↗",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (checking) "正在检查更新…" else status,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !downloading && !present,
                    onClick = { startDownload() },
                ) { Text("下载") }
                if (present) {
                    Button(
                        enabled = !downloading && !checking,
                        onClick = { checkUpdate() },
                    ) { Text("检测更新") }
                }
                OutlinedButton(
                    enabled = !downloading && !checking && present,
                    onClick = {
                        ModelDownload.purge(context.filesDir)
                        prefs.edit { remove(ModelDownload.VALIDATOR_PREF) }
                        present = false
                        progress = 0f
                        status = "⚠ 增强语法未启用（已删除；重启输入法后释放已加载的内存模型）"
                    },
                ) { Text("删除") }
            }
        }
    }
}
