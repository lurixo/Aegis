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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts

private enum class ShiftState { OFF, ONCE, LOCK }

private enum class Mode { PINYIN, DIRECT }

private enum class StepKind { DIGIT, LITERAL, LOCK, DIGIT_CHOICE, CUT }

private data class ReadingLock(val reading: String, val start: Int, val end: Int)

private data class MixedPart(val start: Int, val end: Int, val literal: Boolean) {
    val length: Int get() = end - start
}

private fun mixedParts(raw: String, literals: Set<Int>): List<MixedPart> {
    if (raw.isEmpty()) return emptyList()
    val out = ArrayList<MixedPart>()
    var start = 0
    while (start < raw.length) {
        val literal = start in literals
        var end = start + 1
        while (end < raw.length && (end in literals) == literal) end++
        out.add(MixedPart(start, end, literal))
        start = end
    }
    return out
}

class KeyboardController(
    editor: ImeHost,
    private var engine: CandidateEngine,
) {
    private val host: ImeHost = editor

    private var lang = Lang.CN
    private var shiftState = ShiftState.OFF
    private val shifted get() = shiftState != ShiftState.OFF
    private var layoutId = LayoutId.ALPHA

    private var cnLayout = LayoutId.NINE
    private val composing = StringBuilder()
    private val literalIndices = sortedSetOf<Int>()
    private var candidates: List<Cand> = emptyList()

    private val committedPrefix = StringBuilder()

    private val lockedReadings = mutableListOf<String>()
    private val lockedInputLengths = mutableListOf<Int>()
    private var activeStart = 0

    private val forcedCuts = sortedSetOf<Int>()

    private val history = ArrayDeque<StepKind>()

    private var compositeCands: Set<Cand> = emptySet()
    private var literalCands: Set<Cand> = emptySet()

    var onShowCustomSymbols: () -> Unit = {}
    var onShowCustomOperators: () -> Unit = {}

    private var view: InputView? = null

    fun attachView(v: InputView) {
        view = v
        render()
    }

    fun reset() {
        composing.setLength(0)
        literalIndices.clear()
        candidates = emptyList()
        compositeCands = emptySet()
        literalCands = emptySet()
        lockedReadings.clear()
        lockedInputLengths.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        committedPrefix.setLength(0)
        shiftState = ShiftState.OFF

        render()
    }

    internal fun activeLayoutId(): LayoutId = layoutId

    internal fun switchTextLayoutForTest(nine: Boolean) {
        switchLayout(if (nine) LayoutId.NINE else LayoutId.ALPHA)
        refreshCandidates()
        render()
    }

    fun onKey(key: Key) {
        when (key.action) {
            KeyAction.COMMIT -> handleCommit(key)
            KeyAction.BACKSPACE -> handleBackspace()
            KeyAction.CLEAR_COMPOSING -> handleClearComposing()
            KeyAction.SPACE -> handleSpace()
            KeyAction.ENTER -> handleEnter()
            KeyAction.SHIFT -> if (mode() == Mode.DIRECT) {
                shiftState = if (shiftState == ShiftState.OFF) ShiftState.ONCE else ShiftState.OFF
            }
            KeyAction.SHIFT_LOCK -> if (mode() == Mode.DIRECT) shiftState = ShiftState.LOCK
            KeyAction.SWITCH_SYMBOLS -> switchLayout(LayoutId.SYMBOL)
            KeyAction.SWITCH_NUMBERS -> switchLayout(LayoutId.NUMBER)
            KeyAction.SWITCH_TEXT -> switchLayout(if (lang == Lang.CN) cnLayout else LayoutId.ALPHA)
            KeyAction.SWITCH_NUMPAD -> switchLayout(LayoutId.NUMPAD)
            KeyAction.PICK_READING -> handlePickReading(key)
            KeyAction.PICK_DIGIT -> handlePickDigit(key)
            KeyAction.SEGMENT -> handleSegment()
            KeyAction.CUSTOM_SYMBOL -> onShowCustomSymbols()
            KeyAction.CUSTOM_OPERATOR -> onShowCustomOperators()
            KeyAction.SHOW_SYMBOLS -> { flushComposing() }
            KeyAction.TOGGLE_LANG -> {
                flushComposing()
                shiftState = ShiftState.OFF
                if (lang == Lang.CN) {
                    lang = Lang.EN
                    layoutId = LayoutId.ALPHA
                } else {
                    lang = Lang.CN
                    layoutId = cnLayout
                }
            }
        }
        refreshCandidates()
        render()
    }

    private fun handlePickReading(key: Key) {
        val reading = key.output
        if (reading.isEmpty()) return
        val input = inputForReading(reading)
        if (literalIndices.isNotEmpty()) {
            val start = ninePendingIndex()
            if (layoutId != LayoutId.NINE || start < 0 ||
                !composing.substring(start).startsWith(input) ||
                (start until start + input.length).any { it in literalIndices }
            ) return
            lockLeadingLiterals()
            lockedReadings.add(reading)
            lockedInputLengths.add(input.length)
            activeStart += input.length
            history.addLast(StepKind.LOCK)
            return
        }
        val active = activeInput()
        val separatorPrefix = if (layoutId == LayoutId.ALPHA) active.takeWhile { it == '\'' }.length else 0
        if (!active.substring(separatorPrefix).startsWith(input)) return
        lockedReadings.add(reading)
        lockedInputLengths.add(separatorPrefix + input.length)
        activeStart = (activeStart + separatorPrefix + input.length).coerceAtMost(composing.length)
        history.addLast(StepKind.LOCK)
    }

    private fun ninePendingIndex(): Int =
        (activeStart until composing.length).firstOrNull { it !in literalIndices } ?: -1

    private fun lockLeadingLiterals() {
        while (activeStart < composing.length && activeStart in literalIndices) {
            lockedReadings.add(composing[activeStart].toString())
            lockedInputLengths.add(1)
            activeStart++
        }
    }

    private fun handlePickDigit(key: Key) {
        if (layoutId != LayoutId.NINE || mode() != Mode.PINYIN) return
        val at = ninePendingIndex()
        if (at < 0 || composing[at] !in '2'..'9' || key.output != composing[at].toString()) return
        lockLeadingLiterals()
        literalIndices.add(at)
        lockedReadings.add(key.output)
        lockedInputLengths.add(1)
        activeStart = at + 1
        history.addLast(StepKind.DIGIT_CHOICE)
    }

    private fun readingLocks(): List<ReadingLock> {
        var start = 0
        return lockedReadings.mapIndexed { index, reading ->
            val end = start + lockedInputLengths[index]
            ReadingLock(reading, start, end).also { start = end }
        }
    }

    private fun handleSegment() {
        if (composing.isEmpty()) return
        if (forcedCuts.add(composing.length)) history.addLast(StepKind.CUT)
    }

    private fun removeComposingCharAt(index: Int) {
        if (index !in composing.indices) return
        composing.deleteCharAt(index)
        val shifted = literalIndices
            .filter { it != index }
            .map { if (it > index) it - 1 else it }
        literalIndices.clear()
        literalIndices.addAll(shifted)
    }

    fun onPickCandidate(index: Int) {
        if (index !in candidates.indices) return
        val cand = candidates[index]
        when {
            cand in compositeCands -> commitCompositeCandidate(cand)
            cand in literalCands -> commitLiteralCandidate(cand)
            else -> {
                commitCandidate(cand)
            }
        }
        refreshCandidates()
        render()
    }

    private fun mode(): Mode = when {
        lang == Lang.CN && (layoutId == LayoutId.ALPHA || layoutId == LayoutId.NINE) -> Mode.PINYIN
        else -> Mode.DIRECT
    }

    private fun handleCommit(key: Key) {
        if (key.direct) {
            if (composing.isNotEmpty() || committedPrefix.isNotEmpty()) flushComposing()
            val text = if (key.verbatim) key.output else applyCase(key.output)
            host.commitText(text)
            if (shiftState == ShiftState.ONCE && key.output.any { it.isLetter() }) shiftState = ShiftState.OFF
            return
        }
        when (mode()) {
            Mode.PINYIN -> {
                composing.append(key.output); history.addLast(StepKind.DIGIT)
            }
            Mode.DIRECT -> {
                val text = applyCase(key.output)
                host.commitText(text)
                if (shiftState == ShiftState.ONCE && key.output.any { it.isLetter() }) shiftState = ShiftState.OFF
            }
        }
    }

    private fun handleBackspace() {
        if (composing.isEmpty()) {
            if (committedPrefix.isNotEmpty()) {
                val removeCount = Character.charCount(committedPrefix.codePointBefore(committedPrefix.length))
                committedPrefix.setLength(committedPrefix.length - removeCount)
                return
            }
            host.deleteBackward()
            return
        }
        val step = history.removeLastOrNull()
        when (step) {
            StepKind.LOCK, StepKind.DIGIT_CHOICE -> if (lockedReadings.isNotEmpty()) {
                lockedReadings.removeAt(lockedReadings.lastIndex)
                val inputLength = lockedInputLengths.removeAt(lockedInputLengths.lastIndex)
                activeStart = (activeStart - inputLength).coerceAtLeast(0)
                if (step == StepKind.DIGIT_CHOICE) literalIndices.remove(activeStart)
            }
            StepKind.CUT -> forcedCuts.remove(composing.length)
            StepKind.DIGIT, StepKind.LITERAL, null -> {
                removeComposingCharAt(composing.length - 1)
                forcedCuts.removeIf { it > composing.length }
                if (activeStart > composing.length) activeStart = composing.length
            }
        }
        if (composing.isEmpty() && committedPrefix.isNotEmpty()) flushComposing()
    }

    private fun rebuildHistory() {
        history.clear()
        for (i in 1..composing.length) {
            history.addLast(if (i - 1 in literalIndices) StepKind.LITERAL else StepKind.DIGIT)
            if (i in forcedCuts) history.addLast(StepKind.CUT)
        }
    }

    private fun handleClearComposing() {
        clearComposingState()
    }

    private fun handleSpace() {
        if (composing.isEmpty()) {
            if (committedPrefix.isNotEmpty()) { flushComposing(); return }
            host.commitText(" ")
            return
        }
        val pick = candidates.firstOrNull()
        when {
            pick != null && pick in compositeCands -> commitCompositeCandidate(pick)
            pick != null && pick in literalCands -> commitLiteralCandidate(pick)
            pick != null -> {
                commitCandidate(pick)
            }
            else -> { host.commitText(committedPrefix.toString() + rawComposingText()); clearComposingState() }
        }
    }

    private fun handleEnter() {
        if (composing.isNotEmpty() || committedPrefix.isNotEmpty()) {
            flushComposing()
        } else {
            host.performEnter()
        }
    }

    private fun commitCompositeCandidate(cand: Cand) {
        host.commitText(committedPrefix.toString() + cand.word)
        clearComposingState()
    }

    private fun commitLiteralCandidate(cand: Cand) {
        if (candidateStaysInPreedit(cand)) {
            committedPrefix.append(cand.word)
            consumeComposingPrefix(cand.coveredLen)
        } else {
            host.commitText(committedPrefix.toString() + cand.word)
            clearComposingState()
        }
    }

    private fun consumeComposingPrefix(count: Int) {
        val consumed = count.coerceIn(0, composing.length)
        composing.delete(0, consumed)
        val shiftedLiterals = literalIndices
            .filter { it >= consumed }
            .map { it - consumed }
        literalIndices.clear()
        literalIndices.addAll(shiftedLiterals)
        val shiftedCuts = forcedCuts.filter { it > consumed }.map { it - consumed }
        forcedCuts.clear()
        forcedCuts.addAll(shiftedCuts)
        lockedReadings.clear()
        lockedInputLengths.clear()
        activeStart = 0
        rebuildHistory()
    }

    private fun commitCandidate(cand: Cand) {
        if (candidateStaysInPreedit(cand)) {
            committedPrefix.append(cand.word)
            consumeComposingPrefix(cand.coveredLen)
        } else {
            val wholeWord = committedPrefix.toString() + cand.word
            host.commitText(wholeWord)
            clearComposingState()
        }
    }

    private fun candidateStaysInPreedit(cand: Cand): Boolean =
        cand.coveredLen in 1 until composing.length

    private fun switchLayout(id: LayoutId) {
        flushComposing()
        shiftState = ShiftState.OFF
        layoutId = id
        if (lang == Lang.CN && (id == LayoutId.NINE || id == LayoutId.ALPHA)) cnLayout = id
    }

    private fun flushComposing() {
        val prefix = committedPrefix.toString()
        if (composing.isNotEmpty()) {
            host.commitText(prefix + rawComposingText())
            clearComposingState()
        } else if (prefix.isNotEmpty()) {
            host.commitText(prefix)
            clearComposingState()
        }
    }

    private fun inputForReading(reading: String): String =
        if (layoutId == LayoutId.NINE) T9Pinyin.toT9(reading) else reading

    private fun activeInput(): String =
        if (activeStart < composing.length) composing.substring(activeStart) else ""

    private fun activeCuts(): List<Int> =
        forcedCuts.filter { it in (activeStart + 1)..composing.length }.map { it - activeStart }

    private fun chunked(digits: String, cuts: List<Int>): List<String> {
        if (cuts.isEmpty() || digits.isEmpty()) return listOf(digits)
        val out = ArrayList<String>(cuts.size + 1)
        var prev = 0
        for (c in cuts) if (c in (prev + 1) until digits.length) { out.add(digits.substring(prev, c)); prev = c }
        out.add(digits.substring(prev))
        return out
    }

    private fun fullLetters(): String {
        val active = if (layoutId == LayoutId.NINE) {
            chunked(activeInput(), activeCuts()).joinToString("") { T9Pinyin.preedit(it).replace("'", "") }
        } else {
            activeInput()
        }
        return lockedReadings.joinToString("") + active
    }

    private fun readingToInputBounds(): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        var readingPos = 0
        var inputPos = 0
        for ((index, reading) in lockedReadings.withIndex()) {
            val inputLength = lockedInputLengths[index]
            val leading = (inputLength - inputForReading(reading).length).coerceAtLeast(0)
            for (offset in reading.indices) {
                readingPos++
                map[readingPos] = inputPos + leading + offset + 1
            }
            inputPos += inputLength
        }
        val activeReading = if (layoutId == LayoutId.NINE) {
            chunked(activeInput(), activeCuts()).joinToString("") { T9Pinyin.preedit(it).replace("'", "") }
        } else {
            activeInput()
        }
        for (offset in activeReading.indices) {
            readingPos++
            inputPos++
            map[readingPos] = inputPos
        }
        map[readingPos] = composing.length
        return map
    }

    private fun rawComposingText(): String {
        if (composing.isEmpty()) return ""
        if (layoutId != LayoutId.NINE || lang != Lang.CN) return composing.toString()
        if (literalIndices.isEmpty()) return fullLetters()
        val raw = composing.toString()
        return mixedParts(raw, literalIndices).joinToString("") { part ->
            val text = raw.substring(part.start, part.end)
            if (part.literal) text else mixedSegmentReading(raw, part.start, part.end, readingLocks(), forcedCuts, true).replace("'", "")
        }
    }

    private fun clearComposingState() {
        composing.setLength(0)
        literalIndices.clear()
        candidates = emptyList()
        compositeCands = emptySet()
        literalCands = emptySet()
        lockedReadings.clear()
        lockedInputLengths.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        committedPrefix.setLength(0)
    }

    private fun refreshCandidates() {
        val req = buildDecodeRequest()
        applyDecodeResult(computeDecode(req))
    }

    private class DecodeRequest(
        val engine: CandidateEngine,
        val beforeCursor: String,
        val composingEmpty: Boolean,
        val mode: Mode,
        val raw: String,
        val literalIndices: Set<Int>,
        val readingLocks: List<ReadingLock>,
        val composingLen: Int,
        val lockedNonEmpty: Boolean,
        val full: String,
        val readingCuts: Set<Int>,
        val bounds: Map<Int, Int>,
        val isNine: Boolean,
        val forcedCuts: Set<Int>,
    )

    private class DecodeResult(
        val candidates: List<Cand>,
        val compositeCands: Set<Cand>,
        val literalCands: Set<Cand>,
    )

    private fun buildDecodeRequest(): DecodeRequest {
        val locked = mode() == Mode.PINYIN && composing.isNotEmpty() &&
            literalIndices.isEmpty() && lockedReadings.isNotEmpty()
        val full = if (locked) fullLetters() else ""
        val bounds = if (locked) readingToInputBounds() else emptyMap()
        val readingCuts = if (locked) {
            val lockCuts = ArrayList<Int>(lockedReadings.size); var acc = 0
            for (r in lockedReadings) { acc += r.length; if (acc < full.length) lockCuts.add(acc) }
            val forced = forcedCuts
                .filter { it in (activeStart + 1) until composing.length }
                .mapNotNull { inputCut -> bounds.entries.firstOrNull { it.value == inputCut }?.key }
            (forced + lockCuts).toSet()
        } else {
            emptySet()
        }
        val readsContext = if (composing.isNotEmpty()) {
            mode() == Mode.PINYIN
        } else {
            committedPrefix.isEmpty()
        }
        return DecodeRequest(
            engine = engine,
            beforeCursor = if (readsContext) host.textBeforeCursor(CALC_SCAN_LEN + 1).toString() else "",
            composingEmpty = composing.isEmpty(),
            mode = mode(),
            raw = composing.toString(),
            literalIndices = literalIndices.toSet(),
            readingLocks = readingLocks(),
            composingLen = composing.length,
            lockedNonEmpty = locked,
            full = full,
            readingCuts = readingCuts,
            bounds = bounds,
            isNine = layoutId == LayoutId.NINE,
            forcedCuts = forcedCuts.toSet(),
        )
    }

    private fun applyDecodeResult(r: DecodeResult) {
        candidates = r.candidates
        compositeCands = r.compositeCands
        literalCands = r.literalCands
    }

    private fun computeDecode(req: DecodeRequest): DecodeResult {
        var composite: Set<Cand> = emptySet()
        var literal: Set<Cand> = emptySet()
        val base = computeBase(req)
        val out = when {
            !req.composingEmpty && req.mode == Mode.PINYIN && req.literalIndices.isNotEmpty() -> {
                val mixed = computeMixed(req)
                composite = mixed.composite
                literal = mixed.literal
                mixed.candidates
            }
            else -> base
        }
        return DecodeResult(out, composite, literal)
    }

    private class MixedCandidates(
        val candidates: List<Cand>,
        val composite: Set<Cand>,
        val literal: Set<Cand>,
    )

    private fun computeMixed(req: DecodeRequest): MixedCandidates {
        val parts = mixedParts(req.raw, req.literalIndices)
        if (parts.isEmpty()) return MixedCandidates(emptyList(), emptySet(), emptySet())
        val context = req.beforeCursor.takeLast(CTX_SCAN_LEN)
        val assembled = buildString {
            for (part in parts) {
                val text = req.raw.substring(part.start, part.end)
                append(if (part.literal) text else assemblePinyinSegment(req, text, part.start, context))
            }
        }
        val whole = Cand(assembled, req.raw.length)
        val first = parts.first()
        val secondary = if (first.literal) {
            listOf(Cand(req.raw.substring(first.start, first.end), first.length))
        } else {
            segmentCandidates(req, req.raw.substring(first.start, first.end), first.start, context)
                .map { Cand(it.word, it.coveredLen + first.start, it.correctedReading) }
        }
        val candidates = (listOf(whole) + secondary).distinctBy { it.word to it.coveredLen }
        val literal = secondary.takeIf { first.literal }?.toSet() ?: emptySet()
        return MixedCandidates(candidates, setOf(whole), literal - whole)
    }

    private fun assemblePinyinSegment(
        req: DecodeRequest,
        segment: String,
        rawStart: Int,
        context: CharSequence,
    ): String {
        val out = StringBuilder()
        var at = 0
        while (at < segment.length) {
            val tail = segment.substring(at)
            val candidates = segmentCandidates(req, tail, rawStart + at, context)
            val top = candidates.firstOrNull()
            if (top == null || top.coveredLen <= 0) {
                out.append(displayPinyinSegment(tail, req.isNine, emptySet()))
                break
            }
            out.append(top.word)
            at += top.coveredLen.coerceAtMost(tail.length)
        }
        return out.toString()
    }

    private fun segmentCandidates(
        req: DecodeRequest,
        segment: String,
        rawStart: Int,
        context: CharSequence,
    ): List<Cand> {
        val cuts = req.forcedCuts
            .filter { it in (rawStart + 1) until (rawStart + segment.length) }
            .map { it - rawStart }
            .toSet()
        val segmentEnd = rawStart + segment.length
        val locks = req.readingLocks.filter { it.start >= rawStart && it.end <= segmentEnd }
        if (locks.isNotEmpty()) {
            val reading = mixedSegmentReading(req.raw, rawStart, segmentEnd, locks, req.forcedCuts, req.isNine)
                .replace("'", "")
            val lockCuts = locks.map { it.end - rawStart }.filter { it in 1 until segment.length }
            return req.engine.candidatesForLockedReadingCovered(reading, cuts + lockCuts, context)
        }
        var candidates = req.engine.candidatesCovered(segment, req.isNine, cuts, context)
        if (candidates.isEmpty() && req.isNine) {
            val prefix = T9Pinyin.longestDecodablePrefix(segment)
            if (prefix.length in 1 until segment.length) {
                candidates = req.engine.candidatesCovered(prefix, true, context = context)
            }
        }
        return candidates
    }

    private fun computeBase(req: DecodeRequest): List<Cand> {
        if (req.composingEmpty || req.mode != Mode.PINYIN || req.literalIndices.isNotEmpty()) return emptyList()
        val context = req.beforeCursor.takeLast(CTX_SCAN_LEN)
        return if (req.lockedNonEmpty) {
            val c = req.engine.candidatesForLockedReadingCovered(req.full, req.readingCuts, context)
                .map {
                    Cand(
                        it.word,
                        req.bounds[it.coveredLen] ?: it.coveredLen.coerceAtMost(req.composingLen),
                        it.correctedReading,
                    )
                }
            c
        } else {
            var c = req.engine.candidatesCovered(req.raw, req.isNine, req.forcedCuts, context)
            if (c.isEmpty() && req.isNine) {
                val pfx = T9Pinyin.longestDecodablePrefix(req.raw)
                if (pfx.length in 1 until req.raw.length) c = req.engine.candidatesCovered(pfx, true, context = context)
            }
            c
        }
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    private fun preeditText(): String {
        val prefix = committedPrefix.toString()
        if (composing.isEmpty()) return prefix
        val tail = if (mode() == Mode.PINYIN && literalIndices.isNotEmpty()) {
            mixedPreeditTail()
        } else if (mode() == Mode.PINYIN) {
            val locked = lockedReadings.joinToString("'")
            val rest = when {
                layoutId != LayoutId.NINE -> T9Pinyin.preeditLetters(activeInput(), activeCuts().toSet())
                else -> T9Pinyin.preedit(activeInput(), activeCuts().toSet())
            }
            when {
                locked.isEmpty() -> rest
                rest.isEmpty() -> locked
                else -> "$locked'${rest.trimStart('\'')}"
            }
        } else composing.toString()
        return prefix + tail
    }

    private fun mixedPreeditTail(): String {
        val raw = composing.toString()
        return mixedParts(raw, literalIndices).joinToString("'") { part ->
            val text = raw.substring(part.start, part.end)
            if (part.literal) {
                text
            } else {
                mixedSegmentReading(raw, part.start, part.end, readingLocks(), forcedCuts, layoutId == LayoutId.NINE)
            }
        }
    }

    private fun displayPinyinSegment(input: String, isNine: Boolean, cuts: Set<Int>): String =
        if (isNine) T9Pinyin.preedit(input, cuts) else T9Pinyin.preeditLetters(input, cuts)

    private fun mixedSegmentReading(
        raw: String,
        start: Int,
        end: Int,
        locks: List<ReadingLock>,
        cuts: Set<Int>,
        isNine: Boolean,
    ): String {
        val parts = ArrayList<String>()
        var at = start
        for (lock in locks) {
            if (lock.start != at || lock.end > end) continue
            parts.add(lock.reading)
            at = lock.end
        }
        if (at < end) {
            val localCuts = cuts.filter { it in (at + 1) until end }.map { it - at }.toSet()
            parts.add(displayPinyinSegment(raw.substring(at, end), isNine, localCuts))
        }
        return parts.joinToString("'")
    }

    internal fun nineLeftColumn(): List<Key> {
        if (composing.isEmpty()) return Layouts.ninePunctuation()
        val start = ninePendingIndex()
        if (start < 0) return emptyList()
        val end = minOf(
            forcedCuts.firstOrNull { it > start } ?: composing.length,
            literalIndices.firstOrNull { it > start } ?: composing.length,
        )
        val chunk = composing.substring(start, end)
        val readings = T9Pinyin.leftColumnReadings(chunk, NINE_LEFT_MAX)
        val visible = readings
        val digit = composing[start].takeIf { layoutId == LayoutId.NINE && it in '2'..'9' }
        return readingKeys(visible) + listOfNotNull(
            digit?.let { Key(it.toString(), action = KeyAction.PICK_DIGIT, weight = 0.85f) },
        )
    }

    private fun readingKeys(readings: List<String>): List<Key> {
        return readings.map { r ->
            Key(r, output = r, action = KeyAction.PICK_READING, weight = 0.85f)
        }
    }

    private fun render() {
        val v = view ?: return
        val layout = when (layoutId) {
            LayoutId.NINE -> Layouts.nine(nineLeftColumn(), composing.isNotEmpty())
            LayoutId.NUMPAD -> Layouts.numpad()
            else -> Layouts.forId(layoutId, lang, composing.isNotEmpty())
        }
        v.showKeyboard(layout, shifted, shiftState == ShiftState.LOCK, lang)
        val readings = expandedReadings()
        val preedit = preeditText()
        v.showCandidates(
            candidates.map { it.word },
            preedit,
            readings,
        )
    }

    internal fun shiftStateName(): String = shiftState.name

    internal fun expandedReadings(): List<String> {
        return expandedReadingsWithoutFocus()
    }

    private fun expandedReadingsWithoutFocus(): List<String> = when {
        literalIndices.isNotEmpty() -> emptyList()
        layoutId == LayoutId.ALPHA && mode() == Mode.PINYIN && composing.isNotEmpty() -> {
            val active = activeInput()
            val separatorPrefix = active.takeWhile { it == '\'' }.length
            val body = active.substring(separatorPrefix)
            val forcedEnd = activeCuts().firstOrNull { it > separatorPrefix }?.minus(separatorPrefix)
            val separatorEnd = body.indexOf('\'').takeIf { it >= 0 }
            val chunkEnd = listOfNotNull(forcedEnd, separatorEnd).minOrNull() ?: body.length
            val chunk = body.substring(0, chunkEnd.coerceIn(0, body.length))
            val next = T9Pinyin.leftColumnLetterReadings(chunk, NINE_LEFT_MAX)
            next
        }
        else -> nineLeftColumn().filter { it.action == KeyAction.PICK_READING }.map { it.label }
    }

    internal fun candidateWords(): List<String> = candidates.map { it.word }

    internal fun composingPrefix(): String = committedPrefix.toString()

    internal fun preeditForTest(): String = preeditText()

    fun onPickReadingIndex(index: Int) {
        val readings = expandedReadings()
        if (index !in readings.indices) return
        val reading = readings[index]
        handlePickReading(Key(reading, output = reading, action = KeyAction.PICK_READING))
        refreshCandidates()
        render()
    }

    private companion object {
        const val NINE_LEFT_MAX = 24
        const val CALC_SCAN_LEN = 32
        const val CTX_SCAN_LEN = 16
    }
}
