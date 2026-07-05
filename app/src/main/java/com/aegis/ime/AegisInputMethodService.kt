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
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.GraphemeText
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LayoutPanelView
import com.aegis.ime.ime.ParallelLoad
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.UserDeletionPromises
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import java.io.File

class AegisInputMethodService : InputMethodService(), ImeHost {

    private lateinit var controller: KeyboardController
    private val mainHandler = Handler(Looper.getMainLooper())
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
    private var layoutPanelView: LayoutPanelView? = null
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
        layoutPanelView?.applyPalette(imePalette)
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
        controller = KeyboardController(
            this, DictEngine(null, null, null, userLexicon = userLexicon), decodeLane,
            emailDomains = com.aegis.ime.ime.EmailDomains(getSharedPreferences("aegis", MODE_PRIVATE)),
        )
        controller.onShowLayout = { showLayoutPanel() }
        controller.userLearning = userLearning
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
        super.onFinishInput()
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
            onOverlayChanged = { syncBackCallback() }
            onPreeditTap = { controller.onPreeditTap() }
            onPreeditCaret = { index -> controller.onPreeditCaret(index) }
            onPreeditEditDone = { controller.onPreeditEditDone() }
        }
        inputView = view
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
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
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
        LiveUserData.onLexiconsRestored = null
        runCatching {
            getSharedPreferences("aegis", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(settingsHotApply)
            getSharedPreferences("aegis", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(userLexiconListener)
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

internal fun quarantineCorruptStore(file: java.io.File): Boolean {
    if (!file.exists()) return false
    val aside = java.io.File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
    return file.renameTo(aside)
}
