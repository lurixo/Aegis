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
import com.aegis.ime.engine.Calculator
import com.aegis.ime.engine.InputAssociations
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts

private enum class ShiftState { OFF, ONCE, LOCK }

private enum class Mode { PINYIN, DIRECT }

private enum class StepKind { DIGIT, LOCK, CUT }

class KeyboardController(
    private val host: ImeHost,
    private var engine: CandidateEngine,
) {
    private var lang = Lang.CN
    private var shiftState = ShiftState.OFF
    private val shifted get() = shiftState != ShiftState.OFF
    private var layoutId = LayoutId.ALPHA

    private var cnDefaultLayout = LayoutId.NINE

    private var cnLayout = LayoutId.NINE
    private val composing = StringBuilder()
    private var candidates: List<Cand> = emptyList()
    private var lastWord: String? = null

    private val committedPrefix = StringBuilder()

    private val lockedReadings = mutableListOf<String>()
    private var activeStart = 0

    private val forcedCuts = sortedSetOf<Int>()

    private val history = ArrayDeque<StepKind>()

    private var customSymbols: List<String> = emptyList()

    private var directCommitCands: Set<Cand> = emptySet()
    private var calcCand: Cand? = null
    private var calcExpr = ""

    private var learningBlocked = false

    var onShowEmoji: () -> Unit = {}
    var onShowClipboard: () -> Unit = {}
    var onShowEdit: () -> Unit = {}
    var onShowSymbols: () -> Unit = {}
    var onShowSettings: () -> Unit = {}
    var onShowCustomSymbols: () -> Unit = {}
    var onClosePanel: () -> Unit = {}

    private var view: InputView? = null

    fun attachView(v: InputView) {
        view = v
        render()
    }

    fun setEngine(newEngine: CandidateEngine) {
        engine = newEngine
        refreshCandidates()
        render()
    }

    fun setCustomSymbols(symbols: List<String>) {
        customSymbols = symbols
        render()
    }

    fun setLearningBlocked(blocked: Boolean) { learningBlocked = blocked }

    fun setCnDefaultLayout(id: LayoutId) { cnDefaultLayout = id }

    fun reset() {
        composing.setLength(0)
        candidates = emptyList()
        lockedReadings.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        shiftState = ShiftState.OFF
        cnLayout = cnDefaultLayout
        layoutId = if (lang == Lang.CN) cnDefaultLayout else LayoutId.ALPHA
        lastWord = null
        render()
    }

    internal fun activeLayoutId(): LayoutId = layoutId

    fun onKey(key: Key) {
        when (key.action) {
            KeyAction.COMMIT -> handleCommit(key)
            KeyAction.BACKSPACE -> handleBackspace()
            KeyAction.CLEAR_COMPOSING -> handleClearComposing()
            KeyAction.SPACE -> handleSpace()
            KeyAction.ENTER -> handleEnter()
            KeyAction.SHIFT -> shiftState = when (shiftState) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCK
                ShiftState.LOCK -> ShiftState.OFF
            }
            KeyAction.SWITCH_SYMBOLS -> switchLayout(LayoutId.SYMBOL)
            KeyAction.SWITCH_NUMBERS -> switchLayout(LayoutId.NUMBER)
            KeyAction.SWITCH_ALPHA -> switchLayout(LayoutId.ALPHA)
            KeyAction.SWITCH_NINE -> switchLayout(LayoutId.NINE)
            KeyAction.SWITCH_TEXT -> switchLayout(if (lang == Lang.CN) cnLayout else LayoutId.ALPHA)
            KeyAction.SWITCH_NUMPAD -> switchLayout(LayoutId.NUMPAD)
            KeyAction.PICK_READING -> handlePickReading(key)
            KeyAction.SEGMENT -> handleSegment()
            KeyAction.SHOW_EDIT -> onShowEdit()
            KeyAction.CUSTOM_SYMBOL -> onShowCustomSymbols()
            KeyAction.SHOW_SYMBOLS -> { flushComposing(); onShowSymbols() }
            KeyAction.TOGGLE_LANG -> {
                flushComposing()
                if (lang == Lang.CN) {
                    cnLayout = layoutId
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

    fun onBarFunction(f: BarFunction) {
        when (f) {
            BarFunction.BRAND -> onShowSettings()
            BarFunction.EMOJI -> onShowEmoji()
            BarFunction.EDIT -> onShowEdit()
            BarFunction.CLIPBOARD -> onShowClipboard()
        }
    }

    private fun handlePickReading(key: Key) {
        val reading = key.output
        if (reading.isEmpty()) return
        val digits = T9Pinyin.toT9(reading)
        if (!activeDigits().startsWith(digits)) return
        lockedReadings.add(reading)
        activeStart = (activeStart + digits.length).coerceAtMost(composing.length)
        history.addLast(StepKind.LOCK)
    }

    private fun handleSegment() {
        if (composing.isEmpty()) return
        if (forcedCuts.add(composing.length)) history.addLast(StepKind.CUT)
    }

    fun onBackspaceSwipe(up: Boolean): Boolean {
        if (up && composing.isNotEmpty()) {
            clearComposingState()
            render()
            return true
        }
        return false
    }

    fun onPickCandidate(index: Int) {
        if (index !in candidates.indices) return
        val cand = candidates[index]
        when {
            cand === calcCand -> {
                val live = Calculator.detect(host.textBeforeCursor(CALC_SCAN_LEN))
                if (live != null && live.expr == calcExpr && live.result == cand.word && !host.hasSelection()) {
                    host.replaceBeforeCursor(live.length, live.result)
                }
                clearComposingState(); lastWord = null
            }
            cand in directCommitCands -> {
                host.commitText(committedPrefix.toString() + cand.word)
                clearComposingState(); lastWord = null
            }
            else -> commitCandidate(cand)
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
            if (composing.isNotEmpty()) flushComposing()
            host.commitText(applyCase(key.output))
            if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
            lastWord = null
            return
        }
        when (mode()) {
            Mode.PINYIN -> { composing.append(key.output); history.addLast(StepKind.DIGIT) }
            Mode.DIRECT -> {
                host.commitText(applyCase(key.output))
                if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
                lastWord = null
            }
        }
    }

    private fun handleBackspace() {
        if (composing.isEmpty()) {
            if (committedPrefix.isNotEmpty()) {
                committedPrefix.setLength(committedPrefix.length - 1)
                if (committedPrefix.isEmpty()) lastWord = null
                return
            }
            if (host.hasSelection()) host.deleteSelection() else host.deleteBackward()
            lastWord = null
            return
        }
        when (history.removeLastOrNull()) {
            StepKind.LOCK -> if (lockedReadings.isNotEmpty()) {
                val r = lockedReadings.removeAt(lockedReadings.lastIndex)
                activeStart = (activeStart - T9Pinyin.toT9(r).length).coerceAtLeast(0)
            }
            StepKind.CUT -> forcedCuts.remove(composing.length)
            StepKind.DIGIT, null -> {
                composing.setLength(composing.length - 1)
                forcedCuts.removeIf { it > composing.length }
                if (activeStart > composing.length) activeStart = composing.length
            }
        }
    }

    private fun rebuildHistory() {
        history.clear()
        for (i in 1..composing.length) {
            history.addLast(StepKind.DIGIT)
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
            lastWord = null
            return
        }
        val pick = candidates.firstOrNull()
        if (pick != null) commitCandidate(pick)
        else { host.commitText(committedPrefix.toString() + rawComposingText()); clearComposingState() }
    }

    private fun handleEnter() {
        if (composing.isNotEmpty() || committedPrefix.isNotEmpty()) {
            flushComposing()
        } else {
            host.performEnter()
            lastWord = null
        }
    }

    private fun commitCandidate(cand: Cand) {
        if (!learningBlocked) engine.learn(lastWord, cand.word)
        lastWord = cand.word
        if (cand.coveredLen in 1 until composing.length) {
            committedPrefix.append(cand.word)
            composing.delete(0, cand.coveredLen)
            val shifted = forcedCuts.filter { it > cand.coveredLen }.map { it - cand.coveredLen }
            forcedCuts.clear(); forcedCuts.addAll(shifted)
            lockedReadings.clear()
            activeStart = 0
            candidates = emptyList()
            rebuildHistory()
        } else {
            host.commitText(committedPrefix.toString() + cand.word)
            clearComposingState()
        }
    }

    private fun switchLayout(id: LayoutId) {
        flushComposing()
        layoutId = id
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
        lastWord = null
    }

    private fun activeDigits(): String =
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

    private fun fullLetters(): String =
        lockedReadings.joinToString("") +
            chunked(activeDigits(), activeCuts()).joinToString("") { T9Pinyin.preedit(it).replace("'", "") }

    private fun readingLetterToDigit(): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        var letters = 0
        var digits = 0
        for (r in lockedReadings) {
            letters += r.length
            digits += T9Pinyin.toT9(r).length
            map[letters] = digits
        }
        for (chunk in chunked(activeDigits(), activeCuts())) {
            for (syl in T9Pinyin.preedit(chunk).split("'")) {
                if (syl.isEmpty()) continue
                letters += syl.length
                digits = (digits + T9Pinyin.toT9(syl).length).coerceAtMost(composing.length)
                map[letters] = digits
            }
        }
        map[letters] = composing.length
        return map
    }

    private fun rawComposingText(): String {
        if (composing.isEmpty()) return ""
        return if (layoutId == LayoutId.NINE && lang == Lang.CN) fullLetters() else composing.toString()
    }

    private fun clearComposingState() {
        composing.setLength(0)
        candidates = emptyList()
        lockedReadings.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        committedPrefix.setLength(0)
    }

    private fun refreshCandidates() {
        val base = baseCandidates()
        directCommitCands = emptySet()
        calcCand = null; calcExpr = ""
        candidates = when {
            composing.isNotEmpty() && mode() == Mode.PINYIN -> injectAssociations(base)
            composing.isEmpty() && committedPrefix.isEmpty() -> calcCandidates()
            else -> base
        }
    }

    private fun injectAssociations(base: List<Cand>): List<Cand> {
        val glyphs = InputAssociations.lookup(rawComposingText())
        if (glyphs.isEmpty()) return base
        val extra = glyphs.map { Cand(it, composing.length) }
        directCommitCands = extra.toSet()
        return when {
            base.isEmpty() -> extra
            else -> listOf(base.first()) + extra + base.drop(1)
        }
    }

    private fun calcCandidates(): List<Cand> {
        val match = Calculator.detect(host.textBeforeCursor(CALC_SCAN_LEN)) ?: return emptyList()
        val cand = Cand(match.result, 0)
        calcCand = cand; calcExpr = match.expr
        return listOf(cand)
    }

    private fun baseCandidates(): List<Cand> {
        if (composing.isEmpty()) return emptyList()
        val raw = composing.toString()
        val context = host.textBeforeCursor(CTX_SCAN_LEN)
        return when (mode()) {
            Mode.PINYIN -> if (lockedReadings.isNotEmpty()) {
                val bounds = readingLetterToDigit()
                engine.candidatesForReadingCovered(fullLetters(), context)
                    .map { Cand(it.word, bounds[it.coveredLen] ?: composing.length) }
            } else {
                val isNine = layoutId == LayoutId.NINE
                var c = engine.candidatesCovered(raw, isNine, forcedCuts, context)
                if (c.isEmpty() && isNine) {
                    val pfx = T9Pinyin.longestDecodablePrefix(raw)
                    if (pfx.length in 1 until raw.length) c = engine.candidatesCovered(pfx, true, context = context)
                }
                if (layoutId == LayoutId.ALPHA && c.none { it.word == raw }) c + Cand(raw, raw.length) else c
            }
            Mode.DIRECT -> emptyList()
        }
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    private fun preeditText(): String {
        val prefix = committedPrefix.toString()
        if (composing.isEmpty()) return prefix
        val tail = if (mode() == Mode.PINYIN && layoutId == LayoutId.NINE) {
            val locked = lockedReadings.joinToString("'")
            val rest = T9Pinyin.preedit(activeDigits(), activeCuts().toSet())
            when {
                locked.isEmpty() -> rest
                rest.isEmpty() -> locked
                else -> "$locked'$rest"
            }
        } else composing.toString()
        return prefix + tail
    }

    internal fun nineLeftColumn(): List<Key> {
        val w = 0.85f
        if (composing.isEmpty()) return Layouts.ninePunctuation(customSymbols)
        val active = activeDigits()
        if (active.isEmpty()) return emptyList()
        val firstCut = activeCuts().firstOrNull()
        val chunk = if (firstCut != null) active.substring(0, firstCut) else active
        return T9Pinyin.leftColumnReadings(chunk, NINE_LEFT_MAX)
            .map { Key(it, output = it, action = KeyAction.PICK_READING, weight = w) }
    }

    private fun render() {
        val v = view ?: return
        val layout = if (layoutId == LayoutId.NINE) Layouts.nine(lang, nineLeftColumn(), composing.isNotEmpty())
        else Layouts.forId(layoutId, lang)
        v.showKeyboard(layout, shifted, lang)
        v.showCandidates(candidates.map { it.word }, preeditText(), expandedReadings())
    }

    internal fun expandedReadings(): List<String> =
        nineLeftColumn().filter { it.action == KeyAction.PICK_READING }.map { it.label }

    internal fun candidateWords(): List<String> = candidates.map { it.word }

    internal fun composingPrefix(): String = committedPrefix.toString()

    internal fun preeditForTest(): String = preeditText()

    fun onPickReadingIndex(index: Int) {
        val readings = expandedReadings()
        if (index !in readings.indices) return
        handlePickReading(Key(readings[index], output = readings[index], action = KeyAction.PICK_READING))
        refreshCandidates()
        render()
    }

    fun onPanelBackspace() {
        if (composing.isEmpty()) return
        handleBackspace()
        refreshCandidates()
        render()
    }

    fun onPanelClear() {
        handleClearComposing()
        render()
    }

    private companion object {
        const val NINE_LEFT_MAX = 24
        const val CALC_SCAN_LEN = 32
        const val CTX_SCAN_LEN = 16
    }
}
