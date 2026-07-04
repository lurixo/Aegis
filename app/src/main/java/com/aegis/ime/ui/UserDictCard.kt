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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictImport
import java.io.File

/**
  * Chinese IME behavior note.
  * Chinese IME behavior note.
 * the default dictionary location (an app-private path invisible to file managers) so it's clear why
  * Chinese IME behavior note.
 */
@Composable
internal fun UserDictCard() {
    val context = LocalContext.current
    val userDb = File(context.filesDir, "userdb.txt")
    val importMergedToast = stringResource(R.string.user_dict_toast_import_merged)
    val importOverwrittenToast = stringResource(R.string.user_dict_toast_import_overwritten)
    val importFailedToast = stringResource(R.string.user_dict_toast_import_failed)
    val addedToast = stringResource(R.string.user_dict_toast_added)
    val addFailedToast = stringResource(R.string.user_dict_toast_add_failed)
    val deletedToast = stringResource(R.string.user_dict_toast_deleted)
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    // The custom (recall) words, reloaded from the same userdb.txt after every edit/import so the list, the
    // automatic learning and the IME all read one store.
    var entries by remember { mutableStateOf(UserDictEdit.list(userDb)) }
    var newWord by remember { mutableStateOf("") }
    var newReading by remember { mutableStateOf("") }
    fun reload() { entries = UserDictEdit.list(userDb) }

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
            tmp.delete() // never reuse a stale temp from a prior attempt
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            // ④ The pure apply step validates the import and never wipes the live dict on failure
            // Chinese IME behavior note.
            UserDictImport.apply(tmp, userDb, merge, System.currentTimeMillis())
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

    // A recall word needs a word plus at least one pinyin letter — the reading is what the decoder matches.
    fun readingHasLetter(s: String): Boolean = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    fun addWord() {
        val word = newWord.trim()
        if (word.isEmpty() || !readingHasLetter(newReading)) {
            Toast.makeText(context, addFailedToast, Toast.LENGTH_SHORT).show()
            return
        }
        UserDictEdit.add(userDb, word, newReading, System.currentTimeMillis())
        newWord = ""; newReading = ""
        reload()
        Toast.makeText(context, addedToast, Toast.LENGTH_SHORT).show()
    }

    fun deleteWord(reading: String, word: String) {
        UserDictEdit.remove(userDb, reading, word)
        reload()
        Toast.makeText(context, deletedToast, Toast.LENGTH_SHORT).show()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.user_dict_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.user_dict_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.user_dict_default_path_format, userDb.absolutePath),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { exportLauncher.launch("aegis-userdb.txt") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.user_dict_export_button)) }
            Button(
                onClick = { importLauncher.launch(arrayOf("text/plain")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.user_dict_import_button)) }

            HorizontalDivider()

            // ④ Manual add / delete of custom recall words, written to the same userdb.txt.
            Text(stringResource(R.string.user_dict_manual_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.user_dict_manual_description),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = newWord,
                onValueChange = { newWord = it },
                label = { Text(stringResource(R.string.user_dict_word_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newReading,
                onValueChange = { newReading = it },
                label = { Text(stringResource(R.string.user_dict_reading_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { addWord() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.user_dict_add_button))
            }

            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.user_dict_manual_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                for (entry in entries) {
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
                        TextButton(onClick = { deleteWord(entry.reading, entry.word) }) {
                            Text(stringResource(R.string.user_dict_delete_button))
                        }
                    }
                }
            }
        }
    }

    val uri = pendingImport
    if (uri != null) {
        AlertDialog(
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
