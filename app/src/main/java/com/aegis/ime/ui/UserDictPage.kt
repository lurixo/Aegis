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
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictImport
import com.aegis.ime.user.UserDictSearch
import com.aegis.ime.user.UserLearnEdit
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import com.aegis.ime.user.UserStoreEdits
import java.io.File

@Composable
internal fun UserDictPage(resumeSignal: Int = 0, onBack: () -> Unit) {
    val context = LocalContext.current
    val userDb = File(context.filesDir, "userdb.txt")
    val userLearn = File(context.filesDir, "userlearn.txt")
    val importMergedToast = stringResource(R.string.user_dict_toast_import_merged)
    val importOverwrittenToast = stringResource(R.string.user_dict_toast_import_overwritten)
    val importFailedToast = stringResource(R.string.user_dict_toast_import_failed)
    val addedToast = stringResource(R.string.user_dict_toast_added)
    val keptToast = stringResource(R.string.user_dict_toast_kept)
    val addFailedToast = stringResource(R.string.user_dict_toast_add_failed)
    val addRejectedToast = stringResource(R.string.user_dict_toast_add_rejected)
    val deletedToast = stringResource(R.string.user_dict_toast_deleted)
    val batchDeletedToast = stringResource(R.string.user_dict_toast_batch_deleted)
    val autoClearedToast = stringResource(R.string.user_dict_toast_auto_cleared)
    val writeFailedToast = stringResource(R.string.user_dict_toast_write_failed)
    val exportBlockedToast = stringResource(R.string.user_dict_toast_export_blocked)
    val exportDoneToast = stringResource(R.string.user_dict_toast_export_done)
    val exportFailedToast = stringResource(R.string.user_dict_toast_export_failed)
    val exportEmptyToast = stringResource(R.string.user_dict_toast_export_empty)
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var pendingAutoClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var pendingBatchDelete by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

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
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun manualKey(entry: UserModel.Entry) = "${entry.reading}\t${entry.word}"
    fun learnedKey(entry: UserLearning.Formed) = "auto\t${entry.word}\t${entry.reading}"
    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    fun search(next: String) {
        query = next
        selected = emptySet()
    }

    fun edit(success: String, failure: String, done: (Boolean) -> Unit = {}, work: () -> Boolean) {
        UserStoreEdits.submit {
            val landed = runCatching(work).getOrDefault(false)
            val nextSummary = UserDictEdit.summary(userDb)
            val nextLearned = UserLearnEdit.view(userLearn)
            mainHandler.post {
                summary = nextSummary
                learnedView = nextLearned
                done(landed)
                Toast.makeText(context, if (landed) success else failure, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(resumeSignal) {
        UserStoreEdits.submit {
            val nextSummary = UserDictEdit.summary(userDb)
            val nextLearned = UserLearnEdit.view(userLearn)
            mainHandler.post {
                summary = nextSummary
                learnedView = nextLearned
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            UserStoreEdits.submit {
                val outcome = UserDictEdit.exportDictionary(
                    userDb,
                    runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull(),
                )
                mainHandler.post {
                    Toast.makeText(
                        context,
                        when (outcome) {
                            UserDictEdit.ExportResult.WRITTEN -> exportDoneToast
                            UserDictEdit.ExportResult.NOTHING_TO_EXPORT -> exportEmptyToast
                            UserDictEdit.ExportResult.NOT_WRITTEN -> exportFailedToast
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    fun applyImport(uri: Uri, merge: Boolean) {
        edit(if (merge) importMergedToast else importOverwrittenToast, importFailedToast) {
            val staging = File(context.cacheDir, "import_userdb.txt")
            staging.delete()
            val staged = context.contentResolver.openInputStream(uri)?.use { UserDictImport.stage(it, staging) } ?: false
            (staged && UserDictEdit.applyImport(userDb, staging, merge, System.currentTimeMillis()))
                .also { staging.delete() }
        }
    }

    fun readingHasLetter(s: String): Boolean = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    fun addWord() {
        val word = newWord.trim()
        val typedReading = newReading
        if (word.isEmpty() || !readingHasLetter(typedReading)) {
            Toast.makeText(context, addFailedToast, Toast.LENGTH_SHORT).show()
            return
        }
        if (!UserModel.acceptsManualWord(word, typedReading)) {
            Toast.makeText(context, addRejectedToast, Toast.LENGTH_SHORT).show()
            return
        }
        val reading = UserModel.normalizeReading(typedReading)
        val known = entries.any { it.reading == reading && it.word == word }
        edit(
            if (known) keptToast else addedToast,
            writeFailedToast,
            { landed -> if (landed) { newWord = ""; newReading = "" } },
        ) {
            UserDictEdit.add(userDb, word, typedReading, System.currentTimeMillis())
        }
    }

    fun deleteWord(reading: String, word: String) {
        edit(deletedToast, writeFailedToast) { UserDictEdit.remove(userDb, reading, word) }
    }

    fun deleteLearned(word: String, reading: String) {
        edit(deletedToast, writeFailedToast) { UserLearnEdit.remove(userLearn, word, reading) }
    }

    fun confirmDelete(target: PendingDelete) {
        if (target.learned) deleteLearned(target.word, target.reading) else deleteWord(target.reading, target.word)
    }

    fun deleteSelected() {
        val chosenWords = entries.filter { manualKey(it) in selected }
        val chosenLearned = learned.filter { learnedKey(it) in selected }
        selecting = false
        selected = emptySet()
        edit(batchDeletedToast, writeFailedToast) {
            val words = UserDictEdit.removeAll(userDb, chosenWords)
            val glued = UserLearnEdit.removeAll(userLearn, chosenLearned)
            words && glued
        }
    }

    fun clearLearned() {
        edit(autoClearedToast, writeFailedToast) { UserLearnEdit.clear(userLearn) }
    }

    fun startExport() {
        UserStoreEdits.submit {
            val ready = UserDictEdit.flushBeforeDictionaryExport()
            val anythingToExport = UserDictEdit.hasDictionaryToExport(userDb)
            mainHandler.post {
                when {
                    !ready -> Toast.makeText(context, exportBlockedToast, Toast.LENGTH_SHORT).show()
                    !anythingToExport -> Toast.makeText(context, exportEmptyToast, Toast.LENGTH_SHORT).show()
                    else -> runCatching { exportLauncher.launch("aegis-userdb.txt") }
                }
            }
        }
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
            onValueChange = { search(it) },
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!selecting) {
                TextButton(
                    onClick = { selecting = true },
                    enabled = filtered.isNotEmpty() || filteredLearned.isNotEmpty(),
                    modifier = Modifier.testTag("user_dict_select"),
                ) {
                    Text(stringResource(R.string.user_dict_select_button))
                }
            } else {
                TextButton(
                    onClick = {
                        selected = (filtered.map { manualKey(it) } + filteredLearned.map { learnedKey(it) }).toSet()
                    },
                    modifier = Modifier.testTag("user_dict_select_all"),
                ) {
                    Text(stringResource(R.string.user_dict_select_all_button))
                }
                TextButton(
                    onClick = { pendingBatchDelete = true },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.testTag("user_dict_delete_selected"),
                ) {
                    Text(stringResource(R.string.user_dict_delete_selected_button))
                }
                TextButton(
                    onClick = { selecting = false; selected = emptySet() },
                    modifier = Modifier.testTag("user_dict_select_cancel"),
                ) {
                    Text(stringResource(R.string.user_dict_select_cancel_button))
                }
            }
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
            items(filtered, key = { manualKey(it) }) { entry ->
                UserDictEntryRow(
                    entry,
                    selecting = selecting,
                    checked = manualKey(entry) in selected,
                    onToggle = { toggle(manualKey(entry)) },
                    onDelete = { pendingDelete = PendingDelete(entry.word, entry.reading, learned = false) },
                )
            }
            if (query.isNotBlank() && filteredLearned.isNotEmpty()) {
                item(key = "auto_learn_header") {
                    Text(
                        stringResource(R.string.user_dict_auto_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp).testTag("user_dict_auto_header"),
                    )
                }
                items(filteredLearned, key = { learnedKey(it) }) { entry ->
                    LearnedEntryRow(
                        entry,
                        selecting = selecting,
                        checked = learnedKey(entry) in selected,
                        onToggle = { toggle(learnedKey(entry)) },
                        onDelete = { pendingDelete = PendingDelete(entry.word, entry.reading, learned = true) },
                    )
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
                                if (learned.isEmpty() && learnedHasData) {
                                    Text(
                                        stringResource(R.string.user_dict_auto_pairs_only),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("user_dict_auto_pairs_only"),
                                    )
                                }
                            } else {
                                Text(
                                    stringResource(
                                        if (learned.isEmpty()) R.string.user_learn_unreadable
                                        else R.string.user_learn_unreadable_kept,
                                    ),
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
                    items(learned, key = { learnedKey(it) }) { entry ->
                        LearnedEntryRow(
                            entry,
                            selecting = selecting,
                            checked = learnedKey(entry) in selected,
                            onToggle = { toggle(learnedKey(entry)) },
                            onDelete = { pendingDelete = PendingDelete(entry.word, entry.reading, learned = true) },
                        )
                    }
                }
            }
        }
    }

    val rowDelete = pendingDelete
    if (rowDelete != null) {
        AegisAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.user_dict_delete_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.user_dict_delete_dialog_body,
                        stringResource(R.string.user_dict_entry_format, rowDelete.word, rowDelete.reading),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete(rowDelete); pendingDelete = null },
                    modifier = Modifier.testTag("user_dict_delete_confirm"),
                ) {
                    Text(stringResource(R.string.user_dict_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.testTag("user_dict_delete_cancel"),
                ) {
                    Text(stringResource(R.string.user_dict_delete_cancel))
                }
            },
        )
    }

    if (pendingBatchDelete) {
        val chosenCount = entries.count { manualKey(it) in selected } +
            learned.count { learnedKey(it) in selected }
        AegisAlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = { Text(stringResource(R.string.user_dict_batch_delete_dialog_title)) },
            text = { Text(stringResource(R.string.user_dict_batch_delete_dialog_body, chosenCount)) },
            confirmButton = {
                TextButton(
                    onClick = { deleteSelected(); pendingBatchDelete = false },
                    modifier = Modifier.testTag("user_dict_batch_delete_confirm"),
                ) {
                    Text(stringResource(R.string.user_dict_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingBatchDelete = false },
                    modifier = Modifier.testTag("user_dict_batch_delete_cancel"),
                ) {
                    Text(stringResource(R.string.user_dict_delete_cancel))
                }
            },
        )
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

private class PendingDelete(val word: String, val reading: String, val learned: Boolean)

internal fun Modifier.userDictPageInsets(
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
): Modifier = this
    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    .windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))

@Composable
private fun LearnedEntryRow(
    entry: UserLearning.Formed,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    DictEntryRow(
        text = stringResource(R.string.user_dict_entry_format, entry.word, entry.reading),
        selecting = selecting,
        checked = checked,
        onToggle = onToggle,
        onDelete = onDelete,
    )
}

@Composable
private fun UserDictEntryRow(
    entry: UserModel.Entry,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    DictEntryRow(
        text = stringResource(R.string.user_dict_entry_format, entry.word, entry.reading),
        selecting = selecting,
        checked = checked,
        onToggle = onToggle,
        onDelete = onDelete,
    )
}

@Composable
private fun DictEntryRow(
    text: String,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selecting) {
                    Modifier.toggleable(value = checked, onValueChange = { onToggle() })
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!selecting) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.user_dict_delete_button))
            }
        }
    }
}
