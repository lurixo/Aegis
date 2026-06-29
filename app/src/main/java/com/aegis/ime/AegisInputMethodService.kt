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
import android.util.Log
import android.content.ClipDescription
import android.graphics.Bitmap
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.EngineAssets
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.SelectionMath
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.CustomSymbolStore
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserModel
import java.io.File

class AegisInputMethodService : InputMethodService(), ImeHost {

    private lateinit var controller: KeyboardController
    private val userModel = UserModel()
    private val userDbFile by lazy { File(filesDir, "userdb.txt") }
    @Volatile private var userDbMtime = 0L

    private var inputView: InputView? = null
    private var emojiView: EmojiView? = null
    private var clipboardView: ClipboardView? = null
    private var symbolsView: SymbolsView? = null
    private var editPanelView: EditPanelView? = null
    private var customSymbolView: CustomSymbolPanel? = null
    private val customSymbolStore by lazy { CustomSymbolStore(getSharedPreferences("aegis", MODE_PRIVATE)) }
    private var customOperatorView: CustomSymbolPanel? = null
    private val customOperatorStore by lazy { CustomSymbolStore(getSharedPreferences("aegis", MODE_PRIVATE), "custom_operators") }
    private val zhSymbolPalette: List<String> by lazy {
        SymbolCatalog.categories.first { it.id == "zh" }.symbols.filter { it !in Layouts.nineFixedPunctuation }
    }
    private val mathOperatorPalette: List<String> by lazy {
        SymbolCatalog.categories.first { it.id == "math" }.symbols.filter { it !in Layouts.defaultNumpadOperators }
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
    private var pendingPhraseAdds: List<String> = emptyList()
    private var pendingMoveFrom = ""
    private var pendingMoveTexts: List<String> = emptyList()
    private val clipboardStore by lazy { ClipboardStore(filesDir).also { it.load() } }
    private val clipImageStore by lazy { com.aegis.ime.user.ClipImageStore(filesDir) }
    private val thumbCache = LruCache<String, Bitmap>(50)
    private val symbolUsageStore by lazy { SymbolUsageStore(filesDir).also { it.load() } }
    private val emojiUsageStore by lazy { SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).also { it.load() } }
    @Volatile private var secureField = false
    private var lastCopy: String? = null
    @Volatile private var selfClipUri: android.net.Uri? = null
    @Volatile private var userDbLoaded = false
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
        customSymbolView?.applyPalette(imePalette)
        customOperatorView?.applyPalette(imePalette)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPaletteEverywhere()
    }
    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    private val clipChangedListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onSystemClipChanged() }

    override fun onCreate() {
        super.onCreate()
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipChangedListener) }
        controller = KeyboardController(this, DictEngine(null, null, null))
        controller.onShowEmoji = { showEmojiPanel() }
        controller.onShowClipboard = { showClipboardPanel() }
        controller.onShowEdit = { showEditPanel() }
        controller.onShowSymbols = { showSymbolsPanel() }
        controller.onShowSettings = { openSettings() }
        controller.onShowCustomSymbols = { showCustomSymbolPanel() }
        controller.onShowCustomOperators = { showCustomOperatorPanel() }
        controller.onClosePanel = { inputView?.showPanel(null) }
        controller.setCustomSymbols(customSymbolStore.list())
        controller.setCustomOperators(customOperatorStore.list())
        Thread {
            runCatching { userModel.load(userDbFile); userDbMtime = userDbFile.lastModified() }
            userDbLoaded = true
            val engine = buildEngine()
            Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
        }.apply { name = "aegis-dict-load"; isDaemon = true }.start()
    }

    private fun buildEngine(): DictEngine {
        val sig = EngineAssets.signature(File(filesDir, "downloaded"))
        val dict = loadDict("aegis_dict.bin")
        val t9Dict = loadDict("aegis_t9.bin")
        val fuzzyRules = currentFuzzyRules()
        val initialsDict = loadDict("aegis_jianpin.bin")
        val lm = loadLm("aegis_lm.bin")
        val octagram = runCatching { OctagramReader.fromDownloads(this, "wanxiang-lts-zh-hans.gram") }
            .onFailure { Log.e("Aegis", "octagram load failed", it) }.getOrNull()
        val engine = DictEngine(dict, t9Dict, lm, userModel, fuzzyRules, initialsDict, octagram)
        engineSig = sig
        return engine
    }

    private fun currentFuzzyRules(): Set<String> {
        val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
        return Fuzzy.activeRules(prefs.getBoolean("fuzzy", Fuzzy.DEFAULT_ON)) { prefs.getBoolean(Fuzzy.prefKey(it), true) }
    }

    private fun maybeReloadEngine() {
        if (engineSig.isEmpty() || engineReloading) return
        if (com.aegis.ime.dict.ModelDownload.installInProgress(filesDir)) return
        val current = EngineAssets.signature(File(filesDir, "downloaded"))
        if (!EngineAssets.needsReload(engineSig, current)) return
        engineReloading = true
        try {
            Thread {
                runCatching {
                    val engine = buildEngine()
                    Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
                }.onFailure { Log.e("Aegis", "engine hot-reload failed", it) }
                engineReloading = false
            }.apply { name = "aegis-dict-reload"; isDaemon = true }.start()
        } catch (t: Throwable) {
            Log.e("Aegis", "engine hot-reload thread start failed", t)
            engineReloading = false
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        secureField = info != null && com.aegis.ime.user.ClipboardPolicy.isSensitive(info.inputType)
        controller.setLearningBlocked(
            info != null && com.aegis.ime.user.ClipboardPolicy.blocksLearning(info.inputType, info.imeOptions),
        )
        if (userDbLoaded && !userModel.dirty && userDbFile.lastModified() > userDbMtime) {
            runCatching { userModel.reload(userDbFile); userDbMtime = userDbFile.lastModified() }
        }
        maybeReloadEngine()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        abortInlineInput()
        if (userModel.dirty) runCatching {
            userModel.save(userDbFile)
            userDbMtime = userDbFile.lastModified()
        }
    }

    private fun downloadedOverride(name: String): File? =
        File(File(filesDir, "downloaded"), name).takeIf { it.exists() && it.length() > 0 }

    private fun loadDict(name: String): BinaryDict? {
        downloadedOverride(name)?.let { f ->
            runCatching { BinaryDict.fromFile(f) }
                .onFailure { Log.e("Aegis", "downloaded dict unreadable, falling back to bundled: $name", it) }
                .getOrNull()?.let { return it }
        }
        return runCatching { BinaryDict.fromAssets(this, name) }
            .onFailure { Log.e("Aegis", "dict load failed: $name", it) }
            .getOrNull()
    }

    private fun loadLm(name: String): CharBigramLM? {
        downloadedOverride(name)?.let { f ->
            runCatching { CharBigramLM.fromFile(f) }
                .onFailure { Log.e("Aegis", "downloaded lm unreadable, falling back to bundled: $name", it) }
                .getOrNull()?.let { return it }
        }
        return runCatching { CharBigramLM.fromAssets(this, name) }
            .onFailure { Log.e("Aegis", "lm load failed: $name", it) }
            .getOrNull()
    }

    override fun onCreateInputView(): View {
        val view = InputView(this).apply {
            onKey = { key -> controller.onKey(key) }
            onPickCandidate = { index -> controller.onPickCandidate(index) }
            onPickReading = { index -> controller.onPickReadingIndex(index) }
            onFunction = { f -> controller.onBarFunction(f) }
            onBackspaceSwipe = { up ->
                if (!controller.onBackspaceSwipe(up)) {
                    if (panelInput.active) { if (up) panelInput.begin("") }
                    else handleBackspaceSwipe(up)
                }
            }
            onPanelBackspace = { controller.onPanelBackspace() }
            onPanelClear = { controller.onPanelClear() }
            onExpandClosed = { controller.clearDrill() }
            onCollapse = { requestHideSelf(0) }
            onCopyCommit = { t -> commitLargeText(t) }
            onCopyBlock = { b -> copyBlockToAegis(b) }
            onCopyDismiss = { lastCopy = null }
            onEditConfirm = { confirmInlineInput() }
            onEditCancel = { cancelInlineInput() }
        }
        inputView = view
        panelInput.onChange = { txt -> view.setEditText(txt) }
        controller.attachView(view)
        imePalette = computePalette()
        view.applyPalette(imePalette)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        abortInlineInput()
        inputView?.showPanel(null)
        val lc = lastCopy
        if (com.aegis.ime.user.ClipboardPolicy.shouldRestoreCopyBar(lc, secureField)) {
            inputView?.showCopyBar(lc!!)
        } else {
            inputView?.hideCopyBar()
        }
        val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
        val cnLayout = prefs.getString("cn_layout", "nine")
        controller.setCnDefaultLayout(if (cnLayout == "alpha") LayoutId.ALPHA else LayoutId.NINE)
        controller.setAssociationsEnabled(
            prefs.getBoolean(com.aegis.ime.ui.PREF_ASSOCIATIONS_ON, com.aegis.ime.ui.ASSOCIATIONS_DEFAULT_ON),
        )
        controller.setFuzzyRules(currentFuzzyRules())
        controller.reset()
        applyPaletteEverywhere()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = inputView ?: return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val top = loc[1] + v.barTopInsetPx()
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
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
            editPanelView = it
        }
        ep.applyPalette(imePalette)
        ep.setSelecting(false)
        ep.setHasSelection(!currentInputConnection?.getSelectedText(0).isNullOrEmpty())
        iv.showPanel(ep)
    }

    private fun handleEdit(action: EditAction) {
        if (panelInput.active && action != EditAction.BACK) return
        when (action) {
            EditAction.UP -> nav(KeyEvent.KEYCODE_DPAD_UP, SelectionMath.Move.UP)
            EditAction.DOWN -> nav(KeyEvent.KEYCODE_DPAD_DOWN, SelectionMath.Move.DOWN)
            EditAction.LEFT -> nav(KeyEvent.KEYCODE_DPAD_LEFT, SelectionMath.Move.LEFT)
            EditAction.RIGHT -> nav(KeyEvent.KEYCODE_DPAD_RIGHT, SelectionMath.Move.RIGHT)
            EditAction.HOME -> nav(KeyEvent.KEYCODE_MOVE_HOME, SelectionMath.Move.HOME)
            EditAction.END -> nav(KeyEvent.KEYCODE_MOVE_END, SelectionMath.Move.END)
            EditAction.START_SELECT -> toggleSelecting()
            EditAction.DELETE -> sendKey(KeyEvent.KEYCODE_DEL, false)
            EditAction.COPY -> currentInputConnection?.performContextMenuAction(android.R.id.copy)
            EditAction.CUT -> currentInputConnection?.performContextMenuAction(android.R.id.cut)
            EditAction.SELECT_ALL -> currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            EditAction.PASTE -> currentInputConnection?.performContextMenuAction(android.R.id.paste)
            EditAction.BACK -> { stopSelecting(); inputView?.showPanel(null) }
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
        if (up) {
            val all = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text
            if (!all.isNullOrEmpty()) {
                deletedSnapshot = all
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
            }
        } else {
            deletedSnapshot?.let { ic.commitText(it, 1); deletedSnapshot = null }
        }
    }

    private fun showEmojiPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(emojiView)) { iv.showPanel(null); return }
        val ev = emojiView ?: EmojiView(this).also {
            it.recentProvider = { emojiUsageStore.recent() }
            it.onEmoji = { e -> emojiUsageStore.record(e); commitText(e) }
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
        if (iv.isPanelShowing(clipboardView)) { iv.showPanel(null); return }
        captureClip()
        val cv = clipboardView ?: ClipboardView(this).also {
            it.historyProvider = { clipboardStore.history() }
            it.categoriesProvider = { clipboardStore.categories() }
            it.phrasesInProvider = { cat -> clipboardStore.phrasesIn(cat) }
            it.phraseNoteProvider = { cat, text -> clipboardStore.noteFor(cat, text) }
            it.onPick = { t -> commitLargeText(t); inputView?.showPanel(null) }
            it.isImage = { e -> ClipboardStore.isImageEntry(e) && clipImageStore.isStoredImage(ClipboardStore.imagePath(e)) }
            it.onPickImage = { path -> pasteImage(path) }
            it.thumbnailProvider = { path -> thumbCache.get(path) }
            it.onLoadThumbnail = { path, cb -> loadThumbnailAsync(path, cb) }
            it.onCopyBlockToAegis = { b -> copyBlockToAegis(b) }
            it.onBack = { inputView?.showPanel(null) }
            it.onDeleteClips = { list -> clipboardStore.deleteAll(list); deleteImageFiles(list) }
            it.onDeletePhrasesFrom = { cat, list -> list.forEach { clipboardStore.deletePhraseFrom(cat, it) } }
            it.onSaveAsPhrasesTo = { cat, list -> clipboardStore.addPhrasesTo(cat, list) }
            it.onEditPhrase = { cat, text -> beginInlineEdit(cat, text) }
            it.onMovePhrase = { from, text, to -> clipboardStore.movePhrase(from, text, to) }
            it.onMovePhrasesTo = { from, list, to -> clipboardStore.movePhrasesTo(from, list, to) }
            it.onReorderPhrase = { cat, fromIdx, toIdx -> clipboardStore.reorderPhrase(cat, fromIdx, toIdx) }
            it.onAddPhrase = { cat -> beginInlineAddPhrase(cat) }
            it.onAddCategory = { beginInlineAddCategory() }
            it.onAddCategoryThenAdd = { texts -> beginInlineAddCategory(texts) }
            it.onAddCategoryThenMove = { from, texts -> beginInlineAddCategory(pendingMove = from to texts) }
            it.onRenameCategory = { old -> beginInlineRenameCategory(old) }
            it.onDeleteCategory = { name -> clipboardStore.deleteCategory(name) }
            it.onEditNote = { cat, text -> beginInlineEditNote(cat, text) }
            it.onClearCategory = { cat -> clipboardStore.clearPhrasesIn(cat) }
            it.onExportPhrases = { launchPhraseTransfer(export = true) }
            it.onImportPhrases = { launchPhraseTransfer(export = false) }
            it.onClearHistory = { clipboardStore.clearHistory(); clipImageStore.clear(); thumbCache.evictAll() }
            it.historyEnabledProvider = { historyEnabled() }
            it.onSetHistoryEnabled = { on -> setHistoryEnabled(on) }
            clipboardView = it
        }
        cv.resetToDefault()
        cv.applyPalette(imePalette)
        clipboardStore.reloadPhrases()
        cv.refresh()
        iv.showPanel(cv)
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
            it.backTitle = "‹ 自定义运算符"
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
            it.onSymbol = { s -> symbolUsageStore.record(s); commitText(s) }
            it.onBackspace = { panelBackspace() }
            it.onBack = { inputView?.showPanel(null) }
            symbolsView = it
        }
        sv.resetToDefault()
        sv.applyPalette(imePalette)
        iv.showPanel(sv)
    }

    private fun openSettings() {
        runCatching {
            startActivity(
                android.content.Intent(this, com.aegis.ime.ui.SetupActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun launchPhraseTransfer(export: Boolean) {
        runCatching {
            startActivity(
                android.content.Intent(this, com.aegis.ime.ui.PhraseTransferActivity::class.java)
                    .putExtra(com.aegis.ime.ui.PhraseTransferActivity.EXTRA_EXPORT, export)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            inputView?.showPanel(null)
        }
    }


    private fun beginInlineEdit(category: String, phrase: String) {
        inputPurpose = InputPurpose.EDIT_PHRASE; inputCat = category; inputOld = phrase
        startInlineInput("编辑常用语", phrase)
    }

    private fun beginInlineAddPhrase(category: String) {
        inputPurpose = InputPurpose.ADD_PHRASE; inputCat = category; inputOld = ""
        startInlineInput("添加常用语", "")
    }

    private fun beginInlineEditNote(category: String, text: String) {
        inputPurpose = InputPurpose.EDIT_NOTE; inputCat = category; inputOld = text
        startInlineInput("备注", clipboardStore.noteFor(category, text))
    }

    private fun beginInlineAddCategory(
        pendingAdds: List<String> = emptyList(),
        pendingMove: Pair<String, List<String>>? = null,
    ) {
        inputPurpose = InputPurpose.ADD_CATEGORY; inputCat = ""; inputOld = ""
        pendingPhraseAdds = pendingAdds
        pendingMoveFrom = pendingMove?.first ?: ""
        pendingMoveTexts = pendingMove?.second ?: emptyList()
        startInlineInput("新建分类", "")
    }

    private fun beginInlineRenameCategory(old: String) {
        inputPurpose = InputPurpose.RENAME_CATEGORY; inputCat = ""; inputOld = old
        startInlineInput("重命名分类", old)
    }

    private fun startInlineInput(title: String, initial: String) {
        val iv = inputView ?: return
        iv.showPanel(null)
        panelInput.begin(initial)
        iv.setEditTitle(title)
        iv.setEditText(initial)
        iv.showEditBar(true)
    }

    private fun confirmInlineInput() {
        val text = panelInput.text()
        when (inputPurpose) {
            InputPurpose.EDIT_PHRASE -> clipboardStore.editPhrase(inputCat, inputOld, text)
            InputPurpose.ADD_PHRASE -> { val t = text.trim(); if (t.isNotEmpty()) clipboardStore.addPhrasesTo(inputCat, listOf(t)) }
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

    private fun cancelInlineInput() = endInlineInput()

    private fun endInlineInput() {
        val reopenCat = inputCat
        panelInput.end()
        inputView?.showEditBar(false)
        inputPurpose = null; inputCat = ""; inputOld = ""; pendingPhraseAdds = emptyList(); pendingMoveFrom = ""; pendingMoveTexts = emptyList()
        showClipboardPanel()
        clipboardView?.showPhraseTab(reopenCat)
    }

    private fun abortInlineInput() {
        if (!panelInput.active && inputPurpose == null) return
        panelInput.end()
        inputView?.showEditBar(false)
        inputPurpose = null; inputCat = ""; inputOld = ""; pendingPhraseAdds = emptyList(); pendingMoveFrom = ""; pendingMoveTexts = emptyList()
    }

    private fun captureClip() {
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return
        runCatching {
            val clip = clipboardManager.primaryClip ?: return
            val item = clip.getItemAt(0) ?: return
            if (item.uri != null && item.text == null) return
            clipboardStore.record(item.coerceToText(this)?.toString())
        }
    }

    private fun onSystemClipChanged() {
        if (!com.aegis.ime.user.ClipboardPolicy.shouldReadSystemClip(selfClipUri != null, false, historyEnabled())) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (com.aegis.ime.user.ClipImageStore.isSelfWrite(uri, selfClipUri)) { selfClipUri = null; return }
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return
        val declaredImage = clip.description?.hasMimeType("image/*") == true
        if (uri != null) {
            val seed = System.currentTimeMillis()
            Thread {
                val isImage = declaredImage || runCatching { contentResolver.getType(uri)?.startsWith("image/") }.getOrNull() == true
                val path = if (isImage) clipImageStore.save(contentResolver, uri, seed) else null
                val text = if (isImage) null else runCatching { item.coerceToText(this)?.toString() }.getOrNull()?.trim()
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        when {
                            isImage && path != null -> clipboardStore.recordImage(path)
                            isImage -> toast("图片过大或无法读取,未保存")
                            !text.isNullOrEmpty() -> recordTextClip(text)
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            return
        }
        val t = item.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) recordTextClip(t)
    }

    private fun recordTextClip(t: String) {
        clipboardStore.record(t)
        lastCopy = t
        if (inputView?.isComposing() != true) inputView?.showCopyBar(t)
    }

    private fun pasteImage(path: String) {
        if (panelInput.active) return
        val file = File(path)
        if (!file.exists()) { toast("图片已不存在"); return }
        val mime = com.aegis.ime.user.ClipImageStore.mimeOf(path)
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrNull()
        if (uri == null) { toast("插入失败"); return }

        val ic = currentInputConnection
        val editorInfo = currentInputEditorInfo
        val committed = if (ic != null && editorInfo != null) {
            val accepts = EditorInfoCompat.getContentMimeTypes(editorInfo)
            com.aegis.ime.user.ClipImageStore.deliverImage(accepts, mime) {
                val info = InputContentInfoCompat(uri, ClipDescription("clip image", arrayOf(mime)), null)
                runCatching {
                    InputConnectionCompat.commitContent(ic, editorInfo, info, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                }.getOrDefault(false)
            }
        } else false
        if (committed) { inputView?.showPanel(null); return }

        selfClipUri = uri
        val copied = runCatching {
            clipboardManager.setPrimaryClip(com.aegis.ime.user.ClipImageStore.imageClip(contentResolver, uri))
        }.isSuccess
        toast(if (copied) "图片已复制,请在输入框长按粘贴" else "插入失败")
        inputView?.showPanel(null)
    }

    private fun loadThumbnailAsync(path: String, cb: (Bitmap?) -> Unit) {
        Thread {
            val b = clipImageStore.thumbnail(path, 160)?.also { thumbCache.put(path, it) }
            Handler(Looper.getMainLooper()).post { runCatching { cb(b) } }
        }.apply { isDaemon = true }.start()
    }

    private fun deleteImageFiles(entries: List<String>) {
        for (e in entries) if (ClipboardStore.isImageEntry(e)) {
            val p = ClipboardStore.imagePath(e); clipImageStore.delete(p); thumbCache.remove(p)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun copyBlockToAegis(block: String) {
        clipboardStore.record(block)
        Toast.makeText(this, "已存入剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun historyEnabled() = getSharedPreferences("aegis", MODE_PRIVATE).getBoolean("clip_history", true)
    private fun setHistoryEnabled(on: Boolean) =
        getSharedPreferences("aegis", MODE_PRIVATE).edit().putBoolean("clip_history", on).apply()

    override fun onDestroy() {
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) }
        super.onDestroy()
    }


    override fun commitText(text: CharSequence) {
        if (panelInput.commit(text)) return
        currentInputConnection?.commitText(text, 1)
    }

    private fun commitLargeText(text: CharSequence) {
        if (panelInput.commit(text)) return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        com.aegis.ime.ime.LargeCommit.commit(text) { ic.commitText(it, 1) }
        ic.endBatchEdit()
    }

    override fun deleteBackward() {
        if (panelInput.backspace()) return
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    override fun deleteCodePointBackward() {
        if (panelInput.backspace()) return
        currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
    }

    override fun textBeforeCursor(n: Int): CharSequence =
        panelInput.textBefore(n) ?: currentInputConnection?.getTextBeforeCursor(n, 0) ?: ""

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
