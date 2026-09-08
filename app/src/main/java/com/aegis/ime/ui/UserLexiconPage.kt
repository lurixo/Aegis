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
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppShapes
import com.aegis.ime.ui.theme.AppSpacing
import com.aegis.ime.user.UserLexicon
import com.aegis.ime.user.UserStoreEdits
import java.util.concurrent.atomic.AtomicBoolean

internal enum class UserLexiconTab(val title: Int, val tag: String, val kind: UserLexicon.Kind? = null) {
    CHINESE(R.string.user_lexicon_chinese, "chinese"),
    ENGLISH(R.string.user_lexicon_english, "english", UserLexicon.Kind.ENGLISH),
    EMAIL(R.string.user_lexicon_email, "email", UserLexicon.Kind.EMAIL),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun UserLexiconTabs(selected: UserLexiconTab, onSelect: (UserLexiconTab) -> Unit) {
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("user_lexicon_tabs"),
    ) {
        UserLexiconTab.entries.forEach { tab ->
            Tab(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                text = { Text(stringResource(tab.title)) },
                modifier = Modifier.clip(MaterialTheme.shapes.medium).testTag("user_lexicon_tab_${tab.tag}"),
            )
        }
    }
}

@Composable
internal fun userLexiconOverviewHeight(width: Dp, chineseHelp: String): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium
    val labels = listOf(
        chineseHelp,
        stringResource(R.string.user_lexicon_english_help),
        stringResource(R.string.user_lexicon_email_help),
    )
    val contentWidth = with(density) { (width - AppSpacing.sectionPadding * 2).roundToPx().coerceAtLeast(1) }
    val textHeight = remember(textMeasurer, contentWidth, style, labels) {
        val countHeight = textMeasurer.measure("0", style = style).size.height
        val helpHeight = labels.maxOf { textMeasurer.measure(it, style = style, constraints = Constraints(maxWidth = contentWidth)).size.height }
        countHeight + helpHeight
    }
    val actionHeight = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + AppSpacing.compactGap * 2
    return with(density) { textHeight.toDp() } + AppSpacing.textGap + AppSpacing.contentGap * 3 +
        maxOf(AppSpacing.touchTarget, actionHeight)
}

@Composable
internal fun UserLexiconPage(
    kind: UserLexicon.Kind,
    resumeSignal: Int,
    current: Boolean,
    overviewHeight: Dp,
    onTools: () -> Unit,
    confirmReset: Boolean,
    onResetDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    val prefs = remember(context) { context.getSharedPreferences("aegis", Context.MODE_PRIVATE) }
    val lexicon = remember(prefs) { UserLexicon(prefs) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val active = remember { AtomicBoolean(true) }
    var entries by remember { mutableStateOf(lexicon.entries(kind)) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var newValue by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<UserLexicon.AddResult?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pendingBulkDelete by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val addedToast = stringResource(R.string.user_lexicon_added)
    val deletedToast = stringResource(R.string.user_lexicon_deleted)
    val batchDeletedToast = stringResource(R.string.user_lexicon_batch_deleted)
    val resetToast = stringResource(R.string.user_lexicon_reset_done)
    val failedToast = stringResource(R.string.user_dict_toast_write_failed)
    val english = kind == UserLexicon.Kind.ENGLISH
    val filtered = remember(entries, query, kind) {
        val term = query.trim().let { if (english) it else it.removePrefix("@") }
        entries.filter { it.contains(term, ignoreCase = true) }
    }

    fun reload() {
        UserStoreEdits.submit {
            val next = lexicon.entries(kind)
            mainHandler.post {
                if (active.get()) {
                    entries = next
                    selected = selected.intersect(next.toSet())
                }
            }
        }
    }

    DisposableEffect(prefs, kind) {
        active.set(true)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == UserLexicon.PREF_ENGLISH_WORDS || changed == UserLexicon.PREF_EMAIL_DOMAINS || changed == UserLexicon.PREF_DISABLED_EMAIL_DOMAINS) {
                reload()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            active.set(false)
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    LaunchedEffect(resumeSignal) { reload() }

    fun add() {
        if (busy) return
        busy = true
        addError = null
        val raw = newValue
        UserStoreEdits.submit {
            val result = runCatching { lexicon.add(kind, raw) }.getOrDefault(UserLexicon.AddResult.FAILED)
            val next = lexicon.entries(kind)
            mainHandler.post {
                if (active.get()) {
                    entries = next
                    busy = false
                    if (result == UserLexicon.AddResult.ADDED) {
                        newValue = ""
                        adding = false
                        AegisToast.show(addedToast)
                    } else {
                        addError = result
                    }
                }
            }
        }
    }

    fun remove(value: String) {
        if (busy) return
        busy = true
        pendingDelete = null
        UserStoreEdits.submit {
            val landed = runCatching { lexicon.remove(kind, value) }.getOrDefault(false)
            val next = lexicon.entries(kind)
            mainHandler.post {
                if (active.get()) {
                    entries = next
                    busy = false
                    AegisToast.show(if (landed) deletedToast else failedToast)
                }
            }
        }
    }

    fun resetEmailDefaults() {
        if (busy) return
        busy = true
        onResetDismiss()
        UserStoreEdits.submit {
            val landed = runCatching { lexicon.resetEmailDefaults() }.getOrDefault(false)
            val next = lexicon.entries(kind)
            mainHandler.post {
                if (active.get()) {
                    entries = next
                    busy = false
                    if (landed) query = ""
                    AegisToast.show(if (landed) resetToast else failedToast)
                }
            }
        }
    }

    fun leaveSelection() {
        selecting = false
        selected = emptySet()
    }

    fun removeSelected(values: Set<String>) {
        if (busy) return
        busy = true
        pendingBulkDelete = null
        leaveSelection()
        UserStoreEdits.submit {
            val landed = runCatching { lexicon.removeAll(kind, values) }.getOrDefault(false)
            val next = lexicon.entries(kind)
            mainHandler.post {
                if (active.get()) {
                    entries = next
                    busy = false
                    AegisToast.show(if (landed) batchDeletedToast else failedToast)
                }
            }
        }
    }

    LaunchedEffect(current) { if (!current) leaveSelection() }
    BackHandler(enabled = current && selecting) { leaveSelection() }

    Box(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(
            if (searchFocused) WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) else WindowInsets(0),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; selected = emptySet() },
                label = { Text(stringResource(if (english) R.string.user_lexicon_search_english else R.string.user_lexicon_search_email)) },
                singleLine = true,
                shape = AppShapes.section,
                modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused }.testTag("user_lexicon_search"),
            )
            AppSection(modifier = Modifier.height(overviewHeight).testTag("user_lexicon_overview")) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.sectionPadding, vertical = AppSpacing.contentGap),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.textGap)) {
                        Text(
                            stringResource(
                                if (selecting) R.string.user_dict_selected_count_format
                                else if (english) R.string.user_dict_count_format else R.string.user_lexicon_email_count_format,
                                if (selecting) selected.size else entries.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(if (selecting) "user_lexicon_selected_count" else "user_lexicon_count"),
                        )
                        Text(
                            stringResource(if (english) R.string.user_lexicon_english_help else R.string.user_lexicon_email_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
                    ) {
                        if (selecting) {
                            val allSelected = filtered.isNotEmpty() && selected.containsAll(filtered)
                            AppPrimaryButton(
                                text = stringResource(if (allSelected) R.string.user_dict_deselect_all_button else R.string.user_dict_select_all_button),
                                onClick = { selected = if (allSelected) emptySet() else filtered.toSet() },
                                singleLine = true,
                                contentPadding = PaddingValues(AppSpacing.compactGap),
                                enabled = !busy && filtered.isNotEmpty(),
                                modifier = Modifier.weight(1f).testTag("user_lexicon_select_all"),
                            )
                            AppPrimaryButton(
                                text = stringResource(R.string.user_dict_select_cancel_button),
                                onClick = { leaveSelection() },
                                singleLine = true,
                                contentPadding = PaddingValues(AppSpacing.compactGap),
                                enabled = !busy,
                                modifier = Modifier.weight(1f).testTag("user_lexicon_select_cancel"),
                            )
                            AppPrimaryButton(
                                text = stringResource(R.string.user_dict_delete_selected_button),
                                onClick = { pendingBulkDelete = selected.toSet() },
                                singleLine = true,
                                contentPadding = PaddingValues(AppSpacing.compactGap),
                                enabled = !busy && selected.isNotEmpty(),
                                modifier = Modifier.weight(1f).testTag("user_lexicon_delete_selected"),
                            )
                        } else {
                            AppPrimaryButton(
                                text = stringResource(R.string.user_lexicon_manage),
                                onClick = { selecting = true; selected = emptySet() },
                                singleLine = true,
                                contentPadding = PaddingValues(AppSpacing.compactGap),
                                enabled = !busy && filtered.isNotEmpty(),
                                modifier = Modifier.weight(1f).testTag("user_lexicon_select"),
                            )
                            AppPrimaryButton(
                                text = stringResource(R.string.user_dict_add_sheet_button),
                                onClick = { focus.clearFocus(); newValue = ""; addError = null; adding = true },
                                singleLine = true,
                                contentPadding = PaddingValues(AppSpacing.compactGap),
                                enabled = !busy,
                                modifier = Modifier.weight(1f).testTag("user_lexicon_open_add"),
                            )
                            AppPrimaryButton(
                                text = stringResource(R.string.user_dict_more_button),
                                onClick = onTools,
                                singleLine = true,
                                contentPadding = PaddingValues(AppSpacing.compactGap),
                                enabled = !busy,
                                modifier = Modifier.weight(1f).testTag("user_lexicon_open_more"),
                            )
                        }
                    }
                }
            }
            AppSection(modifier = Modifier.weight(1f).padding(bottom = AppSpacing.pageBottom).testTag("user_lexicon_list_surface")) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("user_lexicon_list"),
                    contentPadding = PaddingValues(vertical = AppSpacing.textGap),
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                stringResource(
                                    when {
                                        query.isNotBlank() -> R.string.user_lexicon_no_match
                                        english -> R.string.user_lexicon_empty_english
                                        else -> R.string.user_lexicon_empty_email
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(AppSpacing.sectionPadding).testTag("user_lexicon_empty"),
                            )
                        }
                    }
                    items(filtered, key = { it }) { value ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = AppSpacing.rowMinHeight)
                                .then(
                                    if (selecting) Modifier.toggleable(
                                        value = value in selected,
                                        enabled = !busy,
                                        role = Role.Checkbox,
                                        onValueChange = { checked -> selected = if (checked) selected + value else selected - value },
                                    ).testTag("user_lexicon_check_$value") else Modifier,
                                )
                                .padding(start = AppSpacing.rowHorizontal, end = AppSpacing.compactGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
                        ) {
                            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (selecting) {
                                Checkbox(checked = value in selected, onCheckedChange = null, enabled = !busy)
                            } else {
                                AppPrimaryButton(
                                    text = stringResource(R.string.user_dict_delete_button),
                                    onClick = { pendingDelete = value },
                                    enabled = !busy,
                                    singleLine = true,
                                    modifier = Modifier.testTag("user_lexicon_delete_$value"),
                                )
                            }
                        }
                        AppSectionDivider()
                    }
                }
            }
        }
    }

    if (adding) {
        val error = when (addError) {
            UserLexicon.AddResult.EXISTS -> stringResource(R.string.user_lexicon_exists)
            UserLexicon.AddResult.INVALID -> stringResource(if (english) R.string.user_lexicon_invalid_english else R.string.user_lexicon_invalid_email)
            UserLexicon.AddResult.FAILED -> failedToast
            else -> null
        }
        UserLexiconAddDialog(
            english = english,
            value = newValue,
            error = error,
            busy = busy,
            onChange = { newValue = it; addError = null },
            onAdd = { add() },
            onDismiss = { adding = false },
        )
    }

    if (confirmReset) {
        AegisAlertDialog(
            onDismissRequest = { onResetDismiss() },
            title = { Text(stringResource(R.string.user_lexicon_reset_defaults)) },
            text = { Text(stringResource(R.string.user_lexicon_reset_body)) },
            confirmButton = {
                TextButton(onClick = { resetEmailDefaults() }, modifier = Modifier.testTag("user_lexicon_reset_confirm")) {
                    Text(stringResource(R.string.user_lexicon_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onResetDismiss() }, modifier = Modifier.testTag("user_lexicon_reset_cancel")) {
                    Text(stringResource(R.string.user_dict_delete_cancel))
                }
            },
        )
    }

    pendingDelete?.let { value ->
        AegisAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.user_lexicon_delete_title)) },
            text = { Text(stringResource(R.string.user_lexicon_delete_body, value)) },
            confirmButton = {
                TextButton(onClick = { remove(value) }, modifier = Modifier.testTag("user_lexicon_delete_confirm")) {
                    Text(stringResource(R.string.user_dict_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, modifier = Modifier.testTag("user_lexicon_delete_cancel")) {
                    Text(stringResource(R.string.user_dict_delete_cancel))
                }
            },
        )
    }

    pendingBulkDelete?.let { values ->
        AegisAlertDialog(
            onDismissRequest = { pendingBulkDelete = null },
            title = { Text(stringResource(R.string.user_lexicon_batch_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.user_lexicon_batch_delete_body, values.size, values.size)) },
            confirmButton = {
                TextButton(
                    onClick = { removeSelected(values) },
                    modifier = Modifier.testTag("user_lexicon_batch_delete_confirm"),
                ) { Text(stringResource(R.string.user_dict_delete_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingBulkDelete = null },
                    modifier = Modifier.testTag("user_lexicon_batch_delete_cancel"),
                ) { Text(stringResource(R.string.user_dict_delete_cancel)) }
            },
        )
    }
}

@Composable
private fun UserLexiconAddDialog(
    english: Boolean,
    value: String,
    error: String?,
    busy: Boolean,
    onChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    UserDictEntryDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("user_lexicon_add_sheet"),
        title = { Text(stringResource(if (english) R.string.user_lexicon_add_english else R.string.user_lexicon_add_email)) },
        confirmButton = {
            TextButton(
                onClick = onAdd,
                enabled = !busy && value.isNotBlank(),
                modifier = Modifier.testTag("user_lexicon_add"),
            ) { Text(stringResource(R.string.user_dict_add_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.user_dict_delete_cancel)) }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    enabled = !busy,
                    label = { Text(stringResource(if (english) R.string.user_lexicon_word_hint else R.string.user_lexicon_domain_hint)) },
                    supportingText = {
                        Text(error ?: stringResource(if (english) R.string.user_lexicon_word_help else R.string.user_lexicon_domain_help))
                    },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = if (english) KeyboardType.Ascii else KeyboardType.Email,
                    ),
                    shape = AppShapes.section,
                    modifier = Modifier.fillMaxWidth().testTag("user_lexicon_new_value").userDictInitialFocus(),
                )
            }
        },
    )
}
