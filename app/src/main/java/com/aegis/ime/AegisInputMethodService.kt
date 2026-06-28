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

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
    private val symbolUsageStore by lazy { SymbolUsageStore(filesDir).also { it.load() } }
    // C1 privacy: pause clipboard capture while a password / PIN / 2FA field is focused (set per onStartInput).
    @Volatile private var secureField = false
    @Volatile private var userDbLoaded = false // M-2: the initial userdb load has completed
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
            onCopyCommit = { t -> currentInputConnection?.commitText(t, 1) } // 复制条 ⑤: 上屏
            onCopyBlock = { b -> clipboardStore.record(b) }                   // 复制条 ③: 写 aegis 剪贴板(不上屏/不写系统)
        }
        inputView = view
        controller.attachView(view)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputView?.showPanel(null)
        // B5: honour the user's CN default-keyboard choice (9-key unless they picked 26-key); EN stays 26-key.
        val cnLayout = getSharedPreferences("aegis", MODE_PRIVATE).getString("cn_layout", "nine")
        controller.setCnDefaultLayout(if (cnLayout == "alpha") LayoutId.ALPHA else LayoutId.NINE)
        controller.reset()
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
            it.onCommitBlock = { b -> clipboardStore.record(b) }                          // ③ 拆词块写 aegis 剪贴板(不上屏/不写系统)
            it.onBack = { inputView?.showPanel(null) }
            it.onDeleteClips = { list -> clipboardStore.deleteAll(list) }                  // C7 多选删除
            it.onDeletePhrasesFrom = { cat, list -> list.forEach { clipboardStore.deletePhraseFrom(cat, it) } }
            it.onSaveAsPhrasesTo = { cat, list -> clipboardStore.addPhrasesTo(cat, list) } // C7 批量添加常用语
            it.onManage = { openPhraseManager() }                                          // C5 管理 / 新建分类
            it.onClearSystemClipboard = { clearSystemClipboard() }                         // C2
            it.onClearHistory = { clipboardStore.clearHistory() }
            it.historyEnabledProvider = { historyEnabled() }                               // C1 记录开关
            it.onSetHistoryEnabled = { on -> setHistoryEnabled(on) }
            clipboardView = it
        }
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
            it.onBack = { inputView?.showPanel(null) }
            customSymbolView = it
        }
        panel.refresh()
        iv.showPanel(panel)
    }

    /** D: categorized symbols panel (reached from the keyboard ✎ pencil key). Symbols commit and stay. */
    private fun showSymbolsPanel() {
        val iv = inputView ?: return
        val sv = symbolsView ?: SymbolsView(this).also {
            it.recentProvider = { symbolUsageStore.recent() }
            it.onSymbol = { s -> symbolUsageStore.record(s); currentInputConnection?.commitText(s, 1) }
            it.onBackspace = { currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0) }
            it.onBack = { inputView?.showPanel(null) }
            symbolsView = it
        }
        sv.refresh()
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
            if (clip.itemCount > 0) clipboardStore.record(clip.getItemAt(0).coerceToText(this)?.toString())
        }
    }

    /**
     * 复制条: a system clipboard change while Aegis is active → record it AND raise the
     * taskbar copy-bar with that content (① 复制后). Same C1 privacy gate as [captureClip]. Fires on the
     * main thread (listener registered without a handler), so touching [inputView] here is safe.
     */
    private fun onSystemClipChanged() {
        if (secureField || !historyEnabled()) return
        val t = runCatching {
            clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()?.trim().orEmpty()
        if (t.isEmpty()) return
        clipboardStore.record(t)
        inputView?.showCopyBar(t)
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
