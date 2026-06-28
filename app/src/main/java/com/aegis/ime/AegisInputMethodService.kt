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
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.layout.LayoutId
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
    private val OPERATOR_PALETTE = listOf(
        "*", "/", "±", "√", "^", "<", ">", "≤", "≥", "≠", "≈", "∑", "∏", "∫", "π", "∞", "°", "|", "{", "}", "[", "]", "!",
    )
    private var selecting = false
    private var deletedSnapshot: CharSequence? = null
    private val clipboardStore by lazy { ClipboardStore(filesDir).also { it.load() } }
    private val clipImageStore by lazy { com.aegis.ime.user.ClipImageStore(filesDir) }
    private val thumbCache = LruCache<String, Bitmap>(50)
    private val symbolUsageStore by lazy { SymbolUsageStore(filesDir).also { it.load() } }
    @Volatile private var secureField = false
    private var lastCopy: String? = null
    @Volatile private var userDbLoaded = false
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
            val dict = loadDict("aegis_dict.bin")
            val t9Dict = loadDict("aegis_t9.bin")
            val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
            val fuzzyRules = if (!prefs.getBoolean("fuzzy", Fuzzy.DEFAULT_ON)) emptySet()
                else Fuzzy.RULES.filter { prefs.getBoolean(Fuzzy.prefKey(it.key), true) }
                    .mapTo(LinkedHashSet()) { it.key }
            val initialsDict = loadDict("aegis_jianpin.bin")
            val lm = loadLm("aegis_lm.bin")
            val octagram = runCatching { OctagramReader.fromDownloads(this, "wanxiang-lts-zh-hans.gram") }
                .onFailure { Log.e("Aegis", "octagram load failed", it) }.getOrNull()
            val enDict = loadDict("aegis_en.bin")
            val engine = DictEngine(dict, t9Dict, lm, userModel, fuzzyRules, initialsDict, octagram, enDict)
            Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
        }.apply { name = "aegis-dict-load"; isDaemon = true }.start()
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
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (userModel.dirty) runCatching {
            userModel.save(userDbFile)
            userDbMtime = userDbFile.lastModified()
        }
    }

    private fun downloadedOverride(name: String): File? =
        File(File(filesDir, "downloaded"), name).takeIf { it.exists() && it.length() > 0 }

    private fun loadDict(name: String): BinaryDict? =
        runCatching { downloadedOverride(name)?.let { BinaryDict.fromFile(it) } ?: BinaryDict.fromAssets(this, name) }
            .onFailure { Log.e("Aegis", "dict load failed: $name", it) }
            .getOrNull()

    private fun loadLm(name: String): CharBigramLM? =
        runCatching { downloadedOverride(name)?.let { CharBigramLM.fromFile(it) } ?: CharBigramLM.fromAssets(this, name) }
            .onFailure { Log.e("Aegis", "lm load failed: $name", it) }
            .getOrNull()

    override fun onCreateInputView(): View {
        val view = InputView(this).apply {
            onKey = { key -> controller.onKey(key) }
            onPickCandidate = { index -> controller.onPickCandidate(index) }
            onPickReading = { index -> controller.onPickReadingIndex(index) }
            onFunction = { f -> controller.onBarFunction(f) }
            onBackspaceSwipe = { up -> if (!controller.onBackspaceSwipe(up)) handleBackspaceSwipe(up) }
            onPanelBackspace = { controller.onPanelBackspace() }
            onPanelClear = { controller.onPanelClear() }
            onCollapse = { requestHideSelf(0) }
            onCopyCommit = { t -> currentInputConnection?.commitText(t, 1) }
            onCopyBlock = { b -> copyBlockToAegis(b) }
            onCopyDismiss = { lastCopy = null }
        }
        inputView = view
        controller.attachView(view)
        imePalette = computePalette()
        view.applyPalette(imePalette)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputView?.showPanel(null)
        val lc = lastCopy
        if (lc != null && !secureField) inputView?.showCopyBar(lc) else inputView?.hideCopyBar()
        val cnLayout = getSharedPreferences("aegis", MODE_PRIVATE).getString("cn_layout", "nine")
        controller.setCnDefaultLayout(if (cnLayout == "alpha") LayoutId.ALPHA else LayoutId.NINE)
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
        selecting = false
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
        when (action) {
            EditAction.UP -> sendKey(KeyEvent.KEYCODE_DPAD_UP, selecting)
            EditAction.DOWN -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN, selecting)
            EditAction.LEFT -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, selecting)
            EditAction.RIGHT -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, selecting)
            EditAction.HOME -> sendKey(KeyEvent.KEYCODE_MOVE_HOME, selecting)
            EditAction.END -> sendKey(KeyEvent.KEYCODE_MOVE_END, selecting)
            EditAction.START_SELECT -> { selecting = !selecting; editPanelView?.setSelecting(selecting) }
            EditAction.DELETE -> sendKey(KeyEvent.KEYCODE_DEL, false)
            EditAction.COPY -> currentInputConnection?.performContextMenuAction(android.R.id.copy)
            EditAction.CUT -> currentInputConnection?.performContextMenuAction(android.R.id.cut)
            EditAction.SELECT_ALL -> currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            EditAction.PASTE -> currentInputConnection?.performContextMenuAction(android.R.id.paste)
            EditAction.BACK -> { selecting = false; inputView?.showPanel(null) }
        }
    }

    private fun sendKey(code: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun handleBackspaceSwipe(up: Boolean) {
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
            it.onEmoji = { e -> currentInputConnection?.commitText(e, 1) }
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
            it.onPick = { t -> currentInputConnection?.commitText(t, 1); inputView?.showPanel(null) }
            it.isImage = { e -> ClipboardStore.isImageEntry(e) && clipImageStore.isStoredImage(ClipboardStore.imagePath(e)) }
            it.onPickImage = { path -> pasteImage(path) }
            it.thumbnailProvider = { path -> thumbCache.get(path) }
            it.onLoadThumbnail = { path, cb -> loadThumbnailAsync(path, cb) }
            it.onCopyBlockToAegis = { b -> copyBlockToAegis(b) }
            it.onBack = { inputView?.showPanel(null) }
            it.onDeleteClips = { list -> clipboardStore.deleteAll(list); deleteImageFiles(list) }
            it.onDeletePhrasesFrom = { cat, list -> list.forEach { clipboardStore.deletePhraseFrom(cat, it) } }
            it.onSaveAsPhrasesTo = { cat, list -> clipboardStore.addPhrasesTo(cat, list) }
            it.onManage = { openPhraseManager() }
            it.onClearSystemClipboard = { clearSystemClipboard() }
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
            it.current = { customSymbolStore.list() }
            it.onAdd = { s -> customSymbolStore.add(s); controller.setCustomSymbols(customSymbolStore.list()); it.refresh() }
            it.onRemove = { s -> customSymbolStore.remove(s); controller.setCustomSymbols(customSymbolStore.list()); it.refresh() }
            it.onPaste = { pasteCustomSymbol() }
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
            it.pasteLabel = "📋 粘贴运算符"
            it.addPalette = OPERATOR_PALETTE
            it.current = { customOperatorStore.list() }
            it.onAdd = { s -> customOperatorStore.add(s); controller.setCustomOperators(customOperatorStore.list()); it.refresh() }
            it.onRemove = { s -> customOperatorStore.remove(s); controller.setCustomOperators(customOperatorStore.list()); it.refresh() }
            it.onPaste = { pasteCustomOperator() }
            it.onBack = { inputView?.showPanel(null) }
            customOperatorView = it
        }
        panel.applyPalette(imePalette)
        iv.showPanel(panel)
    }

    private fun pasteCustomOperator() {
        val t = runCatching {
            clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()?.filterNot { it.isISOControl() }?.trim().orEmpty()
        val msg = when {
            t.isEmpty() -> "剪贴板为空"
            t.length > 16 -> "内容过长,未作为运算符添加"
            customOperatorStore.add(t) -> {
                controller.setCustomOperators(customOperatorStore.list()); customOperatorView?.refresh(); "已添加：$t"
            }
            else -> "已存在或已达上限"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun pasteCustomSymbol() {
        val t = runCatching {
            clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()?.filterNot { it.isISOControl() }?.trim().orEmpty()
        val msg = when {
            t.isEmpty() -> "剪贴板为空"
            t.length > 16 -> "内容过长,未作为符号添加"
            customSymbolStore.add(t) -> {
                controller.setCustomSymbols(customSymbolStore.list()); customSymbolView?.refresh(); "已添加：$t"
            }
            else -> "已存在或已达上限"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showSymbolsPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(symbolsView)) { iv.showPanel(null); return }
        val sv = symbolsView ?: SymbolsView(this).also {
            it.recentProvider = { symbolUsageStore.recent() }
            it.onSymbol = { s -> symbolUsageStore.record(s); currentInputConnection?.commitText(s, 1) }
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

    private fun openPhraseManager() {
        runCatching {
            startActivity(
                android.content.Intent(this, com.aegis.ime.ui.PhraseManagerActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun captureClip() {
        if (secureField || !historyEnabled()) return
        runCatching {
            val clip = clipboardManager.primaryClip ?: return
            val item = clip.getItemAt(0) ?: return
            if (item.uri != null && item.text == null) return
            clipboardStore.record(item.coerceToText(this)?.toString())
        }
    }

    private fun onSystemClipChanged() {
        if (secureField || !historyEnabled()) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val declaredImage = clip.description?.hasMimeType("image/*") == true
        val uri = item.uri
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
        val ic = currentInputConnection
        val editorInfo = currentInputEditorInfo
        if (ic == null || editorInfo == null) { toast("插入失败"); return }
        val file = File(path)
        if (!file.exists()) { toast("图片已不存在"); return }
        val mime = com.aegis.ime.user.ClipImageStore.mimeOf(path)
        val accepts = EditorInfoCompat.getContentMimeTypes(editorInfo)
        if (accepts.none { ClipDescription.compareMimeTypes(mime, it) }) { toast("当前输入框不支持插入图片"); return }
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrNull()
        if (uri == null) { toast("插入失败"); return }
        val info = InputContentInfoCompat(uri, ClipDescription("clip image", arrayOf(mime)), null)
        val ok = runCatching {
            InputConnectionCompat.commitContent(ic, editorInfo, info, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
        if (ok) inputView?.showPanel(null) else toast("插入失败")
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
    private fun clearSystemClipboard() = runCatching { clipboardManager.clearPrimaryClip() }

    override fun onDestroy() {
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) }
        super.onDestroy()
    }


    override fun commitText(text: CharSequence) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun deleteBackward() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    override fun deleteCodePointBackward() {
        currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
    }

    override fun textBeforeCursor(n: Int): CharSequence =
        currentInputConnection?.getTextBeforeCursor(n, 0) ?: ""

    override fun replaceBeforeCursor(length: Int, text: CharSequence) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(length, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    override fun hasSelection(): Boolean = !currentInputConnection?.getSelectedText(0).isNullOrEmpty()

    override fun deleteSelection() {
        currentInputConnection?.commitText("", 1)
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
