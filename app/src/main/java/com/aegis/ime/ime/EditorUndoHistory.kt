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

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.ReplacementSpan
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

internal data class EditorTextSnapshot(
    val text: CharSequence,
    val selectionStart: Int,
    val selectionEnd: Int,
    val images: List<EditorImageRange> = emptyList(),
) {
    fun logicalText(): CharSequence {
        if (images.isEmpty()) return text
        val result = SpannableStringBuilder(text)
        for (image in images.asReversed()) {
            result.replace(image.start, image.end, "\uFFFC")
            result.setSpan(EditorImageToken(image.content, image.instance), image.start, image.start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return SpannedString(result)
    }

    fun logicalOffset(raw: Int): Int {
        var removed = 0
        for (image in images) {
            if (raw <= image.start) break
            if (raw < image.end) return image.start - removed + 1
            removed += image.end - image.start - 1
        }
        return raw - removed
    }

    fun rawOffset(logical: Int): Int {
        var removed = 0
        for (image in images) {
            if (logical <= image.start - removed) break
            removed += image.end - image.start - 1
        }
        return logical + removed
    }

    fun sameText(other: EditorTextSnapshot): Boolean = logicalText().toString() == other.logicalText().toString() &&
        images.map { logicalOffset(it.start) to it.instance } == other.images.map { other.logicalOffset(it.start) to it.instance }
}

internal data class EditorImageRange(val start: Int, val end: Int, val content: EditorUndoHistory.ImageContent, val instance: Long)
internal data class EditorImageToken(val content: EditorUndoHistory.ImageContent, val instance: Long)

internal class TextUndoHistory(
    private val maxEntries: Int = 50,
    private val maxCharacters: Int = 1_048_576,
) {
    data class Entry(val before: EditorTextSnapshot, val after: EditorTextSnapshot, val restoresDeletion: Boolean = true)
    data class Replacement(val start: Int, val end: Int, val text: CharSequence)

    private val entries = ArrayDeque<Entry>()
    private var characters = 0
    val hasUndo: Boolean get() = entries.isNotEmpty()
    val hasDeletion: Boolean get() = entries.lastOrNull()?.let {
        it.restoresDeletion && (it.before.logicalText().length > it.after.logicalText().length ||
            it.before.images.any { image -> it.after.images.none { after -> after.instance == image.instance } })
    } == true

    fun retainedImageIds(): Set<String> = entries.flatMap { it.before.images + it.after.images }.mapTo(HashSet()) { it.content.id }

    fun clear() {
        entries.clear()
        characters = 0
    }

    fun reconcile(current: EditorTextSnapshot?) {
        if (current == null || entries.lastOrNull()?.after?.sameText(current) == false) clear()
    }

    fun record(before: EditorTextSnapshot?, after: EditorTextSnapshot?, restoresDeletion: Boolean = true) {
        if (before == null || after == null) { clear(); return }
        reconcile(before)
        if (before.sameText(after)) return
        val size = before.text.length + after.text.length
        if (size > maxCharacters || maxEntries < 1) { clear(); return }
        while (entries.isNotEmpty() && (entries.size >= maxEntries || characters + size > maxCharacters)) {
            val removed = entries.removeFirst()
            characters -= removed.before.text.length + removed.after.text.length
        }
        entries.addLast(Entry(before, after, restoresDeletion))
        characters += size
    }

    fun peek(current: EditorTextSnapshot?): Entry? {
        reconcile(current)
        return entries.lastOrNull()
    }

    fun pop() {
        val removed = entries.removeLastOrNull() ?: return
        characters -= removed.before.text.length + removed.after.text.length
    }

    fun replacement(entry: Entry): Replacement {
        val before = entry.before.logicalText()
        val after = entry.after.logicalText()
        fun sameAt(left: Int, right: Int): Boolean {
            if (before[left] != after[right]) return false
            val first = (before as? Spanned)?.getSpans(left, left + 1, EditorImageToken::class.java)?.firstOrNull()
            val second = (after as? Spanned)?.getSpans(right, right + 1, EditorImageToken::class.java)?.firstOrNull()
            return first?.instance == second?.instance
        }
        var start = 0
        while (start < before.length && start < after.length && sameAt(start, start)) start++
        if (start > 0 && start < before.length && Character.isLowSurrogate(before[start])) start--
        var beforeEnd = before.length
        var afterEnd = after.length
        while (beforeEnd > start && afterEnd > start && sameAt(beforeEnd - 1, afterEnd - 1)) {
            beforeEnd--
            afterEnd--
        }
        if (beforeEnd < before.length && beforeEnd > start && Character.isLowSurrogate(before[beforeEnd])) {
            beforeEnd++
            afterEnd++
        }
        return Replacement(start, afterEnd, before.subSequence(start, beforeEnd))
    }
}

internal class RichTextBoundary {
    private data class Range(val start: Int, val end: Int)
    private var text: String? = null
    private var opaqueRange: Range? = null
    private var pending: Range? = null
    private var uncertain = false
    private var active = false
    val isActive: Boolean get() = active

    fun clear() {
        text = null
        opaqueRange = null
        pending = null
        uncertain = false
        active = false
    }

    fun begin(before: EditorTextSnapshot?) {
        if (!active) text = before?.text?.toString()
        active = true
        if (before == null) { uncertain = true; return }
        val selection = Range(minOf(before.selectionStart, before.selectionEnd), maxOf(before.selectionStart, before.selectionEnd))
        pending = union(pending, selection)
        if (selection.start != selection.end) opaqueRange = union(opaqueRange, selection)
    }

    fun observe(current: EditorTextSnapshot?): Boolean {
        if (!active) return true
        if (current == null) { uncertain = true; return false }
        val now = current.text.toString()
        if (now == text) return true
        if (now.isEmpty()) { clear(); return false }
        val previous = text
        val range = pending
        if (!uncertain && previous != null && range != null) {
            val prefix = previous.substring(0, range.start)
            val suffix = previous.substring(range.end)
            val insertedEnd = now.length - suffix.length
            if (insertedEnd > range.start && now.startsWith(prefix) && now.endsWith(suffix)) {
                opaqueRange = union(shift(opaqueRange, range.start, range.end, insertedEnd), Range(range.start, insertedEnd))
                pending = null
                text = now
                return false
            }
        }
        uncertain = true
        pending = null
        text = now
        return false
    }

    fun advance(before: EditorTextSnapshot?, after: EditorTextSnapshot?, known: Boolean): Boolean {
        if (!active) return true
        if (before == null || after == null || text != before.text.toString()) {
            observe(after)
            return false
        }
        if (before.text.toString() == after.text.toString()) return true
        if (pending != null && !known) {
            observe(after)
            return false
        }
        val delta = TextUndoHistory().replacement(TextUndoHistory.Entry(before.copy(images = emptyList()), after.copy(images = emptyList())))
        var suffix = 0
        while (suffix < before.text.length && suffix < after.text.length &&
            before.text[before.text.length - suffix - 1] == after.text[after.text.length - suffix - 1]
        ) suffix++
        val start = minOf(delta.start, before.text.length - suffix, after.text.length - suffix)
        val ambiguous = start != delta.start
        val oldEnd = delta.start + delta.text.length
        val newEnd = delta.end
        val range = opaqueRange
        val touches = range != null && (start < range.end && oldEnd > range.start ||
            start == oldEnd && start > range.start && start < range.end)
        val allowed = !touches && (!uncertain || known && !ambiguous && delta.text.isEmpty())
        opaqueRange = shift(opaqueRange, start, oldEnd, newEnd, enclose = ambiguous && touches)
        pending = shift(pending, start, oldEnd, newEnd, enclose = true)
        text = after.text.toString()
        if (text!!.isEmpty()) clear()
        return allowed
    }

    fun containsSelection(current: EditorTextSnapshot?): Boolean {
        if (!active) return false
        current ?: return true
        val start = minOf(current.selectionStart, current.selectionEnd)
        val end = maxOf(current.selectionStart, current.selectionEnd)
        if (start == end) return false
        return uncertain || listOfNotNull(opaqueRange, pending).any { start < it.end && end > it.start }
    }

    private fun shift(range: Range?, start: Int, oldEnd: Int, newEnd: Int, enclose: Boolean = false): Range? {
        range ?: return null
        val delta = newEnd - oldEnd
        if (oldEnd < range.start || oldEnd == range.start && !(enclose && start == oldEnd)) {
            return Range(range.start + delta, range.end + delta)
        }
        if (start > range.end || start == range.end && !(enclose && start == oldEnd)) return range
        if (!enclose && start <= range.start && oldEnd >= range.end) return null
        val from = minOf(range.start, start)
        val through = maxOf(newEnd, range.end + delta)
        return Range(from, maxOf(from, through))
    }

    private fun union(first: Range?, second: Range): Range =
        if (first == null) second else Range(minOf(first.start, second.start), maxOf(first.end, second.end))
}

class EditorUndoHistory(private val maxTextLength: Int = 65_536) {
    data class ImageContent(val id: String, val paste: (InputConnection) -> Boolean)
    enum class ClearCapture { PLAIN, RICH, UNSUPPORTED }

    private val history = TextUndoHistory()
    private val windowHistory = WindowedEditorUndoHistory().also {
        it.onChange = { changed() }
        it.onUndoCompleted = { success -> onUndoCompleted?.invoke(success) }
        it.onInsertionCompleted = { success -> onInsertionCompleted?.invoke(success) }
    }
    private val nativeHistory = NativeEditorUndoHistory().also {
        it.onChange = { changed() }
        it.onUndoCompleted = { success -> onUndoCompleted?.invoke(success) }
        it.onInsertionCompleted = { success -> onInsertionCompleted?.invoke(success) }
    }
    private val webTabInput = WebEditorTabInput(maxTextLength)
    var preferNativeUndo = false
        set(value) { if (field != value) webTabInput.clear(); field = value }
    var selectionProvider: (() -> Pair<Int, Int>?)? = null
        set(value) { field = value; windowHistory.selectionProvider = value }
    private var incompleteRead = false
    private val richBoundary = RichTextBoundary()
    private data class RichDispatch(val target: InputConnection, val before: EditorTextSnapshot?, val image: ImageContent?, val instance: Long,
        val ambiguous: Boolean = false)
    private var richDispatch: RichDispatch? = null
    private var pendingImage: RichDispatch? = null
    private var anonymousBefore: EditorTextSnapshot? = null
    private val anonymousImageIds = LinkedHashSet<String>()
    private var imageRanges = emptyList<EditorImageRange>()
    private var imageText: String? = null
    private var imageSequence = 0L
    private var compositionUnsafe = false
    private var connection: TrackedConnection? = null
    private var compositionBefore: EditorTextSnapshot? = null
    private var compositionAfter: EditorTextSnapshot? = null
    private var delayed: Pair<EditorTextSnapshot, String>? = null
    var onChange: (() -> Unit)? = null
    var onRetainedImagesChanged: ((Set<String>) -> Unit)? = null
    var onUndoCompleted: ((Boolean) -> Unit)? = null
    var onInsertionCompleted: ((Boolean) -> Unit)? = null
    var onClearedContentRestored: ((Boolean) -> Unit)? = null
    var onClearCompleted: ((ClearCapture, CharSequence) -> Unit)? = null
    private data class ClearDraft(val target: InputConnection, val kind: ClearCapture, val before: EditorTextSnapshot?,
        var swept: CharSequence? = null)
    private var clearDraft: ClearDraft? = null
    val hasPendingClear: Boolean get() = clearDraft?.swept != null
    private var clearedContent: EditorTextSnapshot? = null
    private var nativeCleared: Pair<InputConnection, EditorTextSnapshot>? = null
    val hasClearedContent: Boolean get() = clearedContent != null
    private data class ReplayPart(val text: CharSequence? = null, val image: EditorImageToken? = null)
    private data class ReplayStep(val before: EditorTextSnapshot, val start: Int, val end: Int, val part: ReplayPart)
    private class Replay(val target: InputConnection, val entry: TextUndoHistory.Entry, val parts: ArrayDeque<ReplayPart>,
        var current: EditorTextSnapshot, var start: Int, var end: Int, val restoresClearedContent: Boolean = false) {
        var step: ReplayStep? = null
        var deferred = false
        var started = false
    }
    private var replay: Replay? = null
    private var settlingReplay = false
    val hasPendingUndo: Boolean get() = replay != null || windowHistory.hasPendingUndo || nativeHistory.hasPendingUndo
    val hasPendingInsertion: Boolean get() = windowHistory.hasPendingInsertion || nativeHistory.hasPendingInsertion
    val hasUndo: Boolean get() = richDispatch == null && replay == null &&
        (nativeCleared != null || windowHistory.hasUndo || nativeHistory.hasUndo || history.hasUndo || compositionBefore != null && !compositionUnsafe || delayed != null)
    val hasDeletionToRestore: Boolean get() = hasUndo && (nativeCleared != null || windowHistory.hasDeletion || nativeHistory.hasDeletion || history.hasDeletion || delayed?.let {
        it.first.text.length > it.second.length
    } == true)

    private fun changed() {
        val ids = history.retainedImageIds().toMutableSet()
        ids.addAll(anonymousImageIds)
        imageRanges.mapTo(ids) { it.content.id }
        richDispatch?.image?.let { ids.add(it.id) }
        pendingImage?.image?.let { ids.add(it.id) }
        compositionBefore?.images?.mapTo(ids) { it.content.id }
        delayed?.first?.images?.mapTo(ids) { it.content.id }
        clearDraft?.before?.images?.mapTo(ids) { it.content.id }
        clearedContent?.images?.mapTo(ids) { it.content.id }
        replay?.entry?.let { (it.before.images + it.after.images).mapTo(ids) { image -> image.content.id } }
        onRetainedImagesChanged?.invoke(ids)
        onChange?.invoke()
    }

    fun clear() {
        webTabInput.clear()
        windowHistory.clear()
        nativeHistory.clear()
        richBoundary.clear()
        richDispatch = null
        pendingImage = null
        anonymousBefore = null
        anonymousImageIds.clear()
        imageRanges = emptyList()
        imageText = null
        clearDraft = null
        clearedContent = null
        nativeCleared = null
        replay = null
        compositionUnsafe = false
        history.clear()
        compositionBefore = null
        compositionAfter = null
        delayed = null
        connection = null
        changed()
    }

    fun wrap(target: InputConnection): InputConnection {
        val existing = connection
        if (target === existing || target === existing?.target) return existing
        webTabInput.clear()
        windowHistory.clear()
        nativeHistory.clear()
        history.clear()
        compositionBefore = null
        compositionAfter = null
        compositionUnsafe = false
        delayed = null
        nativeCleared = null
        return TrackedConnection(target).also { connection = it }
    }

    fun cancelWebTabInput() { webTabInput.clear() }

    fun cutCopiedSelection(target: InputConnection, copiedText: CharSequence?): Boolean {
        if (!preferNativeUndo || copiedText == null) return target.performContextMenuAction(android.R.id.cut)
        webTabInput.clear()
        nativeCleared = null
        return nativeHistory.cut(unwrap(target), copiedText)
    }

    fun pasteCopiedText(target: InputConnection, copiedText: CharSequence): Boolean {
        if (!preferNativeUndo) return target.performContextMenuAction(android.R.id.paste)
        webTabInput.clear()
        nativeCleared = null
        return nativeHistory.paste(unwrap(target), copiedText)
    }

    fun deleteCapturedSelection(target: InputConnection, start: Int, removed: CharSequence): Boolean {
        if (preferNativeUndo || removed.length < ChunkedRead.CHUNK / 2) return target.commitText("", 1)
        webTabInput.clear()
        nativeCleared = null
        val raw = unwrap(target)
        val selection = selectionProvider?.invoke()
        val from = selection?.first ?: start
        val to = selection?.second ?: start + removed.length
        return windowHistory.replace(raw, start, removed, "", from, to)
    }

    fun replaceCapturedSelection(target: InputConnection, start: Int, removed: CharSequence,
        inserted: CharSequence, selectionStart: Int, selectionEnd: Int): Boolean {
        if (preferNativeUndo) return target.commitText(inserted, 1)
        webTabInput.clear()
        nativeCleared = null
        return windowHistory.replace(unwrap(target), start, removed, inserted, selectionStart, selectionEnd)
    }

    fun commitCapturedText(target: InputConnection, text: CharSequence, action: (InputConnection) -> Boolean): Boolean {
        val selection = selectionProvider?.invoke()
        if (preferNativeUndo || text.length <= WindowedEditorUndoHistory.MAX_INLINE_INSERTION || selection == null || selection.first != selection.second)
            return action(target)
        webTabInput.clear()
        nativeCleared = null
        val raw = unwrap(target)
        return windowHistory.insert(raw, text)
    }

    fun beginRichContent(target: InputConnection, image: ImageContent? = null) {
        webTabInput.clear()
        val raw = unwrap(target)
        val dispatch = RichDispatch(raw, null, image, ++imageSequence)
        richDispatch = dispatch
        windowHistory.clear()
        nativeHistory.clear()
        val before = capture(raw)
        settleDelayed(before)
        if (!richBoundary.observe(before)) history.clear()
        val anonymous = anonymousBefore
        val conflict = before != null && anonymous != null &&
            minOf(before.selectionStart, before.selectionEnd) <= maxOf(anonymous.selectionStart, anonymous.selectionEnd) &&
            maxOf(before.selectionStart, before.selectionEnd) >= minOf(anonymous.selectionStart, anonymous.selectionEnd)
        richDispatch = dispatch.copy(before = before, ambiguous = pendingImage != null || conflict)
        changed()
    }

    fun finishRichContent(target: InputConnection, dispatched: Boolean) {
        val dispatch = richDispatch ?: return
        richDispatch = null
        if (unwrap(target) !== dispatch.target) return
        val after = readRaw(dispatch.target)
        if (dispatched) {
            compositionBefore = null
            compositionAfter = null
            compositionUnsafe = false
            delayed = null
            if (dispatch.ambiguous) {
                val previous = pendingImage
                previous?.image?.let { retainAnonymous(it.id) }
                dispatch.image?.let { retainAnonymous(it.id) }
                pendingImage = null
                val anchors = listOfNotNull(anonymousBefore, previous?.before, dispatch.before)
                val baseline = dispatch.before ?: anchors.lastOrNull()
                anonymousBefore = baseline?.let { current ->
                    val aligned = anchors.all { it.text.toString() == current.text.toString() }
                    current.copy(
                        selectionStart = if (aligned) anchors.minOf { minOf(it.selectionStart, it.selectionEnd) } else 0,
                        selectionEnd = if (aligned) anchors.maxOf { maxOf(it.selectionStart, it.selectionEnd) } else current.text.length,
                    )
                }
                richBoundary.begin(anonymousBefore)
                observeAnonymous(after)
            } else if (dispatch.image != null && dispatch.before != null) {
                pendingImage = dispatch
                observeImage(after)
            } else {
                history.clear()
                richBoundary.begin(dispatch.before)
                richBoundary.observe(after)
            }
        } else if (dispatch.before == null || after == null || dispatch.before.text.toString() != after.text.toString()) {
            history.clear()
            richBoundary.observe(after)
        }
        changed()
    }

    fun canUndo(target: InputConnection): Boolean {
        if (richDispatch != null) return false
        val raw = unwrap(target)
        if (nativeCleared == null && preferNativeUndo) return nativeHistory.canUndo(raw)
        if (nativeCleared == null && windowHistory.active) return windowHistory.canUndo(raw)
        if (replay != null) {
            if (replay?.target !== raw) finishReplay(false) else continueReplay()
            if (replay != null) return false
        }
        val current = capture(raw)
        nativeCleared?.let { return it.first === raw && it.second.sameText(current ?: return false) }
        settleDelayed(current)
        if (!richBoundary.observe(current)) history.clear()
        reconcileComposition(current)
        val available = if (compositionBefore != null) !compositionUnsafe && current != null && !compositionBefore!!.sameText(current)
            else history.peek(current) != null
        changed()
        return available
    }

    fun selectionContainsRichContent(target: InputConnection): Boolean {
        if (richDispatch != null) return true
        val current = capture(unwrap(target))
        settleDelayed(current)
        if (!richBoundary.observe(current)) history.clear()
        val start = current?.let { minOf(it.selectionStart, it.selectionEnd) }
        val end = current?.let { maxOf(it.selectionStart, it.selectionEnd) }
        return richBoundary.containsSelection(current) ||
            (current == null && (imageRanges.isNotEmpty() || pendingImage != null)) ||
            (start != null && end != null && imageRanges.any { start < it.end && end > it.start })
    }

    fun beginClearRestore(target: InputConnection): ClearCapture {
        webTabInput.clear()
        windowHistory.clear()
        nativeHistory.clear()
        val raw = unwrap(target)
        val before = capture(raw)
        settleDelayed(before)
        val whole = before?.copy(selectionStart = 0, selectionEnd = before.text.length)
        val unread = if (before == null) readRaw(raw)?.text else null
        val observedRich = unread != null && ('\uFFFC' in unread || unread is Spanned &&
            unread.getSpans(0, unread.length, ReplacementSpan::class.java).isNotEmpty())
        val unknown = richDispatch != null || replay != null || pendingImage != null ||
            richBoundary.containsSelection(whole) || before == null &&
            (imageRanges.isNotEmpty() || anonymousBefore != null || observedRich)
        val kind = when {
            unknown -> ClearCapture.UNSUPPORTED
            before?.images?.isNotEmpty() == true -> ClearCapture.RICH
            else -> ClearCapture.PLAIN
        }
        clearDraft = ClearDraft(raw, kind, before)
        changed()
        return kind
    }

    fun finishClearRestore(target: InputConnection, swept: CharSequence): Boolean {
        val draft = clearDraft ?: return false
        if (unwrap(target) !== draft.target) { clearDraft = null; changed(); return false }
        val before = draft.before
        if (before == null || swept.toString() != before.text.toString()) {
            clearDraft = null
            if (swept.isNotEmpty()) {
                clearedContent = null
                onClearCompleted?.invoke(draft.kind, swept)
            }
            changed()
            return false
        }
        draft.swept = swept.toString()
        return settleClear(readRaw(draft.target))
    }

    fun clearCaptured(target: InputConnection): Boolean {
        val draft = clearDraft ?: return false
        val before = draft.before ?: return false
        val raw = unwrap(target)
        if (raw !== draft.target) return false
        if (before.text.isEmpty()) { clearDraft = null; changed(); return true }
        val start = minOf(before.selectionStart, before.selectionEnd)
        val end = maxOf(before.selectionStart, before.selectionEnd)
        raw.beginBatchEdit()
        val accepted = try {
            raw.finishComposingText()
            val surrounding = raw.deleteSurroundingText(start, before.text.length - end)
            val selected = if (start != end) raw.commitText("", 1) else true
            surrounding && selected
        } finally { raw.endBatchEdit() }
        if (accepted) finishClearRestore(raw, before.text)
        else { clearDraft = null; changed() }
        return true
    }

    fun untracked(target: InputConnection): InputConnection = unwrap(target)

    fun finishEditorClear(target: InputConnection): Boolean {
        val draft = clearDraft ?: return false
        if (unwrap(target) !== draft.target) return false
        val before = draft.before
        val after = readRaw(draft.target)
        clearDraft = null
        val success = after != null && before?.sameText(after) != true &&
            after.selectionStart == after.selectionEnd
        if (success) {
            val observed = acceptEdit(before, after, null)
            history.record(compositionBefore ?: before, observed, restoresDeletion = false)
            nativeCleared = draft.target to requireNotNull(after)
            compositionBefore = null
            compositionAfter = null
            compositionUnsafe = false
            delayed = null
            clearedContent = null
        }
        changed()
        return success
    }

    fun cancelClear() {
        clearDraft = null
        changed()
    }

    private fun settleClear(after: EditorTextSnapshot?): Boolean {
        val draft = clearDraft ?: return false
        val swept = draft.swept ?: return false
        val before = draft.before ?: return false
        if (after == null || after.text.toString() == before.text.toString()) return false
        clearDraft = null
        val success = after.text.isEmpty() && after.selectionStart == 0 && after.selectionEnd == 0
        if (success) {
            val observed = acceptEdit(before, after, "")
            history.record(compositionBefore ?: before, observed, restoresDeletion = false)
            compositionBefore = null
            compositionAfter = null
            compositionUnsafe = false
            delayed = null
            clearedContent = if (draft.kind == ClearCapture.RICH) before else null
            onClearCompleted?.invoke(draft.kind, swept)
        }
        changed()
        return success && draft.kind == ClearCapture.RICH
    }

    fun restoreClearedContent(target: InputConnection): Boolean {
        webTabInput.clear()
        val held = clearedContent ?: return false
        if (richDispatch != null || replay != null || clearDraft != null || pendingImage != null) return false
        val raw = unwrap(target)
        val current = capture(raw) ?: return false
        settleDelayed(current)
        val start = minOf(current.selectionStart, current.selectionEnd)
        val end = maxOf(current.selectionStart, current.selectionEnd)
        val anonymous = anonymousBefore
        if (anonymous != null && start <= maxOf(anonymous.selectionStart, anonymous.selectionEnd) &&
            end >= minOf(anonymous.selectionStart, anonymous.selectionEnd)) return false
        if (current.images.any { start > it.start && start < it.end || end > it.start && end < it.end } ||
            current.text.length - (end - start) + held.text.length > maxTextLength) return false
        val content = SpannableStringBuilder(held.logicalText())
        for (token in content.getSpans(0, content.length, EditorImageToken::class.java)) {
            val from = content.getSpanStart(token)
            val through = content.getSpanEnd(token)
            content.removeSpan(token)
            if (from < through) content.setSpan(EditorImageToken(token.content, ++imageSequence), from, through, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val logicalStart = current.logicalOffset(start)
        val logicalEnd = current.logicalOffset(end)
        val desiredText = SpannableStringBuilder(current.logicalText()).replace(logicalStart, logicalEnd, content)
        val desiredImages = desiredText.getSpans(0, desiredText.length, EditorImageToken::class.java)
            .filter { desiredText.getSpanStart(it) < desiredText.getSpanEnd(it) }
            .map { EditorImageRange(desiredText.getSpanStart(it), desiredText.getSpanEnd(it), it.content, it.instance) }
            .sortedBy { it.start }
        desiredText.getSpans(0, desiredText.length, EditorImageToken::class.java).forEach(desiredText::removeSpan)
        val caret = logicalStart + content.length
        val desired = EditorTextSnapshot(SpannedString(desiredText), caret, caret, desiredImages)
        val parts = replayParts(content)
        if (start != end && parts.first().image != null) parts.addFirst(ReplayPart(text = ""))
        if (!runCatching { raw.finishComposingText() }.getOrDefault(false)) return false
        reconcileComposition(current)
        compositionBefore?.let { if (!compositionUnsafe) history.record(it, current) else history.clear() }
        compositionBefore = null
        compositionAfter = null
        compositionUnsafe = false
        replay = Replay(raw, TextUndoHistory.Entry(desired, current), parts, current, start, end, restoresClearedContent = true)
        val success = continueReplay()
        replay?.let { it.deferred = true }
        changed()
        return success
    }

    private fun replayParts(value: CharSequence): ArrayDeque<ReplayPart> {
        val parts = ArrayDeque<ReplayPart>()
        val styled = value as? Spanned
        val tokens = if (styled == null) emptyList() else styled.getSpans(0, value.length, EditorImageToken::class.java)
            .filter { styled.getSpanStart(it) < styled.getSpanEnd(it) }
            .sortedBy { styled.getSpanStart(it) }
        var from = 0
        for (token in tokens) {
            val start = styled?.getSpanStart(token) ?: continue
            if (start > from) parts.addLast(ReplayPart(text = value.subSequence(from, start)))
            parts.addLast(ReplayPart(image = token))
            from = styled.getSpanEnd(token)
        }
        if (from < value.length) parts.addLast(ReplayPart(text = value.subSequence(from, value.length)))
        if (parts.isEmpty()) parts.addLast(ReplayPart(text = ""))
        return parts
    }

    fun undo(target: InputConnection): Boolean {
        webTabInput.clear()
        if (richDispatch != null || replay != null) return false
        val raw = unwrap(target)
        if (nativeCleared == null && preferNativeUndo) return nativeHistory.undo(raw)
        if (nativeCleared == null && windowHistory.active) return windowHistory.undo(raw)
        val current = capture(raw)
        nativeCleared?.let { cleared ->
            if (cleared.first !== raw || current == null || !cleared.second.sameText(current)) return false
            val entry = history.peek(current)
            nativeCleared = null
            val now = SystemClock.uptimeMillis()
            val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            val accepted = raw.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
            raw.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
            raw.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END, 0, meta))
            raw.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END, 0, meta))
            if (accepted) {
                if (entry != null) history.pop()
                val restored = readRaw(raw)
                if (entry != null && restored?.text?.toString() == entry.before.text.toString()) {
                    imageRanges = entry.before.images
                    imageText = restored.text.toString()
                }
            }
            changed()
            return accepted
        }
        settleDelayed(current)
        if (!richBoundary.observe(current)) history.clear()
        reconcileComposition(current)
        compositionBefore?.let {
            if (!compositionUnsafe) history.record(it, current) else history.clear()
            compositionUnsafe = false
            compositionBefore = null
            compositionAfter = null
        }
        val entry = history.peek(current) ?: run { changed(); return false }
        val replacement = history.replacement(entry)
        val parts = replayParts(replacement.text)
        val start = current!!.rawOffset(replacement.start)
        val end = current.rawOffset(replacement.end)
        if (start != end && parts.first().image != null) parts.addFirst(ReplayPart(text = ""))
        if (!runCatching { raw.finishComposingText() }.getOrDefault(false)) {
            history.clear()
            changed()
            return false
        }
        replay = Replay(raw, entry, parts, current, start, end)
        val success = continueReplay()
        replay?.let { it.deferred = true }
        changed()
        return success
    }

    private fun continueReplay(): Boolean {
        if (settlingReplay) return false
        val operation = replay ?: return false
        settlingReplay = true
        try {
            while (replay === operation) {
                val step = operation.step
                if (step != null) {
                    val observed = readRaw(operation.target) ?: return false
                    val token = step.part.image
                    val after = if (token != null) {
                        if (observed.text.toString() == step.before.text.toString()) return false
                        locateImage(step.before, observed, token.content, token.instance)
                    } else {
                        val expected = step.before.text.toString().replaceRange(step.start, step.end, step.part.text ?: "")
                        if (observed.text.toString() != expected && observed.text.toString() == step.before.text.toString()) return false
                        if (observed.text.toString() != expected) null else acceptEdit(step.before, observed, expected)
                    }
                    if (after == null) { finishReplay(false); return false }
                    moveAnonymous(step.before, after)
                    operation.current = after
                    operation.start = if (token != null) imageRanges.first { it.instance == token.instance }.end
                        else step.start + (step.part.text ?: "").length
                    operation.end = operation.start
                    operation.step = null
                    operation.parts.removeFirst()
                    continue
                }
                if (operation.parts.isEmpty()) {
                    val desiredStart = operation.current.rawOffset(operation.entry.before.logicalOffset(operation.entry.before.selectionStart))
                    val desiredEnd = operation.current.rawOffset(operation.entry.before.logicalOffset(operation.entry.before.selectionEnd))
                    val selected = runCatching { operation.target.setSelection(desiredStart, desiredEnd) }.getOrDefault(false)
                    val restored = readRaw(operation.target)?.let(::withImages)
                    val success = selected && restored != null && restored.sameText(operation.entry.before) &&
                        restored.selectionStart == desiredStart && restored.selectionEnd == desiredEnd
                    if (success) operation.current = restored
                    finishReplay(success)
                    return success
                }
                val selected = runCatching { operation.target.setSelection(operation.start, operation.end) }.getOrDefault(false)
                val before = readRaw(operation.target)?.let(::withImages)
                if (!selected || before == null || !before.sameText(operation.current) ||
                    before.selectionStart != operation.start || before.selectionEnd != operation.end) {
                    finishReplay(false)
                    return false
                }
                val part = operation.parts.first()
                if (!operation.started) {
                    operation.started = true
                    if (operation.restoresClearedContent) clearedContent = null
                    changed()
                }
                operation.step = ReplayStep(before, operation.start, operation.end, part)
                val accepted = runCatching {
                    part.image?.content?.paste?.invoke(operation.target) ?: operation.target.commitText(part.text, 1)
                }.getOrNull()
                if (accepted == false) {
                    val observed = readRaw(operation.target)
                    if (observed == null || observed.text.toString() == before.text.toString()) {
                        finishReplay(false)
                        return false
                    }
                }
            }
        } catch (_: RuntimeException) {
            finishReplay(false)
        } finally {
            settlingReplay = false
        }
        return false
    }

    private fun finishReplay(success: Boolean) {
        val operation = replay ?: return
        replay = null
        if (success) {
            if (operation.restoresClearedContent) history.record(operation.entry.after, operation.current) else history.pop()
            richBoundary.advance(operation.entry.after, operation.current, true)
        } else {
            history.clear()
            val raw = readRaw(operation.target)
            if (raw == null || imageText != raw.text.toString()) {
                val rich = imageRanges.isNotEmpty() || operation.entry.before.images.isNotEmpty()
                imageRanges = emptyList()
                imageText = raw?.text?.toString()
                if (rich) richBoundary.begin(null)
            }
        }
        delayed = null
        changed()
        if (operation.deferred) {
            if (operation.restoresClearedContent) onClearedContentRestored?.invoke(success) else onUndoCompleted?.invoke(success)
        }
    }

    private fun unwrap(target: InputConnection): InputConnection =
        if (target is TrackedConnection) target.target else target

    private fun readRaw(target: InputConnection, retryChanged: Boolean = true): EditorTextSnapshot? = runCatching {
        incompleteRead = false
        val before = target.getTextBeforeCursor(maxTextLength + 1, 0)
        val after = target.getTextAfterCursor(maxTextLength + 1, 0)
        if (before == null || after == null) { incompleteRead = true; return@runCatching null }
        if (before.length + after.length > maxTextLength) { incompleteRead = true; return@runCatching null }
        val extracted = target.getExtractedText(ExtractedTextRequest().apply {
            flags = InputConnection.GET_TEXT_WITH_STYLES
            hintMaxChars = maxTextLength + 1
            hintMaxLines = maxTextLength + 1
        }, 0) ?: run { incompleteRead = true; return@runCatching null }
        val text = extracted.text ?: run { incompleteRead = true; return@runCatching null }
        if (text.length > maxTextLength || extracted.startOffset != 0 || extracted.partialStartOffset >= 0) incompleteRead = true
        if (extracted.startOffset != 0 || extracted.partialStartOffset >= 0 ||
            text.length > maxTextLength ||
            extracted.selectionStart !in 0..text.length || extracted.selectionEnd !in 0..text.length
        ) return@runCatching null
        val low = minOf(extracted.selectionStart, extracted.selectionEnd)
        val high = maxOf(extracted.selectionStart, extracted.selectionEnd)
        if (before.toString() != text.subSequence(0, low).toString() ||
            after.toString() != text.subSequence(high, text.length).toString()
        ) return@runCatching if (retryChanged) readRaw(target, false) else null
        val frozen = if (text is Spanned) {
            val copy = SpannableStringBuilder(text)
            BaseInputConnection.removeComposingSpans(copy)
            SpannedString(copy)
        } else text.toString()
        EditorTextSnapshot(frozen, extracted.selectionStart, extracted.selectionEnd)
    }.getOrNull()

    private fun capture(target: InputConnection): EditorTextSnapshot? {
        val raw = readRaw(target) ?: return null
        if (nativeCleared?.second?.sameText(raw) == false) nativeCleared = null
        settleClear(raw)
        val queued = delayed
        if (queued != null && raw.text.toString() != queued.first.text.toString()) {
            return acceptEdit(queued.first, raw, queued.second)
        }
        val anonymousChange = observeAnonymous(raw)
        if (!anonymousChange && pendingImage != null) observeImage(raw)
        if (imageText != null && imageText != raw.text.toString() && imageRanges.isNotEmpty()) {
            imageRanges = emptyList()
            history.clear()
            richBoundary.begin(null)
        }
        imageText = raw.text.toString()
        return withImages(raw)
    }

    private fun withImages(raw: EditorTextSnapshot): EditorTextSnapshot? {
        val value = raw.text
        for (index in value.indices) {
            if (value[index] == '\uFFFC' && imageRanges.none { index >= it.start && index < it.end }) return null
        }
        if (value is Spanned && value.getSpans(0, value.length, ReplacementSpan::class.java).any { span ->
                imageRanges.none { value.getSpanStart(span) >= it.start && value.getSpanEnd(span) <= it.end }
            }) return null
        return raw.copy(images = imageRanges.toList())
    }

    private fun locateImage(before: EditorTextSnapshot, raw: EditorTextSnapshot, image: ImageContent, instance: Long): EditorTextSnapshot? {
        val start = minOf(before.selectionStart, before.selectionEnd)
        val end = maxOf(before.selectionStart, before.selectionEnd)
        val prefix = before.text.subSequence(0, start).toString()
        val suffix = before.text.subSequence(end, before.text.length).toString()
        val insertedEnd = raw.text.length - suffix.length
        if (insertedEnd <= start || !raw.text.startsWith(prefix) || !raw.text.endsWith(suffix)) return null
        val moved = moveImages(before.images, start, end, insertedEnd) ?: return null
        imageRanges = (moved + EditorImageRange(start, insertedEnd, image, instance)).sortedBy { it.start }
        imageText = raw.text.toString()
        return withImages(raw)
    }

    private fun observeImage(raw: EditorTextSnapshot?) {
        val pending = pendingImage ?: return
        val before = pending.before ?: return
        if (raw == null || raw.text.toString() == before.text.toString()) return
        val after = locateImage(before, raw, pending.image ?: return, pending.instance)
        pendingImage = null
        if (after != null) {
            if (richBoundary.advance(before, after, true)) history.record(before, after) else history.clear()
            moveAnonymous(before, after)
        } else {
            history.clear()
            imageRanges = emptyList()
            imageText = raw.text.toString()
            richBoundary.begin(null)
        }
        changed()
    }

    private fun retainAnonymous(id: String) {
        anonymousImageIds.remove(id)
        anonymousImageIds.add(id)
        while (anonymousImageIds.size > 50) anonymousImageIds.remove(anonymousImageIds.first())
    }

    private fun observeAnonymous(raw: EditorTextSnapshot?): Boolean {
        val before = anonymousBefore ?: return false
        if (raw == null || raw.text.toString() == before.text.toString()) return false
        val start = minOf(before.selectionStart, before.selectionEnd)
        val end = maxOf(before.selectionStart, before.selectionEnd)
        val suffix = before.text.subSequence(end, before.text.length).toString()
        val newEnd = raw.text.length - suffix.length
        if (newEnd < start || !raw.text.startsWith(before.text.subSequence(0, start)) || !raw.text.endsWith(suffix)) return false
        val moved = moveImages(before.images, start, end, newEnd) ?: return false
        pendingImage?.image?.let { retainAnonymous(it.id) }
        pendingImage = null
        richBoundary.begin(before)
        richBoundary.observe(raw)
        imageRanges = moved
        imageText = raw.text.toString()
        anonymousBefore = raw.copy(selectionStart = start, selectionEnd = newEnd, images = moved)
        history.clear()
        changed()
        return true
    }

    private fun moveAnonymous(before: EditorTextSnapshot, after: EditorTextSnapshot) {
        val anonymous = anonymousBefore ?: return
        if (anonymous.text.toString() != before.text.toString()) return
        val delta = history.replacement(TextUndoHistory.Entry(before.copy(images = emptyList()), after.copy(images = emptyList())))
        val start = delta.start
        val end = start + delta.text.length
        val from = minOf(anonymous.selectionStart, anonymous.selectionEnd)
        val through = maxOf(anonymous.selectionStart, anonymous.selectionEnd)
        val shift = delta.end - end
        val moved = when {
            end < from || end == from && start != end -> from + shift to through + shift
            start > through || start == through && start != end -> from to through
            else -> minOf(from, start) to maxOf(delta.end, through + shift)
        }
        anonymousBefore = after.copy(selectionStart = moved.first, selectionEnd = moved.second)
    }

    private fun moveImages(images: List<EditorImageRange>, start: Int, end: Int, newEnd: Int): List<EditorImageRange>? {
        val moved = ArrayList<EditorImageRange>()
        for (image in images) {
            when {
                image.end <= start -> moved.add(image)
                image.start >= end -> moved.add(image.copy(start = image.start + newEnd - end, end = image.end + newEnd - end))
                image.start >= start && image.end <= end -> Unit
                else -> return null
            }
        }
        return moved
    }

    private fun matchesExpected(before: EditorTextSnapshot?, after: EditorTextSnapshot?, expected: String?): Boolean {
        if (expected == null) return true
        if (after?.text?.toString() == expected) return true
        if (before == null || after == null || before.images.isEmpty()) return false
        val predicted = EditorTextSnapshot(expected, 0, 0)
        val delta = history.replacement(TextUndoHistory.Entry(before.copy(images = emptyList()), predicted))
        var start = delta.start
        var end = start + delta.text.length
        val touched = before.images.filter { start < it.end && end > it.start }
        if (touched.isEmpty()) return false
        start = minOf(start, touched.minOf { it.start })
        end = maxOf(end, touched.maxOf { it.end })
        val inserted = expected.substring(delta.start, delta.end)
        return before.text.toString().replaceRange(start, end, inserted) == after.text.toString()
    }

    private fun acceptEdit(before: EditorTextSnapshot?, raw: EditorTextSnapshot?, expected: String?): EditorTextSnapshot? {
        if (before == null || raw == null) return null
        if (before.text.toString() == raw.text.toString()) return raw.copy(images = before.images)
        if (!matchesExpected(before, raw, expected)) return null
        val delta = history.replacement(TextUndoHistory.Entry(before.copy(images = emptyList()), raw))
        var start = delta.start
        var end = start + delta.text.length
        var newEnd = delta.end
        val selectedStart = minOf(before.selectionStart, before.selectionEnd)
        val selectedEnd = maxOf(before.selectionStart, before.selectionEnd)
        val selectedSuffix = before.text.subSequence(selectedEnd, before.text.length).toString()
        val selectedNewEnd = raw.text.length - selectedSuffix.length
        if (selectedNewEnd >= selectedStart && raw.text.startsWith(before.text.subSequence(0, selectedStart)) &&
            raw.text.endsWith(selectedSuffix)) {
            start = selectedStart
            end = selectedEnd
            newEnd = selectedNewEnd
        } else if (selectedStart == selectedEnd && raw.text.length < before.text.length) {
            val removed = before.text.length - raw.text.length
            val cursor = minOf(raw.selectionStart, raw.selectionEnd)
            val from = if (cursor < selectedStart) selectedStart - removed else selectedStart
            if (from >= 0 && from + removed <= before.text.length &&
                before.text.toString().removeRange(from, from + removed) == raw.text.toString()) {
                start = from
                end = from + removed
                newEnd = from
            }
        }
        val moved = moveImages(before.images, start, end, newEnd)
        if (moved == null) {
            imageRanges = emptyList()
            imageText = raw.text.toString()
            richBoundary.begin(null)
            return null
        }
        imageRanges = moved
        imageText = raw.text.toString()
        val result = withImages(raw) ?: return null
        moveAnonymous(before, result)
        pendingImage?.let { pending ->
            if (pending.before?.text?.toString() == before.text.toString()) pendingImage = pending.copy(before = result)
        }
        return result
    }

    private fun settleDelayed(current: EditorTextSnapshot?) {
        val pending = delayed ?: return
        if (current == null) { delayed = null; history.clear(); return }
        if (matchesExpected(pending.first, current, pending.second)) {
            if (richBoundary.advance(pending.first, current, true)) history.record(pending.first, current) else history.clear()
            delayed = null
        } else if (!pending.first.sameText(current)) {
            delayed = null
            history.clear()
            richBoundary.observe(current)
        }
    }

    private fun reconcileComposition(current: EditorTextSnapshot?) {
        if (compositionBefore != null && (current == null || compositionAfter?.sameText(current) == false)) {
            compositionBefore = null
            compositionAfter = null
            compositionUnsafe = false
            history.clear()
        }
    }

    private inner class TrackedConnection(val target: InputConnection) : InputConnectionWrapper(target, false) {
        private var batchDepth = 0
        private var batchBefore: EditorTextSnapshot? = null
        private var batchChanged = false
        private var batchFinishesComposition = false
        private var batchSafe = true
        private var windowBatch = false
        private var nativeBatch = false

        private fun allowsWindow(): Boolean = richDispatch == null && pendingImage == null && anonymousBefore == null &&
            imageRanges.isEmpty() && !richBoundary.isActive && replay == null && nativeCleared == null

        private fun tracked(
            composing: Boolean = false,
            finishesComposition: Boolean = false,
            expected: ((EditorTextSnapshot) -> String?)? = null,
            windowEdit: WindowEdit = WindowEdit.Unknown,
            action: () -> Boolean,
        ): Boolean {
            if (windowHistory.hasPendingInsertion || windowHistory.hasPendingUndo) return false
            if (windowEdit !is WindowEdit.Commit || windowEdit.composing) webTabInput.clear()
            if (connection !== this || richDispatch != null) return action()
            if (preferNativeUndo) {
                nativeCleared = null
                return nativeHistory.track(target, composing = composing,
                    finishesComposition = finishesComposition, expected = expected, operation = windowEdit, action = action)
            }
            if (windowBatch || windowHistory.active && allowsWindow()) return windowHistory.track(target, windowEdit, action)
            if (replay != null) finishReplay(false)
            if (batchDepth > 0) {
                batchChanged = true
                if (composing && compositionBefore == null) compositionBefore = batchBefore
                if (finishesComposition) batchFinishesComposition = true
                val before = capture(target)
                val prediction = before?.let { expected?.invoke(it) }
                val result = action()
                val after = acceptEdit(before, readRaw(target), prediction)
                val matches = matchesExpected(before, after, prediction)
                val safe = matches && richBoundary.advance(before, after, prediction != null)
                batchSafe = batchSafe && safe
                return result
            }
            val before = capture(target)
            if (before == null && incompleteRead && allowsWindow()) return windowHistory.track(target, windowEdit, action)
            settleDelayed(before)
            if (!richBoundary.observe(before)) history.clear()
            reconcileComposition(before)
            if (delayed != null) {
                delayed = null
                history.clear()
            }
            if (compositionBefore == null) history.reconcile(before)
            if (composing && compositionBefore == null) {
                compositionBefore = before
                compositionUnsafe = false
            }
            val baseline = compositionBefore ?: before
            val prediction = before?.let { expected?.invoke(it) }
            val succeeded = try { action() } catch (error: RuntimeException) {
                history.clear()
                compositionBefore = null
                compositionAfter = null
                compositionUnsafe = false
                changed()
                throw error
            }
            val rawAfter = readRaw(target)
            val after = acceptEdit(before, rawAfter, prediction)
            val waiting = succeeded && baseline != null && prediction != null &&
                prediction != baseline.text.toString() && prediction.length <= maxTextLength &&
                (rawAfter == null || baseline.text.toString() == rawAfter.text.toString())
            val matches = matchesExpected(before, after, prediction)
            val safe = matches && richBoundary.advance(before, after, prediction != null)
            if (compositionBefore == null || finishesComposition) {
                if (waiting && !compositionUnsafe) delayed = baseline to prediction
                else if (safe && !compositionUnsafe) history.record(baseline, after)
                else { history.clear(); richBoundary.observe(after) }
                compositionBefore = null
                compositionAfter = null
                compositionUnsafe = false
            } else if (after == null) {
                compositionBefore = null
                compositionAfter = null
                compositionUnsafe = false
                history.clear()
            } else {
                compositionUnsafe = compositionUnsafe || !safe
                if (compositionUnsafe) history.clear()
                compositionAfter = after
            }
            changed()
            return succeeded
        }

        override fun beginBatchEdit(): Boolean {
            if (connection === this && batchDepth++ == 0) {
                if (preferNativeUndo) {
                    nativeBatch = true
                    nativeHistory.beginBatch(target)
                    return target.beginBatchEdit()
                }
                val current = capture(target)
                if (allowsWindow() && (windowHistory.active || current == null && incompleteRead)) {
                    windowBatch = true
                    windowHistory.beginBatch(target)
                    return target.beginBatchEdit()
                }
                settleDelayed(current)
                if (!richBoundary.observe(current)) history.clear()
                reconcileComposition(current)
                batchBefore = compositionBefore ?: current
                delayed = null
                batchChanged = false
                batchFinishesComposition = false
                batchSafe = true
            }
            return target.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            val result = target.endBatchEdit()
            if (connection === this && batchDepth > 0 && --batchDepth == 0) {
                if (nativeBatch) {
                    nativeBatch = false
                    nativeHistory.endBatch(target)
                    return result
                }
                if (windowBatch) {
                    windowBatch = false
                    windowHistory.endBatch(target)
                    return result
                }
                if (batchChanged) {
                    val after = capture(target)
                    compositionUnsafe = compositionUnsafe || !batchSafe
                    if (compositionBefore == null || batchFinishesComposition || after == null) {
                        if (!compositionUnsafe) history.record(batchBefore, after) else history.clear()
                        compositionBefore = null
                        compositionAfter = null
                        compositionUnsafe = false
                    } else {
                        compositionAfter = after
                    }
                }
                batchBefore = null
                changed()
            }
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (preferNativeUndo && connection === this && richDispatch == null && text?.toString() == "\t" && newCursorPosition == 1) {
                val replaced = nativeHistory.replaceSelectedTab(target) {
                    webTabInput.clear()
                    val prepared = webTabInput.prepare(target, "\t", 1)
                    if (prepared != null) webTabInput.apply(prepared) else target.commitText("\t", 1)
                }
                if (replaced != null) return replaced
            }

            val tab = if (preferNativeUndo && connection === this && richDispatch == null)
                webTabInput.prepare(target, text, newCursorPosition) else { webTabInput.clear(); null }
            if (tab != null) return tracked(
                finishesComposition = true,
                expected = { before ->
                    val replacement = tab.commit.replacement
                    if (replacement == null) insertedText(before, tab.commit.text)
                    else if (before.text.toString() == tab.before.text.toString())
                        before.text.toString().replaceRange(replacement.start, replacement.end, tab.commit.text)
                    else null
                },
                windowEdit = tab.commit,
            ) { webTabInput.apply(tab) }
            return tracked(
                finishesComposition = true,
                expected = { before -> insertedText(compositionBefore ?: before, text) },
                windowEdit = WindowEdit.Commit(text ?: "", newCursorPosition),
            ) { target.commitText(text, newCursorPosition) }
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            webTabInput.clear()
            return if (preferNativeUndo && connection === this) nativeHistory.navigate(target) { target.setSelection(start, end) }
                else target.setSelection(start, end)
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            webTabInput.clear()
            return target.setComposingRegion(start, end)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            tracked(composing = true, expected = { before -> insertedText(compositionBefore ?: before, text) },
                windowEdit = WindowEdit.Commit(text ?: "", newCursorPosition, composing = true)) {
                target.setComposingText(text, newCursorPosition)
            }

        override fun finishComposingText(): Boolean {
            webTabInput.clear()
            return if (nativeCleared != null || compositionBefore == null && !windowHistory.active && !preferNativeUndo) target.finishComposingText()
            else tracked(finishesComposition = true, windowEdit = WindowEdit.FinishComposition) { target.finishComposingText() }
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            tracked(expected = { surroundingDeletion(it, beforeLength, afterLength, false) },
                windowEdit = WindowEdit.Delete(beforeLength, afterLength)) {
                target.deleteSurroundingText(beforeLength, afterLength)
            }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            tracked(expected = { surroundingDeletion(it, beforeLength, afterLength, true) },
                windowEdit = WindowEdit.Delete(beforeLength, afterLength, codePoints = true)) {
                target.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) webTabInput.clear()
            val printable = event.unicodeChar != 0 && !event.isCtrlPressed && !event.isMetaPressed
            val shortcutEdit = (event.isCtrlPressed || event.isMetaPressed) && event.keyCode in intArrayOf(
                KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y,
            )
            val edits = event.action == KeyEvent.ACTION_DOWN && (printable || shortcutEdit ||
                event.keyCode in intArrayOf(
                    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_CUT, KeyEvent.KEYCODE_PASTE,
                ))
            val selectsAll = (event.isCtrlPressed || event.isMetaPressed) && event.keyCode == KeyEvent.KEYCODE_A
            val navigates = preferNativeUndo && connection === this && event.action == KeyEvent.ACTION_DOWN &&
                (selectsAll || event.keyCode in intArrayOf(
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
                ))
            return if (edits) tracked(expected = { keyResult(it, event) }, windowEdit = WindowEdit.Key(event)) { target.sendKeyEvent(event) }
                else if (navigates) nativeHistory.navigate(target, selectAll = selectsAll) { target.sendKeyEvent(event) }
                else target.sendKeyEvent(event)
        }

        private fun insertedText(before: EditorTextSnapshot, text: CharSequence?): String {
            val start = minOf(before.selectionStart, before.selectionEnd)
            val end = maxOf(before.selectionStart, before.selectionEnd)
            return before.text.subSequence(0, start).toString() + (text ?: "") + before.text.subSequence(end, before.text.length)
        }

        private fun surroundingDeletion(
            before: EditorTextSnapshot,
            beforeLength: Int,
            afterLength: Int,
            codePoints: Boolean,
        ): String? {
            if (beforeLength < 0 || afterLength < 0) return null
            val text = before.text.toString()
            val start = minOf(before.selectionStart, before.selectionEnd)
            val end = maxOf(before.selectionStart, before.selectionEnd)
            val from = if (codePoints) Character.offsetByCodePoints(
                text, start, -minOf(beforeLength, Character.codePointCount(text, 0, start)),
            ) else start - minOf(beforeLength, start)
            val through = if (codePoints) Character.offsetByCodePoints(
                text, end, minOf(afterLength, Character.codePointCount(text, end, text.length)),
            ) else end + minOf(afterLength, text.length - end)
            return text.substring(0, from) + text.substring(start, end) + text.substring(through)
        }

        private fun keyResult(before: EditorTextSnapshot, event: KeyEvent): String? {
            if (!event.hasNoModifiers()) return null
            val text = before.text.toString()
            val start = minOf(before.selectionStart, before.selectionEnd)
            val end = maxOf(before.selectionStart, before.selectionEnd)
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> text.removeRange(
                    if (start == end) GraphemeText.previousCluster(text, start) else start, end,
                )
                KeyEvent.KEYCODE_FORWARD_DEL -> text.removeRange(
                    start, if (start == end) GraphemeText.nextCluster(text, end) else end,
                )
                KeyEvent.KEYCODE_ENTER -> text.replaceRange(start, end, "\n")
                KeyEvent.KEYCODE_TAB -> text.replaceRange(start, end, "\t")
                else -> event.unicodeChar.takeIf { it in 0x20..0x10ffff }?.let {
                    text.replaceRange(start, end, String(Character.toChars(it)))
                }
            }
        }

        override fun performContextMenuAction(id: Int): Boolean {
            webTabInput.clear()
            return if (id == android.R.id.cut || id == android.R.id.paste || id == android.R.id.pasteAsPlainText)
                tracked(expected = if (id == android.R.id.cut) { before -> insertedText(before, "") } else null,
                    windowEdit = WindowEdit.Context(id)) {
                    target.performContextMenuAction(id)
                }
            else if (id == android.R.id.selectAll && preferNativeUndo && connection === this)
                nativeHistory.navigate(target, selectAll = true) { target.performContextMenuAction(id) }
            else target.performContextMenuAction(id)
        }

        override fun commitCompletion(text: CompletionInfo?): Boolean =
            tracked(finishesComposition = true) { target.commitCompletion(text) }

        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean =
            tracked(finishesComposition = true) { target.commitCorrection(correctionInfo) }
    }
}
