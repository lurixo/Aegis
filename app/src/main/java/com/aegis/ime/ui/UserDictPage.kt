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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictImport
import com.aegis.ime.user.UserDictSearch
import com.aegis.ime.user.UserLearnEdit
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import java.io.File

@Composable
internal fun UserDictPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val userDb = File(context.filesDir, "userdb.txt")
    val userLearn = File(context.filesDir, "userlearn.txt")
    val importMergedToast = stringResource(R.string.user_dict_toast_import_merged)
    val importOverwrittenToast = stringResource(R.string.user_dict_toast_import_overwritten)
    val importFailedToast = stringResource(R.string.user_dict_toast_import_failed)
    val addedToast = stringResource(R.string.user_dict_toast_added)
    val keptToast = stringResource(R.string.user_dict_toast_kept)
    val addFailedToast = stringResource(R.string.user_dict_toast_add_failed)
    val deletedToast = stringResource(R.string.user_dict_toast_deleted)
    val autoClearedToast = stringResource(R.string.user_dict_toast_auto_cleared)
    val writeFailedToast = stringResource(R.string.user_dict_toast_write_failed)
    val exportBlockedToast = stringResource(R.string.user_dict_toast_export_blocked)
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var pendingAutoClear by remember { mutableStateOf(false) }

    var learnedView by remember { mutableStateOf(UserLearnEdit.view(userLearn)) }
    val learned = learnedView.entries
    val learnedHasData = learnedView.hasData
    var summary by remember { mutableStateOf(UserDictEdit.summary(userDb)) }
    val entries = if (summary.readable) summary.entries else emptyList()
    var query by remember { mutableStateOf("") }
    val searchIndex = remember(entries) { UserDictSearch.index(entries) }
    val filtered = remember(searchIndex, query) { searchIndex.filter(query) }
    val learnedIndex = remember(learned) { UserDictSearch.indexLearned(learned) }
    val filteredLearned = remember(learnedIndex, query) {
        if (query.isBlank()) learned else learnedIndex.filter(query)
    }
    var newWord by remember { mutableStateOf("") }
    var newReading by remember { mutableStateOf("") }
    fun reload() { summary = UserDictEdit.summary(userDb) }

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
            tmp.delete()
            val staged = context.contentResolver.openInputStream(uri)?.use { UserDictImport.stage(it, tmp) } ?: false
            (staged && UserDictEdit.applyImport(userDb, tmp, merge, System.currentTimeMillis()))
                .also { tmp.delete() }
        }.getOrDefault(false)
        if (ok) reload()
        Toast.makeText(
            context,
            if (ok) {
                if (merge) {
                    importMergedToast
                } else {
                    importOverwrittenToast
                }
            } else {
                importFailedToast
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun readingHasLetter(s: String): Boolean = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    fun addWord() {
        val word = newWord.trim()
        if (word.isEmpty() || !readingHasLetter(newReading)) {
            Toast.makeText(context, addFailedToast, Toast.LENGTH_SHORT).show()
            return
        }
        val reading = UserModel.normalizeReading(newReading)
        val known = entries.any { it.reading == reading && it.word == word }
        val saved = UserDictEdit.add(userDb, word, newReading, System.currentTimeMillis())
        if (saved) { newWord = ""; newReading = "" }
        reload()
        val message = when {
            !saved -> writeFailedToast
            known -> keptToast
            else -> addedToast
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun reloadLearned() { learnedView = UserLearnEdit.view(userLearn) }

    fun deleteWord(reading: String, word: String) {
        val saved = UserDictEdit.remove(userDb, reading, word)
        reload()
        reloadLearned()
        Toast.makeText(context, if (saved) deletedToast else writeFailedToast, Toast.LENGTH_SHORT).show()
    }

    fun deleteLearned(entry: UserLearning.Formed) {
        val saved = UserLearnEdit.remove(userLearn, entry.word, entry.reading)
        reloadLearned()
        Toast.makeText(context, if (saved) deletedToast else writeFailedToast, Toast.LENGTH_SHORT).show()
    }

    fun clearLearned() {
        val saved = UserLearnEdit.clear(userLearn)
        reloadLearned()
        Toast.makeText(context, if (saved) autoClearedToast else writeFailedToast, Toast.LENGTH_SHORT).show()
    }

    fun startExport() {
        if (!UserDictEdit.flushBeforeDictionaryExport()) {
            Toast.makeText(context, exportBlockedToast, Toast.LENGTH_SHORT).show()
            return
        }
        exportLauncher.launch("aegis-userdb.txt")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .userDictPageInsets(
                bottomInsets = WindowInsets.safeDrawing,
                topInsets = settingsTopInset(),
            )
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsPageHeader(stringResource(R.string.settings_group_userdict_title), onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.user_dict_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("user_dict_search"),
        )
        if (summary.readable) {
            Text(
                stringResource(R.string.user_dict_count_format, entries.size),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("user_dict_count"),
            )
            Text(
                stringResource(R.string.user_dict_forgotten_format, summary.forgotten),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("user_dict_forgotten"),
            )
        } else {
            Text(
                stringResource(R.string.user_dict_unreadable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("user_dict_unreadable"),
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("user_dict_list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (query.isBlank()) {
                item(key = "tools") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(R.string.user_dict_description),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(R.string.user_dict_default_path_format, userDb.absolutePath),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { startExport() },
                                modifier = Modifier.fillMaxWidth().testTag("user_dict_export"),
                            ) { Text(stringResource(R.string.user_dict_export_button)) }
                            Button(
                                onClick = { importLauncher.launch(arrayOf("text/plain")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.user_dict_import_button)) }

                            HorizontalDivider()

                            Text(
                                stringResource(R.string.user_dict_manual_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.user_dict_manual_description),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = newWord,
                                onValueChange = { newWord = it },
                                label = { Text(stringResource(R.string.user_dict_word_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("user_dict_new_word"),
                            )
                            OutlinedTextField(
                                value = newReading,
                                onValueChange = { newReading = it },
                                label = { Text(stringResource(R.string.user_dict_reading_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.fillMaxWidth().testTag("user_dict_new_reading"),
                            )
                            Button(
                                onClick = { addWord() },
                                modifier = Modifier.fillMaxWidth().testTag("user_dict_add"),
                            ) {
                                Text(stringResource(R.string.user_dict_add_button))
                            }
                        }
                    }
                }
            }
            if (summary.readable && filtered.isEmpty() && (query.isBlank() || filteredLearned.isEmpty())) {
                item(key = "empty") {
                    Text(
                        stringResource(
                            if (query.isBlank()) R.string.user_dict_manual_empty else R.string.user_dict_search_no_match,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp).testTag("user_dict_empty_note"),
                    )
                }
            }
            items(filtered, key = { "${it.reading}\t${it.word}" }) { entry ->
                UserDictEntryRow(entry, onDelete = { deleteWord(entry.reading, entry.word) })
            }
            if (query.isNotBlank() && filteredLearned.isNotEmpty()) {
                item(key = "auto_learn_header") {
                    Text(
                        stringResource(R.string.user_dict_auto_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp).testTag("user_dict_auto_header"),
                    )
                }
                items(filteredLearned, key = { "auto\t${it.word}\t${it.reading}" }) { entry ->
                    LearnedEntryRow(entry, onDelete = { deleteLearned(entry) })
                }
            }
            if (query.isBlank()) {
                item(key = "auto_learn") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(R.string.user_dict_auto_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.user_dict_auto_description),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (learnedView.readable) {
                                Text(
                                    stringResource(R.string.user_dict_auto_count_format, learned.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("user_dict_auto_count"),
                                )
                            } else {
                                Text(
                                    stringResource(R.string.user_learn_unreadable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("user_learn_unreadable"),
                                )
                            }
                            Button(
                                onClick = { pendingAutoClear = true },
                                enabled = learnedHasData || !learnedView.readable,
                                modifier = Modifier.fillMaxWidth().testTag("user_dict_auto_clear"),
                            ) {
                                Text(stringResource(R.string.user_dict_auto_clear_button))
                            }
                        }
                    }
                }
                if (learned.isEmpty() && learnedView.readable) {
                    item(key = "auto_learn_empty") {
                        Text(
                            stringResource(R.string.user_dict_auto_empty),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(learned, key = { "auto\t${it.word}\t${it.reading}" }) { entry ->
                        LearnedEntryRow(entry, onDelete = { deleteLearned(entry) })
                    }
                }
            }
        }
    }

    if (pendingAutoClear) {
        AegisAlertDialog(
            onDismissRequest = { pendingAutoClear = false },
            title = { Text(stringResource(R.string.user_dict_auto_clear_dialog_title)) },
            text = { Text(stringResource(R.string.user_dict_auto_clear_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { clearLearned(); pendingAutoClear = false }) {
                    Text(stringResource(R.string.user_dict_auto_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAutoClear = false }) {
                    Text(stringResource(R.string.user_dict_auto_clear_cancel))
                }
            },
        )
    }

    val uri = pendingImport
    if (uri != null) {
        AegisAlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.user_dict_import_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.user_dict_import_dialog_body),
                )
            },
            confirmButton = {
                TextButton(onClick = { applyImport(uri, merge = true); pendingImport = null }) {
                    Text(stringResource(R.string.user_dict_import_merge))
                }
            },
            dismissButton = {
                TextButton(onClick = { applyImport(uri, merge = false); pendingImport = null }) {
                    Text(stringResource(R.string.user_dict_import_overwrite))
                }
            },
        )
    }
}

internal fun Modifier.userDictPageInsets(
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
): Modifier = this
    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    .windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))

@Composable
private fun LearnedEntryRow(entry: UserLearning.Formed, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.user_dict_entry_format, entry.word, entry.reading),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.user_dict_delete_button))
        }
    }
}

@Composable
private fun UserDictEntryRow(entry: UserModel.Entry, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.user_dict_entry_format, entry.word, entry.reading),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.user_dict_delete_button))
        }
    }
}
