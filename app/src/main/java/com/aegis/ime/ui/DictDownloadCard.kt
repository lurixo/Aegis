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
import java.io.File

@Composable
internal fun DictDownloadCard(preview: DownloadCardPreview? = null) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    val zip = ModelDownload.dictZipFile(context.filesDir)
    val location = zip.parentFile?.absolutePath ?: zip.absolutePath
    fun doneLabel(): String {
        val mb = ModelDownload.DICT_PACK_FILES.sumOf { File(context.filesDir, "downloaded/$it").length() } / 1048576
        return "✅ 已下载并启用：全量词库（约 $mb MB，仅存本机；切换/重启 Aegis 后加载更全词库）"
    }
    val notDownloadedLabel = "⚠ 全量词库未下载 —— 可选下载约 98 MB 压缩包（解压约 243 MB；内置高频种子词库已可离线使用）"

    var present by remember { mutableStateOf(preview?.present ?: ModelDownload.isDictDownloaded(context.filesDir)) }
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
            val result = ModelDownload.download(ModelDownload.DICT_URL, zip) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; handler.post { progress = pct / 100f } }
                }
            }
            if (result.ok) handler.post { status = "校验并解压…" }
            val installed = result.ok && ModelDownload.installDictPack(context.filesDir)
            handler.post {
                downloading = false
                present = ModelDownload.isDictDownloaded(context.filesDir)
                when {
                    installed -> {
                        prefs.edit { putString(ModelDownload.DICT_VALIDATOR_PREF, result.validator) }
                        status = doneLabel()
                    }
                    !result.ok -> status = "下载失败"
                    else -> status = "校验或解压失败（文件可能损坏,请重试）"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun checkUpdate() {
        checking = true
        Thread {
            val checked = runCatching {
                ModelDownload.remoteValidator(ModelDownload.DICT_URL) to
                    prefs.getString(ModelDownload.DICT_VALIDATOR_PREF, null)
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
                        status = "已是最新，无更新（全量词库已是最新版本）"
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
            Text("全量词库包（14 表 freq≥1）", style = MaterialTheme.typography.titleMedium)
            Text(
                "可选下载约 243 MB 的全量词库（字/基础/联想/错音/多音/诗词/地名/医学/化学/药品/名人/异体/物种/人名）。" +
                    "内置高频种子词库无需下载即可离线使用；下载后中文候选覆盖更全。仅存本机，输入全程离线。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "存放位置：$location（应用私有目录,文件管理器不可见,仅本机、可删除）。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(ModelDownload.DICT_REPO_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("词库来源：amzxyz/rime-wanxiang ↗", style = MaterialTheme.typography.bodySmall)
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
                        ModelDownload.purgeDict(context.filesDir)
                        prefs.edit { remove(ModelDownload.DICT_VALIDATOR_PREF) }
                        present = false
                        progress = 0f
                        status = "⚠ 全量词库已删除（内置种子词库仍可用）"
                    },
                ) { Text("删除") }
            }
        }
    }
}
