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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.aegis.ime.R
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.ui.theme.AppSpacing

@Composable
internal fun FuzzySettingsCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)

    val labels = mapOf(
        "zh" to (R.string.fuzzy_rule_zh_title to R.string.fuzzy_rule_zh_desc),
        "ch" to (R.string.fuzzy_rule_ch_title to R.string.fuzzy_rule_ch_desc),
        "sh" to (R.string.fuzzy_rule_sh_title to R.string.fuzzy_rule_sh_desc),
        "ang" to (R.string.fuzzy_rule_ang_title to R.string.fuzzy_rule_ang_desc),
        "eng" to (R.string.fuzzy_rule_eng_title to R.string.fuzzy_rule_eng_desc),
        "ing" to (R.string.fuzzy_rule_ing_title to R.string.fuzzy_rule_ing_desc),
        "n_l" to (R.string.fuzzy_rule_n_l_title to R.string.fuzzy_rule_n_l_desc),
        "f_h" to (R.string.fuzzy_rule_f_h_title to R.string.fuzzy_rule_f_h_desc),
        "l_r" to (R.string.fuzzy_rule_l_r_title to R.string.fuzzy_rule_l_r_desc),
        "k_g" to (R.string.fuzzy_rule_k_g_title to R.string.fuzzy_rule_k_g_desc),
    )

    var master by remember { mutableStateOf(prefs.getBoolean("fuzzy", Fuzzy.DEFAULT_ON)) }
    val ruleOn = remember {
        mutableStateMapOf<String, Boolean>().apply {
            for (rule in Fuzzy.RULES) put(rule.key, prefs.getBoolean(Fuzzy.prefKey(rule.key), true))
        }
    }

    AppSection {
        Text(
            stringResource(R.string.fuzzy_card_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                start = AppSpacing.rowHorizontal,
                end = AppSpacing.rowHorizontal,
                top = AppSpacing.sectionPadding,
            ),
        )
        AppSettingRow(
            title = stringResource(R.string.fuzzy_master_title),
            description = stringResource(R.string.fuzzy_master_description),
            trailing = {
                Switch(
                    checked = master,
                    onCheckedChange = {
                        master = it
                        prefs.edit { putBoolean("fuzzy", it) }
                    },
                )
            },
        )
        for (rule in Fuzzy.RULES) {
            val resourcePair = labels[rule.key]
            AppSectionDivider()
            AppSettingRow(
                title = if (resourcePair == null) rule.key else stringResource(resourcePair.first),
                description = resourcePair?.let { stringResource(it.second) },
                trailing = {
                    Switch(
                        checked = ruleOn[rule.key] == true,
                        enabled = master,
                        onCheckedChange = {
                            ruleOn[rule.key] = it
                            prefs.edit { putBoolean(Fuzzy.prefKey(rule.key), it) }
                        },
                    )
                },
            )
        }
    }
}
