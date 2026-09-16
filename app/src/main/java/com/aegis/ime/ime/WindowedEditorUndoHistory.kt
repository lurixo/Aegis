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

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.ReplacementSpan
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection

internal sealed interface WindowEdit {
    data class Replacement(val start: Int, val end: Int, val removed: CharSequence)
    data class Commit(val text: CharSequence, val cursor: Int, val composing: Boolean = false,
        val replacement: Replacement? = null) : WindowEdit
    data class Delete(val before: Int, val after: Int, val codePoints: Boolean = false) : WindowEdit
    data class Key(val event: KeyEvent) : WindowEdit
    data class Context(val id: Int) : WindowEdit
    data object FinishComposition : WindowEdit
    data object Unknown : WindowEdit
}

internal class WindowedEditorUndoHistory {
    private data class Frame(val text: CharSequence, val offset: Int, val start: Int, val end: Int, val absolute: Boolean = true) {
        val limit get() = offset + text.length
        val low get() = minOf(start, end)
        val high get() = maxOf(start, end)
        fun slice(from: Int, through: Int): CharSequence = text.subSequence(from - offset, through - offset)
    }
    private data class Change(val start: Int, val removed: CharSequence, val inserted: CharSequence,
        val left: String, val right: String, val selectionStart: Int, val selectionEnd: Int,
        val documentEnd: Boolean = false) {
        val size get() = removed.length + inserted.length + left.length + right.length
        val afterEnd get() = start + inserted.length
    }
    private data class Prediction(val frame: Frame, val start: Int, val beforeEnd: Int, val afterEnd: Int)
    private data class Pending(val target: InputConnection, val before: Frame, val baseline: Frame,
        val predicted: Prediction?, val composing: Boolean, val finishes: Boolean, val aggregate: Frame? = null,
        val deadline: Long = SystemClock.uptimeMillis() + 2_000L)
    private data class PendingChange(val target: InputConnection, val entry: Change,
        val deadline: Long = SystemClock.uptimeMillis() + 2_000L)
    private data class Replay(val target: InputConnection, val entry: Change, val original: Pair<Int, Int>?,
        var phase: Int = 0, var deferred: Boolean = false, var sentThrough: Int = 0,
        var verifiedThrough: Int = 0, var verificationEnd: Int = 0,
        var deadline: Long = SystemClock.uptimeMillis() + 2_000L, val insertion: Change? = null,
        var confirmedThrough: Int? = null, var writeDispatched: Boolean = false)
    private data class Recovery(val failed: Replay, var entry: Change)
    private data class Expectation(val selection: Pair<Int, Int>, val previous: Pair<Int, Int>, val entry: Change?)

    var selectionProvider: (() -> Pair<Int, Int>?)? = null
    var onChange: (() -> Unit)? = null
    var onUndoCompleted: ((Boolean) -> Unit)? = null
    var onInsertionCompleted: ((Boolean) -> Unit)? = null
    private val entries = ArrayDeque<Change>()
    private var pending: Pending? = null
    private var pendingChange: PendingChange? = null
    private var compositionBefore: Frame? = null
    private var compositionAfter: Frame? = null
    private var batchDepth = 0
    private var batchBefore: Frame? = null
    private var batchAfter: Frame? = null
    private var batchFailed = false
    private var replay: Replay? = null
    private var recovery: Recovery? = null
    private val expectations = ArrayDeque<Expectation>()
    private var probe: Pair<Int, Int>? = null
    private var reportsSelection = false
    private var contradicted = false
    private var contradictions = 0
    private var settling = false
    private val handler = Handler(Looper.getMainLooper())
    private val replayPump = object : Runnable {
        override fun run() {
            pendingChange?.let { settle(it.target) }
            pending?.let { settle(it.target) }
            continueReplay()
            if (replay != null || pendingChange != null || pending != null) handler.postDelayed(this, 32L)
        }
    }
    val active get() = entries.isNotEmpty() || pending != null || pendingChange != null || compositionBefore != null || batchDepth > 0 || replay != null
    val hasUndo get() = replay == null && pendingChange == null && (entries.isNotEmpty() || compositionBefore != null && compositionAfter != null)
    val hasPendingUndo get() = replay != null && replay?.insertion == null
    val hasPendingInsertion get() = replay?.insertion != null
    val hasDeletion get() = hasUndo && entries.lastOrNull()?.let { it.removed.length > it.inserted.length } == true

    fun clear() {
        handler.removeCallbacks(replayPump)
        entries.clear()
        pending = null
        pendingChange = null
        compositionBefore = null
        compositionAfter = null
        batchBefore = null
        batchAfter = null
        batchFailed = false
        batchDepth = 0
        replay = null
        recovery = null
        expectations.clear()
        contradicted = false
        onChange?.invoke()
    }

    private fun read(target: InputConnection, expected: Pair<Int, Int>? = null, reach: Int = WINDOW): Frame? = runCatching {
        val supplied = selectionProvider?.invoke()?.takeIf { it.first >= 0 && it.second >= 0 }
        if (supplied != null && kotlin.math.abs(supplied.second.toLong() - supplied.first) > MAX_CHANGE) return@runCatching null
        val selection = expected ?: supplied
        val selectedLength = selection?.let { kotlin.math.abs(it.second.toLong() - it.first) } ?: 0L
        val boundedReach = minOf(reach, ((MAX_READ.toLong() - selectedLength).coerceAtLeast(0L) / 2).toInt())
        val value = target.getSurroundingText(boundedReach, boundedReach,
            InputConnection.GET_TEXT_WITH_STYLES) ?: return@runCatching null
        val text = value.text
        if (text.length > MAX_READ || value.selectionStart !in 0..text.length || value.selectionEnd !in 0..text.length ||
            '\uFFFC' in text || text is Spanned && text.getSpans(0, text.length, ReplacementSpan::class.java).isNotEmpty()) return@runCatching null
        val low = minOf(value.selectionStart, value.selectionEnd)
        val high = maxOf(value.selectionStart, value.selectionEnd)
        val offset = if (value.offset >= 0) value.offset else {
            val selection = supplied ?: return@runCatching null
            if (expected != null && selection != expected ||
                kotlin.math.abs(selection.second - selection.first) != high - low) return@runCatching null
            minOf(selection.first, selection.second) - low
        }
        if (offset < 0 || offset.toLong() + text.length > Int.MAX_VALUE) return@runCatching null
        val actualSelection = if (value.offset < 0) supplied!! else offset + value.selectionStart to offset + value.selectionEnd
        if (expected != null && actualSelection != expected) return@runCatching null
        val frozen = if (text is Spanned) {
            val copy = SpannableStringBuilder(text)
            BaseInputConnection.removeComposingSpans(copy)
            SpannedString(copy)
        } else text.toString()
        Frame(frozen, offset, actualSelection.first, actualSelection.second, value.offset >= 0)
    }.getOrNull()

    private fun replacement(frame: Frame, start: Int, end: Int, text: CharSequence, cursor: Pair<Int, Int>): Prediction? {
        if (start < frame.offset || end > frame.limit || end < start || text.length > MAX_CHANGE) return null
        val result = SpannableStringBuilder(frame.text).replace(start - frame.offset, end - frame.offset, text)
        return Prediction(Frame(SpannedString(result), frame.offset, cursor.first, cursor.second), start, end, start + text.length)
    }

    private fun predict(before: Frame, operation: WindowEdit): Prediction? {
        val base = if (operation is WindowEdit.Commit) compositionBefore ?: before else before
        val start = base.low
        val end = base.high
        return when (operation) {
            is WindowEdit.Commit -> {
                val cursor = if (operation.cursor > 0) start + operation.text.length + operation.cursor - 1 else start + operation.cursor
                replacement(base, start, end, operation.text, cursor.coerceAtLeast(0) to cursor.coerceAtLeast(0))
            }
            is WindowEdit.Delete -> {
                if (operation.before < 0 || operation.after < 0) return null
                val text = base.text.toString()
                val localStart = start - base.offset
                val localEnd = end - base.offset
                val from = if (operation.codePoints) Character.offsetByCodePoints(text, localStart,
                    -minOf(operation.before, Character.codePointCount(text, 0, localStart))) else maxOf(0, localStart - operation.before)
                val through = if (operation.codePoints) Character.offsetByCodePoints(text, localEnd,
                    minOf(operation.after, Character.codePointCount(text, localEnd, text.length))) else minOf(text.length, localEnd + operation.after)
                if (from == 0 && base.offset > 0 && operation.before > localStart ||
                    through == text.length && operation.after > text.length - localEnd) return null
                val shift = localStart - from
                replacement(base, base.offset + from, base.offset + through, base.slice(start, end),
                    base.start - shift to base.end - shift)
            }
            is WindowEdit.Key -> {
                val event = operation.event
                if (!event.hasNoModifiers()) return null
                val text = base.text.toString()
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        val from = if (start != end) start else base.offset + GraphemeText.previousCluster(text, start - base.offset)
                        replacement(base, from, end, "", from to from)
                    }
                    KeyEvent.KEYCODE_FORWARD_DEL -> {
                        val through = if (start != end) end else base.offset + GraphemeText.nextCluster(text, end - base.offset)
                        replacement(base, start, through, "", start to start)
                    }
                    KeyEvent.KEYCODE_ENTER -> replacement(base, start, end, "\n", start + 1 to start + 1)
                    KeyEvent.KEYCODE_TAB -> replacement(base, start, end, "\t", start + 1 to start + 1)
                    KeyEvent.KEYCODE_CUT -> replacement(base, start, end, "", start to start)
                    else -> event.unicodeChar.takeIf { it in 0x20..0x10ffff }?.let {
                        val inserted = String(Character.toChars(it))
                        replacement(base, start, end, inserted, start + inserted.length to start + inserted.length)
                    }
                }
            }
            is WindowEdit.Context -> if (operation.id == android.R.id.cut) replacement(base, start, end, "", start to start) else null
            WindowEdit.FinishComposition -> Prediction(before, start, end, end)
            WindowEdit.Unknown -> null
        }
    }

    private fun agrees(expected: Frame, actual: Frame, start: Int, end: Int): Boolean {
        val from = maxOf(expected.offset, actual.offset)
        val through = minOf(expected.limit, actual.limit)
        val guardedStart = maxOf(expected.offset, start - GUARD)
        val guardedEnd = minOf(expected.limit, end + GUARD)
        return from <= guardedStart && through >= guardedEnd && through >= from &&
            expected.slice(from, through).toString() == actual.slice(from, through).toString()
    }

    private fun change(before: Frame, after: Frame): Change? {
        if (before.offset == after.offset) {
            var start = 0
            while (start < before.text.length && start < after.text.length && before.text[start] == after.text[start]) start++
            var beforeEnd = before.text.length
            var afterEnd = after.text.length
            while (beforeEnd > start && afterEnd > start && before.text[beforeEnd - 1] == after.text[afterEnd - 1]) {
                beforeEnd--
                afterEnd--
            }
            if (start == beforeEnd && start == afterEnd) return null
            if (start > 0 && start < before.text.length && Character.isLowSurrogate(before.text[start])) start--
            if (beforeEnd < before.text.length && beforeEnd > start && Character.isLowSurrogate(before.text[beforeEnd])) {
                beforeEnd++
                afterEnd++
            }
            if (beforeEnd - start + afterEnd - start > MAX_CHANGE) return null
            return Change(before.offset + start, before.text.subSequence(start, beforeEnd), after.text.subSequence(start, afterEnd),
                before.text.subSequence(maxOf(0, start - GUARD), start).toString(),
                before.text.subSequence(beforeEnd, minOf(before.text.length, beforeEnd + GUARD)).toString(), before.start, before.end)
        }
        val from = maxOf(before.offset, after.offset)
        val leftEnd = minOf(before.low, after.low)
        if (from > leftEnd) return null
        var start = from
        while (start < leftEnd && before.slice(start, start + 1).toString() == after.slice(start, start + 1).toString()) start++
        if (start != leftEnd) return null
        var beforeEnd = before.high
        var afterEnd = after.high
        if (beforeEnd > before.limit || afterEnd > after.limit) return null
        val beforeTail = before.limit - beforeEnd
        val afterTail = after.limit - afterEnd
        val tail = minOf(beforeTail, afterTail)
        if (before.slice(beforeEnd, beforeEnd + tail).toString() != after.slice(afterEnd, afterEnd + tail).toString()) return null
        val old = before.slice(start, beforeEnd)
        val new = after.slice(start, afterEnd)
        if (old.toString() == new.toString()) return null
        if (old.length + new.length > MAX_CHANGE) return null
        return Change(start, old, new,
            before.slice(maxOf(before.offset, start - GUARD), start).toString(),
            before.slice(beforeEnd, minOf(before.limit, beforeEnd + GUARD)).toString(), before.start, before.end)
    }

    private fun record(before: Frame, after: Frame) {
        val entry = change(before, after) ?: return
        record(entry)
    }

    private fun record(entry: Change, keepUnchanged: Boolean = false): Change? {
        if (!keepUnchanged && entry.removed.toString() == entry.inserted.toString()) return null
        val retained = entry.copy(removed = retainText(entry.removed), inserted = retainText(entry.inserted))
        val largest = maxOf(entry.size, entries.maxOfOrNull { it.size } ?: 0)
        val limit = MAX_RETAINED.toLong() + if (largest > MAX_RETAINED) 2L * largest else 0
        entries.addLast(retained)
        while (entries.size > 1 && (entries.size > 50 || retainedCharacters() > limit)) entries.removeFirst()
        if (recovery != null && entries.none { it === recovery?.entry }) recovery = null
        return retained
    }

    private fun retainText(text: CharSequence): CharSequence {
        if (text is Spanned) return SpannedString(text)
        val value = text.toString()
        for (entry in entries.reversed()) {
            for (retained in listOf(entry.removed, entry.inserted)) {
                if (retained !is Spanned && retained.length == value.length && retained.toString() == value) return retained
            }
        }
        return value
    }

    private fun retainedCharacters(): Long {
        val counted = java.util.IdentityHashMap<CharSequence, Unit>()
        var size = 0L
        for (entry in entries) {
            size += entry.left.length + entry.right.length
            for (text in listOf(entry.removed, entry.inserted)) {
                if (counted.put(text, Unit) == null) size += text.length
            }
        }
        return size
    }

    private fun finish(pendingEdit: Pending, after: Frame) {
        pending = null
        val confirmed = pendingEdit.aggregate ?: pendingEdit.predicted?.frame ?: after
        if (pendingEdit.composing) {
            compositionBefore = pendingEdit.baseline
            compositionAfter = confirmed
        } else if (batchDepth > 0) {
            batchBefore = batchBefore ?: pendingEdit.baseline
            batchAfter = confirmed
            if (pendingEdit.finishes) { compositionBefore = null; compositionAfter = null }
        } else {
            record(pendingEdit.baseline, confirmed)
            compositionBefore = null
            compositionAfter = null
        }
        onChange?.invoke()
    }

    private fun settle(target: InputConnection) {
        pendingChange?.let { edit ->
            if (SystemClock.uptimeMillis() >= edit.deadline) { clear(); return }
            val entry = edit.entry
            val after = read(target, entry.afterEnd to entry.afterEnd) ?: return
            val valid = if (entry.inserted.isNotEmpty()) matchesInsertion(after, entry,
                maxOf(0, entry.inserted.length - WINDOW + GUARD), entry.inserted.length) else matches(after, entry, false)
            if (!valid) { clear(); return }
            pendingChange = null
            record(entry)
            onChange?.invoke()
        }
        val edit = pending ?: return
        // An editor applies a key event on its own thread, so a read can still show the old text.
        // Keep waiting until the edit is either confirmed or too old to be worth holding.
        val expired = SystemClock.uptimeMillis() >= edit.deadline
        val predicted = edit.predicted
        val after = read(target, predicted?.frame?.let { it.start to it.end },
            maxOf(WINDOW, predicted?.let { it.afterEnd - it.start + GUARD } ?: WINDOW))
        if (after == null) { if (expired) clear(); return }
        if (predicted == null && !after.absolute && after.start == edit.before.start && after.end == edit.before.end) {
            if (expired) clear()
            return
        }
        val matches = if (predicted != null) agrees(predicted.frame, after, predicted.start, predicted.afterEnd)
            else change(edit.before, after) != null
        if (matches) finish(edit, after)
        else if (!agrees(edit.before, after, edit.before.low, edit.before.high)) clear()
    }

    fun track(target: InputConnection, operation: WindowEdit, action: () -> Boolean): Boolean {
        expectations.clear()
        verify(target)
        settle(target)
        if (pending != null || pendingChange != null || replay != null) clear()
        val before = read(target)
        if (before == null) { clear(); return action() }
        val composing = operation is WindowEdit.Commit && operation.composing
        val finishes = operation is WindowEdit.Commit && !operation.composing || operation == WindowEdit.FinishComposition
        val baseline = compositionBefore ?: batchBefore ?: before
        val predicted = predict(before, operation)
        if (predicted == null && operation !is WindowEdit.Context &&
            !(operation is WindowEdit.Key && operation.event.keyCode == KeyEvent.KEYCODE_PASTE) && operation != WindowEdit.Unknown) {
            clear()
            return action()
        }
        val previous = batchAfter ?: batchBefore
        val aggregate = if (previous != null && predicted != null && !composing && compositionBefore == null)
            replacement(previous, predicted.start, predicted.beforeEnd, predicted.frame.slice(predicted.start, predicted.afterEnd),
                predicted.frame.start to predicted.frame.end)?.frame else null
        if (previous != null && predicted != null && !composing && compositionBefore == null && aggregate == null) batchFailed = true
        val edit = Pending(target, before, baseline, predicted, composing, finishes, aggregate)
        val accepted = try { action() } catch (error: RuntimeException) { clear(); throw error }
        pending = edit
        settle(target)
        if (!accepted && pending != null) { pending = null; onChange?.invoke() }
        else if (pending != null) handler.postDelayed(replayPump, 32L)
        return accepted
    }

    fun resetSelectionReports() {
        probe = null
        reportsSelection = false
        contradicted = false
        contradictions = 0
    }

    /** True while an edit sent to the editor has yet to be seen in a read. */
    val hasPendingEdit get() = pending != null

    /** Confirms an edit the editor applied after the key returned, before the panel reads [hasUndo]. */
    fun settlePending() {
        pendingChange?.let { settle(it.target) }
        pending?.let { settle(it.target) }
    }

    fun trackLocal(target: InputConnection, operation: WindowEdit, action: () -> Boolean): Boolean? {
        if (contradicted || pending != null || pendingChange != null || replay != null || compositionBefore != null ||
            batchDepth > 0 || expectations.size >= MAX_EXPECTATIONS) return null
        val (beforeReach, afterReach) = localReach(operation) ?: return null
        val before = readLocal(target, beforeReach, afterReach) ?: return null
        expectations.lastOrNull()?.let { tail ->
            val landed = landed(before, tail)
            if (landed == false) {
                val stale = !before.absolute && (expectations.size > 1 || before.low to before.high != tail.previous)
                clear()
                if (stale) return null
            } else if (!before.absolute) return null
        }
        val predicted = predict(before, operation) ?: return null
        val previous = before.low to before.high
        val selection = predicted.frame.low to predicted.frame.high
        val removed = before.slice(predicted.start, predicted.beforeEnd)
        val inserted = predicted.frame.slice(predicted.start, predicted.afterEnd)
        val changed = removed.toString() != inserted.toString()
        if (changed && selection == previous) return null
        if (!reportsSelection) {
            probe = selection.takeIf { it != previous }
            return null
        }
        val accepted = try { action() } catch (error: RuntimeException) { clear(); throw error }
        if (!accepted || !changed && selection == previous) return accepted
        val entry = if (!changed) null else {
            val right = before.slice(predicted.beforeEnd, minOf(before.limit, predicted.beforeEnd + GUARD)).toString()
            record(Change(predicted.start, removed, inserted,
                before.slice(maxOf(before.offset, predicted.start - GUARD), predicted.start).toString(), right,
                before.start, before.end, right.length < GUARD))
        }
        expectations.addLast(Expectation(selection, previous, entry))
        if (entry != null) onChange?.invoke()
        return true
    }

    private fun landed(frame: Frame, expected: Expectation): Boolean? {
        if (frame.absolute) return frame.low to frame.high == expected.selection
        val entry = expected.entry ?: return null
        if (frame.high - frame.low != expected.selection.second - expected.selection.first) return false
        val high = frame.high - frame.offset
        val before = entry.left + entry.inserted
        val count = minOf(high, before.length)
        if (count < minOf(before.length, GUARD) || frame.text.subSequence(high - count, high).toString() != before.takeLast(count)) return false
        val after = frame.text.subSequence(high, frame.text.length).toString()
        return if (entry.documentEnd) after == entry.right else after.startsWith(entry.right)
    }

    fun selectionUpdated(start: Int, end: Int) {
        // A report is the editor saying it has applied the edit, so an edit still waiting settles now.
        pending?.let { settle(it.target) }
        val selection = minOf(start, end) to maxOf(start, end)
        probe?.let { reportsSelection = reportsSelection || it == selection }
        probe = null
        val head = expectations.firstOrNull() ?: return
        val index = expectations.indexOfFirst { it.selection == selection }
        if (index >= 0) {
            repeat(index + 1) { expectations.removeFirst() }
            contradictions = 0
            return
        }
        if (selection == head.previous) return
        // A report can lag behind the edit — a drag that selected the range keeps reporting after the
        // deletion was sent. Stop trusting the reports, but leave the history for a read to judge.
        expectations.clear()
        contradicted = true
        if (++contradictions >= MAX_CONTRADICTIONS) reportsSelection = false
    }

    /** Confirms the newest entry against the editor once a report or a new connection cast doubt on it. */
    private fun verify(target: InputConnection) {
        if (!contradicted) return
        contradicted = false
        val entry = entries.lastOrNull() ?: return
        val frame = read(target, reach = maxOf(WINDOW, entry.inserted.length + GUARD))
        if (frame == null) return
        val from = entry.start - entry.left.length
        val through = entry.afterEnd + entry.right.length
        if (from < frame.offset || through > frame.limit) return
        if (!matches(frame, entry, false)) clear()
    }

    private fun localReach(operation: WindowEdit): Pair<Int, Int>? = when (operation) {
        is WindowEdit.Commit -> if (operation.composing || operation.cursor != 1 || operation.replacement != null ||
            operation.text.length > MAX_CHANGE || opaque(operation.text)) null else GUARD to GUARD
        is WindowEdit.Delete -> {
            val scale = if (operation.codePoints) 2 else 1
            if (operation.before !in 0..LOCAL_DELETE || operation.after !in 0..LOCAL_DELETE) null
            else operation.before * scale + GUARD to operation.after * scale + GUARD
        }
        is WindowEdit.Key -> {
            val event = operation.event
            if (event.action != KeyEvent.ACTION_DOWN || !event.hasNoModifiers()) null else when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> GUARD + GraphemeText.WINDOW to GUARD
                KeyEvent.KEYCODE_FORWARD_DEL -> GUARD to GUARD + GraphemeText.WINDOW
                else -> null
            }
        }
        else -> null
    }

    private fun opaque(text: CharSequence): Boolean =
        '\uFFFC' in text || text is Spanned && text.getSpans(0, text.length, ReplacementSpan::class.java).isNotEmpty()

    private fun readLocal(target: InputConnection, beforeReach: Int, afterReach: Int): Frame? = runCatching {
        val supplied = selectionProvider?.invoke()?.takeIf { it.first >= 0 && it.second >= 0 } ?: return@runCatching null
        val suppliedLow = minOf(supplied.first, supplied.second)
        val suppliedHigh = maxOf(supplied.first, supplied.second)
        if (suppliedHigh - suppliedLow > MAX_CHANGE) return@runCatching null
        val value = target.getSurroundingText(beforeReach, afterReach, InputConnection.GET_TEXT_WITH_STYLES) ?: return@runCatching null
        val text = value.text
        if (value.selectionStart !in 0..text.length || value.selectionEnd !in 0..text.length || opaque(text)) return@runCatching null
        val low = minOf(value.selectionStart, value.selectionEnd)
        val high = maxOf(value.selectionStart, value.selectionEnd)
        if (high - low > MAX_CHANGE) return@runCatching null
        val offset = if (value.offset >= 0) value.offset else {
            if (suppliedHigh - suppliedLow != high - low) return@runCatching null
            suppliedLow - low
        }
        if (offset < 0 || offset.toLong() + text.length > Int.MAX_VALUE) return@runCatching null
        val selection = if (value.offset < 0) supplied else offset + value.selectionStart to offset + value.selectionEnd
        val frozen = if (text is Spanned) {
            val copy = SpannableStringBuilder(text)
            BaseInputConnection.removeComposingSpans(copy)
            SpannedString(copy)
        } else text.toString()
        Frame(frozen, offset, selection.first, selection.second, value.offset >= 0)
    }.getOrNull()

    fun trackDeletion(target: InputConnection, start: Int, removed: CharSequence,
        selectionStart: Int, selectionEnd: Int, action: () -> Boolean): Boolean {
        expectations.clear()
        settle(target)
        if (pending != null || pendingChange != null || replay != null || compositionBefore != null || batchDepth > 0) clear()
        val context = capturedChange(target, start, removed, "", selectionStart, selectionEnd)
        if (context == null) { clear(); return action() }
        val accepted = try { action() } catch (error: RuntimeException) { clear(); throw error }
        if (accepted) {
            pendingChange = PendingChange(target, context)
            settle(target)
            if (pendingChange != null) handler.postDelayed(replayPump, 32L)
        }
        onChange?.invoke()
        return accepted
    }

    private fun capturedChange(target: InputConnection, start: Int, removed: CharSequence, inserted: CharSequence,
        selectionStart: Int, selectionEnd: Int): Change? = runCatching {
        val left = target.getTextBeforeCursor(GUARD, InputConnection.GET_TEXT_WITH_STYLES) ?: return@runCatching null
        val right = target.getTextAfterCursor(GUARD, InputConnection.GET_TEXT_WITH_STYLES) ?: return@runCatching null
        if (start < 0 || start.toLong() + removed.length > Int.MAX_VALUE || start.toLong() + inserted.length > Int.MAX_VALUE ||
            left.length > start || left.length > GUARD || right.length > GUARD ||
            listOf(left, removed, inserted, right).any { text -> '\uFFFC' in text ||
                text is Spanned && text.getSpans(0, text.length, ReplacementSpan::class.java).isNotEmpty() }) return@runCatching null
        Change(start, retainText(removed), retainText(inserted),
            left.toString(), right.toString(), selectionStart, selectionEnd, right.length < GUARD)
    }.getOrNull()

    fun replace(target: InputConnection, start: Int, removed: CharSequence, inserted: CharSequence,
        selectionStart: Int, selectionEnd: Int): Boolean {
        expectations.clear()
        settle(target)
        if (pending != null || pendingChange != null || replay != null) return false
        if (compositionBefore != null || batchDepth > 0) clear()
        if (minOf(selectionStart, selectionEnd) != start || maxOf(selectionStart, selectionEnd).toLong() != start.toLong() + removed.length) return false
        if (removed.isEmpty() && inserted.isEmpty()) return true
        val entry = capturedChange(target, start, removed, inserted, selectionStart, selectionEnd) ?: return false
        return startEdit(target, entry)
    }

    fun insert(target: InputConnection, inserted: CharSequence): Boolean {
        expectations.clear()
        settle(target)
        if (pending != null || pendingChange != null || replay != null) return false
        if (compositionBefore != null || batchDepth > 0) clear()
        val before = read(target)
        if (before == null || before.start != before.end || inserted.isEmpty() ||
            before.start.toLong() + inserted.length > Int.MAX_VALUE || '\uFFFC' in inserted ||
            inserted is Spanned && inserted.getSpans(0, inserted.length, ReplacementSpan::class.java).isNotEmpty()) {
            return false
        }
        val text = retainText(inserted)
        val right = before.slice(before.end, minOf(before.limit, before.end + GUARD)).toString()
        val entry = Change(before.start, "", text,
            before.slice(maxOf(before.offset, before.start - GUARD), before.start).toString(), right,
            before.start, before.end, right.length < GUARD)
        return startEdit(target, entry)
    }

    private fun startEdit(target: InputConnection, entry: Change): Boolean {
        val caret = entry.afterEnd
        replay = Replay(target, entry.copy(removed = entry.inserted, inserted = entry.removed, selectionStart = caret, selectionEnd = caret),
            entry.selectionStart to entry.selectionEnd, phase = if (entry.removed.length > REPLAY_CHUNK) 4 else 0, insertion = entry)
        val completed = continueReplay()
        replay?.let {
            it.deferred = true
            handler.postDelayed(replayPump, 32L)
        }
        val accepted = completed || replay != null
        onChange?.invoke()
        return accepted
    }

    fun beginBatch(target: InputConnection) {
        if (batchDepth++ != 0) return
        expectations.clear()
        settle(target)
        batchBefore = compositionBefore ?: read(target)
        batchAfter = null
        batchFailed = false
    }

    fun endBatch(target: InputConnection) {
        if (batchDepth <= 0) return
        settle(target)
        if (batchDepth <= 0) return
        if (--batchDepth != 0) return
        val before = batchBefore
        val after = batchAfter
        if (!batchFailed && pending == null && compositionBefore == null && before != null && after != null) record(before, after)
        batchBefore = null
        batchAfter = null
        onChange?.invoke()
    }

    fun canUndo(target: InputConnection): Boolean {
        verify(target)
        settle(target)
        if (replay != null) continueReplay()
        if (replay == null) settleRecovery(target)
        return hasUndo
    }

    fun undo(target: InputConnection): Boolean {
        expectations.clear()
        verify(target)
        settle(target)
        if (pending != null || pendingChange != null || replay != null) return false
        val composition = compositionBefore
        if (composition != null) {
            val after = compositionAfter ?: return false
            record(composition, after)
            compositionBefore = null
            compositionAfter = null
        }
        if (!settleRecovery(target)) return false
        val entry = entries.lastOrNull() ?: return false
        val original = selectionProvider?.invoke()?.takeIf { it.first >= 0 && it.second >= 0 }
            ?: read(target)?.let { it.start to it.end }
        replay = Replay(target, entry, original, phase = if (entry.inserted.length > REPLAY_CHUNK) 4 else 0)
        val success = continueReplay()
        replay?.let {
            it.deferred = true
            handler.postDelayed(replayPump, 32L)
        }
        onChange?.invoke()
        return success
    }

    private fun continueReplay(): Boolean {
        if (settling) return false
        val current = replay ?: return false
        if (SystemClock.uptimeMillis() >= current.deadline) return failReplay()
        settling = true
        try {
            val entry = current.entry
            if (current.phase == 4) {
                if (current.verificationEnd == current.verifiedThrough) {
                    current.verificationEnd = minOf(entry.inserted.length, current.verifiedThrough + REPLAY_CHUNK)
                    val caret = entry.start + current.verificationEnd
                    if (!current.target.setSelection(caret, caret)) return failReplay()
                }
                val caret = entry.start + current.verificationEnd
                val frame = read(current.target, caret to caret, MAX_CHANGE) ?: return false
                if (!matchesInsertion(frame, entry, current.verifiedThrough, current.verificationEnd)) return failReplay()
                current.verifiedThrough = current.verificationEnd
                current.deadline = SystemClock.uptimeMillis() + 2_000L
                if (current.verifiedThrough < entry.inserted.length) return false
                current.phase = 0
            }
            if (current.phase == 0) {
                if (!current.target.finishComposingText() || !current.target.setSelection(entry.start, entry.afterEnd)) return failReplay()
                current.phase = 1
            }
            if (current.phase == 1) {
                if (entry.inserted.length > REPLAY_CHUNK) {
                    if (selectionProvider != null && selectionProvider?.invoke() != entry.start to entry.afterEnd) return false
                } else {
                    val selected = read(current.target, entry.start to entry.afterEnd, maxOf(WINDOW, entry.inserted.length + GUARD)) ?: return false
                    if (!matches(selected, entry, false)) return failReplay()
                }
                if (entry.removed.toString() == entry.inserted.toString()) {
                    current.sentThrough = entry.removed.length
                    current.confirmedThrough = current.sentThrough
                    current.phase = 3
                    if (!current.target.setSelection(entry.selectionStart, entry.selectionEnd)) return failReplay()
                } else {
                    current.phase = 2
                    if (!sendReplayChunk(current)) return failReplay()
                }
            }
            if (current.phase == 2) {
                val caret = entry.start + current.sentThrough
                val restored = read(current.target, caret to caret, maxOf(WINDOW, minOf(current.sentThrough, REPLAY_CHUNK) + GUARD,
                    if (entry.removed.length <= REPLAY_CHUNK) entry.inserted.length + GUARD else 0)) ?: return false
                if (!matchesRestored(restored, current)) return if (entry.removed.length <= REPLAY_CHUNK &&
                    !matches(restored, entry, false)) failReplay() else false
                current.confirmedThrough = current.sentThrough
                current.deadline = SystemClock.uptimeMillis() + 2_000L
                if (current.sentThrough < entry.removed.length) {
                    if (!sendReplayChunk(current)) return failReplay()
                    return false
                }
                current.phase = 3
                if (!current.target.setSelection(entry.selectionStart, entry.selectionEnd)) return failReplay()
            }
            if (current.phase == 3) {
                if (kotlin.math.abs(entry.selectionEnd.toLong() - entry.selectionStart) > REPLAY_CHUNK) {
                    if (selectionProvider != null && selectionProvider?.invoke() != entry.selectionStart to entry.selectionEnd) return false
                } else {
                    val restored = read(current.target, entry.selectionStart to entry.selectionEnd,
                        maxOf(WINDOW, minOf(entry.removed.length, REPLAY_CHUNK) + GUARD)) ?: return false
                    if (entry.removed.length <= REPLAY_CHUNK && !matches(restored, entry, true)) return failReplay()
                }
                if (current.insertion != null) record(current.insertion) else entries.removeLast()
                if (recovery?.entry === entry) recovery = null
                replay = null
                handler.removeCallbacks(replayPump)
                onChange?.invoke()
                if (current.deferred) {
                    if (current.insertion != null) onInsertionCompleted?.invoke(true) else onUndoCompleted?.invoke(true)
                }
                return true
            }
        } catch (_: RuntimeException) { return failReplay() }
        finally { settling = false }
        return false
    }

    private fun sendReplayChunk(current: Replay): Boolean {
        val entry = current.entry
        val from = current.sentThrough
        var through = minOf(entry.removed.length, from + REPLAY_CHUNK)
        if (through < entry.removed.length && through > from && Character.isHighSurrogate(entry.removed[through - 1]) &&
            Character.isLowSurrogate(entry.removed[through])) through--
        current.sentThrough = through
        return current.target.commitText(entry.removed.subSequence(from, through), 1).also {
            if (it) {
                current.writeDispatched = true
                if (recovery?.entry === entry) recovery = null
            }
        }
    }

    private fun matchesRestored(frame: Frame, current: Replay): Boolean {
        val entry = current.entry
        val restored = current.sentThrough
        val from = maxOf(0, restored - REPLAY_CHUNK)
        val left = if (from == 0) entry.left else ""
        val start = entry.start + from - left.length
        val through = entry.start + restored + entry.right.length
        return start >= frame.offset && through <= frame.limit && (!entry.documentEnd || through == frame.limit) &&
            frame.slice(start, through).toString() == left + entry.removed.subSequence(from, restored) + entry.right
    }

    private fun matchesInsertion(frame: Frame, entry: Change, from: Int, through: Int): Boolean {
        val left = if (from == 0) entry.left else ""
        val right = if (through == entry.inserted.length) entry.right else ""
        val start = entry.start + from - left.length
        val end = entry.start + through + right.length
        return start >= frame.offset && end <= frame.limit &&
            (!entry.documentEnd || through != entry.inserted.length || end == frame.limit) &&
            frame.slice(start, end).toString() == left + entry.inserted.subSequence(from, through) + right
    }

    private fun matches(frame: Frame, entry: Change, restored: Boolean): Boolean {
        val text = if (restored) entry.removed else entry.inserted
        val from = entry.start - entry.left.length
        val through = entry.start + text.length + entry.right.length
        return from >= frame.offset && through <= frame.limit && (!entry.documentEnd || through == frame.limit) &&
            frame.slice(from, through).toString() == entry.left + text + entry.right
    }

    private fun settleRecovery(target: InputConnection): Boolean {
        val retained = recovery ?: return true
        if (entries.lastOrNull() !== retained.entry) return true
        if (retained.failed.target !== target) return false
        val selection = selectionProvider?.invoke()
        var observed = read(target, reach = MAX_CHANGE)
        if (selectionProvider?.invoke() != selection) observed = read(target, reach = MAX_CHANGE)
        val frame = observed ?: return false
        val failed = retained.failed
        fun atPrefix(through: Int) = frame.start == failed.entry.start + through && frame.end == frame.start
        val actual = when {
            atPrefix(failed.sentThrough) && matchesRestored(frame, failed) -> failed.entry.removed.subSequence(0, failed.sentThrough)
            failed.confirmedThrough != null && atPrefix(failed.confirmedThrough!!) &&
                matchesRestored(frame, failed.copy(sentThrough = failed.confirmedThrough!!)) ->
                failed.entry.removed.subSequence(0, failed.confirmedThrough!!)
            matches(frame, failed.entry, false) -> failed.entry.inserted
            else -> return false
        }
        if (actual.toString() == retained.entry.inserted.toString()) return true
        val entry = retained.entry.copy(inserted = retainText(actual))
        entries.removeLast()
        entries.addLast(entry)
        retained.entry = entry
        return true
    }

    private fun failReplay(): Boolean {
        val current = replay
        if (current != null && (current.phase < 2 || current.phase == 4)) current.original?.let { current.target.setSelection(it.first, it.second) }
        if (current != null && (current.writeDispatched || current.confirmedThrough != null)) {
            val original = current.insertion ?: current.entry
            val actual = current.confirmedThrough?.let { current.entry.removed.subSequence(0, it) } ?: current.entry.inserted
            val entry = original.copy(inserted = actual)
            if (current.insertion == null && entries.lastOrNull() === current.entry) entries.removeLast()
            val retained = record(entry, keepUnchanged = true)!!
            recovery = Recovery(current, retained)
            replay = null
            handler.removeCallbacks(replayPump)
            settleRecovery(current.target)
            onChange?.invoke()
        } else if (current != null && recovery?.entry === current.entry) {
            replay = null
            handler.removeCallbacks(replayPump)
            onChange?.invoke()
        } else clear()
        if (current?.deferred == true) {
            if (current.insertion != null) onInsertionCompleted?.invoke(false) else onUndoCompleted?.invoke(false)
        }
        return false
    }

    companion object {
        private const val WINDOW = 4096
        private const val GUARD = 128
        private const val MAX_CHANGE = 65_536
        private const val MAX_READ = 65_536
        private const val REPLAY_CHUNK = MAX_READ / 2 - GUARD
        private const val MAX_RETAINED = 1_048_576
        private const val LOCAL_DELETE = WINDOW
        private const val MAX_EXPECTATIONS = 50
        private const val MAX_CONTRADICTIONS = 3
        val MAX_INLINE_INSERTION = minOf(REPLAY_CHUNK, LargeCommit.CHUNK)
    }
}
