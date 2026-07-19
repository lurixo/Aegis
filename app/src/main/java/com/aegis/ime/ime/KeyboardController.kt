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
import com.aegis.ime.layout.SymbolCatalog

private enum class ShiftState { OFF, ONCE, LOCK }

private enum class Mode { PINYIN, DIRECT }

private enum class StepKind { DIGIT, LOCK, CUT }

class KeyboardController(
    private val host: ImeHost,
    private var engine: CandidateEngine,
    private val decodeLane: DecodeLane? = null,
) {
    private data class LearnEvent(val prevWord: String?, val word: String, val prefixEnd: Int, val reading: String)

    private var lang = Lang.CN
    private var shiftState = ShiftState.OFF
    private val shifted get() = shiftState != ShiftState.OFF
    private var layoutId = LayoutId.ALPHA

    private var cnDefaultLayout = LayoutId.NINE

    private var defaultLang = Lang.CN

    private var cnLayout = LayoutId.NINE
    private val composing = StringBuilder()
    private var candidates: List<Cand> = emptyList()
    private var lastWord: String? = null

    private var engineSupportsChinese: Boolean = engine.supportsChinese

    private val decodeLock = Any()

    private val committedPrefix = StringBuilder()

    private val lockedReadings = mutableListOf<String>()
    private var activeStart = 0

    private val forcedCuts = sortedSetOf<Int>()

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

    private var drillSyllable = -1

    private val drillChoices = HashMap<Int, String>()

    private var customSymbols: List<String> = emptyList()

    private var customOperators: List<String> = emptyList()

    private var directCommitCands: Set<Cand> = emptySet()
    private var predictionCands: Set<Cand> = emptySet()
    private var calcCand: Cand? = null
    private var calcExpr = ""
    private var calcResult = ""
    private var calcDismissed = false

    private var learningBlocked = false

    private var associationsEnabled = true

    private var pushedFuzzyRules: Set<String>? = null

    var onShowEmoji: () -> Unit = {}
    var onShowClipboard: () -> Unit = {}
    var onShowEdit: () -> Unit = {}
    var onShowLayout: () -> Unit = {}
    var onShowSymbols: () -> Unit = {}
    var onShowSettings: () -> Unit = {}
    var onShowCustomSymbols: () -> Unit = {}
    var onShowCustomOperators: () -> Unit = {}
    var onClosePanel: () -> Unit = {}

    private var view: InputView? = null

    fun attachView(v: InputView) {
        view = v
        render()
    }

    fun setEngine(newEngine: CandidateEngine) {
        engine = newEngine
        engineSupportsChinese = newEngine.supportsChinese
        pushedFuzzyRules?.let { newEngine.setFuzzyRules(it) }
        refreshCandidates()
        render()
    }

    fun setCustomSymbols(symbols: List<String>) {
        customSymbols = symbols
        render()
    }

    fun setCustomOperators(operators: List<String>) {
        customOperators = operators
        render()
    }

    fun setLearningBlocked(blocked: Boolean) { learningBlocked = blocked }

    fun setCnDefaultLayout(id: LayoutId) {
        if (cnDefaultLayout == id) return
        cnDefaultLayout = id
        cnLayout = id
        if (lang == Lang.CN && (layoutId == LayoutId.NINE || layoutId == LayoutId.ALPHA) &&
            composing.isEmpty() && committedPrefix.isEmpty()
        ) {
            switchLayout(id)
            refreshCandidates()
            render()
        }
    }

    fun setDefaultLang(l: Lang) {
        if (defaultLang == l) return
        defaultLang = l
        if (lang != l && (layoutId == LayoutId.NINE || layoutId == LayoutId.ALPHA) &&
            composing.isEmpty() && committedPrefix.isEmpty()
        ) {
            lang = l
            shiftState = ShiftState.OFF
            layoutId = if (l == Lang.CN) cnLayout else LayoutId.ALPHA
            refreshCandidates()
            render()
        }
    }

    fun setAssociationsEnabled(on: Boolean) {
        if (associationsEnabled == on) return
        associationsEnabled = on
        predictionCands = emptySet()
        refreshCandidates()
        render()
    }

    fun setFuzzyRules(rules: Set<String>) {
        pushedFuzzyRules = rules
        engine.setFuzzyRules(rules)
    }

    fun reset(preserveLayout: Boolean = false) {
        decodeLane?.markSatisfiedSynchronously()
        composing.setLength(0)
        candidates = emptyList()
        lockedReadings.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        preeditChoiceUndo.clear()
        deferredLearnEvents.clear()
        drillSyllable = -1
        drillChoices.clear()
        committedPrefix.setLength(0)
        shiftState = ShiftState.OFF
        if (!preserveLayout) {
            lang = defaultLang
            cnLayout = cnDefaultLayout
            layoutId = if (lang == Lang.CN) cnDefaultLayout else LayoutId.ALPHA
        }
        lastWord = null
        calcDismissed = false
        render()
    }

    fun restoreBaseKeyboard() {
        shiftState = ShiftState.OFF
        layoutId = if (lang == Lang.CN) cnLayout else LayoutId.ALPHA
        render()
    }

    internal fun activeLayoutId(): LayoutId = layoutId

    fun onKey(key: Key) {
        if (key.action != KeyAction.BACKSPACE) {
            expirePreeditChoiceUndo()
            drillSyllable = -1
            drillChoices.clear()
        }
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
            KeyAction.SWITCH_ALPHA -> switchLayout(LayoutId.ALPHA)
            KeyAction.SWITCH_NINE -> switchLayout(LayoutId.NINE)
            KeyAction.SWITCH_TEXT -> switchLayout(if (lang == Lang.CN) cnLayout else LayoutId.ALPHA)
            KeyAction.SWITCH_NUMPAD -> switchLayout(LayoutId.NUMPAD)
            KeyAction.PICK_READING -> handlePickReading(key)
            KeyAction.SEGMENT -> handleSegment()
            KeyAction.SHOW_EDIT -> onShowEdit()
            KeyAction.CUSTOM_SYMBOL -> onShowCustomSymbols()
            KeyAction.CUSTOM_OPERATOR -> onShowCustomOperators()
            KeyAction.SHOW_SYMBOLS -> { flushComposing(); onShowSymbols() }
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

    fun onBarFunction(f: BarFunction) {
        expirePreeditChoiceUndo()
        if (composing.isNotEmpty() || committedPrefix.isNotEmpty()) {
            flushComposing()
            refreshCandidates()
            render()
        }
        when (f) {
            BarFunction.BRAND -> onShowSettings()
            BarFunction.LAYOUT -> onShowLayout()
            BarFunction.EMOJI -> onShowEmoji()
            BarFunction.EDIT -> onShowEdit()
            BarFunction.CLIPBOARD -> onShowClipboard()
        }
    }

    fun currentLayoutChoice(): LayoutChoice = when {
        lang == Lang.EN -> LayoutChoice.EN_ALPHA
        cnLayout == LayoutId.NINE -> LayoutChoice.CN_NINE
        else -> LayoutChoice.CN_ALPHA
    }

    fun applyLayoutChoice(choice: LayoutChoice) {
        expirePreeditChoiceUndo()
        flushComposing()
        shiftState = ShiftState.OFF
        if (choice == LayoutChoice.EN_ALPHA) {
            lang = Lang.EN
            layoutId = LayoutId.ALPHA
        } else {
            lang = Lang.CN
            cnLayout = if (choice == LayoutChoice.CN_NINE) LayoutId.NINE else LayoutId.ALPHA
            layoutId = cnLayout
        }
        refreshCandidates()
        render()
    }

    private fun handlePickReading(key: Key) {
        val reading = key.output
        if (reading.isEmpty()) return
        val digits = T9Pinyin.toT9(reading)
        if (activeDigits().isEmpty() && lockedReadings.isNotEmpty()) {
            val lastDigits = T9Pinyin.toT9(lockedReadings.last())
            if (!lastDigits.startsWith(digits)) return
            lockedReadings.removeAt(lockedReadings.lastIndex)
            activeStart = (activeStart - lastDigits.length).coerceAtLeast(0)
            lockedReadings.add(reading)
            activeStart = (activeStart + digits.length).coerceAtMost(composing.length)
            return
        }
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
        if (up && (composing.isNotEmpty() || committedPrefix.isNotEmpty())) {
            clearComposingState()
            render()
            return true
        }
        return false
    }

    fun onPickCandidate(index: Int) {
        if (decodeLane?.pending == true) return
        if (index !in candidates.indices) return
        if (drillSyllable >= 0) {
            pickDrilledHomophone(candidates[index].word)
            refreshCandidates()
            render()
            return
        }
        val cand = candidates[index]
        when {
            cand === calcCand -> {
                val live = if (learningBlocked) null else Calculator.detect(host.textBeforeCursor(CALC_SCAN_LEN))
                if (live != null && live.expr == calcExpr && live.result == calcResult && !host.hasSelection()) {
                    host.commitText(live.append)
                }
                clearComposingState(); lastWord = null
            }
            cand in directCommitCands -> {
                val text = committedPrefix.toString() + cand.word
                expirePreeditChoiceUndo()
                host.commitText(text)
                applyDeferredLearning()
                clearComposingState(); lastWord = null
            }
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

    private fun mode(): Mode = when {
        lang == Lang.CN && (layoutId == LayoutId.ALPHA || layoutId == LayoutId.NINE) -> Mode.PINYIN
        else -> Mode.DIRECT
    }

    private fun chineseGateActive(): Boolean =
        mode() == Mode.PINYIN && !engineSupportsChinese && composing.isNotEmpty()

    internal fun chineseGateActiveForTest(): Boolean = chineseGateActive()

    private fun handleCommit(key: Key) {
        if (key.direct) {
            if (composing.isNotEmpty() || committedPrefix.isNotEmpty()) flushComposing()
            host.commitText(if (key.verbatim) key.output else applyCase(key.output))
            if (shiftState == ShiftState.ONCE && key.output.any { it.isLetter() }) shiftState = ShiftState.OFF
            lastWord = null
            calcDismissed = false
            return
        }
        when (mode()) {
            Mode.PINYIN -> { composing.append(key.output); history.addLast(StepKind.DIGIT) }
            Mode.DIRECT -> {
                host.commitText(applyCase(key.output))
                if (shiftState == ShiftState.ONCE && key.output.any { it.isLetter() }) shiftState = ShiftState.OFF
                lastWord = null
                calcDismissed = false
            }
        }
    }

    private fun handleBackspace() {
        if (restorePreeditChoiceUndo()) return
        drillSyllable = -1
        drillChoices.clear()
        if (composing.isEmpty()) {
            if (committedPrefix.isNotEmpty()) {
                val removeCount = Character.charCount(committedPrefix.codePointBefore(committedPrefix.length))
                committedPrefix.setLength(committedPrefix.length - removeCount)
                trimDeferredLearningToPrefix()
                if (committedPrefix.isEmpty()) lastWord = null
                return
            }
            if (host.hasSelection()) host.deleteSelection() else host.deleteBackward()
            lastWord = null
            if (calcCand != null) calcDismissed = true
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
        if (composing.isEmpty() && committedPrefix.isNotEmpty()) flushComposing()
    }

    private fun rebuildHistory() {
        history.clear()
        for (i in 1..composing.length) {
            history.addLast(StepKind.DIGIT)
            if (i in forcedCuts) history.addLast(StepKind.CUT)
        }
    }

    private fun handleClearComposing() {
        val hadCalc = calcCand != null
        lastWord = null
        clearComposingState()
        if (hadCalc) calcDismissed = true
    }

    private fun handleSpace() {
        if (composing.isEmpty()) {
            if (committedPrefix.isNotEmpty()) { flushComposing(); return }
            host.commitText(" ")
            lastWord = null
            return
        }
        ensureDecodeApplied()
        val pick = candidates.firstOrNull()
        when {
            pick != null && pick in directCommitCands -> {
                val text = committedPrefix.toString() + pick.word
                expirePreeditChoiceUndo()
                host.commitText(text)
                applyDeferredLearning()
                clearComposingState(); lastWord = null
            }
            pick != null -> {
                if (candidateStaysInPreedit(pick)) savePreeditChoiceUndo()
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
            lastWord = null
        }
    }

    private fun commitCandidate(cand: Cand) {
        if (candidateStaysInPreedit(cand)) {
            val prefixEnd = committedPrefix.length + cand.word.length
            val chunkReading = consumedReading(cand.coveredLen)
            if (!learningBlocked) deferredLearnEvents.addLast(LearnEvent(lastWord, cand.word, prefixEnd, chunkReading))
            lastWord = cand.word
            committedPrefix.append(cand.word)
            composing.delete(0, cand.coveredLen)
            val shifted = forcedCuts.filter { it > cand.coveredLen }.map { it - cand.coveredLen }
            forcedCuts.clear(); forcedCuts.addAll(shifted)
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
            drillSyllable = -1
            rebuildHistory()
            repeat(lockedReadings.size) { history.addLast(StepKind.LOCK) }
        } else {
            expirePreeditChoiceUndo()
            val assembled = committedPrefix.isNotEmpty()
            val finalReading = consumedReading(cand.coveredLen)
            val wholeWord = committedPrefix.toString() + cand.word
            val wholeReading = deferredLearnEvents.joinToString("") { it.reading } + finalReading
            host.commitText(wholeWord)
            applyDeferredLearning(cand.word)
            maybeLearnAssembledWord(wholeWord, wholeReading, assembled)
            lastWord = cand.word
            clearComposingState()
        }
    }

    private fun consumedReading(coveredLen: Int): String {
        val letters = rawComposingText()
        return letters.take(coveredLen.coerceIn(0, letters.length)).replace("'", "")
    }

    private fun maybeLearnAssembledWord(word: String, reading: String, assembled: Boolean) {
        if (learningBlocked) return
        if (word.codePointCount(0, word.length) < 2) return
        if (reading.length < 2 || reading.any { it !in 'a'..'z' }) return
        if (T9Pinyin.segmentLetters(reading) == null) return
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (!Character.isIdeographic(cp)) return
            i += Character.charCount(cp)
        }
        engine.learnWord(reading, word, assembled)
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
            applyDeferredLearning()
            clearComposingState()
        } else if (prefix.isNotEmpty()) {
            val wholeReading = deferredLearnEvents.joinToString("") { it.reading }
            host.commitText(prefix)
            applyDeferredLearning()
            maybeLearnAssembledWord(prefix, wholeReading, assembled = true)
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
        decodeLane?.markSatisfiedSynchronously()
        composing.setLength(0)
        candidates = emptyList()
        lockedReadings.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        preeditChoiceUndo.clear()
        deferredLearnEvents.clear()
        committedPrefix.setLength(0)
        drillSyllable = -1
        drillChoices.clear()
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
        val req = buildDecodeRequest()
        val lane = decodeLane
        if (lane == null) {
            applyDecodeResult(computeDecode(req))
        } else {
            lane.submit(
                compute = { computeDecode(req) },
                apply = { result -> applyDecodeResult(result); render() },
                onError = { applyDecodeResult(emptyDecodeResult()); render() },
            )
        }
    }

    private fun ensureDecodeApplied() {
        val lane = decodeLane ?: return
        if (!lane.pending) return
        applyDecodeResult(computeDecode(buildDecodeRequest()))
        lane.markSatisfiedSynchronously()
    }

    private class DecodeRequest(
        val engine: CandidateEngine,
        val host: ImeHost,
        val composingEmpty: Boolean,
        val committedPrefixEmpty: Boolean,
        val mode: Mode,
        val drillSyllable: Int,
        val raw: String,
        val rawComposing: String,
        val composingLen: Int,
        val lockedNonEmpty: Boolean,
        val full: String,
        val readingCuts: Set<Int>,
        val bounds: Map<Int, Int>,
        val isNine: Boolean,
        val forcedCuts: Set<Int>,
        val associationsEnabled: Boolean,
        val learningBlocked: Boolean,
        val calcDismissed: Boolean,
        val lastWord: String?,
    )

    private class DecodeResult(
        val candidates: List<Cand>,
        val directCommitCands: Set<Cand>,
        val predictionCands: Set<Cand>,
        val calcCand: Cand?,
        val calcExpr: String,
        val calcResult: String,
    )

    private fun emptyDecodeResult(): DecodeResult =
        DecodeResult(emptyList(), emptySet(), emptySet(), null, "", "")

    private fun buildDecodeRequest(): DecodeRequest {
        val locked = mode() == Mode.PINYIN && composing.isNotEmpty() && lockedReadings.isNotEmpty()
        val full = if (locked) fullLetters() else ""
        val readingCuts = if (locked) {
            val lockCuts = ArrayList<Int>(lockedReadings.size); var acc = 0
            for (r in lockedReadings) { acc += r.length; if (acc < full.length) lockCuts.add(acc) }
            (forcedCuts.filter { it in (activeStart + 1) until composing.length } + lockCuts).toSet()
        } else {
            emptySet()
        }
        return DecodeRequest(
            engine = engine,
            host = host,
            composingEmpty = composing.isEmpty(),
            committedPrefixEmpty = committedPrefix.isEmpty(),
            mode = mode(),
            drillSyllable = drillSyllable,
            raw = composing.toString(),
            rawComposing = rawComposingText(),
            composingLen = composing.length,
            lockedNonEmpty = locked,
            full = full,
            readingCuts = readingCuts,
            bounds = if (locked) readingLetterToDigit() else emptyMap(),
            isNine = layoutId == LayoutId.NINE,
            forcedCuts = forcedCuts.toSet(),
            associationsEnabled = associationsEnabled,
            learningBlocked = learningBlocked,
            calcDismissed = calcDismissed,
            lastWord = lastWord,
        )
    }

    private fun applyDecodeResult(r: DecodeResult) {
        candidates = r.candidates
        directCommitCands = r.directCommitCands
        predictionCands = r.predictionCands
        calcCand = r.calcCand
        calcExpr = r.calcExpr
        calcResult = r.calcResult
    }

    private fun computeDecode(req: DecodeRequest): DecodeResult = synchronized(decodeLock) {
        var directCommit: Set<Cand> = emptySet()
        var prediction: Set<Cand> = emptySet()
        var calcC: Cand? = null; var calcE = ""; var calcR = ""
        val base = computeBase(req)
        val out = when {
            req.drillSyllable >= 0 && !req.composingEmpty && req.mode == Mode.PINYIN -> computeDrill(req)
            !req.composingEmpty && req.mode == Mode.PINYIN -> {
                val glyphs = dedupeFullHalfGlyphs(InputAssociations.lookup(req.rawComposing))
                if (glyphs.isEmpty()) {
                    base
                } else {
                    val extra = glyphs.map { Cand(it, req.composingLen) }
                    directCommit = extra.toSet()
                    if (base.isEmpty()) extra else listOf(base.first()) + extra + base.drop(1)
                }
            }
            req.composingEmpty && req.committedPrefixEmpty -> {
                val match = if (req.learningBlocked || req.calcDismissed) null else Calculator.detect(req.host.textBeforeCursor(CALC_SCAN_LEN))
                when {
                    match != null -> {
                        val cand = Cand(match.append, 0)
                        calcC = cand; calcE = match.expr; calcR = match.result
                        listOf(cand)
                    }
                    !req.associationsEnabled || req.learningBlocked -> emptyList()
                    else -> {
                        val preds = req.engine.predict(req.lastWord).map { Cand(it, 0) }
                        prediction = preds.toSet()
                        preds
                    }
                }
            }
            else -> base
        }
        DecodeResult(out, directCommit, prediction, calcC, calcE, calcR)
    }

    private fun computeBase(req: DecodeRequest): List<Cand> {
        if (req.composingEmpty || req.mode != Mode.PINYIN) return emptyList()
        val context = req.host.textBeforeCursor(CTX_SCAN_LEN)
        return if (req.lockedNonEmpty) {
            req.engine.candidatesForLockedReadingCovered(req.full, req.readingCuts, context)
                .map { Cand(it.word, req.bounds[it.coveredLen] ?: it.coveredLen.coerceAtMost(req.composingLen)) }
        } else {
            var c = req.engine.candidatesCovered(req.raw, req.isNine, req.forcedCuts, context)
            if (c.isEmpty() && req.isNine) {
                val pfx = T9Pinyin.longestDecodablePrefix(req.raw)
                if (pfx.length in 1 until req.raw.length) c = req.engine.candidatesCovered(pfx, true, context = context)
            }
            c
        }
    }

    private fun computeDrill(req: DecodeRequest): List<Cand> {
        val syls = req.engine.syllablesForReading(req.raw)
        if (req.drillSyllable !in syls.indices) return emptyList()
        val coveredLen = syls[req.drillSyllable].end.coerceIn(1, req.composingLen)
        return req.engine.homophonesForReadingAt(req.raw, req.drillSyllable).map { Cand(it, coveredLen) }
    }

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
        inputEpoch++
        preeditChoiceUndo.clear()
    }

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
        return true
    }

    private fun pickDrilledHomophone(charWord: String) {
        if (composing.isEmpty()) { drillSyllable = -1; drillChoices.clear(); return }
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
        commitCandidate(Cand(word, coveredLen))
        drillChoices.clear()
        drillChoices.putAll(carried)
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
        if (active.isEmpty()) {
            if (lockedReadings.isEmpty()) return emptyList()
            val lastDigits = T9Pinyin.toT9(lockedReadings.last())
            return T9Pinyin.leftColumnReadings(lastDigits, NINE_LEFT_MAX)
                .map { Key(it, output = it, action = KeyAction.PICK_READING, weight = w) }
        }
        val firstCut = activeCuts().firstOrNull()
        val chunk = if (firstCut != null) active.substring(0, firstCut) else active
        return T9Pinyin.leftColumnReadings(chunk, NINE_LEFT_MAX)
            .map { Key(it, output = it, action = KeyAction.PICK_READING, weight = w) }
    }

    private fun render() {
        val v = view ?: return
        val layout = when (layoutId) {
            LayoutId.NINE -> Layouts.nine(lang, nineLeftColumn(), composing.isNotEmpty())
            LayoutId.NUMPAD -> Layouts.numpad(Layouts.numpadOperators(customOperators))
            else -> Layouts.forId(layoutId, lang)
        }
        v.showKeyboard(layout, shifted, shiftState == ShiftState.LOCK, lang)
        val readings = expandedReadings()
        v.showCandidates(candidates.map { it.word }, preeditText(), readings, selectedExpandedReadingIndex(readings), chineseGateActive())
    }

    internal fun shiftStateName(): String = shiftState.name

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

    internal fun drilledSyllableForTest(): Int = drillSyllable

    internal fun candidateWords(): List<String> = candidates.map { it.word }

    internal fun decodeStateForTest(): String = buildString {
        append("C:")
        for (c in candidates) append(c.word).append('/').append(c.coveredLen).append(',')
        append("|D:")
        for (c in directCommitCands) append(c.word).append(',')
        append("|P:")
        for (c in predictionCands) append(c.word).append(',')
        append("|calc:").append(calcCand?.word ?: "").append('/').append(calcExpr).append('/').append(calcResult)
    }

    internal fun composingPrefix(): String = committedPrefix.toString()

    internal fun preeditForTest(): String = preeditText()

    fun onPickReadingIndex(index: Int) {
        if (decodeLane?.pending == true) return
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

    fun clearDrill() {
        if (drillSyllable < 0 && drillChoices.isEmpty()) return
        drillSyllable = -1
        drillChoices.clear()
        refreshCandidates()
        render()
    }

    private companion object {
        const val NINE_LEFT_MAX = 24
        const val CALC_SCAN_LEN = 32
        const val CTX_SCAN_LEN = 16
    }
}

internal fun dedupeFullHalfGlyphs(glyphs: List<String>): List<String> {
    if (glyphs.size <= 1) return glyphs
    val seen = HashSet<String>(glyphs.size * 2)
    return glyphs.filter { seen.add(SymbolCatalog.foldFullWidth(it)) }
}
