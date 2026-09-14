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

package com.aegis.ime.ime

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

internal class WebEditorTabInput(private val maxTextLength: Int = 65_536) {
    internal data class Marker(val target: InputConnection, val prefix: String, val suffix: String, val tabs: String)
    internal data class Prepared(val target: InputConnection, val before: EditorTextSnapshot,
        val commit: WindowEdit.Commit, val next: Marker?)

    private var marker: Marker? = null

    fun clear() { marker = null }

    fun prepare(target: InputConnection, text: CharSequence?, cursor: Int): Prepared? {
        val previous = marker
        marker = null
        val input = text?.toString().orEmpty()
        if (cursor != 1 || input.isEmpty() || previous == null && input != "\t") return null
        val before = read(target) ?: return null
        val matched = previous?.takeIf { it.target === target }?.let { match(it, before) }
        if (matched == null && input != "\t") return null
        val start = matched?.first ?: minOf(before.selectionStart, before.selectionEnd)
        val end = matched?.second ?: maxOf(before.selectionStart, before.selectionEnd)
        val inserted = if (matched == null) input else previous.tabs + input
        val replacement = matched?.let { WindowEdit.Replacement(start, end, previous.tabs) }
        val tabs = inserted.takeLastWhile { it == '\t' }
        val next = if (tabs.isEmpty()) null else {
            val prefix = if (matched != null) previous.prefix else prefix(before.text.toString(), start)
            val suffix = if (matched != null) previous.suffix else suffix(before.text.toString(), end)
            Marker(target, prefix + rendered(inserted.dropLast(tabs.length)), suffix, tabs)
        }
        return Prepared(target, before, WindowEdit.Commit(inserted, cursor, replacement = replacement), next)
    }

    fun apply(edit: Prepared): Boolean {
        val target = edit.target
        val replacement = edit.commit.replacement
        val accepted = if (replacement == null) target.commitText(edit.commit.text, edit.commit.cursor) else {
            target.beginBatchEdit()
            try {
                val current = read(target)
                if (current == null || current.text.toString() != edit.before.text.toString() ||
                    current.selectionStart != edit.before.selectionStart || current.selectionEnd != edit.before.selectionEnd) return false
                if (!target.setSelection(replacement.start, replacement.end)) return false
                val selected = read(target)
                if (selected == null || selected.text.toString() != edit.before.text.toString() ||
                    selected.selectionStart != replacement.start || selected.selectionEnd != replacement.end) {
                    if (selected?.text?.toString() == edit.before.text.toString())
                        target.setSelection(edit.before.selectionStart, edit.before.selectionEnd)
                    return false
                }
                target.commitText(edit.commit.text, edit.commit.cursor)
            } finally { target.endBatchEdit() }
        }
        marker = edit.next.takeIf { accepted }
        return accepted
    }

    private fun match(value: Marker, snapshot: EditorTextSnapshot): Pair<Int, Int>? {
        if (snapshot.selectionStart != snapshot.selectionEnd) return null
        val text = snapshot.text.toString()
        val prefix = Regex("\\A(?:${value.prefix})").find(text) ?: return null
        val start = prefix.value.length
        val end = snapshot.selectionEnd
        if (end <= start || !Regex("(?:${value.suffix})\\z").matches(text.substring(end))) return null
        if (!displayedTabs(text.substring(start, end), value.tabs.length)) return null
        return start to end
    }

    private fun displayedTabs(text: String, count: Int): Boolean {
        var minimum = 0
        var maximum = 0
        var spaces = false
        for (character in text) when (character) {
            '\t' -> { minimum++; maximum++; spaces = false }
            ' ' -> { if (!spaces) minimum++; maximum++; spaces = true }
            else -> return false
        }
        return count in minimum..maximum
    }

    private fun prefix(text: String, end: Int): String =
        if (placeholder(text, end - 1)) Regex.escape(text.substring(0, end - 1)) + "\\u200b?"
        else Regex.escape(text.substring(0, end))

    private fun suffix(text: String, start: Int): String =
        if (placeholder(text, start)) "\\u200b?" + Regex.escape(text.substring(start + 1))
        else Regex.escape(text.substring(start))

    private fun placeholder(text: String, at: Int): Boolean = at in text.indices && text[at] == '\u200b' &&
        (at == 0 || text[at - 1] == '\n') && at + 1 < text.length && text[at + 1] == '\n'

    private fun rendered(text: String): String = text.split('\t').joinToString("(?:\\t| +)", transform = Regex::escape)

    private fun read(target: InputConnection): EditorTextSnapshot? = runCatching {
        val extracted = target.getExtractedText(ExtractedTextRequest().apply {
            hintMaxChars = maxTextLength + 1
            hintMaxLines = maxTextLength + 1
        }, 0)
        val snapshot = if (extracted != null) {
            if (extracted.startOffset != 0 || extracted.partialStartOffset >= 0) return@runCatching null
            EditorTextSnapshot(extracted.text?.toString() ?: return@runCatching null, extracted.selectionStart, extracted.selectionEnd)
        } else {
            val around = target.getSurroundingText(maxTextLength + 1, maxTextLength + 1, 0) ?: return@runCatching null
            if (around.offset != 0) return@runCatching null
            EditorTextSnapshot(around.text.toString(), around.selectionStart, around.selectionEnd)
        }
        if (snapshot.text.length > maxTextLength || snapshot.selectionStart !in 0..snapshot.text.length ||
            snapshot.selectionEnd !in 0..snapshot.text.length) return@runCatching null
        val before = target.getTextBeforeCursor(maxTextLength + 1, 0)?.toString() ?: return@runCatching null
        val after = target.getTextAfterCursor(maxTextLength + 1, 0)?.toString() ?: return@runCatching null
        if (before != snapshot.text.subSequence(0, minOf(snapshot.selectionStart, snapshot.selectionEnd)).toString() ||
            after != snapshot.text.subSequence(maxOf(snapshot.selectionStart, snapshot.selectionEnd), snapshot.text.length).toString()) return@runCatching null
        snapshot
    }.getOrNull()
}
