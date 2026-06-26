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

package com.aegis.ime.user

import java.io.File

/**
 * On-device clipboard history + canned phrases (issue #7). Plain-text files in
 * filesDir — newest first, de-duplicated, capped. Nothing leaves the device.
 */
class ClipboardStore(private val dir: File) {

    private val histFile get() = File(dir, "clipboard.txt")
    private val phraseFile get() = File(dir, "phrases.txt")

    private val history = ArrayList<String>()
    private val phrases = ArrayList<String>()

    fun load() {
        history.clear()
        phrases.clear()
        runCatching { if (histFile.exists()) histFile.readLines().forEach { decode(it)?.let(history::add) } }
        if (phraseFile.exists()) {
            runCatching { phraseFile.readLines().forEach { decode(it)?.let(phrases::add) } }
        }
        if (phrases.isEmpty()) phrases.addAll(DEFAULT_PHRASES)
    }

    /** Add [text] to the front of the history (dedup, trim, persist). No-op for blank text. */
    fun record(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return
        history.remove(t)
        history.add(0, t)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        runCatching { histFile.writeText(history.joinToString("\n") { encode(it) }) }
    }

    fun history(): List<String> = history.toList()
    fun phrases(): List<String> = phrases.toList()

    // Single-line encoding so multi-line clips survive a line-based file.
    private fun encode(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")
    private fun decode(line: String): String? {
        if (line.isEmpty()) return null
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                when (line[i + 1]) { 'n' -> sb.append('\n'); '\\' -> sb.append('\\'); else -> sb.append(line[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_HISTORY = 50
        val DEFAULT_PHRASES = listOf(
            "你好", "谢谢", "好的", "收到", "在吗？", "稍等一下", "马上到", "没问题",
            "抱歉，刚看到消息", "哈哈哈", "晚点联系你", "辛苦了",
        )
    }
}
