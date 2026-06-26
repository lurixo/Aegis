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

import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts

private enum class ShiftState { OFF, ONCE, LOCK }

private enum class Mode { PINYIN, ENGLISH, DIRECT }

class KeyboardController(
    private val host: ImeHost,
    private var engine: CandidateEngine,
) {
    private var lang = Lang.CN
    private var shiftState = ShiftState.OFF
    private val shifted get() = shiftState != ShiftState.OFF
    private var layoutId = LayoutId.ALPHA
    private val composing = StringBuilder()
    private var candidates: List<String> = emptyList()
    private var lastWord: String? = null

    var onShowEmoji: () -> Unit = {}
    var onShowClipboard: () -> Unit = {}
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
        shiftState = ShiftState.OFF
        layoutId = LayoutId.ALPHA
        lastWord = null
        render()
    }

    fun onKey(key: Key) {
        when (key.action) {
            KeyAction.COMMIT -> handleCommit(key)
            KeyAction.BACKSPACE -> handleBackspace()
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
            KeyAction.TOGGLE_LANG -> {
                flushComposing()
                lang = if (lang == Lang.CN) Lang.EN else Lang.CN
            }
        }
        refreshCandidates()
        render()
    }

    fun onBarFunction(f: BarFunction) {
        when (f) {
            BarFunction.SWITCH_KBD -> {
                onClosePanel()
                switchLayout(if (layoutId == LayoutId.NINE) LayoutId.ALPHA else LayoutId.NINE)
            }
            BarFunction.NUMPAD -> { onClosePanel(); switchLayout(LayoutId.NUMPAD) }
            BarFunction.EMOJI -> { onShowEmoji(); return }
            BarFunction.CLIPBOARD -> { onShowClipboard(); return }
        }
        refreshCandidates()
        render()
    }

    private fun handlePickReading(key: Key) {
        val letters = key.output
        if (letters.isEmpty()) return
        val word = engine.candidatesForReading(letters).firstOrNull() ?: letters
        commitWord(word)
    }

    fun onPickCandidate(index: Int) {
        if (index !in candidates.indices) return
        val word = candidates[index]
        if (mode() == Mode.ENGLISH) {
            host.commitText("$word ")
            clearComposingState()
            lastWord = null
        } else {
            commitWord(word)
        }
        refreshCandidates()
        render()
    }

    private fun mode(): Mode = when {
        lang == Lang.CN && (layoutId == LayoutId.ALPHA || layoutId == LayoutId.NINE) -> Mode.PINYIN
        lang == Lang.EN && layoutId == LayoutId.ALPHA -> Mode.ENGLISH
        else -> Mode.DIRECT
    }

    private fun handleCommit(key: Key) {
        when (mode()) {
            Mode.PINYIN -> composing.append(key.output)
            Mode.ENGLISH -> {
                composing.append(applyCase(key.output))
                if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
            }
            Mode.DIRECT -> {
                host.commitText(applyCase(key.output))
                if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
                lastWord = null
            }
        }
    }

    private fun handleBackspace() {
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - 1)
        } else {
            host.deleteBackward()
            lastWord = null
        }
    }

    private fun handleSpace() {
        if (composing.isEmpty()) {
            host.commitText(" ")
            lastWord = null
            return
        }
        when (mode()) {
            Mode.ENGLISH -> { host.commitText(composing.toString() + " "); clearComposingState(); lastWord = null }
            else -> {
                val pick = candidates.firstOrNull()
                if (pick != null) commitWord(pick) else { host.commitText(composing.toString()); clearComposingState() }
            }
        }
    }

    private fun handleEnter() {
        if (composing.isNotEmpty()) {
            flushComposing()
        } else {
            host.performEnter()
            lastWord = null
        }
    }

    private fun commitWord(word: String) {
        host.commitText(word)
        engine.learn(lastWord, word)
        lastWord = word
        clearComposingState()
    }

    private fun switchLayout(id: LayoutId) {
        flushComposing()
        layoutId = id
    }

    private fun flushComposing() {
        if (composing.isNotEmpty()) {
            host.commitText(composing.toString())
            clearComposingState()
        }
        lastWord = null
    }

    private fun clearComposingState() {
        composing.setLength(0)
        candidates = emptyList()
    }

    private fun refreshCandidates() {
        candidates = when {
            composing.isNotEmpty() -> {
                val raw = composing.toString()
                when (mode()) {
                    Mode.PINYIN -> engine.candidates(raw, layoutId == LayoutId.NINE)
                        .let { if (layoutId == LayoutId.ALPHA && raw !in it) it + raw else it }
                    Mode.ENGLISH -> engine.english(raw).let { if (raw !in it) it + raw else it }
                    Mode.DIRECT -> emptyList()
                }
            }
            mode() == Mode.PINYIN -> engine.predict(lastWord)
            else -> emptyList()
        }
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    private fun preeditText(): String {
        if (composing.isEmpty()) return ""
        return if (mode() == Mode.PINYIN && layoutId == LayoutId.NINE) {
            T9Pinyin.preedit(composing.toString())
        } else {
            composing.toString()
        }
    }

    private fun nineLeftColumn(): List<Key> {
        val w = 0.85f
        if (composing.isEmpty()) return Layouts.defaultNineLeft()
        val keys = ArrayList<Key>(4)
        for (r in T9Pinyin.firstSyllableOptions(composing.toString(), 4)) {
            keys.add(Key(r, output = r, action = KeyAction.PICK_READING, weight = w))
        }
        val pads = Layouts.defaultNineLeft()
        var i = 0
        while (keys.size < 4) keys.add(pads[i++])
        return keys
    }

    private fun render() {
        val v = view ?: return
        val layout = if (layoutId == LayoutId.NINE) Layouts.nine(lang, nineLeftColumn())
        else Layouts.forId(layoutId, lang)
        v.showKeyboard(layout, shifted)
        v.showCandidates(candidates, preeditText())
    }
}
