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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.ime.R

private data class LicenseItem(
    val nameRes: Int,
    val copyright: String,
    val license: String,
    val url: String,
    val noteRes: Int,
    val modified: Boolean,
)

private val LICENSE_ITEMS = listOf(
    LicenseItem(R.string.license_wanxiang_name, "amzxyz", "CC BY 4.0", "https://github.com/amzxyz/rime-wanxiang", R.string.license_wanxiang_note, modified = true),
    LicenseItem(R.string.license_octagram_name, "amzxyz", "CC BY 4.0", "https://github.com/amzxyz/RIME-LMDG", R.string.license_octagram_note, modified = false),
    LicenseItem(R.string.license_opencc_name, "BYVoid", "Apache-2.0", "https://github.com/BYVoid/OpenCC", R.string.license_opencc_note, modified = true),
    LicenseItem(R.string.license_emoji_name, "Unicode, Inc.", "Unicode License", "https://www.unicode.org/license.txt", R.string.license_emoji_note, modified = true),
    LicenseItem(R.string.license_tgh_name, "PRC State Council", "National standard", "https://www.gov.cn/zwgk/2013-08/19/content_2469793.htm", R.string.license_tgh_note, modified = true),
    LicenseItem(R.string.license_androidx_name, "AOSP · JetBrains", "Apache-2.0", "https://developer.android.com/jetpack/androidx", R.string.license_androidx_note, modified = false),
)

@Composable
internal fun LicensesPage(onBack: () -> Unit) {
    SettingsPageColumn(stringResource(R.string.licenses_page_title), onBack) {
        Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium)
        for (item in LICENSE_ITEMS) {
            LicenseCard(item)
        }
        Text(
            stringResource(R.string.licenses_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseCard(item: LicenseItem) {
    val modifiedLabel = stringResource(R.string.licenses_modified)
    val context = LocalContext.current
    Card(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(item.nameRes), style = MaterialTheme.typography.titleMedium)
            val meta = "${item.copyright} · ${item.license}" + if (item.modified) " · $modifiedLabel" else ""
            Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(item.noteRes), style = MaterialTheme.typography.bodySmall)
            Text("${item.url} ↗", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
