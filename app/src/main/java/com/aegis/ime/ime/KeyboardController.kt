package com.aegis.ime.ime

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
            KeyAction.TOGGLE_LANG -> {
                flushComposing()
                lang = if (lang == Lang.CN) Lang.EN else Lang.CN
            }
        }
        refreshCandidates()
        render()
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

    private fun render() {
        val v = view ?: return
        v.showKeyboard(Layouts.forId(layoutId, lang), shifted)
        v.showCandidates(candidates, composing.toString())
    }
}
