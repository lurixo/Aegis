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

package com.aegis.ime

import android.content.Context
import android.os.LocaleList
import android.content.res.Configuration
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.aegis.ime.ui.appLocaleTag
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.GraphemeText
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.SymbolCatalog

class AegisInputMethodService : InputMethodService(), ImeHost {

    private lateinit var controller: KeyboardController
    private val decodeResults = Handler.createAsync(Looper.getMainLooper())
    private val decodeWorker: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
                r.run()
            }, "aegis-decode").apply { isDaemon = true }
        }
    private val decodeHintLock = Any()
    private var decodeHintOpened = false
    private var decodeHint: PerformanceHintManager.Session? = null
    private val decodeLane = DecodeLane(
        worker = decodeWorker,
        main = java.util.concurrent.Executor { r -> decodeResults.post(r) },
        logError = { Log.e("Aegis", "decode failed", it) },
        workDone = ::reportDecodeWork,
    )

    private var inputView: InputView? = null
    private var uiLocaleContext: Context? = null
    private var uiLocaleTags: String? = null

    internal var appLocaleTags: (Context) -> String? = { appLocaleTag(it) }

    private var selStart = -1
    private var selEnd = -1

    @Volatile private var personalizationBlocked = false

    private data class EditorTarget(
        val packageName: String,
        val fieldId: Int?,
        val fieldName: String?,
        val inputKind: Int,
    ) {
        fun sameEditor(other: EditorTarget): Boolean {
            if (packageName != other.packageName || inputKind != other.inputKind) return false
            if (fieldId != null || other.fieldId != null) return fieldId != null && fieldId == other.fieldId
            if (fieldName != null || other.fieldName != null) return fieldName != null && fieldName == other.fieldName
            return true
        }
    }

    private var currentEditorTarget: EditorTarget? = null
    private var layoutSessionPackage: String? = null
    private var inputSessionActive = false
    private var resetControllerOnNextInputView = false

    private fun imeUiContext(): Context {
        val tags = appLocaleTags(this)
        val cached = uiLocaleContext
        if (cached != null && tags == uiLocaleTags) return cached
        uiLocaleTags = tags
        val context = if (tags == null) {
            this
        } else {
            createConfigurationContext(
                Configuration().apply { setLocales(LocaleList.forLanguageTags(tags)) },
            )
        }
        uiLocaleContext = context
        return context
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreate() {
        super.onCreate()
        controller = KeyboardController(
            this, DictEngine(null, null, null), decodeLane,
            emailDomains = com.aegis.ime.ime.EmailDomains(getSharedPreferences("aegis", MODE_PRIVATE)),
        )
    }

    private fun editorTarget(info: EditorInfo?): EditorTarget? {
        val packageName = info?.packageName?.takeIf { it.isNotBlank() } ?: return null
        val stableFieldId = info.fieldId.takeIf { it > 0 }
        val stableFieldName = info.fieldName?.takeIf { it.isNotBlank() }
        val inputKind = info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return EditorTarget(packageName, stableFieldId, stableFieldName, inputKind)
    }

    private fun clearEditorTransientState(resetController: Boolean, abortInline: Boolean = true, preserveLayout: Boolean = false) {
        inputView?.clearEditorTransientUiImmediately()
        if (resetController && ::controller.isInitialized) controller.reset(preserveLayout)
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)

        val nextTarget = editorTarget(info)
        val preserveLayout = nextTarget != null && nextTarget.packageName == layoutSessionPackage
        val identified = nextTarget != null && (nextTarget.fieldId != null || nextTarget.fieldName != null)
        val sameRestart = (restarting || identified) && inputSessionActive &&
            currentEditorTarget?.let { previous -> nextTarget?.let(previous::sameEditor) } == true

        if (!sameRestart) {
            clearEditorTransientState(resetController = true, preserveLayout = preserveLayout)

            resetControllerOnNextInputView = true
        }
        if (nextTarget != null) layoutSessionPackage = nextTarget.packageName
        selStart = info?.initialSelStart ?: -1
        selEnd = info?.initialSelEnd ?: -1
        currentEditorTarget = nextTarget
        inputSessionActive = nextTarget != null

        personalizationBlocked = info != null && com.aegis.ime.user.ClipboardPolicy.blocksLearning(info.imeOptions)
        controller.setLearningBlocked(personalizationBlocked)
        controller.onInputTargetChanged()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        clearEditorTransientState(resetController = true, abortInline = false, preserveLayout = layoutSessionPackage != null)
        currentEditorTarget = null
        inputSessionActive = false
        resetControllerOnNextInputView = false
        personalizationBlocked = false
    }

    override fun onCreateInputView(): View {

        val view = InputView(imeUiContext()).apply {
            onKey = { key -> controller.onKey(key) }
            onPickCandidate = { index -> controller.onPickCandidate(index) }
            onPickReading = { index -> controller.onPickReadingIndex(index) }
            onFunction = { f -> controller.onBarFunction(f) }
            onPanelBackspace = { controller.onPanelBackspace() }
            onPanelClear = { controller.onPanelClear() }
            onExpandClosed = { controller.clearDrill() }
            onCollapse = { requestHideSelf(0) }
            onPreeditTap = { controller.onPreeditTap() }
            onPreeditCaret = { index -> controller.onPreeditCaret(index) }
            onPreeditEditDone = { controller.onPreeditEditDone() }
        }
        inputView = view
        controller.attachView(view)

        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val viewTarget = editorTarget(info)
        val viewBlocksPersonalization = info != null && com.aegis.ime.user.ClipboardPolicy.blocksLearning(info.imeOptions)
        val preserveLayout = viewTarget != null && viewTarget.packageName == layoutSessionPackage
        val targetMatches = inputSessionActive &&
            currentEditorTarget?.let { active -> viewTarget?.let(active::sameEditor) } == true
        if (!targetMatches) {

            clearEditorTransientState(resetController = true, abortInline = false, preserveLayout = preserveLayout)
            currentEditorTarget = null
            inputSessionActive = false
            personalizationBlocked = viewBlocksPersonalization
            resetControllerOnNextInputView = true
        }
        if (resetControllerOnNextInputView) {
            controller.reset(preserveLayout)
            resetControllerOnNextInputView = false
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = inputView ?: return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val normalTop = loc[1] + v.barTopInsetPx()
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = v.isCompactLandscapeDock(),
            normalTop = normalTop,
            windowBottom = loc[1] + v.height,
            surfaceBounds = v.dockTouchableBoundsInWindow(),
            windowBounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height),
            preeditTab = v.preeditTabBoundsInWindow(),
        )
        outInsets.contentTopInsets = spec.contentTop
        outInsets.visibleTopInsets = spec.visibleTop
        outInsets.touchableInsets = spec.touchableInsets
        outInsets.touchableRegion.setEmpty()
        spec.touchableRegion?.let(outInsets.touchableRegion::set)
        spec.touchableExtra?.let { outInsets.touchableRegion.union(it) }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (::controller.isInitialized) controller.onEditorContextChanged()
    }

    private fun sendKey(code: Int, shift: Boolean) =
        sendKeyWithMeta(code, if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

    private fun sendKeyWithMeta(code: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput) {
            clearEditorTransientState(resetController = true, preserveLayout = layoutSessionPackage != null)
            currentEditorTarget = null
            inputSessionActive = false
            resetControllerOnNextInputView = false
            personalizationBlocked = false
        }
    }

    override fun onDestroy() {
        clearEditorTransientState(resetController = true)
        currentEditorTarget = null
        layoutSessionPackage = null
        inputSessionActive = false
        resetControllerOnNextInputView = false
        personalizationBlocked = false
        runCatching { decodeWorker.shutdownNow() }
        synchronized(decodeHintLock) {
            decodeHintOpened = true
            decodeHint?.let { session -> runCatching { session.close() } }
            decodeHint = null
        }
        super.onDestroy()
    }

    override fun commitText(text: CharSequence) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun commitSymbol(symbol: CharSequence) {
        commitSymbolToEditor(symbol)
    }

    private fun commitSymbolToEditor(symbol: CharSequence): Boolean {
        val ic = currentInputConnection ?: return false
        val s = symbol.toString()
        val insertion = SymbolCatalog.insertionFor(
            s,
            textAfterCursor = ic.getTextAfterCursor(1, 0),
        )
        if (insertion.size == 1) {
            return ic.commitText(insertion[0], 1)
        }
        ic.beginBatchEdit()
        return try {
            ic.commitText(insertion[0], 1) && ic.commitText(insertion[1], 0)
        } finally {
            ic.endBatchEdit()
        }
    }

    override fun deleteBackward() {
        deleteLastEditorCluster()
    }

    private fun deleteLastEditorCluster() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(GraphemeText.WINDOW, 0) ?: ""
        val n = GraphemeText.lastClusterLength(before)
        if (n > 1) ic.deleteSurroundingText(n, 0) else sendKey(KeyEvent.KEYCODE_DEL, false)
    }

    private fun reportDecodeWork(nanos: Long) = synchronized(decodeHintLock) {
        if (!decodeHintOpened) {
            decodeHintOpened = true
            decodeHint = runCatching {
                getSystemService(PerformanceHintManager::class.java)
                    ?.createHintSession(intArrayOf(Process.myTid()), DECODE_TARGET_NANOS)
            }.getOrNull()
        }
        decodeHint?.let { session -> runCatching { session.reportActualWorkDuration(nanos) } }
    }

    override fun textBeforeCursor(n: Int): CharSequence {
        return runCatching { currentInputConnection?.getTextBeforeCursor(n, 0) }.getOrNull() ?: ""
    }

    override fun hasSelection(): Boolean {
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) return true
        val ic = currentInputConnection ?: return false
        val around = ic.getSurroundingText(0, 0, 0) ?: return false
        return around.selectionStart >= 0 &&
            around.selectionEnd >= 0 &&
            around.selectionStart != around.selectionEnd
    }

    override fun deleteSelection() {
        sendKey(KeyEvent.KEYCODE_DEL, false)
    }

    override fun performEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val hasAction = !noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (hasAction) {
            sendDefaultEditorAction(true)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }
}

private const val DECODE_TARGET_NANOS = 16_666_667L
