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

class KeyboardController(
    private val host: ImeHost,
    private var engine: CandidateEngine,
) {
    private var lang = Lang.CN
    private var shiftState = ShiftState.OFF
    private val shifted get() = shiftState != ShiftState.OFF
    private var layoutId = LayoutId.ALPHA
    private val composing = StringBuilder()
    private var candidates: List<Cand> = emptyList()
    private var lastWord: String? = null

    private val lockedReadings = mutableListOf<String>()
    private var activeStart = 0

    private val forcedCuts = sortedSetOf<Int>()

    var onShowEmoji: () -> Unit = {}
    var onShowClipboard: () -> Unit = {}
    var onShowEdit: () -> Unit = {}
    var onShowSettings: () -> Unit = {}
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

    fun reset() {
        composing.setLength(0)
        candidates = emptyList()
        lockedReadings.clear()
        activeStart = 0
        forcedCuts.clear()
        shiftState = ShiftState.OFF
        layoutId = LayoutId.ALPHA
        lastWord = null
        render()
    }

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
            KeyAction.SWITCH_NUMPAD -> switchLayout(LayoutId.NUMPAD)
            KeyAction.PICK_READING -> handlePickReading(key)
            KeyAction.SEGMENT -> handleSegment()
            KeyAction.SHOW_EDIT -> onShowEdit()
            KeyAction.TOGGLE_LANG -> {
                flushComposing()
                lang = if (lang == Lang.CN) Lang.EN else Lang.CN
                if (lang == Lang.EN && layoutId == LayoutId.NINE) layoutId = LayoutId.ALPHA
            }
        }
        refreshCandidates()
        render()
    }

    fun onBarFunction(f: BarFunction) {
        when (f) {
            BarFunction.SWITCH_KBD -> {
                onClosePanel()
                val target = if (lang == Lang.EN || layoutId == LayoutId.NINE) LayoutId.ALPHA else LayoutId.NINE
                switchLayout(target)
            }
            BarFunction.NUMPAD -> { onClosePanel(); switchLayout(LayoutId.NUMPAD) }
            BarFunction.SETTINGS -> { onShowSettings(); return }
            BarFunction.EMOJI -> { onShowEmoji(); return }
            BarFunction.EDIT -> { onShowEdit(); return }
            BarFunction.CLIPBOARD -> { onShowClipboard(); return }
        }
        refreshCandidates()
        render()
    }

    private fun handlePickReading(key: Key) {
        val reading = key.output
        if (reading.isEmpty()) return
        val digits = T9Pinyin.toT9(reading)
        if (!activeDigits().startsWith(digits)) return
        lockedReadings.add(reading)
        activeStart = (activeStart + digits.length).coerceAtMost(composing.length)
    }

    private fun handleSegment() {
        if (composing.isEmpty()) return
        forcedCuts.add(composing.length)
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
        commitCandidate(candidates[index])
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
            Mode.PINYIN -> composing.append(key.output)
            Mode.DIRECT -> {
                host.commitText(applyCase(key.output))
                if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
                lastWord = null
            }
        }
    }

    private fun handleBackspace() {
        if (composing.isNotEmpty()) {
            if (forcedCuts.remove(composing.length)) return
            composing.setLength(composing.length - 1)
            forcedCuts.removeIf { it > composing.length }
            lockedReadings.clear()
            activeStart = 0
        } else {
            host.deleteBackward()
            lastWord = null
        }
    }

    private fun handleClearComposing() {
        clearComposingState()
    }

    private fun handleSpace() {
        if (composing.isEmpty()) {
            host.commitText(" ")
            lastWord = null
            return
        }
        val pick = candidates.firstOrNull()
        if (pick != null) commitCandidate(pick)
        else { host.commitText(rawComposingText()); clearComposingState() }
    }

    private fun handleEnter() {
        if (composing.isNotEmpty()) {
            flushComposing()
        } else {
            host.performEnter()
            lastWord = null
        }
    }

    private fun commitCandidate(cand: Cand) {
        host.commitText(cand.word)
        engine.learn(lastWord, cand.word)
        lastWord = cand.word
        if (cand.coveredLen in 1 until composing.length) {
            composing.delete(0, cand.coveredLen)
            val shifted = forcedCuts.filter { it > cand.coveredLen }.map { it - cand.coveredLen }
            forcedCuts.clear(); forcedCuts.addAll(shifted)
            lockedReadings.clear()
            activeStart = 0
            candidates = emptyList()
        } else {
            clearComposingState()
        }
    }

    private fun switchLayout(id: LayoutId) {
        flushComposing()
        layoutId = id
    }

    private fun flushComposing() {
        if (composing.isNotEmpty()) {
            host.commitText(rawComposingText())
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
    }

    private fun refreshCandidates() {
        candidates = when {
            composing.isNotEmpty() -> {
                val raw = composing.toString()
                when (mode()) {
                    Mode.PINYIN -> if (lockedReadings.isNotEmpty()) {
                        engine.candidatesForReading(fullLetters()).map { Cand(it, composing.length) }
                    } else {
                        val isNine = layoutId == LayoutId.NINE
                        var c = engine.candidatesCovered(raw, isNine, forcedCuts)
                        if (c.isEmpty() && isNine) {
                            val pfx = T9Pinyin.longestDecodablePrefix(raw)
                            if (pfx.length in 1 until raw.length) c = engine.candidatesCovered(pfx, true)
                        }
                        if (layoutId == LayoutId.ALPHA && c.none { it.word == raw }) c + Cand(raw, raw.length) else c
                    }
                    Mode.DIRECT -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    private fun preeditText(): String {
        if (composing.isEmpty()) return ""
        if (mode() == Mode.PINYIN && layoutId == LayoutId.NINE) {
            val locked = lockedReadings.joinToString("'")
            val rest = T9Pinyin.preedit(activeDigits(), activeCuts().toSet())
            return when {
                locked.isEmpty() -> rest
                rest.isEmpty() -> locked
                else -> "$locked'$rest"
            }
        }
        return composing.toString()
    }

    private fun nineLeftColumn(): List<Key> {
        val w = 0.85f
        if (composing.isEmpty()) return Layouts.defaultNineLeft()
        val active = activeDigits()
        if (active.isEmpty()) return Layouts.defaultNineLeft()
        val firstCut = activeCuts().firstOrNull()
        val chunk = if (firstCut != null) active.substring(0, firstCut) else active
        val keys = ArrayList<Key>(4)
        for (r in T9Pinyin.firstSyllableOptions(chunk, 4)) {
            keys.add(Key(r, output = r, action = KeyAction.PICK_READING, weight = w))
        }
        val pads = Layouts.defaultNineLeft()
        var i = 0
        while (keys.size < 4) keys.add(pads[i++])
        return keys
    }

    private fun render() {
        val v = view ?: return
        val layout = if (layoutId == LayoutId.NINE) Layouts.nine(lang, nineLeftColumn(), composing.isNotEmpty())
        else Layouts.forId(layoutId, lang)
        v.showKeyboard(layout, shifted)
        v.showCandidates(candidates.map { it.word }, preeditText())
    }
}
