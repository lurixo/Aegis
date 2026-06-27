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
import android.view.inputmethod.InputConnection
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.aegis.ime.ui.appLocaleTag
import com.aegis.ime.backup.RestoreJournal
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.EngineAssets
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.engine.StoredReadingRepair
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.GraphemeText
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LayoutPanelView
import com.aegis.ime.ime.ParallelLoad
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.CustomSymbolStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.LiveUserDictHost
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
    private var symbolsView: SymbolsView? = null
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
    private var selStart = -1
    private var selEnd = -1

    private val panelInput = com.aegis.ime.ime.PanelTextInput().also {
        it.onTargetChanged = { if (::controller.isInitialized) controller.onInputTargetChanged() }
    }
    private val clipboardStore by lazy {
        ClipboardStore(filesDir).also {
            it.load()
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

    private var currentEditorTarget: EditorTarget? = null
    private var layoutSessionPackage: String? = null
    private var inputSessionActive = false
    private var resetControllerOnNextInputView = false

    private var frameworkWillFinishInput = false
    private var translateOpen = false
    private var translateEngaged = true
    private var translateInputConnection: InputConnection? = null
    private var translatePending: Runnable? = null
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
        symbolsView?.applyPalette(imePalette)
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

    override fun onEvaluateFullscreenMode(): Boolean = false

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
        controller.onShowTranslate = { toggleTranslateBar() }
        controller.onShowLayout = { showLayoutPanel() }
        controller.onShowSymbols = { showSymbolsPanel() }
        controller.onShowCustomSymbols = { showCustomSymbolPanel() }
        controller.onShowCustomOperators = { showCustomOperatorPanel() }
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
        inputView?.clearEditorTransientUiImmediately()
        if (abortInline) abortInlineInput(hideBar = false)
        if (resetController && ::controller.isInitialized) controller.reset(preserveLayout)
    }

    private fun canRestoreCurrentSession(): Boolean =
        inputSessionActive && currentEditorTarget != null

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
        val view = InputView(imeUiContext()).apply {
            onKey = { key -> controller.onKey(key) }
            onPickCandidate = { index -> controller.onPickCandidate(index) }
            onPickReading = { index -> controller.onPickReadingIndex(index) }
            onFunction = { f -> controller.onBarFunction(f) }
            onPanelBackspace = { controller.onPanelBackspace() }
            onPanelClear = { controller.onPanelClear() }
            onExpandClosed = { controller.clearDrill() }
            onCollapse = { requestHideSelf(0) }
            onTranslateClose = { closeTranslateBar() }
            onTranslateFieldTap = { resumeTranslateRouting() }
            onOverlayChanged = { syncBackCallback() }
            onPreeditTap = { controller.onPreeditTap() }
            onPreeditCaret = { index -> controller.onPreeditCaret(index) }
            onPreeditEditDone = { controller.onPreeditEditDone() }
        }
        inputView = view
        view.onTranslateTextChanged = { text ->
            scheduleTranslation(text)
            refreshPanelEmailContext(view)
        }
        view.onTranslateModeChanged = { mode ->
            setTranslateMode(mode)
            scheduleTranslation(view.translateText())
        }
        view.onTranslateSelectionChanged = { has ->
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

    private fun restoreTransientUi(candidateView: InputView? = inputView) {
        val view = candidateView ?: return
        if (translateOpen) bindTranslateInput(view)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
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
        applyPaletteEverywhere()
    }

    private fun buildBackCallback(): OnBackInvokedCallback = OnBackInvokedCallback { closeTopOverlayOnBack() }

    private fun closeTopOverlayOnBack() {
        inputView?.closeTopOverlay()
        syncBackCallback()
    }

    private fun overlayOwnsBack(): Boolean = isInputViewShown && inputView?.hasOverlay() == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && overlayOwnsBack()) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && overlayOwnsBack()) {
            if (event.isTracking && !event.isCanceled) closeTopOverlayOnBack()
            return true
        }
        return super.onKeyUp(keyCode, event)
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
        selStart = newSelStart
        selEnd = newSelEnd
        if (::controller.isInitialized) controller.onEditorContextChanged()
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

    private fun sendKey(code: Int, shift: Boolean) =
        sendKeyWithMeta(code, if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

    private fun sendKeyWithMeta(code: Int, meta: Int) {
        if (panelInput.active) return
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
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
            it.onBack = { inputView?.showPanel(null) }
            emojiView = it
        }
        ev.resetToDefault()
        ev.applyPalette(imePalette)
        iv.showPanel(ev)
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
            it.onBack = { inputView?.showPanel(null) }
            symbolsView = it
        }
        sv.resetToDefault()
        sv.applyPalette(imePalette)
        iv.showPanel(sv)
    }

    private fun abortInlineInput(hideBar: Boolean = true) {
        if (!panelInput.active) return
        panelInput.end()
        if (hideBar) inputView?.showEditBar(false)
        bindTranslateInput()
    }

    private fun toggleTranslateBar() {
        if (translateOpen) closeTranslateBar() else openTranslateBar()
    }

    private fun openTranslateBar() {
        val iv = inputView ?: return
        translateOpen = true
        translateEngaged = true
        iv.showPanel(null)
        bindTranslateInput(iv)
    }

    private fun closeTranslateBar() {
        translateOpen = false
        finishTranslation()
        if (panelInput.active) {
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
        if (!translateOpen) return
        iv.setTranslateMode(translateMode())
        iv.setTranslateFieldEngaged(translateEngaged)
        iv.showTranslateBar(true)
        if (translateEngaged) panelInput.begin(iv.translateEditable()) { iv.isTranslateBarShowing() }
    }

    private fun pauseTranslateRouting() {
        if (!translateOpen || !translateEngaged) return
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
        if (!translateOpen || translateEngaged) return
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

    private fun toast(msg: String) { inputView?.showToast(msg) }

    internal fun toastTextForTest(): String? = inputView?.toastTextForTest()

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

private const val DECODE_TARGET_NANOS = 16_666_667L
private const val PREF_TRANSLATE_MODE = "translate_mode"
private const val TRANSLATE_DEBOUNCE_MS = 300L

internal fun quarantineCorruptStore(file: java.io.File): Boolean {
    if (!file.exists()) return false
    val aside = java.io.File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
    return file.renameTo(aside)
}
