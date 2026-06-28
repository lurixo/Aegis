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

/**
 * Aegis IME entry point. Builds the input view, wires it to [KeyboardController], and bridges
 * the controller's editor operations to the current [android.view.inputmethod.InputConnection].
 *
 * P2: EN commits ASCII directly; CN (26-key) routes through [DictEngine] for real candidates.
 * T9 disambiguation lands at P4; full syllable-segmenting decoder at P3.
 */
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
    private var selecting = false
    private var deletedSnapshot: CharSequence? = null // for the backspace up/down restore gesture (#5)
    private val clipboardStore by lazy { ClipboardStore(filesDir).also { it.load() } }
    private val clipImageStore by lazy { com.aegis.ime.user.ClipImageStore(filesDir) } // U22 image clipboard
    private val thumbCache = LruCache<String, Bitmap>(50) // U22: decoded thumbnails (path → bitmap)
    private val symbolUsageStore by lazy { SymbolUsageStore(filesDir).also { it.load() } }
    // C1 privacy: pause clipboard capture while a password / PIN / 2FA field is focused (set per onStartInput).
    @Volatile private var secureField = false
    // U21: the most-recent captured clip — kept so the 复制条 survives an app switch / IME re-show (restored
    // in onStartInputView). Cleared when the user leaves the bar (× or 上屏).
    private var lastCopy: String? = null
    @Volatile private var userDbLoaded = false // M-2: the initial userdb load has completed
    private var imePalette = ImePalette.STATIC_LIGHT // F1: live Monet palette (dark-aware)

    /** F1: the current Monet palette for this process config (dark = system night mode). */
    private fun computePalette(): ImePalette {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return ImePalette.from(this, dark)
    }

    /** F1: recompute + fan the palette out to the input view and every cached panel (theme change). */
    private fun applyPaletteEverywhere() {
        imePalette = computePalette()
        inputView?.applyPalette(imePalette)
        emojiView?.applyPalette(imePalette)
        clipboardView?.applyPalette(imePalette)
        symbolsView?.applyPalette(imePalette)
        editPanelView?.applyPalette(imePalette)
        customSymbolView?.applyPalette(imePalette)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPaletteEverywhere() // F1: follow a light/dark (or accent) change live, no stale colours
    }
    private val clipboardManager by lazy { getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    // C1: passive clipboard monitoring — record EVERY primary-clip change (incl. passwords/sensitive),
    // not just on explicit panel-open. On-device only (ClipboardStore is plain-text in filesDir, nothing
    // leaves the device). Android 10+ only grants clipboard reads to the focused IME, so this fires while
    // Aegis is the active input method.
    private val clipChangedListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { onSystemClipChanged() }

    override fun onCreate() {
        super.onCreate()
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipChangedListener) }
        // Start with an empty engine (ASCII typing works immediately); load the ~70 MB dictionaries
        // and the user model off the main thread and swap the real engine in when ready.
        controller = KeyboardController(this, DictEngine(null, null, null))
        controller.onShowEmoji = { showEmojiPanel() }
        controller.onShowClipboard = { showClipboardPanel() }
        controller.onShowEdit = { showEditPanel() }
        controller.onShowSymbols = { showSymbolsPanel() }
        controller.onShowSettings = { openSettings() }
        controller.onShowCustomSymbols = { showCustomSymbolPanel() }
        controller.onClosePanel = { inputView?.showPanel(null) }
        controller.setCustomSymbols(customSymbolStore.list()) // A3: seed the punctuation column with saved marks
        Thread {
            runCatching { userModel.load(userDbFile); userDbMtime = userDbFile.lastModified() }
            userDbLoaded = true // M-2: gate onStartInput's reload until the initial load is done
            val dict = loadDict("aegis_dict.bin")
            val t9Dict = loadDict("aegis_t9.bin")
            // Per-rule fuzzy (E4): the enabled rule keys drive query-time variant expansion in the
            // decoder, so no separate fuzzy index is loaded. Master "fuzzy" off → no rules.
            val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
            val fuzzyRules = if (!prefs.getBoolean("fuzzy", Fuzzy.DEFAULT_ON)) emptySet()
                else Fuzzy.RULES.filter { prefs.getBoolean(Fuzzy.prefKey(it.key), true) }
                    .mapTo(LinkedHashSet()) { it.key }
            val initialsDict = loadDict("aegis_jianpin.bin")
            val lm = loadLm("aegis_lm.bin")
            // Optional top-tier context model, only if the user downloaded it.
            val octagram = runCatching { OctagramReader.fromDownloads(this, "wanxiang-lts-zh-hans.gram") }
                .onFailure { Log.e("Aegis", "octagram load failed", it) }.getOrNull()
            val enDict = loadDict("aegis_en.bin")
            val engine = DictEngine(dict, t9Dict, lm, userModel, fuzzyRules, initialsDict, octagram, enDict)
            Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
        }.apply { name = "aegis-dict-load"; isDaemon = true }.start()
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // C1 privacy: don't harvest clips while a password/2FA field is focused.
        secureField = info != null && com.aegis.ime.user.ClipboardPolicy.isSensitive(info.inputType)
        // M-3/L-3 privacy: never learn committed words in a password / NO_PERSONALIZED_LEARNING field.
        controller.setLearningBlocked(
            info != null && com.aegis.ime.user.ClipboardPolicy.blocksLearning(info.inputType, info.imeOptions),
        )
        // Pick up an imported user dict (newer file, no unsaved edits) without restarting the IME — but
        // M-2: not until the initial background load has finished, so reload() can't race load().
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

    /** Prefer a downloaded enhancement pack (optional full dict / .gram tier) over the bundled asset. */
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
            onPickReading = { index -> controller.onPickReadingIndex(index) } // A2 expanded: pick combination
            onFunction = { f -> controller.onBarFunction(f) }
            // C: up-swipe clears the pending pinyin (重输) in any layout first; only if there's nothing
            // composing does it fall back to the editor-field clear/restore (#5).
            onBackspaceSwipe = { up -> if (!controller.onBackspaceSwipe(up)) handleBackspaceSwipe(up) }
            onPanelBackspace = { controller.onPanelBackspace() } // A2 expanded: 退格
            onPanelClear = { controller.onPanelClear() }          // A2 expanded: 重输
            onCollapse = { requestHideSelf(0) } // idle toolbar ⌄ collapses the keyboard
            onCopyCommit = { t -> currentInputConnection?.commitText(t, 1) } // 复制条 ⑤: 上屏 (到当前字段)
            onCopyBlock = { b -> copyBlockToAegis(b) }                        // 复制条 ③: 写 aegis 剪贴板(不上屏/不写系统)
            onCopyDismiss = { lastCopy = null }                              // U21: ④/⑤ 离开 → 不再恢复该复制条
        }
        inputView = view
        controller.attachView(view)
        imePalette = computePalette()
        view.applyPalette(imePalette) // F1: dynamic Monet colours (dark-aware) come alive here
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputView?.showPanel(null)
        // U21: restore the most-recent 复制条 across an app switch / IME re-show (reverses the old
        // "start clean" behaviour). Suppressed in a password/secure field. ⑤ "点内容→上屏" then targets
        // the current field (acceptable behaviour).
        val lc = lastCopy
        if (lc != null && !secureField) inputView?.showCopyBar(lc) else inputView?.hideCopyBar()
        // B5: honour the user's CN default-keyboard choice (9-key unless they picked 26-key); EN stays 26-key.
        val cnLayout = getSharedPreferences("aegis", MODE_PRIVATE).getString("cn_layout", "nine")
        controller.setCnDefaultLayout(if (cnLayout == "alpha") LayoutId.ALPHA else LayoutId.NINE)
        controller.reset()
        applyPaletteEverywhere() // F1: pick up a theme change that happened between input sessions
    }

    /**
     * the preedit band at the top of the input view is transparent. Report the IME's
     * content/visible top at the candidate bar (one band-height below the input-view top) so the host app
     * reclaims that strip and only the floating pinyin tab overlaps it — instead of being pushed up by a
     * full-width grey bar. The band height is constant, so the host's layout still never jitters.
     */
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

    /** Text-editing panel (#4): maps the D-pad / selection / clipboard actions onto the editor. */
    private fun showEditPanel() {
        val iv = inputView ?: return
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

    /** #5: swipe up on backspace clears the whole field (snapshotted); swipe down restores it. */
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

    /** Show the emoji panel in place of the keyboard; emoji commit straight to the editor. */
    private fun showEmojiPanel() {
        val iv = inputView ?: return
        val ev = emojiView ?: EmojiView(this).also {
            it.onEmoji = { e -> currentInputConnection?.commitText(e, 1) }
            it.onBackspace = { currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0) }
            it.onBack = { inputView?.showPanel(null) }
            emojiView = it
        }
        ev.applyPalette(imePalette)
        iv.showPanel(ev)
    }

    /** Show the clipboard / canned-phrases panel; tapping an entry commits it. */
    private fun showClipboardPanel() {
        val iv = inputView ?: return
        captureClip() // read the current clip only on explicit user intent (avoids spurious access)
        val cv = clipboardView ?: ClipboardView(this).also {
            it.historyProvider = { clipboardStore.history() }
            it.categoriesProvider = { clipboardStore.categories() }                       // C5 分类
            it.phrasesInProvider = { cat -> clipboardStore.phrasesIn(cat) }
            it.onPick = { t -> currentInputConnection?.commitText(t, 1); inputView?.showPanel(null) }
            // M-1: an entry is an image only if the marker is backed by a real file under clipboard_images.
            it.isImage = { e -> ClipboardStore.isImageEntry(e) && clipImageStore.isStoredImage(ClipboardStore.imagePath(e)) }
            it.onPickImage = { path -> pasteImage(path) }                                  // U22: 点图片条目 → commitContent
            it.thumbnailProvider = { path -> thumbCache.get(path) }                        // U22: 缓存命中(同步)
            it.onLoadThumbnail = { path, cb -> loadThumbnailAsync(path, cb) }              // U22: 未命中后台解码
            it.onCopyBlockToAegis = { b -> copyBlockToAegis(b) }                          // ③ 拆词块写 aegis 剪贴板(不上屏/不写系统)
            it.onBack = { inputView?.showPanel(null) }
            it.onDeleteClips = { list -> clipboardStore.deleteAll(list); deleteImageFiles(list) } // C7 多选删除(+图片文件)
            it.onDeletePhrasesFrom = { cat, list -> list.forEach { clipboardStore.deletePhraseFrom(cat, it) } }
            it.onSaveAsPhrasesTo = { cat, list -> clipboardStore.addPhrasesTo(cat, list) } // C7 批量添加常用语
            it.onManage = { openPhraseManager() }                                          // C5 管理 / 新建分类
            it.onClearSystemClipboard = { clearSystemClipboard() }                         // C2
            it.onClearHistory = { clipboardStore.clearHistory(); clipImageStore.clear(); thumbCache.evictAll() }
            it.historyEnabledProvider = { historyEnabled() }                               // C1 记录开关
            it.onSetHistoryEnabled = { on -> setHistoryEnabled(on) }
            clipboardView = it
        }
        cv.applyPalette(imePalette)
        clipboardStore.reloadPhrases() // pick up category/phrase edits made in the manager Activity
        cv.reset() // always open on the 剪贴板 tab in normal (non-select) mode
        cv.refresh()
        iv.showPanel(cv)
    }

    /** A3 自定义: edit the user's custom punctuation marks; changes persist + refresh the 9-key column live. */
    private fun showCustomSymbolPanel() {
        val iv = inputView ?: return
        val panel = customSymbolView ?: CustomSymbolPanel(this).also {
            it.current = { customSymbolStore.list() }
            it.onAdd = { s -> customSymbolStore.add(s); controller.setCustomSymbols(customSymbolStore.list()); it.refresh() }
            it.onRemove = { s -> customSymbolStore.remove(s); controller.setCustomSymbols(customSymbolStore.list()); it.refresh() }
            it.onPaste = { pasteCustomSymbol() } // U13: 粘贴 aegis 没有的符号
            it.onBack = { inputView?.showPanel(null) }
            customSymbolView = it
        }
        panel.applyPalette(imePalette) // also calls refresh() — no separate refresh needed
        iv.showPanel(panel)
    }

    /** U13: add the system clipboard's content as a custom 9-key mark (paste a symbol aegis doesn't ship). */
    private fun pasteCustomSymbol() {
        // Read item.text only (no coerceToText → no main-thread ContentResolver read for URI clips).
        // U13: strip control chars (incl. internal \n\r) so a multi-line paste can't split into several marks.
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

    /** D: categorized symbols panel (reached from the keyboard ✎ pencil key). U3: a tap 上屏s + closes the panel. */
    private fun showSymbolsPanel() {
        val iv = inputView ?: return
        val sv = symbolsView ?: SymbolsView(this).also {
            it.recentProvider = { symbolUsageStore.recent() }
            // U3: 点符号 = 上屏 + 自动回到点击前界面(关闭符号面板,回到键盘)。
            it.onSymbol = { s -> symbolUsageStore.record(s); currentInputConnection?.commitText(s, 1); inputView?.showPanel(null) }
            it.onBackspace = { currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0) }
            it.onBack = { inputView?.showPanel(null) }
            symbolsView = it
        }
        sv.applyPalette(imePalette) // also re-renders the active category (picks up the latest 常用 MRU)
        iv.showPanel(sv)
    }

    /** In-keyboard settings entry: open the setup/settings screen. */
    private fun openSettings() {
        runCatching {
            startActivity(
                android.content.Intent(this, com.aegis.ime.ui.SetupActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** C5: open the canned-phrase category manager (text input needs a real Activity window). */
    private fun openPhraseManager() {
        runCatching {
            startActivity(
                android.content.Intent(this, com.aegis.ime.ui.PhraseManagerActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun captureClip() {
        if (secureField || !historyEnabled()) return // C1: skip password fields / when history is off
        runCatching {
            val clip = clipboardManager.primaryClip ?: return
            val item = clip.getItemAt(0) ?: return
            // A2: don't record an image/URI item as a bogus text entry (coerceToText would give "content://…").
            if (item.uri != null && item.text == null) return
            clipboardStore.record(item.coerceToText(this)?.toString())
        }
    }

    /**
     * 复制条: a system clipboard change while Aegis is active → record it AND raise the
     * taskbar copy-bar with that content (① 复制后). Same C1 privacy gate as [captureClip]. Fires on the
     * main thread (listener registered without a handler), so touching [inputView] here is safe.
     */
    private fun onSystemClipChanged() {
        if (secureField || !historyEnabled()) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val declaredImage = clip.description?.hasMimeType("image/*") == true
        val uri = item.uri
        if (uri != null) {
            // U22: a URI clip → resolve type + (if image) save bytes ALL off the main thread (A1: no
            // main-thread getType/IPC); otherwise fall back to its coerced text. Posts back to the main thread.
            val seed = System.currentTimeMillis()
            Thread {
                val isImage = declaredImage || runCatching { contentResolver.getType(uri)?.startsWith("image/") }.getOrNull() == true
                val path = if (isImage) clipImageStore.save(contentResolver, uri, seed) else null
                val text = if (isImage) null else runCatching { item.coerceToText(this)?.toString() }.getOrNull()?.trim()
                Handler(Looper.getMainLooper()).post {
                    runCatching { // A3: service may be gone by now
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
        // Plain text clip (no URI): item.text only, on the main thread.
        val t = item.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) recordTextClip(t)
    }

    /** Record a captured text clip + raise the copy-bar (unless composing). Shared by the text + URI paths. */
    private fun recordTextClip(t: String) {
        clipboardStore.record(t)
        lastCopy = t // U21: remember it so it survives an app switch / IME re-show
        if (inputView?.isComposing() != true) inputView?.showCopyBar(t) // don't clobber live candidates
    }

    /**
     * U22: insert a saved clipboard image into the target field via commitContent, granting a temporary
     * read on our FileProvider URI. Gracefully bails (toast, no crash, no silent fail) when the field can't
     * accept images — EditorInfoCompat.getContentMimeTypes must advertise a compatible image type.
     */
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

    /** U22: decode a ~160px thumbnail OFF the main thread, cache it, and deliver it on the main thread. */
    private fun loadThumbnailAsync(path: String, cb: (Bitmap?) -> Unit) {
        Thread {
            val b = clipImageStore.thumbnail(path, 160)?.also { thumbCache.put(path, it) }
            Handler(Looper.getMainLooper()).post { runCatching { cb(b) } }
        }.apply { isDaemon = true }.start()
    }

    /** U22: when image history entries are deleted, drop their backing files + cached thumbnails too. */
    private fun deleteImageFiles(entries: List<String>) {
        for (e in entries) if (ClipboardStore.isImageEntry(e)) {
            val p = ClipboardStore.imagePath(e); clipImageStore.delete(p); thumbCache.remove(p)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * ③ 拆词块 → aegis 剪贴板: write the block to [clipboardStore] (NOT the system clipboard, NOT the
     * editor) and confirm with a light toast so the tap doesn't feel like a no-op. Shared by the copy-bar
     * and the clipboard panel's 拆词 chips.
     */
    private fun copyBlockToAegis(block: String) {
        clipboardStore.record(block)
        Toast.makeText(this, "已存入剪贴板", Toast.LENGTH_SHORT).show()
    }

    // C1/C2 clipboard controls (wired to the panel ⚙ menu).
    private fun historyEnabled() = getSharedPreferences("aegis", MODE_PRIVATE).getBoolean("clip_history", true)
    private fun setHistoryEnabled(on: Boolean) =
        getSharedPreferences("aegis", MODE_PRIVATE).edit().putBoolean("clip_history", on).apply()
    /** C2: clear the SYSTEM clipboard (not the aegis private history). */
    private fun clearSystemClipboard() = runCatching { clipboardManager.clearPrimaryClip() }

    override fun onDestroy() {
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) }
        super.onDestroy()
    }

    // --- ImeHost ---

    override fun commitText(text: CharSequence) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun deleteBackward() {
        currentInputConnection?.deleteSurroundingText(1, 0)
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
        // S2: commitText("") over a live selection replaces (deletes) exactly the selected span — unlike
        // deleteSurroundingText(1,0), which is selection-start-relative and would eat the char before it.
        currentInputConnection?.commitText("", 1)
    }

    override fun performEnter() {
        // #7: editor-action fields (search/send/go/done) fire the action; everything else gets a real
        // ENTER key event so multi-line fields actually get a newline (sendDefaultEditorAction did neither).
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
