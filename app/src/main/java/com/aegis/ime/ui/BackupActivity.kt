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
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppShapes
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.backup.BackupError
import com.aegis.ime.backup.BackupItem
import com.aegis.ime.backup.BackupException
import com.aegis.ime.backup.BackupManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class BackupActivity : ComponentActivity() {

    private var uiState by mutableStateOf<BackupUiState>(BackupUiState.Menu)

    private var pendingExportPassword: CharArray? = null

    private var pendingImportUri: Uri? = null

    private val onJobResult: (BackupUiState.Result) -> Unit = { uiState = it }

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

    override fun onStart() {
        super.onStart()
        BackupJob.reportTo(onJobResult)
    }

    override fun onResume() {
        super.onResume()
        bootstrapSettingsEdgeToEdge()
    }

    override fun onStop() {
        BackupJob.stopReportingTo(onJobResult)
        super.onStop()
    }

    override fun onDestroy() {
        pendingExportPassword?.fill('\u0000')
        pendingExportPassword = null
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
        if (uri == null) {
            password?.fill('\u0000')
            uiState = BackupUiState.Menu
            return
        }
        if (password == null) {
            uiState = BackupUiState.Result(R.string.backup_export_interrupted)
            return
        }
        uiState = BackupUiState.Working
        BackupJob.start {
            val report = runCatching { writeExport(uri, password) }.getOrNull()
            password.fill('\u0000')
            exportResult(report)
        }
    }

    private fun writeExport(uri: Uri, password: CharArray): BackupManager.ExportReport? {
        val staged = File.createTempFile("aegis-export", ".tmp", cacheDir.apply { mkdirs() })
        try {
            val report = FileOutputStream(staged).use { fileOut ->
                BackupManager.export(filesDir, aegisPrefs(), password, fileOut)
            }
            val out = contentResolver.openOutputStream(uri, "wt") ?: return null
            out.use { staged.inputStream().use { archive -> archive.copyTo(it) } }
            return report
        } finally {
            staged.delete()
        }
    }

    private fun beginImport(password: String, mode: BackupManager.Mode) {
        val uri = pendingImportUri
        if (uri == null) {
            uiState = BackupUiState.Result(R.string.backup_import_interrupted)
            return
        }
        val chars = password.toCharArray()
        pendingImportUri = null
        uiState = BackupUiState.Working
        BackupJob.start {
            val result = runImport(uri, chars, mode)
            chars.fill('\u0000')
            result
        }
    }

    private fun runImport(uri: Uri, password: CharArray, mode: BackupManager.Mode): BackupUiState.Result {
        return try {
            val input = contentResolver.openInputStream(uri)
                ?: return BackupUiState.Result(R.string.backup_error_io)
            input.use { BackupManager.restore(filesDir, aegisPrefs(), password, it, mode) }
            BackupUiState.Result(
                if (mode == BackupManager.Mode.MERGE) R.string.backup_import_ok_merge
                else R.string.backup_import_ok_overwrite,
            )
        } catch (e: BackupException) {
            importResult(e)
        } catch (e: Exception) {
            BackupUiState.Result(R.string.backup_error_io)
        }
    }

    private fun aegisPrefs() = getSharedPreferences("aegis", Context.MODE_PRIVATE)

    private companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val DEFAULT_FILE_NAME = "aegis-backup.aegisbak"
    }
}

internal object BackupJob {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-backup").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var inProgress: Boolean = false
        private set

    private var finished: BackupUiState.Result? = null
    private var listener: ((BackupUiState.Result) -> Unit)? = null

    fun start(work: () -> BackupUiState.Result) {
        inProgress = true
        worker.execute {
            val result = runCatching(work).getOrElse { BackupUiState.Result(R.string.backup_error_io) }
            main.post { deliver(result) }
        }
    }

    fun reportTo(page: (BackupUiState.Result) -> Unit) {
        listener = page
        finished?.let { finished = null; page(it) }
    }

    fun stopReportingTo(page: (BackupUiState.Result) -> Unit) {
        if (listener === page) listener = null
    }

    private fun deliver(result: BackupUiState.Result) {
        inProgress = false
        val page = listener
        if (page == null) finished = result else page(result)
    }
}

internal sealed interface BackupUiState {
    data object Menu : BackupUiState
    data object ExportPassword : BackupUiState
    data object ImportPassword : BackupUiState
    data object Working : BackupUiState
    data class Result(val messageRes: Int, val omittedRes: List<Int> = emptyList()) : BackupUiState
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
        AppSection {
            Column(
                modifier = Modifier.padding(AppSpacing.sectionPadding),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
            ) {
                Text(
                    stringResource(R.string.backup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.backup_password_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AppPrimaryButton(
            text = stringResource(R.string.backup_export_button),
            onClick = onStartExport,
            enabled = state == BackupUiState.Menu,
            modifier = Modifier.fillMaxWidth(),
        )

        AppPrimaryButton(
            text = stringResource(R.string.backup_import_button),
            onClick = onStartImport,
            enabled = state == BackupUiState.Menu,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state == BackupUiState.Working) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    when (state) {
        BackupUiState.ExportPassword -> ExportPasswordDialog(
            onDismiss = onDismissDialog,
            onConfirm = onExportConfirm,
        )
        BackupUiState.ImportPassword -> ImportPasswordDialog(
            onDismiss = onDismissDialog,
            onConfirm = onImportConfirm,
        )
        is BackupUiState.Result -> ResultDialog(state.messageRes, state.omittedRes, onDone)
        else -> Unit
    }
}

@Composable
private fun ExportPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    AegisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_button)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_set_password_hint), style = MaterialTheme.typography.bodySmall)
                PasswordTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    labelRes = R.string.backup_password_label,
                )
                PasswordTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        error = null
                    },
                    labelRes = R.string.backup_password_confirm_label,
                )
                error?.let {
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
    onDismiss: () -> Unit,
    onConfirm: (String, BackupManager.Mode) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(BackupManager.Mode.OVERWRITE) }
    var error by remember { mutableStateOf<Int?>(null) }

    AegisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_button)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
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
                error?.let {
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
internal fun PasswordTextField(
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
        shape = AppShapes.section,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }, shape = MaterialTheme.shapes.extraSmall) {
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
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
private fun ResultDialog(messageRes: Int, omittedRes: List<Int>, onDone: () -> Unit) {
    AegisAlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.settings_backup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(messageRes))
                omittedRes.forEach {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.backup_done)) } },
    )
}

internal fun exportResult(report: BackupManager.ExportReport?): BackupUiState.Result = when {
    report == null -> BackupUiState.Result(R.string.backup_export_failed)
    report.omitted.isEmpty() -> BackupUiState.Result(R.string.backup_export_ok)
    else -> BackupUiState.Result(R.string.backup_export_ok_partial, report.omitted.map(::backupItemLabel))
}

internal fun importResult(failure: BackupException): BackupUiState.Result = when (failure.error) {
    BackupError.NOT_A_BACKUP -> BackupUiState.Result(R.string.backup_error_not_a_backup)
    BackupError.UNSUPPORTED_VERSION -> BackupUiState.Result(R.string.backup_error_unsupported)
    BackupError.WRONG_PASSWORD_OR_CORRUPT -> BackupUiState.Result(R.string.backup_error_wrong_password)
    BackupError.IO_ERROR -> BackupUiState.Result(R.string.backup_error_io)
    BackupError.DAMAGED_CONTENT -> BackupUiState.Result(
        R.string.backup_error_damaged_content,
        failure.items.map(::backupItemLabel),
    )
    BackupError.ALREADY_RESTORING -> BackupUiState.Result(R.string.backup_error_already_restoring)
    BackupError.ROLLBACK_FAILED -> BackupUiState.Result(R.string.backup_error_rollback_failed)
}

internal fun backupItemLabel(item: BackupItem): Int = when (item) {
    BackupItem.DICTIONARY -> R.string.backup_item_dictionary
    BackupItem.LEARNING -> R.string.backup_item_learning
    BackupItem.PHRASES -> R.string.backup_item_phrases
    BackupItem.CLIPBOARD -> R.string.backup_item_clipboard
    BackupItem.SYMBOL_USAGE -> R.string.backup_item_symbols
    BackupItem.EMOJI_USAGE -> R.string.backup_item_emoji
}

internal fun passwordProblem(password: String, confirm: String): Int? = when {
    password.isEmpty() -> R.string.backup_password_empty
    password.length < BACKUP_MIN_PASSWORD_LENGTH -> R.string.backup_password_too_short
    password != confirm -> R.string.backup_password_mismatch
    else -> null
}
