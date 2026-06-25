package com.aegis.ime.ime

import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts

class KeyboardController(
    private val host: ImeHost,
    private var engine: CandidateEngine,
) {
    private var lang = Lang.CN
    private var shifted = false
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
        shifted = false
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
            KeyAction.SHIFT -> shifted = !shifted
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
        commitWord(candidates[index])
        refreshCandidates()
        render()
    }

    private fun composingMode(): Boolean =
        lang == Lang.CN && (layoutId == LayoutId.ALPHA || layoutId == LayoutId.NINE)

    private fun handleCommit(key: Key) {
        if (composingMode()) {
            composing.append(if (layoutId == LayoutId.NINE) key.output else applyCase(key.output))
        } else {
            host.commitText(applyCase(key.output))
            lastWord = null
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
        if (composing.isNotEmpty()) {
            val pick = candidates.firstOrNull()
            if (pick != null) commitWord(pick) else { host.commitText(composing.toString()); clearComposingState() }
        } else {
            host.commitText(" ")
            lastWord = null
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
            composing.isNotEmpty() -> engine.candidates(composing.toString(), layoutId == LayoutId.NINE)
            composingMode() -> engine.predict(lastWord)
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
