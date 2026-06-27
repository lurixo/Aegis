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

/**
 * Input state machine. Owns the active layout, language, shift state and the composing buffer;
 * turns key taps into editor operations ([ImeHost]) and re-renders the [InputView].
 *
 * In CN + (ALPHA|NINE) letters/digits accumulate in [composing] and feed [engine]; on an empty
 * buffer the bar shows learned next-word predictions for [lastWord]. Committing a CN candidate
 * teaches the engine ([CandidateEngine.learn]) so user-preferred words rise over time.
 */
/** Shift key state: off, one-shot (next letter only), or caps-lock. */
private enum class ShiftState { OFF, ONCE, LOCK }

/** Input mode derived from language + layout. Only full-pinyin CN buffers; everything else is DIRECT. */
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

    /**
     * 9-key per-syllable selection (★E): [lockedReadings] are the syllable readings the user has picked
     * from the left column (letter form, e.g. ["hao"]); [activeStart] is where the still-unconfirmed
     * digits begin in [composing]. The left column shows the readings of the active (next) syllable, so
     * picking advances syllable-by-syllable instead of only ever choosing the first.
     */
    private val lockedReadings = mutableListOf<String>()
    private var activeStart = 0

    /** 分词/隔音: user-forced syllable boundaries — indices into [composing] where a word may
     *  not span. The decoder, preedit and reading column all honour these. See [handleSegment]. */
    private val forcedCuts = sortedSetOf<Int>()

    /** Extras-panel hooks wired by the IME service (it owns the InputConnection + Context). */
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

    /** Swap in the real engine once dictionaries finish loading off the main thread. */
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
                // English is 26-key only (issue #10): never leave the user on the 9-key in EN.
                if (lang == Lang.EN && layoutId == LayoutId.NINE) layoutId = LayoutId.ALPHA
            }
        }
        refreshCandidates()
        render()
    }

    /** Candidate-strip toolbar shortcut (issue #4). */
    fun onBarFunction(f: BarFunction) {
        when (f) {
            BarFunction.SWITCH_KBD -> {
                onClosePanel()
                // EN is 26-key only (issue #10); CN toggles 9-key ↔ 26-key.
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

    /**
     * 9-key left column (★E): tapping a reading LOCKS that syllable and advances to the next syllable's
     * readings — it does NOT commit any word. Re-ranks candidates under the locked prefix.
     */
    private fun handlePickReading(key: Key) {
        val reading = key.output
        if (reading.isEmpty()) return
        val digits = T9Pinyin.toT9(reading)
        if (!activeDigits().startsWith(digits)) return // reading must encode the active syllable's prefix
        lockedReadings.add(reading)
        activeStart = (activeStart + digits.length).coerceAtMost(composing.length)
    }

    /**
     * 分词/隔音: force a syllable boundary at the current input position. The
     * decoder won't let a word span it, the preedit splits there, and the reading column scopes to the
     * chunk — imposing a boundary WITHOUT forcing a particular reading (xi'an vs xian, long-string cuts).
     */
    private fun handleSegment() {
        if (composing.isEmpty()) return
        forcedCuts.add(composing.length)
    }

    /**
     * Backspace up-swipe (C): if there is pending pinyin in the bar, clear it (重输) — works in EVERY
     * layout. Returns true when consumed; otherwise the service does its field-level clear/restore (#5).
     */
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

    /** PINYIN = CN buffered (26/9-key). Everything else — EN letters, numbers, symbols — commits DIRECTly (D). */
    private fun mode(): Mode = when {
        lang == Lang.CN && (layoutId == LayoutId.ALPHA || layoutId == LayoutId.NINE) -> Mode.PINYIN
        else -> Mode.DIRECT
    }

    private fun handleCommit(key: Key) {
        // Number row / symbol keys always go straight to the editor, even mid-pinyin (resolve first).
        if (key.direct) {
            if (composing.isNotEmpty()) flushComposing()
            host.commitText(applyCase(key.output))
            if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
            lastWord = null
            return
        }
        when (mode()) {
            Mode.PINYIN -> composing.append(key.output) // T9/pinyin buffer (lowercase); locked syllables persist
            Mode.DIRECT -> {
                // EN letters / numbers / symbols go straight to the editor (D), with shift applied.
                host.commitText(applyCase(key.output))
                if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
                lastWord = null
            }
        }
    }

    private fun handleBackspace() {
        if (composing.isNotEmpty()) {
            // 分词 first: backspace at a freshly forced boundary undoes the cut, not a digit.
            if (forcedCuts.remove(composing.length)) return
            composing.setLength(composing.length - 1)
            forcedCuts.removeIf { it > composing.length }
            // ★E: editing the buffer invalidates the locked syllable readings — re-derive from scratch.
            lockedReadings.clear()
            activeStart = 0
        } else {
            host.deleteBackward()
            lastWord = null
        }
    }

    /** 9-key "重输": drop the pending pinyin + candidates without touching committed text. */
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

    /**
     * Commit a CN candidate and teach the engine. ★E: if the candidate covers only part of the buffer
     * (its reading is a prefix of the input), commit that part and keep composing the remaining digits.
     */
    private fun commitCandidate(cand: Cand) {
        host.commitText(cand.word)
        engine.learn(lastWord, cand.word)
        lastWord = cand.word
        if (cand.coveredLen in 1 until composing.length) {
            composing.delete(0, cand.coveredLen)
            // ★E×分词: drop consumed cuts, shift the rest left by the consumed length.
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

    /**
     * Commit the pending buffer as raw text (not a learned word): the typed letters on the 26-key,
     * the decoded pinyin (no separators) on the 9-key. Drives both layout switches and Enter (#9).
     */
    private fun flushComposing() {
        if (composing.isNotEmpty()) {
            host.commitText(rawComposingText())
            clearComposingState()
        }
        lastWord = null
    }

    /** The active (still-unconfirmed) digits of the 9-key buffer, after any locked syllables. */
    private fun activeDigits(): String =
        if (activeStart < composing.length) composing.substring(activeStart) else ""

    /** Forced-cut offsets within the active tail, relative to [activeDigits] (★分词); includes a boundary
     *  at the very end so the 隔音符 shows the moment 分词 is pressed. */
    private fun activeCuts(): List<Int> =
        forcedCuts.filter { it in (activeStart + 1)..composing.length }.map { it - activeStart }

    /** Split [digits] at the (ascending, in-range) cut offsets into independent chunks. */
    private fun chunked(digits: String, cuts: List<Int>): List<String> {
        if (cuts.isEmpty() || digits.isEmpty()) return listOf(digits)
        val out = ArrayList<String>(cuts.size + 1)
        var prev = 0
        for (c in cuts) if (c in (prev + 1) until digits.length) { out.add(digits.substring(prev, c)); prev = c }
        out.add(digits.substring(prev))
        return out
    }

    /** Full pinyin letters the 9-key buffer represents: locked readings + each forced chunk decoded. */
    private fun fullLetters(): String =
        lockedReadings.joinToString("") +
            chunked(activeDigits(), activeCuts()).joinToString("") { T9Pinyin.preedit(it).replace("'", "") }

    /** The raw text the composing buffer represents (letters on 26-key, decoded pinyin on 9-key). */
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
                        // ★E: syllable(s) locked via the left column — decode the combined full pinyin.
                        engine.candidatesForReading(fullLetters()).map { Cand(it, composing.length) }
                    } else {
                        val isNine = layoutId == LayoutId.NINE
                        var c = engine.candidatesCovered(raw, isNine, forcedCuts)
                        // ★N: mid-syllable the full digit buffer may not segment yet — fall back to the
                        // longest decodable syllable prefix so the grid keeps the confirmed words
                        // (你/你说…) instead of going blank when a half-typed syllable trails.
                        if (c.isEmpty() && isNine) {
                            val pfx = T9Pinyin.longestDecodablePrefix(raw)
                            if (pfx.length in 1 until raw.length) c = engine.candidatesCovered(pfx, true)
                        }
                        if (layoutId == LayoutId.ALPHA && c.none { it.word == raw }) c + Cand(raw, raw.length) else c
                    }
                    Mode.DIRECT -> emptyList()
                }
            }
            // ★S: no next-word prediction. An empty buffer shows the toolbar, never a "ghost"
            // suggestion (the old predict(lastWord) display was un-dismissable by 重输 because clear
            // left lastWord set). Frequency learning (engine.learn / UserModel) is intentionally kept;
            // only the auto-display of successors is removed.
            else -> emptyList()
        }
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    /** Preedit pinyin tab: locked syllable readings + decoded active tail (9-key), else typed letters. */
    private fun preeditText(): String {
        if (composing.isEmpty()) return ""
        if (mode() == Mode.PINYIN && layoutId == LayoutId.NINE) {
            val locked = lockedReadings.joinToString("'")
            // ★分词: render the active tail with its forced boundaries as 隔音符 ' (incl. a trailing one).
            val rest = T9Pinyin.preedit(activeDigits(), activeCuts().toSet())
            return when {
                locked.isEmpty() -> rest
                rest.isEmpty() -> locked
                else -> "$locked'$rest"
            }
        }
        return composing.toString()
    }

    /**
     * 9-key left column (★E): readings of the ACTIVE (next-unconfirmed) syllable while
     * composing (tap → lock that syllable and advance, no commit), common punctuation when idle.
     *
     * the column shows ONLY real readings (canonical syllables + 首键字母, via
     * [T9Pinyin.leftColumnReadings]) and its length follows the option count: NEVER pad with empty
     * placeholder boxes, punctuation or junk letters, and never the
     * fixed 4 slots. [Layouts.nine] places exactly `left.size` peanut cells so the column shrinks to fit.
     * Punctuation only appears at rest / once every syllable is locked (the early returns) — the two are
     * mutually exclusive. `internal` so the option set can be asserted in unit tests.
     */
    internal fun nineLeftColumn(): List<Key> {
        val w = 0.85f
        if (composing.isEmpty()) return Layouts.defaultNineLeft()
        val active = activeDigits()
        if (active.isEmpty()) return Layouts.defaultNineLeft() // every syllable locked → resting punctuation
        // ★分词: the active syllable is bounded by the first forced cut in the active region.
        val firstCut = activeCuts().firstOrNull()
        val chunk = if (firstCut != null) active.substring(0, firstCut) else active
        return T9Pinyin.leftColumnReadings(chunk, NINE_LEFT_SLOTS)
            .map { Key(it, output = it, action = KeyAction.PICK_READING, weight = w) }
    }

    private fun render() {
        val v = view ?: return
        val layout = if (layoutId == LayoutId.NINE) Layouts.nine(lang, nineLeftColumn(), composing.isNotEmpty())
        else Layouts.forId(layoutId, lang)
        v.showKeyboard(layout, shifted)
        v.showCandidates(candidates.map { it.word }, preeditText())
    }

    private companion object {
        /** Max readings shown in the 9-key left peanut (its geometry stacks up to this many cells). */
        const val NINE_LEFT_SLOTS = 4
    }
}
