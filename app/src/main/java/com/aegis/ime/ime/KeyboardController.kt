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
import com.aegis.ime.decoder.CANDIDATE_PAGE_SIZE
import com.aegis.ime.decoder.CandidateContinuation
import com.aegis.ime.decoder.CandidatePage
import com.aegis.ime.decoder.CandidatePageSource
import com.aegis.ime.decoder.CandidateSlice
import com.aegis.ime.decoder.ListCandidatePageSource
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.decoder.continueCandidatePage
import com.aegis.ime.decoder.firstCandidatePage
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.Calculator
import com.aegis.ime.engine.InputAssociations
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.UserLearning

private enum class ShiftState { OFF, ONCE, LOCK }

private enum class Mode { PINYIN, DIRECT }

private enum class StepKind { DIGIT, LOCK, CUT }

class KeyboardController(
    private val host: ImeHost,
    private var engine: CandidateEngine,
    private val decodeLane: DecodeLane? = null,
) {
    private data class LearnEvent(val prevWord: String?, val word: String, val prefixEnd: Int, val reading: String)

    private enum class CandidateRole { NORMAL, DIRECT, PREDICTION, CALCULATOR }

    private data class CandidateEntry(val candidate: Cand, val role: CandidateRole)

    private var lang = Lang.CN
    private var shiftState = ShiftState.OFF
    private val shifted get() = shiftState != ShiftState.OFF
    private var layoutId = LayoutId.ALPHA

    private var cnDefaultLayout = LayoutId.NINE

    private var defaultLang = Lang.CN

    private var cnLayout = LayoutId.NINE
    private val composing = StringBuilder()
    private var candidates: List<Cand> = emptyList()
    private var candidateRoles: List<CandidateRole> = emptyList()
    private var candidateContinuation: CandidateContinuation<CandidateEntry>? = null
    private var readingContinuation: CandidateContinuation<String>? = null
    private var expandedReadingItems: List<String> = emptyList()
    private var queryInputEpoch = 0L
    private var initialDecodePending = false
    private var candidatePagePending = false
    private var readingPagePending = false
    private var lastWord: String? = null

    private var engineSupportsChinese: Boolean = engine.supportsChinese

    private val decodeLock = Any()

    private val committedPrefix = StringBuilder()

    private val lockedReadings = mutableListOf<String>()
    private val lockedInputLengths = mutableListOf<Int>()
    private var activeStart = 0

    private val forcedCuts = sortedSetOf<Int>()

    private val history = ArrayDeque<StepKind>()

    private data class PreeditChoiceUndo(
        val composing: String,
        val committedPrefix: String,
        val lockedReadings: List<String>,
        val lockedInputLengths: List<Int>,
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

    private var customSymbolKeys: List<Key> = Layouts.ninePunctuation()

    private var customOperatorKeys: List<Key> = Layouts.numpadOperators()

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
    var onShowPhrases: () -> Unit = {}
    var onShowEdit: () -> Unit = {}
    var onShowLayout: () -> Unit = {}
    var onShowSymbols: () -> Unit = {}
    var onShowSettings: () -> Unit = {}
    var onShowCustomSymbols: () -> Unit = {}
    var onShowCustomOperators: () -> Unit = {}
    var onClosePanel: () -> Unit = {}
    var userLearning: UserLearning? = null

    private var view: InputView? = null

    fun attachView(v: InputView) {
        view = v
        render()
    }

    fun setEngine(newEngine: CandidateEngine) {
        val clearDownloadTrigger = !engineSupportsChinese && newEngine.supportsChinese && chineseGateActive()
        engine = newEngine
        engineSupportsChinese = newEngine.supportsChinese
        pushedFuzzyRules?.let { newEngine.setFuzzyRules(it) }
        if (clearDownloadTrigger) {
            clearComposingState()
            applyDecodeResult(emptyDecodeResult())
        } else {
            refreshCandidates()
        }
        render()
    }

    fun setCustomSymbols(symbols: List<String>) {
        customSymbolKeys = Layouts.ninePunctuation(symbols)
        render()
    }

    fun setCustomOperators(operators: List<String>, prefiltered: Boolean = false) {
        customOperatorKeys = Layouts.numpadOperators(operators, prefiltered)
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
        if (pushedFuzzyRules == rules) return
        pushedFuzzyRules = rules
        engine.setFuzzyRules(rules)
        refreshCandidates()
        render()
    }

    fun reset(preserveLayout: Boolean = false) {
        userLearning?.observeBreak()
        invalidateCandidateQuery()
        composing.setLength(0)
        candidates = emptyList()
        candidateRoles = emptyList()
        expandedReadingItems = emptyList()
        directCommitCands = emptySet()
        predictionCands = emptySet()
        calcCand = null
        calcExpr = ""
        calcResult = ""
        lockedReadings.clear()
        lockedInputLengths.clear()
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
            BarFunction.PHRASE -> onShowPhrases()
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
        val input = inputForReading(reading)
        if (activeInput().isEmpty() && lockedReadings.isNotEmpty()) {
            val lastInput = inputForReading(lockedReadings.last())
            if (!lastInput.startsWith(input)) return
            lockedReadings.removeAt(lockedReadings.lastIndex)
            val oldLength = lockedInputLengths.removeAt(lockedInputLengths.lastIndex)
            activeStart = (activeStart - oldLength).coerceAtLeast(0)
            lockedReadings.add(reading)
            lockedInputLengths.add(input.length)
            activeStart = (activeStart + input.length).coerceAtMost(composing.length)
            return
        }
        val active = activeInput()
        val separatorPrefix = if (layoutId == LayoutId.ALPHA) active.takeWhile { it == '\'' }.length else 0
        if (!active.substring(separatorPrefix).startsWith(input)) return
        lockedReadings.add(reading)
        lockedInputLengths.add(separatorPrefix + input.length)
        activeStart = (activeStart + separatorPrefix + input.length).coerceAtMost(composing.length)
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
        if (initialDecodePending) return
        if (index !in candidates.indices) return
        if (drillSyllable >= 0) {
            pickDrilledHomophone(candidates[index].word)
            refreshCandidates()
            render()
            return
        }
        val cand = candidates[index]
        when (candidateRoles.getOrElse(index) { CandidateRole.NORMAL }) {
            CandidateRole.CALCULATOR -> {
                val live = if (learningBlocked) null else Calculator.detect(readCalculatorInput())
                if (live != null && live.expr == calcExpr && live.result == calcResult && !host.hasSelection()) {
                    host.commitText(live.append)
                }
                clearComposingState(); lastWord = null
            }
            CandidateRole.DIRECT -> {
                val text = committedPrefix.toString() + cand.word
                expirePreeditChoiceUndo()
                host.commitText(text)
                applyDeferredLearning()
                clearComposingState(); lastWord = null
            }
            CandidateRole.PREDICTION -> {
                expirePreeditChoiceUndo()
                host.commitText(cand.word)
                if (!learningBlocked) engine.learn(lastWord, cand.word)
                if (!learningBlocked) {
                    userLearning?.observeCommit(lastWord, cand.word, "", System.currentTimeMillis())
                }
                lastWord = cand.word
            }
            CandidateRole.NORMAL -> {
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
            if (calcCand != null && !host.hasSelection()) { calcDismissed = true; return }
            if (host.hasSelection()) host.deleteSelection() else host.deleteBackward()
            lastWord = null
            if (calcCand != null) calcDismissed = true
            return
        }
        when (history.removeLastOrNull()) {
            StepKind.LOCK -> if (lockedReadings.isNotEmpty()) {
                lockedReadings.removeAt(lockedReadings.lastIndex)
                val inputLength = lockedInputLengths.removeAt(lockedInputLengths.lastIndex)
                activeStart = (activeStart - inputLength).coerceAtLeast(0)
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
            pick != null && candidateRoles.firstOrNull() == CandidateRole.DIRECT -> {
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
            var consumedInput = 0; var dropLocks = 0
            while (dropLocks < lockedReadings.size && consumedInput < cand.coveredLen) {
                consumedInput += lockedInputLengths[dropLocks]; dropLocks++
            }
            if (lockedReadings.isNotEmpty() && consumedInput == cand.coveredLen) {
                repeat(dropLocks) {
                    lockedReadings.removeAt(0)
                    lockedInputLengths.removeAt(0)
                }
                activeStart = (activeStart - cand.coveredLen).coerceAtLeast(0)
            } else {
                lockedReadings.clear(); lockedInputLengths.clear(); activeStart = 0
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
            applyDeferredLearning(cand.word, finalReading)
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

    private fun inputForReading(reading: String): String =
        if (layoutId == LayoutId.NINE) T9Pinyin.toT9(reading) else reading

    private fun activeInput(): String =
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

    private fun fullLetters(): String {
        val active = if (layoutId == LayoutId.NINE) {
            chunked(activeInput(), activeCuts()).joinToString("") { T9Pinyin.preedit(it).replace("'", "") }
        } else {
            activeInput()
        }
        return lockedReadings.joinToString("") + active
    }

    private fun readingToInputBounds(): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        var readingPos = 0
        var inputPos = 0
        for ((index, reading) in lockedReadings.withIndex()) {
            val inputLength = lockedInputLengths[index]
            val leading = (inputLength - inputForReading(reading).length).coerceAtLeast(0)
            for (offset in reading.indices) {
                readingPos++
                map[readingPos] = inputPos + leading + offset + 1
            }
            inputPos += inputLength
        }
        val activeReading = if (layoutId == LayoutId.NINE) {
            chunked(activeInput(), activeCuts()).joinToString("") { T9Pinyin.preedit(it).replace("'", "") }
        } else {
            activeInput()
        }
        for (offset in activeReading.indices) {
            readingPos++
            inputPos++
            map[readingPos] = inputPos
        }
        map[readingPos] = composing.length
        return map
    }

    private fun rawComposingText(): String {
        if (composing.isEmpty()) return ""
        return if (layoutId == LayoutId.NINE && lang == Lang.CN) fullLetters() else composing.toString()
    }

    private fun clearComposingState() {
        invalidateCandidateQuery()
        composing.setLength(0)
        candidates = emptyList()
        candidateRoles = emptyList()
        expandedReadingItems = emptyList()
        directCommitCands = emptySet()
        predictionCands = emptySet()
        calcCand = null
        calcExpr = ""
        calcResult = ""
        lockedReadings.clear()
        lockedInputLengths.clear()
        activeStart = 0
        forcedCuts.clear()
        history.clear()
        preeditChoiceUndo.clear()
        deferredLearnEvents.clear()
        committedPrefix.setLength(0)
        drillSyllable = -1
        drillChoices.clear()
    }

    private fun applyDeferredLearning(finalWord: String? = null, finalReading: String = "") {
        if (!learningBlocked) {
            val now = System.currentTimeMillis()
            for (event in deferredLearnEvents) {
                engine.learn(event.prevWord, event.word)
                userLearning?.observeCommit(event.prevWord, event.word, event.reading, now)
            }
            if (finalWord != null) {
                engine.learn(lastWord, finalWord)
                userLearning?.observeCommit(lastWord, finalWord, finalReading, now)
            }
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
        val epoch = invalidateCandidateQuery()
        refreshExpandedReadings(epoch)
        val req = buildDecodeRequest(epoch)
        val lane = decodeLane
        if (lane == null) {
            applyDecodeResult(computeDecode(req))
        } else {
            initialDecodePending = true
            lane.submit(
                compute = { computeDecode(req) },
                apply = { result ->
                    initialDecodePending = false
                    applyDecodeResult(result)
                    render()
                },
                onError = {
                    initialDecodePending = false
                    applyDecodeResult(emptyDecodeResult(req.inputEpoch))
                    render()
                },
            )
        }
    }

    private fun invalidateCandidateQuery(): Long {
        queryInputEpoch++
        candidateContinuation = null
        readingContinuation = null
        candidatePagePending = false
        readingPagePending = false
        initialDecodePending = false
        decodeLane?.markSatisfiedSynchronously()
        return queryInputEpoch
    }

    private fun ensureDecodeApplied() {
        val lane = decodeLane ?: return
        if (!initialDecodePending || !lane.pending) return
        applyDecodeResult(computeDecode(buildDecodeRequest(queryInputEpoch)))
        initialDecodePending = false
        lane.markSatisfiedSynchronously()
    }

    private class PagePull<T>(
        private val inputEpoch: Long,
        private val firstPage: () -> CandidatePage<T>,
        private val nextPage: (CandidateContinuation<T>) -> CandidatePage<T>,
    ) {
        private val pending = ArrayDeque<T>()
        private var continuation: CandidateContinuation<T>? = null
        private var started = false
        private var exhausted = false
        private var pageFetchAvailable = true

        fun beginOutputPage() {
            pageFetchAvailable = true
        }

        fun nextItem(): T? {
            while (pending.isEmpty() && !exhausted) {
                if (!pageFetchAvailable) return null
                pageFetchAvailable = false
                val page = if (!started) {
                    started = true
                    firstPage()
                } else {
                    val token = continuation
                    if (token == null) {
                        exhausted = true
                        break
                    }
                    nextPage(token)
                }
                if (page.inputEpoch != inputEpoch) {
                    continuation = null
                    exhausted = true
                    break
                }
                pending.addAll(page.items)
                continuation = page.continuation
                if (pending.isEmpty() && continuation == null) exhausted = true
            }
            return pending.removeFirstOrNull()
        }

        fun hasMoreItems(): Boolean = pending.isNotEmpty() || !started || (!exhausted && continuation != null)
    }

    private class PullPageSource<T>(
        private val pull: () -> T?,
        private val hasMore: () -> Boolean,
        private val beginPage: () -> Unit,
    ) : CandidatePageSource<T> {
        private var exhausted = false

        override fun next(pageSize: Int): CandidateSlice<T> {
            beginPage()
            val items = ArrayList<T>(pageSize)
            while (items.size < pageSize && !exhausted) {
                val item = pull()
                if (item == null) {
                    if (!hasMore()) exhausted = true
                    break
                } else {
                    items.add(item)
                }
            }
            return CandidateSlice(items, !exhausted && hasMore())
        }
    }

    private class DecodeRequest(
        val engine: CandidateEngine,
        val inputEpoch: Long,
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
        val context: CharSequence,
        val calculatorInput: CharSequence,
    )

    private class DecodeResult(
        val page: CandidatePage<CandidateEntry>,
        val calcExpr: String,
        val calcResult: String,
    )

    private fun emptyDecodeResult(inputEpoch: Long = queryInputEpoch): DecodeResult =
        DecodeResult(CandidatePage(emptyList(), null, inputEpoch), "", "")

    private fun expandingTextBeforeCursor(initialLength: Int, needsEarlier: (String) -> Boolean): String {
        var requested = initialLength
        var snapshot = host.textBeforeCursor(requested).toString()
        while (snapshot.length >= requested && needsEarlier(snapshot)) {
            val expandedLength = if (requested > Int.MAX_VALUE / 2) Int.MAX_VALUE else requested * 2
            if (expandedLength == requested) break
            val expanded = host.textBeforeCursor(expandedLength).toString()
            if (expanded.length <= snapshot.length) break
            requested = expandedLength
            snapshot = expanded
        }
        return snapshot
    }

    private fun readCalculatorInput(): String =
        expandingTextBeforeCursor(CALC_INITIAL_SCAN_LEN, Calculator::needsEarlierText)

    private fun readCandidateContext(): String {
        val required = engine.requiredContextCodePoints().coerceAtLeast(1)
        return expandingTextBeforeCursor(CONTEXT_INITIAL_SCAN_LEN) { text ->
            var offset = text.length
            var count = 0
            while (offset > 0 && count < required) {
                val cp = text.codePointBefore(offset)
                if (!Character.isIdeographic(cp)) {
                    return@expandingTextBeforeCursor offset == 1 && Character.isLowSurrogate(text[0])
                }
                offset -= Character.charCount(cp)
                count++
            }
            count < required
        }
    }

    private fun buildDecodeRequest(inputEpoch: Long): DecodeRequest {
        val locked = mode() == Mode.PINYIN && composing.isNotEmpty() && lockedReadings.isNotEmpty()
        val full = if (locked) fullLetters() else ""
        val bounds = if (locked) readingToInputBounds() else emptyMap()
        val readingCuts = if (locked) {
            val lockCuts = ArrayList<Int>(lockedReadings.size); var acc = 0
            for (r in lockedReadings) { acc += r.length; if (acc < full.length) lockCuts.add(acc) }
            val forced = forcedCuts
                .filter { it in (activeStart + 1) until composing.length }
                .mapNotNull { inputCut -> bounds.entries.firstOrNull { it.value == inputCut }?.key }
            (forced + lockCuts).toSet()
        } else {
            emptySet()
        }
        return DecodeRequest(
            engine = engine,
            inputEpoch = inputEpoch,
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
            bounds = bounds,
            isNine = layoutId == LayoutId.NINE,
            forcedCuts = forcedCuts.toSet(),
            associationsEnabled = associationsEnabled,
            learningBlocked = learningBlocked,
            calcDismissed = calcDismissed,
            lastWord = lastWord,
            context = readCandidateContext(),
            calculatorInput = readCalculatorInput(),
        )
    }

    private fun applyDecodeResult(r: DecodeResult) {
        if (r.page.inputEpoch != queryInputEpoch) return
        candidates = r.page.items.map { it.candidate }
        candidateRoles = r.page.items.map { it.role }
        candidateContinuation = r.page.continuation
        directCommitCands = r.page.items.filter { it.role == CandidateRole.DIRECT }.mapTo(HashSet()) { it.candidate }
        predictionCands = r.page.items.filter { it.role == CandidateRole.PREDICTION }.mapTo(HashSet()) { it.candidate }
        calcCand = r.page.items.firstOrNull { it.role == CandidateRole.CALCULATOR }?.candidate
        calcExpr = r.calcExpr
        calcResult = r.calcResult
    }

    fun requestMoreCandidates() {
        val continuation = candidateContinuation ?: return
        if (candidatePagePending || initialDecodePending) return
        val epoch = queryInputEpoch
        candidatePagePending = true
        val compute = {
            synchronized(decodeLock) {
                continueCandidatePage(continuation, epoch)
            }
        }
        val lane = decodeLane
        if (lane == null) {
            val page = runCatching(compute).getOrElse {
                candidatePagePending = false
                candidateContinuation = null
                return
            }
            candidatePagePending = false
            appendCandidatePage(page)
            render()
        } else {
            lane.submit(
                compute = compute,
                apply = { page ->
                    candidatePagePending = false
                    appendCandidatePage(page)
                    render()
                },
                onError = {
                    candidatePagePending = false
                    candidateContinuation = null
                },
            )
        }
    }

    private fun appendCandidatePage(page: CandidatePage<CandidateEntry>) {
        if (page.inputEpoch != queryInputEpoch) return
        candidateContinuation = page.continuation
        if (page.items.isEmpty()) return
        candidates = candidates + page.items.map { it.candidate }
        candidateRoles = candidateRoles + page.items.map { it.role }
        directCommitCands = directCommitCands + page.items
            .filter { it.role == CandidateRole.DIRECT }
            .map { it.candidate }
        predictionCands = predictionCands + page.items
            .filter { it.role == CandidateRole.PREDICTION }
            .map { it.candidate }
    }

    internal fun hasMoreCandidatesForTest(): Boolean = candidateContinuation != null

    internal fun requestAllCandidatesForTest() {
        while (candidateContinuation != null) requestMoreCandidates()
    }

    private fun computeDecode(req: DecodeRequest): DecodeResult = synchronized(decodeLock) {
        var calcE = ""
        var calcR = ""
        val source = when {
            req.drillSyllable >= 0 && !req.composingEmpty && req.mode == Mode.PINYIN -> drillCandidateSource(req)
            !req.composingEmpty && req.mode == Mode.PINYIN -> composingCandidateSource(req)
            req.composingEmpty && req.committedPrefixEmpty -> {
                val match = if (req.learningBlocked || req.calcDismissed) null else Calculator.detect(req.calculatorInput)
                when {
                    match != null -> {
                        calcE = match.expr
                        calcR = match.result
                        ListCandidatePageSource(listOf(CandidateEntry(Cand(match.append, 0), CandidateRole.CALCULATOR)))
                    }
                    !req.associationsEnabled || req.learningBlocked -> ListCandidatePageSource(emptyList())
                    else -> predictionCandidateSource(req)
                }
            }
            else -> ListCandidatePageSource(emptyList())
        }
        DecodeResult(firstCandidatePage(source, req.inputEpoch), calcE, calcR)
    }

    private fun baseCandidatePull(req: DecodeRequest): PagePull<Cand> {
        val first = {
            if (req.lockedNonEmpty) {
                req.engine.candidatesForLockedReadingCoveredPage(
                    req.full,
                    req.inputEpoch,
                    req.readingCuts,
                    req.context,
                )
            } else {
                val page = req.engine.candidatesCoveredPage(
                    req.raw,
                    req.isNine,
                    req.inputEpoch,
                    req.forcedCuts,
                    req.context,
                )
                if (page.items.isNotEmpty() || page.continuation != null || !req.isNine) {
                    page
                } else {
                    val prefix = T9Pinyin.longestDecodablePrefix(req.raw)
                    if (prefix.length in 1 until req.raw.length) {
                        req.engine.candidatesCoveredPage(
                            prefix,
                            t9 = true,
                            inputEpoch = req.inputEpoch,
                            context = req.context,
                        )
                    } else {
                        page
                    }
                }
            }
        }
        return PagePull(
            req.inputEpoch,
            firstPage = first,
            nextPage = { continuation -> req.engine.continuePage(continuation, req.inputEpoch) },
        )
    }

    private fun composingCandidateSource(req: DecodeRequest): CandidatePageSource<CandidateEntry> {
        val base = baseCandidatePull(req)
        val associations = PagePull(
            req.inputEpoch,
            firstPage = { InputAssociations.lookupPage(req.rawComposing, req.inputEpoch) },
            nextPage = { continuation -> continueCandidatePage(continuation, req.inputEpoch) },
        )
        val seenGlyphs = HashSet<String>()
        var baseHeadPending = true
        var associationsExhausted = false
        return PullPageSource(
            pull = pull@{
                if (baseHeadPending) {
                    baseHeadPending = false
                    val head = base.nextItem()
                    if (head != null) return@pull CandidateEntry(remapCovered(req, head), CandidateRole.NORMAL)
                }
                while (!associationsExhausted) {
                    val glyph = associations.nextItem()
                    if (glyph == null) {
                        if (associations.hasMoreItems()) return@pull null
                        associationsExhausted = true
                    } else if (seenGlyphs.add(SymbolCatalog.foldFullWidth(glyph))) {
                        return@pull CandidateEntry(Cand(glyph, req.composingLen), CandidateRole.DIRECT)
                    }
                }
                base.nextItem()?.let { CandidateEntry(remapCovered(req, it), CandidateRole.NORMAL) }
            },
            hasMore = {
                baseHeadPending || !associationsExhausted || associations.hasMoreItems() || base.hasMoreItems()
            },
            beginPage = {
                base.beginOutputPage()
                associations.beginOutputPage()
            },
        )
    }

    private fun predictionCandidateSource(req: DecodeRequest): CandidatePageSource<CandidateEntry> {
        val predictions = PagePull(
            req.inputEpoch,
            firstPage = { req.engine.predictPage(req.lastWord, req.inputEpoch) },
            nextPage = { continuation -> req.engine.continuePage(continuation, req.inputEpoch) },
        )
        return PullPageSource(
            pull = { predictions.nextItem()?.let { CandidateEntry(Cand(it, 0), CandidateRole.PREDICTION) } },
            hasMore = predictions::hasMoreItems,
            beginPage = predictions::beginOutputPage,
        )
    }

    private fun drillCandidateSource(req: DecodeRequest): CandidatePageSource<CandidateEntry> {
        val reading = if (req.lockedNonEmpty) req.full else req.raw
        val syls = req.engine.syllablesForReading(reading, req.readingCuts)
        if (req.drillSyllable !in syls.indices) return ListCandidatePageSource(emptyList())
        val readingEnd = syls[req.drillSyllable].end
        val coveredLen = if (req.lockedNonEmpty) req.bounds[readingEnd] ?: readingEnd else readingEnd
        val homophones = PagePull(
            req.inputEpoch,
            firstPage = {
                req.engine.homophonesForReadingAtPage(
                    reading,
                    req.drillSyllable,
                    req.inputEpoch,
                    req.readingCuts,
                )
            },
            nextPage = { continuation -> req.engine.continuePage(continuation, req.inputEpoch) },
        )
        return PullPageSource(
            pull = {
                homophones.nextItem()?.let {
                    CandidateEntry(Cand(it, coveredLen.coerceIn(1, req.composingLen)), CandidateRole.NORMAL)
                }
            },
            hasMore = homophones::hasMoreItems,
            beginPage = homophones::beginOutputPage,
        )
    }

    private fun remapCovered(req: DecodeRequest, candidate: Cand): Cand =
        if (!req.lockedNonEmpty) candidate
        else Cand(candidate.word, req.bounds[candidate.coveredLen] ?: candidate.coveredLen.coerceAtMost(req.composingLen))

    private fun currentSyllables(): List<Syllable> {
        if (composing.isEmpty()) return emptyList()
        val req = buildDecodeRequest(queryInputEpoch)
        val reading = if (req.lockedNonEmpty) req.full else req.raw
        return req.engine.syllablesForReading(reading, req.readingCuts)
    }

    private fun savePreeditChoiceUndo() {
        preeditChoiceUndo.addLast(PreeditChoiceUndo(
            composing = composing.toString(),
            committedPrefix = committedPrefix.toString(),
            lockedReadings = lockedReadings.toList(),
            lockedInputLengths = lockedInputLengths.toList(),
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
        invalidateCandidateQuery()
    }

    fun onHostContextChanged() {
        expirePreeditChoiceUndo()
        refreshCandidates()
        render()
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
        lockedInputLengths.clear(); lockedInputLengths.addAll(snap.lockedInputLengths)
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
        if (drillChoices.containsKey(0)) {
            commitChosenLeftPrefix()
        } else {
            drillSyllable = syls.indices.firstOrNull { !drillChoices.containsKey(it) } ?: -1
        }
    }

    private fun commitChosenLeftPrefix() {
        val req = buildDecodeRequest(queryInputEpoch)
        val reading = if (req.lockedNonEmpty) req.full else req.raw
        val syls = req.engine.syllablesForReading(reading, req.readingCuts)
        var k = 0
        while (drillChoices.containsKey(k) && k < syls.size) k++
        if (k == 0) return
        val word = (0 until k).joinToString("") { drillChoices[it] ?: "" }
        val readingEnd = syls[k - 1].end
        val coveredLen = (
            if (req.lockedNonEmpty) req.bounds[readingEnd] ?: readingEnd else readingEnd
        ).coerceIn(1, composing.length)
        val carried = HashMap<Int, String>()
        for ((idx, ch) in drillChoices) if (idx >= k) carried[idx - k] = ch
        drillSyllable = -1
        commitCandidate(Cand(word, coveredLen))
        drillChoices.clear()
        drillChoices.putAll(carried)
        if (drillChoices.isNotEmpty() && composing.isNotEmpty()) {
            val remainingSyllables = currentSyllables()
            drillSyllable = remainingSyllables.indices.firstOrNull {
                !drillChoices.containsKey(it)
            } ?: -1
        }
    }

    private fun applyCase(s: String): String = if (shifted) s.uppercase() else s

    private fun preeditText(): String {
        val prefix = committedPrefix.toString()
        if (composing.isEmpty()) return prefix
        val tail = if (mode() == Mode.PINYIN) {
            val locked = lockedReadings.joinToString("'")
            val rest = if (layoutId == LayoutId.NINE) {
                T9Pinyin.preedit(activeInput(), activeCuts().toSet())
            } else {
                T9Pinyin.preeditLetters(activeInput(), activeCuts().toSet())
            }
            when {
                locked.isEmpty() -> rest
                rest.isEmpty() -> locked
                else -> "$locked'${rest.trimStart('\'')}"
            }
        } else composing.toString()
        return prefix + tail
    }

    internal fun nineLeftColumn(): List<Key> {
        val w = 0.85f
        if (composing.isEmpty()) return customSymbolKeys
        val active = activeInput()
        if (active.isEmpty()) {
            if (lockedReadings.isEmpty()) return emptyList()
            val lastDigits = T9Pinyin.toT9(lockedReadings.last())
            return T9Pinyin.leftColumnReadings(lastDigits, NINE_LEFT_MAX)
                .map { Key(it, output = it, action = KeyAction.PICK_READING, weight = w) }
        }
        val firstCut = activeCuts().firstOrNull()
        val chunk = if (firstCut != null) active.substring(0, firstCut) else active
        val readings = T9Pinyin.leftColumnReadings(chunk, NINE_LEFT_MAX)
        val visible = if (lockedReadings.isEmpty()) readings else listOf(lockedReadings.last()) + readings
        return visible.map { Key(it, output = it, action = KeyAction.PICK_READING, weight = w) }
    }

    private fun render() {
        val v = view ?: return
        val layout = when (layoutId) {
            LayoutId.NINE -> Layouts.nine(lang, nineLeftColumn(), composing.isNotEmpty())
            LayoutId.NUMPAD -> Layouts.numpad(customOperatorKeys)
            else -> Layouts.forId(layoutId, lang, composing.isNotEmpty())
        }
        v.showKeyboard(layout, shifted, shiftState == ShiftState.LOCK, lang)
        val readings = expandedReadings()
        v.showCandidates(candidates.map { it.word }, preeditText(), readings, selectedExpandedReadingIndex(readings), chineseGateActive())
    }

    internal fun shiftStateName(): String = shiftState.name

    private fun refreshExpandedReadings(inputEpoch: Long) {
        val page = firstCandidatePage(
            ListCandidatePageSource(expandedReadingValues()),
            inputEpoch,
            CANDIDATE_PAGE_SIZE,
        )
        expandedReadingItems = page.items
        readingContinuation = page.continuation
    }

    private fun expandedReadingValues(): List<String> = when {
        drillChoices.isNotEmpty() && drillSyllable >= 0 ->
            currentSyllables().getOrNull(drillSyllable)?.reading?.let(::listOf) ?: emptyList()
        layoutId == LayoutId.ALPHA && mode() == Mode.PINYIN && composing.isNotEmpty() -> {
            val active = activeInput()
            val separatorPrefix = active.takeWhile { it == '\'' }.length
            val body = active.substring(separatorPrefix)
            val forcedEnd = activeCuts().firstOrNull { it > separatorPrefix }?.minus(separatorPrefix)
            val separatorEnd = body.indexOf('\'').takeIf { it >= 0 }
            val chunkEnd = listOfNotNull(forcedEnd, separatorEnd).minOrNull() ?: body.length
            val chunk = body.substring(0, chunkEnd.coerceIn(0, body.length))
            val next = T9Pinyin.leftColumnLetterReadings(chunk, Int.MAX_VALUE)
            when {
                lockedReadings.isEmpty() -> next
                next.isEmpty() -> listOf(lockedReadings.last())
                else -> listOf(lockedReadings.last()) + next
            }
        }
        layoutId == LayoutId.NINE && mode() == Mode.PINYIN && composing.isNotEmpty() -> {
            val active = activeInput()
            if (active.isEmpty()) {
                lockedReadings.lastOrNull()?.let {
                    T9Pinyin.leftColumnReadings(T9Pinyin.toT9(it), Int.MAX_VALUE)
                }.orEmpty()
            } else {
                val firstCut = activeCuts().firstOrNull()
                val chunk = if (firstCut != null) active.substring(0, firstCut) else active
                val next = T9Pinyin.leftColumnReadings(chunk, Int.MAX_VALUE)
                if (lockedReadings.isEmpty()) next else listOf(lockedReadings.last()) + next
            }
        }
        else -> emptyList()
    }

    internal fun expandedReadings(): List<String> = expandedReadingItems

    fun requestMoreReadings() {
        val continuation = readingContinuation ?: return
        if (readingPagePending) return
        val epoch = queryInputEpoch
        readingPagePending = true
        val page = continueCandidatePage(continuation, epoch)
        readingPagePending = false
        if (page.inputEpoch != queryInputEpoch) return
        readingContinuation = page.continuation
        if (page.items.isNotEmpty()) {
            expandedReadingItems = expandedReadingItems + page.items
            render()
        }
    }

    internal fun hasMoreReadingsForTest(): Boolean = readingContinuation != null

    internal fun requestAllReadingsForTest() {
        while (readingContinuation != null) requestMoreReadings()
    }

    private fun selectedExpandedReadingIndex(readings: List<String>): Int = when {
        drillSyllable >= 0 ->
            currentSyllables().getOrNull(drillSyllable)?.reading?.let(readings::indexOf) ?: -1
        mode() == Mode.PINYIN && composing.isNotEmpty() && lockedReadings.isNotEmpty() ->
            readings.indexOf(lockedReadings.last())
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
        if (initialDecodePending) return
        val readings = expandedReadings()
        if (index !in readings.indices) return
        expirePreeditChoiceUndo()
        val reading = readings[index]
        val recentLockedReading = lockedReadings.lastOrNull()
        val lockedIndex = recentLockedReading?.let(readings::indexOf) ?: -1
        if (mode() == Mode.PINYIN && composing.isNotEmpty() &&
            index == lockedIndex && recentLockedReading == reading
        ) {
            drillSyllable = lockedReadings.lastIndex
        } else {
            drillSyllable = -1
            drillChoices.clear()
            handlePickReading(Key(reading, output = reading, action = KeyAction.PICK_READING))
        }
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
        const val CALC_INITIAL_SCAN_LEN = 32
        const val CONTEXT_INITIAL_SCAN_LEN = 16
    }
}

internal fun dedupeFullHalfGlyphs(glyphs: List<String>): List<String> {
    if (glyphs.size <= 1) return glyphs
    val seen = HashSet<String>(glyphs.size * 2)
    return glyphs.filter { seen.add(SymbolCatalog.foldFullWidth(it)) }
}
