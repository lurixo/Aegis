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
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.user.UserLearnEdit
import com.aegis.ime.user.UserStoreEdits
import java.io.File

internal const val PREF_AUTO_LEARN_ON = "pref_auto_learn_on"

internal const val AUTO_LEARN_DEFAULT_ON = true

@Composable
internal fun AutoLearnToggleCard(resumeSignal: Int) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    val userLearn = File(context.filesDir, "userlearn.txt")
    val clearedToast = stringResource(R.string.user_dict_toast_auto_cleared)
    val writeFailedToast = stringResource(R.string.user_dict_toast_write_failed)
    var on by remember { mutableStateOf(prefs.getBoolean(PREF_AUTO_LEARN_ON, AUTO_LEARN_DEFAULT_ON)) }
    var learnedView by remember { mutableStateOf(UserLearnEdit.view(userLearn)) }
    val learnedHasData = learnedView.hasData
    var pendingClear by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun clearLearned() {
        UserStoreEdits.submit {
            val landed = UserLearnEdit.clear(userLearn)
            val next = UserLearnEdit.view(userLearn)
            mainHandler.post {
                learnedView = next
                Toast.makeText(
                    context,
                    if (landed) clearedToast else writeFailedToast,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    LaunchedEffect(resumeSignal) {
        UserStoreEdits.submit {
            val next = UserLearnEdit.view(userLearn)
            mainHandler.post { learnedView = next }
        }
    }

    AppSection {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.auto_learn_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.auto_learn_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = {
                        on = it
                        prefs.edit { putBoolean(PREF_AUTO_LEARN_ON, it) }
                    },
                    modifier = Modifier.testTag("auto_learn_switch"),
                )
            }
            if (learnedView.readable) {
                Text(
                    stringResource(R.string.user_dict_auto_count_format, learnedView.entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("auto_learn_count"),
                )
                if (learnedView.entries.isEmpty() && learnedHasData) {
                    Text(
                        stringResource(R.string.user_dict_auto_pairs_only),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("auto_learn_pairs_only"),
                    )
                }
            } else {
                Text(
                    stringResource(
                        if (learnedView.entries.isEmpty()) R.string.user_learn_unreadable
                        else R.string.user_learn_unreadable_kept,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("auto_learn_unreadable"),
                )
            }
            AppDangerButton(
                text = stringResource(R.string.user_dict_auto_clear_button),
                onClick = { pendingClear = true },
                enabled = learnedHasData || !learnedView.readable,
                modifier = Modifier.fillMaxWidth().testTag("auto_learn_clear"),
            )
        }
    }

    if (pendingClear) {
        AegisAlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text(stringResource(R.string.user_dict_auto_clear_dialog_title)) },
            text = { Text(stringResource(R.string.user_dict_auto_clear_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearLearned()
                        pendingClear = false
                    },
                ) {
                    Text(stringResource(R.string.user_dict_auto_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) {
                    Text(stringResource(R.string.user_dict_auto_clear_cancel))
                }
            },
        )
    }
}
