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

class PhraseManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = ClipboardStore(filesDir).apply { load() }
        setContent {
            AegisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PhraseManagerScreen(store)
                }
            }
        }
    }
}

@Composable
private fun PhraseManagerScreen(store: ClipboardStore) {
    var cats by remember { mutableStateOf(store.categories()) }
    var sel by remember { mutableStateOf(cats.firstOrNull().orEmpty()) }
    var phrases by remember { mutableStateOf(store.phrasesIn(sel)) }
    var newCat by remember { mutableStateOf("") }
    var renameCat by remember { mutableStateOf("") }
    var newPhrase by remember { mutableStateOf("") }

    fun refresh() {
        cats = store.categories()
        if (sel !in cats) sel = cats.firstOrNull().orEmpty()
        phrases = store.phrasesIn(sel)
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("常用语管理", style = MaterialTheme.typography.headlineSmall)

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

        if (cats.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cats.forEach { c ->
                    val pick = { sel = c; phrases = store.phrasesIn(c) }
                    if (c == sel) Button(onClick = pick) { Text(c) } else OutlinedButton(onClick = pick) { Text(c) }
                }
            }
        }

        if (sel.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分类「$sel」", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { store.deleteCategory(sel); refresh() }) { Text("删除该分类") }
            }

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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { store.deletePhraseFrom(sel, p); refresh() }) { Text("删除") }
                    }
                }
            }
        }
    }
}
