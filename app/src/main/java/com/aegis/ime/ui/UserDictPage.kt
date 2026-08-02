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
import com.aegis.ime.user.UserModel
import com.aegis.ime.user.UserDataDatabase
import java.io.File

@Composable
internal fun UserDictPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val userDb = File(context.filesDir, "userdb.txt")
    val userDataPath = File(context.filesDir, UserDataDatabase.DATABASE_NAME).absolutePath
    val importMergedToast = stringResource(R.string.user_dict_toast_import_merged)
    val importOverwrittenToast = stringResource(R.string.user_dict_toast_import_overwritten)
    val importFailedToast = stringResource(R.string.user_dict_toast_import_failed)
    val addedToast = stringResource(R.string.user_dict_toast_added)
    val addFailedToast = stringResource(R.string.user_dict_toast_add_failed)
    val deletedToast = stringResource(R.string.user_dict_toast_deleted)
    val writeFailedToast = stringResource(R.string.user_dict_toast_write_failed)
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    var query by remember { mutableStateOf("") }
    var pageIndex by remember { mutableStateOf(0) }
    var revision by remember { mutableStateOf(0) }
    val totalCount = remember(revision) { UserDictEdit.count(userDb, "") }
    val matchCount = remember(query, revision) {
        if (query.isBlank()) totalCount else UserDictEdit.count(userDb, query)
    }
    val maximumPage = ((matchCount - 1L).coerceAtLeast(0L) / USER_DICT_PAGE_SIZE).toInt()
    val currentPage = pageIndex.coerceAtMost(maximumPage)
    val entries = remember(query, currentPage, revision) {
        UserDictEdit.page(userDb, query, currentPage * USER_DICT_PAGE_SIZE, USER_DICT_PAGE_SIZE)
    }
    var newWord by remember { mutableStateOf("") }
    var newReading by remember { mutableStateOf("") }
    fun reload() { revision++ }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    UserDictEdit.export(userDb, out)
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
        val saved = runCatching {
            UserDictEdit.add(userDb, word, newReading, System.currentTimeMillis())
        }.getOrDefault(false)
        if (!saved) {
            Toast.makeText(context, writeFailedToast, Toast.LENGTH_SHORT).show()
            return
        }
        newWord = ""; newReading = ""
        reload()
        Toast.makeText(context, addedToast, Toast.LENGTH_SHORT).show()
    }

    fun deleteWord(reading: String, word: String) {
        val removed = runCatching { UserDictEdit.remove(userDb, reading, word) }.getOrDefault(false)
        if (!removed) {
            Toast.makeText(context, writeFailedToast, Toast.LENGTH_SHORT).show()
            return
        }
        reload()
        Toast.makeText(context, deletedToast, Toast.LENGTH_SHORT).show()
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
            onValueChange = { query = it; pageIndex = 0 },
            label = { Text(stringResource(R.string.user_dict_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("user_dict_search"),
        )
        Text(
            stringResource(R.string.user_dict_count_format, totalCount),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("user_dict_count"),
        )
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
                                stringResource(R.string.user_dict_default_path_format, userDataPath),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = {
                                    exportLauncher.launch("aegis-userdb.txt")
                                },
                                modifier = Modifier.fillMaxWidth(),
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
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(
                            if (query.isBlank()) R.string.user_dict_manual_empty else R.string.user_dict_search_no_match,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(entries, key = { "${it.reading}\t${it.word}" }) { entry ->
                    UserDictEntryRow(entry, onDelete = { deleteWord(entry.reading, entry.word) })
                }
                if (maximumPage > 0) {
                    item(key = "page-controls") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { pageIndex = currentPage - 1 },
                                enabled = currentPage > 0,
                            ) { Text(stringResource(R.string.user_dict_previous_page)) }
                            Text(
                                stringResource(R.string.user_dict_page_format, currentPage + 1, maximumPage + 1),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = { pageIndex = currentPage + 1 },
                                enabled = currentPage < maximumPage,
                            ) { Text(stringResource(R.string.user_dict_next_page)) }
                        }
                    }
                }
            }
        }
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

private const val USER_DICT_PAGE_SIZE = 100

internal fun Modifier.userDictPageInsets(
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
): Modifier = this
    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    .windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))

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
