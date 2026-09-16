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
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

internal class NativeEditorUndoHistory {
    private data class Entry(
        val before: EditorTextSnapshot,
        var after: EditorTextSnapshot,
        val expected: String?,
        val actions: List<WindowEdit>?,
        val beforePlaceholders: List<Int>,
        var afterPlaceholders: List<Int>,
        val expectedPlaceholders: List<Int>,
        val originalBefore: EditorTextSnapshot? = null,
        val nativeOnly: Boolean = false,
        val capturedSelection: String? = null,
        val intermediates: List<EditorTextSnapshot> = emptyList(),
        val selectedAll: Boolean = false,
    ) {
        val size get() = before.text.length + after.text.length + (originalBefore?.text?.length ?: 0) + (capturedSelection?.length ?: 0) + intermediates.sumOf { it.text.length } +
            2 * (beforePlaceholders.size + afterPlaceholders.size + expectedPlaceholders.size) + actions.orEmpty().sumOf {
            if (it is WindowEdit.Commit) it.text.length + (it.replacement?.removed?.length ?: 0) else 0
        }
    }
    private data class Pending(
        val before: EditorTextSnapshot,
        val expected: String,
        val actions: List<WindowEdit>?,
        val replacing: Entry?,
        val deadline: Long,
        val nativeOnly: Boolean = false,
        val capturedSelection: String? = null,
    )
    private data class LocalEdit(val start: Int, val end: Int, val text: String)
    private data class Expansion(val before: EditorTextSnapshot, val expected: String, val actions: List<WindowEdit>)
    private data class Navigation(val entry: Entry, var after: EditorTextSnapshot, var awaiting: Boolean, val deadline: Long,
        var verified: Boolean)
    private class SelectionReplacement(val target: InputConnection, val before: EditorTextSnapshot, val text: String,
        val insert: () -> Boolean) {
        var deadline = SystemClock.uptimeMillis() + WAIT_MS
        var deleted: EditorTextSnapshot? = null
        var entry: Entry? = null
        var inserting = false
        var accepted = true
        var deferred = false
    }
    private enum class Phase { SELECT, CONFIRM, RESTORE_SELECTION, NATIVE, REDO, REPLAY }
    private class Undo(val target: InputConnection, val before: EditorTextSnapshot, val entry: Entry, native: Boolean,
        val nativeLimit: Int, val singleNative: Boolean = false) {
        var phase = if (native) Phase.NATIVE else Phase.SELECT
        var deadline = SystemClock.uptimeMillis() + WAIT_MS
        var deferred = false
        var selectionSent = false
        var completed: Boolean? = null
        var restoredBody: EditorTextSnapshot? = null
        var restoredSelection: EditorTextSnapshot? = null
        var selectionObservedAt: Long? = null
        var selectionWrites = 0
        var dispatchedBefore: EditorTextSnapshot? = null
        var dispatchedPlaceholders: List<Int> = emptyList()
        var dispatchedActions: List<WindowEdit>? = null
        val nativeSteps = ArrayList<EditorTextSnapshot>()
        var redoIndex = -1
        var redoBefore: EditorTextSnapshot? = null
        var replay: ArrayDeque<Entry>? = null
        var replaySent = false
        var replayWrites = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private val entries = ArrayDeque<Entry>()
    private var undoableEntries = 0
    private var retainedCharacters = 0
    private val nativeTrail = ArrayList<EditorTextSnapshot>()
    private var nativeTrailCharacters = 0
    private var localUndosSinceNative = 0
    private var target: InputConnection? = null
    private var pending: Pending? = null
    private var navigation: Navigation? = null
    private var selectedAll: EditorTextSnapshot? = null
    private var selectionReplacement: SelectionReplacement? = null
    private var settlingReplacement = false
    private var undoing: Undo? = null
    private var settlingUndo = false
    private var compositionBefore: EditorTextSnapshot? = null
    private var compositionEntry: Entry? = null
    private var compositionCommit: WindowEdit.Commit? = null
    private var compositionExpected: String? = null
    private var batchDepth = 0
    private var trackingDepth = 0
    private var batchBefore: EditorTextSnapshot? = null
    private var batchExpected: String? = null
    private var batchActions: MutableList<WindowEdit>? = null
    private var batchReplacing: Entry? = null
    private var batchChanged = false
    private var batchSafe = true
    private var batchFinishesComposition = false
    var onChange: (() -> Unit)? = null
    var onUndoCompleted: ((Boolean) -> Unit)? = null
    var onInsertionCompleted: ((Boolean) -> Unit)? = null
    val hasPendingUndo: Boolean get() = undoing != null
    val hasPendingInsertion: Boolean get() = selectionReplacement != null
    val hasUndo: Boolean get() = pending == null && selectionReplacement == null && undoing == null && undoableEntries > 0
    val hasDeletion: Boolean get() = hasUndo && entries.last().before.text.length > entries.last().after.text.length

    private var pollDelay = POLL_MS
    private var unchangedPolls = 0
    private var polled: EditorTextSnapshot? = null

    private val pump = object : Runnable {
        override fun run() {
            val currentTarget = target ?: return
            val current = observe(currentTarget)
            if (pending == null && selectionReplacement == null && undoing == null) return
            val previous = polled
            polled = current
            if (!confirming()) pollDelay = minOf(maxOf(pollDelay, CONFIRM_POLL_MS) * 2, MAX_CONFIRM_POLL_MS)
            else if (current == null || previous == null || !sameSelection(previous, current)) {
                unchangedPolls = 0
                pollDelay = POLL_MS
            } else if (++unchangedPolls >= IDLE_POLLS) pollDelay = minOf(pollDelay * 2, MAX_POLL_MS)
            handler.postDelayed(this, nextPoll())
        }
    }

    private fun confirming(): Boolean = undoing != null || selectionReplacement != null ||
        pending?.let { it.nativeOnly || largeInsertion(it.actions) } == true

    private fun nextPoll(): Long {
        val now = SystemClock.uptimeMillis()
        val operation = undoing
        val deadline = operation?.deadline ?: selectionReplacement?.deadline ?: pending?.deadline ?: return pollDelay
        var delay = if (confirming()) pollDelay else maxOf(pollDelay, CONFIRM_POLL_MS)
        return minOf(delay, maxOf(1L, deadline - now))
    }

    fun clear() {
        handler.removeCallbacks(pump)
        discard()
        undoing = null
        target = null
        batchDepth = 0
        batchBefore = null
        batchExpected = null
        batchActions = null
        batchReplacing = null
        batchChanged = false
        batchSafe = true
        batchFinishesComposition = false
    }

    private fun bind(connection: InputConnection) {
        if (target !== connection) {
            clear()
            target = connection
        }
    }

    private fun clearComposition() {
        compositionBefore = null
        compositionEntry = null
        compositionCommit = null
        compositionExpected = null
    }

    private fun discard() {
        entries.clear()
        undoableEntries = 0
        retainedCharacters = 0
        clearNativeTrail()
        pending = null
        navigation = null
        selectedAll = null
        selectionReplacement = null
        clearComposition()
        onChange?.invoke()
    }

    private fun clearNativeTrail() {
        nativeTrail.clear()
        nativeTrailCharacters = 0
        localUndosSinceNative = 0
    }

    private fun trimNativeTrail() {
        if (entries.none { requiresNative(it) }) clearNativeTrail()
        while (entries.isNotEmpty() && (retainedCharacters + nativeTrailCharacters > MAX_RETAINED ||
            localUndosSinceNative > MAX_ENTRIES)) {
            removeFirst()
            if (entries.none { requiresNative(it) }) clearNativeTrail()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(pump)
        pollDelay = POLL_MS
        unchangedPolls = 0
        polled = null
        handler.postDelayed(pump, nextPoll())
        onChange?.invoke()
    }

    fun selectionUpdated() {
        val connection = target ?: return
        if (pending == null || undoing != null || selectionReplacement != null || trackingDepth > 0 || batchDepth > 0) return
        observe(connection)
        if (pending == null && undoing == null && selectionReplacement == null) handler.removeCallbacks(pump)
    }

    private fun snapshot(connection: InputConnection): EditorTextSnapshot? {
        repeat(SNAPSHOT_READS) {
            when (val read = read(connection)) {
                is Read.Consistent -> return read.snapshot
                Read.Unavailable -> return null
                Read.Changed -> Unit
            }
        }
        return null
    }

    private sealed interface Read {
        data class Consistent(val snapshot: EditorTextSnapshot) : Read
        data object Changed : Read
        data object Unavailable : Read
    }

    private fun read(connection: InputConnection): Read = runCatching {
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply {
            hintMaxChars = MAX_TEXT + 1
            hintMaxLines = MAX_TEXT + 1
        }, 0)
        val candidate = if (extracted != null) {
            if (extracted.startOffset != 0 || extracted.partialStartOffset >= 0) return@runCatching Read.Unavailable
            EditorTextSnapshot(extracted.text?.toString() ?: return@runCatching Read.Unavailable, extracted.selectionStart, extracted.selectionEnd)
        } else {
            val around = connection.getSurroundingText(MAX_TEXT + 1, MAX_TEXT + 1, 0) ?: return@runCatching Read.Unavailable
            if (around.offset != 0) return@runCatching Read.Unavailable
            EditorTextSnapshot(around.text.toString(), around.selectionStart, around.selectionEnd)
        }
        if (candidate.text.length > MAX_TEXT || candidate.selectionStart !in 0..candidate.text.length ||
            candidate.selectionEnd !in 0..candidate.text.length) return@runCatching Read.Unavailable
        val start = minOf(candidate.selectionStart, candidate.selectionEnd)
        val end = maxOf(candidate.selectionStart, candidate.selectionEnd)
        val before = connection.getTextBeforeCursor(CONSISTENCY_WINDOW, 0)?.toString() ?: return@runCatching Read.Unavailable
        val after = connection.getTextAfterCursor(CONSISTENCY_WINDOW, 0)?.toString() ?: return@runCatching Read.Unavailable
        if (before != candidate.text.subSequence(maxOf(0, start - CONSISTENCY_WINDOW), start).toString() ||
            after != candidate.text.subSequence(end, minOf(candidate.text.length, end + CONSISTENCY_WINDOW)).toString()) return@runCatching Read.Changed
        Read.Consistent(candidate)
    }.getOrDefault(Read.Unavailable)

    private fun standalonePlaceholders(text: CharSequence): List<Int> = if (!text.endsWith('\n')) emptyList()
        else text.indices.filter { text[it] == '\u200b' && (it == 0 || text[it - 1] == '\n') &&
            (it + 1 == text.length || text[it + 1] == '\n') }

    private fun placeholders(snapshot: EditorTextSnapshot): List<Int> {
        val replay = undoing
        val recorded = entries.asReversed().firstNotNullOfOrNull { entry ->
            when (snapshot.text.toString()) {
                entry.after.text.toString() -> entry.afterPlaceholders
                entry.before.text.toString() -> entry.beforePlaceholders
                entry.expected -> entry.expectedPlaceholders
                else -> null
            }
        }
        val known = recorded ?: if (replay?.phase == Phase.CONFIRM && replay.dispatchedBefore != null) {
            mapPlaceholders(replay.dispatchedBefore!!, snapshot.text.toString(), replay.dispatchedPlaceholders, replay.dispatchedActions)
        } else emptyList()
        return (known + standalonePlaceholders(snapshot.text)).distinct().sorted()
    }

    private fun mapPlaceholders(before: EditorTextSnapshot, expected: String, known: List<Int>,
        actions: List<WindowEdit>? = null): List<Int> {
        val old = before.text.toString()
        val commit = actions?.singleOrNull() as? WindowEdit.Commit
        val selectedStart = commit?.replacement?.start ?: minOf(before.selectionStart, before.selectionEnd)
        val selectedEnd = commit?.replacement?.end ?: maxOf(before.selectionStart, before.selectionEnd)
        if (selectedStart !in 0..old.length || selectedEnd !in selectedStart..old.length) return emptyList()
        if (commit != null && old.replaceRange(selectedStart, selectedEnd, commit.text) == expected) {
            return known.mapNotNull { index -> when {
                index < selectedStart -> index
                index >= selectedEnd -> index + commit.text.length - (selectedEnd - selectedStart)
                else -> null
            } }
        }
        var start = 0
        while (start < old.length && start < expected.length && old[start] == expected[start]) start++
        var oldEnd = old.length
        var newEnd = expected.length
        while (oldEnd > start && newEnd > start && old[oldEnd - 1] == expected[newEnd - 1]) { oldEnd--; newEnd-- }
        return known.mapNotNull { index -> when {
            index < start -> index
            index >= oldEnd -> index + newEnd - oldEnd
            else -> null
        } }.filter { it in expected.indices && expected[it] == '\u200b' }
    }

    private fun normalized(snapshot: EditorTextSnapshot, known: List<Int>): EditorTextSnapshot {
        val removed = known.filter { index -> index in snapshot.text.indices && snapshot.text[index] == '\u200b' &&
            !((index == 0 || snapshot.text[index - 1] == '\n') &&
                (index + 1 == snapshot.text.length || snapshot.text[index + 1] == '\n')) }
        if (removed.isEmpty()) return snapshot
        val text = StringBuilder(snapshot.text)
        for (index in removed.asReversed()) text.deleteCharAt(index)
        return EditorTextSnapshot(text.toString(), snapshot.selectionStart - removed.count { it < snapshot.selectionStart },
            snapshot.selectionEnd - removed.count { it < snapshot.selectionEnd })
    }

    private fun matchingSnapshot(reference: EditorTextSnapshot, current: EditorTextSnapshot?): EditorTextSnapshot? {
        if (current == null) return null
        if (reference.sameText(current)) return reference
        val wanted = normalized(reference, placeholders(reference))
        val actual = normalized(current, placeholders(current))
        if (!wanted.sameText(actual)) return null
        val removed = placeholders(current).filter { index -> index in current.text.indices && current.text[index] == '\u200b' &&
            !((index == 0 || current.text[index - 1] == '\n') &&
                (index + 1 == current.text.length || current.text[index + 1] == '\n')) }
        fun offset(position: Int): Int {
            var result = position
            for (index in removed) if (index < result) result++
            return result
        }
        return EditorTextSnapshot(current.text, offset(wanted.selectionStart), offset(wanted.selectionEnd))
    }

    private fun matches(before: EditorTextSnapshot, current: EditorTextSnapshot, expected: String,
        known: List<Int> = placeholders(before), actions: List<WindowEdit>? = null): Boolean {
        val actual = current.text.toString()
        if (sameRenderedText(actual, expected)) return true
        val old = before.text.toString()
        if (!old.endsWith('\n')) return false
        val rewritten = StringBuilder(expected)
        for (mapped in mapPlaceholders(before, expected, known, actions).asReversed()) {
            if (mapped in rewritten.indices && rewritten[mapped] == '\u200b') rewritten.deleteCharAt(mapped)
        }
        val plain = rewritten.toString()
        if (!plain.endsWith('\n')) return false
        val lines = plain.dropLast(1).split('\n')
        return sameRenderedText(actual, lines.joinToString("\n") { it.ifEmpty { "\u200b" } } + "\n")
    }

    private fun sameRenderedText(actual: String, expected: String): Boolean {
        return renderings(expected).any { it == actual }
    }

    private fun rendered(expected: String, width: Int): String {
        if (width == 0 || '\t' !in expected) return expected
        val expanded = StringBuilder()
        var column = 0
        for (character in expected) {
            when (character) {
                '\n' -> { expanded.append(character); column = 0 }
                '\t' -> {
                    val spaces = width - column % width
                    repeat(spaces) { expanded.append(' ') }
                    column += spaces
                }
                else -> { expanded.append(character); column++ }
            }
        }
        return expanded.toString()
    }

    private fun renderings(expected: String): List<String> =
        if ('\t' !in expected) listOf(expected) else (0..16).map { rendered(expected, it) }.distinct()

    private fun expansion(before: EditorTextSnapshot, after: EditorTextSnapshot, expected: String,
        actions: List<WindowEdit>?): Expansion? {
        if (actions == null || compositionBefore != null || expected.isEmpty()) return null
        val actual = after.text.toString()
        val positions = linkedSetOf<Pair<Int, Int>>()
        for (rendered in renderings(expected)) {
            var start = actual.indexOf(rendered)
            while (start >= 0) {
                val end = start + rendered.length
                if ((start == 0 || actual[start - 1] == '\n') && (end == actual.length || rendered.endsWith('\n')))
                    positions.add(start to end)
                start = actual.indexOf(rendered, start + 1)
            }
        }
        val (start, end) = positions.singleOrNull() ?: return null
        if (start == 0 && end == actual.length) return null
        val prefix = actual.substring(0, start)
        val suffix = actual.substring(end)
        val shifted = actions.map { action ->
            if (action is WindowEdit.Commit && action.replacement != null) action.copy(replacement = action.replacement.copy(
                start = action.replacement.start + start, end = action.replacement.end + start)) else action
        }
        return Expansion(EditorTextSnapshot(prefix + before.text + suffix, before.selectionStart + start,
            before.selectionEnd + start), prefix + expected + suffix, shifted)
    }

    private fun viewportShift(before: EditorTextSnapshot, after: EditorTextSnapshot): Boolean {
        val old = projected(before).text.toString()
        val current = projected(after).text.toString()
        if (old == current) return true
        if (!old.endsWith('\n') || !current.endsWith('\n')) return false
        val shorter = if (old.length <= current.length) old else current
        val longer = if (old.length <= current.length) current else old
        if (shorter.length < MIN_WINDOW) return false
        val start = longer.indexOf(shorter)
        return start >= 0 && (start == 0 || longer[start - 1] == '\n') && longer.indexOf(shorter, start + 1) < 0
    }

    private fun insertionWindow(before: EditorTextSnapshot, current: EditorTextSnapshot, actions: List<WindowEdit>?): Boolean {
        val commit = actions?.singleOrNull() as? WindowEdit.Commit ?: return false
        if (commit.cursor != 1 || commit.replacement != null || commit.text.isEmpty()) return false
        val old = projected(before)
        val first = minOf(old.selectionStart, old.selectionEnd)
        val last = maxOf(old.selectionStart, old.selectionEnd)
        val prefix = old.text.substring(0, first)
        val suffix = old.text.substring(last)
        val visible = projected(current)
        val actual = visible.text.toString()
        if (visible.selectionStart != visible.selectionEnd || !actual.endsWith('\n')) return false
        val expected = prefix + commit.text + suffix
        return (0..16).any { width ->
            val rendered = rendered(expected, width)
            val caret = rendered(prefix + commit.text, width).length
            val start = rendered.length - actual.length
            val tailLength = rendered.length - caret
            start >= 0 && rendered.endsWith(actual) && rendered.indexOf(actual) == start &&
                (start == 0 || rendered[start - 1] == '\n') &&
                visible.selectionEnd == caret - start && tailLength >= 0 &&
                actual.length - tailLength >= minOf(commit.text.length, MIN_WINDOW)
        }
    }

    private fun largeInsertion(actions: List<WindowEdit>?): Boolean =
        ((actions?.singleOrNull() as? WindowEdit.Commit)?.text?.length ?: 0) > 16_384

    private fun partialInsertion(before: EditorTextSnapshot, current: EditorTextSnapshot, actions: List<WindowEdit>?): Boolean {
        val commit = actions?.singleOrNull() as? WindowEdit.Commit ?: return false
        if (commit.cursor != 1 || commit.replacement != null) return false
        val first = minOf(before.selectionStart, before.selectionEnd)
        val last = maxOf(before.selectionStart, before.selectionEnd)
        val prefix = before.text.substring(0, first)
        val suffix = before.text.substring(last)
        val actual = current.text.toString()
        if (!actual.startsWith(prefix) || !actual.endsWith(suffix) || actual.length <= prefix.length + suffix.length) return false
        val inserted = actual.substring(prefix.length, actual.length - suffix.length)
        return inserted.length < commit.text.length && commit.text.startsWith(inserted)
    }

    private fun removeFirst() {
        if (entries.size == undoableEntries) undoableEntries--
        retainedCharacters -= entries.removeFirst().size
    }

    private fun removeLast() {
        retainedCharacters -= entries.removeLast().size
        if (undoableEntries > 0) undoableEntries--
    }

    private fun reconcile(current: EditorTextSnapshot?): Boolean {
        if (current == null) { discard(); return false }
        val previous = entries.lastOrNull() ?: return true
        navigation?.takeIf { it.entry === previous }?.let { moved ->
            if (moved.after.sameText(current)) {
                if (moved.after.selectionStart != current.selectionStart || moved.after.selectionEnd != current.selectionEnd ||
                    SystemClock.uptimeMillis() >= moved.deadline) moved.awaiting = false
                return true
            }
            if (moved.awaiting && SystemClock.uptimeMillis() < moved.deadline) {
                moved.verified = matchesAfter(previous, current) || viewportShift(previous.after, current) ||
                    moved.verified && viewportShift(moved.after, current)
                moved.after = current
                moved.awaiting = false
                return true
            }
            discard()
            return false
        }
        if (previous.after.sameText(current)) return true
        if (previous.intermediates.isNotEmpty() && matchesAfter(previous, current)) {
            val oldSize = previous.size
            previous.after = current
            previous.afterPlaceholders = placeholders(current)
            retainedCharacters += previous.size - oldSize
            return true
        }
        if (previous.expected?.let { matches(previous.before, current, it, previous.beforePlaceholders, previous.actions) } == true) {
            val oldSize = previous.size
            previous.afterPlaceholders = placeholders(current)
            previous.after = current
            retainedCharacters += previous.size - oldSize
            while (entries.size > 1 && retainedCharacters > MAX_RETAINED) removeFirst()
            return true
        }
        val expanded = previous.expected?.let { expansion(previous.before, current, it, previous.actions) }
        if (expanded != null) {
            record(expanded.before, current, expanded.expected, expanded.actions, previous,
                previous.originalBefore ?: previous.before, previous.nativeOnly)
            return true
        }
        discard()
        return false
    }

    private fun record(before: EditorTextSnapshot, after: EditorTextSnapshot, expected: String?, actions: List<WindowEdit>?, replacing: Entry?,
        originalBefore: EditorTextSnapshot? = null, nativeOnly: Boolean = false, capturedSelection: String? = replacing?.capturedSelection,
        intermediates: List<EditorTextSnapshot> = replacing?.intermediates.orEmpty()) {
        if (before.sameText(after)) return
        val beforePlaceholders = placeholders(before)
        val expectedPlaceholders = expected?.let { mapPlaceholders(before, it, beforePlaceholders, actions) }.orEmpty()
        val afterPlaceholders = standalonePlaceholders(after.text) + if (expected != null) {
            mapPlaceholders(EditorTextSnapshot(expected, 0, 0), after.text.toString(), expectedPlaceholders)
        } else mapPlaceholders(before, after.text.toString(), beforePlaceholders, actions)
        if (replacing != null && entries.lastOrNull() === replacing) removeLast()
        val entry = Entry(before, after, expected, actions, beforePlaceholders, afterPlaceholders.distinct().sorted(), expectedPlaceholders, originalBefore, nativeOnly, capturedSelection, intermediates,
            replacing?.selectedAll ?: (selectedAll?.let { sameSelection(it, before) } == true))
        navigation = null
        selectedAll = null
        if (entry.size > MAX_RETAINED) { discard(); return }
        while (entries.isNotEmpty() && (entries.size >= MAX_ENTRIES || retainedCharacters + entry.size > MAX_RETAINED)) removeFirst()
        entries.addLast(entry)
        undoableEntries = minOf(MAX_ENTRIES, undoableEntries + 1)
        retainedCharacters += entry.size
        trimNativeTrail()
        if (compositionBefore?.sameText(before) == true) compositionEntry = entry
        onChange?.invoke()
    }

    private fun frozen(operation: WindowEdit): WindowEdit? = when (operation) {
        is WindowEdit.Commit -> operation.copy(text = operation.text.toString(), composing = false,
            replacement = operation.replacement?.let { it.copy(removed = it.removed.toString()) })
        is WindowEdit.Key -> if (operation.event.keyCode in intArrayOf(KeyEvent.KEYCODE_PASTE, KeyEvent.KEYCODE_CUT)) null
            else WindowEdit.Key(KeyEvent(operation.event))
        is WindowEdit.Delete -> operation.copy()
        is WindowEdit.Context -> operation.takeIf { it.id == android.R.id.cut }
        WindowEdit.FinishComposition -> WindowEdit.FinishComposition
        WindowEdit.Unknown -> null
    }

    private fun contextCut(actions: List<WindowEdit>?): Boolean =
        (actions?.filter { it != WindowEdit.FinishComposition }?.singleOrNull() as? WindowEdit.Context)?.id == android.R.id.cut

    private fun structuralChange(before: EditorTextSnapshot, expected: String?, actions: List<WindowEdit>?): Boolean {
        if (expected == null) return false
        val action = actions?.filter { it != WindowEdit.FinishComposition }?.singleOrNull() ?: return false
        val start = minOf(before.selectionStart, before.selectionEnd)
        val end = maxOf(before.selectionStart, before.selectionEnd)
        return before.text.count { it == '\n' } != expected.count { it == '\n' } ||
            before.text.subSequence(start, end).contains('\n') || action is WindowEdit.Commit && action.text.contains('\n')
    }

    private fun nativePasteChanged(before: EditorTextSnapshot, after: EditorTextSnapshot, expected: String?,
        actions: List<WindowEdit>?): Boolean = expected != null &&
        (matches(before, after, expected, actions = actions) || before.text.endsWith('\n') && after.text.endsWith('\n') &&
            (structuralChange(before, expected, actions) || largeInsertion(actions) || expansion(before, after, expected, actions) != null))

    private fun finishEdit(connection: InputConnection, before: EditorTextSnapshot?, expected: String?, accepted: Boolean,
        actions: List<WindowEdit>?, replacing: Entry?, nativeOnly: Boolean = false, capturedSelection: String? = null) {
        if (!accepted || before == null) { discard(); return }
        val after = snapshot(connection)
        if (after != null && !before.sameText(after)) {
            if (nativeOnly) {
                if (nativePasteChanged(before, after, expected, actions)) record(before, after, null, actions, replacing, nativeOnly = true)
                else discard()
            }
            else if (contextCut(actions)) record(before, after, null, actions, replacing, capturedSelection = capturedSelection)
            else if (expected == null || matches(before, after, expected, actions = actions)) record(before, after, expected, actions, replacing)
            else if (insertionWindow(before, after, actions)) record(before, after, null, actions, replacing)
            else if (partialInsertion(before, after, actions) || largeInsertion(actions)) {
                pending = Pending(before, requireNotNull(expected), actions, replacing, SystemClock.uptimeMillis() + INSERT_WAIT_MS)
                schedule()
            }
            else if (expected?.let { expansion(before, after, it, actions) } != null) {
                val expanded = requireNotNull(expansion(before, after, expected, actions))
                record(expanded.before, after, expanded.expected, expanded.actions, replacing, before)
            }
            else if (structuralChange(before, expected, actions)) record(before, after, null, actions, replacing) else discard()
        } else if (expected != null && expected != before.text.toString()) {
            pending = Pending(before, expected, actions, replacing,
                SystemClock.uptimeMillis() + if (nativeOnly) INSERT_WAIT_MS else WAIT_MS, nativeOnly, capturedSelection)
            schedule()
        }
    }

    fun track(
        connection: InputConnection,
        composing: Boolean = false,
        finishesComposition: Boolean = false,
        expected: ((EditorTextSnapshot) -> String?)? = null,
        operation: WindowEdit = WindowEdit.Unknown,
        nativeOnly: Boolean = false,
        capturedSelection: String? = null,
        action: () -> Boolean,
    ): Boolean {
        bind(connection)
        if (undoing != null || selectionReplacement != null) return false
        val before = observe(connection)
        if (pending != null && !composing && !finishesComposition) discard()
        if (batchDepth == 0 && pending == null) reconcile(before)
        if (composing && compositionBefore == null) compositionBefore = before
        val composingEdit = compositionBefore != null && (composing || finishesComposition)
        val baseline = if (composingEdit) compositionBefore else before
        val saved = frozen(operation)
        if (composingEdit && saved is WindowEdit.Commit) compositionCommit = saved
        val prediction = baseline?.let { expected?.invoke(it) }
            ?: compositionExpected.takeIf { composingEdit && operation == WindowEdit.FinishComposition }
        if (composingEdit && prediction != null) compositionExpected = prediction
        val actions = if (composingEdit) compositionCommit?.let { listOf<WindowEdit>(it) }
            else saved?.let { listOf(it) }
        val replacing = compositionEntry.takeIf { composingEdit }
        val accepted = try { trackingDepth++; action() }
            catch (error: RuntimeException) { discard(); throw error }
            finally { trackingDepth-- }
        if (batchDepth > 0) {
            batchChanged = true
            batchSafe = batchSafe && accepted && before != null
            if (composingEdit) {
                batchBefore = baseline
                batchActions = actions?.toMutableList()
                batchReplacing = replacing
            } else if (saved == null) batchActions = null
            else batchActions?.add(saved)
            batchExpected = prediction
            batchFinishesComposition = batchFinishesComposition || finishesComposition
        } else {
            if (expected != null && !composingEdit && !nativeOnly && accepted && baseline != null && prediction != null &&
                prediction != baseline.text.toString() && !largeInsertion(actions) && !contextCut(actions)) {
                pending = Pending(baseline, prediction, actions, null, SystemClock.uptimeMillis() + WAIT_MS)
                schedule()
            } else if (expected != null || composingEdit || before?.sameText(snapshot(connection) ?: before) == false) {
                pending = null
                finishEdit(connection, baseline, prediction, accepted, actions, replacing, nativeOnly, capturedSelection)
            }
            if (finishesComposition) clearComposition()
        }
        return accepted
    }

    fun cut(connection: InputConnection, copiedText: CharSequence): Boolean {
        bind(connection)
        val before = snapshot(connection) ?: return false
        val visible = projected(before)
        val start = minOf(visible.selectionStart, visible.selectionEnd)
        val end = maxOf(visible.selectionStart, visible.selectionEnd)
        val removed = copiedText.toString()
        val text = visible.text.toString()
        if (start == end) return false
        val verified = '\n' !in removed && '\r' !in removed && sameRenderedText(text, text.replaceRange(start, end, removed))
        val replacement = WindowEdit.Replacement(minOf(before.selectionStart, before.selectionEnd),
            maxOf(before.selectionStart, before.selectionEnd), removed)
        val operation = if (verified) WindowEdit.Commit("", 1, replacement = replacement) else WindowEdit.Context(android.R.id.cut)
        var unchanged = false
        return track(connection, expected = { current ->
            unchanged = before.sameText(current) && before.selectionStart == current.selectionStart &&
                before.selectionEnd == current.selectionEnd
            if (unchanged) current.text.toString().removeRange(replacement.start, replacement.end) else null
        }, operation = operation, capturedSelection = removed.takeUnless { verified }) {
            unchanged && connection.performContextMenuAction(android.R.id.cut)
        }
    }

    fun paste(connection: InputConnection, copiedText: CharSequence): Boolean {
        bind(connection)
        if (undoing != null || selectionReplacement != null || batchDepth != 0) return false
        val before = observe(connection) ?: return false
        if (pending != null) return false
        val text = copiedText.toString()
        if (text.isEmpty()) return false
        var unchanged = false
        return track(connection, expected = { current ->
            unchanged = before.sameText(current) && before.selectionStart == current.selectionStart &&
                before.selectionEnd == current.selectionEnd
            if (unchanged) current.text.toString().replaceRange(minOf(current.selectionStart, current.selectionEnd),
                maxOf(current.selectionStart, current.selectionEnd), text) else null
        }, operation = WindowEdit.Commit(text, 1), nativeOnly = true) {
            unchanged && connection.performContextMenuAction(android.R.id.paste)
        }
    }

    fun replaceSelectedTab(connection: InputConnection, insert: () -> Boolean): Boolean? {
        bind(connection)
        if (undoing != null || selectionReplacement != null) return false
        val before = observe(connection) ?: return null
        if (pending != null) return false
        val visible = numberedWindow(before)?.visible ?: before
        if (batchDepth != 0 || compositionBefore != null || before.text.length < MIN_WINDOW ||
            before.selectionStart == before.selectionEnd ||
            minOf(visible.selectionStart, visible.selectionEnd) > 0 &&
                maxOf(visible.selectionStart, visible.selectionEnd) < visible.text.length - 1) return null
        val operation = SelectionReplacement(connection, before, "\t", insert)
        selectionReplacement = operation
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        operation.accepted = runCatching {
            connection.sendKeyEvent(event).also { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) }
        }.getOrDefault(false)
        settleReplacement(operation, snapshot(connection))
        if (selectionReplacement != null) { operation.deferred = true; schedule() }
        return operation.accepted
    }

    private fun settleReplacement(operation: SelectionReplacement, initial: EditorTextSnapshot?) {
        if (settlingReplacement || selectionReplacement !== operation) return
        settlingReplacement = true
        try {
            var current = initial
            if (!operation.inserting) {
                if (current == null || operation.before.sameText(current) || current.selectionStart != current.selectionEnd) {
                    if (!operation.accepted || SystemClock.uptimeMillis() >= operation.deadline) {
                        if (current != null && !operation.before.sameText(current)) record(operation.before, current, null,
                            listOf(WindowEdit.Key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))), null, nativeOnly = true)
                        selectionReplacement = null
                        operation.accepted = false
                        onChange?.invoke()
                        if (operation.deferred) onInsertionCompleted?.invoke(false)
                    }
                    return
                }
                operation.deleted = current
                record(operation.before, current, null, listOf(WindowEdit.Key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))),
                    null, nativeOnly = true)
                operation.entry = entries.lastOrNull()
                operation.inserting = true
                operation.deadline = SystemClock.uptimeMillis() + INSERT_WAIT_MS
                operation.accepted = runCatching { operation.insert() }.getOrDefault(false)
                current = snapshot(operation.target)
            }
            val deleted = operation.deleted ?: return
            val actions = listOf(WindowEdit.Commit(operation.text, 1))
            val expected = deleted.text.toString().replaceRange(deleted.selectionStart, deleted.selectionEnd, operation.text)
            val confirmed = current != null && (matches(deleted, current, expected, actions = actions) ||
                expansion(deleted, current, expected, actions) != null)
            if (current != null && !deleted.sameText(current) && (confirmed || !operation.accepted ||
                    SystemClock.uptimeMillis() >= operation.deadline)) {
                record(operation.before, current, null, actions, operation.entry,
                    nativeOnly = true, intermediates = listOf(deleted))
                operation.accepted = confirmed
                selectionReplacement = null
                clearComposition()
                onChange?.invoke()
                if (operation.deferred) onInsertionCompleted?.invoke(operation.accepted)
            } else if (!operation.accepted || SystemClock.uptimeMillis() >= operation.deadline) {
                selectionReplacement = null
                operation.accepted = false
                clearComposition()
                onChange?.invoke()
                if (operation.deferred) onInsertionCompleted?.invoke(false)
            }
        } finally { settlingReplacement = false }
    }

    fun navigate(connection: InputConnection, selectAll: Boolean = false, action: () -> Boolean): Boolean {
        bind(connection)
        if (undoing != null || selectionReplacement != null) return false
        if (entries.isEmpty() && pending == null && compositionBefore == null && batchDepth == 0)
            return action().also { if (it) selectedAll = if (selectAll) snapshot(connection) else null }
        val before = observe(connection)
        if (pending != null) return false
        val entry = entries.lastOrNull()
        val verifiedBefore = navigation?.verified != false
        val accepted = action()
        if (accepted) selectedAll = if (selectAll) snapshot(connection) else null
        if (accepted && entry != null && before != null && entries.lastOrNull() === entry) {
            val after = snapshot(connection) ?: before
            val verified = matchesAfter(entry, after) || viewportShift(entry.after, after) || verifiedBefore && viewportShift(before, after)
            navigation = Navigation(entry, after, before.sameText(after), SystemClock.uptimeMillis() + WAIT_MS, verified)
        }
        return accepted
    }

    fun beginBatch(connection: InputConnection) {
        bind(connection)
        if (batchDepth++ == 0) {
            val current = observe(connection)
            if (pending != null) discard()
            reconcile(current)
            batchBefore = compositionBefore ?: current
            batchExpected = null
            batchActions = ArrayList()
            batchReplacing = null
            batchChanged = false
            batchSafe = true
            batchFinishesComposition = false
        }
    }

    fun endBatch(connection: InputConnection) {
        if (target !== connection || batchDepth == 0 || --batchDepth != 0) return
        if (batchChanged) {
            var actions = batchActions?.toList()
            var expected = batchExpected
            val before = batchBefore
            if (before != null && actions != null && actions.size > 1 && actions.all {
                it is WindowEdit.Commit && it.cursor == 1 && it.replacement == null
            }) {
                val text = actions.joinToString("") { (it as WindowEdit.Commit).text }
                actions = listOf(WindowEdit.Commit(text, 1))
                expected = before.text.toString().replaceRange(minOf(before.selectionStart, before.selectionEnd),
                    maxOf(before.selectionStart, before.selectionEnd), text)
            }
            finishEdit(connection, before, expected, batchSafe, actions, batchReplacing)
        }
        if (batchFinishesComposition) clearComposition()
        batchBefore = null
        batchExpected = null
        batchActions = null
        batchReplacing = null
        batchChanged = false
        batchFinishesComposition = false
    }

    private fun sameSelection(before: EditorTextSnapshot, after: EditorTextSnapshot?): Boolean = after != null &&
        before.sameText(after) && before.selectionStart == after.selectionStart && before.selectionEnd == after.selectionEnd

    private fun observe(connection: InputConnection): EditorTextSnapshot? {
        val current = snapshot(connection)
        if (trackingDepth == 0 && pending == null && selectionReplacement == null && undoing == null &&
            selectedAll?.let { !sameSelection(it, current) } == true) selectedAll = null
        selectionReplacement?.let { operation ->
            settleReplacement(operation, current)
            return if (selectionReplacement == null) snapshot(connection) else current
        }
        val replay = undoing
        if (replay != null) {
            settleUndo(replay, current)
            return current
        }
        val edit = pending
        if (edit != null) {
            if (current != null && !edit.before.sameText(current)) {
                if (!edit.nativeOnly && partialInsertion(edit.before, current, edit.actions) && !insertionWindow(edit.before, current, edit.actions)) {
                    if (SystemClock.uptimeMillis() >= edit.deadline) discard()
                    return current
                }
                pending = null
                if (edit.nativeOnly) {
                    if (nativePasteChanged(edit.before, current, edit.expected, edit.actions))
                        record(edit.before, current, null, edit.actions, edit.replacing, nativeOnly = true)
                    else discard()
                }
                else if (contextCut(edit.actions)) record(edit.before, current, null, edit.actions, edit.replacing, capturedSelection = edit.capturedSelection)
                else if (matches(edit.before, current, edit.expected, actions = edit.actions)) record(edit.before, current, edit.expected, edit.actions, edit.replacing)
                else if (insertionWindow(edit.before, current, edit.actions)) record(edit.before, current, null, edit.actions, edit.replacing)
                else if (expansion(edit.before, current, edit.expected, edit.actions) != null) {
                    val expanded = requireNotNull(expansion(edit.before, current, edit.expected, edit.actions))
                    record(expanded.before, current, expanded.expected, expanded.actions, edit.replacing, edit.before)
                }
                else if (largeInsertion(edit.actions)) {
                    if (SystemClock.uptimeMillis() < edit.deadline) pending = edit else discard()
                }
                else if (structuralChange(edit.before, edit.expected, edit.actions)) record(edit.before, current, null, edit.actions, edit.replacing)
                else discard()
            } else if (SystemClock.uptimeMillis() >= edit.deadline) discard()
        } else if (batchDepth == 0) reconcile(current)
        return current
    }

    private fun projected(snapshot: EditorTextSnapshot): EditorTextSnapshot {
        val removed = placeholders(snapshot)
        if (removed.isEmpty()) return snapshot
        val text = StringBuilder(snapshot.text)
        for (index in removed.asReversed()) text.deleteCharAt(index)
        return EditorTextSnapshot(text.toString(), snapshot.selectionStart - removed.count { it < snapshot.selectionStart },
            snapshot.selectionEnd - removed.count { it < snapshot.selectionEnd })
    }

    private fun rawOffset(snapshot: EditorTextSnapshot, position: Int): Int {
        var result = position
        for (index in placeholders(snapshot)) if (index < result) result++
        return result
    }

    private fun trustedReplacement(before: EditorTextSnapshot, start: Int, end: Int): String? {
        val old = projected(before).text.toString()
        val value = old.substring(start, end)
        if (value.none { it == '\n' || it == '\r' || it == '\t' || it == ' ' }) return value
        for (entry in entries.asReversed()) {
            if (entry.nativeOnly) continue
            val commit = entry.actions?.singleOrNull() as? WindowEdit.Commit ?: continue
            if (!matchesAfter(entry, before)) continue
            val source = projected(entry.before)
            if (source.selectionStart != source.selectionEnd) continue
            val prefix = source.text.substring(0, source.selectionStart)
            val suffix = source.text.substring(source.selectionEnd)
            if (old.startsWith(prefix) && old.endsWith(suffix) && sameRenderedText(old, prefix + commit.text + suffix)) {
                val through = old.length - suffix.length
                if (start == prefix.length && end == through) return commit.text.toString()
                if (start >= prefix.length && end <= through && old.substring(prefix.length, through) == commit.text.toString())
                    return commit.text.substring(start - prefix.length, end - prefix.length)
            }
        }
        return null
    }

    private fun localEdit(entry: Entry, current: EditorTextSnapshot): LocalEdit? {
        if (entry.before.selectionStart != entry.before.selectionEnd) return null
        if (entry.nativeOnly || entry.capturedSelection != null && contextCut(entry.actions) || navigation?.let { it.entry === entry && !it.verified } == true || !matchesAfter(entry, current)) return null
        val operation = entry.actions?.filter { it != WindowEdit.FinishComposition }?.singleOrNull() ?: return null
        val before = projected(entry.before)
        val old = before.text.toString()
        var start = minOf(before.selectionStart, before.selectionEnd)
        var end = maxOf(before.selectionStart, before.selectionEnd)
        val replacement = (operation as? WindowEdit.Commit)?.replacement
        if (replacement != null) {
            if (replacement.start !in 0..entry.before.text.length || replacement.end !in replacement.start..entry.before.text.length) return null
            val removed = placeholders(entry.before)
            start = replacement.start - removed.count { it < replacement.start }
            end = replacement.end - removed.count { it < replacement.end }
        }
        val inserted = when (operation) {
            is WindowEdit.Commit -> operation.text.toString()
            is WindowEdit.Context -> if (operation.id == android.R.id.cut) "" else return null
            is WindowEdit.Delete -> {
                if (operation.before < 0 || operation.after < 0) return null
                val selected = old.substring(start, end)
                start = if (operation.codePoints) Character.offsetByCodePoints(old, start,
                    -minOf(operation.before, Character.codePointCount(old, 0, start))) else maxOf(0, start - operation.before)
                end = if (operation.codePoints) Character.offsetByCodePoints(old, end,
                    minOf(operation.after, Character.codePointCount(old, end, old.length))) else minOf(old.length, end + operation.after)
                selected
            }
            is WindowEdit.Key -> {
                if (!operation.event.hasNoModifiers()) return null
                when (operation.event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> { if (start == end) start = GraphemeText.previousCluster(old, start); "" }
                    KeyEvent.KEYCODE_FORWARD_DEL -> { if (start == end) end = GraphemeText.nextCluster(old, end); "" }
                    KeyEvent.KEYCODE_ENTER -> "\n"
                    KeyEvent.KEYCODE_TAB -> "\t"
                    else -> operation.event.unicodeChar.takeIf { it in 0x20..0x10ffff }
                        ?.let { String(Character.toChars(it)) } ?: return null
                }
            }
            else -> return null
        }
        val actual = projected(current).text.toString()
        val prefix = old.substring(0, start)
        val suffix = old.substring(end)
        if (!actual.startsWith(prefix) || !actual.endsWith(suffix) || actual.length < prefix.length + suffix.length ||
            !sameRenderedText(actual, prefix + inserted + suffix)) return null
        val restored = replacement?.removed?.toString() ?: trustedReplacement(entry.before, start, end) ?: return null
        if ('\n' in restored || '\r' in restored) return null
        return LocalEdit(rawOffset(current, prefix.length), rawOffset(current, actual.length - suffix.length), restored)
    }

    private fun nativeClear(entry: Entry, current: EditorTextSnapshot): Boolean {
        if (!matchesAfter(entry, current) && navigation?.let { it.entry === entry && it.after.sameText(current) } != true) return false
        return entry.nativeOnly || forwardAction(entry) != null
    }

    private fun requiresNative(entry: Entry): Boolean = nativeClear(entry, entry.after) && localEdit(entry, entry.after) == null

    private fun forwardAction(entry: Entry): WindowEdit? = if (entry.nativeOnly) null else
        entry.actions?.filter { it != WindowEdit.FinishComposition }?.singleOrNull()?.takeIf {
            when (it) {
                is WindowEdit.Commit, is WindowEdit.Delete -> true
                is WindowEdit.Key -> it.event.hasNoModifiers() && it.event.action == KeyEvent.ACTION_DOWN
                is WindowEdit.Context -> it.id == android.R.id.cut
                else -> false
            }
        }

    fun canUndo(connection: InputConnection): Boolean {
        bind(connection)
        val current = observe(connection) ?: return false
        val entry = entries.lastOrNull() ?: return false
        return hasUndo && (localEdit(entry, current) != null || nativeClear(entry, current))
    }

    fun undo(connection: InputConnection): Boolean {
        bind(connection)
        if (!canUndo(connection)) return false
        connection.finishComposingText()
        clearComposition()
        val current = observe(connection) ?: return false
        if (!hasUndo) return false
        val entry = entries.last()
        val repeatsNativeBody = entries.takeWhile { it !== entry }.any { previous ->
            val action = previous.actions?.filter { it != WindowEdit.FinishComposition }?.singleOrNull()
            val boundary = action is WindowEdit.Context && action.id == android.R.id.cut ||
                action is WindowEdit.Commit && (action.text.isEmpty() && action.replacement != null ||
                    previous.nativeOnly && previous.intermediates.isEmpty()) ||
                action is WindowEdit.Key && action.event.keyCode in intArrayOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)
            boundary && requiresNative(previous) && matchingSnapshot(previous.before, current) != null
        }
        val native = localEdit(entry, current) == null || repeatsNativeBody
        if (native && !nativeClear(entry, current)) return false
        val singleNative = navigation?.let { it.entry === entry && !it.verified } == true
        val operation = Undo(connection, current, entry, native,
            if (singleNative) 1 else 1 + entry.intermediates.size + 2 * (localUndosSinceNative + entries.size - 1), singleNative)
        undoing = operation
        if (native) dispatchNativeUndo(operation, current)
        val success = settleUndo(operation, snapshot(connection))
        if (undoing != null) {
            operation.deferred = true
            schedule()
        }
        return success || operation.completed == true
    }

    private fun dispatchNativeUndo(operation: Undo, current: EditorTextSnapshot) {
        operation.nativeSteps.add(current)
        operation.deadline = SystemClock.uptimeMillis() + WAIT_MS
        nativeKey(operation.target, redo = false)
    }

    private fun dispatchNativeRedo(operation: Undo, current: EditorTextSnapshot?) {
        operation.phase = Phase.REDO
        operation.redoBefore = current
        operation.deadline = SystemClock.uptimeMillis() + WAIT_MS
        nativeKey(operation.target, redo = true)
    }

    private fun settleNative(operation: Undo, initial: EditorTextSnapshot?): Boolean {
        var current = initial
        while (true) {
            val expired = SystemClock.uptimeMillis() >= operation.deadline
            if (operation.phase == Phase.REDO) {
                val expected = operation.nativeSteps[operation.redoIndex]
                val unchanged = matchingSnapshot(operation.redoBefore ?: expected, current) != null
                if (matchingSnapshot(expected, current) != null && (!unchanged || expired)) {
                    if (--operation.redoIndex < 0) { finishUndo(false); return false }
                    dispatchNativeRedo(operation, current)
                    current = snapshot(operation.target)
                    continue
                }
                if (expired) finishUndo(false)
                return false
            }
            if (restoredMatches(operation.entry, current)) {
                return completeUndo(operation, requireNotNull(current))
            }
            val unchanged = matchingSnapshot(operation.nativeSteps.last(), current) != null
            if (operation.singleNative) {
                if (current != null && !unchanged) {
                    operation.redoIndex = operation.nativeSteps.lastIndex
                    dispatchNativeRedo(operation, current)
                    current = snapshot(operation.target)
                    continue
                }
                if (expired) finishUndo(false)
                return false
            }
            if (current != null && (!unchanged || expired)) {
                val earlier = entries.takeWhile { it !== operation.entry }
                val start = earlier.indexOfLast { matchingSnapshot(it.before, current) != null }
                if (start >= 0) {
                    val replay = earlier.drop(start)
                    if (replay.all { forwardAction(it) != null }) {
                        operation.replay = ArrayDeque(replay)
                        operation.phase = Phase.REPLAY
                        operation.selectionSent = false
                        operation.deadline = SystemClock.uptimeMillis() + WAIT_MS
                        return settleReplay(operation, current)
                    }
                }
            }
            val known = current != null && (matchingSnapshot(operation.before, current) != null ||
                nativeTrail.any { matchingSnapshot(it, current) != null } ||
                operation.entry.intermediates.any { matchingSnapshot(it, current) != null || restoredAnchoredWindow(it, current) } ||
                entries.any { matchingSnapshot(it.before, current) != null || matchingSnapshot(it.after, current) != null })
            if (known && (!unchanged || expired) &&
                operation.nativeSteps.size < operation.nativeLimit) {
                dispatchNativeUndo(operation, requireNotNull(current))
                current = snapshot(operation.target)
                continue
            }
            if (expired) {
                if (operation.nativeSteps.all { matchingSnapshot(it, current) != null }) finishUndo(false)
                else {
                    operation.redoIndex = operation.nativeSteps.lastIndex
                    dispatchNativeRedo(operation, current)
                    current = snapshot(operation.target)
                    continue
                }
            }
            return false
        }
    }

    private fun failReplay(operation: Undo, current: EditorTextSnapshot?): Boolean {
        if (operation.replayWrites == 0) {
            operation.redoIndex = operation.nativeSteps.lastIndex
            dispatchNativeRedo(operation, current)
            return settleNative(operation, snapshot(operation.target))
        }
        finishUndo(false)
        return false
    }

    private fun settleReplay(operation: Undo, initial: EditorTextSnapshot?): Boolean {
        var current = initial
        while (true) {
            val next = operation.replay?.firstOrNull()
            if (next == null) {
                if (restoredMatches(operation.entry, current)) return completeUndo(operation, requireNotNull(current))
                finishUndo(false)
                return false
            }
            val expired = SystemClock.uptimeMillis() >= operation.deadline
            if (operation.replaySent) {
                if (current != null && matchesAfter(next, current)) {
                    operation.replay!!.removeFirst()
                    operation.replaySent = false
                    operation.selectionSent = false
                    operation.deadline = SystemClock.uptimeMillis() + WAIT_MS
                    continue
                }
                if (expired) finishUndo(false)
                return false
            }
            val before = matchingSnapshot(next.before, current)
            if (before == null) {
                if (expired) return failReplay(operation, current)
                return false
            }
            val action = forwardAction(next) ?: return failReplay(operation, current)
            val replacement = (action as? WindowEdit.Commit)?.replacement
            val start = replacement?.start ?: before.selectionStart
            val end = replacement?.end ?: before.selectionEnd
            if (current!!.selectionStart != start || current.selectionEnd != end) {
                if (!operation.selectionSent) {
                    operation.selectionSent = true
                    if (!operation.target.setSelection(start, end)) return failReplay(operation, snapshot(operation.target))
                    current = snapshot(operation.target)
                    continue
                }
                if (expired) return failReplay(operation, current)
                return false
            }
            operation.replaySent = true
            operation.deadline = SystemClock.uptimeMillis() + WAIT_MS
            val accepted = runCatching {
                when (action) {
                    is WindowEdit.Commit -> operation.target.commitText(action.text, action.cursor)
                    is WindowEdit.Delete -> if (action.codePoints)
                        operation.target.deleteSurroundingTextInCodePoints(action.before, action.after)
                        else operation.target.deleteSurroundingText(action.before, action.after)
                    is WindowEdit.Key -> operation.target.sendKeyEvent(KeyEvent(action.event))
                    is WindowEdit.Context -> operation.target.performContextMenuAction(action.id)
                    else -> false
                }
            }.getOrDefault(false)
            current = snapshot(operation.target)
            if (!accepted) {
                if (matchingSnapshot(before, current) == null) operation.replayWrites++
                return failReplay(operation, current)
            }
            operation.replayWrites++
        }
    }

    private fun nativeKey(connection: InputConnection, redo: Boolean) {
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
            if (redo) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        runCatching {
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
            connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
        }
    }

    private fun matchesAfter(entry: Entry, current: EditorTextSnapshot): Boolean {
        if (matchingSnapshot(entry.after, current) != null ||
            entry.expected?.let { matches(entry.before, current, it, entry.beforePlaceholders, entry.actions) } == true) return true
        val deleted = entry.intermediates.singleOrNull() ?: return false
        val commit = entry.actions?.singleOrNull() as? WindowEdit.Commit ?: return false
        val expected = deleted.text.toString().replaceRange(minOf(deleted.selectionStart, deleted.selectionEnd),
            maxOf(deleted.selectionStart, deleted.selectionEnd), commit.text)
        return matches(deleted, current, expected, actions = entry.actions) || expansion(deleted, current, expected, entry.actions) != null
    }

    private fun restoredAnchoredWindow(reference: EditorTextSnapshot, current: EditorTextSnapshot): Boolean {
        val before = normalized(reference, placeholders(reference))
        val after = normalized(current, placeholders(current))
        val old = before.text.toString()
        val actual = after.text.toString()
        if (old.length < MIN_WINDOW || actual.length < MIN_WINDOW || !old.endsWith('\n') || !actual.endsWith('\n')) return false
        val oldEnd = old.length - 1
        val newEnd = actual.length - 1
        val endpoints = listOf(before.selectionStart to after.selectionStart, before.selectionEnd to after.selectionEnd)
        val shift = endpoints.filter { (from, to) -> from in 1 until oldEnd && to in 1 until newEnd }
            .map { (from, to) -> to - from }.distinct().singleOrNull() ?: return false
        fun mapped(from: Int, to: Int): Boolean = when {
            from == 0 -> to in 0..maxOf(0, shift)
            from >= oldEnd -> to >= minOf(newEnd, shift + oldEnd)
            to == 0 -> from + shift <= 0
            to >= newEnd -> from + shift >= newEnd
            else -> to == from + shift
        }
        if (!endpoints.all { (from, to) -> mapped(from, to) }) return false
        val oldStart = maxOf(0, -shift)
        val newStart = maxOf(0, shift)
        val count = minOf(old.length - oldStart, actual.length - newStart)
        return count >= MIN_WINDOW && (oldStart == 0 || old[oldStart - 1] == '\n') &&
            (newStart == 0 || actual[newStart - 1] == '\n') && old.regionMatches(oldStart, actual, newStart, count)
    }

    private fun restoredEdgeWindow(reference: EditorTextSnapshot, current: EditorTextSnapshot): Boolean {
        val before = normalized(reference, placeholders(reference))
        val after = normalized(current, placeholders(current))
        if (before.selectionStart != before.selectionEnd || after.selectionStart != after.selectionEnd) return false
        val old = before.text.toString()
        val actual = after.text.toString()
        if (!old.endsWith('\n') || !actual.endsWith('\n')) return false
        val atStart = before.selectionStart == 0 && after.selectionStart == 0
        val atEnd = before.selectionEnd >= old.length - 1 && after.selectionEnd >= actual.length - 1
        if (!atStart && !atEnd) return false
        val shorter = if (old.length <= actual.length) old else actual
        val longer = if (old.length <= actual.length) actual else old
        if (shorter.length < MIN_WINDOW) return false
        val start = longer.indexOf(shorter)
        return start >= 0 && longer.indexOf(shorter, start + 1) < 0 && (start == 0 || longer[start - 1] == '\n') &&
            (atStart && start == 0 || atEnd && start + shorter.length == longer.length)
    }

    private fun restoredSelectedWindow(entry: Entry, current: EditorTextSnapshot): Boolean {
        val action = entry.actions?.filter { it != WindowEdit.FinishComposition }?.singleOrNull() ?: return false
        val clears = when (action) {
            is WindowEdit.Commit -> action.text.isEmpty()
            is WindowEdit.Context -> action.id == android.R.id.cut
            is WindowEdit.Key -> action.event.hasNoModifiers() && action.event.keyCode in
                intArrayOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)
            else -> false
        }
        val deleted = entry.intermediates.singleOrNull()?.takeIf { action is WindowEdit.Commit && action.text == "\t" }
        if (deleted == null && !clears || projected(deleted ?: entry.after).text.toString() !in listOf("", "\n")) return false
        val before = normalized(entry.before, placeholders(entry.before))
        val after = normalized(current, placeholders(current))
        fun covered(value: EditorTextSnapshot): Boolean {
            val visible = projected(value)
            return visible.text.endsWith('\n') && minOf(visible.selectionStart, visible.selectionEnd) == 0 &&
                maxOf(visible.selectionStart, visible.selectionEnd) in visible.text.length - 1..visible.text.length
        }
        if (!covered(before) || !covered(after) ||
            (before.selectionStart > before.selectionEnd) != (after.selectionStart > after.selectionEnd)) return false
        val old = before.text.toString()
        val actual = after.text.toString()
        val smaller = if (old.length <= actual.length) old else actual
        val larger = if (old.length <= actual.length) actual else old
        if (smaller.length < MIN_WINDOW) return false
        var start = larger.indexOf(smaller)
        while (start >= 0) {
            if (start == 0 || larger[start - 1] == '\n') return true
            start = larger.indexOf(smaller, start + 1)
        }
        return false
    }

    private fun restoredCapturedSelection(entry: Entry, current: EditorTextSnapshot): Boolean {
        val copied = entry.capturedSelection ?: return false
        if (!contextCut(entry.actions) || copied.isEmpty()) return false
        val before = projected(entry.before)
        val after = projected(current)
        val cleared = projected(entry.after)
        if (!before.text.endsWith('\n') || !after.text.endsWith('\n') ||
            cleared.selectionStart != cleared.selectionEnd) return false
        fun selected(value: EditorTextSnapshot): Triple<String, Boolean, Boolean>? {
            val start = minOf(value.selectionStart, value.selectionEnd)
            val end = minOf(maxOf(value.selectionStart, value.selectionEnd), value.text.length - 1)
            if (start >= end) return null
            return Triple(value.text.substring(start, end), start == 0, end == value.text.length - 1)
        }
        val original = selected(before) ?: return false
        val restored = selected(after) ?: return false
        if (minOf(original.first.length, restored.first.length) < MIN_WINDOW) return false
        fun compatible(text: String, part: Triple<String, Boolean, Boolean>): Boolean = when {
            !part.second && !part.third -> text == part.first
            !part.second -> text.startsWith(part.first)
            !part.third -> text.endsWith(part.first)
            else -> {
                var start = text.indexOf(part.first)
                var found = false
                while (start >= 0 && !found) {
                    val end = start + part.first.length
                    found = (start == 0 || text[start - 1] == '\n') && (end == text.length || text[end] == '\n')
                    start = text.indexOf(part.first, start + 1)
                }
                found
            }
        }
        if (renderings(copied).none { compatible(it, original) && compatible(it, restored) }) return false
        if (cleared.text.toString() in listOf("", "\n")) return true
        val start = minOf(after.selectionStart, after.selectionEnd)
        val end = maxOf(after.selectionStart, after.selectionEnd)
        var guarded = false
        if (!restored.second) {
            val known = cleared.text.substring(0, cleared.selectionStart)
            val visible = after.text.substring(0, start)
            if (!known.endsWith(visible) && !visible.endsWith(known)) return false
            guarded = known.isNotEmpty() && visible.isNotEmpty()
        }
        if (!restored.third) {
            val known = cleared.text.substring(cleared.selectionEnd)
            val visible = after.text.substring(end)
            if (!known.startsWith(visible) && !visible.startsWith(known)) return false
            guarded = guarded || known.isNotEmpty() && visible.isNotEmpty()
        }
        return guarded
    }

    private data class NumberedWindow(val visible: EditorTextSnapshot, val firstLine: Int, val lineCount: Int)

    private fun numberedWindow(snapshot: EditorTextSnapshot, minimumLines: Int = 3): NumberedWindow? {
        val raw = snapshot.text.toString()
        if (!raw.endsWith('\n')) return null
        val lines = raw.dropLast(1).split('\n')
        if (lines.size % 2 != 0 || lines.size / 2 < minimumLines) return null
        val firstLine = lines[0].toIntOrNull()?.takeIf { it > 0 && it.toString() == lines[0] } ?: return null
        val text = StringBuilder()
        var rawStart = 0
        var selectedStart: Int? = null
        var selectedEnd: Int? = null
        for (row in 0 until lines.size / 2) {
            val number = lines[row * 2]
            if (number != (firstLine.toLong() + row).toString()) return null
            val body = lines[row * 2 + 1]
            val contentStart = rawStart + number.length + 1
            val contentEnd = contentStart + body.length
            fun mapped(position: Int): Int? = if (position in contentStart..contentEnd)
                text.length + position - contentStart else null
            mapped(snapshot.selectionStart)?.let { selectedStart = it }
            mapped(snapshot.selectionEnd)?.let { selectedEnd = it }
            text.append(body).append('\n')
            rawStart = contentEnd + 1
        }
        return NumberedWindow(EditorTextSnapshot(text.toString(), selectedStart ?: return null, selectedEnd ?: return null),
            firstLine, lines.size / 2)
    }

    private fun restoredNumberedWindow(entry: Entry, current: EditorTextSnapshot): Boolean {
        if (!entry.selectedAll && entry.capturedSelection == null) return false
        val before = numberedWindow(entry.before) ?: return false
        val restored = numberedWindow(current) ?: return false
        val after = numberedWindow(entry.after, 1) ?: return false
        fun anchor(value: NumberedWindow, position: Int): Pair<Int, Int>? {
            val visible = projected(value.visible)
            val mapped = position - standalonePlaceholders(value.visible.text).count { it < position }
            if (mapped <= 0 || mapped >= visible.text.length - 1) return null
            val prefix = visible.text.substring(0, mapped)
            return value.firstLine + prefix.count { it == '\n' } to (mapped - prefix.lastIndexOf('\n') - 1)
        }
        for ((from, to) in listOf(before.visible.selectionStart to restored.visible.selectionStart,
            before.visible.selectionEnd to restored.visible.selectionEnd)) {
            val known = anchor(before, from)
            val actual = anchor(restored, to)
            if (known != null && actual != null && known != actual) return false
        }
        val intermediate = entry.intermediates.map { numberedWindow(it, 1)?.visible ?: return false }
        val candidate = entry.copy(before = before.visible, after = after.visible,
            beforePlaceholders = standalonePlaceholders(before.visible.text),
            afterPlaceholders = standalonePlaceholders(after.visible.text), intermediates = intermediate)
        return if (entry.capturedSelection != null) restoredCapturedSelection(candidate, restored.visible)
        else entry.selectedAll && restoredSelectedWindow(candidate, restored.visible)
    }

    private fun restoredMatches(entry: Entry, current: EditorTextSnapshot?): Boolean {
        if (matchingSnapshot(entry.before, current) != null) return true
        if (entry.originalBefore?.let { matchingSnapshot(it, current) } != null) return true
        if (current == null) return false
        if (undoing?.nativeSteps?.isNotEmpty() == true &&
            (restoredAnchoredWindow(entry.before, current) || entry.originalBefore?.let { restoredAnchoredWindow(it, current) } == true ||
                restoredEdgeWindow(entry.before, current) || restoredSelectedWindow(entry, current) || restoredCapturedSelection(entry, current) ||
                restoredNumberedWindow(entry, current)))
            return true
        val replacement = (entry.actions?.singleOrNull() as? WindowEdit.Commit)?.replacement ?: return false
        if (replacement.start !in 0..entry.before.text.length || replacement.end !in replacement.start..entry.before.text.length) return false
        val expected = entry.before.text.toString().replaceRange(replacement.start, replacement.end, replacement.removed)
        val action = WindowEdit.Commit(replacement.removed, 1, replacement = replacement)
        return matches(entry.before, current, expected, entry.beforePlaceholders, listOf(action))
    }

    private fun settleUndo(operation: Undo, initial: EditorTextSnapshot?): Boolean {
        if (settlingUndo || undoing !== operation) return false
        settlingUndo = true
        return try {
            var current = initial
            val expired = SystemClock.uptimeMillis() >= operation.deadline
            when (operation.phase) {
                Phase.REDO, Phase.NATIVE -> settleNative(operation, current)
                Phase.REPLAY -> settleReplay(operation, current)
                Phase.RESTORE_SELECTION -> settleRestoredSelection(operation, current)
                Phase.CONFIRM -> {
                    if (restoredMatches(operation.entry, current)) {
                        completeUndo(operation, requireNotNull(current))
                    } else {
                        if (expired) finishUndo(false)
                        false
                    }
                }
                Phase.SELECT -> {
                    val edit = current?.let { localEdit(operation.entry, it) }
                    if (edit == null) {
                        finishUndo(false)
                        false
                    } else {
                        if (current!!.selectionStart != edit.start || current!!.selectionEnd != edit.end) {
                            if (!operation.selectionSent) {
                                operation.selectionSent = true
                                if (!operation.target.setSelection(edit.start, edit.end)) {
                                    finishUndo(false)
                                    return false
                                }
                                current = snapshot(operation.target)
                            }
                        }
                        if (current == null || !matchesAfter(operation.entry, current!!) ||
                            current!!.selectionStart != edit.start || current!!.selectionEnd != edit.end) {
                            if (expired) finishUndo(false)
                            false
                        } else {
                            operation.dispatchedBefore = current
                            operation.dispatchedPlaceholders = placeholders(current!!)
                            operation.dispatchedActions = listOf(WindowEdit.Commit(edit.text, 1))
                            operation.phase = Phase.CONFIRM
                            operation.deadline = SystemClock.uptimeMillis() + WAIT_MS
                            val accepted = runCatching {
                                operation.target.beginBatchEdit()
                                try { operation.target.commitText(edit.text, 1) }
                                finally { operation.target.endBatchEdit() }
                            }.getOrDefault(false)
                            current = snapshot(operation.target)
                            if (restoredMatches(operation.entry, current)) {
                                completeUndo(operation, requireNotNull(current))
                            } else {
                                if (!accepted) finishUndo(false)
                                false
                            }
                        }
                    }
                }
            }
        } finally { settlingUndo = false }
    }

    private fun restoredSelection(entry: Entry, current: EditorTextSnapshot?): EditorTextSnapshot? {
        matchingSnapshot(entry.before, current)?.let { return it }
        entry.originalBefore?.let { matchingSnapshot(it, current) }?.let { return it }
        val replacement = (entry.actions?.singleOrNull() as? WindowEdit.Commit)?.replacement ?: return null
        val before = entry.before
        if (replacement.start !in 0..before.text.length || replacement.end !in replacement.start..before.text.length) return null
        val expected = before.text.toString().replaceRange(replacement.start, replacement.end, replacement.removed)
        fun offset(position: Int): Int? = when {
            position <= replacement.start -> position
            position >= replacement.end -> position + replacement.removed.length - (replacement.end - replacement.start)
            else -> null
        }
        val start = offset(before.selectionStart) ?: return null
        val end = offset(before.selectionEnd) ?: return null
        return (0..16).mapNotNull { width ->
            val candidate = EditorTextSnapshot(rendered(expected, width),
                rendered(expected.substring(0, start), width).length,
                rendered(expected.substring(0, end), width).length)
            matchingSnapshot(candidate, current)
        }.distinctBy { it.selectionStart to it.selectionEnd }.singleOrNull()
    }

    private fun completeUndo(operation: Undo, current: EditorTextSnapshot): Boolean {
        if (operation.nativeSteps.isNotEmpty()) {
            finishUndo(true, current)
            return true
        }
        val wanted = restoredSelection(operation.entry, current)
        if (wanted == null || sameSelection(wanted, current)) {
            finishUndo(true, current)
            return true
        }
        operation.restoredBody = current
        operation.restoredSelection = wanted
        operation.phase = Phase.RESTORE_SELECTION
        operation.selectionSent = false
        operation.deadline = SystemClock.uptimeMillis() + WAIT_MS
        return settleRestoredSelection(operation, current)
    }

    private fun settleRestoredSelection(operation: Undo, current: EditorTextSnapshot?): Boolean {
        val body = requireNotNull(operation.restoredBody)
        val wanted = requireNotNull(operation.restoredSelection)
        val mapped = matchingSnapshot(wanted, current) ?: restoredSelection(operation.entry, current)
        fun verifiedBody(value: EditorTextSnapshot?): EditorTextSnapshot = value?.takeIf {
            restoredSelection(operation.entry, it) != null || viewportShift(body, it)
        } ?: body
        if (mapped != null && sameSelection(mapped, current)) {
            val now = SystemClock.uptimeMillis()
            val observed = operation.selectionObservedAt
            if (observed == null) operation.selectionObservedAt = now
            if (now >= operation.deadline || observed != null && now - observed >= SELECTION_SETTLE_MS) {
                finishUndo(true, current)
                return true
            }
            return false
        }
        if (mapped != null && operation.selectionObservedAt != null) {
            if (operation.selectionWrites < 2) operation.selectionSent = false
            operation.selectionObservedAt = null
        }
        if (!operation.selectionSent && mapped != null) {
            operation.selectionSent = true
            operation.selectionWrites++
            val accepted = runCatching {
                operation.target.setSelection(mapped.selectionStart, mapped.selectionEnd)
            }.getOrDefault(false)
            val after = snapshot(operation.target)
            if (!accepted) {
                finishUndo(true, verifiedBody(after))
                return true
            }
            return settleRestoredSelection(operation, after)
        }
        if (SystemClock.uptimeMillis() >= operation.deadline ||
            mapped == null && current != null && viewportShift(body, current)) {
            finishUndo(true, verifiedBody(current))
            return true
        }
        return false
    }

    private fun finishUndo(success: Boolean, restored: EditorTextSnapshot? = null) {
        val operation = undoing ?: return
        val restoredPlaceholders = if (success && restored != null) placeholders(restored) else emptyList()
        operation.completed = success
        undoing = null
        handler.removeCallbacks(pump)
        if (success) {
            navigation = null
            selectedAll = restored.takeIf { operation.entry.selectedAll }
            if (operation.nativeSteps.isEmpty() && entries.any { it !== operation.entry && requiresNative(it) }) {
                for (state in listOf(operation.entry.before, operation.entry.after)) {
                    if (nativeTrail.none { it.sameText(state) }) {
                        nativeTrail.add(state)
                        nativeTrailCharacters += state.text.length
                    }
                }
                localUndosSinceNative++
            }
            if (entries.lastOrNull() === operation.entry) removeLast() else discard()
            val previous = entries.lastOrNull()
            if (previous != null && restored != null) {
                val oldSize = previous.size
                previous.after = restored
                previous.afterPlaceholders = restoredPlaceholders
                retainedCharacters += previous.size - oldSize
                while (entries.size > 1 && retainedCharacters > MAX_RETAINED) removeFirst()
            }
            trimNativeTrail()
            onChange?.invoke()
        } else discard()
        if (operation.deferred) onUndoCompleted?.invoke(success)
    }

    private companion object {
        const val MAX_TEXT = 65_536
        const val MAX_ENTRIES = 50
        const val MAX_RETAINED = 1_048_576
        const val POLL_MS = 32L
        const val MAX_POLL_MS = 128L
        const val CONFIRM_POLL_MS = 96L
        const val MAX_CONFIRM_POLL_MS = 384L
        const val IDLE_POLLS = 4
        const val CONSISTENCY_WINDOW = 256
        const val SELECTION_SETTLE_MS = 160L
        const val WAIT_MS = 480L
        const val INSERT_WAIT_MS = 2_000L
        const val MIN_WINDOW = 32
        const val SNAPSHOT_READS = 3
    }
}
