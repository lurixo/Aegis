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

import android.view.inputmethod.InputConnection

object EditorSweep {

    const val CHUNK = 65_536
    const val MAX_CHARS = 33_554_432

    fun hasText(ic: InputConnection): Boolean =
        !ic.getTextBeforeCursor(1, 0).isNullOrEmpty() ||
            !ic.getTextAfterCursor(1, 0).isNullOrEmpty() ||
            !ic.getSelectedText(0).isNullOrEmpty()

    fun nearbyLength(ic: InputConnection, bound: Int = CHUNK): Int =
        (ic.getTextBeforeCursor(bound, 0)?.length ?: 0) +
            (ic.getSelectedText(0)?.length ?: 0) +
            (ic.getTextAfterCursor(bound, 0)?.length ?: 0)

    fun clearCapturing(ic: InputConnection): CharSequence {
        val capture = Capture(ic)
        while (true) {
            when (capture.advance()) {
                Progress.DONE -> return capture.text()
                Progress.WAITING -> { capture.cancel(); return capture.text() }
                Progress.MORE -> Unit
            }
        }
    }

    enum class Progress { MORE, WAITING, DONE }

    class Capture(private val ic: InputConnection) {
        private enum class Phase { SELECTED, BEFORE, AFTER, DONE }
        private data class Pending(val expected: Selection, val accept: () -> Unit)
        private var phase = Phase.SELECTED
        private var pending: Pending? = null
        private val preceding = ArrayDeque<String>()
        private var selected = ""
        private val following = StringBuilder()
        private var captured = 0
        private var afterChunk: String? = null
        private var afterOrigin = 0

        fun advance(): Progress {
            if (phase == Phase.DONE) return Progress.DONE
            pending?.let { step ->
                if (selection(ic) != step.expected) return Progress.WAITING
                pending = null
                step.accept()
                return Progress.MORE
            }
            if (captured >= MAX_CHARS) return finish()
            return when (phase) {
                Phase.SELECTED -> {
                    val text = ic.getSelectedText(0)?.toString().orEmpty()
                    if (text.isEmpty()) { phase = Phase.BEFORE; Progress.MORE }
                    else {
                        val origin = selection(ic) ?: return finish()
                        if (text.length > MAX_CHARS) return finish()
                        dispatch(Selection(origin.start, origin.start), { ic.commitText("", 1) }) {
                            selected = text
                            captured += text.length
                            phase = Phase.BEFORE
                        }
                    }
                }
                Phase.BEFORE -> {
                    val got = ic.getTextBeforeCursor(minOf(CHUNK, MAX_CHARS - captured), 0)
                    if (got.isNullOrEmpty()) { phase = Phase.AFTER; Progress.MORE }
                    else {
                        val text = got.toString()
                        val origin = selection(ic) ?: return finish()
                        if (origin.start != origin.end || origin.start < text.length) return finish()
                        dispatch(Selection(origin.start - text.length, origin.start - text.length),
                            { ic.deleteSurroundingText(text.length, 0) }) {
                            preceding.addFirst(text)
                            captured += text.length
                        }
                    }
                }
                Phase.AFTER -> {
                    val ready = afterChunk
                    if (ready != null) {
                        dispatch(Selection(afterOrigin, afterOrigin), { ic.deleteSurroundingText(ready.length, 0) }) {
                            following.append(ready)
                            captured += ready.length
                            afterChunk = null
                        }
                    } else {
                        val got = ic.getTextAfterCursor(minOf(CHUNK, MAX_CHARS - captured), 0)
                        if (got.isNullOrEmpty()) {
                            edit(ic) { ic.performContextMenuAction(android.R.id.selectAll) && ic.commitText("", 1) }
                            finish()
                        } else {
                            val text = got.toString()
                            val origin = selection(ic) ?: return finish()
                            if (origin.start != origin.end) return finish()
                            val end = origin.end + text.length
                            dispatch(Selection(end, end), { ic.setSelection(end, end) }) {
                                afterOrigin = origin.start
                                afterChunk = text
                            }
                        }
                    }
                }
                Phase.DONE -> Progress.DONE
            }
        }

        private fun dispatch(expected: Selection, action: () -> Boolean, accept: () -> Unit): Progress {
            pending = Pending(expected, accept)
            if (!edit(ic, action)) { pending = null; return finish() }
            return Progress.MORE
        }

        fun cancel() {
            pending?.let { if (selection(ic) == it.expected) it.accept() }
            if (afterChunk != null) edit(ic) { ic.setSelection(afterOrigin, afterOrigin) }
            finish()
        }

        fun text(): CharSequence = StringBuilder(captured).apply {
            preceding.forEach { append(it) }
            append(selected)
            append(following)
        }

        private fun finish(): Progress {
            pending = null
            phase = Phase.DONE
            return Progress.DONE
        }
    }

    private data class Selection(val start: Int, val end: Int)

    private fun selection(ic: InputConnection): Selection? = runCatching {
        val surrounding = ic.getSurroundingText(0, 0, 0) ?: return@runCatching null
        if (surrounding.offset < 0 || surrounding.selectionStart < 0 || surrounding.selectionEnd < 0) return@runCatching null
        Selection(
            surrounding.offset + minOf(surrounding.selectionStart, surrounding.selectionEnd),
            surrounding.offset + maxOf(surrounding.selectionStart, surrounding.selectionEnd),
        )
    }.getOrNull()

    private inline fun edit(ic: InputConnection, action: () -> Boolean): Boolean = try {
        ic.beginBatchEdit()
        try { action() } finally { ic.endBatchEdit() }
    } catch (_: RuntimeException) { false }
}
