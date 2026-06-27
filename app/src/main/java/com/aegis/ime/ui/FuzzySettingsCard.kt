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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aegis.ime.dict.Fuzzy

/**
 * E4 — 模糊拼音, each confusion rule with its own toggle. A master switch gates the whole feature;
 * the per-rule switches (sub-keyed "fuzzy_<rule>") feed the decoder's per-rule variant matching and
 * are the same prefs the startup screen (J②) drives, so the two stay in sync. Takes effect next time
 * the user switches to Aegis (the engine reads these on (re)load).
 */
@Composable
internal fun FuzzySettingsCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)

    // Human labels for each supported rule (rule set itself is single-sourced from Fuzzy.RULES).
    val labels = mapOf(
        "zh" to ("zh → z" to "翘舌/平舌：知=资、长=藏"),
        "ch" to ("ch → c" to "翘舌/平舌：吃=疵"),
        "sh" to ("sh → s" to "翘舌/平舌：是=四"),
        "ang" to ("ang → an" to "前后鼻音：刚=干、唱=灿"),
        "eng" to ("eng → en" to "前后鼻音：冷=（len）"),
        "ing" to ("ing → in" to "前后鼻音：心=星、林=灵"),
    )

    var master by remember { mutableStateOf(prefs.getBoolean("fuzzy", Fuzzy.DEFAULT_ON)) }
    val ruleOn = remember {
        mutableStateMapOf<String, Boolean>().apply {
            for (rule in Fuzzy.RULES) put(rule.key, prefs.getBoolean(Fuzzy.prefKey(rule.key), true))
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("输入设置 · 模糊拼音", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("模糊拼音（总开关）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "容忍常见拼音混淆；可逐项单独开关。下次切换到 Aegis 生效。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = master,
                    onCheckedChange = {
                        master = it
                        prefs.edit { putBoolean("fuzzy", it) }
                    },
                )
            }
            HorizontalDivider()
            for (rule in Fuzzy.RULES) {
                val (title, desc) = labels[rule.key] ?: (rule.key to "")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        if (desc.isNotEmpty()) {
                            Text(desc, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Switch(
                        checked = ruleOn[rule.key] == true,
                        enabled = master,
                        onCheckedChange = {
                            ruleOn[rule.key] = it
                            prefs.edit { putBoolean(Fuzzy.prefKey(rule.key), it) }
                        },
                    )
                }
            }
        }
    }
}
