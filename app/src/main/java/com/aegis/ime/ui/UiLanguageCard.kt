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

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppSpacing

internal fun appLocaleTag(context: Context): String? =
    context.getSystemService(LocaleManager::class.java)
        ?.applicationLocales
        ?.takeIf { !it.isEmpty }
        ?.get(0)
        ?.toLanguageTag()

internal fun setAppLocaleTag(context: Context, tag: String?) {
    val manager = context.getSystemService(LocaleManager::class.java) ?: return
    manager.applicationLocales =
        if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}

@Composable
internal fun UiLanguageCard(
    read: (Context) -> String? = ::appLocaleTag,
    write: (Context, String?) -> Unit = ::setAppLocaleTag,
) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(read(context)?.substringBefore('-')) }

    AppSection {
        Column(
            modifier = Modifier.padding(AppSpacing.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
        ) {
            Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_language_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppSectionDivider()
        AppChoiceGroup {
            listOf(
                null to R.string.language_follow_system,
                "zh" to R.string.language_zh,
                "en" to R.string.language_en,
            ).forEach { (value, labelRes) ->
                AppChoiceRow(
                    label = stringResource(labelRes),
                    selected = choice == value,
                    onSelect = {
                        choice = value
                        write(context, if (value == "zh") "zh-CN" else value)
                    },
                )
            }
        }
    }
}
