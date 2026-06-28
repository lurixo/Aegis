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

/** One reversible input step in the 9-key buffer (A9 退格=退回上一步): a typed digit, a left-column
 *  reading lock, or a forced 分词 cut. Backspace undoes exactly the most recent one. */
private enum class StepKind { DIGIT, LOCK, CUT }

class KeyboardController(
    private val host: ImeHost,
    private var engine: CandidateEngine,
) {
    private var lang = Lang.CN
    private var shiftState = ShiftState.OFF
    private val shifted get() = shiftState != ShiftState.OFF
    private var layoutId = LayoutId.ALPHA

    /**
     * B5: the CN startup keyboard — 全拼九键 by default, 全拼26键 optional (the user picks in the setup
     * screen; the IME service pushes the pref via [setCnDefaultLayout]). EN is always 26-key; [reset]
     * applies this at the start of each input session.
     */
    private var cnDefaultLayout = LayoutId.NINE

    /**
     * The CN keyboard to restore when toggling back from EN (EN is 26-key only). A 中英 round-trip must
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
     * the old "选一个就上屏一个" behaviour that dribbled 就/见/左… into the app one syllable at a time.
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

    /** 分词/隔音: user-forced syllable boundaries — indices into [composing] where a word may
     *  not span. The decoder, preedit and reading column all honour these. See [handleSegment]. */
    private val forcedCuts = sortedSetOf<Int>()

    /** A9: reverse-order log of input steps in the current buffer so 退格 steps back exactly ONE — a typed
     *  digit, a reading lock (left-column pick), or a forced cut — never dropping a whole locked syllable. */
    private val history = ArrayDeque<StepKind>()

    /** A3 自定义: the user's custom punctuation marks (injected from prefs by the service). */
    private var customSymbols: List<String> = emptyList()

    /** I2: the user's custom numpad operators (injected from prefs), appended to the operator scroll column. */
    private var customOperators: List<String> = emptyList()

    /** U23: associated emoji/symbol candidates in the current list — committed directly (no pinyin learn). */
    private var directCommitCands: Set<Cand> = emptySet()
    /** U25: the inline-calculator candidate (if any) + the expression it was computed from. The expression
     *  is re-validated against the LIVE text-before-cursor at pick time (M-3) so a moved cursor can never
     *  make the replace delete unrelated characters. */
    private var calcCand: Cand? = null
    private var calcExpr = ""
    private var calcResult = "" // I1: the bare result string ("2"); the candidate shows "=2" and appends "=2"

    /** M-3/L-3: when the focused field is a password / opts out of personalized learning, never learn. */
    private var learningBlocked = false

    /** Extras-panel hooks wired by the IME service (it owns the InputConnection + Context). */
    var onShowEmoji: () -> Unit = {}
    var onShowClipboard: () -> Unit = {}
    var onShowEdit: () -> Unit = {}
    var onShowSymbols: () -> Unit = {}
    var onShowSettings: () -> Unit = {}
    var onShowCustomSymbols: () -> Unit = {} // A3 自定义 entry tapped
    var onShowCustomOperators: () -> Unit = {} // I2 numpad operator 自定义 entry tapped
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

    /** A3 自定义: set the user's custom punctuation marks (from prefs) and re-render the column. */
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

    /** B5: choose the CN default keyboard (NINE / ALPHA). Applied on the next [reset]; EN ignores it. */
    fun setCnDefaultLayout(id: LayoutId) { cnDefaultLayout = id }

    fun reset() {
        composing.setLength(0)
        candidates = emptyList()
        lockedReadings.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        // D1 (debug.12): reset() runs on every onStartInputView (field switch) and config change (rotation),
        // and onFinishInput does NOT flush — so an assembled-but-uncommitted prefix MUST be dropped here, or
        // a partial pick ("就") left pending leaks into the next field (committed there or flushed into the
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
            // H-1: 返回 from the number/symbol/numpad page goes to the CN TEXT keyboard the user actually
            // uses (9-key by default — B5) rather than hard-forcing 26-key, which trapped a 9-key user with
            // no in-field way back. EN stays 26-key (it has no 9-key).
            KeyAction.SWITCH_TEXT -> switchLayout(if (lang == Lang.CN) cnLayout else LayoutId.ALPHA)
            KeyAction.SWITCH_NUMPAD -> switchLayout(LayoutId.NUMPAD)
            KeyAction.PICK_READING -> handlePickReading(key)
            KeyAction.SEGMENT -> handleSegment()
            KeyAction.SHOW_EDIT -> onShowEdit()
            KeyAction.CUSTOM_SYMBOL -> onShowCustomSymbols() // A3: open the 自定义 punctuation panel
            KeyAction.CUSTOM_OPERATOR -> onShowCustomOperators() // I2: open the 自定义 operator panel
            // D: 铅笔 ✎ → symbols panel. The pencil sits on the keyboard (reachable mid-composing), so flush
            // the pending pinyin first — otherwise the panel commits symbols straight to the editor while a
            // stale buffer lingers and its ⌫ deletes committed text out from under it (parity with switchLayout).
            KeyAction.SHOW_SYMBOLS -> { flushComposing(); onShowSymbols() }
            KeyAction.TOGGLE_LANG -> {
                flushComposing()
                shiftState = ShiftState.OFF // I4: switching 中英 clears caps lock / one-shot
                if (lang == Lang.CN) {
                    // Leaving CN: remember the CN keyboard (issue #10: EN is 26-key only).
                    cnLayout = layoutId
                    lang = Lang.EN
                    layoutId = LayoutId.ALPHA
                } else {
                    // Returning to CN: restore the CN keyboard so a 中英 round-trip keeps the user's
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
        if (!activeDigits().startsWith(digits)) return // reading must encode the active syllable's prefix
        lockedReadings.add(reading)
        activeStart = (activeStart + digits.length).coerceAtMost(composing.length)
        history.addLast(StepKind.LOCK) // A9: locking a reading is one undoable step
    }

    /**
     * 分词/隔音: force a syllable boundary at the current input position. The
     * decoder won't let a word span it, the preedit splits there, and the reading column scopes to the
     * chunk — imposing a boundary WITHOUT forcing a particular reading (xi'an vs xian, long-string cuts).
     */
    private fun handleSegment() {
        if (composing.isEmpty()) return
        if (forcedCuts.add(composing.length)) history.addLast(StepKind.CUT) // A9: a fresh cut is one step
    }

    /**
     * Backspace up-swipe (C): if there is pending pinyin in the bar, clear it (重输) — works in EVERY
     * layout. Returns true when consumed; otherwise the service does its field-level clear/restore (#5).
     */
    fun onBackspaceSwipe(up: Boolean): Boolean {
        // D3 (debug.12): a bare assembled prefix (composing already backspaced to empty, committedPrefix
        // still pending) IS pending input — the up-swipe must 重输 (drop it via clearComposingState) and
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
                host.commitText(committedPrefix.toString() + cand.word)
                clearComposingState(); lastWord = null
            }
            else -> commitCandidate(cand)
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
            // away) so the punctuation/number follows the confirmed word ("就。"), never precedes it ("。就")
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
        if (composing.isEmpty()) {
            // S1(c): an assembled-but-not-yet-committed word prefix lives inside the IME, NOT in the editor.
            // Peel its last confirmed character back here instead of deleting committed editor text the user
            // can still see — only once the prefix is empty does 退格 reach the field.
            if (committedPrefix.isNotEmpty()) {
                committedPrefix.setLength(committedPrefix.length - 1)
                if (committedPrefix.isEmpty()) lastWord = null
                return
            }
            // S2 (debug.12): with an active selection, deleteSurroundingText(1,0) is selection-START-relative
            // and removes the char BEFORE the selection (silent data loss). Delete the SELECTION itself.
            if (host.hasSelection()) host.deleteSelection() else host.deleteBackward()
            lastWord = null
            return
        }
        // A9 退格=退回上一步: undo EXACTLY the most recent input step — a reading lock (left-column pick),
        // a forced 分词 cut, or a single typed digit. Never wipe a whole locked syllable (the old code
        // cleared every lock on any digit delete, so backspace dropped an entire 音节组合 — the bug).
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
    }

    /** Rebuild [history] from the current (lock-free) buffer: a DIGIT per digit + a CUT at each boundary.
     *  Used after a partial commit leaves a fresh remainder so 退格 still steps back correctly. */
    private fun rebuildHistory() {
        history.clear()
        for (i in 1..composing.length) {
            history.addLast(StepKind.DIGIT)
            if (i in forcedCuts) history.addLast(StepKind.CUT)
        }
    }

    /** 9-key "重输": drop the pending pinyin + candidates without touching committed text. */
    private fun handleClearComposing() {
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
        // M-3/L-3: never learn a word committed in a password / NO_PERSONALIZED_LEARNING field — it would
        // be saved to userdb in plaintext and later resurface (via wordBoost) in ordinary fields (肩窥).
        // Each picked chunk is still learned as a bigram so 就→见→左侧 adaptation survives (the deferral
        // below changes only WHEN text reaches the editor, never what is learned).
        if (!learningBlocked) engine.learn(lastWord, cand.word)
        lastWord = cand.word
        if (cand.coveredLen in 1 until composing.length) {
            // S1(c): a PARTIAL pick confirms cand.word as the next chunk of the word being assembled but does
            // NOT reach the editor yet — accumulate it in [committedPrefix] (shown at the strip's leftmost)
            // and keep decoding the remainder. The old code commitText()'d every pick, dribbling one
            // syllable at a time into the app (the "选一个就上屏一个" bug).
            committedPrefix.append(cand.word)
            composing.delete(0, cand.coveredLen)
            // ★E×分词: drop consumed cuts, shift the rest left by the consumed length.
            val shifted = forcedCuts.filter { it > cand.coveredLen }.map { it - cand.coveredLen }
            forcedCuts.clear(); forcedCuts.addAll(shifted)
            lockedReadings.clear()
            activeStart = 0
            candidates = emptyList()
            rebuildHistory() // A9: the remainder is fresh + lock-free → 退格 steps back per remaining digit
        } else {
            // The pick completes the word: send the assembled prefix + this final chunk to the editor in ONE
            // commit (整词完成才上屏), then reset.
            host.commitText(committedPrefix.toString() + cand.word)
            clearComposingState()
        }
    }

    private fun switchLayout(id: LayoutId) {
        flushComposing()
        shiftState = ShiftState.OFF // I4: a layout switch clears caps lock / one-shot
        layoutId = id
    }

    /**
     * Commit the pending buffer as raw text (not a learned word): the typed letters on the 26-key,
     * the decoded pinyin (no separators) on the 9-key. Drives both layout switches and Enter (#9).
     */
    private fun flushComposing() {
        // S1(c): a forced flush (layout switch / Enter / 中英) must still land the confirmed prefix + the
        // raw remainder as ONE commit, never lose the prefix and never dribble.
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
        committedPrefix.setLength(0) // S1(c): drop any assembled-but-uncommitted prefix too
    }

    private fun refreshCandidates() {
        val base = baseCandidates()
        // U23/U25 reset; recomputed below so a stale association/calc cand never lingers.
        directCommitCands = emptySet()
        calcCand = null; calcExpr = ""; calcResult = ""
        candidates = when {
            // While composing pinyin: inject associated emoji/symbols (haode→👌) just after the top word.
            composing.isNotEmpty() && mode() == Mode.PINYIN -> injectAssociations(base)
            // Empty buffer with NO word being assembled: offer an inline calculator result if the text before
            // the cursor is an expression. S1(c): suppress it while a confirmed prefix is still building (the
            // strip shows the prefix in the preedit tab; a calc cand would bypass + lose it).
            composing.isEmpty() && committedPrefix.isEmpty() -> calcCandidates()
            else -> base
        }
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
        // word so cross-word context disambiguates same-code input (非常 + 943943 → 谢谢, not 这些).
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
                // F6 (debug.12): forward the user's 分词 boundaries that fall inside the still-active tail so
                // the locked-path decode honours them too. The unlocked path always passed forcedCuts to the
                // decoder; this path dropped them, letting a decoded word span a boundary the user explicitly
                // forced (the cut "disappeared" the moment a reading was locked). T9 maps each letter to
                // exactly one digit, so |fullLetters| == |composing| and a forcedCut's digit index IS its
                // letter offset in fullLetters — interior active cuts pass straight through, no translation.
                val readingCuts = forcedCuts.filter { it in (activeStart + 1) until composing.length }.toSet()
                engine.candidatesForReadingCovered(fullLetters(), readingCuts, context)
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
                // (你/你说…) instead of going blank when a half-typed syllable trails.
                if (c.isEmpty() && isNine) {
                    val pfx = T9Pinyin.longestDecodablePrefix(raw)
                    if (pfx.length in 1 until raw.length) c = engine.candidatesCovered(pfx, true, context = context)
                }
                if (layoutId == LayoutId.ALPHA && c.none { it.word == raw }) c + Cand(raw, raw.length) else c
            }
            // ★S: no next-word prediction (handled by the empty-buffer calculator path in refreshCandidates).
            Mode.DIRECT -> emptyList()
        }
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    /** Preedit pinyin tab: the assembled confirmed prefix (S1c) at the LEFTMOST, then locked syllable
     *  readings + the decoded active tail (9-key), else typed letters. */
    private fun preeditText(): String {
        // S1(c): the confirmed-but-uncommitted word prefix renders first (leftmost) — "就jianzuoce" building
        // up — so multi-syllable phrases assemble in the strip instead of dribbling into the editor.
        val prefix = committedPrefix.toString()
        if (composing.isEmpty()) return prefix
        val tail = if (mode() == Mode.PINYIN && layoutId == LayoutId.NINE) {
            val locked = lockedReadings.joinToString("'")
            // ★分词: render the active tail with its forced boundaries as 隔音符 ' (incl. a trailing one).
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
     * the column shows ONLY real readings (canonical syllables + 首键字母, via
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
        // S1(c) (debug.12): once every syllable is locked there is no NEXT syllable to offer readings for —
        // show an empty column, NOT punctuation. Punctuation only ever appears at rest (the empty-buffer
        // return above); the "读音变标点" bug after locking is gone.
        if (active.isEmpty()) return emptyList()
        // ★分词: the active syllable is bounded by the first forced cut in the active region.
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
        v.showCandidates(candidates.map { it.word }, preeditText(), expandedReadings())
    }

    /** I4 test seam: the shift state name (OFF / ONCE / LOCK) driving the key glyph + uppercasing. */
    internal fun shiftStateName(): String = shiftState.name

    /** A2 expanded screen left column: the active syllable's combinations (9-key composing only; else empty). */
    internal fun expandedReadings(): List<String> =
        nineLeftColumn().filter { it.action == KeyAction.PICK_READING }.map { it.label }

    /** Current candidate words (test seam — locks ★S: no ghost suggestion lingers on an empty buffer). */
    internal fun candidateWords(): List<String> = candidates.map { it.word }

    /** S1(c) test seam: the confirmed-but-not-yet-committed word prefix assembled from partial picks. */
    internal fun composingPrefix(): String = committedPrefix.toString()

    /** Test seam: the preedit tab text (prefix + pinyin) shown at the leftmost of the candidate strip. */
    internal fun preeditForTest(): String = preeditText()

    /** A2 expanded screen: pick the combination at [index] in the left column — locks that syllable. */
    fun onPickReadingIndex(index: Int) {
        val readings = expandedReadings()
        if (index !in readings.indices) return
        handlePickReading(Key(readings[index], output = readings[index], action = KeyAction.PICK_READING))
        refreshCandidates()
        render()
    }

    /** A2 expanded screen 退格: delete one composing unit, then refresh. No-op on an empty buffer so the
     *  panel button never reaches back into committed editor text. */
    fun onPanelBackspace() {
        if (composing.isEmpty()) return
        handleBackspace()
        refreshCandidates()
        render()
    }

    /** A2 expanded screen 重输: drop the pending pinyin + candidates (closes the screen via empty candidates). */
    fun onPanelClear() {
        handleClearComposing()
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
