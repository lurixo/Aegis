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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.backup.BackupError
import com.aegis.ime.backup.BackupException
import com.aegis.ime.backup.BackupManager
import java.util.concurrent.Executors

class BackupActivity : ComponentActivity() {

    private var uiState by mutableStateOf<BackupUiState>(BackupUiState.Menu)
    private lateinit var defaultPasswordStore: BackupDefaultPasswordStore
    private lateinit var defaultPasswordAuthenticator: BackupDefaultPasswordAuthenticator
    private var defaultPasswordSaved by mutableStateOf(false)
    private var defaultPasswordAuthAvailable by mutableStateOf(false)
    private var defaultPasswordMessageRes by mutableStateOf<Int?>(null)
    private var defaultPasswordDialogErrorRes by mutableStateOf<Int?>(null)
    private var defaultPasswordAutofill by mutableStateOf<String?>(null)
    private var pendingDefaultPasswordToSave: String? = null

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
        defaultPasswordStore = backupDefaultPasswordStore(this)
        defaultPasswordAuthenticator = PlatformBackupDefaultPasswordAuthenticator(this)
        refreshDefaultPasswordState()
        bootstrapSettingsEdgeToEdge()
        setContent {
            SettingsActivityChrome {
                BackupScreen(
                    state = uiState,
                    defaultPasswordSaved = defaultPasswordSaved,
                    defaultPasswordAuthAvailable = defaultPasswordAuthAvailable,
                    defaultPasswordMessageRes = defaultPasswordMessageRes,
                    defaultPasswordDialogErrorRes = defaultPasswordDialogErrorRes,
                    defaultPasswordAutofill = defaultPasswordAutofill,
                    onBack = { finish() },
                    onStartExport = { uiState = BackupUiState.ExportPassword },
                    onStartImport = { openDocument.launch(arrayOf("*/*")) },
                    onSetDefaultPassword = {
                        defaultPasswordDialogErrorRes = null
                        uiState = BackupUiState.SetDefaultPassword
                    },
                    onSaveDefaultPassword = { password -> saveDefaultPassword(password) },
                    onRemoveDefaultPassword = { uiState = BackupUiState.RemoveDefaultPasswordConfirmation },
                    onConfirmRemoveDefaultPassword = { removeDefaultPassword() },
                    onUseDefaultPassword = { useDefaultPassword() },
                    onDefaultPasswordAutofillConsumed = { defaultPasswordAutofill = null },
                    onClearDefaultPasswordDialogError = { defaultPasswordDialogErrorRes = null },
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
        if (::defaultPasswordAuthenticator.isInitialized) refreshDefaultPasswordState()
    }

    override fun onDestroy() {
        pendingExportPassword?.fill('\u0000')
        pendingExportPassword = null
        pendingDefaultPasswordToSave = null
        if (::defaultPasswordAuthenticator.isInitialized) defaultPasswordAuthenticator.cancel()
        worker.shutdown()
        super.onDestroy()
    }

    private fun cancelDialogs() {
        pendingExportPassword?.fill('\u0000')
        pendingExportPassword = null
        pendingDefaultPasswordToSave = null
        pendingImportUri = null
        defaultPasswordAutofill = null
        defaultPasswordDialogErrorRes = null
        uiState = BackupUiState.Menu
    }

    private fun refreshDefaultPasswordState() {
        defaultPasswordSaved = runCatching { defaultPasswordStore.hasPassword() }.getOrDefault(false)
        defaultPasswordAuthAvailable = defaultPasswordAuthenticator.canAuthenticate()
    }

    private fun saveDefaultPassword(password: String) {
        if (runCatching { defaultPasswordStore.prepareForAuth() }.isFailure) {
            refreshDefaultPasswordState()
            defaultPasswordDialogErrorRes = R.string.backup_default_password_auth_unavailable
            return
        }
        pendingDefaultPasswordToSave = password
        defaultPasswordDialogErrorRes = null
        defaultPasswordAuthenticator.authenticate(
            title = getString(R.string.backup_default_password_auth_title),
            subtitle = getString(R.string.backup_default_password_auth_save_subtitle),
            onSuccess = {
                val pending = pendingDefaultPasswordToSave
                pendingDefaultPasswordToSave = null
                if (pending != null) {
                    val saved = runCatching { defaultPasswordStore.save(pending) }.isSuccess
                    refreshDefaultPasswordState()
                    if (saved) {
                        uiState = BackupUiState.Menu
                        defaultPasswordMessageRes = R.string.backup_default_password_saved
                    } else {
                        defaultPasswordDialogErrorRes = R.string.backup_default_password_save_failed
                    }
                }
            },
            onUnavailable = {
                pendingDefaultPasswordToSave = null
                refreshDefaultPasswordState()
                defaultPasswordDialogErrorRes = R.string.backup_default_password_auth_unavailable
            },
            onCanceledOrFailed = {
                pendingDefaultPasswordToSave = null
                defaultPasswordDialogErrorRes = R.string.backup_default_password_auth_failed
            },
        )
    }

    private fun useDefaultPassword() {
        if (runCatching { defaultPasswordStore.prepareForAuth() }.isFailure) {
            refreshDefaultPasswordState()
            defaultPasswordDialogErrorRes = R.string.backup_default_password_use_failed
            return
        }
        defaultPasswordDialogErrorRes = null
        defaultPasswordAuthenticator.authenticate(
            title = getString(R.string.backup_default_password_auth_title),
            subtitle = getString(R.string.backup_default_password_auth_use_subtitle),
            onSuccess = {
                val password = runCatching { defaultPasswordStore.read() }.getOrNull()
                refreshDefaultPasswordState()
                if (password.isNullOrEmpty()) {
                    defaultPasswordDialogErrorRes = R.string.backup_default_password_use_failed
                } else {
                    defaultPasswordAutofill = password
                }
            },
            onUnavailable = {
                refreshDefaultPasswordState()
                defaultPasswordDialogErrorRes = R.string.backup_default_password_auth_unavailable
            },
            onCanceledOrFailed = {
                defaultPasswordDialogErrorRes = R.string.backup_default_password_auth_failed
            },
        )
    }

    private fun removeDefaultPassword() {
        runCatching { defaultPasswordStore.clear() }
        refreshDefaultPasswordState()
        uiState = BackupUiState.Menu
        defaultPasswordMessageRes = R.string.backup_default_password_removed
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

    internal fun writeExport(uri: Uri, password: CharArray): Boolean {
        try {
            val out = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("backup target open failed")
            out.use { BackupManager.export(filesDir, aegisPrefs(), password, it) }
            val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("backup reopen failed")
            input.use { BackupManager.verify(filesDir, password, it) }
            return true
        } catch (_: Exception) {
            runCatching { contentResolver.delete(uri, null, null) }
            return false
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
    data object SetDefaultPassword : BackupUiState
    data object RemoveDefaultPasswordConfirmation : BackupUiState
    data object ExportPassword : BackupUiState
    data object ImportPassword : BackupUiState
    data object Working : BackupUiState
    data class Result(val messageRes: Int) : BackupUiState
}

internal const val BACKUP_MIN_PASSWORD_LENGTH = 6

@Composable
internal fun BackupScreen(
    state: BackupUiState,
    defaultPasswordSaved: Boolean,
    defaultPasswordAuthAvailable: Boolean,
    defaultPasswordMessageRes: Int?,
    defaultPasswordDialogErrorRes: Int?,
    defaultPasswordAutofill: String?,
    onBack: () -> Unit,
    onStartExport: () -> Unit,
    onStartImport: () -> Unit,
    onSetDefaultPassword: () -> Unit,
    onSaveDefaultPassword: (String) -> Unit,
    onRemoveDefaultPassword: () -> Unit,
    onConfirmRemoveDefaultPassword: () -> Unit,
    onUseDefaultPassword: () -> Unit,
    onDefaultPasswordAutofillConsumed: () -> Unit,
    onClearDefaultPasswordDialogError: () -> Unit,
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

        DefaultPasswordCard(
            saved = defaultPasswordSaved,
            authAvailable = defaultPasswordAuthAvailable,
            actionsEnabled = state == BackupUiState.Menu,
            messageRes = defaultPasswordMessageRes,
            onSet = onSetDefaultPassword,
            onRemove = onRemoveDefaultPassword,
        )

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
        BackupUiState.SetDefaultPassword -> DefaultPasswordDialog(
            externalErrorRes = defaultPasswordDialogErrorRes,
            onClearExternalError = onClearDefaultPasswordDialogError,
            onDismiss = onDismissDialog,
            onSave = onSaveDefaultPassword,
        )
        BackupUiState.RemoveDefaultPasswordConfirmation -> RemoveDefaultPasswordDialog(
            onDismiss = onDismissDialog,
            onConfirm = onConfirmRemoveDefaultPassword,
        )
        BackupUiState.ExportPassword -> ExportPasswordDialog(
            defaultPasswordSaved = defaultPasswordSaved,
            defaultPasswordAuthAvailable = defaultPasswordAuthAvailable,
            defaultPasswordAutofill = defaultPasswordAutofill,
            defaultPasswordErrorRes = defaultPasswordDialogErrorRes,
            onUseDefaultPassword = onUseDefaultPassword,
            onDefaultPasswordAutofillConsumed = onDefaultPasswordAutofillConsumed,
            onClearDefaultPasswordError = onClearDefaultPasswordDialogError,
            onDismiss = onDismissDialog,
            onConfirm = onExportConfirm,
        )
        BackupUiState.ImportPassword -> ImportPasswordDialog(
            defaultPasswordSaved = defaultPasswordSaved,
            defaultPasswordAuthAvailable = defaultPasswordAuthAvailable,
            defaultPasswordAutofill = defaultPasswordAutofill,
            defaultPasswordErrorRes = defaultPasswordDialogErrorRes,
            onUseDefaultPassword = onUseDefaultPassword,
            onDefaultPasswordAutofillConsumed = onDefaultPasswordAutofillConsumed,
            onClearDefaultPasswordError = onClearDefaultPasswordDialogError,
            onDismiss = onDismissDialog,
            onConfirm = onImportConfirm,
        )
        is BackupUiState.Result -> ResultDialog(state.messageRes, onDone)
        else -> Unit
    }
}

@Composable
private fun DefaultPasswordCard(
    saved: Boolean,
    authAvailable: Boolean,
    actionsEnabled: Boolean,
    messageRes: Int?,
    onSet: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.backup_default_password_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.backup_default_password_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    if (saved) R.string.backup_default_password_status_saved
                    else R.string.backup_default_password_status_not_set,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!authAvailable) {
                Text(
                    stringResource(R.string.backup_default_password_auth_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            messageRes?.let {
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSet,
                    enabled = actionsEnabled && authAvailable,
                ) {
                    Text(
                        stringResource(
                            if (saved) R.string.backup_default_password_update_button
                            else R.string.backup_default_password_set_button,
                        ),
                    )
                }
                if (saved) {
                    OutlinedButton(
                        onClick = onRemove,
                        enabled = actionsEnabled,
                    ) { Text(stringResource(R.string.backup_default_password_remove_button)) }
                }
            }
        }
    }
}

@Composable
private fun RemoveDefaultPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AegisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_default_password_remove_title)) },
        text = { Text(stringResource(R.string.backup_default_password_remove_desc)) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("backup_default_password_remove_confirm"),
                onClick = onConfirm,
            ) { Text(stringResource(R.string.backup_default_password_remove_confirm_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun DefaultPasswordDialog(
    externalErrorRes: Int?,
    onClearExternalError: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    AegisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_default_password_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_default_password_dialog_desc), style = MaterialTheme.typography.bodySmall)
                PasswordTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                        onClearExternalError()
                    },
                    labelRes = R.string.backup_password_label,
                )
                PasswordTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        error = null
                        onClearExternalError()
                    },
                    labelRes = R.string.backup_password_confirm_label,
                )
                (error ?: externalErrorRes)?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("backup_default_password_save"),
                onClick = {
                    val problem = passwordProblem(password, confirm)
                    if (problem != null) error = problem else onSave(password)
                },
            ) { Text(stringResource(R.string.backup_default_password_save_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun ExportPasswordDialog(
    defaultPasswordSaved: Boolean,
    defaultPasswordAuthAvailable: Boolean,
    defaultPasswordAutofill: String?,
    defaultPasswordErrorRes: Int?,
    onUseDefaultPassword: () -> Unit,
    onDefaultPasswordAutofillConsumed: () -> Unit,
    onClearDefaultPasswordError: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(defaultPasswordAutofill) {
        val fill = defaultPasswordAutofill ?: return@LaunchedEffect
        password = fill
        confirm = fill
        error = null
        onClearDefaultPasswordError()
        onDefaultPasswordAutofillConsumed()
    }

    AegisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_button)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_set_password_hint), style = MaterialTheme.typography.bodySmall)
                if (defaultPasswordSaved) {
                    TextButton(
                        onClick = onUseDefaultPassword,
                        enabled = defaultPasswordAuthAvailable,
                        modifier = Modifier.testTag("backup_use_default_password"),
                    ) { Text(stringResource(R.string.backup_default_password_use_button)) }
                }
                PasswordTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                        onClearDefaultPasswordError()
                    },
                    labelRes = R.string.backup_password_label,
                )
                PasswordTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        error = null
                        onClearDefaultPasswordError()
                    },
                    labelRes = R.string.backup_password_confirm_label,
                )
                (error ?: defaultPasswordErrorRes)?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("backup_export_confirm"),
                onClick = {
                    val problem = passwordProblem(password, confirm)
                    if (problem != null) error = problem else onConfirm(password)
                },
            ) { Text(stringResource(R.string.backup_export_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun ImportPasswordDialog(
    defaultPasswordSaved: Boolean,
    defaultPasswordAuthAvailable: Boolean,
    defaultPasswordAutofill: String?,
    defaultPasswordErrorRes: Int?,
    onUseDefaultPassword: () -> Unit,
    onDefaultPasswordAutofillConsumed: () -> Unit,
    onClearDefaultPasswordError: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, BackupManager.Mode) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(BackupManager.Mode.OVERWRITE) }
    var error by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(defaultPasswordAutofill) {
        val fill = defaultPasswordAutofill ?: return@LaunchedEffect
        password = fill
        error = null
        onClearDefaultPasswordError()
        onDefaultPasswordAutofillConsumed()
    }

    AegisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_button)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (defaultPasswordSaved) {
                    TextButton(
                        onClick = onUseDefaultPassword,
                        enabled = defaultPasswordAuthAvailable,
                        modifier = Modifier.testTag("backup_use_default_password"),
                    ) { Text(stringResource(R.string.backup_default_password_use_button)) }
                }
                PasswordTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                        onClearDefaultPasswordError()
                    },
                    labelRes = R.string.backup_password_label,
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
                (error ?: defaultPasswordErrorRes)?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("backup_import_confirm"),
                onClick = {
                    if (password.isEmpty()) error = R.string.backup_password_empty else onConfirm(password, mode)
                },
            ) { Text(stringResource(R.string.backup_import_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(
                    stringResource(
                        if (visible) R.string.backup_password_hide
                        else R.string.backup_password_show,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
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
    AegisAlertDialog(
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
