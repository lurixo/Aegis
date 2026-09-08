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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.aegis.ime.R
import com.aegis.ime.backup.BackupManager
import com.aegis.ime.ui.theme.AppShapes
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.user.UserLexiconTransfer
import com.aegis.ime.user.UserLexiconTransfer.Scope
import com.aegis.ime.user.UserStoreEdits
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun UserLexiconTransferUi(content: @Composable (() -> Unit, Int) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("aegis", Context.MODE_PRIVATE) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val active = remember { AtomicBoolean(true) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var scopeMask by rememberSaveable { mutableIntStateOf(7) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }
    var importMask by rememberSaveable { mutableIntStateOf(0) }
    var importSignal by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val exported = stringResource(R.string.user_dict_toast_export_done)
    val exportFailed = stringResource(R.string.user_dict_toast_export_failed)
    val exportBlocked = stringResource(R.string.user_dict_toast_export_blocked)
    val importFailed = stringResource(R.string.user_dict_toast_import_failed)
    val merged = stringResource(R.string.user_dict_toast_import_merged)
    val overwritten = stringResource(R.string.user_dict_toast_import_overwritten)

    DisposableEffect(Unit) {
        active.set(true)
        onDispose { active.set(false) }
    }

    fun deleteStage(name: String?) {
        if (name != null) UserStoreEdits.submit { File(context.cacheDir, name).delete() }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val name = pendingExport
        pendingExport = null
        if (name != null) {
            busy = true
            UserStoreEdits.submit {
                val stage = File(context.cacheDir, name)
                val result = runCatching {
                    if (uri != null) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                            stage.inputStream().use { it.copyTo(output) }
                        }
                    }
                }
                stage.delete()
                handler.post {
                    if (active.get()) {
                        busy = false
                        if (uri != null) AegisToast.show(if (result.isSuccess) exported else exportFailed)
                    }
                }
            }
        } else if (uri != null) {
            AegisToast.show(exportFailed)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            UserStoreEdits.submit {
                var stage: File? = null
                val result = runCatching {
                    val file = File.createTempFile("lexicon-import-", ".tmp", context.cacheDir).also { stage = it }
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= UserLexiconTransfer.MAX_BYTES)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    file.inputStream().use(UserLexiconTransfer::inspect)
                }
                handler.post {
                    if (active.get()) {
                        busy = false
                        result.fold(
                            onSuccess = { scopes ->
                                deleteStage(pendingImport)
                                pendingImport = requireNotNull(stage).name
                                importMask = scopes.fold(0) { mask, scope -> mask or (1 shl scope.ordinal) }
                            },
                            onFailure = {
                                deleteStage(stage?.name)
                                AegisToast.show(importFailed)
                            },
                        )
                    } else deleteStage(stage?.name)
                }
            }
        }
    }

    fun startExport() {
        if (busy || scopeMask == 0) return
        busy = true
        exportOpen = false
        val scopes = Scope.entries.filter { scopeMask and (1 shl it.ordinal) != 0 }.toSet()
        UserStoreEdits.submit {
            var stage: File? = null
            val result = runCatching {
                val bytes = UserLexiconTransfer.export(context.filesDir, prefs, scopes)
                File.createTempFile("lexicon-export-", ".tmp", context.cacheDir).also {
                    stage = it
                    it.writeBytes(bytes)
                }
            }
            handler.post {
                if (active.get()) {
                    busy = false
                    result.fold(
                        onSuccess = { file ->
                            deleteStage(pendingExport)
                            pendingExport = file.name
                            runCatching { exportLauncher.launch("aegis-lexicons.json") }.onFailure {
                                pendingExport = null
                                deleteStage(file.name)
                                AegisToast.show(exportFailed)
                            }
                        },
                        onFailure = {
                            deleteStage(stage?.name)
                            AegisToast.show(if (it is UserLexiconTransfer.ExportBlockedException) exportBlocked else exportFailed)
                        },
                    )
                } else deleteStage(stage?.name)
            }
        }
    }

    fun dismissImport() {
        deleteStage(pendingImport)
        pendingImport = null
        importMask = 0
    }

    fun applyImport(mode: BackupManager.Mode) {
        val name = pendingImport ?: return
        if (busy) return
        pendingImport = null
        importMask = 0
        busy = true
        UserStoreEdits.submit {
            val stage = File(context.cacheDir, name)
            val result = runCatching {
                stage.inputStream().use { UserLexiconTransfer.importData(context.filesDir, prefs, it, mode) }
            }
            stage.delete()
            handler.post {
                if (active.get()) {
                    busy = false
                    if (result.isSuccess) {
                        importSignal++
                        AegisToast.show(if (mode == BackupManager.Mode.MERGE) merged else overwritten)
                    } else AegisToast.show(importFailed)
                }
            }
        }
    }

    content({
        if (!busy) {
            focus.clearFocus()
            keyboard?.hide()
            toolsOpen = true
        }
    }, importSignal)

    if (toolsOpen) {
        UserLexiconToolsSheet(
            onExport = { toolsOpen = false; scopeMask = 7; exportOpen = true },
            onImport = {
                toolsOpen = false
                runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                    .onFailure { AegisToast.show(importFailed) }
            },
            onDismiss = { toolsOpen = false },
        )
    }
    if (exportOpen) {
        AegisAlertDialog(
            onDismissRequest = { exportOpen = false },
            title = { Text(stringResource(R.string.user_dict_export_button)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.user_lexicon_export_help))
                    Scope.entries.forEach { scope ->
                        val bit = 1 shl scope.ordinal
                        val checked = scopeMask and bit != 0
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = AppSpacing.touchTarget).toggleable(checked, role = Role.Checkbox) { scopeMask = scopeMask xor bit }
                                .testTag("lexicon_export_scope_${scope.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.textGap),
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(scopeLabel(scope))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = ::startExport, enabled = scopeMask != 0 && !busy, modifier = Modifier.testTag("lexicon_export_confirm")) {
                    Text(stringResource(R.string.user_lexicon_export_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { exportOpen = false }, modifier = Modifier.testTag("lexicon_export_cancel")) {
                    Text(stringResource(R.string.user_dict_delete_cancel))
                }
            },
        )
    }
    if (pendingImport != null) {
        val labels = Scope.entries.filter { importMask and (1 shl it.ordinal) != 0 }.map { scopeLabel(it) }.joinToString(", ")
        AegisAlertDialog(
            onDismissRequest = ::dismissImport,
            title = { Text(stringResource(R.string.user_dict_import_dialog_title)) },
            text = { Text(stringResource(R.string.user_lexicon_import_help, labels), Modifier.testTag("lexicon_import_scopes")) },
            confirmButton = {
                TextButton(onClick = { applyImport(BackupManager.Mode.MERGE) }, modifier = Modifier.testTag("user_dict_import_merge")) {
                    Text(stringResource(R.string.user_dict_import_merge))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = ::dismissImport, modifier = Modifier.testTag("user_dict_import_cancel")) {
                        Text(stringResource(R.string.user_dict_delete_cancel))
                    }
                    TextButton(onClick = { applyImport(BackupManager.Mode.OVERWRITE) }, modifier = Modifier.testTag("user_dict_import_overwrite")) {
                        Text(stringResource(R.string.user_dict_import_overwrite))
                    }
                }
            },
        )
    }
}

@Composable
private fun scopeLabel(scope: Scope): String = stringResource(
    when (scope) {
        Scope.CHINESE -> R.string.user_lexicon_chinese
        Scope.ENGLISH -> R.string.user_lexicon_english
        Scope.EMAIL -> R.string.user_lexicon_email
    },
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun UserLexiconToolsSheet(onExport: () -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = AppShapes.sheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag("user_dict_more_sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenHorizontal).padding(bottom = AppSpacing.pageBottom),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
        ) {
            Text(stringResource(R.string.user_dict_more_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.user_lexicon_transfer_help), style = MaterialTheme.typography.bodyMedium)
            AppPrimaryButton(
                text = stringResource(R.string.user_dict_export_button),
                onClick = onExport,
                modifier = Modifier.fillMaxWidth().testTag("user_dict_export"),
            )
            AppPrimaryButton(
                text = stringResource(R.string.user_dict_import_button),
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().testTag("user_dict_import"),
            )
        }
    }
}
