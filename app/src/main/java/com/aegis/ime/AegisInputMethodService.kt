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

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.aegis.ime.R
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.EngineAssets
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.GraphemeText
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LayoutPanelView
import com.aegis.ime.ime.ParallelLoad
import com.aegis.ime.ime.SelectionMath
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.CustomSymbolStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import java.io.File

class AegisInputMethodService : InputMethodService(), ImeHost {

    private lateinit var controller: KeyboardController
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeWorker: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "aegis-decode").apply { isDaemon = true }
        }
    private val decodeLane = DecodeLane(
        worker = decodeWorker,
        main = java.util.concurrent.Executor { r -> mainHandler.post(r) },
        logError = { Log.e("Aegis", "decode failed", it) },
    )
    @Volatile private var panelTextSnapshot: String? = null
    private val userModel = UserModel()
    private val userLearning = UserLearning()
    private val userDbFile by lazy { File(filesDir, "userdb.txt") }
    private val userLearnFile by lazy { File(filesDir, "userlearn.txt") }
    @Volatile private var userDbMtime = 0L
    @Volatile private var userLearnMtime = 0L

    private var inputView: InputView? = null

    private var backCallback: OnBackInvokedCallback? = null
    private var backRegistered = false
    private var emojiView: EmojiView? = null
    private var clipboardView: ClipboardView? = null
    private var symbolsView: SymbolsView? = null
    private var editPanelView: EditPanelView? = null
    private var layoutPanelView: LayoutPanelView? = null
    private var customSymbolView: CustomSymbolPanel? = null
    private val customSymbolStore by lazy { CustomSymbolStore(getSharedPreferences("aegis", MODE_PRIVATE)) }
    private var customOperatorView: CustomSymbolPanel? = null
    private val customOperatorStore by lazy { CustomSymbolStore(getSharedPreferences("aegis", MODE_PRIVATE), "custom_operators") }
    private val zhSymbolPalette: List<String> by lazy {
        SymbolCatalog.categories.first { it.id == "zh" }.symbols.filter { it !in Layouts.nineFixedPunctuation }
    }
    private val mathOperatorPalette: List<String> by lazy {
        val hidden = Layouts.defaultNumpadOperators.toSet() - Layouts.numpadOperatorsInCustomPalette.toSet()
        SymbolCatalog.categories.first { it.id == "math" }.symbols.filter { it !in hidden }
    }
    private var selecting = false
    private var selAnchor = -1
    private var selMoving = -1
    private var deletedSnapshot: CharSequence? = null
    private val panelInput = com.aegis.ime.ime.PanelTextInput()
    private enum class InputPurpose { EDIT_PHRASE, ADD_PHRASE, EDIT_NOTE, ADD_CATEGORY, RENAME_CATEGORY }
    private var inputPurpose: InputPurpose? = null
    private var inputCat = ""
    private var inputOld = ""
    private var inlineOriginPhrasesTab = false
    private var pendingPhraseAdds: List<String> = emptyList()
    private var pendingMoveFrom = ""
    private var pendingMoveTexts: List<String> = emptyList()
    private val clipboardStore by lazy { ClipboardStore(filesDir).also { it.load(); LiveUserData.clipboardHost = it } }
    private val clipboardPendingWriteFlush: () -> Unit = { clipboardStore.flushPendingWrites() }
    private val symbolUsageStore by lazy { SymbolUsageStore(filesDir).also { it.load() } }
    private val emojiUsageStore by lazy { SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).also { it.load() } }
    @Volatile private var secureField = false

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

    private enum class RestorablePanel {
        EXPANDED_CANDIDATES, EDIT, LAYOUT, EMOJI, CLIPBOARD, SYMBOLS, CUSTOM_SYMBOLS, CUSTOM_OPERATORS,
    }

    internal data class TransientStateSnapshot(
        val inputActive: Boolean,
        val secure: Boolean,
        val targetPackage: String?,
        val composition: String,
        val editActive: Boolean,
        val editText: String,
        val editPurpose: String?,
        val panel: String?,
        val panelDetail: String?,
    )

    private var currentEditorTarget: EditorTarget? = null
    private var layoutSessionPackage: String? = null
    private var inputSessionActive = false
    private var resetControllerOnNextInputView = false
    private var restorablePanel: RestorablePanel? = null

    private var panelCacheDensityDpi = 0
    private var clipboardRecreationState: ClipboardView.RecreationState? = null
    private var restoreClipboardWithoutCapture = false
    private var splitSelectionInputConnection: InputConnection? = null
    private var frameworkWillFinishInput = false
    private var panelInputTitle = ""
    private var lastCopy: String? = null
    @Volatile private var userStoresLoaded = false
    @Volatile private var engineSig = ""
    @Volatile private var engineReloading = false
    private var imePalette = ImePalette.STATIC_LIGHT

    private fun computePalette(): ImePalette {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return ImePalette.from(this, dark)
    }

    private fun applyPaletteEverywhere() {
        imePalette = computePalette()
        inputView?.applyPalette(imePalette)
        emojiView?.applyPalette(imePalette)
        clipboardView?.applyPalette(imePalette)
        symbolsView?.applyPalette(imePalette)
        editPanelView?.applyPalette(imePalette)
        layoutPanelView?.applyPalette(imePalette)
        customSymbolView?.applyPalette(imePalette)
        customOperatorView?.applyPalette(imePalette)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {

        unregisterBackCallback()
        val previousInputView = inputView
        val nextDensityDpi = newConfig.densityDpi.takeIf { it > 0 } ?: resources.displayMetrics.densityDpi
        val densityChanged = panelCacheDensityDpi > 0 && panelCacheDensityDpi != nextDensityDpi
        if (densityChanged) invalidateDensityBoundPanelCaches(nextDensityDpi)
        else panelCacheDensityDpi = nextDensityDpi
        super.onConfigurationChanged(newConfig)
        applyPaletteEverywhere()

        if (densityChanged && previousInputView != null && inputView === previousInputView) {
            val replacement = onCreateInputView() as InputView
            setInputView(replacement)
            replacement.post { if (inputView === replacement) syncBackCallback() }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    private val clipChangedListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onSystemClipChanged() }

    private val settingsHotApply = SettingsHotApply(
        onCnLayout = { id ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setCnDefaultLayout(id)
            }
        },
        onDefaultLang = { l ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setDefaultLang(l)
            }
        },
        onAssociations = { on ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setAssociationsEnabled(on)
            }
        },
        onAutoLearn = { on ->
            userLearning.enabled = on
            userModel.autoLearnEnabled = on
        },
        onFuzzyRules = { rules ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setFuzzyRules(rules)
            }
        },
        onEngineAssetsChanged = {
            Handler(Looper.getMainLooper()).post { maybeReloadEngine() }
        },
        onKeyHaptics = { on -> mainHandler.post { inputView?.setKeyHaptics(on) } },
        onKeyPreviewNine = { on -> mainHandler.post { inputView?.setKeyPreviewNine(on) } },
        onKeyPreviewAlpha = { on -> mainHandler.post { inputView?.setKeyPreviewAlpha(on) } },
        onLetterCase = { mode -> mainHandler.post { inputView?.setLetterCase(mode) } },
    )

    private val liveUserDictHost by lazy {
        LiveUserDictHost(userModel, userDbFile, userLearning, userLearnFile) { savedUserDb, savedUserLearn ->
            savedUserDb?.let { userDbMtime = it }
            savedUserLearn?.let { userLearnMtime = it }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipChangedListener) }
        runCatching {
            getSharedPreferences("aegis", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(settingsHotApply)
        }
        LiveUserData.onRestored = {
            mainHandler.post {
                runCatching { clipboardStore.load() }
                runCatching { symbolUsageStore.load() }
                runCatching { emojiUsageStore.load() }
                runCatching {
                    userLearning.load(userLearnFile)
                    userLearnMtime = userLearnFile.lastModified()
                }
                LiveUserData.restoreInProgress = false
            }
        }
        LiveUserData.registerClipboardPersistenceHooks(clipboardPendingWriteFlush)
        controller = KeyboardController(this, DictEngine(null, null, null), decodeLane)
        controller.onShowEmoji = { showEmojiPanel() }
        controller.onShowClipboard = { showClipboardPanel() }
        controller.onShowPhrases = { showPhrasePanel() }
        controller.onShowEdit = { showEditPanel() }
        controller.onShowLayout = { showLayoutPanel() }
        controller.onShowSymbols = { showSymbolsPanel() }
        controller.onShowSettings = { openSettings() }
        controller.onShowCustomSymbols = { showCustomSymbolPanel() }
        controller.onShowCustomOperators = { showCustomOperatorPanel() }
        controller.onClosePanel = { inputView?.showPanel(null) }
        controller.userLearning = userLearning
        controller.setCustomSymbols(customSymbolStore.list())
        controller.setCustomOperators(customOperatorStore.list())
        Thread {
            val (_, engine) = ParallelLoad.both({
                runCatching { com.aegis.ime.engine.InputAssociations.lookup("nihao") }
                runCatching {
                    userModel.load(userDbFile)
                    userDbMtime = userDbFile.lastModified()
                }.onFailure { Log.e("Aegis", "userdb load failed", it) }
                runCatching {
                    userLearning.load(userLearnFile)
                    userLearnMtime = userLearnFile.lastModified()
                }.onFailure { Log.e("Aegis", "userlearn load failed", it) }
                userStoresLoaded = true
                UserDictHot.host = liveUserDictHost
            }, {
                buildEngine()
            })
            Handler(Looper.getMainLooper()).post {
                controller.setEngine(engine)
                maybeReloadEngine()
            }
        }.apply { name = "aegis-dict-load"; isDaemon = true }.start()
    }

    private fun buildEngine(): DictEngine {
        com.aegis.ime.dict.ModelDownload.recoverInterruptedDictionaryInstall(filesDir)
        val (sig, dictionaries) = com.aegis.ime.dict.ModelDownload.withDictionaryGeneration {
            EngineAssets.signature(File(filesDir, "downloaded")) to
                ParallelLoad.results(com.aegis.ime.dict.ModelDownload.DICT_BIN_FILES.map { { loadDict(it) } })
        }
        val (dict, t9Dict, initialsDict) = dictionaries
        val fuzzyRules = currentFuzzyRules()
        val lm = loadLm(com.aegis.ime.dict.ModelDownload.LM_NAME)
        val octagram = runCatching { OctagramReader.fromDownloads(this, "wanxiang-lts-zh-hans.gram") }
            .onFailure { Log.e("Aegis", "octagram load failed", it) }.getOrNull()
        val engine = DictEngine(dict, t9Dict, lm, userModel, fuzzyRules, initialsDict, octagram, userLearning)
        engineSig = sig
        return engine
    }

    private fun currentFuzzyRules(): Set<String> =
        SettingsHotApply.fuzzyRules(getSharedPreferences("aegis", MODE_PRIVATE))

    private fun maybeReloadEngine() {
        if (engineSig.isEmpty() || engineReloading) return
        if (com.aegis.ime.dict.ModelDownload.installInProgress(filesDir)) return
        val current = EngineAssets.signature(File(filesDir, "downloaded"))
        if (!EngineAssets.needsReload(engineSig, current)) return
        engineReloading = true
        try {
            Thread {
                val ok = runCatching {
                    val engine = buildEngine()
                    Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
                }.onFailure { Log.e("Aegis", "engine hot-reload failed", it) }.isSuccess
                engineReloading = false
                if (ok) Handler(Looper.getMainLooper()).post { maybeReloadEngine() }
            }.apply { name = "aegis-dict-reload"; isDaemon = true }.start()
        } catch (t: Throwable) {
            Log.e("Aegis", "engine hot-reload thread start failed", t)
            engineReloading = false
        }
    }

    private fun editorTarget(info: EditorInfo?): EditorTarget? {
        val packageName = info?.packageName?.takeIf { it.isNotBlank() } ?: return null
        val stableFieldId = info.fieldId.takeIf { it > 0 }
        val stableFieldName = info.fieldName?.takeIf { it.isNotBlank() }
        val inputKind = info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return EditorTarget(packageName, stableFieldId, stableFieldName, inputKind)
    }

    private fun clearEditorTransientState(resetController: Boolean, abortInline: Boolean = true, preserveLayout: Boolean = false) {
        if (abortInline) abortInlineInput(hideBar = false)

        inputView?.clearEditorTransientUiImmediately()
        restorablePanel = null
        clipboardRecreationState = null
        stopSelecting()
        deletedSnapshot = null
        if (resetController && ::controller.isInitialized) controller.reset(preserveLayout)
    }

    private fun canRestoreCurrentSession(): Boolean =
        inputSessionActive && !secureField && currentEditorTarget != null

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)

        val nextTarget = editorTarget(info)
        val nextSecure = info != null && com.aegis.ime.user.ClipboardPolicy.isSensitive(info.inputType)
        val preserveLayout = nextTarget != null && nextTarget.packageName == layoutSessionPackage
        val sameRestart = restarting && inputSessionActive && !secureField && !nextSecure &&
            currentEditorTarget?.let { previous -> nextTarget?.let(previous::sameEditor) } == true

        if (!sameRestart) {
            clearEditorTransientState(resetController = true, preserveLayout = preserveLayout)

            resetControllerOnNextInputView = true
        }
        if (nextTarget != null) layoutSessionPackage = nextTarget.packageName
        secureField = nextSecure
        currentEditorTarget = nextTarget
        inputSessionActive = nextTarget != null

        controller.setLearningBlocked(
            info != null && com.aegis.ime.user.ClipboardPolicy.blocksLearning(info.inputType, info.imeOptions),
        )
        val quiet = userStoresLoaded && !liveUserDictHost.writing && !LiveUserData.restoreInProgress
        if (quiet && (!userModel.dirty || !userModel.readable) && userDbFile.lastModified() > userDbMtime) {
            val readAt = userDbFile.lastModified()
            runCatching { userModel.reload(userDbFile) }
            userDbMtime = readAt
        }
        if (quiet && !userLearning.dirty && userLearnFile.lastModified() > userLearnMtime) {
            val readAt = userLearnFile.lastModified()
            runCatching { userLearning.load(userLearnFile) }
            userLearnMtime = readAt
        }
        maybeReloadEngine()
    }

    override fun onFinishInput() {
        frameworkWillFinishInput = true
        try {
            clipboardView?.finishSplitSelection()
            inputView?.finishCopySplitSelection()
        } finally {
            frameworkWillFinishInput = false
        }
        super.onFinishInput()
        abortInlineInput()
        clearEditorTransientState(resetController = true, abortInline = false, preserveLayout = layoutSessionPackage != null)
        currentEditorTarget = null
        inputSessionActive = false
        resetControllerOnNextInputView = false
        secureField = false
        if (LiveUserData.restoreInProgress) return
        liveUserDictHost.scheduleSave()
    }

    private fun downloadedOverride(name: String): File? =
        EngineAssets.downloadedOverride(File(filesDir, "downloaded"), name)

    private fun loadDict(name: String): BinaryDict? {
        val file = downloadedOverride(name) ?: return null
        return runCatching { BinaryDict.fromFile(file) }
            .onFailure { Log.e("Aegis", "dict load failed: $name", it) }
            .getOrNull()
    }

    private fun loadLm(name: String): CharBigramLM? {
        val file = downloadedOverride(name)
        if (file == null) {
            Log.w("Aegis", "lm not installed, ranking without it: $name")
            return null
        }
        return runCatching { CharBigramLM.fromFile(file) }
            .onFailure { Log.e("Aegis", "lm load failed: $name", it) }
            .getOrNull()
    }

    override fun onCreateInputView(): View {

        unregisterBackCallback()
        if (panelCacheDensityDpi == 0) panelCacheDensityDpi = resources.displayMetrics.densityDpi
        val view = InputView(this).apply {
            onKey = { key -> controller.onKey(key) }
            onPickCandidate = { index -> controller.onPickCandidate(index) }
            onPickReading = { index -> controller.onPickReadingIndex(index) }
            onFunction = { f -> controller.onBarFunction(f) }
            onBackspaceSwipe = { up -> backspaceSwipe(up) }
            onPanelBackspace = { controller.onPanelBackspace() }
            onPanelClear = { controller.onPanelClear() }
            onExpandClosed = { controller.clearDrill() }
            onCollapse = { requestHideSelf(0) }
            onCopyCommit = { t -> commitLargeText(t) }
            onCopySelectionChanged = { text ->
                controller.expireCandidateChoiceUndo()
                updateSplitSelection(text)
            }
            onCopySelectionFinished = { finishSplitSelection() }
            onCopyDismiss = {
                controller.expireCandidateChoiceUndo()
                lastCopy = null
            }
            onEditConfirm = { confirmInlineInput() }
            onEditCancel = { cancelInlineInput() }
            onOverlayChanged = { syncBackCallback() }
        }
        inputView = view
        view.onPanelChanged = { panel ->
            if (inputView === view) {
                restorablePanel = classifyPanel(view, panel)
            }
        }
        panelInput.onChange = { txt ->
            panelTextSnapshot = txt
            view.setEditText(txt)
        }
        controller.attachView(view)
        imePalette = computePalette()
        view.applyPalette(imePalette)
        val fbPrefs = getSharedPreferences("aegis", MODE_PRIVATE)
        view.setKeyHaptics(SettingsHotApply.keyHaptics(fbPrefs))
        view.setKeyPreviewNine(SettingsHotApply.keyPreviewNine(fbPrefs))
        view.setKeyPreviewAlpha(SettingsHotApply.keyPreviewAlpha(fbPrefs))
        view.setLetterCase(SettingsHotApply.letterCase(fbPrefs))

        if (canRestoreCurrentSession()) restoreTransientUi(view)
        return view
    }

    private fun classifyPanel(view: InputView, panel: View?): RestorablePanel? = when {
        panel == null -> null
        view.isExpandedCandidatePanel(panel) -> RestorablePanel.EXPANDED_CANDIDATES
        panel === editPanelView -> RestorablePanel.EDIT
        panel === layoutPanelView -> RestorablePanel.LAYOUT
        panel === emojiView -> RestorablePanel.EMOJI
        panel === clipboardView -> RestorablePanel.CLIPBOARD
        panel === symbolsView -> RestorablePanel.SYMBOLS
        panel === customSymbolView -> RestorablePanel.CUSTOM_SYMBOLS
        panel === customOperatorView -> RestorablePanel.CUSTOM_OPERATORS
        else -> null
    }

    private fun invalidateDensityBoundPanelCaches(nextDensityDpi: Int) {
        clipboardRecreationState = if (restorablePanel == RestorablePanel.CLIPBOARD) {
            clipboardView?.recreationState()
        } else {
            null
        }
        emojiView = null
        clipboardView = null
        symbolsView = null
        editPanelView = null
        layoutPanelView = null
        customSymbolView = null
        customOperatorView = null
        panelCacheDensityDpi = nextDensityDpi
    }

    private fun restoreClipboardPanel() {
        restoreClipboardWithoutCapture = true
        try {
            showClipboardPanel()
        } finally {
            restoreClipboardWithoutCapture = false
        }
    }

    private fun restoreTransientUi(candidateView: InputView? = inputView) {
        val view = candidateView ?: return
        when (restorablePanel) {
            RestorablePanel.EXPANDED_CANDIDATES -> view.showExpandedCandidates()
            RestorablePanel.EDIT -> editPanelView?.let(view::showPanel) ?: showEditPanel()
            RestorablePanel.LAYOUT -> presentLayoutPanel()
            RestorablePanel.EMOJI -> emojiView?.let(view::showPanel) ?: showEmojiPanel()
            RestorablePanel.CLIPBOARD -> restoreClipboardPanel()
            RestorablePanel.SYMBOLS -> symbolsView?.let(view::showPanel) ?: showSymbolsPanel()
            RestorablePanel.CUSTOM_SYMBOLS -> customSymbolView?.let(view::showPanel) ?: showCustomSymbolPanel()
            RestorablePanel.CUSTOM_OPERATORS -> customOperatorView?.let(view::showPanel) ?: showCustomOperatorPanel()
            null -> Unit
        }
        if (panelInput.active) {
            view.setEditTitle(panelInputTitle)
            view.setEditText(panelInput.text())
            view.showEditBar(true)
        }
    }

    internal fun transientStateForTest(): TransientStateSnapshot {
        val composition = if (::controller.isInitialized) controller.preeditForTest() else ""
        val panel = restorablePanel?.name
        val panelDetail = when (restorablePanel) {
            RestorablePanel.CLIPBOARD -> if (clipboardView?.isClipboardTabForTest() == true) "HISTORY" else "PHRASES"
            RestorablePanel.EXPANDED_CANDIDATES -> "CANDIDATES"
            null -> null
            else -> "DEFAULT"
        }
        return TransientStateSnapshot(
            inputActive = inputSessionActive,
            secure = secureField,
            targetPackage = currentEditorTarget?.packageName,
            composition = composition,
            editActive = panelInput.active,
            editText = panelInput.text(),
            editPurpose = inputPurpose?.name,
            panel = panel,
            panelDetail = panelDetail,
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val viewTarget = editorTarget(info)
        val viewSecure = info != null && com.aegis.ime.user.ClipboardPolicy.isSensitive(info.inputType)
        val preserveLayout = viewTarget != null && viewTarget.packageName == layoutSessionPackage
        val targetMatches = inputSessionActive && !secureField && !viewSecure &&
            currentEditorTarget?.let { active -> viewTarget?.let(active::sameEditor) } == true
        if (!targetMatches) {

        abortInlineInput()
            clearEditorTransientState(resetController = true, abortInline = false, preserveLayout = preserveLayout)
            currentEditorTarget = null
            inputSessionActive = false
            secureField = viewSecure
            resetControllerOnNextInputView = true
        }
        val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
        controller.setCnDefaultLayout(SettingsHotApply.cnLayout(prefs))
        controller.setDefaultLang(SettingsHotApply.defaultLang(prefs))
        controller.setAssociationsEnabled(SettingsHotApply.associationsOn(prefs))
        userLearning.enabled = SettingsHotApply.autoLearnOn(prefs)
        userModel.autoLearnEnabled = SettingsHotApply.autoLearnOn(prefs)
        controller.setFuzzyRules(currentFuzzyRules())
        inputView?.setKeyHaptics(SettingsHotApply.keyHaptics(prefs))
        inputView?.setKeyPreviewNine(SettingsHotApply.keyPreviewNine(prefs))
        inputView?.setKeyPreviewAlpha(SettingsHotApply.keyPreviewAlpha(prefs))
        inputView?.setLetterCase(SettingsHotApply.letterCase(prefs))
        if (resetControllerOnNextInputView) {
            controller.reset(preserveLayout)
            resetControllerOnNextInputView = false
        }

        val lc = lastCopy
        if (inputView?.isComposing() == true) {
            inputView?.hideCopyBar()
        } else if (com.aegis.ime.user.ClipboardPolicy.shouldRestoreCopyBar(lc, secureField)) {
            if (restorablePanel == RestorablePanel.EDIT) inputView?.stageCopyBar(lc!!)
            else inputView?.showCopyBar(lc!!)
        } else {
            inputView?.hideCopyBar()
        }
        applyPaletteEverywhere()
        if (targetMatches && canRestoreCurrentSession()) restoreTransientUi()
    }

    private fun buildBackCallback(): OnBackInvokedCallback = OnBackInvokedCallback {
        inputView?.closeTopOverlay()
        syncBackCallback()
    }

    internal fun syncBackCallback() {
        val iv = inputView ?: return
        val dispatcher = iv.findOnBackInvokedDispatcher()
        val want = iv.hasOverlay()
        if (want && !backRegistered && dispatcher != null) {
            val cb = backCallback ?: buildBackCallback().also { backCallback = it }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backRegistered = true
        } else if (!want && backRegistered) {
            backCallback?.let { dispatcher?.unregisterOnBackInvokedCallback(it) }
            backRegistered = false
        }
    }

    private fun unregisterBackCallback() {
        if (!backRegistered) return
        backCallback?.let { inputView?.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
        backRegistered = false
    }

    internal fun backCallbackRegisteredForTest(): Boolean = backRegistered

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
        )
        outInsets.contentTopInsets = spec.contentTop
        outInsets.visibleTopInsets = spec.visibleTop
        outInsets.touchableInsets = spec.touchableInsets
        outInsets.touchableRegion.setEmpty()
        spec.touchableRegion?.let(outInsets.touchableRegion::set)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        editPanelView?.setHasSelection(newSelStart != newSelEnd)
    }

    private fun showEditPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(editPanelView)) { iv.showPanel(null); return }
        stopSelecting()
        val ep = editPanelView ?: EditPanelView(this).also {
            it.onAction = { a -> handleEdit(a) }
            it.onBackspaceSwipe = { up -> backspaceSwipe(up) }
            editPanelView = it
        }
        ep.applyPalette(imePalette)
        ep.setSelecting(false)
        ep.setHasSelection(!currentInputConnection?.getSelectedText(0).isNullOrEmpty())
        iv.showPanel(ep)
    }

    private fun showLayoutPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(layoutPanelView)) { iv.showPanel(null); return }
        presentLayoutPanel()
    }

    private fun presentLayoutPanel() {
        val iv = inputView ?: return
        val lp = layoutPanelView ?: LayoutPanelView(this).also {
            it.onPick = { choice ->
                controller.applyLayoutChoice(choice)
                inputView?.showPanel(null)
            }
            it.onBack = { inputView?.showPanel(null) }
            layoutPanelView = it
        }
        lp.applyPalette(imePalette)
        lp.setActiveChoice(controller.currentLayoutChoice())
        iv.showPanel(lp)
    }

    private fun handleEdit(action: EditAction) {
        val keyAction = action.keyAction
        if (keyAction == null) {
            if (panelInput.active && action != EditAction.BACK) return
            controller.expireCandidateChoiceUndo()
        }
        when (action) {
            EditAction.UP -> nav(KeyEvent.KEYCODE_DPAD_UP, SelectionMath.Move.UP)
            EditAction.DOWN -> nav(KeyEvent.KEYCODE_DPAD_DOWN, SelectionMath.Move.DOWN)
            EditAction.LEFT -> nav(KeyEvent.KEYCODE_DPAD_LEFT, SelectionMath.Move.LEFT)
            EditAction.RIGHT -> nav(KeyEvent.KEYCODE_DPAD_RIGHT, SelectionMath.Move.RIGHT)
            EditAction.HOME -> nav(KeyEvent.KEYCODE_MOVE_HOME, SelectionMath.Move.HOME)
            EditAction.END -> nav(KeyEvent.KEYCODE_MOVE_END, SelectionMath.Move.END)
            EditAction.START_SELECT -> toggleSelecting()
            EditAction.DELETE -> keyAction?.let { key ->
                controller.onKey(Key(action = key))
                resetSelectionAnchor()
            }
            EditAction.COPY -> currentInputConnection?.performContextMenuAction(android.R.id.copy)
            EditAction.CUT -> {
                currentInputConnection?.performContextMenuAction(android.R.id.cut)
                resetSelectionAnchor()
            }
            EditAction.SELECT_ALL -> {
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                resetSelectionAnchor()
            }
            EditAction.PASTE -> {
                clipboardStore.latest()?.let(::commitLargeText)
                resetSelectionAnchor()
            }
            EditAction.BACK -> { stopSelecting(); inputView?.showPanel(null) }
        }
    }

    private fun backspaceSwipe(up: Boolean) {
        if (!controller.onBackspaceSwipe(up)) {
            if (panelInput.active) { if (up) panelInput.begin("") }
            else handleBackspaceSwipe(up)
        }
    }

    private fun toggleSelecting() {
        selecting = !selecting
        if (selecting) {
            val et = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
            val base = et?.startOffset?.takeIf { it >= 0 } ?: 0
            selAnchor = base + (et?.selectionStart?.coerceAtLeast(0) ?: 0)
            selMoving = base + (et?.selectionEnd?.coerceAtLeast(0) ?: (selAnchor - base))
        } else stopSelecting()
        editPanelView?.setSelecting(selecting)
    }

    private fun stopSelecting() { selecting = false; selAnchor = -1; selMoving = -1 }

    private fun resetSelectionAnchor() {
        if (!selecting) return
        selAnchor = -1
        selMoving = -1
    }

    private fun nav(keyCode: Int, move: SelectionMath.Move) {
        if (!selecting) { sendKey(keyCode, false); return }
        val ic = currentInputConnection
        val et = ic?.getExtractedText(ExtractedTextRequest(), 0)
        val text = et?.text
        if (ic == null || text == null) { sendKey(keyCode, true); return }
        val base = if (et.startOffset >= 0) et.startOffset else 0
        if (selAnchor < 0) selAnchor = base + et.selectionStart.coerceAtLeast(0)
        if (selMoving < 0) selMoving = base + et.selectionEnd.coerceAtLeast(0)
        selMoving = base + SelectionMath.step(text, selMoving - base, move)
        ic.setSelection(minOf(selAnchor, selMoving), maxOf(selAnchor, selMoving))
    }

    private fun sendKey(code: Int, shift: Boolean) {
        if (panelInput.active) return
        val ic = currentInputConnection ?: return
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun handleBackspaceSwipe(up: Boolean) {
        if (panelInput.active) return
        val ic = currentInputConnection ?: return
        controller.expireCandidateChoiceUndo()
        if (up) {
            val all = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text
            if (!all.isNullOrEmpty()) {
                deletedSnapshot = all
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
                resetSelectionAnchor()
            }
        } else {
            deletedSnapshot?.let {
                ic.commitText(it, 1)
                deletedSnapshot = null
                resetSelectionAnchor()
            }
        }
    }

    private fun showEmojiPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(emojiView)) { iv.showPanel(null); return }
        val ev = emojiView ?: EmojiView(this).also {
            it.recentProvider = { emojiUsageStore.recent() }
            it.onEmoji = { e -> emojiUsageStore.record(e); commitExternalText(e) }
            it.onClearRecents = { emojiUsageStore.clear() }
            it.onBackspace = { panelBackspace() }
            it.onBack = { inputView?.showPanel(null) }
            emojiView = it
        }
        ev.resetToDefault()
        ev.applyPalette(imePalette)
        iv.showPanel(ev)
    }

    private fun showClipboardPanel() {
        val iv = inputView ?: return
        val captureCurrentClip = !restoreClipboardWithoutCapture
        if (!restoreClipboardWithoutCapture) {
        if (iv.isPanelShowing(clipboardView)) { iv.showPanel(null); return }
        }
        if (captureCurrentClip) clipboardRecreationState = null
        val recreationState = clipboardRecreationState
        val cv = clipboardView ?: ClipboardView(this).also {
            it.historyProvider = { clipboardStore.history() }
            it.categoriesProvider = { clipboardStore.categories() }
            it.phrasesInProvider = { cat -> clipboardStore.phrasesIn(cat) }
            it.phraseNoteProvider = { cat, text -> clipboardStore.noteFor(cat, text) }
            it.onPick = { t -> commitLargeText(t); inputView?.showPanel(null) }
            it.onCopyBlocksToAegis = { blocks -> copyBlocksToAegis(blocks) }
            it.onSplitSelectionChanged = { text -> updateSplitSelection(text) }
            it.onSplitSelectionFinished = { finishSplitSelection() }
            it.onBack = { inputView?.showPanel(null) }
            it.onDeleteClips = { list -> clipboardStore.deleteAll(list) }
            it.onDeletePhrasesFrom = { cat, list -> list.forEach { clipboardStore.deletePhraseFrom(cat, it) } }
            it.onSaveAsPhrasesTo = { cat, list ->
                val added = clipboardStore.addPhrasesTo(cat, list)
                if (list.size == 1) toast(if (added > 0) getString(R.string.svc_phrase_added) else getString(R.string.svc_phrase_exists))
            }
            it.onEditPhrase = { cat, text -> beginInlineEdit(cat, text) }
            it.onMovePhrase = { from, text, to -> clipboardStore.movePhrase(from, text, to) }
            it.onMovePhrasesTo = { from, list, to -> clipboardStore.movePhrasesTo(from, list, to) }
            it.onReorderPhrase = { cat, fromIdx, toIdx -> clipboardStore.reorderPhrase(cat, fromIdx, toIdx) }
            it.onReorderCategory = { fromIdx, toIdx -> clipboardStore.reorderCategory(fromIdx, toIdx) }
            it.onAddPhrase = { cat -> beginInlineAddPhrase(cat) }
            it.onAddCategory = { beginInlineAddCategory() }
            it.onAddCategoryThenAdd = { texts -> beginInlineAddCategory(texts) }
            it.onAddCategoryThenMove = { from, texts -> beginInlineAddCategory(pendingMove = from to texts) }
            it.onRenameCategory = { old -> beginInlineRenameCategory(old) }
            it.onDeleteCategory = { name -> clipboardStore.deleteCategory(name) }
            it.onEditNote = { cat, text -> beginInlineEditNote(cat, text) }
            it.onClearCategory = { cat -> clipboardStore.clearPhrasesIn(cat) }
            it.onExportPhrases = { launchPhraseTransfer(export = true) }
            it.onImportPhrasesWithMode = { merge -> launchPhraseTransfer(export = false, merge = merge) }
            it.onClearHistory = { clipboardStore.clearHistory() }
            it.historyEnabledProvider = { historyEnabled() }
            it.historyReadableProvider = { clipboardStore.historyReadable }
            it.onSetHistoryEnabled = { on -> setHistoryEnabled(on) }
            clipboardView = it
        }

        if (captureCurrentClip) {
        cv.resetToDefault()
        }
        cv.applyPalette(imePalette)
        recreationState?.let(cv::restoreRecreationState)
        clipboardRecreationState = null
        iv.showPanelImmediately(cv)
        iv.post {
            if (captureCurrentClip) captureClip()
            clipboardStore.reloadPhrases()
            refreshOpenClipboardPanel()
        }
    }

    private fun showPhrasePanel() {
        val iv = inputView ?: return
        val open = clipboardView
        if (open != null && iv.isPanelShowing(open)) {
            open.resetToDefault()
            open.showPhraseTab("")
            return
        }
        showClipboardPanel()
        clipboardView?.takeIf(iv::isPanelShowing)?.showPhraseTab("")
    }

    private fun showCustomSymbolPanel() {
        val iv = inputView ?: return
        val panel = customSymbolView ?: CustomSymbolPanel(this).also {
            it.addPalette = zhSymbolPalette
            it.current = { customSymbolStore.list() }
            it.onAdd = { s -> customSymbolStore.add(s); controller.setCustomSymbols(customSymbolStore.list()); it.refresh() }
            it.onRemove = { s -> customSymbolStore.remove(s); controller.setCustomSymbols(customSymbolStore.list()); it.refresh() }
            it.onBack = { inputView?.showPanel(null) }
            customSymbolView = it
        }
        panel.resetToDefault()
        panel.applyPalette(imePalette)
        iv.showPanel(panel)
    }

    private fun showCustomOperatorPanel() {
        val iv = inputView ?: return
        val panel = customOperatorView ?: CustomSymbolPanel(this).also {
            it.backTitle = getString(R.string.csp_operators_title)
            it.paletteTitle = getString(R.string.csp_section_all_operators)
            it.addPalette = mathOperatorPalette
            it.current = { customOperatorStore.list() }
            it.onAdd = { s -> customOperatorStore.add(s); controller.setCustomOperators(customOperatorStore.list()); it.refresh() }
            it.onRemove = { s -> customOperatorStore.remove(s); controller.setCustomOperators(customOperatorStore.list()); it.refresh() }
            it.onBack = { inputView?.showPanel(null) }
            customOperatorView = it
        }
        panel.applyPalette(imePalette)
        iv.showPanel(panel)
    }

    private fun showSymbolsPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(symbolsView)) { iv.showPanel(null); return }
        val sv = symbolsView ?: SymbolsView(this).also {
            it.recentProvider = { symbolUsageStore.recent() }
            it.recentOriginOf = { s -> symbolUsageStore.originOf(s) }
            it.onClearRecents = { symbolUsageStore.clear() }
            it.onSymbol = { s, origin -> symbolUsageStore.record(s, origin); commitExternalSymbol(s) }
            it.onBackspace = { panelBackspace() }
            it.onBack = { inputView?.showPanel(null) }
            symbolsView = it
        }
        sv.resetToDefault()
        sv.applyPalette(imePalette)
        iv.showPanel(sv)
    }

    private fun openSettings() {
        requestHideSelf(0)
        runCatching {
            startActivity(
                android.content.Intent(this, com.aegis.ime.ui.SetupActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun launchPhraseTransfer(export: Boolean, merge: Boolean = true) {
        runCatching {
            startActivity(
                com.aegis.ime.ui.PhraseTransferActivity.launchIntent(this, export, merge),
            )
            inputView?.showPanel(null)
        }
    }


    private fun beginInlineEdit(category: String, phrase: String) {
        inputPurpose = InputPurpose.EDIT_PHRASE; inputCat = category; inputOld = phrase
        startInlineInput(getString(R.string.svc_edit_phrase), phrase)
    }

    private fun beginInlineAddPhrase(category: String) {
        inputPurpose = InputPurpose.ADD_PHRASE; inputCat = category; inputOld = ""
        startInlineInput(getString(R.string.svc_add_phrase), "")
    }

    private fun beginInlineEditNote(category: String, text: String) {
        inputPurpose = InputPurpose.EDIT_NOTE; inputCat = category; inputOld = text
        startInlineInput(getString(R.string.svc_note), clipboardStore.noteFor(category, text))
    }

    private fun beginInlineAddCategory(
        pendingAdds: List<String> = emptyList(),
        pendingMove: Pair<String, List<String>>? = null,
    ) {
        inputPurpose = InputPurpose.ADD_CATEGORY; inputCat = ""; inputOld = ""
        pendingPhraseAdds = pendingAdds
        pendingMoveFrom = pendingMove?.first ?: ""
        pendingMoveTexts = pendingMove?.second ?: emptyList()
        startInlineInput(getString(R.string.svc_new_category), "")
    }

    private fun beginInlineRenameCategory(old: String) {
        inputPurpose = InputPurpose.RENAME_CATEGORY; inputCat = ""; inputOld = old
        startInlineInput(getString(R.string.svc_rename_category), old)
    }

    private fun startInlineInput(title: String, initial: String) {
        val iv = inputView ?: return
        inlineOriginPhrasesTab = clipboardView?.recreationState()?.phrasesTab == true
        iv.showPanel(null)
        panelInputTitle = title
        panelInput.begin(initial)
        iv.setEditTitle(title)
        iv.setEditText(initial)
        iv.showEditBar(true)
    }

    private fun confirmInlineInput() {
        val text = panelInput.text()
        when (inputPurpose) {
            InputPurpose.EDIT_PHRASE -> clipboardStore.editPhrase(inputCat, inputOld, text)
            InputPurpose.ADD_PHRASE -> { val t = text.trim(); if (t.isNotEmpty()) addSinglePhraseWithToast(inputCat, t) }
            InputPurpose.EDIT_NOTE -> clipboardStore.setPhraseNote(inputCat, inputOld, text)
            InputPurpose.ADD_CATEGORY -> {
                val name = text.trim()
                if (name.isNotEmpty()) {
                    clipboardStore.addCategory(name)
                    if (pendingPhraseAdds.isNotEmpty()) clipboardStore.addPhrasesTo(name, pendingPhraseAdds)
                    if (pendingMoveTexts.isNotEmpty()) clipboardStore.movePhrasesTo(pendingMoveFrom, pendingMoveTexts, name)
                    inputCat = name
                }
            }
            InputPurpose.RENAME_CATEGORY -> { val n = text.trim(); if (clipboardStore.renameCategory(inputOld, n)) inputCat = n }
            null -> {}
        }
        endInlineInput()
    }

    private fun addSinglePhraseWithToast(category: String, text: String) {
        val added = clipboardStore.addPhrasesTo(category, listOf(text))
        toast(if (added > 0) getString(R.string.svc_phrase_added) else getString(R.string.svc_phrase_exists))
    }

    private fun cancelInlineInput() = endInlineInput()

    private fun endInlineInput() {
        val reopenCat = inputCat
        val returningView = inputView
        val returningClipboard = clipboardView
        if (::controller.isInitialized) controller.onPanelClear()
        panelInput.end()
        panelTextSnapshot = null
        panelInputTitle = ""
        inputPurpose = null; inputCat = ""; inputOld = ""; pendingPhraseAdds = emptyList(); pendingMoveFrom = ""; pendingMoveTexts = emptyList()
        if (returningView == null) return
        returningView.dismissEditBarForPanelReturn()
        if (returningClipboard != null) {
            if (inlineOriginPhrasesTab) returningClipboard.showPhraseTab(reopenCat) else returningClipboard.reopenAfterInline(reopenCat)
            returningView.showPanelImmediately(returningClipboard)
        } else {
            clipboardRecreationState = ClipboardView.RecreationState(inlineOriginPhrasesTab, reopenCat)
            restoreClipboardPanel()
        }
    }

    private fun abortInlineInput(hideBar: Boolean = true) {
        if (!panelInput.active && inputPurpose == null) return
        panelInput.end()
        panelTextSnapshot = null
        panelInputTitle = ""
        if (hideBar) inputView?.showEditBar(false)
        inputPurpose = null; inputCat = ""; inputOld = ""; pendingPhraseAdds = emptyList(); pendingMoveFrom = ""; pendingMoveTexts = emptyList()
    }

    private fun captureClip() {
        if (LiveUserData.restoreInProgress) return
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return
        runCatching {
            val clip = clipboardManager.primaryClip ?: return
            val item = clip.getItemAt(0) ?: return
            if (item.uri != null && item.text == null) return
            clipboardStore.record(item.coerceToText(this)?.toString())
        }
    }

    private fun onSystemClipChanged() {
        if (LiveUserData.restoreInProgress) return
        if (!com.aegis.ime.user.ClipboardPolicy.shouldReadSystemClip(false, historyEnabled())) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return
        if (item.uri != null && item.text == null) return
        val t = runCatching { item.coerceToText(this)?.toString() }.getOrNull()?.trim().orEmpty()
        if (t.isNotEmpty()) recordTextClip(t)
    }

    private fun recordTextClip(t: String) {
        clipboardStore.record(t)
        refreshOpenClipboardPanel()
        lastCopy = t
        if (inputView?.isPanelShowing(editPanelView) == true) {
            inputView?.stageCopyBar(t)
        } else {
            if (inputView?.isComposing() != true) inputView?.showCopyBar(t)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun copyBlocksToAegis(blocks: List<String>) {
        if (blocks.isEmpty()) return
        for (block in blocks) clipboardStore.record(block)
        refreshOpenClipboardPanel()
        toast(getString(R.string.svc_saved_to_clipboard))
    }

    private fun updateSplitSelection(text: String) {
        val connection = splitSelectionInputConnection ?: currentInputConnection?.also {
            splitSelectionInputConnection = it
        }
        connection?.setComposingText(text, 1)
    }

    private fun finishSplitSelection() {
        val connection = splitSelectionInputConnection ?: currentInputConnection
        if (!frameworkWillFinishInput) connection?.finishComposingText()
        splitSelectionInputConnection = null
    }

    private fun refreshOpenClipboardPanel() {
        val cv = clipboardView ?: return
        if (inputView?.isPanelShowing(cv) == true) cv.refresh()
    }

    private fun historyEnabled() = getSharedPreferences("aegis", MODE_PRIVATE).getBoolean("clip_history", true)
    private fun setHistoryEnabled(on: Boolean) =
        getSharedPreferences("aegis", MODE_PRIVATE).edit().putBoolean("clip_history", on).apply()

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        unregisterBackCallback()
        if (finishingInput) {
            clearEditorTransientState(resetController = true, preserveLayout = layoutSessionPackage != null)
            currentEditorTarget = null
            inputSessionActive = false
            resetControllerOnNextInputView = false
            secureField = false
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()

        val shownView = inputView
        shownView?.post { if (inputView === shownView) syncBackCallback() }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (panelInput.active && ::controller.isInitialized) controller.onPanelClear()
        clearEditorTransientState(resetController = false)
        layoutSessionPackage = null
        if (::controller.isInitialized) controller.restoreBaseKeyboard()
    }

    override fun onUnbindInput() {
        clearEditorTransientState(resetController = true)
        currentEditorTarget = null
        layoutSessionPackage = null
        inputSessionActive = false
        resetControllerOnNextInputView = false
        secureField = false
        super.onUnbindInput()
    }

    override fun onDestroy() {
        clearEditorTransientState(resetController = true)
        currentEditorTarget = null
        layoutSessionPackage = null
        inputSessionActive = false
        resetControllerOnNextInputView = false
        secureField = false
        unregisterBackCallback()
        runCatching { decodeWorker.shutdownNow() }
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) }
        if (UserDictHot.host === liveUserDictHost) UserDictHot.host = null
        runCatching { liveUserDictHost.flush() }
        liveUserDictHost.stopSaving()
        LiveUserData.unregisterClipboardPersistenceHooks(clipboardPendingWriteFlush)
        clipboardStore.stopSaving()
        if (LiveUserData.clipboardHost === clipboardStore) LiveUserData.clipboardHost = null
        LiveUserData.onRestored = null
        runCatching {
            getSharedPreferences("aegis", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(settingsHotApply)
        }
        super.onDestroy()
    }


    private fun commitExternalText(text: CharSequence) {
        if (panelInput.commit(text)) return
        controller.expireCandidateChoiceUndo()
        currentInputConnection?.commitText(text, 1)
    }

    override fun commitText(text: CharSequence) {
        if (panelInput.commit(text)) return
        currentInputConnection?.commitText(text, 1)
    }

    private fun commitExternalSymbol(symbol: CharSequence) {
        if (panelInput.commit(symbol)) return
        controller.expireCandidateChoiceUndo()
        commitSymbolToEditor(symbol)
    }

    override fun commitSymbol(symbol: CharSequence) {
        if (panelInput.commit(symbol)) return
        commitSymbolToEditor(symbol)
    }

    private fun commitSymbolToEditor(symbol: CharSequence) {
        val ic = currentInputConnection ?: return
        val s = symbol.toString()
        val insertion = SymbolCatalog.insertionFor(
            s,
            hasTextAfterCursor = !ic.getTextAfterCursor(1, 0).isNullOrEmpty(),
        )
        if (insertion.size == 1) {
            ic.commitText(insertion[0], 1)
            return
        }
        ic.beginBatchEdit()
        ic.commitText(insertion[0], 1)
        ic.commitText(insertion[1], 0)
        ic.endBatchEdit()
    }

    private fun commitLargeText(text: CharSequence) {
        if (panelInput.commit(text)) return
        controller.expireCandidateChoiceUndo()
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        com.aegis.ime.ime.LargeCommit.commit(text) { ic.commitText(it, 1) }
        ic.endBatchEdit()
    }

    override fun deleteBackward() {
        if (panelInput.backspace()) return
        deleteLastEditorCluster()
    }

    override fun deleteGraphemeBackward() {
        if (panelInput.backspace()) return
        deleteLastEditorCluster()
    }

    private fun deleteLastEditorCluster() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(GraphemeText.WINDOW, 0) ?: ""
        val n = GraphemeText.lastClusterLength(before)
        ic.deleteSurroundingText(if (n > 0) n else 1, 0)
    }

    override fun panelBackspace() {
        if (panelInput.backspace()) return
        controller.expireCandidateChoiceUndo()
        if (hasSelection()) deleteSelection() else deleteGraphemeBackward()
    }

    override fun textBeforeCursor(n: Int): CharSequence {
        panelTextSnapshot?.let { s -> return s.substring(maxOf(0, s.length - n)) }
        return runCatching { currentInputConnection?.getTextBeforeCursor(n, 0) }.getOrNull() ?: ""
    }

    override fun replaceBeforeCursor(length: Int, text: CharSequence) {
        if (panelInput.replaceBefore(length, text)) return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(length, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    override fun hasSelection(): Boolean =
        if (panelInput.active) false else !currentInputConnection?.getSelectedText(0).isNullOrEmpty()

    override fun deleteSelection() {
        if (panelInput.backspace()) return
        currentInputConnection?.commitText("", 1)
    }

    override fun performEnter() {
        if (panelInput.active) return
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
