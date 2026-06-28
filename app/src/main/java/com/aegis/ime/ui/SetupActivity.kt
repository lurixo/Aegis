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
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.aegis.ime.ui.theme.AegisTheme

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var typed by remember { mutableStateOf("") }
    var showDownloadHint by remember { mutableStateOf(!prefs.getBoolean("dl_hint_dismissed", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Aegis 输入法", style = MaterialTheme.typography.headlineMedium)
        Text(
            "离线中文 / 英文输入法。自建拼音引擎（全拼 + 九宫格 T9），模糊拼音、简拼、中英混输、" +
                "英文补全纠错、离线自学习；可选下载万象大模型增强。全程离线，输入不联网。",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (showDownloadHint) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("首次使用", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "下方可选下载【增强模型】与【全量词库】(都不是必须的)。内置种子词库与基础语法已能离线打字," +
                            "想要更准/更全时再下载即可。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            showDownloadHint = false
                            prefs.edit { putBoolean("dl_hint_dismissed", true) }
                        },
                    ) { Text("知道了") }
                }
            }
        }

        GramDownloadCard()
        DictDownloadCard()
        FuzzySettingsCard()
        AssociationToggleCard()
        LayoutChoiceCard()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("常用语管理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "管理剪贴板面板里的常用语：新建/重命名/删除分类,增删短语。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { context.startActivity(Intent(context, PhraseManagerActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("打开常用语管理") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("启用步骤", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("1 · 在系统设置中启用 Aegis") }
                Button(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("2 · 切换到 Aegis 输入法") }
            }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("3 · 在此试打") },
            modifier = Modifier.fillMaxWidth(),
        )

        UserDictCard()
    }
}
