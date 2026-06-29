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

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
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
import com.aegis.ime.user.UserDictImport
import java.io.File

/**
 * E2/E3 — 学习词库 导入/导出. Import is explicit: after picking a file the user chooses 合并 (default;
 * counts accumulate + dedupe) or 覆盖 (the imported file replaces the dictionary). The card spells out
 * the default dictionary location (an app-private path invisible to file managers) so it's clear why
 * 导出 exists — it's the only way to pull a copy out.
 */
@Composable
internal fun UserDictCard() {
    val context = LocalContext.current
    val userDb = File(context.filesDir, "userdb.txt")
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null && userDb.exists()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    userDb.inputStream().use { it.copyTo(out) }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    fun applyImport(uri: Uri, merge: Boolean) {
        val ok = runCatching {
            val tmp = File(context.cacheDir, "import_userdb.txt")
            tmp.delete() // never reuse a stale temp from a prior attempt
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            // ④ The pure apply step validates the import and never wipes the live dict on failure
            // (missing/empty copy, or — for 覆盖 — an import that parses to no entries).
            UserDictImport.apply(tmp, userDb, merge, System.currentTimeMillis())
                .also { tmp.delete() }
        }.getOrDefault(false)
        Toast.makeText(
            context,
            if (ok) (if (merge) "已合并导入学习词库" else "已覆盖导入学习词库")
            else "导入失败：文件无法读取或无有效词条,词库未改动",
            Toast.LENGTH_SHORT,
        ).show()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("学习词库", style = MaterialTheme.typography.titleMedium)
            Text(
                "Aegis 会离线学习你常用的词与下一个词，存为【学习词库】(只存本机)。这与你下载的【主词库】(拼音大词库)是" +
                    "两份独立的库 —— 这里的导入/覆盖只动学习词库,绝不影响主词库。导入会在下次切换到 Aegis 时生效。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "默认词库：${userDb.absolutePath}（应用私有目录，文件管理器不可见 → 用“导出”取出副本，默认文件名 aegis-userdb.txt）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { exportLauncher.launch("aegis-userdb.txt") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出学习词库") }
            Button(
                onClick = { importLauncher.launch(arrayOf("text/plain")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导入学习词库") }
        }
    }

    val uri = pendingImport
    if (uri != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入学习词库") },
            text = {
                Text(
                    "这里导入/导出的是【学习词库】(userdb.txt — 本机学习到的词与词频),不是你下载的【主词库】(拼音大词库)。" +
                        "「合并」把导入内容累加到现有学习词库（去重、词频相加，保留历史）;「覆盖」用导入文件整体替换" +
                        "【学习词库】(不影响已下载的主词库)。",
                )
            },
            confirmButton = {
                TextButton(onClick = { applyImport(uri, merge = true); pendingImport = null }) {
                    Text("合并（推荐）")
                }
            },
            dismissButton = {
                TextButton(onClick = { applyImport(uri, merge = false); pendingImport = null }) {
                    Text("覆盖")
                }
            },
        )
    }
}
