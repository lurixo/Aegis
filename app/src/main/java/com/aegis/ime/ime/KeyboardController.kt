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
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.Calculator
import com.aegis.ime.engine.InputAssociations
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
 * buffer the bar shows the inline calculator (for an arithmetic expression) else learned next-word
  * Chinese IME behavior note.
 * engine ([CandidateEngine.learn]) so user-preferred words rise over time.
 */
/** Shift key state: off, one-shot (next letter only), or caps-lock. */
private enum class ShiftState { OFF, ONCE, LOCK }

/** Input mode derived from language + layout. Only full-pinyin CN buffers; everything else is DIRECT. */
private enum class Mode { PINYIN, DIRECT }

/**
  * Chinese IME behavior note.
  */
private enum class StepKind { DIGIT, LOCK, CUT }

class KeyboardController(
    private val host: ImeHost,
    private var engine: CandidateEngine,
) {
    private data class LearnEvent(val prevWord: String?, val word: String, val prefixEnd: Int)

    private var lang = Lang.CN
    private var shiftState = ShiftState.OFF
    private val shifted get() = shiftState != ShiftState.OFF
    private var layoutId = LayoutId.ALPHA

    /**
      * Chinese IME behavior note.
     * screen; the IME service pushes the pref via [setCnDefaultLayout]). EN is always 26-key; [reset]
     * applies this at the start of each input session.
     */
    private var cnDefaultLayout = LayoutId.NINE

    /**
      * Chinese IME behavior note.
     * NOT silently demote a 9-key user to 26-key (B5): captured when leaving CN, seeded to the default
     * each input session, so it also preserves a manual 9↔26 toolbar switch across one EN excursion.
     */
    private var cnLayout = LayoutId.NINE
    private val composing = StringBuilder()
    private var candidates: List<Cand> = emptyList()
    private var lastWord: String? = null

    /**
     * S1(c) (debug.12): the CONFIRMED prefix of the multi-syllable word currently being assembled. A
     * partial candidate pick (its reading covers only part of the buffer) appends its word here and keeps
     * decoding the remainder — it does NOT reach the editor. The prefix is rendered at the LEFTMOST of the
     * candidate strip (the preedit tab) while the rest is still being chosen; the whole word lands in the
     * editor in ONE [ImeHost.commitText] only when it completes (full pick / flush / space). This replaces
      * Chinese IME behavior note.
     */
    private val committedPrefix = StringBuilder()

    /**
     * 9-key per-syllable selection (★E): [lockedReadings] are the syllable readings the user has picked
     * from the left column (letter form, e.g. ["hao"]); [activeStart] is where the still-unconfirmed
     * digits begin in [composing]. The left column shows the readings of the active (next) syllable, so
     * picking advances syllable-by-syllable instead of only ever choosing the first.
     */
    private val lockedReadings = mutableListOf<String>()
    private var activeStart = 0

    /**
     *  not span. The decoder, preedit and reading column all honour these. See [handleSegment]. */
    private val forcedCuts = sortedSetOf<Int>()

    /**
     *  digit, a reading lock (left-column pick), or a forced cut — never dropping a whole locked syllable. */
    private val history = ArrayDeque<StepKind>()

    private data class PreeditChoiceUndo(
        val composing: String,
        val committedPrefix: String,
        val lockedReadings: List<String>,
        val activeStart: Int,
        val forcedCuts: Set<Int>,
        val history: List<StepKind>,
        val drillSyllable: Int,
        val drillChoices: Map<Int, String>,
        val deferredLearnEvents: List<LearnEvent>,
        val lastWord: String?,
        val inputEpoch: Long,
    )

    private val preeditChoiceUndo = ArrayDeque<PreeditChoiceUndo>()
    private val deferredLearnEvents = ArrayDeque<LearnEvent>()
    private var inputEpoch = 0L

    /**
     * UI-2 (debug.13/debug.19): on the 26-key expanded screen the left column shows only the first unresolved
     * syllable. Tapping it sets [drillSyllable] to 0 and the candidate grid shows that syllable's complete
     * homophone set ([CandidateEngine.homophonesForReadingAt], uncapped). Buffer changes clear it back to the
     * normal candidate grid.
     */
    private var drillSyllable = -1

    /**
     * 26-key homophone choices keyed by the current segmentation index. debug.19 keeps the visible column
     * left-to-right, so normal UI picks only index 0; the map remains for the shared left-prefix commit path.
     */
    private val drillChoices = HashMap<Int, String>()

    /** Chinese IME behavior note. */
    private var customSymbols: List<String> = emptyList()

    /** I2: the user's custom numpad operators (injected from prefs), appended to the operator scroll column. */
    private var customOperators: List<String> = emptyList()

    /** U23: associated emoji/symbol candidates in the current list — committed directly (no pinyin learn). */
    private var directCommitCands: Set<Cand> = emptySet()
    /** C5: next-word prediction candidates on an empty buffer — picking one commits it and chains [lastWord]. */
    private var predictionCands: Set<Cand> = emptySet()
    /** U25: the inline-calculator candidate (if any) + the expression it was computed from. The expression
     *  is re-validated against the LIVE text-before-cursor at pick time (M-3) so a moved cursor can never
     *  make the replace delete unrelated characters. */
    private var calcCand: Cand? = null
    private var calcExpr = ""
    private var calcResult = "" // I1: the bare result string ("2"); the candidate shows "=2" and appends "=2"

    /** M-3/L-3: when the focused field is a password / opts out of personalized learning, never learn. */
    private var learningBlocked = false

    /**
     *  (default OFF since debug.17); this initial true is only the pre-push fallback and is overridden before
     *  any input. When off, next-word predictions are not shown. */
    private var associationsEnabled = true

    /** E4 hot-toggle (debug.16): last fuzzy rule set the service pushed (null until first push). Re-applied on
      * Chinese IME behavior note.
      */
    private var pushedFuzzyRules: Set<String>? = null

    /** Extras-panel hooks wired by the IME service (it owns the InputConnection + Context). */
    var onShowEmoji: () -> Unit = {}
    var onShowClipboard: () -> Unit = {}
    var onShowEdit: () -> Unit = {}
    var onShowSymbols: () -> Unit = {}
    var onShowSettings: () -> Unit = {}
    var onShowCustomSymbols: () -> Unit = {} // Chinese IME behavior note.
    var onShowCustomOperators: () -> Unit = {} // Chinese IME behavior note.
    var onClosePanel: () -> Unit = {}

    private var view: InputView? = null

    fun attachView(v: InputView) {
        view = v
        render()
    }

    /** Swap in the real engine once dictionaries finish loading off the main thread. */
    fun setEngine(newEngine: CandidateEngine) {
        engine = newEngine
        // E4 hot-toggle (debug.16): fuzzy rules live INSIDE the engine, so re-apply the last pushed set across a
        // swap — otherwise a hot-reloaded engine (built with a stale build-time snapshot) could silently revert
        // Chinese IME behavior note.
        pushedFuzzyRules?.let { newEngine.setFuzzyRules(it) }
        refreshCandidates()
        render()
    }

    /** Chinese IME behavior note. */
    fun setCustomSymbols(symbols: List<String>) {
        customSymbols = symbols
        render()
    }

    /** I2: set the user's custom numpad operators (from prefs) and re-render the operator column. */
    fun setCustomOperators(operators: List<String>) {
        customOperators = operators
        render()
    }

    /** M-3/L-3 privacy: when true (password / NO_PERSONALIZED_LEARNING field), commits are NOT learned. */
    fun setLearningBlocked(blocked: Boolean) { learningBlocked = blocked }

    /** B5: choose the CN default keyboard (NINE / ALPHA). Applied live when safe and always on the next CN return. */
    fun setCnDefaultLayout(id: LayoutId) {
        if (cnDefaultLayout == id) return
        cnDefaultLayout = id
        cnLayout = id
        // ③ debug.18: apply the new CN default keyboard IMMEDIATELY when we are already on a CN pinyin keyboard
        // with NOTHING pending. It used to only take effect on the next reset()/onStartInputView, so flipping the
        // Chinese IME behavior note.
        // never commit a half-typed word into the wrong field (a non-empty buffer defers the switch to the next
        // reset/onStartInputView). EN (lang==EN) and the number/symbol pages (layoutId∉{NINE,ALPHA}) are untouched.
        if (lang == Lang.CN && (layoutId == LayoutId.NINE || layoutId == LayoutId.ALPHA) &&
            composing.isEmpty() && committedPrefix.isEmpty()
        ) {
            switchLayout(id)
            refreshCandidates()
            render()
        }
    }

    /** Chinese IME behavior note. */
    fun setAssociationsEnabled(on: Boolean) {
        if (associationsEnabled == on) return
        associationsEnabled = on
        predictionCands = emptySet()
        refreshCandidates()
        render()
    }

    /** E4 hot-toggle (debug.16): push a fuzzy-rule change to the live engine AND remember it, so [setEngine]
     *  re-applies it across a hot-reload swap (the controller is the source of truth, like associationsEnabled). */
    fun setFuzzyRules(rules: Set<String>) {
        pushedFuzzyRules = rules
        engine.setFuzzyRules(rules)
    }

    fun reset() {
        composing.setLength(0)
        candidates = emptyList()
        lockedReadings.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        preeditChoiceUndo.clear()
        deferredLearnEvents.clear()
        drillSyllable = -1 // UI-2: a fresh field starts on the normal grid, not a drilled syllable
        drillChoices.clear() // Chinese IME behavior note.
        // D1 (debug.12): reset() runs on every onStartInputView (field switch) and config change (rotation),
        // and onFinishInput does NOT flush — so an assembled-but-uncommitted prefix MUST be dropped here, or
        // Chinese IME behavior note.
        // wrong editor) = silent cross-field data contamination. Drop it (parity with clearComposingState).
        committedPrefix.setLength(0)
        shiftState = ShiftState.OFF
        // B5: CN opens on the user's default keyboard (9-key unless they chose 26-key); EN is 26-key only.
        cnLayout = cnDefaultLayout
        layoutId = if (lang == Lang.CN) cnDefaultLayout else LayoutId.ALPHA
        lastWord = null
        render()
    }

    /** Test accessor for the active layout (drives the B5 default-keyboard assertions). */
    internal fun activeLayoutId(): LayoutId = layoutId

    fun onKey(key: Key) {
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        if (key.action != KeyAction.BACKSPACE) {
            expirePreeditChoiceUndo()
            drillSyllable = -1
            drillChoices.clear() // Chinese IME behavior note.
            // Chinese IME behavior note.
        }
        when (key.action) {
            KeyAction.COMMIT -> handleCommit(key)
            KeyAction.BACKSPACE -> handleBackspace()
            KeyAction.CLEAR_COMPOSING -> handleClearComposing()
            KeyAction.SPACE -> handleSpace()
            KeyAction.ENTER -> handleEnter()
            // I4: single tap = one-shot (OFF→ONCE); tapping again, or while locked, turns it OFF. Shift is
            // meaningless for full-pinyin (the buffer is always lowercase) and is INERT in CN PINYIN mode —
            // otherwise the shared 26-key ⇧ would arm and the letter caps would stick, because the PINYIN
            // commit path never spends the one-shot (only the DIRECT paths do).
            KeyAction.SHIFT -> if (mode() == Mode.DIRECT) {
                shiftState = if (shiftState == ShiftState.OFF) ShiftState.ONCE else ShiftState.OFF
            }
            // I4: double tap (KeyboardView promotes the 2nd quick SHIFT tap) = caps lock; also EN-only.
            KeyAction.SHIFT_LOCK -> if (mode() == Mode.DIRECT) shiftState = ShiftState.LOCK
            KeyAction.SWITCH_SYMBOLS -> switchLayout(LayoutId.SYMBOL)
            KeyAction.SWITCH_NUMBERS -> switchLayout(LayoutId.NUMBER)
            KeyAction.SWITCH_ALPHA -> switchLayout(LayoutId.ALPHA)
            KeyAction.SWITCH_NINE -> switchLayout(LayoutId.NINE)
            // Chinese IME behavior note.
            // uses (9-key by default — B5) rather than hard-forcing 26-key, which trapped a 9-key user with
            // no in-field way back. EN stays 26-key (it has no 9-key).
            KeyAction.SWITCH_TEXT -> switchLayout(if (lang == Lang.CN) cnLayout else LayoutId.ALPHA)
            KeyAction.SWITCH_NUMPAD -> switchLayout(LayoutId.NUMPAD)
            KeyAction.PICK_READING -> handlePickReading(key)
            KeyAction.SEGMENT -> handleSegment()
            KeyAction.SHOW_EDIT -> onShowEdit()
            KeyAction.CUSTOM_SYMBOL -> onShowCustomSymbols() // Chinese IME behavior note.
            KeyAction.CUSTOM_OPERATOR -> onShowCustomOperators() // Chinese IME behavior note.
            // Chinese IME behavior note.
            // the pending pinyin first — otherwise the panel commits symbols straight to the editor while a
            // stale buffer lingers and its ⌫ deletes committed text out from under it (parity with switchLayout).
            KeyAction.SHOW_SYMBOLS -> { flushComposing(); onShowSymbols() }
            KeyAction.TOGGLE_LANG -> {
                flushComposing()
                shiftState = ShiftState.OFF // Chinese IME behavior note.
                if (lang == Lang.CN) {
                    // Leaving CN keeps the already-recorded CN restore target. Settings changes can arrive
                    // while composing or while already in EN; do not overwrite that newer target with a stale
                    // visible layout here.
                    lang = Lang.EN
                    layoutId = LayoutId.ALPHA
                } else {
                    // Chinese IME behavior note.
                    // 9-key/26-key choice (B5) instead of demoting a 9-key user to 26-key.
                    lang = Lang.CN
                    layoutId = cnLayout
                }
            }
        }
        refreshCandidates()
        render()
    }

    /** Candidate-strip toolbar shortcut (C2). Every kept entry opens a panel / screen — no layout switch. */
    fun onBarFunction(f: BarFunction) {
        expirePreeditChoiceUndo()
        // F7 (debug.12): defensively land any in-progress pinyin before opening a panel/screen, so an entry can
        // never open over a live buffer (the assembled word would otherwise be left dangling for the panel to
        // commit at the wrong caret / lose on reset / delete around — S1). GUARDED like handleCommit(direct) /
        // handleEnter so it only fires when a buffer is actually pending — never clearing lastWord or touching
        // the strip on the idle path. NOTE: today the candidate-bar function icons are drawn + hit-tested ONLY
        // while the strip is idle (CandidateView shows them only when there are no candidates AND no preedit),
        // so this guard is not reachable through the current UI — it is belt-and-suspenders keeping these
        // entries consistent with the ✎ SHOW_SYMBOLS key (the one panel entry reachable mid-composing, which
        // already flushes) should a future bar ever expose them while composing.
        if (composing.isNotEmpty() || committedPrefix.isNotEmpty()) {
            flushComposing()
            refreshCandidates()
            render()
        }
        when (f) {
            BarFunction.BRAND -> onShowSettings() // leading "A" brand mark doubles as the settings entry
            BarFunction.EMOJI -> onShowEmoji()
            BarFunction.EDIT -> onShowEdit()
            BarFunction.CLIPBOARD -> onShowClipboard()
        }
    }

    /**
     * 9-key left column (★E): tapping a reading LOCKS that syllable and advances to the next syllable's
     * readings — it does NOT commit any word. Re-ranks candidates under the locked prefix.
     */
    private fun handlePickReading(key: Key) {
        val reading = key.output
        if (reading.isEmpty()) return
        val digits = T9Pinyin.toT9(reading)
        // UI-1 (debug.13): when the active tail is exhausted (every syllable locked) the left column still
        // Chinese IME behavior note.
        // last syllable: re-expose its digits, then lock the new reading. A shorter reading naturally
        // re-activates the leftover digits; an equal-length one just swaps the reading. The original LOCK
        // Chinese IME behavior note.
        if (activeDigits().isEmpty() && lockedReadings.isNotEmpty()) {
            val lastDigits = T9Pinyin.toT9(lockedReadings.last())
            if (!lastDigits.startsWith(digits)) return // only re-pick a reading of the same trailing chunk
            lockedReadings.removeAt(lockedReadings.lastIndex)
            activeStart = (activeStart - lastDigits.length).coerceAtLeast(0)
            lockedReadings.add(reading)
            activeStart = (activeStart + digits.length).coerceAtMost(composing.length)
            return
        }
        if (!activeDigits().startsWith(digits)) return // reading must encode the active syllable's prefix
        lockedReadings.add(reading)
        activeStart = (activeStart + digits.length).coerceAtMost(composing.length)
        history.addLast(StepKind.LOCK) // A9: locking a reading is one undoable step
    }

    /**
      * Chinese IME behavior note.
     * decoder won't let a word span it, the preedit splits there, and the reading column scopes to the
     * chunk — imposing a boundary WITHOUT forcing a particular reading (xi'an vs xian, long-string cuts).
     */
    private fun handleSegment() {
        if (composing.isEmpty()) return
        if (forcedCuts.add(composing.length)) history.addLast(StepKind.CUT) // A9: a fresh cut is one step
    }

    /**
      * Chinese IME behavior note.
     * layout. Returns true when consumed; otherwise the service does its field-level clear/restore (#5).
     */
    fun onBackspaceSwipe(up: Boolean): Boolean {
        // D3 (debug.12): a bare assembled prefix (composing already backspaced to empty, committedPrefix
        // Chinese IME behavior note.
        // consume the gesture. The old `composing.isNotEmpty()`-only guard let it fall through to the
        // service's field-level clear, which selectAll+commitText("") WIPED THE WHOLE EDITOR FIELD and left
        // the prefix stranded. Sibling parity with handleSpace / handleEnter / handleCommit(direct).
        if (up && (composing.isNotEmpty() || committedPrefix.isNotEmpty())) {
            clearComposingState()
            render()
            return true
        }
        return false
    }

    fun onPickCandidate(index: Int) {
        if (index !in candidates.indices) return
        // Chinese IME behavior note.
        // single char; the leading-syllable defaults are supplied here), not a normal whole-word candidate.
        if (drillSyllable >= 0) {
            pickDrilledHomophone(candidates[index].word)
            refreshCandidates()
            render()
            return
        }
        val cand = candidates[index]
        when {
            // U25/I1: the calculator result is APPENDED after the expression (1+1 → 1+1=2), not a replace.
            cand === calcCand -> {
                // M-3 (data loss): the result was computed from a snapshot of the text before the cursor.
                // The caret may have moved since with no keystroke (so no refresh ran), leaving this cand
                // stale. Re-detect against the LIVE text and only append when the SAME expression still sits
                // immediately before the caret — otherwise "=result" would land in the wrong place. Also skip
                // when a selection is active: commitText would replace (destroy) the selected text.
                val live = Calculator.detect(host.textBeforeCursor(CALC_SCAN_LEN))
                if (live != null && live.expr == calcExpr && live.result == calcResult && !host.hasSelection()) {
                    host.commitText(live.append) // F3/I1: "=result", or bare "result" when '=' was already typed
                }
                clearComposingState(); lastWord = null
            }
            // U23: an associated emoji/symbol commits directly and is NOT learned as a pinyin word. S1(c):
            // flush any assembled prefix ahead of it so the emoji follows the confirmed word, not replaces it.
            cand in directCommitCands -> {
                val text = committedPrefix.toString() + cand.word
                expirePreeditChoiceUndo()
                host.commitText(text)
                applyDeferredLearning()
                clearComposingState(); lastWord = null
            }
            // C5: a next-word prediction commits directly and becomes the new [lastWord] so predictions
            // Chinese IME behavior note.
            cand in predictionCands -> {
                expirePreeditChoiceUndo()
                host.commitText(cand.word)
                if (!learningBlocked) engine.learn(lastWord, cand.word)
                lastWord = cand.word
            }
            else -> {
                if (candidateStaysInPreedit(cand)) savePreeditChoiceUndo()
                commitCandidate(cand)
            }
        }
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
            // D2 (debug.12): also flush when only an assembled prefix remains (composing already backspaced
            // Chinese IME behavior note.
            // or gets stranded for reset() to drop.
            if (composing.isNotEmpty() || committedPrefix.isNotEmpty()) flushComposing()
            host.commitText(applyCase(key.output))
            if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
            lastWord = null
            return
        }
        when (mode()) {
            Mode.PINYIN -> { composing.append(key.output); history.addLast(StepKind.DIGIT) } // T9 buffer; one step per digit
            Mode.DIRECT -> {
                // EN letters / numbers / symbols go straight to the editor (D), with shift applied.
                host.commitText(applyCase(key.output))
                if (shiftState == ShiftState.ONCE) shiftState = ShiftState.OFF
                lastWord = null
            }
        }
    }

    private fun handleBackspace() {
        if (restorePreeditChoiceUndo()) return
        drillSyllable = -1 // Chinese IME behavior note.
        drillChoices.clear() // Chinese IME behavior note.
        if (composing.isEmpty()) {
            // S1(c): an assembled-but-not-yet-committed word prefix lives inside the IME, NOT in the editor.
            // Peel its last confirmed character back here instead of deleting committed editor text the user
            // Chinese IME behavior note.
            if (committedPrefix.isNotEmpty()) {
                val removeCount = Character.charCount(committedPrefix.codePointBefore(committedPrefix.length))
                committedPrefix.setLength(committedPrefix.length - removeCount)
                trimDeferredLearningToPrefix()
                if (committedPrefix.isEmpty()) lastWord = null
                return
            }
            // S2 (debug.12): with an active selection, deleteSurroundingText(1,0) is selection-START-relative
            // and removes the char BEFORE the selection (silent data loss). Delete the SELECTION itself.
            if (host.hasSelection()) host.deleteSelection() else host.deleteBackward()
            lastWord = null
            return
        }
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        when (history.removeLastOrNull()) {
            StepKind.LOCK -> if (lockedReadings.isNotEmpty()) {
                val r = lockedReadings.removeAt(lockedReadings.lastIndex)
                activeStart = (activeStart - T9Pinyin.toT9(r).length).coerceAtLeast(0)
            }
            StepKind.CUT -> forcedCuts.remove(composing.length)
            // a typed digit (or an inconsistent empty history) → delete just that one letter
            StepKind.DIGIT, null -> {
                composing.setLength(composing.length - 1)
                forcedCuts.removeIf { it > composing.length }
                if (activeStart > composing.length) activeStart = composing.length
            }
        }
        if (composing.isEmpty() && committedPrefix.isNotEmpty()) flushComposing()
    }

    /** Rebuild [history] from the current (lock-free) buffer: a DIGIT per digit + a CUT at each boundary.
      * Chinese IME behavior note.
      */
    private fun rebuildHistory() {
        history.clear()
        for (i in 1..composing.length) {
            history.addLast(StepKind.DIGIT)
            if (i in forcedCuts) history.addLast(StepKind.CUT)
        }
    }

    /** Chinese IME behavior note. */
    private fun handleClearComposing() {
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // NOT immediately regenerate it — the old code left lastWord set, so predict() re-populated the strip
        // Chinese IME behavior note.
        lastWord = null
        clearComposingState()
    }

    private fun handleSpace() {
        if (composing.isEmpty()) {
            // S1(c): a word may be assembled in the prefix with the remainder already backspaced away —
            // space commits that pending word (consuming the space), it does NOT insert a literal space.
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
        // S1(c): an assembled prefix (composing already emptied) is still pending — Enter commits it and is
        // consumed, rather than firing a newline/editor-action and stranding the buffered word.
        if (composing.isNotEmpty() || committedPrefix.isNotEmpty()) {
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
        if (candidateStaysInPreedit(cand)) {
            // S1(c): a PARTIAL pick confirms cand.word as the next chunk of the word being assembled but does
            // NOT reach the editor yet — accumulate it in [committedPrefix] (shown at the strip's leftmost)
            // and keep decoding the remainder. The old code commitText()'d every pick, dribbling one
            // Chinese IME behavior note.
            val prefixEnd = committedPrefix.length + cand.word.length
            if (!learningBlocked) deferredLearnEvents.addLast(LearnEvent(lastWord, cand.word, prefixEnd))
            lastWord = cand.word
            committedPrefix.append(cand.word)
            composing.delete(0, cand.coveredLen)
            // Chinese IME behavior note.
            val shifted = forcedCuts.filter { it > cand.coveredLen }.map { it - cand.coveredLen }
            forcedCuts.clear(); forcedCuts.addAll(shifted)
            // debug.14 BUG2: the pick consumed only the FIRST locked syllable(s) — KEEP the locks for the
            // syllables it did NOT consume, so the remaining preedit/decode stays the readings the user locked
            // (gai'lv'chu'xian) instead of re-decoding the bare digits to the T9-default reading
            // (hai'lu'chu'xiao). Drop only the leading locks the pick covered; if the coverage does not land on
            // a lock boundary (it always does on this path) fall back to clearing every lock (old behaviour).
            var consumedDigits = 0; var dropLocks = 0
            while (dropLocks < lockedReadings.size && consumedDigits < cand.coveredLen) {
                consumedDigits += T9Pinyin.toT9(lockedReadings[dropLocks]).length; dropLocks++
            }
            if (lockedReadings.isNotEmpty() && consumedDigits == cand.coveredLen) {
                repeat(dropLocks) { lockedReadings.removeAt(0) }
                activeStart = (activeStart - cand.coveredLen).coerceAtLeast(0)
            } else {
                lockedReadings.clear(); activeStart = 0
            }
            drillSyllable = -1 // UI-2: the remainder re-segments → drop the stale drill, show its candidates
            candidates = emptyList()
            rebuildHistory() // Chinese IME behavior note.
            repeat(lockedReadings.size) { history.addLast(StepKind.LOCK) } // … then per surviving locked syllable
        } else {
            // The pick completes the word: send the assembled prefix + this final chunk to the editor in ONE
            // Chinese IME behavior note.
            expirePreeditChoiceUndo()
            host.commitText(committedPrefix.toString() + cand.word)
            applyDeferredLearning(cand.word)
            lastWord = cand.word
            clearComposingState()
        }
    }

    private fun candidateStaysInPreedit(cand: Cand): Boolean =
        cand.coveredLen in 1 until composing.length

    private fun switchLayout(id: LayoutId) {
        flushComposing()
        shiftState = ShiftState.OFF // I4: a layout switch clears caps lock / one-shot
        layoutId = id
        if (lang == Lang.CN && (id == LayoutId.NINE || id == LayoutId.ALPHA)) cnLayout = id
    }

    /**
     * Commit the pending buffer as raw text (not a learned word): the typed letters on the 26-key,
     * the decoded pinyin (no separators) on the 9-key. Drives both layout switches and Enter (#9).
     */
    private fun flushComposing() {
        // Chinese IME behavior note.
        // raw remainder as ONE commit, never lose the prefix and never dribble.
        val prefix = committedPrefix.toString()
        if (composing.isNotEmpty()) {
            host.commitText(prefix + rawComposingText())
            applyDeferredLearning()
            clearComposingState()
        } else if (prefix.isNotEmpty()) {
            host.commitText(prefix)
            applyDeferredLearning()
            clearComposingState()
        }
        lastWord = null
    }

    /** The active (still-unconfirmed) digits of the 9-key buffer, after any locked syllables. */
    private fun activeDigits(): String =
        if (activeStart < composing.length) composing.substring(activeStart) else ""

    /**
      * Chinese IME behavior note.
      */
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

    /**
     * ★E/U1 coverage bridge: candidates from [CandidateEngine.candidatesForReadingCovered] tag their
     * coverage in LETTERS of [fullLetters], but partial-commit ([commitCandidate]) consumes DIGITS of
     * [composing]. Walk the locked readings then the active syllables, recording each syllable boundary
     * as (cumulative letters → cumulative digits). A coverage not on a boundary (or the full length)
     * falls back to the whole buffer in [refreshCandidates], i.e. a safe full commit. Each syllable's
     * digit width is `toT9(syllable).length`, which reconstructs the originating digits exactly (the
     * DIGIT_INITIAL fallbacks round-trip too), so the active widths sum back to the live digit tail.
     */
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
        map[letters] = composing.length // full coverage is exact regardless of per-syllable rounding
        return map
    }

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
        history.clear()
        preeditChoiceUndo.clear()
        deferredLearnEvents.clear()
        committedPrefix.setLength(0) // S1(c): drop any assembled-but-uncommitted prefix too
        drillSyllable = -1 // UI-2: clearing the buffer leaves no syllable to drill
        drillChoices.clear() // Chinese IME behavior note.
    }

    private fun applyDeferredLearning(finalWord: String? = null) {
        if (!learningBlocked) {
            for (event in deferredLearnEvents) engine.learn(event.prevWord, event.word)
            if (finalWord != null) engine.learn(lastWord, finalWord)
        }
        deferredLearnEvents.clear()
    }

    private fun trimDeferredLearningToPrefix() {
        val baseLastWord = deferredLearnEvents.firstOrNull()?.prevWord
        var removed = false
        while (deferredLearnEvents.lastOrNull()?.prefixEnd?.let { it > committedPrefix.length } == true) {
            deferredLearnEvents.removeLast()
            removed = true
        }
        if (removed) lastWord = deferredLearnEvents.lastOrNull()?.word ?: baseLastWord
    }

    private fun refreshCandidates() {
        val base = baseCandidates()
        // U23/U25/C5 reset; recomputed below so a stale association/calc/prediction cand never lingers.
        directCommitCands = emptySet()
        predictionCands = emptySet()
        calcCand = null; calcExpr = ""; calcResult = ""
        candidates = when {
            // Chinese IME behavior note.
            // (uncapped) instead of the word grid. The single chars carry the syllable's coverage so picking
            // Chinese IME behavior note.
            drillSyllable >= 0 && composing.isNotEmpty() && mode() == Mode.PINYIN ->
                syllableHomophoneCandidates(drillSyllable)
            // While composing pinyin: inject associated emoji/symbols (haode→👌) just after the top word.
            composing.isNotEmpty() && mode() == Mode.PINYIN -> injectAssociations(base)
            // Empty buffer with NO word being assembled: the inline calculator (if the text before the cursor
            // is an expression) else learned next-word predictions (C5/D2). S1(c): suppress while a confirmed
            // prefix is still building (the strip shows it in the preedit tab; these would bypass + lose it).
            composing.isEmpty() && committedPrefix.isEmpty() -> emptyBufferCandidates()
            else -> base
        }
    }

    /**
     * C5/D2: empty-buffer candidates — the calculator takes priority (an arithmetic expression before the
      * Chinese IME behavior note.
     * learned next-word predictions for [lastWord]. Predictions cover 0 input units (committed directly).
     */
    private fun emptyBufferCandidates(): List<Cand> {
        val calc = calcCandidates()
        if (calc.isNotEmpty()) return calc
        if (!associationsEnabled || learningBlocked) return emptyList()
        val preds = engine.predict(lastWord).map { Cand(it, 0) }
        predictionCands = preds.toSet()
        return preds
    }

    /** U23: splice the pinyin-associated emoji/symbols in after the best candidate, capped, no displacement. */
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

    /** U25/I1: if the editor text before the cursor ends in an arithmetic expression, offer "=result" — a
     *  pick APPENDS "=result" after the expression (1+1 → 1+1=2), it does not replace the expression. */
    private fun calcCandidates(): List<Cand> {
        val match = Calculator.detect(host.textBeforeCursor(CALC_SCAN_LEN)) ?: return emptyList()
        // F3/I1: the candidate shows exactly what a pick appends — "=2" normally, or just "2" when the user
        // already typed the trailing '=' on the numpad ("1+1=" → "1+1=2", never "1+1==2").
        val cand = Cand(match.append, 0)
        calcCand = cand; calcExpr = match.expr; calcResult = match.result
        return listOf(cand)
    }

    private fun baseCandidates(): List<Cand> {
        if (composing.isEmpty()) return emptyList()
        val raw = composing.toString()
        // ③ context-aware decoding: the committed text before the cursor conditions the first decoded
        // Chinese IME behavior note.
        val context = host.textBeforeCursor(CTX_SCAN_LEN)
        return when (mode()) {
            Mode.PINYIN -> if (lockedReadings.isNotEmpty()) {
                // ★E / U1: syllable(s) locked via the left column. Use the RICH covered decode
                // (best sentence + completions + per-prefix words) over the combined full pinyin
                // — NOT the narrow best-sentence-only decode(), which collapsed the grid to a
                // single candidate the moment a reading was locked. coveredLen comes back in
                // LETTERS of fullLetters(); remap it to DIGITS of the live buffer so picking a
                // prefix word still partial-commits correctly (★E), full coverage → whole buffer.
                val bounds = readingLetterToDigit()
                // Chinese IME behavior note.
                // the locked-path decode honours them too. The unlocked path always passed forcedCuts to the
                // decoder; this path dropped them, letting a decoded word span a boundary the user explicitly
                // forced (the cut "disappeared" the moment a reading was locked). T9 maps each letter to
                // exactly one digit, so |fullLetters| == |composing| and a forcedCut's digit index IS its
                // letter offset in fullLetters — interior active cuts pass straight through, no translation.
                val full = fullLetters()
                val lockCuts = ArrayList<Int>(lockedReadings.size); var acc = 0
                for (r in lockedReadings) { acc += r.length; if (acc < full.length) lockCuts.add(acc) }
                val readingCuts = (forcedCuts.filter { it in (activeStart + 1) until composing.length } + lockCuts).toSet()
                engine.candidatesForLockedReadingCovered(full, readingCuts, context)
                    // F1 (debug.12, DATA LOSS): coveredLen is in LETTERS of fullLetters; remap it to DIGITS of
                    // the live buffer. A coverage NOT on a syllable boundary (e.g. a 2-letter word while the
                    // locked syllable is 3 letters) is absent from [bounds]; the old `?: composing.length`
                    // fallback then mislabelled it as FULL coverage, so commitCandidate took the "whole word
                    // done" branch — committing the partial word AND clearing the rest of the buffer, so the
                    // still-typed tail silently vanished. Letters↔digits is 1:1, so fall back to the coverage
                    // length itself (clamped): an off-boundary pick now partial-commits exactly what it covers.
                    .map { Cand(it.word, bounds[it.coveredLen] ?: it.coveredLen.coerceAtMost(composing.length)) }
            } else {
                val isNine = layoutId == LayoutId.NINE
                var c = engine.candidatesCovered(raw, isNine, forcedCuts, context)
                // ★N: mid-syllable the full digit buffer may not segment yet — fall back to the
                // longest decodable syllable prefix so the grid keeps the confirmed words
                // Chinese IME behavior note.
                if (c.isEmpty() && isNine) {
                    val pfx = T9Pinyin.longestDecodablePrefix(raw)
                    if (pfx.length in 1 until raw.length) c = engine.candidatesCovered(pfx, true, context = context)
                }
                if (layoutId == LayoutId.ALPHA && c.none { it.word == raw }) c + Cand(raw, raw.length) else c
            }
            // ★S: DIRECT mode has no composing buffer; the empty-buffer candidates (calculator + next-word
            // prediction) are produced by [emptyBufferCandidates] in refreshCandidates, not here.
            Mode.DIRECT -> emptyList()
        }
    }

    /**
      * Chinese IME behavior note.
     * column. Uses the letters path ([CandidateEngine.syllablesForReading]); empty on an un-segmentable or
     * empty buffer. (The 9-key uses the reading-lock column instead, so this is only consulted on ALPHA.)
     */
    private fun currentSyllables(): List<Syllable> =
        if (composing.isEmpty()) emptyList() else engine.syllablesForReading(composing.toString())

    private fun savePreeditChoiceUndo() {
        preeditChoiceUndo.addLast(PreeditChoiceUndo(
            composing = composing.toString(),
            committedPrefix = committedPrefix.toString(),
            lockedReadings = lockedReadings.toList(),
            activeStart = activeStart,
            forcedCuts = forcedCuts.toSet(),
            history = history.toList(),
            drillSyllable = drillSyllable,
            drillChoices = drillChoices.toMap(),
            deferredLearnEvents = deferredLearnEvents.toList(),
            lastWord = lastWord,
            inputEpoch = inputEpoch,
        ))
    }

    private fun expirePreeditChoiceUndo() {
        // A candidate-choice undo is only valid for the next Backspace after the pick.
        inputEpoch++
        preeditChoiceUndo.clear()
    }

    /**
     * Service panels and copy-bar actions mutate the target editor outside [onKey], so they must explicitly retire
     * candidate-choice undo before touching the InputConnection. Candidate picks that stay in IME preedit keep their
     * own undo; picks that commit to the editor retire it before touching editor text.
     */
    fun expireCandidateChoiceUndo() {
        expirePreeditChoiceUndo()
    }

    private fun restorePreeditChoiceUndo(): Boolean {
        val snap = preeditChoiceUndo.removeLastOrNull() ?: return false
        if (snap.inputEpoch != inputEpoch) {
            preeditChoiceUndo.clear()
            return false
        }
        composing.setLength(0); composing.append(snap.composing)
        committedPrefix.setLength(0); committedPrefix.append(snap.committedPrefix)
        lockedReadings.clear(); lockedReadings.addAll(snap.lockedReadings)
        activeStart = snap.activeStart
        forcedCuts.clear(); forcedCuts.addAll(snap.forcedCuts)
        history.clear(); for (step in snap.history) history.addLast(step)
        drillSyllable = snap.drillSyllable
        drillChoices.clear(); drillChoices.putAll(snap.drillChoices)
        deferredLearnEvents.clear(); deferredLearnEvents.addAll(snap.deferredLearnEvents)
        lastWord = snap.lastWord
        candidates = emptyList()
        return true
    }

    /**
     * Complete single-character homophone set for the drilled syllable, tagged with that syllable's coverage.
     * The controller does not re-cap [CandidateEngine.homophonesForReadingAt], so large homophone sets still
     * reach the grid in full.
     */
    private fun syllableHomophoneCandidates(index: Int): List<Cand> {
        val syls = currentSyllables()
        if (index !in syls.indices) return emptyList()
        val coveredLen = syls[index].end.coerceIn(1, composing.length)
        return engine.homophonesForReadingAt(composing.toString(), index).map { Cand(it, coveredLen) }
    }

    /**
     * Record the chosen homophone for the drilled syllable, then commit only the contiguous chosen prefix.
     * A partial preedit commit is snapshotted first so Backspace can return to this exact choice grid.
     */
    private fun pickDrilledHomophone(charWord: String) {
        if (composing.isEmpty()) { drillSyllable = -1; drillChoices.clear(); return } // defensive
        val syls = currentSyllables()
        if (drillSyllable !in syls.indices) { drillSyllable = -1; return }
        val choices = HashMap(drillChoices)
        choices[drillSyllable] = charWord
        var k = 0
        while (choices.containsKey(k) && k < syls.size) k++
        val commitsToEditor = k > 0 && syls[k - 1].end >= composing.length
        if (!commitsToEditor) savePreeditChoiceUndo()
        drillChoices[drillSyllable] = charWord
        if (drillChoices.containsKey(0)) commitChosenLeftPrefix()
    }

    /**
     * Commit the maximal contiguous left-prefix of chosen syllables (0..k-1 all in [drillChoices]) as one
     * ordered word, reusing [commitCandidate] so partial/full commit bookkeeping stays identical to a normal pick.
     */
    private fun commitChosenLeftPrefix() {
        val syls = currentSyllables()
        var k = 0
        while (drillChoices.containsKey(k) && k < syls.size) k++
        if (k == 0) return
        val word = (0 until k).joinToString("") { drillChoices[it] ?: "" }
        val coveredLen = syls[k - 1].end.coerceIn(1, composing.length)
        val carried = HashMap<Int, String>()
        for ((idx, ch) in drillChoices) if (idx >= k) carried[idx - k] = ch
        drillSyllable = -1
        commitCandidate(Cand(word, coveredLen)) // partial → keeps the remainder; full → clearComposingState
        drillChoices.clear()
        drillChoices.putAll(carried)
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    /** Preedit pinyin tab: the assembled confirmed prefix (S1c) at the LEFTMOST, then locked syllable
     *  readings + the decoded active tail (9-key), else typed letters. */
    private fun preeditText(): String {
        // Chinese IME behavior note.
        // up — so multi-syllable phrases assemble in the strip instead of dribbling into the editor.
        val prefix = committedPrefix.toString()
        if (composing.isEmpty()) return prefix
        val tail = if (mode() == Mode.PINYIN && layoutId == LayoutId.NINE) {
            val locked = lockedReadings.joinToString("'")
            // Chinese IME behavior note.
            val rest = T9Pinyin.preedit(activeDigits(), activeCuts().toSet())
            when {
                locked.isEmpty() -> rest
                rest.isEmpty() -> locked
                else -> "$locked'$rest"
            }
        } else composing.toString()
        return prefix + tail
    }

    /**
     * 9-key left column (★E): readings of the ACTIVE (next-unconfirmed) syllable while
     * composing (tap → lock that syllable and advance, no commit), common punctuation when idle.
     *
      * Chinese IME behavior note.
     * [T9Pinyin.leftColumnReadings]) and its length follows the option count: NEVER pad with empty
     * placeholder boxes, punctuation or junk letters, and never the
     * fixed 4 slots. [Layouts.nine] places exactly `left.size` peanut cells so the column shrinks to fit.
     * Punctuation only appears at rest / once every syllable is locked (the early returns) — the two are
     * mutually exclusive. `internal` so the option set can be asserted in unit tests.
     */
    internal fun nineLeftColumn(): List<Key> {
        val w = 0.85f
        if (composing.isEmpty()) return Layouts.ninePunctuation(customSymbols)
        val active = activeDigits()
        // UI-1 (debug.13): once every syllable is locked there is no NEXT syllable — but the column must NOT
        // Chinese IME behavior note.
        // readings so it stays visible + re-pickable (handlePickReading re-opens it). Never punctuation — that
        // Chinese IME behavior note.
        if (active.isEmpty()) {
            if (lockedReadings.isEmpty()) return emptyList()
            val lastDigits = T9Pinyin.toT9(lockedReadings.last())
            return T9Pinyin.leftColumnReadings(lastDigits, NINE_LEFT_MAX)
                .map { Key(it, output = it, action = KeyAction.PICK_READING, weight = w) }
        }
        // Chinese IME behavior note.
        val firstCut = activeCuts().firstOrNull()
        val chunk = if (firstCut != null) active.substring(0, firstCut) else active
        // A3: the FULL combination list (the view scrolls through it) — no fixed cap.
        return T9Pinyin.leftColumnReadings(chunk, NINE_LEFT_MAX)
            .map { Key(it, output = it, action = KeyAction.PICK_READING, weight = w) }
    }

    private fun render() {
        val v = view ?: return
        val layout = when (layoutId) {
            LayoutId.NINE -> Layouts.nine(lang, nineLeftColumn(), composing.isNotEmpty())
            // I2: the numpad's left column is the (default + user-custom) operator scroll strip.
            LayoutId.NUMPAD -> Layouts.numpad(Layouts.numpadOperators(customOperators))
            else -> Layouts.forId(layoutId, lang)
        }
        v.showKeyboard(layout, shifted, shiftState == ShiftState.LOCK, lang) // I4 locked + I2 numpad merged
        // Expanded left column selected state: 26-key drill, or the persisted last locked 9-key reading.
        val readings = expandedReadings()
        v.showCandidates(candidates.map { it.word }, preeditText(), readings, selectedExpandedReadingIndex(readings))
    }

    /** I4 test seam: the shift state name (OFF / ONCE / LOCK) driving the key glyph + uppercasing. */
    internal fun shiftStateName(): String = shiftState.name

    /**
     * Expand-screen left column. On the 9-key it is the active syllable's reading combinations (tap = lock,
     * ★E). On the 26-key it exposes only the first unresolved segmented syllable; choosing it advances the
     * buffer before the next unresolved syllable is shown. Empty when there is nothing to offer.
     */
    internal fun expandedReadings(): List<String> = when {
        layoutId == LayoutId.ALPHA && mode() == Mode.PINYIN && composing.isNotEmpty() ->
            currentSyllables().firstOrNull()?.let { listOf(it.reading) } ?: emptyList()
        else -> nineLeftColumn().filter { it.action == KeyAction.PICK_READING }.map { it.label }
    }

    private fun selectedExpandedReadingIndex(readings: List<String>): Int = when {
        layoutId == LayoutId.ALPHA && mode() == Mode.PINYIN && composing.isNotEmpty() -> drillSyllable
        layoutId == LayoutId.NINE && mode() == Mode.PINYIN && composing.isNotEmpty() &&
            activeDigits().isEmpty() && lockedReadings.isNotEmpty() -> readings.indexOf(lockedReadings.last())
        else -> -1
    }

    /** UI-2 test seam / render hook: which syllable (if any) is currently drilled in the 26-key expand grid. */
    internal fun drilledSyllableForTest(): Int = drillSyllable

    /** Current candidate words (test seam — locks ★S: no ghost suggestion lingers on an empty buffer). */
    internal fun candidateWords(): List<String> = candidates.map { it.word }

    /** S1(c) test seam: the confirmed-but-not-yet-committed word prefix assembled from partial picks. */
    internal fun composingPrefix(): String = committedPrefix.toString()

    /** Test seam: the preedit tab text (prefix + pinyin) shown at the leftmost of the candidate strip. */
    internal fun preeditForTest(): String = preeditText()

    /**
     * Expand-screen left-column tap at [index]. On the 26-key (UI-2) this DRILLS into that syllable — the
      * Chinese IME behavior note.
     */
    fun onPickReadingIndex(index: Int) {
        if (layoutId == LayoutId.ALPHA && mode() == Mode.PINYIN && composing.isNotEmpty()) {
            if (index != 0 || currentSyllables().isEmpty()) return
            expirePreeditChoiceUndo()
            drillSyllable = 0
            refreshCandidates()
            render()
            return
        }
        val readings = expandedReadings()
        if (index !in readings.indices) return
        expirePreeditChoiceUndo()
        handlePickReading(Key(readings[index], output = readings[index], action = KeyAction.PICK_READING))
        refreshCandidates()
        render()
    }

    /**
     *  composing unit. No-op on an empty buffer so the panel button never reaches back into committed editor
     *  text. */
    fun onPanelBackspace() {
        if (composing.isEmpty()) return
        handleBackspace()
        refreshCandidates()
        render()
    }

    /** Chinese IME behavior note. */
    fun onPanelClear() {
        handleClearComposing()
        render()
    }

    /**
     *  word candidates again. No-op when nothing is drilled (every other panel close is unaffected). */
    fun clearDrill() {
        if (drillSyllable < 0 && drillChoices.isEmpty()) return
        drillSyllable = -1
        drillChoices.clear() // Chinese IME behavior note.
        refreshCandidates()
        render()
    }

    private companion object {
        /** Upper bound on readings fed to the scrollable 9-key left column (A3) — large; the view scrolls. */
        const val NINE_LEFT_MAX = 24
        /** U25: how many chars before the cursor to scan for a trailing arithmetic expression. */
        const val CALC_SCAN_LEN = 32
        /** ③ how many committed chars before the cursor to feed the decoder as same-code context. */
        const val CTX_SCAN_LEN = 16
    }
}
