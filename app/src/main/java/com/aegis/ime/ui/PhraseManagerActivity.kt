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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.ime.ui.theme.AegisTheme
import com.aegis.ime.user.ClipboardStore

/**
 * C5 常用语管理: create / delete categories and add / delete phrases. Runs as a normal Activity (not in
 * the IME panel) so the text fields actually receive keyboard input — an EditText hosted inside the IME's
 * own window can't be typed into. Edits persist immediately to the same [ClipboardStore] file the IME reads.
 */
class PhraseManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // debug.16: the clipboard panel's 常用语 long-press 编辑 launches this Activity focused on the phrase's
        // category (and its inline editor pre-opened), since text input only works in an Activity, not the IME panel.
        val focusCategory = intent.getStringExtra(EXTRA_CATEGORY)
        val focusPhrase = intent.getStringExtra(EXTRA_PHRASE)
        val store = ClipboardStore(filesDir).apply { load() }
        setContent {
            AegisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PhraseManagerScreen(store, focusCategory, focusPhrase)
                }
            }
        }
    }

    companion object {
        const val EXTRA_CATEGORY = "aegis.phrase.category"
        const val EXTRA_PHRASE = "aegis.phrase.text"
    }
}

@Composable
internal fun PhraseManagerScreen(store: ClipboardStore, focusCategory: String? = null, focusPhrase: String? = null) {
    val initialCats = store.categories()
    val initialSel = focusCategory?.takeIf { it in initialCats } ?: initialCats.firstOrNull().orEmpty()
    var cats by remember { mutableStateOf(initialCats) }
    var sel by remember { mutableStateOf(initialSel) }
    var phrases by remember { mutableStateOf(store.phrasesIn(initialSel)) }
    var newCat by remember { mutableStateOf("") }
    var renameCat by remember { mutableStateOf("") }
    var newPhrase by remember { mutableStateOf("") }
    // debug.16: inline phrase edit. `editing` = the phrase whose inline field is open ("" = none); `editText`
    // is its draft. Pre-opened on the phrase the panel asked to edit (focusPhrase) when it exists in the category.
    var editing by remember { mutableStateOf(focusPhrase?.takeIf { it in store.phrasesIn(initialSel) }.orEmpty()) }
    var editText by remember { mutableStateOf(editing) }
    var editError by remember { mutableStateOf(false) } // editPhrase rejected the draft (duplicate)

    fun refresh() {
        cats = store.categories()
        if (sel !in cats) sel = cats.firstOrNull().orEmpty()
        phrases = store.phrasesIn(sel)
    }

    fun selectCategory(c: String) { sel = c; phrases = store.phrasesIn(c); editing = ""; editText = ""; editError = false }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("常用语管理", style = MaterialTheme.typography.headlineSmall)

        // 新建分类
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newCat,
                onValueChange = { newCat = it },
                label = { Text("新建分类") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { if (store.addCategory(newCat)) { sel = newCat.trim(); newCat = ""; refresh() } },
                enabled = newCat.isNotBlank(),
            ) { Text("新建") }
        }

        // 分类选择
        if (cats.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cats.forEach { c ->
                    val pick = { selectCategory(c) }
                    if (c == sel) Button(onClick = pick) { Text(c) } else OutlinedButton(onClick = pick) { Text(c) }
                }
            }
        }

        if (sel.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分类「$sel」", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { store.deleteCategory(sel); refresh() }) { Text("删除该分类") }
            }

            // 重命名分类
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = renameCat,
                    onValueChange = { renameCat = it },
                    label = { Text("重命名为") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { if (store.renameCategory(sel, renameCat)) { sel = renameCat.trim(); renameCat = ""; refresh() } },
                    enabled = renameCat.isNotBlank(),
                ) { Text("重命名") }
            }

            // 添加常用语
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPhrase,
                    onValueChange = { newPhrase = it },
                    label = { Text("添加常用语") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { if (store.addPhrasesTo(sel, listOf(newPhrase)) > 0) { newPhrase = ""; refresh() } },
                    enabled = newPhrase.isNotBlank(),
                ) { Text("添加") }
            }

            phrases.forEach { p ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    if (editing == p) {
                        // debug.16: inline edit — replace the phrase's text in place via store.editPhrase.
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = editText,
                                onValueChange = { editText = it; editError = false },
                                label = { Text("编辑常用语") },
                                singleLine = true,
                                isError = editError,
                                supportingText = if (editError) ({ Text("该分类已有相同常用语，未保存") }) else null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                TextButton(onClick = { editing = ""; editText = ""; editError = false }) { Text("取消") }
                                Button(
                                    onClick = { if (store.editPhrase(sel, p, editText)) { editing = ""; editText = ""; editError = false; refresh() } else editError = true },
                                    enabled = editText.isNotBlank(),
                                ) { Text("保存") }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { editing = p; editText = p; editError = false }) { Text("编辑") }
                            TextButton(onClick = { store.deletePhraseFrom(sel, p); refresh() }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}
