package com.aegis.ime.ime

import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.KeyboardLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts

/**
 * Input state machine. Owns the active layout, language, shift state and the composing buffer;
 * turns key taps into editor operations ([ImeHost]) and re-renders the [InputView].
 *
 * In CN + (ALPHA|NINE) letters/digits accumulate in [composing] and feed [engine]; everything
 * else commits directly. The engine is stubbed in P1 — only the plumbing is exercised here.
 */
class KeyboardController(
    private val host: ImeHost,
    private val engine: CandidateEngine,
) {
    private var lang = Lang.CN
    private var shifted = false
    private var layoutId = LayoutId.ALPHA
    private val composing = StringBuilder()
    private var candidates: List<String> = emptyList()

    private var view: InputView? = null

    fun attachView(v: InputView) {
        view = v
        render()
    }

    fun reset() {
        composing.setLength(0)
        candidates = emptyList()
        shifted = false
        layoutId = LayoutId.ALPHA
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
        render()
    }

    fun onPickCandidate(index: Int) {
        if (index in candidates.indices) {
            host.commitText(candidates[index])
            clearComposing()
            render()
        }
    }

    private fun composingMode(): Boolean =
        lang == Lang.CN && (layoutId == LayoutId.ALPHA || layoutId == LayoutId.NINE)

    private fun handleCommit(key: Key) {
        if (composingMode()) {
            composing.append(if (layoutId == LayoutId.NINE) key.output else applyCase(key.output))
            refreshCandidates()
        } else {
            host.commitText(applyCase(key.output))
        }
    }

    private fun handleBackspace() {
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - 1)
            refreshCandidates()
        } else {
            host.deleteBackward()
        }
    }

    private fun handleSpace() {
        if (composing.isNotEmpty()) {
            host.commitText(candidates.firstOrNull() ?: composing.toString())
            clearComposing()
        } else {
            host.commitText(" ")
        }
    }

    private fun handleEnter() {
        if (composing.isNotEmpty()) {
            flushComposing()
        } else {
            host.performEnter()
        }
    }

    private fun switchLayout(id: LayoutId) {
        flushComposing()
        layoutId = id
    }

    /** Commit any pending buffer verbatim (used when leaving a composing context). */
    private fun flushComposing() {
        if (composing.isNotEmpty()) {
            host.commitText(composing.toString())
            clearComposing()
        }
    }

    private fun clearComposing() {
        composing.setLength(0)
        candidates = emptyList()
    }

    private fun refreshCandidates() {
        candidates = if (composing.isEmpty()) {
            emptyList()
        } else {
            engine.candidates(composing.toString(), layoutId == LayoutId.NINE)
        }
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    private fun render() {
        val v = view ?: return
        v.showKeyboard(Layouts.forId(layoutId, lang), shifted)
        v.showCandidates(candidates, composing.toString())
    }
}
