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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.aegis.ime.FuzzyPreferences
import com.aegis.ime.R
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.ui.theme.AppSpacing

@Composable
internal fun FuzzySettingsCard() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    }

    val labels = mapOf(
        "zh" to R.string.fuzzy_rule_zh_title,
        "ch" to R.string.fuzzy_rule_ch_title,
        "sh" to R.string.fuzzy_rule_sh_title,
        "ang" to R.string.fuzzy_rule_ang_title,
        "eng" to R.string.fuzzy_rule_eng_title,
        "ing" to R.string.fuzzy_rule_ing_title,
        "n_l" to R.string.fuzzy_rule_n_l_title,
        "f_h" to R.string.fuzzy_rule_f_h_title,
        "l_r" to R.string.fuzzy_rule_l_r_title,
        "k_g" to R.string.fuzzy_rule_k_g_title,
        "iang" to R.string.fuzzy_rule_iang_title,
        "uang" to R.string.fuzzy_rule_uang_title,
        "un_ong" to R.string.fuzzy_rule_un_ong_title,
        "on_ong" to R.string.fuzzy_rule_on_ong_title,
        "un_iong" to R.string.fuzzy_rule_un_iong_title,
        "eng_ong" to R.string.fuzzy_rule_eng_ong_title,
        "an_ai" to R.string.fuzzy_rule_an_ai_title,
        "hui_fei" to R.string.fuzzy_rule_hui_fei_title,
        "hu_fu" to R.string.fuzzy_rule_hu_fu_title,
        "huang_wang" to R.string.fuzzy_rule_huang_wang_title,
    )

    var master by remember { mutableStateOf(prefs.flagOr("fuzzy", Fuzzy.DEFAULT_ON)) }
    val ruleOn = remember {
        mutableStateMapOf<String, Boolean>().apply {
            for (rule in Fuzzy.RULES) put(rule.key, FuzzyPreferences.ruleEnabled(prefs, rule.key))
        }
    }

    AppSection {
        val toggleMaster = {
            master = !master
            prefs.edit { putBoolean("fuzzy", master) }
        }
        AppSettingRow(
            title = stringResource(R.string.fuzzy_master_title),
            description = stringResource(R.string.fuzzy_master_description),
            onClick = toggleMaster,
            trailing = {
                AegisSwitch(
                    checked = master,
                    onCheckedChange = { toggleMaster() },
                )
            },
        )
        if (master) {
            for (rule in Fuzzy.RULES) {
                if (Fuzzy.RULES.first { it.kind == rule.kind } == rule) {
                    HorizontalDivider()
                    Text(
                        stringResource(when (rule.kind) {
                            Fuzzy.Kind.INITIAL -> R.string.fuzzy_group_initial
                            Fuzzy.Kind.FINAL -> R.string.fuzzy_group_final
                            Fuzzy.Kind.SYLLABLE -> R.string.fuzzy_group_syllable
                        }),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(
                            start = AppSpacing.rowHorizontal,
                            end = AppSpacing.rowHorizontal,
                            top = AppSpacing.sectionPadding,
                            bottom = AppSpacing.compactGap,
                        ),
                    )
                } else {
                    AppSectionDivider()
                }
                val titleResource = labels[rule.key]
                val toggleRule = {
                    val next = !(ruleOn[rule.key] == true)
                    ruleOn[rule.key] = next
                    prefs.edit { putBoolean(Fuzzy.prefKey(rule.key), next) }
                }
                AppSettingRow(
                    title = if (titleResource == null) rule.key else stringResource(titleResource),
                    titleStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = MaterialTheme.typography.bodyMedium.fontWeight,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    onClick = if (master) toggleRule else null,
                    trailing = {
                        AegisSwitch(
                            checked = ruleOn[rule.key] == true,
                            enabled = master,
                            onCheckedChange = { toggleRule() },
                        )
                    },
                )
            }
        }
    }
}
