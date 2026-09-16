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

import android.content.ClipData
import android.content.ClipDescription
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
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.aegis.ime.ui.appLocaleTag
import com.aegis.ime.R
import com.aegis.ime.backup.RestoreJournal
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.EngineAssets
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.engine.StoredReadingRepair
import com.aegis.ime.ime.CaretRealign
import com.aegis.ime.ime.ClearedTextRestore
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
import com.aegis.ime.ime.phraseWriteNotice
import com.aegis.ime.ime.SelectionMath
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.ime.ChunkedRead
import com.aegis.ime.ime.EditorSweep
import com.aegis.ime.ime.EditorUndoHistory
import com.aegis.ime.ime.WindowedEditorUndoHistory
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.ClearedTextStore
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.user.ClipboardImages
import com.aegis.ime.user.ClipboardImageTooLargeException
import com.aegis.ime.user.CustomSymbolStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.PhraseChange
import com.aegis.ime.user.PhraseEdit
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDeletionPromises
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.translate.TranslateClient
import com.aegis.ime.translate.TranslateMode
import java.io.File

class AegisInputMethodService : InputMethodService(), ImeHost {

    private lateinit var controller: KeyboardController
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainLane = java.util.concurrent.Executor { r -> mainHandler.post(r) }
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
    private val translateWorker: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "aegis-translate").apply { isDaemon = true }
        }
    private val translateLane = DecodeLane(
        worker = translateWorker,
        main = mainLane,
        logError = { Log.w("Aegis", "translate failed", it) },
    )
    private var translateClient = TranslateClient()
    @Volatile private var panelTextSnapshot: String? = null
    private val userModel = UserModel()
    private val userLearning = UserLearning()
    private val userDbFile by lazy { File(filesDir, "userdb.txt") }
    private val userLearnFile by lazy { File(filesDir, "userlearn.txt") }
    @Volatile private var userDbMtime = 0L
    @Volatile private var userLearnMtime = 0L

    private var inputView: InputView? = null
    private var uiLocaleContext: Context? = null
    private var uiLocaleTags: String? = null

    internal var appLocaleTags: (Context) -> String? = { appLocaleTag(it) }

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
    private var selStart = -1
    private var selEnd = -1
    private var chunkedRead: ChunkedRead? = null
    private var readDropped: (() -> Unit)? = null
    private val readTimeout = Runnable { chunkedRead?.giveUp() }
    private var undoBlocked = true
    private var silentUndo = false
    private var queuedUndos = 0
    private var largeEdit = false
    private var committingContent = false
    private var pasteCompletionNotice = false
    private var cutCompletionNotice: String? = null
    private val undoImageLeases = HashMap<String, com.aegis.ime.user.ClipboardStore.InputImageLease>()
    private val editorUndo = EditorUndoHistory().also {
        it.webWriteSettleMs = WEB_WRITE_SETTLE_MS
        it.selectionProvider = { if (selStart >= 0 && selEnd >= 0) selStart to selEnd else null }
        it.onChange = { refreshUndoAvailability() }
        it.onRetainedImagesChanged = { retained ->
            undoImageLeases.keys.toList().filterNot { key -> key in retained }.forEach { key ->
                undoImageLeases.remove(key)?.close()
            }
        }
        it.onUndoCompleted = { restored -> finishEditingUndo(restored) }
        it.onInsertionCompleted = { inserted ->
            largeEdit = false
            controller.onEditorContextChanged()
            refreshUndoAvailability()
            if (queuedUndos > 0) mainHandler.post(::runQueuedUndo)
            if (pasteCompletionNotice) {
                pasteCompletionNotice = false
                toast(uiString(if (inserted) R.string.edit_paste_done else R.string.edit_paste_failed))
            }
            cutCompletionNotice?.let { notice ->
                cutCompletionNotice = null
                if (inserted) resetSelectionAnchor()
                toast(if (inserted) notice else uiString(R.string.edit_cut_failed))
            }
        }
        it.onClearedContentRestored = { restored -> finishClearedContentRestore(restored) }
        it.onClearCompleted = { kind, text ->
            if (kind == EditorUndoHistory.ClearCapture.PLAIN) clearedText.keep(text) else clearedText.forget()
        }
    }

    override fun getCurrentInputConnection(): InputConnection? {
        val connection = super.getCurrentInputConnection() ?: return null
        return if (undoBlocked || committingContent || clearSweep != null || webClear != null || largeRestore) connection else editorUndo.wrap(connection)
    }

    private fun refreshUndoAvailability() {
        editPanelView?.setUndoAvailable(if (panelInput.active) panelInput.canUndo()
            else !undoBlocked && (editorUndo.hasUndo ||
                (editorUndo.hasPendingUndo || editorUndo.hasPendingInsertion || largeEdit) && !restoring))
    }

    private fun undoEditing() {
        silentUndo = false
        val restored = if (panelInput.active) panelInput.undo()
            else currentInputConnection?.let { !undoBlocked && editorUndo.undo(it) } == true
        if (!restored && editorUndo.hasPendingUndo) { refreshUndoAvailability(); return }
        finishEditingUndo(restored)
    }

    private fun finishEditingUndo(restored: Boolean) {
        val notify = !silentUndo
        silentUndo = false
        if (restored) {
            stopSelecting()
            editPanelView?.setSelecting(false)
            controller.onEditorContextChanged()
        }
        refreshUndoAvailability()
        if (notify) toast(uiString(if (restored) R.string.edit_undo_done else R.string.edit_undo_unavailable))
        if (queuedUndos > 0) {
            if (restored && editorUndo.hasUndo) mainHandler.post(::runQueuedUndo) else queuedUndos = 0
        }
    }

    private fun runQueuedUndo() {
        if (queuedUndos == 0 || editorUndo.hasPendingUndo) return
        if (panelInput.active || chunkedRead?.pending == true || clearSweep != null || webClear != null || restoring ||
            editorUndo.hasPendingInsertion || !editorUndo.hasUndo) {
            queuedUndos = 0
            return
        }
        queuedUndos--
        undoEditing()
    }

    private val clearedText by lazy { ClearedTextStore(filesDir) }
    private val panelInput = com.aegis.ime.ime.PanelTextInput().also {
        it.onTargetChanged = { if (::controller.isInitialized) controller.onInputTargetChanged() }
    }
    private enum class InputPurpose { EDIT_PHRASE, EDIT_CLIP, ADD_PHRASE, EDIT_NOTE, ADD_CATEGORY, RENAME_CATEGORY }
    private var inputPurpose: InputPurpose? = null
    private var inputCat = ""
    private var inputOld = ""
    private var inlineOriginPhrasesTab = false
    private var pendingPhraseAdds: List<String> = emptyList()
    private var pendingMoveFrom = ""
    private var pendingMoveTexts: List<String> = emptyList()
    private val clipboardStore by lazy {
        ClipboardStore(filesDir).also {
            it.load()
            it.reportPhraseWritesTo(mainLane, ::reportPhraseWrite)
            it.reportClipWritesTo(mainLane, ::reportClipWrite)
            LiveUserData.clipboardHost = it
        }
    }
    private val clipboardPendingWriteFlush: () -> Unit = { clipboardStore.flushPendingWrites() }
    private val symbolUsageStore by lazy {
        SymbolUsageStore(filesDir).also {
            it.load()
            it.reportWritesTo(mainLane) { landed -> reportRecentsWrite(landed) { symbolsView?.refresh() } }
        }
    }
    private val emojiUsageStore by lazy {
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).also {
            it.load()
            it.reportWritesTo(mainLane) { landed -> reportRecentsWrite(landed) { emojiView?.refresh() } }
        }
    }

    private fun reportRecentsWrite(landed: Boolean, redraw: () -> Unit) {
        redraw()
        if (!landed) toast(uiString(R.string.svc_recents_update_failed))
    }
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

    private enum class RestorablePanel {
        EXPANDED_CANDIDATES, EDIT, LAYOUT, EMOJI, CLIPBOARD, SYMBOLS, CUSTOM_SYMBOLS, CUSTOM_OPERATORS,
    }

    internal data class TransientStateSnapshot(
        val inputActive: Boolean,
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
    private var panelCacheFontScale = 0f
    private var panelCacheLocales = ""
    private var clipboardRecreationState: ClipboardView.RecreationState? = null
    private var restoreClipboardWithoutCapture = false
    private var splitSelectionInputConnection: InputConnection? = null
    private var frameworkWillFinishInput = false
    private var panelInputTitle = ""
    private var translateOpen = false
    private var translateEngaged = true
    private var translateInputConnection: InputConnection? = null
    private var translatePending: Runnable? = null
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

    private fun uiString(res: Int): String = imeUiContext().getString(res)

    private fun uiString(res: Int, vararg args: Any): String = imeUiContext().getString(res, *args)

    private fun syncUiLocale() {
        if (inputView == null) return
        if (appLocaleTags(this) == uiLocaleTags) return
        uiLocaleContext = null
        invalidateDensityBoundPanelCaches(panelCacheDensityDpi)
        panelCacheLocales = imeUiContext().resources.configuration.locales.toLanguageTags()
        val replacement = onCreateInputView() as InputView
        if (window != null) setInputView(replacement)
        replacement.post { if (inputView === replacement) syncBackCallback() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {

        unregisterBackCallback()
        val previousInputView = inputView
        val nextDensityDpi = newConfig.densityDpi.takeIf { it > 0 } ?: resources.displayMetrics.densityDpi
        val nextFontScale = newConfig.fontScale.takeIf { it > 0f } ?: resources.configuration.fontScale
        val nextLocales = appLocaleTags(this) ?: newConfig.locales.toLanguageTags()
        val densityChanged = panelCacheDensityDpi > 0 &&
            (panelCacheDensityDpi != nextDensityDpi ||
                panelCacheFontScale != nextFontScale ||
                panelCacheLocales != nextLocales)
        if (densityChanged) invalidateDensityBoundPanelCaches(nextDensityDpi)
        else panelCacheDensityDpi = nextDensityDpi
        panelCacheFontScale = nextFontScale
        panelCacheLocales = nextLocales
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
        onCnAssociations = { on ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setCnAssociationsEnabled(on)
            }
        },
        onEnAssociations = { on ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setEnAssociationsEnabled(on)
            }
        },
        onEmailAssociations = { on ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setEmailAssociationsEnabled(on)
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
        onKeySound = { sound -> mainHandler.post { inputView?.setKeySound(sound) } },
        onKeySoundVolume = { volume -> mainHandler.post { inputView?.setKeySoundVolume(volume) } },
        onKeyHaptics = { on -> mainHandler.post { inputView?.setKeyHaptics(on) } },
        onKeyHapticStyle = { style -> mainHandler.post { inputView?.setKeyHapticStyle(style) } },
        onKeyHapticStrength = { strength -> mainHandler.post { inputView?.setKeyHapticStrength(strength) } },
        onKeyPreviewNine = { on -> mainHandler.post { inputView?.setKeyPreviewNine(on) } },
        onKeyPreviewAlpha = { on -> mainHandler.post { inputView?.setKeyPreviewAlpha(on) } },
        onLetterCase = { mode -> mainHandler.post { inputView?.setLetterCase(mode) } },
    )

    private val liveUserDictHost by lazy {
        LiveUserDictHost(
            userModel,
            userDbFile,
            userLearning,
            userLearnFile,
            onSaved = { savedUserDb, savedUserLearn ->
                savedUserDb?.let { userDbMtime = it }
                savedUserLearn?.let { userLearnMtime = it }
            },
            onWordsReplaced = { readingRepair.request() },
        )
    }

    private val readingRepair: StoredReadingRepair by lazy { StoredReadingRepair { liveUserDictHost.repairReadings(it) } }

    private val userLexicon by lazy {
        com.aegis.ime.user.UserLexicon(getSharedPreferences("aegis", MODE_PRIVATE))
    }
    private val userLexiconListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == com.aegis.ime.user.UserLexicon.PREF_ENGLISH_WORDS ||
            key == com.aegis.ime.user.UserLexicon.PREF_EMAIL_DOMAINS ||
            key == com.aegis.ime.user.UserLexicon.PREF_DISABLED_EMAIL_DOMAINS ||
            key.startsWith(com.aegis.ime.user.UserLexicon.EMAIL_COUNT_PREFIX)) {
            mainHandler.post { if (::controller.isInitialized) controller.onUserLexiconChanged() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            RestoreJournal.finishAnyInterrupted(filesDir, getSharedPreferences("aegis", MODE_PRIVATE))
        }.onFailure { Log.e("Aegis", "interrupted restore rollback failed", it) }
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipChangedListener) }
        runCatching {
            getSharedPreferences("aegis", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(settingsHotApply)
            getSharedPreferences("aegis", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(userLexiconListener)
        }
        val reloadUserLexicons = {
            val adoptRestoredStores = {
                runCatching {
                    userModel.replaceWordsFrom(userDbFile)
                    userDbMtime = userDbFile.lastModified()
                }
                runCatching {
                    userLearning.load(userLearnFile)
                    userLearnMtime = userLearnFile.lastModified()
                }
                if (UserDeletionPromises.keep(userModel, userDbFile, userLearning, userLearnFile)) {
                    userDbMtime = userDbFile.lastModified()
                    userLearnMtime = userLearnFile.lastModified()
                }
                LiveUserData.restoreInProgress = false
                readingRepair.request()
            }
            if (!liveUserDictHost.handOff(adoptRestoredStores)) adoptRestoredStores()
        }
        LiveUserData.onLexiconsRestored = {
            mainHandler.post { reloadUserLexicons() }
        }
        LiveUserData.onRestored = {
            mainHandler.post {
                runCatching { clipboardStore.load() }
                runCatching { symbolUsageStore.load() }
                runCatching { emojiUsageStore.load() }
                reloadUserLexicons()
            }
        }
        LiveUserData.registerClipboardPersistenceHooks(clipboardPendingWriteFlush)
        controller = KeyboardController(
            this, DictEngine(null, null, null, userLexicon = userLexicon), decodeLane,
            emailDomains = com.aegis.ime.ime.EmailDomains(getSharedPreferences("aegis", MODE_PRIVATE)),
        )
        controller.onShowEmoji = { showEmojiPanel() }
        controller.onShowClipboard = { showClipboardPanel() }
        controller.onShowTranslate = { toggleTranslateBar() }
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
                }.onFailure {
                    Log.e("Aegis", "userdb load failed", it)
                    if (quarantineCorruptStore(userDbFile)) {
                        runCatching { userModel.load(userDbFile) }
                    }
                }
                runCatching {
                    userLearning.load(userLearnFile)
                    userLearnMtime = userLearnFile.lastModified()
                }.onFailure {
                    Log.e("Aegis", "userlearn load failed", it)
                    if (quarantineCorruptStore(userLearnFile)) {
                        runCatching { userLearning.load(userLearnFile) }
                    }
                }
                if (UserDeletionPromises.keep(userModel, userDbFile, userLearning, userLearnFile)) {
                    userDbMtime = userDbFile.lastModified()
                    userLearnMtime = userLearnFile.lastModified()
                }
                userStoresLoaded = true
                UserDictHot.host = liveUserDictHost
            }, {
                buildEngine()
            })
            readingRepair.request(engine)
            Handler(Looper.getMainLooper()).post {
                controller.setEngine(engine)
                maybeReloadEngine()
            }
            runCatching { clipboardStore }
        }.apply { name = "aegis-dict-load"; isDaemon = true }.start()
    }

    private fun buildEngine(): DictEngine {
        com.aegis.ime.dict.ModelDownload.recoverInterruptedDictionaryInstall(filesDir)
        val (sig, dictionaries) = com.aegis.ime.dict.ModelDownload.withDictionaryGeneration {
            EngineAssets.signature(File(filesDir, "downloaded")) to
                ParallelLoad.results(
                    (com.aegis.ime.dict.ModelDownload.DICT_BIN_FILES +
                        com.aegis.ime.dict.ModelDownload.EN_NAME).map { { loadDict(it) } },
                )
        }
        val (dict, t9Dict, initialsDict, englishDict) = dictionaries
        val fuzzyRules = currentFuzzyRules()
        val lm = loadLm(com.aegis.ime.dict.ModelDownload.LM_NAME)
        val octagram = runCatching { OctagramReader.fromDownloads(this, "wanxiang-lts-zh-hans.gram") }
            .onFailure { Log.e("Aegis", "octagram load failed", it) }.getOrNull()
        val engine = DictEngine(
            dict, t9Dict, lm, userModel, fuzzyRules, initialsDict, octagram, userLearning, englishDict, userLexicon,
        )
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
                    readingRepair.request(engine)
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
        finishTranslation()
        editorUndo.clear()
        queuedUndos = 0
        largeEdit = false
        inputView?.clearEditorTransientUiImmediately()
        if (abortInline) abortInlineInput(hideBar = false)
        pasteCompletionNotice = false
        cutCompletionNotice = null
        dropChunkedRead()
        dropRestoreStream()
        restorablePanel = null
        clipboardRecreationState = null
        stopSelecting()
        if (resetController && ::controller.isInitialized) controller.reset(preserveLayout)
    }

    private fun canRestoreCurrentSession(): Boolean =
        inputSessionActive && currentEditorTarget != null

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        undoBlocked = inputType == InputType.TYPE_NULL ||
            (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        editorUndo.preferNativeUndo = !undoBlocked && inputClass == InputType.TYPE_CLASS_TEXT &&
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        if (undoBlocked) editorUndo.clear()

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
        dropChunkedRead()
        selStart = info?.initialSelStart ?: -1
        selEnd = info?.initialSelEnd ?: -1
        currentEditorTarget = nextTarget
        inputSessionActive = nextTarget != null

        personalizationBlocked = info != null && com.aegis.ime.user.ClipboardPolicy.blocksLearning(info.imeOptions)
        controller.setLearningBlocked(personalizationBlocked)
        controller.onInputTargetChanged()
        val quiet = userStoresLoaded && !liveUserDictHost.writing && !LiveUserData.restoreInProgress
        if (quiet && (!userModel.dirty || !userModel.readable) && userDbFile.lastModified() > userDbMtime) {
            val readAt = userDbFile.lastModified()
            val previous = userDbMtime
            userDbMtime = readAt
            val handedOff = liveUserDictHost.handOff {
                val reloaded = runCatching { userModel.reloadIfUnchanged(userDbFile) }.getOrDefault(false)
                if (UserDeletionPromises.keep(userModel, userDbFile, userLearning, userLearnFile)) {
                    userDbMtime = userDbFile.lastModified()
                    userLearnMtime = userLearnFile.lastModified()
                }
                if (reloaded) readingRepair.request()
            }
            if (!handedOff) userDbMtime = previous
        }
        if (quiet && !userLearning.dirty && userLearnFile.lastModified() > userLearnMtime) {
            val readAt = userLearnFile.lastModified()
            val previous = userLearnMtime
            userLearnMtime = readAt
            val handedOff = liveUserDictHost.handOff {
                runCatching { userLearning.loadIfUnchanged(userLearnFile) }
                if (UserDeletionPromises.keep(userModel, userDbFile, userLearning, userLearnFile)) {
                    userDbMtime = userDbFile.lastModified()
                    userLearnMtime = userLearnFile.lastModified()
                }
            }
            if (!handedOff) userLearnMtime = previous
        }
        maybeReloadEngine()
    }

    override fun onFinishInput() {
        frameworkWillFinishInput = true
        try {
            clipboardView?.finishSplitSelection()
            inputView?.finishCopySplitSelection()
            finishTranslation()
        } finally {
            frameworkWillFinishInput = false
        }
        super.onFinishInput()
        abortInlineInput()
        clearEditorTransientState(resetController = true, abortInline = false, preserveLayout = layoutSessionPackage != null)
        currentEditorTarget = null
        inputSessionActive = false
        resetControllerOnNextInputView = false
        personalizationBlocked = false
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
        if (panelCacheFontScale == 0f) panelCacheFontScale = resources.configuration.fontScale
        if (panelCacheLocales.isEmpty()) panelCacheLocales = imeUiContext().resources.configuration.locales.toLanguageTags()
        val view = InputView(imeUiContext()).apply {
            onKey = { key -> controller.onKey(key) }
            onPickCandidate = { index -> controller.onPickCandidate(index) }
            onPickReading = { index -> controller.onPickReadingIndex(index) }
            onFunction = { f -> controller.onBarFunction(f) }
            onBackspaceSwipe = { up -> backspaceSwipe(up) }
            backspaceSwipeAvailable = { up -> canBackspaceSwipe(up) }
            onPanelBackspace = { controller.onPanelBackspace() }
            onPanelClear = { controller.onPanelClear() }
            onExpandClosed = { controller.clearDrill() }
            onCollapse = { requestHideSelf(0) }
            onCopyCommit = { t ->
                toast(uiString(if (commitLargeText(t)) R.string.edit_paste_done else R.string.edit_paste_failed))
            }
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
            onTranslateClose = { closeTranslateBar() }
            onTranslateFieldTap = { resumeTranslateRouting() }
            onOverlayChanged = { syncBackCallback() }
            onRestoreNotice = { openBackup() }
            onPreeditTap = { controller.onPreeditTap() }
            onPreeditCaret = { index -> controller.onPreeditCaret(index) }
            onPreeditEditDone = { controller.onPreeditEditDone() }
        }
        inputView = view
        view.onPanelChanged = { panel ->
            if (inputView === view) {
                restorablePanel = classifyPanel(view, panel)
            }
        }
        view.onEditTextChanged = { txt ->
            panelTextSnapshot = txt
            refreshUndoAvailability()
            refreshPanelEmailContext(view)
        }
        view.onEditSelectionChanged = { has ->
            if (panelInput.active) editPanelView?.setHasSelection(has)
            refreshPanelEmailContext(view)
        }
        view.onTranslateTextChanged = { text ->
            scheduleTranslation(text)
            refreshPanelEmailContext(view)
        }
        view.onTranslateModeChanged = { mode ->
            setTranslateMode(mode)
            scheduleTranslation(view.translateText())
        }
        view.onTranslateSelectionChanged = { has ->
            if (panelInput.active) editPanelView?.setHasSelection(has)
            refreshPanelEmailContext(view)
        }
        controller.attachView(view)
        imePalette = computePalette()
        view.applyPalette(imePalette)
        val fbPrefs = getSharedPreferences("aegis", MODE_PRIVATE)
        view.setKeyHaptics(SettingsHotApply.keyHaptics(fbPrefs))
        view.setKeyHapticStyle(SettingsHotApply.keyHapticStyle(fbPrefs))
        view.setKeyHapticStrength(SettingsHotApply.keyHapticStrength(fbPrefs))
        view.setKeySoundVolume(SettingsHotApply.keySoundVolume(fbPrefs))
        view.setKeySound(SettingsHotApply.keySound(fbPrefs))
        view.setKeyPreviewNine(SettingsHotApply.keyPreviewNine(fbPrefs))
        view.setKeyPreviewAlpha(SettingsHotApply.keyPreviewAlpha(fbPrefs))
        view.setLetterCase(SettingsHotApply.letterCase(fbPrefs))

        if (canRestoreCurrentSession()) restoreTransientUi(view) else if (translateOpen) bindTranslateInput(view)
        return view
    }

    private fun refreshPanelEmailContext(view: InputView) {
        mainHandler.post {
            if (inputView === view && panelInput.active && ::controller.isInitialized) {
                controller.onEditorContextChanged()
            }
        }
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
            RestorablePanel.EDIT -> restorePanel(view, editPanelView) { showEditPanel() }
            RestorablePanel.LAYOUT -> if (!view.isPanelShowing(layoutPanelView)) presentLayoutPanel()
            RestorablePanel.EMOJI -> restorePanel(view, emojiView) { showEmojiPanel() }
            RestorablePanel.CLIPBOARD -> if (!view.isPanelShowing(clipboardView)) restoreClipboardPanel()
            RestorablePanel.SYMBOLS -> restorePanel(view, symbolsView) { showSymbolsPanel() }
            RestorablePanel.CUSTOM_SYMBOLS -> restorePanel(view, customSymbolView) { showCustomSymbolPanel() }
            RestorablePanel.CUSTOM_OPERATORS -> restorePanel(view, customOperatorView) { showCustomOperatorPanel() }
            null -> Unit
        }
        if (inputPurpose != null) {
            view.setEditTitle(panelInputTitle)
            view.setEditText(panelTextSnapshot.orEmpty())
            view.showEditBar(true)
            panelInput.begin(view.editEditable()) { view.isEditBarShowing() }
        }
        if (translateOpen) bindTranslateInput(view)
    }

    private fun restorePanel(view: InputView, panel: View?, show: () -> Unit) {
        if (view.isPanelShowing(panel)) return
        if (panel != null) view.showPanel(panel) else show()
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
        syncUiLocale()
        super.onStartInputView(info, restarting)
        val viewTarget = editorTarget(info)
        val viewBlocksPersonalization = info != null && com.aegis.ime.user.ClipboardPolicy.blocksLearning(info.imeOptions)
        val preserveLayout = viewTarget != null && viewTarget.packageName == layoutSessionPackage
        val targetMatches = inputSessionActive &&
            currentEditorTarget?.let { active -> viewTarget?.let(active::sameEditor) } == true
        if (!targetMatches) {

        abortInlineInput()
            clearEditorTransientState(resetController = true, abortInline = false, preserveLayout = preserveLayout)
            currentEditorTarget = null
            inputSessionActive = false
            personalizationBlocked = viewBlocksPersonalization
            resetControllerOnNextInputView = true
        }
        val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
        controller.setCnDefaultLayout(SettingsHotApply.cnLayout(prefs))
        controller.setDefaultLang(SettingsHotApply.defaultLang(prefs))
        controller.setCnAssociationsEnabled(SettingsHotApply.cnAssociationsOn(prefs))
        controller.setEnAssociationsEnabled(SettingsHotApply.enAssociationsOn(prefs))
        controller.setEmailAssociationsEnabled(SettingsHotApply.emailAssociationsOn(prefs))
        userLearning.enabled = SettingsHotApply.autoLearnOn(prefs)
        userModel.autoLearnEnabled = SettingsHotApply.autoLearnOn(prefs)
        controller.setFuzzyRules(currentFuzzyRules())
        inputView?.setKeyHaptics(SettingsHotApply.keyHaptics(prefs))
        inputView?.setKeyHapticStyle(SettingsHotApply.keyHapticStyle(prefs))
        inputView?.setKeyHapticStrength(SettingsHotApply.keyHapticStrength(prefs))
        inputView?.setKeySoundVolume(SettingsHotApply.keySoundVolume(prefs))
        inputView?.setKeySound(SettingsHotApply.keySound(prefs))
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
        } else if (com.aegis.ime.user.ClipboardPolicy.shouldRestoreCopyBar(lc)) {
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
        val scan = chunkedRead
        if (scan != null && scan.pending) {
            if (newSelEnd >= 0) {
                mainHandler.removeCallbacks(readTimeout)
                mainHandler.postDelayed(readTimeout, READ_TIMEOUT_MS)
                scan.onCaret(newSelEnd)
            }
            return
        }
        selStart = newSelStart
        selEnd = newSelEnd
        if (!undoBlocked) editorUndo.selectionUpdated(newSelStart, newSelEnd)
        if (clearSweep != null || webClear != null || largeRestore) return
        if ((editorUndo.hasPendingClear || editorUndo.hasPendingUndo || inputView?.isPanelShowing(editPanelView) == true) && !panelInput.active && !undoBlocked) {
            currentInputConnection?.let { editorUndo.canUndo(it) }
            refreshUndoAvailability()
        }
        if (::controller.isInitialized) controller.onEditorContextChanged()
        if (newSelStart >= 0 && newSelEnd >= 0) {
            if (!panelInput.active) editPanelView?.setHasSelection(newSelStart != newSelEnd)
        }
    }

    private fun showEditPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(editPanelView)) { iv.showPanel(null); return }
        stopSelecting()
        val ep = editPanelView ?: EditPanelView(imeUiContext()).also {
            it.onAction = { a -> handleEdit(a) }
            it.onBackspaceSwipe = { up -> backspaceSwipe(up) }
            it.backspaceSwipeAvailable = { up -> canBackspaceSwipe(up) }
            editPanelView = it
        }
        ep.applyPalette(imePalette)
        ep.setSelecting(false)
        ep.setHasSelection(
            if (panelInput.active) panelInput.hasSelection()
            else hasSelection() || !editorReportsNoSelection(),
        )
        currentInputConnection?.let(editorUndo::confirmReconnect)
        refreshUndoAvailability()
        iv.showPanel(ep)
    }


    private fun showLayoutPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(layoutPanelView)) { iv.showPanel(null); return }
        presentLayoutPanel()
    }

    private fun presentLayoutPanel() {
        val iv = inputView ?: return
        val lp = layoutPanelView ?: LayoutPanelView(imeUiContext()).also {
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
        if (action == EditAction.BACK) {
            stopSelecting()
            inputView?.showPanel(null)
            return
        }
        when (action) {
            EditAction.TAB, EditAction.DELETE, EditAction.UNDO, EditAction.FORWARD_DELETE,
            EditAction.SELECT_ALL, EditAction.COPY, EditAction.CUT, EditAction.PASTE -> stopSelecting()
            else -> Unit
        }
        if (panelInput.active && action != EditAction.BACK) { handleEditInPanel(action); return }
        if (action == EditAction.UNDO && (editorUndo.hasPendingUndo || editorUndo.hasPendingInsertion || largeEdit) && !restoring) {
            queuedUndos = minOf(queuedUndos + 1, MAX_QUEUED_UNDOS)
            return
        }
        if (chunkedRead?.pending == true || clearSweep != null || webClear != null || restoring || editorUndo.hasPendingUndo || editorUndo.hasPendingInsertion) return
        val keyAction = action.keyAction
        if (keyAction == null) {
            controller.expireCandidateChoiceUndo()
        }
        when (action) {
            EditAction.UNDO -> undoEditing()
            EditAction.UP -> nav(KeyEvent.KEYCODE_DPAD_UP, SelectionMath.Move.UP)
            EditAction.DOWN -> nav(KeyEvent.KEYCODE_DPAD_DOWN, SelectionMath.Move.DOWN)
            EditAction.LEFT -> nav(KeyEvent.KEYCODE_DPAD_LEFT, SelectionMath.Move.LEFT)
            EditAction.RIGHT -> nav(KeyEvent.KEYCODE_DPAD_RIGHT, SelectionMath.Move.RIGHT)
            EditAction.HOME -> navDocument(toStart = true)
            EditAction.END -> navDocument(toStart = false)
            EditAction.START_SELECT -> toggleSelecting()
            EditAction.DELETE -> keyAction?.let { key ->
                controller.onKey(Key(action = key))
                resetSelectionAnchor()
            }
            EditAction.TAB -> {
                if (takesRawKeys(currentInputEditorInfo)) sendKey(KeyEvent.KEYCODE_TAB, false)
                else commitExternalText("\t")
                resetSelectionAnchor()
            }
            EditAction.FORWARD_DELETE -> {
                deleteNextEditorCluster()
                controller.onEditorContextChanged()
                resetSelectionAnchor()
            }
            EditAction.COPY -> if (editorReportsNoSelection()) {
                toast(uiString(R.string.edit_no_selection))
            } else {
                takeSelection(cut = false)
            }
            EditAction.CUT -> if (editorReportsNoSelection()) {
                toast(uiString(R.string.edit_no_selection))
            } else {
                takeSelection(cut = true)
            }
            EditAction.SELECT_ALL -> {
                if (!isWebEditor()) currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                if (!takesRawKeys(currentInputEditorInfo)) {
                    sendKeyWithMeta(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
                }
                resetSelectionAnchor()
                toast(uiString(R.string.edit_select_all_done))
            }
            EditAction.PASTE -> {
                pasteClipboard()
                resetSelectionAnchor()
            }
            EditAction.BACK -> { stopSelecting(); inputView?.showPanel(null) }
        }
    }

    private fun canBackspaceSwipe(up: Boolean): Boolean {
        if (up && controller.hasComposingToClear()) return true
        if (panelInput.active) return up && panelInput.text().isNotEmpty()
        if (currentInputConnection == null || restoring || clearSweep != null || webClear != null || editorUndo.hasPendingClear || editorUndo.hasPendingUndo || editorUndo.hasPendingInsertion) return false
        return up || editorUndo.hasClearedContent || editorUndo.hasDeletionToRestore || clearedText.hasContent()
    }

    private fun backspaceSwipe(up: Boolean) {
        if (!controller.onBackspaceSwipe(up)) {
            if (panelInput.active) { if (up) { panelInput.selectAll(); panelInput.deleteSelection() } }
            else handleBackspaceSwipe(up)
        }
    }

    private fun handleEditInPanel(action: EditAction) {
        when (action) {
            EditAction.UNDO -> undoEditing()
            EditAction.UP -> panelInput.move(SelectionMath.Move.UP, selecting)
            EditAction.DOWN -> panelInput.move(SelectionMath.Move.DOWN, selecting)
            EditAction.LEFT -> panelInput.move(SelectionMath.Move.LEFT, selecting)
            EditAction.RIGHT -> panelInput.move(SelectionMath.Move.RIGHT, selecting)
            EditAction.HOME -> panelInput.move(SelectionMath.Move.HOME, selecting)
            EditAction.END -> panelInput.move(SelectionMath.Move.END, selecting)
            EditAction.START_SELECT -> {
                selecting = !selecting
                editPanelView?.setSelecting(selecting)
            }
            EditAction.DELETE -> panelInput.backspace()
            EditAction.TAB -> panelInput.commit("\t")
            EditAction.FORWARD_DELETE -> panelInput.deleteForward()
            EditAction.SELECT_ALL -> {
                if (panelInput.text().isEmpty()) {
                    toast(uiString(R.string.edit_no_selection))
                } else {
                    panelInput.selectAll()
                    toast(uiString(R.string.edit_select_all_done))
                }
            }
            EditAction.COPY -> {
                val selected = panelInput.selectedText()
                if (selected == null) toast(uiString(R.string.edit_no_selection))
                else copyFromPanel(selected, R.string.edit_copy_done)
            }
            EditAction.CUT -> {
                val selected = panelInput.selectedText()
                if (selected == null) toast(uiString(R.string.edit_no_selection))
                else if (copyFromPanel(selected, R.string.edit_cut_done)) panelInput.deleteSelection()
            }
            EditAction.PASTE -> {
                pasteClipboard()
            }
            EditAction.BACK -> Unit
        }
        refreshUndoAvailability()
    }

    private fun copyFromPanel(text: String, notice: Int): Boolean {
        val keep = keepsCopies()
        val previousOrder = if (keep) clipboardStore.latestEntry()?.captureOrder else null
        val publication = syncSystemClipboard(text)
        if (keep) {
            clipboardStore.record(text)
            rememberUnpublishedClipboard(publication, previousOrder)
            refreshOpenClipboardPanel()
        }
        if (!keep && !publication.published) return false
        toast(uiString(notice))
        return true
    }

    private data class SystemClipboardIdentity(val timestamp: Long, val fingerprint: String)
    private data class ClipboardPublication(val published: Boolean, val unchangedSystem: SystemClipboardIdentity?)
    private data class UnpublishedClipboard(val system: SystemClipboardIdentity, val entryOrder: Long)
    private var unpublishedClipboard: UnpublishedClipboard? = null

    private fun systemClipboardIdentity(clip: ClipData?): SystemClipboardIdentity {
        if (clip == null) return SystemClipboardIdentity(-1L, "")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun include(value: String?) {
            val bytes = value?.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if (bytes != null) digest.update(bytes)
        }
        include(clip.description.label?.toString())
        include(clip.description.mimeTypeCount.toString())
        for (index in 0 until clip.description.mimeTypeCount) include(clip.description.getMimeType(index))
        include(clip.itemCount.toString())
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            include(item.text?.toString())
            include(item.htmlText)
            include(item.uri?.toString())
            include(item.intent?.toUri(0))
        }
        return SystemClipboardIdentity(clip.description.timestamp, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun readSystemClipboardIdentity(): SystemClipboardIdentity? =
        runCatching { systemClipboardIdentity(clipboardManager.primaryClip) }.getOrNull()

    private fun syncSystemClipboard(text: String): ClipboardPublication {
        unpublishedClipboard = null
        val before = readSystemClipboardIdentity()
        val published = runCatching { clipboardManager.setPrimaryClip(ClipData.newPlainText("Aegis", text)) }.isSuccess
        val unchanged = if (!published && before != null && before == readSystemClipboardIdentity()) before else null
        return ClipboardPublication(published, unchanged)
    }

    private fun rememberUnpublishedClipboard(publication: ClipboardPublication, previousOrder: Long?) {
        val system = publication.unchangedSystem ?: return
        val entry = clipboardStore.latestEntry() ?: return
        if (!entry.isImage && entry.captureOrder != previousOrder) {
            unpublishedClipboard = UnpublishedClipboard(system, entry.captureOrder)
        }
    }

    private fun hasUnpublishedClipboard(clip: ClipData?, entry: ClipEntry?): Boolean {
        val pending = unpublishedClipboard ?: return false
        val matches = entry != null && !entry.isImage && entry.captureOrder == pending.entryOrder &&
            runCatching { systemClipboardIdentity(clip) }.getOrNull() == pending.system
        if (!matches) unpublishedClipboard = null
        return matches
    }

    internal fun takesRawKeys(info: EditorInfo?): Boolean =
        info != null && info.inputType == InputType.TYPE_NULL

    private fun isWebEditor(): Boolean = currentInputEditorInfo?.inputType?.let {
        it and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            it and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
    } == true

    private fun keepsCopies(): Boolean =
        !LiveUserData.restoreInProgress && com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())

    private fun trackedSelectionSpan(): Int {
        val from = minOf(selStart, selEnd)
        val to = maxOf(selStart, selEnd)
        return if (from >= 0 && to > from) to - from else -1
    }

    private fun takeSelection(cut: Boolean) {
        val ic = currentInputConnection ?: run {
            toast(uiString(if (cut) R.string.edit_cut_failed else R.string.edit_copy_failed))
            return
        }
        val styledSelection = if (trackedSelectionSpan() <= ChunkedRead.DIRECT_MAX)
            runCatching { ic.getSelectedText(InputConnection.GET_TEXT_WITH_STYLES) }.getOrNull() else null
        val richSelection = styledSelection?.contains('\uFFFC') == true ||
            (styledSelection is Spanned && styledSelection.getSpans(0, styledSelection.length, ReplacementSpan::class.java).isNotEmpty())
        if (trackedSelectionSpan() < ChunkedRead.CHUNK / 2 || isWebEditor()) {
            if (takeNativeSelection(ic, cut)) return
        }
        if (richSelection || editorUndo.selectionContainsRichContent(ic)) return
        val from = minOf(selStart, selEnd)
        val to = maxOf(selStart, selEnd)
        if (trackedSelectionSpan() <= ChunkedRead.DIRECT_MAX) {
            val direct = ic.getSelectedText(0)
            if (!direct.isNullOrEmpty()) {
                settleSelection(ic, cut, null, direct, whole = true)
                return
            }
        }
        if (from < 0 || to <= from) {
            toast(uiString(if (cut) R.string.edit_cut_failed else R.string.edit_copy_failed))
            return
        }
        readSelection(
            ic,
            from,
            to,
            dropped = { toast(uiString(if (cut) R.string.edit_cut_failed else R.string.edit_copy_failed)) },
        ) { taken, whole -> settleSelection(ic, cut, from, taken, whole) }
    }

    private fun readSelection(
        ic: InputConnection,
        from: Int,
        to: Int,
        dropped: () -> Unit,
        then: (CharSequence, Boolean) -> Unit,
    ) {
        dropChunkedRead()
        val read = ChunkedRead(
            from,
            to,
            select = { at -> ic.setSelection(at, at) },
            before = { length -> ic.getTextBeforeCursor(length, 0) },
            done = { taken, whole ->
                chunkedRead = null
                readDropped = null
                mainHandler.removeCallbacks(readTimeout)
                ic.setSelection(from, to)
                then(taken, whole)
            },
        )
        chunkedRead = read
        readDropped = dropped
        mainHandler.postDelayed(readTimeout, READ_TIMEOUT_MS)
        read.begin()
    }

    private fun replaceLargeSelection(inserted: CharSequence): Boolean {
        if (panelInput.active || isWebEditor() || takesRawKeys(currentInputEditorInfo)) return false
        val span = trackedSelectionSpan()
        if (span <= 0 || span < ChunkedRead.CHUNK / 2 && inserted.length <= WindowedEditorUndoHistory.MAX_INLINE_INSERTION) return false
        val ic = currentInputConnection ?: return false
        if (chunkedRead?.pending == true || editorUndo.hasPendingInsertion) return true
        largeEdit = true
        refreshUndoAvailability()
        val targetEditor = currentEditorTarget
        val originalStart = selStart
        val originalEnd = selEnd
        val from = minOf(originalStart, originalEnd)
        val to = maxOf(originalStart, originalEnd)
        fun failed() {
            largeEdit = false
            queuedUndos = 0
            if (pasteCompletionNotice) {
                pasteCompletionNotice = false
                toast(uiString(R.string.edit_paste_failed))
            }
            refreshUndoAvailability()
        }
        fun replace(removed: CharSequence, whole: Boolean) {
            if (ic !== currentInputConnection || targetEditor != currentEditorTarget) {
                failed()
                return
            }
            if (!whole || removed.length != to - from) {
                ic.setSelection(originalStart, originalEnd)
                failed()
                return
            }
            val accepted = editorUndo.replaceCapturedSelection(ic, from, removed, inserted, originalStart, originalEnd)
            if (!accepted) failed()
            else if (!editorUndo.hasPendingInsertion) {
                largeEdit = false
                controller.onEditorContextChanged()
                refreshUndoAvailability()
                if (queuedUndos > 0) mainHandler.post(::runQueuedUndo)
                if (pasteCompletionNotice) {
                    pasteCompletionNotice = false
                    toast(uiString(R.string.edit_paste_done))
                }
            }
        }
        val direct = if (span <= ChunkedRead.CHUNK) runCatching { ic.getSelectedText(InputConnection.GET_TEXT_WITH_STYLES) }.getOrNull() else null
        if (direct != null && direct.length == span) replace(direct, true)
        else readSelection(ic, from, to, dropped = ::failed, then = ::replace)
        return true
    }

    private fun dropChunkedRead() {
        mainHandler.removeCallbacks(readTimeout)
        val dropped = if (chunkedRead != null) readDropped else null
        chunkedRead = null
        readDropped = null
        dropped?.invoke()
    }

    private fun dropRestoreStream() {
        mainHandler.removeCallbacks(clearPump)
        clearSweep?.let { operation ->
            val captured = operation.capture.text()
            if (operation.kind == EditorUndoHistory.ClearCapture.PLAIN && captured.isNotEmpty()) clearedText.keep(captured)
        }
        clearSweep = null
        mainHandler.removeCallbacks(webClearPump)
        if (webClear != null) editorUndo.cancelClear()
        webClear = null
        mainHandler.removeCallbacks(streamPump)
        streaming = null
        afterStreaming = null
        restoring = false
        largeRestore = false
    }

    private fun settleSelection(ic: InputConnection, cut: Boolean, from: Int?, taken: CharSequence, whole: Boolean) {
        if (taken.isEmpty()) {
            toast(uiString(if (cut) R.string.edit_cut_failed else R.string.edit_copy_failed))
            return
        }
        val body = taken.toString()
        val keep = keepsCopies()
        val previousOrder = if (keep) clipboardStore.latestEntry()?.captureOrder else null
        val publication = syncSystemClipboard(body)
        if (!keep && !publication.published) return
        if (keep) {
            if (body.length <= com.aegis.ime.user.ClipboardStore.BIG_THRESHOLD) {
                recordTextClip(body)
            } else {
                clipboardStore.record(body)
                refreshOpenClipboardPanel()
            }
            rememberUnpublishedClipboard(publication, previousOrder)
        }
        val notice = if (whole) uiString(if (cut) R.string.edit_cut_done else R.string.edit_copy_done)
            else imeUiContext().resources.getQuantityString(
                if (cut) R.plurals.edit_cut_partial else R.plurals.edit_copy_partial,
                body.length,
                body.length,
            )
        if (cut) {
            if (from != null) ic.setSelection(from, from + body.length)
            val accepted = if (from != null && body.length >= ChunkedRead.CHUNK / 2) {
                editorUndo.replaceCapturedSelection(ic, from, body, "", from, from + body.length)
            } else editorUndo.deleteCapturedSelection(ic, from ?: minOf(selStart, selEnd), body)
            if (!accepted) {
                toast(uiString(R.string.edit_cut_failed))
                return
            }
            if (editorUndo.hasPendingInsertion) {
                cutCompletionNotice = notice
                return
            }
            resetSelectionAnchor()
        }
        toast(notice)
    }

    private fun editorReportsNoSelection(): Boolean {
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) return false
        val around = currentInputConnection?.getSurroundingText(0, 0, 0) ?: return false
        return around.selectionStart >= 0 && around.selectionStart == around.selectionEnd
    }

    private fun toggleSelecting() {
        selecting = !selecting
        if (selecting) {
            if (trackedSelectionSpan() > ChunkedRead.DIRECT_MAX) {
                selAnchor = selStart
                selMoving = selEnd
            } else {
                val window = currentInputConnection?.let(::caretWindow)
                selAnchor = window?.let { it.base + it.start } ?: -1
                selMoving = window?.let { it.base + it.end } ?: -1
            }
        } else stopSelecting()
        editPanelView?.setSelecting(selecting)
    }

    private fun stopSelecting() {
        selecting = false
        selAnchor = -1
        selMoving = -1
        editPanelView?.setSelecting(false)
    }

    private fun resetSelectionAnchor() {
        if (!selecting) return
        selAnchor = -1
        selMoving = -1
    }

    private class CaretWindow(val text: CharSequence, val base: Int, val start: Int, val end: Int)

    private fun caretWindow(ic: InputConnection): CaretWindow? {
        val around = ic.getSurroundingText(NAV_WINDOW, NAV_WINDOW, 0)
        val aroundText = around?.text
        if (around != null && aroundText != null && around.offset >= 0) {
            return CaretWindow(aroundText, around.offset, around.selectionStart.coerceAtLeast(0), around.selectionEnd.coerceAtLeast(0))
        }
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val text = extracted.text ?: return null
        if (extracted.startOffset != 0 || extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
        return CaretWindow(text, 0, extracted.selectionStart.coerceAtMost(text.length), extracted.selectionEnd.coerceAtMost(text.length))
    }

    private fun navDocument(toStart: Boolean) {
        val ic = currentInputConnection ?: return
        val extracted = if (takesRawKeys(currentInputEditorInfo) || isWebEditor()) null
            else ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = extracted?.text
        if (extracted == null || text == null || extracted.startOffset != 0 ||
            extracted.partialStartOffset >= 0 || extracted.selectionStart < 0 || extracted.selectionEnd < 0
        ) {
            val shift = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            sendKeyWithMeta(
                if (toStart) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or shift,
            )
            return
        }
        val next = if (toStart) 0 else text.length
        if (selecting) {
            if (selAnchor < 0) selAnchor = extracted.selectionStart.coerceIn(0, text.length)
            selMoving = next
            ic.setSelection(minOf(selAnchor, next), maxOf(selAnchor, next))
        } else ic.setSelection(next, next)
    }

    private fun nav(keyCode: Int, move: SelectionMath.Move) {
        if (isWebEditor()) { sendKey(keyCode, selecting); return }
        val ic = currentInputConnection ?: return
        val window = ic.takeIf { trackedSelectionSpan() <= ChunkedRead.DIRECT_MAX }?.let(::caretWindow)
        if (window == null) { sendNativeNavigationKey(ic, keyCode, selecting); return }
        val base = window.base
        val text = window.text
        if (!selecting) {
            val lo = base + minOf(window.start, window.end)
            val hi = base + maxOf(window.start, window.end)
            val collapsing = lo != hi && (move == SelectionMath.Move.LEFT || move == SelectionMath.Move.RIGHT)
            val from = if (move == SelectionMath.Move.LEFT || move == SelectionMath.Move.UP || move == SelectionMath.Move.HOME) lo else hi
            val next = if (collapsing) from else base + SelectionMath.step(text, from - base, move)
            ic.setSelection(next, next)
            return
        }
        if (selAnchor < 0) selAnchor = base + window.start
        if (selMoving < 0) selMoving = base + window.end
        selMoving = base + SelectionMath.step(text, selMoving - base, move)
        ic.setSelection(minOf(selAnchor, selMoving), maxOf(selAnchor, selMoving))
    }

    private fun sendNativeNavigationKey(ic: InputConnection, code: Int, shift: Boolean) {
        if (panelInput.active) return
        val downTime = SystemClock.uptimeMillis()
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        try {
            if (shift) ic.sendKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta))
            try {
                ic.sendKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 0, meta))
            } finally {
                ic.sendKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta))
            }
        } finally {
            if (shift) ic.sendKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
        }
    }

    private fun sendKey(code: Int, shift: Boolean) =
        sendKeyWithMeta(code, if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

    private fun sendKeyWithMeta(code: Int, meta: Int) {
        if (panelInput.active) return
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private var restoring = false
    private var largeRestore = false
    private class ClearSweep(val target: InputConnection, val kind: EditorUndoHistory.ClearCapture) {
        val capture = EditorSweep.Capture(target)
        var progressedAt = SystemClock.uptimeMillis()
    }
    private var clearSweep: ClearSweep? = null
    private class WebClear(val target: InputConnection, var confirming: Boolean = false)
    private var webClear: WebClear? = null
    private val webClearPump = object : Runnable {
        override fun run() {
            val operation = webClear ?: return
            if (currentInputConnection?.let(editorUndo::untracked) !== operation.target) {
                webClear = null
                editorUndo.cancelClear()
                return
            }
            if (!operation.confirming) {
                operation.confirming = true
                sendKeyWithMeta(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
                mainHandler.postDelayed(this, 32L)
                return
            }
            val emptySelection = operation.target.getSelectedText(0).isNullOrEmpty()
            if (emptySelection) {
                if (editorUndo.finishEditorClear(operation.target)) clearedText.forget()
            } else {
                editorUndo.cancelClear()
                sendKeyWithMeta(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            }
            webClear = null
            resetSelectionAnchor()
            refreshUndoAvailability()
            controller.onEditorContextChanged()
        }
    }
    private val clearPump = object : Runnable {
        override fun run() {
            val operation = clearSweep ?: return
            if (currentInputConnection?.let(editorUndo::untracked) !== operation.target) {
                dropRestoreStream()
                return
            }
            val progress = operation.capture.advance()
            val timedOut = progress == EditorSweep.Progress.WAITING &&
                SystemClock.uptimeMillis() - operation.progressedAt >= READ_TIMEOUT_MS
            if (progress == EditorSweep.Progress.DONE || timedOut) {
                if (timedOut) operation.capture.cancel()
                clearSweep = null
                editorUndo.finishClearRestore(operation.target, operation.capture.text())
                resetSelectionAnchor()
                controller.onEditorContextChanged()
                return
            }
            if (progress == EditorSweep.Progress.MORE) operation.progressedAt = SystemClock.uptimeMillis()
            mainHandler.postDelayed(this, if (progress == EditorSweep.Progress.WAITING) STREAM_GAP_MS else 1L)
        }
    }

    private fun handleBackspaceSwipe(up: Boolean) {
        if (panelInput.active) return
        val ic = currentInputConnection ?: return
        controller.expireCandidateChoiceUndo()
        if (up) {
            if (restoring || clearSweep != null || webClear != null || editorUndo.hasPendingClear || editorUndo.hasPendingUndo || editorUndo.hasPendingInsertion) return
            val kind = editorUndo.beginClearRestore(ic)
            if (isWebEditor()) {
                val target = editorUndo.untracked(ic)
                webClear = WebClear(target)
                target.finishComposingText()
                sendKeyWithMeta(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
                sendKey(KeyEvent.KEYCODE_DEL, false)
                mainHandler.postDelayed(webClearPump, 120L)
                return
            }
            if (!editorUndo.clearCaptured(ic)) {
                clearSweep = ClearSweep(editorUndo.untracked(ic), kind)
                clearPump.run()
            }
            resetSelectionAnchor()
        } else {
            if (restoring || clearSweep != null || webClear != null || editorUndo.hasPendingClear || editorUndo.hasPendingInsertion) return
            if (editorUndo.hasClearedContent) {
                restoring = true
                val restored = editorUndo.restoreClearedContent(ic)
                if (!editorUndo.hasPendingUndo) finishClearedContentRestore(restored)
                return
            }
            if (editorUndo.hasDeletionToRestore) {
                silentUndo = true
                val restored = editorUndo.undo(ic)
                if (restored) clearedText.forget()
                if (!editorUndo.hasPendingUndo) finishEditingUndo(restored)
                return
            }
            clearedText.held()?.let {
                val within =
                    if (it.length > ClearedTextRestore.MAX_CHARS) it.subSequence(0, ClearedTextRestore.MAX_CHARS)
                    else it
                val span = maxOf(CaretRealign.breaksIn(within), TRIM_WINDOW)
                val followed = CaretRealign.following(ic, span)
                restoring = true
                largeRestore = within.length > EditorSweep.CHUNK
                val restoreTarget = if (largeRestore) editorUndo.untracked(ic) else ic
                ClearedTextRestore.restore(
                    within,
                    measure = { EditorSweep.nearbyLength(restoreTarget) },
                    commit = { part, then ->
                        if (part.length > STREAM_CHUNK) {
                            commitStreamed(part, then)
                        } else {
                            commitLargeText(part, realign = false)
                            then()
                        }
                    },
                    done = {
                        restoring = false
                        settleRestore(restoreTarget, within, span, followed)
                        largeRestore = false
                        clearedText.forget()
                    },
                )
                resetSelectionAnchor()
            }
        }
    }

    private fun finishClearedContentRestore(restored: Boolean) {
        restoring = false
        if (restored) {
            resetSelectionAnchor()
            controller.onEditorContextChanged()
        }
        refreshUndoAvailability()
    }

    private fun showEmojiPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(emojiView)) { iv.showPanel(null); return }
        val ev = emojiView ?: EmojiView(imeUiContext()).also {
            it.recentProvider = { emojiUsageStore.recent() }
            it.onEmoji = { e ->
                if (!personalizationBlocked) emojiUsageStore.record(e)
                commitExternalText(e)
            }
            it.onClearRecents = { emojiUsageStore.clear() }
            it.onDeleteRecent = { emoji -> emojiUsageStore.remove(emoji) }
            it.onBackspace = { panelBackspace() }
            it.onBackspaceSwipe = { up -> backspaceSwipe(up) }
            it.backspaceSwipeAvailable = { up -> canBackspaceSwipe(up) }
            it.onBack = { inputView?.showPanel(null) }
            emojiView = it
        }
        ev.resetToDefault()
        ev.applyPalette(imePalette)
        iv.showPanel(ev)
    }

    private fun showClipboardPanel() {
        val iv = inputView ?: return
        iv.showPhraseNotice(null)
        val captureCurrentClip = !restoreClipboardWithoutCapture
        if (!restoreClipboardWithoutCapture) {
        if (iv.isPanelShowing(clipboardView)) { iv.showPanel(null); return }
        }
        if (captureCurrentClip) clipboardRecreationState = null
        val recreationState = clipboardRecreationState
        val cv = clipboardView ?: ClipboardView(imeUiContext()).also {
            it.historyProvider = { clipboardStore.history() }
            it.categoriesProvider = { clipboardStore.categories() }
            it.phrasesInProvider = { cat -> clipboardStore.phrasesIn(cat) }
            it.phraseNoteProvider = { cat, text -> clipboardStore.noteFor(cat, text) }
            it.onPick = { t -> commitLargeText(t); inputView?.showPanel(null) }
            it.onPickImage = { entry -> pasteImage(entry) }
            it.onCopyImage = { entry ->
                if (publishImageClip(entry)) toast(uiString(R.string.edit_copy_done))
            }
            it.onCopyBlocksToAegis = { blocks -> copyBlocksToAegis(blocks) }
            it.onSplitSelectionChanged = { text -> updateSplitSelection(text) }
            it.onSplitSelectionFinished = { finishSplitSelection() }
            it.onBack = { inputView?.showPanel(null) }
            it.onDeleteClips = { list -> clipboardStore.deleteAll(list) }
            it.onDeletePhrasesFrom = { cat, list -> clipboardStore.deletePhrasesFrom(cat, list) }
            it.onSaveAsPhrasesTo = { cat, list -> clipboardStore.addPhrasesTo(cat, list) }
            it.onEditPhrase = { cat, text -> beginInlineEdit(cat, text) }
            it.onEditClip = { key -> beginInlineEditClip(key) }
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
            it.phrasesReadableProvider = { clipboardStore.phrasesReadable }
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

    private fun showCustomSymbolPanel() {
        val iv = inputView ?: return
        val panel = customSymbolView ?: CustomSymbolPanel(imeUiContext()).also {
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
        val panel = customOperatorView ?: CustomSymbolPanel(imeUiContext()).also {
            it.backTitle = uiString(R.string.csp_operators_title)
            it.paletteTitle = uiString(R.string.csp_section_all_operators)
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
        val sv = symbolsView ?: SymbolsView(imeUiContext()).also {
            it.recentProvider = { symbolUsageStore.recent() }
            it.recentOriginOf = { s -> symbolUsageStore.originOf(s) }
            it.onClearRecents = { symbolUsageStore.clear() }
            it.onDeleteRecent = { symbol -> symbolUsageStore.remove(symbol) }
            it.onSymbol = { s, origin ->
                if (!personalizationBlocked) symbolUsageStore.record(s, origin)
                commitExternalSymbol(s)
            }
            it.onBackspace = { panelBackspace() }
            it.onBackspaceSwipe = { up -> backspaceSwipe(up) }
            it.backspaceSwipeAvailable = { up -> canBackspaceSwipe(up) }
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

    private fun openBackup() {
        requestHideSelf(0)
        runCatching {
            startActivity(
                android.content.Intent(this, com.aegis.ime.ui.BackupActivity::class.java)
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
        if (phrase.length > EDITABLE_CLIP_CHARS) {
            toast(uiString(R.string.phrase_edit_too_long))
            return
        }
        inputPurpose = InputPurpose.EDIT_PHRASE; inputCat = category; inputOld = phrase
        startInlineInput(uiString(R.string.svc_edit_phrase), phrase)
    }

    private fun beginInlineEditClip(key: String) {
        if (clipboardStore.clipBodySizeHint(key) > EDITABLE_CLIP_CHARS) {
            toast(uiString(R.string.clip_edit_too_long))
            return
        }
        val body = clipboardStore.clipBody(key)
        if (body == null) {
            toast(uiString(R.string.clip_entry_unreadable_body))
            return
        }
        inputPurpose = InputPurpose.EDIT_CLIP; inputCat = ""; inputOld = key
        startInlineInput(uiString(R.string.svc_edit_clip), body)
    }

    private fun beginInlineAddPhrase(category: String) {
        inputPurpose = InputPurpose.ADD_PHRASE; inputCat = category; inputOld = ""
        startInlineInput(uiString(R.string.svc_add_phrase), "")
    }

    private fun beginInlineEditNote(category: String, text: String) {
        inputPurpose = InputPurpose.EDIT_NOTE; inputCat = category; inputOld = text
        startInlineInput(uiString(R.string.svc_note), clipboardStore.noteFor(category, text))
    }

    private fun beginInlineAddCategory(
        pendingAdds: List<String> = emptyList(),
        pendingMove: Pair<String, List<String>>? = null,
    ) {
        inputPurpose = InputPurpose.ADD_CATEGORY; inputCat = ""; inputOld = ""
        pendingPhraseAdds = pendingAdds
        pendingMoveFrom = pendingMove?.first ?: ""
        pendingMoveTexts = pendingMove?.second ?: emptyList()
        startInlineInput(uiString(R.string.svc_new_category), "")
    }

    private fun beginInlineRenameCategory(old: String) {
        inputPurpose = InputPurpose.RENAME_CATEGORY; inputCat = ""; inputOld = old
        startInlineInput(uiString(R.string.svc_rename_category), old)
    }

    private fun startInlineInput(title: String, initial: String) {
        val iv = inputView ?: return
        inlineOriginPhrasesTab = clipboardView?.recreationState()?.phrasesTab == true
        iv.showPanel(null)
        panelInputTitle = title
        panelTextSnapshot = initial
        iv.setEditTitle(title)
        iv.setEditText(initial)
        iv.showEditBar(true)
        panelInput.begin(iv.editEditable()) { iv.isEditBarShowing() }
    }

    private fun confirmInlineInput() {
        val text = panelInput.text()
        when (inputPurpose) {
            InputPurpose.EDIT_PHRASE -> clipboardStore.editPhrase(inputCat, inputOld, text)
            InputPurpose.EDIT_CLIP -> clipboardStore.editClip(inputOld, text)
            InputPurpose.ADD_PHRASE -> if (text.isNotBlank()) clipboardStore.addPhrasesTo(inputCat, listOf(text))
            InputPurpose.EDIT_NOTE -> clipboardStore.setPhraseNote(inputCat, inputOld, text)
            InputPurpose.ADD_CATEGORY -> {
                val name = com.aegis.ime.user.ClipboardStore.sanitizePhraseText(text)
                if (name.isNotBlank()) {
                    clipboardStore.addCategory(name)
                    if (pendingPhraseAdds.isNotEmpty()) clipboardStore.addPhrasesTo(name, pendingPhraseAdds)
                    if (pendingMoveTexts.isNotEmpty()) clipboardStore.movePhrasesTo(pendingMoveFrom, pendingMoveTexts, name)
                    inputCat = name
                }
            }
            InputPurpose.RENAME_CATEGORY -> if (text != inputOld) {
                val n = com.aegis.ime.user.ClipboardStore.sanitizePhraseText(text)
                if (clipboardStore.renameCategory(inputOld, n)) inputCat = n
            }
            null -> {}
        }
        endInlineInput()
    }

    private fun reportClipWrite(landed: Boolean) {
        if (landed) {
            inputView?.showPhraseNotice(null)
            return
        }
        val panel = clipboardView
        if (panel != null && inputView?.isPanelShowing(panel) == true) panel.reportClipWrite()
        else inputView?.showPhraseNotice(uiString(R.string.clip_change_not_saved))
    }

    private fun reportPhraseWrite(change: PhraseChange) {
        val panel = clipboardView
        val leftOut = if (change.edit == PhraseEdit.ADD) panel?.takeClipsLeftOut() ?: 0 else 0
        val message = phraseWriteNotice(this, change, leftOut)
        if (change.saved && leftOut <= 0) {
            inputView?.showPhraseNotice(null)
            if (message.isNotEmpty()) toast(message)
            return
        }
        if (panel != null && inputView?.isPanelShowing(panel) == true) panel.reportPhraseWrite(change, leftOut)
        else inputView?.showPhraseNotice(message)
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
        bindTranslateInput(returningView)
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
        bindTranslateInput()
    }

    private fun toggleTranslateBar() {
        if (translateOpen) closeTranslateBar() else openTranslateBar()
    }

    private fun openTranslateBar() {
        if (inputPurpose != null) return
        val iv = inputView ?: return
        translateOpen = true
        translateEngaged = true
        iv.showPanel(null)
        bindTranslateInput(iv)
    }

    private fun closeTranslateBar() {
        translateOpen = false
        finishTranslation()
        if (inputPurpose == null && panelInput.active) {
            if (::controller.isInitialized) controller.onPanelClear()
            panelInput.end()
        }
        inputView?.let { iv ->
            iv.setTranslateText("")
            iv.showTranslateBar(false)
        }
    }

    private fun bindTranslateInput(view: InputView? = inputView) {
        val iv = view ?: return
        if (!translateOpen || inputPurpose != null) return
        iv.setTranslateMode(translateMode())
        iv.setTranslateFieldEngaged(translateEngaged)
        iv.showTranslateBar(true)
        if (translateEngaged) panelInput.begin(iv.translateEditable()) { iv.isTranslateBarShowing() }
    }

    private fun pauseTranslateRouting() {
        if (!translateOpen || !translateEngaged || inputPurpose != null) return
        translateEngaged = false
        if (panelInput.active) {
            if (::controller.isInitialized) controller.onPanelClear()
            panelInput.end()
        }
        finishTranslation()
        inputView?.let { iv ->
            iv.setTranslateText("")
            iv.setTranslateFieldEngaged(false)
        }
    }

    private fun resumeTranslateRouting() {
        if (!translateOpen || translateEngaged || inputPurpose != null) return
        translateEngaged = true
        bindTranslateInput()
    }

    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        pauseTranslateRouting()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        pauseTranslateRouting()
    }

    private fun translateMode(): TranslateMode = runCatching {
        TranslateMode.valueOf(getSharedPreferences("aegis", MODE_PRIVATE).getString(PREF_TRANSLATE_MODE, null) ?: TranslateMode.AUTO.name)
    }.getOrDefault(TranslateMode.AUTO)

    private fun setTranslateMode(mode: TranslateMode) {
        getSharedPreferences("aegis", MODE_PRIVATE).edit().putString(PREF_TRANSLATE_MODE, mode.name).apply()
    }

    private fun scheduleTranslation(text: String) {
        translatePending?.let(mainHandler::removeCallbacks)
        translatePending = null
        translateLane.markSatisfiedSynchronously()
        translateClient.abort()
        if (text.isBlank()) {
            translateInputConnection?.setComposingText("", 1)
            return
        }
        val request = Runnable {
            translatePending = null
            val mode = translateMode()
            translateLane.submit(
                compute = { runCatching { translateClient.translate(text, mode) } },
                apply = { outcome ->
                    outcome.fold(
                        onSuccess = { applyTranslation(it) },
                        onFailure = { toast(uiString(R.string.translate_failed_cause, translateFailureCause(it))) },
                    )
                },
            )
        }
        translatePending = request
        mainHandler.postDelayed(request, TRANSLATE_DEBOUNCE_MS)
    }

    private fun translateFailureCause(failure: Throwable): String = uiString(
        when (ModelDownload.classifyRequestFailure(failure)) {
            ModelDownload.CheckFailure.OFFLINE -> R.string.download_cause_offline
            ModelDownload.CheckFailure.TIMEOUT -> R.string.download_cause_timeout
            ModelDownload.CheckFailure.SERVER, ModelDownload.CheckFailure.PARSE -> R.string.download_cause_server
        },
    )

    private fun applyTranslation(text: String) {
        if (!translateOpen) return
        val connection = translateInputConnection ?: currentInputConnection?.also { translateInputConnection = it }
        connection?.setComposingText(text, 1)
    }

    private fun finishTranslation() {
        translatePending?.let(mainHandler::removeCallbacks)
        translatePending = null
        translateClient.abort()
        translateLane.markSatisfiedSynchronously()
        val connection = translateInputConnection ?: return
        translateInputConnection = null
        if (!frameworkWillFinishInput) connection.finishComposingText()
    }

    internal fun translateBarOpenForTest(): Boolean = translateOpen

    internal fun setTranslateClientForTest(client: TranslateClient) { translateClient = client }

    private fun captureClip() {
        if (LiveUserData.restoreInProgress) return
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        captureSystemClip(clip, showText = false)
    }

    private fun onSystemClipChanged() {
        if (LiveUserData.restoreInProgress) return
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        captureSystemClip(clip, showText = true)
    }

    private fun captureSystemClip(clip: ClipData, showText: Boolean) {
        if (hasUnpublishedClipboard(clip, clipboardStore.latestEntry())) return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val mime = ClipboardImages.imageMimeType(contentResolver, clip, item)
        if (mime != null && item.uri != null) {
            clipboardStore.recordImage(contentResolver, item.uri, mime) { result ->
                mainHandler.post {
                    if (result.isSuccess) {
                        val current = runCatching { clipboardManager.primaryClip }.getOrNull()
                        if (current != null && current.itemCount > 0 && current.getItemAt(0).uri == item.uri) {
                            lastCopy = null
                            inputView?.hideCopyBar()
                        }
                        refreshOpenClipboardPanel()
                    } else result.exceptionOrNull()?.let { reportImageFailure(it) }
                }
            }
            return
        }
        if (item.uri != null && item.text == null) return
        val text = runCatching { item.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        if (text.isBlank()) return
        if (showText) recordTextClip(text) else clipboardStore.record(text)
    }

    private fun pasteClipboard() {
        val system = runCatching { clipboardManager.primaryClip }.getOrNull()
        val item = system?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val mime = if (item != null) ClipboardImages.imageMimeType(contentResolver, system, item) else null
        if (mime != null && item?.uri != null) {
            if (panelInput.active) { toast(uiString(R.string.clip_image_paste_unsupported)); return }
            val target = currentInputConnection
            val targetEditor = currentEditorTarget
            val start = selStart
            val end = selEnd
            if (!keepsCopies()) {
                clipboardStore.loadImageForInput(contentResolver, item.uri, mime) { result ->
                    mainHandler.post {
                        result.fold(onSuccess = { lease ->
                            val currentClip = runCatching { clipboardManager.primaryClip }.getOrNull()
                            if (target !== currentInputConnection || targetEditor != currentEditorTarget || panelInput.active ||
                                start != selStart || end != selEnd || currentClip == null || currentClip.itemCount == 0 ||
                                currentClip.getItemAt(0).uri != item.uri) { lease.close(); return@fold }
                            undoImageLeases.put(lease.entry.key, lease)?.close()
                            requestImageClipboardPaste(lease.entry, publish = false)
                        }, onFailure = { reportImageFailure(it) })
                    }
                }
                return
            }
            clipboardStore.recordImage(contentResolver, item.uri, mime) { result ->
                mainHandler.post {
                    if (target !== currentInputConnection || targetEditor != currentEditorTarget || panelInput.active ||
                        start != selStart || end != selEnd) return@post
                    result.fold(
                        onSuccess = { pasteImage(it) },
                        onFailure = { reportImageFailure(it) },
                    )
                }
            }
            return
        }
        val systemText = item?.text?.toString()
        val entry = clipboardStore.latestEntry()
        val localPublication = keepsCopies() && hasUnpublishedClipboard(system, entry)
        if (!systemText.isNullOrEmpty() && (systemText.isBlank() && !localPublication || !keepsCopies() || entry == null)) {
            pastePlainText(systemText)
            return
        }
        when {
            entry == null -> toast(uiString(R.string.edit_paste_empty))
            entry.isImage -> pasteImage(entry)
            else -> pastePlainText(entry.body())
        }
    }

    private fun pastePlainText(text: CharSequence?) {
        pasteCompletionNotice = true
        val nativeClip = if (!panelInput.active && isWebEditor() && text != null) {
            runCatching { clipboardManager.primaryClip }.getOrNull()?.takeIf { it.itemCount == 1 }
                ?.getItemAt(0)?.takeIf { it.htmlText == null && it.uri == null && it.intent == null &&
                    it.text?.toString() == text.toString() }
        } else null
        val inserted = if (nativeClip != null && text != null) {
            currentInputConnection?.let { editorUndo.pasteCopiedText(it, text) } == true
        } else text?.let { commitLargeText(it) } == true
        if (pasteCompletionNotice && !editorUndo.hasPendingInsertion && chunkedRead?.pending != true) {
            pasteCompletionNotice = false
            toast(uiString(if (inserted) R.string.edit_paste_done else R.string.edit_paste_failed))
        }
    }

    private fun reportImageFailure(failure: Throwable) {
        toast(uiString(if (failure is ClipboardImageTooLargeException) R.string.clip_image_too_large else R.string.clip_image_save_failed))
    }

    private fun publishImageClip(entry: ClipEntry): Boolean {
        if (LiveUserData.restoreInProgress) return false
        val clip = ClipboardImages.clipData(this, entry) ?: return false
        return clipboardStore.retainPublishedImage(entry) {
            runCatching { clipboardManager.setPrimaryClip(clip) }.isSuccess
        }
    }

    private fun imageUndoContent(entry: ClipEntry): EditorUndoHistory.ImageContent? {
        if (entry.key !in undoImageLeases) {
            val lease = clipboardStore.retainImageForInput(entry) ?: return null
            undoImageLeases[entry.key] = lease
        }
        return EditorUndoHistory.ImageContent(entry.key) { target ->
            when (commitImageContent(target, entry, publish = false)) {
                true -> true
                false -> when (dispatchImageClipboardPaste(target, entry)) {
                    ImageClipboardResult.REQUESTED -> true
                    ImageClipboardResult.UNCERTAIN -> throw IllegalStateException("image paste result is unavailable")
                    else -> false
                }
                null -> throw IllegalStateException("image delivery result is unavailable")
            }
        }
    }

    private fun commitImageContent(ic: InputConnection, entry: ClipEntry, publish: Boolean = true): Boolean? {
        if (LiveUserData.restoreInProgress) return false
        val uri = ClipboardImages.uri(this, entry) ?: return false
        val mime = entry.mimeType ?: return false
        val pasted = runCatching {
            committingContent = true
            try {
                ic.commitContent(InputContentInfo(uri, ClipDescription(uiString(R.string.clip_image_label), arrayOf(mime)), null),
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
            } finally {
                committingContent = false
            }
        }.getOrNull()
        if (publish && pasted != false) publishImageClip(entry)
        return pasted
    }

    private fun pasteImage(entry: ClipEntry) {
        if (editorUndo.hasPendingUndo || editorUndo.hasPendingInsertion) return
        if (panelInput.active) { toast(uiString(R.string.clip_image_paste_unsupported)); return }
        if (LiveUserData.restoreInProgress) return
        val ic = currentInputConnection ?: return
        if (ClipboardImages.uri(this, entry) == null || entry.mimeType == null) {
            toast(uiString(R.string.clip_image_unavailable)); return
        }
        editorUndo.beginRichContent(ic, imageUndoContent(entry))
        var pasted: Boolean? = null
        try {
            pasted = commitImageContent(ic, entry)
        } finally {
            editorUndo.finishRichContent(ic, pasted != false)
        }
        if (pasted == true) {
            toast(uiString(R.string.edit_paste_done))
            inputView?.showPanel(null)
        } else if (pasted == false) {
            requestImageClipboardPaste(entry)
        } else {
            toast(uiString(R.string.clip_image_paste_unsupported))
        }
    }

    private enum class ImageClipboardResult { REQUESTED, SELECTION, UNAVAILABLE, UNCERTAIN }

    private fun dispatchImageClipboardPaste(ic: InputConnection, entry: ClipEntry?): ImageClipboardResult {
        if (LiveUserData.restoreInProgress) return ImageClipboardResult.UNAVAILABLE
        val selection = runCatching { ic.getSurroundingText(0, 0, 0) }.getOrNull()
            ?.let { it.selectionStart to it.selectionEnd }
            ?: runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
                ?.let { it.selectionStart to it.selectionEnd }
        if (selection != null && selection.first >= 0 && selection.second >= 0 && selection.first != selection.second)
            return ImageClipboardResult.SELECTION
        if (selection == null || selection.first < 0 || selection.first != selection.second)
            return ImageClipboardResult.UNAVAILABLE
        if (entry != null && !publishImageClip(entry)) return ImageClipboardResult.UNAVAILABLE
        val requested = runCatching {
            val now = SystemClock.uptimeMillis()
            val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            val down = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE, 0,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
            val up = ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE, 0,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
            down && up
        }.getOrDefault(false)
        return if (requested) ImageClipboardResult.REQUESTED else ImageClipboardResult.UNCERTAIN
    }

    private fun requestImageClipboardPaste(entry: ClipEntry, publish: Boolean = true) {
        if (editorUndo.hasPendingUndo || editorUndo.hasPendingInsertion) return
        if (LiveUserData.restoreInProgress) return
        val ic = currentInputConnection ?: return
        editorUndo.beginRichContent(ic, imageUndoContent(entry))
        var result = ImageClipboardResult.UNAVAILABLE
        try {
            result = dispatchImageClipboardPaste(ic, entry.takeIf { publish })
        } finally {
            editorUndo.finishRichContent(ic, result == ImageClipboardResult.REQUESTED || result == ImageClipboardResult.UNCERTAIN)
        }
        when (result) {
            ImageClipboardResult.REQUESTED, ImageClipboardResult.UNCERTAIN -> {
                toast(uiString(R.string.clip_image_paste_requested))
                inputView?.showPanel(null)
            }
            ImageClipboardResult.SELECTION -> toast(uiString(R.string.clip_image_paste_cursor))
            ImageClipboardResult.UNAVAILABLE -> toast(uiString(R.string.clip_image_paste_unsupported))
        }
    }

    private fun takeNativeSelection(ic: InputConnection, cut: Boolean, attempt: Int = 0, originalClip: ClipData? = null): Boolean {
        val oldClip = if (attempt == 0) runCatching { clipboardManager.primaryClip }.getOrNull() else originalClip
        if (attempt == 0) {
            runCatching { ic.performContextMenuAction(android.R.id.copy) }.getOrElse { return false }
        }
        val selectedText = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull()
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
        if (clip == null || clip.itemCount == 0 || oldClip != null && oldClip.description.timestamp == clip.description.timestamp &&
            oldClip.itemCount == clip.itemCount && (0 until clip.itemCount).all { index ->
                val old = oldClip.getItemAt(index)
                val new = clip.getItemAt(index)
                old.uri == new.uri && old.text?.toString() == new.text?.toString() && old.htmlText == new.htmlText
            }
        ) {
            if (!cut || !isWebEditor()) return false
            if (attempt >= 10) {
                toast(uiString(R.string.edit_cut_failed))
                return true
            }
            val start = selStart
            val end = selEnd
            val editor = currentEditorTarget
            mainHandler.postDelayed({
                if (currentInputConnection === ic && currentEditorTarget == editor && !panelInput.active &&
                    start == selStart && end == selEnd && ic.getSelectedText(0)?.toString() == selectedText) {
                    takeNativeSelection(ic, cut, attempt + 1, oldClip)
                } else toast(uiString(R.string.edit_cut_failed))
            }, 50L)
            return true
        }
        val item = clip.getItemAt(0)
        val mime = ClipboardImages.imageMimeType(contentResolver, clip, item)
        val uri = item.uri
        if (mime == null || uri == null || !keepsCopies()) {
            if (keepsCopies()) captureSystemClip(clip, showText = true)
            if (cut) {
                if (!runCatching { editorUndo.cutCopiedSelection(ic, item.text) }.getOrDefault(false)) {
                    toast(uiString(R.string.edit_cut_failed))
                    return true
                }
                resetSelectionAnchor()
            }
            return true
        }
        val start = selStart
        val end = selEnd
        val target = currentEditorTarget
        clipboardStore.recordImage(contentResolver, uri, mime) { result ->
            mainHandler.post {
                val entry = result.getOrNull() ?: return@post
                refreshOpenClipboardPanel()
                val currentClip = runCatching { clipboardManager.primaryClip }.getOrNull()
                val sameClip = currentClip != null && currentClip.itemCount > 0 && currentClip.getItemAt(0).uri == uri
                if (!cut) {
                    if (sameClip) publishImageClip(entry)
                    toast(uiString(R.string.edit_copy_done))
                    return@post
                }
                val unchanged = currentInputConnection === ic && currentEditorTarget == target && sameClip &&
                    selStart == start && selEnd == end && !panelInput.active &&
                    ic.getSelectedText(0)?.toString() == selectedText
                if (!unchanged) return@post
                val removed = runCatching { ic.performContextMenuAction(android.R.id.cut) }.getOrDefault(false)
                if (removed) {
                    resetSelectionAnchor()
                    publishImageClip(entry)
                }
                if (removed) toast(uiString(R.string.edit_cut_done))
            }
        }
        return true
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

    private fun toast(msg: String) { inputView?.showToast(msg) }

    internal fun toastTextForTest(): String? = inputView?.toastTextForTest()

    private fun copyBlocksToAegis(blocks: List<String>) {
        if (LiveUserData.restoreInProgress) return
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return
        if (blocks.isEmpty()) return
        for (block in blocks) clipboardStore.record(block)
        refreshOpenClipboardPanel()
        toast(uiString(R.string.svc_saved_to_clipboard))
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

    private fun historyEnabled() =
        runCatching { getSharedPreferences("aegis", MODE_PRIVATE).getBoolean("clip_history", true) }.getOrDefault(true)
    private fun setHistoryEnabled(on: Boolean) {
        getSharedPreferences("aegis", MODE_PRIVATE).edit().putBoolean("clip_history", on).apply()
        toast(uiString(if (on) R.string.clip_history_resumed else R.string.clip_history_paused))
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        unregisterBackCallback()
        if (finishingInput) {
            clearEditorTransientState(resetController = true, preserveLayout = layoutSessionPackage != null)
            currentEditorTarget = null
            inputSessionActive = false
            resetControllerOnNextInputView = false
            personalizationBlocked = false
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
        if (LiveUserData.restoreInProgress) return
        liveUserDictHost.scheduleSave()
    }

    override fun onUnbindInput() {
        clearEditorTransientState(resetController = true)
        currentEditorTarget = null
        layoutSessionPackage = null
        inputSessionActive = false
        resetControllerOnNextInputView = false
        personalizationBlocked = false
        super.onUnbindInput()
    }

    override fun onDestroy() {
        clearEditorTransientState(resetController = true)
        currentEditorTarget = null
        layoutSessionPackage = null
        inputSessionActive = false
        resetControllerOnNextInputView = false
        personalizationBlocked = false
        unregisterBackCallback()
        runCatching { decodeWorker.shutdownNow() }
        synchronized(decodeHintLock) {
            decodeHintOpened = true
            decodeHint?.let { session -> runCatching { session.close() } }
            decodeHint = null
        }
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) }
        if (UserDictHot.host === liveUserDictHost) UserDictHot.host = null
        runCatching { liveUserDictHost.flush() }
        liveUserDictHost.stopSaving()
        LiveUserData.unregisterClipboardPersistenceHooks(clipboardPendingWriteFlush)
        clipboardStore.stopReportingPhraseWrites()
        clipboardStore.stopReportingClipWrites()
        symbolUsageStore.stopReportingWrites()
        emojiUsageStore.stopReportingWrites()
        clipboardStore.stopSaving()
        if (LiveUserData.clipboardHost === clipboardStore) LiveUserData.clipboardHost = null
        LiveUserData.onRestored = null
        LiveUserData.onLexiconsRestored = null
        runCatching {
            getSharedPreferences("aegis", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(settingsHotApply)
            getSharedPreferences("aegis", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(userLexiconListener)
        }
        super.onDestroy()
    }


    private fun commitExternalText(text: CharSequence) {
        if (panelInput.commit(text)) {
            controller.onEditorContextChanged()
            return
        }
        controller.expireCandidateChoiceUndo()
        if (replaceLargeSelection(text)) return
        if (currentInputConnection?.commitText(text, 1) == true) controller.onEditorContextChanged()
    }

    override fun commitText(text: CharSequence) {
        if (panelInput.commit(text)) return
        currentInputConnection?.commitText(text, 1)
    }

    private fun commitExternalSymbol(symbol: CharSequence) {
        if (panelInput.commitSymbol(symbol)) {
            controller.onEditorContextChanged()
            return
        }
        controller.expireCandidateChoiceUndo()
        if (commitSymbolToEditor(symbol)) controller.onEditorContextChanged()
    }

    override fun commitSymbol(symbol: CharSequence) {
        if (panelInput.commitSymbol(symbol)) return
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

    private var streaming: CharSequence? = null
    private var streamedAt = 0
    private var afterStreaming: (() -> Unit)? = null

    private val streamPump = object : Runnable {
        override fun run() {
            val text = streaming ?: return
            val ic = currentInputConnection
            if (ic == null) { streaming = null; afterStreaming = null; restoring = false; largeRestore = false; return }
            val end = minOf(streamedAt + STREAM_CHUNK, text.length)
            ic.commitText(text.subSequence(streamedAt, end), 1)
            streamedAt = end
            if (end < text.length) {
                mainHandler.postDelayed(this, STREAM_GAP_MS)
                return
            }
            streaming = null
            val then = afterStreaming
            afterStreaming = null
            then?.invoke()
            controller.onEditorContextChanged()
        }
    }

    private fun commitStreamed(text: CharSequence, then: () -> Unit) {
        mainHandler.removeCallbacks(streamPump)
        streaming = text
        streamedAt = 0
        afterStreaming = then
        streamPump.run()
    }

    private fun commitLargeText(text: CharSequence, realign: Boolean = true): Boolean {
        if (panelInput.commit(text)) {
            controller.onEditorContextChanged()
            return true
        }
        controller.expireCandidateChoiceUndo()
        val ic = currentInputConnection ?: return false
        if (replaceLargeSelection(text)) return true
        val breaks = if (realign) CaretRealign.breaksIn(text) else 0
        val following = CaretRealign.following(ic, breaks)
        val committed = editorUndo.commitCapturedText(ic, text) { target ->
            target.beginBatchEdit()
            try {
                var accepted = true
                com.aegis.ime.ime.LargeCommit.commit(text) {
                    accepted = target.commitText(it, 1) && accepted
                }
                accepted
            } finally { target.endBatchEdit() }
        }
        if (editorUndo.hasPendingInsertion) return committed
        catchCaretUp(ic, breaks, following)
        controller.onEditorContextChanged()
        return committed
    }

    private fun settleRestore(ic: InputConnection, written: CharSequence, span: Int, followed: String) {
        catchCaretUp(ic, span, followed)
        if (followed.isNotEmpty() || written.isEmpty() || written.last() == '\n') return
        val before = ic.getTextBeforeCursor(TRIM_WINDOW, 0) ?: return
        val extra = before.length - 1 - before.indexOfLast { it != '\n' }
        if (extra > 0) ic.deleteSurroundingText(extra, 0)
    }

    private fun catchCaretUp(ic: InputConnection, span: Int, following: String) {
        if (span <= 0) return
        val window = span * 2
        val lag = { CaretRealign.lagBetween(following, CaretRealign.following(ic, window)) }
        var behind = lag()
        if (behind <= 0) return
        val reshow = isInputViewShown
        while (behind > 0) {
            ic.commitText("", 1 + behind)
            val now = lag()
            if (now >= behind) break
            behind = now
        }
        if (reshow) requestShowSelf(0)
    }

    override fun deleteBackward() {
        if (panelInput.backspace()) return
        deleteLastEditorCluster()
    }

    override fun deleteGraphemeBackward() {
        if (panelInput.backspace()) return
        deleteLastEditorCluster()
    }

    private fun deleteNextEditorCluster() {
        val ic = currentInputConnection ?: return
        if (takesRawKeys(currentInputEditorInfo) || isWebEditor()) {
            sendKey(KeyEvent.KEYCODE_FORWARD_DEL, false)
            return
        }
        if (hasSelection()) {
            if (!replaceLargeSelection("")) ic.commitText("", 1)
            return
        }
        val after = ic.getTextAfterCursor(GraphemeText.WINDOW, 0)
        if (after == null) sendKey(KeyEvent.KEYCODE_FORWARD_DEL, false)
        else if (after.isNotEmpty()) ic.deleteSurroundingText(0, GraphemeText.nextCluster(after, 0))
    }

    private fun deleteLastEditorCluster() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(GraphemeText.WINDOW, 0) ?: ""
        val n = GraphemeText.lastClusterLength(before)
        if (n > 1) ic.deleteSurroundingText(n, 0) else sendKey(KeyEvent.KEYCODE_DEL, false)
    }

    override fun panelBackspace() {
        if (panelInput.backspace()) return
        controller.expireCandidateChoiceUndo()
        if (hasSelection()) deleteSelection() else deleteGraphemeBackward()
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
        panelInput.textBefore(n)?.let { return it }
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

    override fun hasSelection(): Boolean {
        if (panelInput.active) return panelInput.hasSelection()
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) return true
        val ic = currentInputConnection ?: return false
        val around = ic.getSurroundingText(0, 0, 0) ?: return false
        return around.selectionStart >= 0 &&
            around.selectionEnd >= 0 &&
            around.selectionStart != around.selectionEnd
    }

    override fun deleteSelection() {
        if (panelInput.active) { panelInput.deleteSelection(); return }
        if (replaceLargeSelection("")) return
        sendKey(KeyEvent.KEYCODE_DEL, false)
    }

    override fun performEnter() {
        if (panelInput.newline()) return
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

private const val EDITABLE_CLIP_CHARS = 4096L
private const val DECODE_TARGET_NANOS = 16_666_667L
private const val STREAM_GAP_MS = 16L
private const val STREAM_CHUNK = 16_384
private const val TRIM_WINDOW = 8
private const val NAV_WINDOW = 16_384
private const val READ_TIMEOUT_MS = 1_500L
private const val WEB_WRITE_SETTLE_MS = 160L
private const val MAX_QUEUED_UNDOS = 50
private const val PREF_TRANSLATE_MODE = "translate_mode"
private const val TRANSLATE_DEBOUNCE_MS = 300L

internal fun quarantineCorruptStore(file: java.io.File): Boolean {
    if (!file.exists()) return false
    val aside = java.io.File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
    return file.renameTo(aside)
}
