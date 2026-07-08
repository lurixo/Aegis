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
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.backup.BackupError
import com.aegis.ime.backup.BackupException
import com.aegis.ime.backup.BackupManager
import java.util.concurrent.Executors

class BackupActivity : ComponentActivity() {

    private var uiState by mutableStateOf<BackupUiState>(BackupUiState.Menu)

    private var pendingExportPassword: CharArray? = null

    private var pendingImportUri: Uri? = null

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-backup").apply { isDaemon = true }
    }

    private val createDocument = registerForActivityResult(CreateDocument(MIME_TYPE)) { uri ->
        onExportTarget(uri)
    }

    private val openDocument = registerForActivityResult(OpenDocument()) { uri ->
        if (uri == null) {
            uiState = BackupUiState.Menu
        } else {
            pendingImportUri = uri
            uiState = BackupUiState.ImportPassword
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bootstrapSettingsEdgeToEdge()
        setContent {
            SettingsActivityChrome {
                BackupScreen(
                    state = uiState,
                    onBack = { finish() },
                    onStartExport = { uiState = BackupUiState.ExportPassword },
                    onStartImport = { openDocument.launch(arrayOf("*/*")) },
                    onExportConfirm = { password -> beginExport(password) },
                    onImportConfirm = { password, mode -> beginImport(password, mode) },
                    onDismissDialog = { cancelDialogs() },
                    onDone = { uiState = BackupUiState.Menu },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bootstrapSettingsEdgeToEdge()
    }

    override fun onDestroy() {
        pendingExportPassword?.fill('\u0000')
        pendingExportPassword = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun cancelDialogs() {
        pendingExportPassword?.fill('\u0000')
        pendingExportPassword = null
        pendingImportUri = null
        uiState = BackupUiState.Menu
    }

    private fun beginExport(password: String) {
        pendingExportPassword = password.toCharArray()
        uiState = BackupUiState.Working
        createDocument.launch(DEFAULT_FILE_NAME)
    }

    private fun onExportTarget(uri: Uri?) {
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null || password == null) {
            password?.fill('\u0000')
            uiState = BackupUiState.Menu
            return
        }
        uiState = BackupUiState.Working
        worker.execute {
            val ok = runCatching { writeExport(uri, password) }.getOrDefault(false)
            password.fill('\u0000')
            runOnUiThread {
                uiState = BackupUiState.Result(
                    if (ok) R.string.backup_export_ok else R.string.backup_export_failed,
                )
            }
        }
    }

    private fun writeExport(uri: Uri, password: CharArray): Boolean {
        val out = contentResolver.openOutputStream(uri) ?: return false
        return try {
            BackupManager.export(filesDir, aegisPrefs(), password, out)
            true
        } finally {
            runCatching { out.close() }
        }
    }

    private fun beginImport(password: String, mode: BackupManager.Mode) {
        val uri = pendingImportUri
        if (uri == null) {
            uiState = BackupUiState.Menu
            return
        }
        val chars = password.toCharArray()
        uiState = BackupUiState.Working
        worker.execute {
            val messageRes = runImport(uri, chars, mode)
            chars.fill('\u0000')
            pendingImportUri = null
            runOnUiThread { uiState = BackupUiState.Result(messageRes) }
        }
    }

    private fun runImport(uri: Uri, password: CharArray, mode: BackupManager.Mode): Int {
        return try {
            val input = contentResolver.openInputStream(uri)
                ?: return R.string.backup_error_io
            input.use { BackupManager.restore(filesDir, aegisPrefs(), password, it, mode) }
            if (mode == BackupManager.Mode.MERGE) R.string.backup_import_ok_merge
            else R.string.backup_import_ok_overwrite
        } catch (e: BackupException) {
            messageFor(e.error)
        } catch (e: Exception) {
            R.string.backup_error_io
        }
    }

    private fun aegisPrefs() = getSharedPreferences("aegis", Context.MODE_PRIVATE)

    private fun messageFor(error: BackupError): Int = when (error) {
        BackupError.NOT_A_BACKUP -> R.string.backup_error_not_a_backup
        BackupError.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported
        BackupError.WRONG_PASSWORD_OR_CORRUPT -> R.string.backup_error_wrong_password
        BackupError.IO_ERROR -> R.string.backup_error_io
    }

    private companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val DEFAULT_FILE_NAME = "aegis-backup.aegisbak"
    }
}

internal sealed interface BackupUiState {
    data object Menu : BackupUiState
    data object ExportPassword : BackupUiState
    data object ImportPassword : BackupUiState
    data object Working : BackupUiState
    data class Result(val messageRes: Int) : BackupUiState
}

internal const val BACKUP_MIN_PASSWORD_LENGTH = 6

@Composable
internal fun BackupScreen(
    state: BackupUiState,
    onBack: () -> Unit,
    onStartExport: () -> Unit,
    onStartImport: () -> Unit,
    onExportConfirm: (String) -> Unit,
    onImportConfirm: (String, BackupManager.Mode) -> Unit,
    onDismissDialog: () -> Unit,
    onDone: () -> Unit,
) {
    SettingsPageColumn(stringResource(R.string.settings_backup_title), onBack) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.backup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.backup_password_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = onStartExport,
            enabled = state == BackupUiState.Menu,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.backup_export_button)) }

        OutlinedButton(
            onClick = onStartImport,
            enabled = state == BackupUiState.Menu,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.backup_import_button)) }

        if (state == BackupUiState.Working) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    when (state) {
        BackupUiState.ExportPassword -> ExportPasswordDialog(onDismissDialog, onExportConfirm)
        BackupUiState.ImportPassword -> ImportPasswordDialog(onDismissDialog, onImportConfirm)
        is BackupUiState.Result -> ResultDialog(state.messageRes, onDone)
        else -> Unit
    }
}

@Composable
private fun ExportPasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_button)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_set_password_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_confirm_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = passwordProblem(password, confirm)
                if (problem != null) error = problem else onConfirm(password)
            }) { Text(stringResource(R.string.backup_export_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun ImportPasswordDialog(onDismiss: () -> Unit, onConfirm: (String, BackupManager.Mode) -> Unit) {
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(BackupManager.Mode.OVERWRITE) }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_button)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.backup_mode_title), style = MaterialTheme.typography.titleSmall)
                ModeOption(
                    selected = mode == BackupManager.Mode.OVERWRITE,
                    titleRes = R.string.backup_mode_overwrite,
                    descRes = R.string.backup_mode_overwrite_desc,
                    onSelect = { mode = BackupManager.Mode.OVERWRITE },
                )
                ModeOption(
                    selected = mode == BackupManager.Mode.MERGE,
                    titleRes = R.string.backup_mode_merge,
                    descRes = R.string.backup_mode_merge_desc,
                    onSelect = { mode = BackupManager.Mode.MERGE },
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (password.isEmpty()) error = R.string.backup_password_empty else onConfirm(password, mode)
            }) { Text(stringResource(R.string.backup_import_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun ModeOption(selected: Boolean, titleRes: Int, descRes: Int, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultDialog(messageRes: Int, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.settings_backup_title)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.backup_done)) } },
    )
}

internal fun passwordProblem(password: String, confirm: String): Int? = when {
    password.isEmpty() -> R.string.backup_password_empty
    password.length < BACKUP_MIN_PASSWORD_LENGTH -> R.string.backup_password_too_short
    password != confirm -> R.string.backup_password_mismatch
    else -> null
}
