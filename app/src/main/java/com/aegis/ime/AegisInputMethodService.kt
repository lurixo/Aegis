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
    private var customOperatorView: CustomSymbolPanel? = null // I2: numpad operator customization (own panel)
    private val customOperatorStore by lazy { CustomSymbolStore(getSharedPreferences("aegis", MODE_PRIVATE), "custom_operators") }
    // debug.16 items1/2: the 自定义 panels add straight from the symbol keyboard's sets (no clipboard paste).
    // Pinyin column → SymbolCatalog 中文, minus the marks already fixed on the 9-key column. Numpad column →
    // SymbolCatalog 数学, minus the built-in default operators (so a tap can't create a duplicate).
    private val zhSymbolPalette: List<String> by lazy {
        SymbolCatalog.categories.first { it.id == "zh" }.symbols.filter { it !in Layouts.nineFixedPunctuation }
    }
    private val mathOperatorPalette: List<String> by lazy {
        SymbolCatalog.categories.first { it.id == "math" }.symbols.filter { it !in Layouts.defaultNumpadOperators }
    }
    private var selecting = false
    // debug.16 选区扩展: while selecting, the D-pad keeps [selAnchor] fixed and walks [selMoving]; -1 = inactive.
    private var selAnchor = -1
    private var selMoving = -1
    private var deletedSnapshot: CharSequence? = null // for the backspace up/down restore gesture (#5)
    // debug.16 Option A: inline text-input. While [panelInput] is active the keyboard's output is redirected into
    // its buffer (not the target app); on 确定 the buffered text runs the pending [inputPurpose] action.
    private val panelInput = com.aegis.ime.ime.PanelTextInput()
    private enum class InputPurpose { EDIT_PHRASE, ADD_PHRASE, EDIT_NOTE, ADD_CATEGORY, RENAME_CATEGORY }
    private var inputPurpose: InputPurpose? = null
    private var inputCat = "" // EDIT_PHRASE: owning category; RENAME_CATEGORY: (unused)
    private var inputOld = "" // EDIT_PHRASE: the phrase being edited; RENAME_CATEGORY: the old category name
    private var pendingPhraseAdds: List<String> = emptyList() // ADD_CATEGORY via 剪贴板「添加常用语→新建分类」: clips to add once the category exists
    private var pendingMoveFrom = "" // ADD_CATEGORY via「移动到分类→新建分类」: source category of the carried move
    private var pendingMoveTexts: List<String> = emptyList() //  ...and the items to move into the new category
    private val clipboardStore by lazy { ClipboardStore(filesDir).also { it.load() } }
    private val clipImageStore by lazy { com.aegis.ime.user.ClipImageStore(filesDir) } // U22 image clipboard
    private val thumbCache = LruCache<String, Bitmap>(50) // U22: decoded thumbnails (path → bitmap)
    private val symbolUsageStore by lazy { SymbolUsageStore(filesDir).also { it.load() } }
    // E2: emoji MRU ("最近") — its OWN usage file (filesDir/emoji/) so it never mixes with the 符号 常用 list.
    private val emojiUsageStore by lazy { SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).also { it.load() } }
    // C1 privacy: pause clipboard capture while a password / PIN / 2FA field is focused (set per onStartInput).
    @Volatile private var secureField = false
    // U21: the most-recent captured clip — kept so the 复制条 survives an app switch / IME re-show (restored
    // in onStartInputView). Cleared when the user leaves the bar (× or 上屏).
    private var lastCopy: String? = null
    // BUG3: the URI of an image WE just placed on the system clipboard (the commitContent fallback). Our own
    // OnPrimaryClipChangedListener would otherwise re-capture it into a DUPLICATE aegis history entry — skip
    // exactly that one self-write. (The image already lives in aegis; it's the entry the user tapped.)
    @Volatile private var selfClipUri: android.net.Uri? = null
    @Volatile private var userDbLoaded = false // M-2: the initial userdb load has completed
    // debug.16 (engine hot-reload): the downloaded-asset signature the LIVE engine was built from (empty until
    // the initial onCreate build commits it), plus a re-entrancy guard so a rebuild never runs twice at once.
    @Volatile private var engineSig = ""
    @Volatile private var engineReloading = false
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
        customOperatorView?.applyPalette(imePalette)
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
        controller.onShowCustomOperators = { showCustomOperatorPanel() } // I2
        controller.onClosePanel = { inputView?.showPanel(null) }
        controller.setCustomSymbols(customSymbolStore.list()) // A3: seed the punctuation column with saved marks
        controller.setCustomOperators(customOperatorStore.list()) // I2: seed the numpad operator column
        Thread {
            runCatching { userModel.load(userDbFile); userDbMtime = userDbFile.lastModified() }
            userDbLoaded = true // M-2: gate onStartInput's reload until the initial load is done
            val engine = buildEngine() // debug.16: also records engineSig for the hot-reload check
            Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
        }.apply { name = "aegis-dict-load"; isDaemon = true }.start()
    }

    /**
     * Construct the real decode engine from the bundled assets plus any downloaded override packs. HEAVY (dict /
     * lm / .gram parsing) — must be called OFF the main thread (onCreate's load thread and the hot-reload thread
     * share this single construction site so both paths are byte-for-byte identical). [engineSig] is snapshotted
     * BEFORE the loads (so it can never claim to be newer than the data actually read — at worst one redundant
     * reload) and committed only once construction succeeds (a throwing build leaves the old signature so the
     * next onStartInput retries). The SAME [userModel] instance is reused, preserving on-device learning.
     */
    private fun buildEngine(): DictEngine {
        val sig = EngineAssets.signature(File(filesDir, "downloaded"))
        val dict = loadDict("aegis_dict.bin")
        val t9Dict = loadDict("aegis_t9.bin")
        // Per-rule fuzzy (E4): the enabled rule keys drive query-time variant expansion in the decoder, so no
        // separate fuzzy index is loaded. Shared with onStartInputView's hot-toggle via currentFuzzyRules().
        val fuzzyRules = currentFuzzyRules()
        val initialsDict = loadDict("aegis_jianpin.bin")
        val lm = loadLm("aegis_lm.bin")
        // Optional top-tier context model, only if the user downloaded it.
        val octagram = runCatching { OctagramReader.fromDownloads(this, "wanxiang-lts-zh-hans.gram") }
            .onFailure { Log.e("Aegis", "octagram load failed", it) }.getOrNull()
        val engine = DictEngine(dict, t9Dict, lm, userModel, fuzzyRules, initialsDict, octagram)
        engineSig = sig // commit only on success; the pre-load snapshot keeps the invariant sig ≤ loaded data
        return engine
    }

    /**
     * debug.16 (fuzzy hot-toggle): the fuzzy rule-key set selected by the current prefs (master "fuzzy" +
     * each per-rule "fuzzy_<rule>"). Shared by [buildEngine] (so a rebuilt engine carries the current rules)
     * and onStartInputView's hot push, so flipping a 模糊音 toggle takes effect on the next field focus without
     * a cold start or an engine rebuild. The pure selection lives in [Fuzzy.activeRules] (unit-tested).
     */
    private fun currentFuzzyRules(): Set<String> {
        val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
        return Fuzzy.activeRules(prefs.getBoolean("fuzzy", Fuzzy.DEFAULT_ON)) { prefs.getBoolean(Fuzzy.prefKey(it), true) }
    }

    /**
     * debug.16 (engine hot-reload): if a downloaded model/dict pack changed (new download / update / delete)
     * since the live engine was built, rebuild it off the main thread and atomically swap it in via
     * [KeyboardController.setEngine]. The old engine keeps serving input until the new one is ready. No-op
     * until the initial onCreate build has recorded [engineSig], and re-entrancy guarded against overlapping
     * rebuilds. Mirrors the userdb reload in onStartInput; file mtime is the cross-process signal (no IPC).
     */
    private fun maybeReloadEngine() {
        if (engineSig.isEmpty() || engineReloading) return // wait for the initial build; never rebuild twice at once
        // Don't read downloaded/ mid-install: extractDictPack renames the 3 .bin one at a time, so a build during
        // an install could load a mixed old/new set. The dict zip lives until all 3 land, so this is airtight;
        // the next onStartInput (post-install) picks up the complete pack.
        if (com.aegis.ime.dict.ModelDownload.installInProgress(filesDir)) return
        val current = EngineAssets.signature(File(filesDir, "downloaded"))
        if (!EngineAssets.needsReload(engineSig, current)) return
        engineReloading = true
        try {
            Thread {
                runCatching {
                    val engine = buildEngine() // updates engineSig to the snapshot it loaded
                    Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
                }.onFailure { Log.e("Aegis", "engine hot-reload failed", it) }
                engineReloading = false
            }.apply { name = "aegis-dict-reload"; isDaemon = true }.start()
        } catch (t: Throwable) {
            // The thread could not be started (e.g. native-thread exhaustion under memory pressure). Clear the
            // guard so a later onStartInput can retry — never latch hot-reload off for the process lifetime.
            Log.e("Aegis", "engine hot-reload thread start failed", t)
            engineReloading = false
        }
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
        // debug.16: pick up a freshly-downloaded / updated model or dict pack the same way — rebuild the engine
        // off the main thread and hot-swap it in, so a download takes effect on the next field focus (切走再回
        // 来) instead of requiring an IME cold start. No-op when nothing downloaded changed.
        maybeReloadEngine()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        abortInlineInput() // debug.16 red line: tear down any in-progress inline edit when the session ends
        if (userModel.dirty) runCatching {
            userModel.save(userDbFile)
            userDbMtime = userDbFile.lastModified()
        }
    }

    /** Prefer a downloaded enhancement pack (optional full dict / .gram tier) over the bundled asset. */
    private fun downloadedOverride(name: String): File? =
        File(File(filesDir, "downloaded"), name).takeIf { it.exists() && it.length() > 0 }

    // A downloaded override that exists but fails to parse (e.g. a truncated extract) must NOT sink the whole
    // load — fall back to the bundled asset rather than returning null (which would yield zero candidates).
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
            onPickReading = { index -> controller.onPickReadingIndex(index) } // A2 expanded: pick combination
            onFunction = { f -> controller.onBarFunction(f) }
            // C: up-swipe clears the pending pinyin (重输) in any layout first; only if there's nothing
            // composing does it fall back to the editor-field clear/restore (#5).
            onBackspaceSwipe = { up ->
                if (!controller.onBackspaceSwipe(up)) {
                    if (panelInput.active) { if (up) panelInput.begin("") } // inline edit: up-swipe = 重输 the BUFFER, never the app
                    else handleBackspaceSwipe(up)
                }
            }
            onPanelBackspace = { controller.onPanelBackspace() } // A2 expanded: 退格
            onPanelClear = { controller.onPanelClear() }          // A2 expanded: 重输
            onExpandClosed = { controller.clearDrill() }          // UI-2: drop the drilled syllable on close
            onCollapse = { requestHideSelf(0) } // idle toolbar ⌄ collapses the keyboard
            onCopyCommit = { t -> commitLargeText(t) } // 复制条 ⑤: 上屏 (到当前字段; E5: chunked for huge clips)
            onCopyBlock = { b -> copyBlockToAegis(b) }                        // 复制条 ③: 写 aegis 剪贴板(不上屏/不写系统)
            onCopyDismiss = { lastCopy = null }                              // U21: ④/⑤ 离开 → 不再恢复该复制条
            onEditConfirm = { confirmInlineInput() }                         // debug.16: inline edit 确定
            onEditCancel = { cancelInlineInput() }                           // debug.16: inline edit 取消
        }
        inputView = view
        panelInput.onChange = { txt -> view.setEditText(txt) }               // debug.16: mirror buffer → edit bar
        controller.attachView(view)
        imePalette = computePalette()
        view.applyPalette(imePalette) // F1: dynamic Monet colours (dark-aware) come alive here
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        abortInlineInput() // debug.16 red line: a new input session must never inherit an active capture buffer
        inputView?.showPanel(null)
        // U21: restore the most-recent 复制条 across an app switch / IME re-show (reverses the old
        // "start clean" behaviour). VISIBILITY is decoupled from secureField — an already-captured
        // clip is restored in EVERY field type, incl. terminal / visible-password fields like Termius that
        // report textVisiblePassword (which previously hid it); only × dismisses it. secureField still gates
        // CAPTURE of new clips below (onSystemClipChanged / captureClip), the real privacy boundary. ⑤
        // "点内容→上屏" targets the current field (acceptable behaviour). The !! is safe: true ⇒ lc != null.
        val lc = lastCopy
        if (com.aegis.ime.user.ClipboardPolicy.shouldRestoreCopyBar(lc, secureField)) {
            inputView?.showCopyBar(lc!!)
        } else {
            inputView?.hideCopyBar()
        }
        // B5: honour the user's CN default-keyboard choice (9-key unless they picked 26-key); EN stays 26-key.
        val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
        val cnLayout = prefs.getString("cn_layout", "nine")
        controller.setCnDefaultLayout(if (cnLayout == "alpha") LayoutId.ALPHA else LayoutId.NINE)
        // D2: 联想开关 — show learned next-word predictions only when the toggle is on. debug.17: default OFF;
        // the stored pref still wins, so a user who explicitly enabled it keeps it. Single source of truth for
        // the key + default lives in AssociationToggleCard.
        controller.setAssociationsEnabled(
            prefs.getBoolean(com.aegis.ime.ui.PREF_ASSOCIATIONS_ON, com.aegis.ime.ui.ASSOCIATIONS_DEFAULT_ON),
        )
        // debug.16: 模糊音 hot-toggle — re-read the fuzzy prefs each focus and push them to the live engine
        // (query-time variant expansion, no rebuild), so a 模糊音 change takes effect on 切走再回来 without a
        // cold start. The engine hot-reload path (#49) carries the same rules via buildEngine → currentFuzzyRules.
        controller.setFuzzyRules(currentFuzzyRules())
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
        if (iv.isPanelShowing(editPanelView)) { iv.showPanel(null); return } // P4(#4): re-tap 文字编辑 入口 = 返回
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
        // debug.16: during an inline 常用语/分类 edit the buffer is the document — the text-edit panel must never
        // mutate the target app underneath. (BACK still closes the panel.)
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

    /** 开始选择/结束选择 toggle. Entering capture: anchor at the current selection start, moving at its end (so
     *  a pre-existing selection extends from the right end). Leaving: drop the anchor. */
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

    /**
     * D-pad / 段首段尾 navigation. While selecting, EXTEND the selection: keep [selAnchor] fixed, step [selMoving]
     * one unit and `setSelection(anchor, moving)` — the cross-editor-reliable way (an IME's injected shift+DPAD
     * is widely ignored, which is the "开始选择后方向键不选中" bug). Not selecting: the original plain
     * cursor key event, unchanged (keeps the editor's own visual-wrap navigation). Editors that expose no
     * extracted text fall back to a best-effort shift+key.
     */
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
        // Normalise to start<=end: selecting LEFT/UP/HOME drives selMoving below the anchor, and some editors
        // mishandle a reversed setSelection. We keep [selAnchor]/[selMoving] as the directional model ourselves,
        // so the next step still walks the correct moving end.
        ic.setSelection(minOf(selAnchor, selMoving), maxOf(selAnchor, selMoving))
    }

    private fun sendKey(code: Int, shift: Boolean) {
        if (panelInput.active) return // debug.16: never send key events to the target app while inline-editing
        val ic = currentInputConnection ?: return
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** #5: swipe up on backspace clears the whole field (snapshotted); swipe down restores it. */
    private fun handleBackspaceSwipe(up: Boolean) {
        if (panelInput.active) return // debug.16: the swipe-clear must NOT wipe the target field during inline edit
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
        if (iv.isPanelShowing(emojiView)) { iv.showPanel(null); return } // P4(#4): re-tap 表情 入口 = 返回
        val ev = emojiView ?: EmojiView(this).also {
            it.recentProvider = { emojiUsageStore.recent() } // E2: 最近 (MRU) tab
            it.onEmoji = { e -> emojiUsageStore.record(e); commitText(e) } // E2: record usage (debug.16: via gated commitText)
            it.onBackspace = { panelBackspace() } // F2: selection-aware (else eats the char before a selection)
            it.onBack = { inputView?.showPanel(null) }
            emojiView = it
        }
        ev.resetToDefault() // P7(#19): open on the first category. Belt-and-suspenders to the exit-reset:
                            // panels are service-scoped, so an input-view recreate leaves the dismissed
                            // singleton with no outgoing InputView to reset — resetting here covers that.
        ev.applyPalette(imePalette)
        iv.showPanel(ev)
    }

    /** Show the clipboard / canned-phrases panel; tapping an entry commits it. */
    private fun showClipboardPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(clipboardView)) { iv.showPanel(null); return } // P4(#4): re-tap 剪贴板 入口 = 返回
        captureClip() // read the current clip only on explicit user intent (avoids spurious access)
        val cv = clipboardView ?: ClipboardView(this).also {
            it.historyProvider = { clipboardStore.history() }
            it.categoriesProvider = { clipboardStore.categories() }                       // C5 分类
            it.phrasesInProvider = { cat -> clipboardStore.phrasesIn(cat) }
            it.phraseNoteProvider = { cat, text -> clipboardStore.noteFor(cat, text) } // debug.17 F2: display note
            it.onPick = { t -> commitLargeText(t); inputView?.showPanel(null) } // E5: chunked for huge clips
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
            it.onEditPhrase = { cat, text -> beginInlineEdit(cat, text) }                  // debug.16 Option A: 编辑 → inline buffer
            it.onMovePhrase = { from, text, to -> clipboardStore.movePhrase(from, text, to) } // debug.16: 移动常用语分类
            it.onMovePhrasesTo = { from, list, to -> clipboardStore.movePhrasesTo(from, list, to) } // debug.16: 批量移动
            it.onReorderPhrase = { cat, fromIdx, toIdx -> clipboardStore.reorderPhrase(cat, fromIdx, toIdx) } // debug.16: 拖动重排
            it.onAddPhrase = { cat -> beginInlineAddPhrase(cat) }                         // debug.17: 顶部 ＋ → 当前分类内联新增常用语
            it.onAddCategory = { beginInlineAddCategory() }                               // debug.16/17: ＋分类(now ✎二级菜单) → inline buffer
            it.onAddCategoryThenAdd = { texts -> beginInlineAddCategory(texts) }          // debug.16: 剪贴板 添加常用语→新建分类 (carry clips)
            it.onAddCategoryThenMove = { from, texts -> beginInlineAddCategory(pendingMove = from to texts) } // debug.16: 移动到分类→新建分类 (carry move)
            it.onRenameCategory = { old -> beginInlineRenameCategory(old) }               // debug.16: 分类改名 → inline buffer
            it.onDeleteCategory = { name -> clipboardStore.deleteCategory(name) }         // debug.16: 删除分类 (no typing)
            it.onEditNote = { cat, text -> beginInlineEditNote(cat, text) }               // debug.17 F2: 备注 → inline buffer
            it.onClearCategory = { cat -> clipboardStore.clearPhrasesIn(cat) }            // debug.17 E2: 清空当前分类
            it.onExportPhrases = { launchPhraseTransfer(export = true) }                  // debug.17 E1: SAF 导出
            it.onImportPhrases = { launchPhraseTransfer(export = false) }                 // debug.17 E1: SAF 导入
            it.onClearHistory = { clipboardStore.clearHistory(); clipImageStore.clear(); thumbCache.evictAll() }
            it.historyEnabledProvider = { historyEnabled() }                               // C1 记录开关
            it.onSetHistoryEnabled = { on -> setHistoryEnabled(on) }
            clipboardView = it
        }
        cv.resetToDefault() // P7(#19): always open on the 剪贴板 tab, normal mode (also recreation-proof — see
                            // showEmojiPanel). Reset BEFORE applyPalette so its refresh() builds the clean state.
        cv.applyPalette(imePalette)
        clipboardStore.reloadPhrases() // pick up category/phrase edits made in the manager Activity
        cv.refresh()
        iv.showPanel(cv)
    }

    /** A3 自定义: edit the user's custom punctuation marks; changes persist + refresh the 9-key column live. */
    private fun showCustomSymbolPanel() {
        val iv = inputView ?: return
        val panel = customSymbolView ?: CustomSymbolPanel(this).also {
            it.addPalette = zhSymbolPalette // debug.16 item1: tap-add from 符号键盘 中文 (no clipboard paste)
            it.current = { customSymbolStore.list() }
            it.onAdd = { s -> customSymbolStore.add(s); controller.setCustomSymbols(customSymbolStore.list()); it.refresh() }
            it.onRemove = { s -> customSymbolStore.remove(s); controller.setCustomSymbols(customSymbolStore.list()); it.refresh() }
            it.onBack = { inputView?.showPanel(null) }
            customSymbolView = it
        }
        panel.resetToDefault() // P7(#19): open scrolled to the top (also recreation-proof — see showEmojiPanel)
        panel.applyPalette(imePalette) // also calls refresh() — no separate refresh needed
        iv.showPanel(panel)
    }

    /** I2 自定义: edit the user's custom numpad operators; changes persist + refresh the operator column live. */
    private fun showCustomOperatorPanel() {
        val iv = inputView ?: return
        val panel = customOperatorView ?: CustomSymbolPanel(this).also {
            it.backTitle = "‹ 自定义运算符"
            it.addPalette = mathOperatorPalette // debug.16 item2: tap-add from 符号键盘 数学 (no clipboard paste)
            it.current = { customOperatorStore.list() }
            it.onAdd = { s -> customOperatorStore.add(s); controller.setCustomOperators(customOperatorStore.list()); it.refresh() }
            it.onRemove = { s -> customOperatorStore.remove(s); controller.setCustomOperators(customOperatorStore.list()); it.refresh() }
            it.onBack = { inputView?.showPanel(null) }
            customOperatorView = it
        }
        panel.applyPalette(imePalette)
        iv.showPanel(panel)
    }

    /** D: categorized symbols panel (reached from the keyboard ✎ pencil key). U3: a tap 上屏s + closes the panel. */
    private fun showSymbolsPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(symbolsView)) { iv.showPanel(null); return } // P4(#4): re-tap 符号 入口 = 返回
        val sv = symbolsView ?: SymbolsView(this).also {
            it.recentProvider = { symbolUsageStore.recent() }
            // U3/P3: 点符号 = 上屏 + 记入常用;是否回键盘由 SymbolsView 的锁定态决定(锁定则连续输入)。
            it.onSymbol = { s -> symbolUsageStore.record(s); commitText(s) } // debug.16: via gated commitText
            it.onBackspace = { panelBackspace() } // F2: selection-aware (else eats the char before a selection)
            it.onBack = { inputView?.showPanel(null) }
            symbolsView = it
        }
        sv.resetToDefault() // P3/P7(#19): always (re)open unlocked on the 常用 tab (also recreation-proof — see
                            // showEmojiPanel). Reset BEFORE applyPalette so it re-renders the default 常用 grid.
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

    /** debug.17 E1: launch the SAF bridge Activity to export / import the 常用语 library (the IME service is not
     *  an Activity, so SAF document pickers must run in a real Activity). It reads/writes the SAME ClipboardStore
     *  files in filesDir; the panel re-reads phrases when it next opens. */
    private fun launchPhraseTransfer(export: Boolean) {
        runCatching {
            startActivity(
                android.content.Intent(this, com.aegis.ime.ui.PhraseTransferActivity::class.java)
                    .putExtra(com.aegis.ime.ui.PhraseTransferActivity.EXTRA_EXPORT, export)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            inputView?.showPanel(null) // close the panel so the picker is unobstructed
        }
    }

    // --- debug.16 Option A: inline text input (edit a 常用语 / add+rename a category) ---

    private fun beginInlineEdit(category: String, phrase: String) {
        inputPurpose = InputPurpose.EDIT_PHRASE; inputCat = category; inputOld = phrase
        startInlineInput("编辑常用语", phrase)
    }

    /** debug.17: 顶部 ＋(常用语tab) — add a NEW phrase under [category] via the same Option A inline buffer. */
    private fun beginInlineAddPhrase(category: String) {
        inputPurpose = InputPurpose.ADD_PHRASE; inputCat = category; inputOld = ""
        startInlineInput("添加常用语", "")
    }

    /** debug.17 F2: edit the display 备注 for phrase [text] in [category] — pre-filled with the current note; an
     *  empty buffer clears it. Reuses the same Option A gated buffer (never reaches the target app). */
    private fun beginInlineEditNote(category: String, text: String) {
        inputPurpose = InputPurpose.EDIT_NOTE; inputCat = category; inputOld = text
        startInlineInput("备注", clipboardStore.noteFor(category, text))
    }

    private fun beginInlineAddCategory(
        pendingAdds: List<String> = emptyList(),
        pendingMove: Pair<String, List<String>>? = null,
    ) {
        inputPurpose = InputPurpose.ADD_CATEGORY; inputCat = ""; inputOld = ""
        pendingPhraseAdds = pendingAdds                    // 添加常用语→新建分类: clip(s) to add once created
        pendingMoveFrom = pendingMove?.first ?: ""         // 移动到分类→新建分类: carry the move through the create
        pendingMoveTexts = pendingMove?.second ?: emptyList()
        startInlineInput("新建分类", "")
    }

    private fun beginInlineRenameCategory(old: String) {
        inputPurpose = InputPurpose.RENAME_CATEGORY; inputCat = ""; inputOld = old
        startInlineInput("重命名分类", old)
    }

    /** Close the clipboard panel (so the keyboard shows), redirect keyboard output into the buffer, raise the bar. */
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
            InputPurpose.ADD_PHRASE -> { val t = text.trim(); if (t.isNotEmpty()) clipboardStore.addPhrasesTo(inputCat, listOf(t)) } // inputCat stays → reopen there
            InputPurpose.EDIT_NOTE -> clipboardStore.setPhraseNote(inputCat, inputOld, text) // F2: empty buffer clears the note
            InputPurpose.ADD_CATEGORY -> {
                val name = text.trim()
                if (name.isNotEmpty()) {
                    clipboardStore.addCategory(name) // creates it (no-op if it already exists)
                    // 剪贴板「添加常用语→新建分类」: the carried clip(s) now land in the just-created category.
                    if (pendingPhraseAdds.isNotEmpty()) clipboardStore.addPhrasesTo(name, pendingPhraseAdds)
                    // 剪贴板「移动到分类→新建分类」: move the carried item(s) into the just-created category.
                    if (pendingMoveTexts.isNotEmpty()) clipboardStore.movePhrasesTo(pendingMoveFrom, pendingMoveTexts, name)
                    inputCat = name // reopen the 常用语 tab on the new category
                }
            }
            InputPurpose.RENAME_CATEGORY -> { val n = text.trim(); if (clipboardStore.renameCategory(inputOld, n)) inputCat = n }
            null -> {}
        }
        endInlineInput()
    }

    private fun cancelInlineInput() = endInlineInput()

    /** Stop the redirect (keyboard output returns to the target app), hide the bar, reopen the 常用语 panel. */
    private fun endInlineInput() {
        val reopenCat = inputCat // EDIT_PHRASE/ADD/RENAME: the category to land back on
        panelInput.end()
        inputView?.showEditBar(false)
        inputPurpose = null; inputCat = ""; inputOld = ""; pendingPhraseAdds = emptyList(); pendingMoveFrom = ""; pendingMoveTexts = emptyList()
        showClipboardPanel()                       // reopen + reloadPhrases + refresh (lands on the 剪贴板 tab)
        clipboardView?.showPhraseTab(reopenCat)    // ...then switch to the 常用语 tab the user was editing on
    }

    /** Tear down an in-progress inline edit WITHOUT reopening any panel — for IME teardown / a new input
     *  session, so an interrupted edit can never leave keystrokes redirected away from the target app. */
    private fun abortInlineInput() {
        if (!panelInput.active && inputPurpose == null) return
        panelInput.end()
        inputView?.showEditBar(false)
        inputPurpose = null; inputCat = ""; inputOld = ""; pendingPhraseAdds = emptyList(); pendingMoveFrom = ""; pendingMoveTexts = emptyList()
    }

    private fun captureClip() {
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return // debug.17: secure fields ARE captured now; only the history switch gates
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
        // BUG3-1: restore the debug.13 short-circuit — don't even read the system clipboard when capture is
        // paused (history off) AND there's no pending self-write to consume. debug.17: secureField is passed as
        // FALSE here (policy change) so secure/password-field copies ARE captured; only history-off
        // (+ no pending self-write) still short-circuits the getPrimaryClip IPC.
        if (!com.aegis.ime.user.ClipboardPolicy.shouldReadSystemClip(selfClipUri != null, false, historyEnabled())) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        // BUG3: consume our own image fallback write BEFORE the capture gate, so the guard is reset even when
        // capture is paused (secure field / history off) and can never go stale to suppress a later clip.
        if (com.aegis.ime.user.ClipImageStore.isSelfWrite(uri, selfClipUri)) { selfClipUri = null; return }
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return // debug.17: secure fields recorded too
        val declaredImage = clip.description?.hasMimeType("image/*") == true
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
     * U22 / BUG3: deliver a saved clipboard image to the target field. PREFERRED path = commitContent (rich
     * content) when the field advertises a compatible image type via EditorInfoCompat.getContentMimeTypes,
     * granting a temporary read on our FileProvider URI. FALLBACK (BUG3) = when the field does NOT support
     * commitContent (no advertised image type) or the commit fails, the image is placed on the SYSTEM
     * clipboard so the user can long-press → paste (works wherever system paste does). Never crashes.
     */
    private fun pasteImage(path: String) {
        if (panelInput.active) return // debug.16: an image can't go into the text buffer — ignore during inline edit
        val file = File(path)
        if (!file.exists()) { toast("图片已不存在"); return }
        val mime = com.aegis.ime.user.ClipImageStore.mimeOf(path)
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrNull()
        if (uri == null) { toast("插入失败"); return }

        // 1) Preferred: rich-content commitContent into a field that advertises image support (BUG3 ①②③④ —
        // verified correct). deliverImage only runs the commit when the field accepts it, so a field that does
        // NOT advertise contentMimeTypes (subcase A) skips straight to the fallback below; a commit that
        // returns false (subcase B) does too.
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
        if (committed) { inputView?.showPanel(null); return } // inserted directly — silent, no toast

        // 2) BUG3 fallback: the field doesn't support commitContent (or it failed) — put the image on the
        // SYSTEM clipboard so the user can long-press → paste (which works wherever system paste does). The
        // ClipboardService grants read on our FileProvider URI to the pasting app.
        selfClipUri = uri // BUG3: mark this write so our own clip listener doesn't re-record it
        val copied = runCatching {
            clipboardManager.setPrimaryClip(com.aegis.ime.user.ClipImageStore.imageClip(contentResolver, uri))
        }.isSuccess
        toast(if (copied) "图片已复制,请在输入框长按粘贴" else "插入失败")
        inputView?.showPanel(null)
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
    // debug.16: 清空系统剪贴板 removed — clearPrimaryClip is silently ignored by some OEM clipboards
    // (Samsung/Vivo), so the action couldn't be made reliable; only 清空剪贴板历史 (aegis history) remains.

    override fun onDestroy() {
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) }
        super.onDestroy()
    }

    // --- ImeHost ---

    // debug.16 Option A: every ImeHost output checks [panelInput] FIRST. While inline-editing it consumes the
    // output into the buffer and returns; otherwise (the normal case) it falls through to the editor UNCHANGED.
    override fun commitText(text: CharSequence) {
        if (panelInput.commit(text)) return
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * E5: commit a clipboard entry that may be huge (million-char paste) in binder-safe chunks — a single
     * commitText over ~1 MB throws TransactionTooLargeException. Used by the 复制条 ⑤ and a 剪贴板 entry tap.
     * (A third-party editor may still refuse an oversized paste of its own accord — that's outside the IME.)
     */
    private fun commitLargeText(text: CharSequence) {
        if (panelInput.commit(text)) return // debug.16: a clip tapped during inline edit goes into the buffer, not the app
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        com.aegis.ime.ime.LargeCommit.commit(text) { ic.commitText(it, 1) }
        ic.endBatchEdit()
    }

    override fun deleteBackward() {
        if (panelInput.backspace()) return
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    // F2: code-point-aware backspace so a multi-code-point emoji deletes whole (used by the panels' ⌫).
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
        // S2: commitText("") over a live selection replaces (deletes) exactly the selected span — unlike
        // deleteSurroundingText(1,0), which is selection-start-relative and would eat the char before it.
        if (panelInput.backspace()) return
        currentInputConnection?.commitText("", 1)
    }

    override fun performEnter() {
        if (panelInput.active) return // inline-editing: a single-line buffer ignores ENTER (no newline leak)
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
