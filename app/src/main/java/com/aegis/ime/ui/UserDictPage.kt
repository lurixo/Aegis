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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppShapes
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.ui.theme.SettingsMotion
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictImport
import com.aegis.ime.user.UserDictSearch
import com.aegis.ime.user.UserLearnEdit
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import com.aegis.ime.user.UserStoreEdits
import java.io.File
import kotlin.math.roundToInt

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
    var sheet by remember { mutableStateOf<UserDictSheet?>(null) }

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
                AegisToast.show(if (landed) success else failure)
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
                    AegisToast.show(
                        when (outcome) {
                            UserDictEdit.ExportResult.WRITTEN -> exportDoneToast
                            UserDictEdit.ExportResult.NOTHING_TO_EXPORT -> exportEmptyToast
                            UserDictEdit.ExportResult.NOT_WRITTEN -> exportFailedToast
                        },
                    )
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
            AegisToast.show(addFailedToast)
            return
        }
        if (!UserModel.acceptsManualWord(word, typedReading)) {
            AegisToast.show(addRejectedToast)
            return
        }
        val reading = UserModel.normalizeReading(typedReading)
        val known = entries.any { it.reading == reading && it.word == word }
        edit(
            if (known) keptToast else addedToast,
            writeFailedToast,
            { landed ->
                if (landed) {
                    newWord = ""
                    newReading = ""
                    sheet = null
                }
            },
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
                    !ready -> AegisToast.show(exportBlockedToast)
                    !anythingToExport -> AegisToast.show(exportEmptyToast)
                    else -> runCatching { exportLauncher.launch("aegis-userdb.txt") }
                }
            }
        }
    }

    fun leaveSelection() {
        selecting = false
        selected = emptySet()
    }

    BackHandler(enabled = selecting) { leaveSelection() }

    val pageBack = { if (selecting) leaveSelection() else onBack() }
    AppPageScaffold(
        title = stringResource(R.string.settings_group_userdict_title),
        onBack = pageBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.screenHorizontal)
                .padding(top = AppSpacing.compactGap),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { search(it) },
                label = { Text(stringResource(R.string.user_dict_search_hint)) },
                singleLine = true,
                shape = AppShapes.section,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("user_dict_search"),
            )
            val selectionProgress by animateFloatAsState(
                targetValue = if (selecting) 1f else 0f,
                animationSpec = tween(SettingsMotion.DURATION_STATE, easing = SettingsMotion.EmphasizedDecelerate),
            )
            UserDictTopCard(
                selecting = selecting,
                selectionProgress = selectionProgress,
                readable = summary.readable,
                count = entries.size,
                forgotten = summary.forgotten,
                manageEnabled = filtered.isNotEmpty() || filteredLearned.isNotEmpty(),
                selectedCount = selected.size,
                deleteEnabled = selected.isNotEmpty(),
                onManage = { selecting = true },
                onAdd = { sheet = UserDictSheet.ADD },
                onMore = { sheet = UserDictSheet.MORE },
                onSelectAll = {
                    selected = (filtered.map { manualKey(it) } + filteredLearned.map { learnedKey(it) }).toSet()
                },
                onCancel = { leaveSelection() },
                onDeleteSelected = { pendingBatchDelete = true },
            )
            AppSection(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = AppSpacing.compactGap)
                    .testTag("user_dict_list_surface"),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("user_dict_list"),
                    contentPadding = PaddingValues(vertical = AppSpacing.textGap),
                ) {
                    if (summary.readable && filtered.isNotEmpty()) {
                        item(key = "manual_header") {
                            UserDictListHeader(
                                title = stringResource(R.string.user_dict_manual_title),
                            )
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
                    } else if (summary.readable && query.isBlank()) {
                        item(key = "manual_empty") {
                            UserDictListNote(
                                text = stringResource(R.string.user_dict_manual_empty),
                                modifier = Modifier.testTag("user_dict_empty_note"),
                            )
                        }
                    }

                    if (!learnedView.readable || filteredLearned.isNotEmpty() || query.isBlank()) {
                        item(key = "auto_learn_header") {
                            UserDictListHeader(
                                title = stringResource(R.string.user_dict_auto_title),
                                status = if (learnedView.readable && query.isBlank()) {
                                    stringResource(R.string.user_dict_auto_count_format, learned.size)
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    if (!learnedView.readable) {
                        item(key = "auto_learn_unreadable") {
                            UserDictListNote(
                                text = stringResource(
                                    if (learned.isEmpty()) R.string.user_learn_unreadable
                                    else R.string.user_learn_unreadable_kept,
                                ),
                                error = true,
                                modifier = Modifier.testTag("user_learn_unreadable"),
                            )
                        }
                    }
                    if (filteredLearned.isNotEmpty()) {
                        items(filteredLearned, key = { learnedKey(it) }) { entry ->
                            LearnedEntryRow(
                                entry,
                                selecting = selecting,
                                checked = learnedKey(entry) in selected,
                                onToggle = { toggle(learnedKey(entry)) },
                                onDelete = { pendingDelete = PendingDelete(entry.word, entry.reading, learned = true) },
                            )
                        }
                    } else if (learnedView.readable && query.isBlank()) {
                        item(key = "auto_learn_empty") {
                            UserDictListNote(
                                text = stringResource(
                                    if (learnedHasData) R.string.user_dict_auto_pairs_only else R.string.user_dict_auto_empty,
                                ),
                                modifier = if (learnedHasData) {
                                    Modifier.testTag("user_dict_auto_pairs_only")
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }

                    if (summary.readable && query.isNotBlank() && filtered.isEmpty() && filteredLearned.isEmpty()) {
                        item(key = "search_empty") {
                            UserDictListNote(
                                text = stringResource(R.string.user_dict_search_no_match),
                                modifier = Modifier.testTag("user_dict_empty_note"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (sheet == UserDictSheet.ADD) {
        UserDictAddSheet(
            word = newWord,
            reading = newReading,
            onWordChange = { newWord = it },
            onReadingChange = { newReading = it },
            onAdd = { addWord() },
            onDismiss = { sheet = null },
        )
    }

    if (sheet == UserDictSheet.MORE) {
        UserDictMoreSheet(
            dictionaryPath = userDb.absolutePath,
            learnedHasData = learnedHasData,
            learnedReadable = learnedView.readable,
            onExport = {
                sheet = null
                startExport()
            },
            onImport = {
                sheet = null
                importLauncher.launch(arrayOf("text/plain"))
            },
            onClearLearned = {
                sheet = null
                pendingAutoClear = true
            },
            onDismiss = { sheet = null },
        )
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
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.user_dict_batch_delete_dialog_body,
                        chosenCount,
                        chosenCount,
                    ),
                )
            },
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

private enum class UserDictSheet { ADD, MORE }

@Composable
private fun UserDictTopCard(
    selecting: Boolean,
    selectionProgress: Float,
    readable: Boolean,
    count: Int,
    forgotten: Int,
    manageEnabled: Boolean,
    selectedCount: Int,
    deleteEnabled: Boolean,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onMore: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    AppSection(modifier = Modifier.testTag("user_dict_overview")) {
        Column(
            modifier = Modifier.padding(
                horizontal = AppSpacing.sectionPadding,
                vertical = AppSpacing.contentGap,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.textGap),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(1f - selectionProgress)
                        .then(if (selecting) Modifier.clearAndSetSemantics {} else Modifier),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.textGap),
                ) {
                    if (readable) {
                        Text(
                            stringResource(R.string.user_dict_count_format, count),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("user_dict_count"),
                        )
                        Text(
                            stringResource(R.string.user_dict_forgotten_format, forgotten),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("user_dict_forgotten"),
                        )
                    } else {
                        Text(
                            stringResource(R.string.user_dict_unreadable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("user_dict_unreadable"),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .alpha(selectionProgress)
                        .then(if (selecting) Modifier else Modifier.clearAndSetSemantics {})
                        .testTag("user_dict_selection_context"),
                ) {
                    Text(
                        stringResource(R.string.user_dict_selected_count_format, selectedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("user_dict_selected_count"),
                    )
                }
            }
            Layout(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    UserDictSlotButtons(
                        selecting = selecting,
                        selectionProgress = selectionProgress,
                        normalText = stringResource(R.string.user_dict_select_button),
                        normalEnabled = manageEnabled,
                        onNormal = onManage,
                        normalTag = "user_dict_select",
                        selectText = stringResource(R.string.user_dict_select_all_button),
                        selectEnabled = true,
                        onSelect = onSelectAll,
                        selectTag = "user_dict_select_all",
                    )
                    UserDictSlotButtons(
                        selecting = selecting,
                        selectionProgress = selectionProgress,
                        normalText = stringResource(R.string.user_dict_add_sheet_button),
                        normalEnabled = true,
                        onNormal = onAdd,
                        normalTag = "user_dict_open_add",
                        selectText = stringResource(R.string.user_dict_select_cancel_button),
                        selectEnabled = true,
                        onSelect = onCancel,
                        selectTag = "user_dict_select_cancel",
                    )
                    UserDictSlotButtons(
                        selecting = selecting,
                        selectionProgress = selectionProgress,
                        normalText = stringResource(R.string.user_dict_more_button),
                        normalEnabled = true,
                        onNormal = onMore,
                        normalTag = "user_dict_open_more",
                        selectText = stringResource(R.string.user_dict_delete_selected_button),
                        selectEnabled = deleteEnabled,
                        onSelect = onDeleteSelected,
                        selectTag = "user_dict_delete_selected",
                    )
                },
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val intrinsics = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
                val normalSum = intrinsics[0] + intrinsics[2] + intrinsics[4]
                val selectSum = intrinsics[1] + intrinsics[3] + intrinsics[5]
                fun budget(index: Int): Int {
                    val sum = if (index % 2 == 0) normalSum else selectSum
                    if (sum <= width || sum == 0) return intrinsics[index]
                    return (width.toLong() * intrinsics[index] / sum).toInt()
                }
                val placeables = measurables.mapIndexed { index, measurable ->
                    measurable.measure(
                        constraints.copy(minWidth = 0, minHeight = 0, maxWidth = budget(index)),
                    )
                }
                fun slotWidth(slot: Int): Int {
                    val normal = placeables[slot * 2].width
                    val select = placeables[slot * 2 + 1].width
                    return normal + ((select - normal) * selectionProgress).roundToInt()
                }
                val height = placeables.maxOf { it.height }
                val middle = slotWidth(0) + (width - slotWidth(2) - slotWidth(0) - slotWidth(1)) / 2
                layout(width, height) {
                    for (i in 0..5) {
                        val slot = i / 2
                        val x = when (slot) {
                            0 -> 0
                            1 -> middle + (slotWidth(1) - placeables[i].width) / 2
                            else -> width - placeables[i].width
                        }
                        val y = (height - placeables[i].height) / 2
                        placeables[i].placeRelative(x, y)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserDictSlotButtons(
    selecting: Boolean,
    selectionProgress: Float,
    normalText: String,
    normalEnabled: Boolean,
    onNormal: () -> Unit,
    normalTag: String,
    selectText: String,
    selectEnabled: Boolean,
    onSelect: () -> Unit,
    selectTag: String,
) {
    AppPrimaryButton(
        text = normalText,
        onClick = onNormal,
        enabled = !selecting && normalEnabled,
        singleLine = true,
        modifier = Modifier
            .zIndex(if (selecting) 0f else 1f)
            .alpha(1f - selectionProgress)
            .then(if (selecting) Modifier.clearAndSetSemantics {} else Modifier)
            .testTag(normalTag),
    )
    AppPrimaryButton(
        text = selectText,
        onClick = onSelect,
        enabled = selecting && selectEnabled,
        singleLine = true,
        modifier = Modifier
            .zIndex(if (selecting) 1f else 0f)
            .alpha(selectionProgress)
            .then(if (selecting) Modifier else Modifier.clearAndSetSemantics {})
            .testTag(selectTag),
    )
}

@Composable
private fun UserDictListHeader(title: String, status: String? = null) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppSpacing.touchTarget)
                .padding(horizontal = AppSpacing.rowHorizontal, vertical = AppSpacing.compactGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("user_dict_auto_count"),
                )
            }
        }
        AppSectionDivider()
    }
}

@Composable
private fun UserDictListNote(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.rowHorizontal, vertical = AppSpacing.contentGap),
        )
        AppSectionDivider()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun UserDictAddSheet(
    word: String,
    reading: String,
    onWordChange: (String) -> Unit,
    onReadingChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = AppShapes.sheet,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag("user_dict_add_sheet"),
    ) {
        Box(modifier = Modifier.fillMaxWidth().imePadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.screenHorizontal)
                    .padding(bottom = AppSpacing.pageBottom),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
            ) {
                Text(stringResource(R.string.user_dict_add_sheet_button), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = word,
                    onValueChange = onWordChange,
                    label = { Text(stringResource(R.string.user_dict_word_hint)) },
                    singleLine = true,
                    shape = AppShapes.section,
                    modifier = Modifier.fillMaxWidth().testTag("user_dict_new_word"),
                )
                OutlinedTextField(
                    value = reading,
                    onValueChange = onReadingChange,
                    label = { Text(stringResource(R.string.user_dict_reading_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = AppShapes.section,
                    modifier = Modifier.fillMaxWidth().testTag("user_dict_new_reading"),
                )
                AppPrimaryButton(
                    text = stringResource(R.string.user_dict_add_button),
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth().testTag("user_dict_add"),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = AppSpacing.compactGap),
            ) {
                AegisToastOverlay(modifier = Modifier.testTag("user_dict_add_sheet_toast"))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun UserDictMoreSheet(
    dictionaryPath: String,
    learnedHasData: Boolean,
    learnedReadable: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearLearned: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = AppShapes.sheet,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag("user_dict_more_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenHorizontal)
                .padding(bottom = AppSpacing.pageBottom),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            Text(stringResource(R.string.user_dict_more_title), style = MaterialTheme.typography.titleLarge)
            AppSection {
                Column(
                    modifier = Modifier.padding(AppSpacing.sectionPadding),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
                ) {
                    Text(stringResource(R.string.user_dict_data_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.user_dict_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.user_dict_default_path_format, dictionaryPath),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            AppSection {
                Column(
                    modifier = Modifier.padding(AppSpacing.sectionPadding),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
                ) {
                    Text(stringResource(R.string.user_dict_auto_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.user_dict_auto_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppPrimaryButton(
                        text = stringResource(R.string.user_dict_auto_clear_button),
                        onClick = onClearLearned,
                        enabled = learnedHasData || !learnedReadable,
                        modifier = Modifier.fillMaxWidth().testTag("user_dict_auto_clear"),
                    )
                }
            }
        }
    }
}

private class PendingDelete(val word: String, val reading: String, val learned: Boolean)

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
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppSpacing.rowMinHeight)
                .then(
                    if (selecting) {
                        Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { onToggle() },
                            )
                    } else {
                        Modifier
                    },
                )
                .padding(start = AppSpacing.rowHorizontal, end = AppSpacing.compactGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).testTag("user_dict_entry_text"),
            )
            Box(
                modifier = Modifier.width(96.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (selecting) {
                    Checkbox(checked = checked, onCheckedChange = null)
                } else {
                    AppPrimaryButton(
                        text = stringResource(R.string.user_dict_delete_button),
                        onClick = onDelete,
                        singleLine = true,
                    )
                }
            }
        }
        AppSectionDivider()
    }
}
