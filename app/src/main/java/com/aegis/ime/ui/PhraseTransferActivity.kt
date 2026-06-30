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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.aegis.ime.user.ClipboardStore

/**
 * debug.17 E1: SAF bridge for 常用语 (canned-phrase) import / export. The IME service is not an Activity, so it
 * cannot host SAF document pickers — the clipboard panel's ✎ 二级菜单 launches this transparent helper instead.
 * It reads/writes the SAME [ClipboardStore] files in filesDir (the panel re-reads phrases on its next open), and
 * follows the same rules as the 学习词库 import ([UserDictCard]): 合并 (accumulate, dedupe) vs 覆盖 (replace), and
 * NEVER silently clears — an empty/unreadable file leaves the phrase library untouched.
 */
class PhraseTransferActivity : ComponentActivity() {

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val ok = PhraseTransferIo.exportPhrases(filesDir) { contentResolver.openOutputStream(uri) }
            toast(if (ok) "已导出常用语" else "导出失败")
        }
        finish()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { finish(); return@registerForActivityResult }
        val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        if (text == null) {
            toast("导入失败：文件无法读取,常用语未改动")
            finish()
        } else {
            applyImport(text, merge = intent.getBooleanExtra(EXTRA_IMPORT_MERGE, true))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_EXPORT, false)) exportLauncher.launch("aegis-phrases.txt")
        else importLauncher.launch(arrayOf("text/plain"))
    }

    private fun applyImport(text: String, merge: Boolean) {
        val ok = runCatching {
            ClipboardStore(filesDir).also { it.load() }.importPhrasesText(text, merge)
        }.getOrDefault(false)
        toast(
            if (ok) (if (merge) "已合并导入常用语" else "已覆盖导入常用语")
            else "导入失败：无有效内容,常用语未改动",
        )
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_EXPORT = "export" // true = export, false = import
        const val EXTRA_IMPORT_MERGE = "import_merge"
    }
}
