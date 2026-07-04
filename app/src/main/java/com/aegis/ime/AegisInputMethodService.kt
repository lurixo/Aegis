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
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
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
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.SelectionMath
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.CustomSymbolStore
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
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
    // Chinese IME behavior note.
    // Chinese IME behavior note.
    // Chinese IME behavior note.
    private val zhSymbolPalette: List<String> by lazy {
        SymbolCatalog.categories.first { it.id == "zh" }.symbols.filter { it !in Layouts.nineFixedPunctuation }
    }
    private val mathOperatorPalette: List<String> by lazy {
        SymbolCatalog.categories.first { it.id == "math" }.symbols.filter { it !in Layouts.defaultNumpadOperators }
    }
    private var selecting = false
    // Chinese IME behavior note.
    private var selAnchor = -1
    private var selMoving = -1
    private var deletedSnapshot: CharSequence? = null // for the backspace up/down restore gesture (#5)
    // debug.16 Option A: inline text-input. While [panelInput] is active the keyboard's output is redirected into
    // Chinese IME behavior note.
    private val panelInput = com.aegis.ime.ime.PanelTextInput()
    private enum class InputPurpose { EDIT_PHRASE, ADD_PHRASE, EDIT_NOTE, ADD_CATEGORY, RENAME_CATEGORY }
    private var inputPurpose: InputPurpose? = null
    private var inputCat = "" // EDIT_PHRASE: owning category; RENAME_CATEGORY: (unused)
    private var inputOld = "" // EDIT_PHRASE: the phrase being edited; RENAME_CATEGORY: the old category name
    private var pendingPhraseAdds: List<String> = emptyList() // Chinese IME behavior note.
    private var pendingMoveFrom = "" // Chinese IME behavior note.
    private var pendingMoveTexts: List<String> = emptyList() //  ...and the items to move into the new category
    private val clipboardStore by lazy { ClipboardStore(filesDir).also { it.load() } }
    private val symbolUsageStore by lazy { SymbolUsageStore(filesDir).also { it.load() } }
    // Chinese IME behavior note.
    private val emojiUsageStore by lazy { SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).also { it.load() } }
    // C1 privacy: pause clipboard capture while a password / PIN / 2FA field is focused (set per onStartInput).
    @Volatile private var secureField = false
    // Chinese IME behavior note.
    // Chinese IME behavior note.
    private var lastCopy: String? = null
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

    // ③ → debug.47: EVERY settings change hot-applies to the RUNNING IME the moment it lands — never "wait
    // for the next field focus". SettingsHotApply maps each pref key to its live action (CN layout,
    // predictions, fuzzy rules, downloaded-pack changes); the user dictionary hot path is UserDictHot below.
    // Callbacks hop to the main thread and guard on controller initialization, like the two per-key
    // listeners this replaces.
    private val settingsHotApply = SettingsHotApply(
        onCnLayout = { id ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setCnDefaultLayout(id)
            }
        },
        onAssociations = { on ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setAssociationsEnabled(on)
            }
        },
        onFuzzyRules = { rules ->
            Handler(Looper.getMainLooper()).post {
                if (::controller.isInitialized) controller.setFuzzyRules(rules)
            }
        },
        onEngineAssetsChanged = {
            Handler(Looper.getMainLooper()).post { maybeReloadEngine() }
        },
    )

    // debug.47: live user-dictionary host for the settings page — same process, same UserModel instance the
    // decoder reads, so an add/delete/import is visible on the next keystroke and dirty (unsaved learning)
    // merges instead of being lost. Registered only after the initial userdb load (else a save could wipe
    // the not-yet-loaded learning); the mtime callback keeps onStartInput's reload watermark current.
    private val liveUserDictHost by lazy {
        LiveUserDictHost(userModel, userDbFile) { userDbMtime = it }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipChangedListener) }
        runCatching {
            getSharedPreferences("aegis", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(settingsHotApply)
        } // ③/debug.47: one listener for every hot-applied setting
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
            UserDictHot.host = liveUserDictHost // debug.47: settings edits now go into the LIVE model
            val engine = buildEngine() // debug.16: also records engineSig for the hot-reload check
            Handler(Looper.getMainLooper()).post {
                controller.setEngine(engine)
                // debug.47: a pack downloaded/deleted while the initial build ran fired the pref listener
                // into the engineSig-empty no-op — re-check once now that the signature is committed.
                maybeReloadEngine()
            }
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
      * Chinese IME behavior note.
     * a cold start or an engine rebuild. Single-sourced with the hot-apply listener via
     * [SettingsHotApply.fuzzyRules]; the pure selection lives in [Fuzzy.activeRules] (unit-tested).
     */
    private fun currentFuzzyRules(): Set<String> =
        SettingsHotApply.fuzzyRules(getSharedPreferences("aegis", MODE_PRIVATE))

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
                val ok = runCatching {
                    val engine = buildEngine() // updates engineSig to the snapshot it loaded
                    Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
                }.onFailure { Log.e("Aegis", "engine hot-reload failed", it) }.isSuccess
                engineReloading = false
                // debug.47: a pack change landing while this rebuild ran was swallowed by the re-entrancy
                // guard above — re-check once after a successful swap (signature equality makes it a no-op
                // when nothing else changed). A FAILED build keeps the old signature and retries on the next
                // onStartInput, as before — no tight retry loop on a persistently unreadable pack.
                if (ok) Handler(Looper.getMainLooper()).post { maybeReloadEngine() }
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
        // Chinese IME behavior note.
        // Chinese IME behavior note.
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
        EngineAssets.downloadedOverride(File(filesDir, "downloaded"), name)

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
            // Chinese IME behavior note.
            // composing does it fall back to the editor-field clear/restore (#5).
            onBackspaceSwipe = { up ->
                if (!controller.onBackspaceSwipe(up)) {
                    if (panelInput.active) { if (up) panelInput.begin("") } // Chinese IME behavior note.
                    else handleBackspaceSwipe(up)
                }
            }
            onPanelBackspace = { controller.onPanelBackspace() } // Chinese IME behavior note.
            onPanelClear = { controller.onPanelClear() } // Chinese IME behavior note.
            onExpandClosed = { controller.clearDrill() }          // UI-2: drop the drilled syllable on close
            onCollapse = { requestHideSelf(0) } // idle toolbar ⌄ collapses the keyboard
            onCopyCommit = { t -> commitLargeText(t) } // Chinese IME behavior note.
            onCopyBlock = { b ->
                controller.expireCandidateChoiceUndo()
                copyBlockToAegis(b)
            } // Chinese IME behavior note.
            onCopyDismiss = {
                controller.expireCandidateChoiceUndo()
                lastCopy = null
            } // Chinese IME behavior note.
            onEditConfirm = { confirmInlineInput() } // Chinese IME behavior note.
            onEditCancel = { cancelInlineInput() } // Chinese IME behavior note.
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
        // Chinese IME behavior note.
        // "start clean" behaviour). VISIBILITY is decoupled from secureField — an already-captured
        // clip is restored in EVERY field type, incl. terminal / visible-password fields like Termius that
        // report textVisiblePassword (which previously hid it); only × dismisses it. secureField still gates
        // CAPTURE of new clips below (onSystemClipChanged / captureClip), the real privacy boundary. ⑤
        // Chinese IME behavior note.
        val lc = lastCopy
        if (com.aegis.ime.user.ClipboardPolicy.shouldRestoreCopyBar(lc, secureField)) {
            inputView?.showCopyBar(lc!!)
        } else {
            inputView?.hideCopyBar()
        }
        // B5: honour the user's CN default-keyboard choice (9-key unless they picked 26-key); EN stays 26-key.
        // debug.47: read through the same SettingsHotApply resolvers the hot-apply listener uses, so the
        // "belt" (this re-read on focus) and the "suspenders" (the live listener) can never disagree.
        val prefs = getSharedPreferences("aegis", MODE_PRIVATE)
        controller.setCnDefaultLayout(SettingsHotApply.cnLayout(prefs))
        // Chinese IME behavior note.
        // the stored pref still wins, so a user who explicitly enabled it keeps it. Single source of truth for
        // the key + default lives in AssociationToggleCard.
        controller.setAssociationsEnabled(SettingsHotApply.associationsOn(prefs))
        // Chinese IME behavior note.
        // Chinese IME behavior note.
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
        if (iv.isPanelShowing(editPanelView)) { iv.showPanel(null); return } // Chinese IME behavior note.
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
        // Chinese IME behavior note.
        // mutate the target app underneath. (BACK still closes the panel.)
        if (panelInput.active && action != EditAction.BACK) return
        controller.expireCandidateChoiceUndo()
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

    /**
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
      * Chinese IME behavior note.
     * one unit and `setSelection(anchor, moving)` — the cross-editor-reliable way (an IME's injected shift+DPAD
      * Chinese IME behavior note.
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
        controller.expireCandidateChoiceUndo()
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
        if (iv.isPanelShowing(emojiView)) { iv.showPanel(null); return } // Chinese IME behavior note.
        val ev = emojiView ?: EmojiView(this).also {
            it.recentProvider = { emojiUsageStore.recent() } // Chinese IME behavior note.
            it.onEmoji = { e -> emojiUsageStore.record(e); commitExternalText(e) } // E2: record usage (debug.16: via gated commitText)
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
        if (iv.isPanelShowing(clipboardView)) { iv.showPanel(null); return } // Chinese IME behavior note.
        captureClip() // read the current clip only on explicit user intent (avoids spurious access)
        val cv = clipboardView ?: ClipboardView(this).also {
            it.historyProvider = { clipboardStore.history() }
            it.categoriesProvider = { clipboardStore.categories() } // Chinese IME behavior note.
            it.phrasesInProvider = { cat -> clipboardStore.phrasesIn(cat) }
            it.phraseNoteProvider = { cat, text -> clipboardStore.noteFor(cat, text) } // debug.17 F2: display note
            it.onPick = { t -> commitLargeText(t); inputView?.showPanel(null) } // E5: chunked for huge clips
            it.onCopyBlockToAegis = { b -> copyBlockToAegis(b) } // Chinese IME behavior note.
            it.onCopyBlocksToAegis = { blocks -> copyBlocksToAegis(blocks) }
            it.onBack = { inputView?.showPanel(null) }
            it.onDeleteClips = { list -> clipboardStore.deleteAll(list) } // Chinese IME behavior note.
            it.onDeletePhrasesFrom = { cat, list -> list.forEach { clipboardStore.deletePhraseFrom(cat, it) } }
            it.onSaveAsPhrasesTo = { cat, list ->
                val added = clipboardStore.addPhrasesTo(cat, list)
                if (list.size == 1) toast(if (added > 0) "常用语已添加成功" else "常用语已存在")
            } // C7 batch add phrases
            it.onEditPhrase = { cat, text -> beginInlineEdit(cat, text) } // Chinese IME behavior note.
            it.onMovePhrase = { from, text, to -> clipboardStore.movePhrase(from, text, to) } // Chinese IME behavior note.
            it.onMovePhrasesTo = { from, list, to -> clipboardStore.movePhrasesTo(from, list, to) } // Chinese IME behavior note.
            it.onReorderPhrase = { cat, fromIdx, toIdx -> clipboardStore.reorderPhrase(cat, fromIdx, toIdx) } // Chinese IME behavior note.
            it.onReorderCategory = { fromIdx, toIdx -> clipboardStore.reorderCategory(fromIdx, toIdx) }
            it.onAddPhrase = { cat -> beginInlineAddPhrase(cat) } // Chinese IME behavior note.
            it.onAddCategory = { beginInlineAddCategory() } // Chinese IME behavior note.
            it.onAddCategoryThenAdd = { texts -> beginInlineAddCategory(texts) } // Chinese IME behavior note.
            it.onAddCategoryThenMove = { from, texts -> beginInlineAddCategory(pendingMove = from to texts) } // Chinese IME behavior note.
            it.onRenameCategory = { old -> beginInlineRenameCategory(old) } // Chinese IME behavior note.
            it.onDeleteCategory = { name -> clipboardStore.deleteCategory(name) } // Chinese IME behavior note.
            it.onEditNote = { cat, text -> beginInlineEditNote(cat, text) } // Chinese IME behavior note.
            it.onClearCategory = { cat -> clipboardStore.clearPhrasesIn(cat) } // Chinese IME behavior note.
            it.onExportPhrases = { launchPhraseTransfer(export = true) } // Chinese IME behavior note.
            it.onImportPhrasesWithMode = { merge -> launchPhraseTransfer(export = false, merge = merge) } // debug.17 E1: SAF import
            it.onClearHistory = { clipboardStore.clearHistory() }
            it.historyEnabledProvider = { historyEnabled() } // Chinese IME behavior note.
            it.onSetHistoryEnabled = { on -> setHistoryEnabled(on) }
            clipboardView = it
        }
        cv.resetToDefault() // Chinese IME behavior note.
                            // showEmojiPanel). Reset BEFORE applyPalette so its refresh() builds the clean state.
        clipboardStore.reloadPhrases() // pick up category/phrase edits made in the manager Activity
        cv.applyPalette(imePalette)
        iv.showPanel(cv)
    }

    /** Chinese IME behavior note. */
    private fun showCustomSymbolPanel() {
        val iv = inputView ?: return
        val panel = customSymbolView ?: CustomSymbolPanel(this).also {
            it.addPalette = zhSymbolPalette // Chinese IME behavior note.
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

    /** Chinese IME behavior note. */
    private fun showCustomOperatorPanel() {
        val iv = inputView ?: return
        val panel = customOperatorView ?: CustomSymbolPanel(this).also {
            it.backTitle = "‹ 自定义运算符"
            it.addPalette = mathOperatorPalette // Chinese IME behavior note.
            it.current = { customOperatorStore.list() }
            it.onAdd = { s -> customOperatorStore.add(s); controller.setCustomOperators(customOperatorStore.list()); it.refresh() }
            it.onRemove = { s -> customOperatorStore.remove(s); controller.setCustomOperators(customOperatorStore.list()); it.refresh() }
            it.onBack = { inputView?.showPanel(null) }
            customOperatorView = it
        }
        panel.applyPalette(imePalette)
        iv.showPanel(panel)
    }

    /** Chinese IME behavior note. */
    private fun showSymbolsPanel() {
        val iv = inputView ?: return
        if (iv.isPanelShowing(symbolsView)) { iv.showPanel(null); return } // Chinese IME behavior note.
        val sv = symbolsView ?: SymbolsView(this).also {
            it.recentProvider = { symbolUsageStore.recent() }
            it.recentOriginOf = { s -> symbolUsageStore.originOf(s) }
            // Record the tap with the category it came from, then commit through the gated symbol path.
            it.onSymbol = { s, origin -> symbolUsageStore.record(s, origin); commitExternalSymbol(s) }
            it.onBackspace = { panelBackspace() } // F2: selection-aware (else eats the char before a selection)
            it.onBack = { inputView?.showPanel(null) }
            symbolsView = it
        }
        sv.resetToDefault() // Chinese IME behavior note.
                            // Chinese IME behavior note.
        sv.applyPalette(imePalette) // Chinese IME behavior note.
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

    /** debug.17 E1: launch the SAF bridge Activity to export / import the phrase library (the IME service is not
     *  an Activity, so SAF document pickers must run in a real Activity). It reads/writes the SAME ClipboardStore
     *  files in filesDir; the panel re-reads phrases when it next opens. */
    private fun launchPhraseTransfer(export: Boolean, merge: Boolean = true) {
        runCatching {
            startActivity(
                com.aegis.ime.ui.PhraseTransferActivity.launchIntent(this, export, merge),
            )
            inputView?.showPanel(null) // close the panel so the picker is unobstructed
        }
    }

    // Chinese IME behavior note.

    private fun beginInlineEdit(category: String, phrase: String) {
        inputPurpose = InputPurpose.EDIT_PHRASE; inputCat = category; inputOld = phrase
        startInlineInput("编辑常用语", phrase)
    }

    /** Chinese IME behavior note. */
    private fun beginInlineAddPhrase(category: String) {
        inputPurpose = InputPurpose.ADD_PHRASE; inputCat = category; inputOld = ""
        startInlineInput("添加常用语", "")
    }

    /**
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
        pendingPhraseAdds = pendingAdds // Chinese IME behavior note.
        pendingMoveFrom = pendingMove?.first ?: "" // Chinese IME behavior note.
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
            InputPurpose.ADD_PHRASE -> { val t = text.trim(); if (t.isNotEmpty()) addSinglePhraseWithToast(inputCat, t) } // inputCat stays → reopen there
            InputPurpose.EDIT_NOTE -> clipboardStore.setPhraseNote(inputCat, inputOld, text) // F2: empty buffer clears the note
            InputPurpose.ADD_CATEGORY -> {
                val name = text.trim()
                if (name.isNotEmpty()) {
                    clipboardStore.addCategory(name) // creates it (no-op if it already exists)
                    // Chinese IME behavior note.
                    if (pendingPhraseAdds.isNotEmpty()) clipboardStore.addPhrasesTo(name, pendingPhraseAdds)
                    // Chinese IME behavior note.
                    if (pendingMoveTexts.isNotEmpty()) clipboardStore.movePhrasesTo(pendingMoveFrom, pendingMoveTexts, name)
                    inputCat = name // Chinese IME behavior note.
                }
            }
            InputPurpose.RENAME_CATEGORY -> { val n = text.trim(); if (clipboardStore.renameCategory(inputOld, n)) inputCat = n }
            null -> {}
        }
        endInlineInput()
    }

    private fun addSinglePhraseWithToast(category: String, text: String) {
        val added = clipboardStore.addPhrasesTo(category, listOf(text))
        toast(if (added > 0) "常用语已添加成功" else "常用语已存在")
    }

    private fun cancelInlineInput() = endInlineInput()

    /** Chinese IME behavior note. */
    private fun endInlineInput() {
        val reopenCat = inputCat // EDIT_PHRASE/ADD/RENAME: the category to land back on
        panelInput.end()
        inputView?.showEditBar(false)
        inputPurpose = null; inputCat = ""; inputOld = ""; pendingPhraseAdds = emptyList(); pendingMoveFrom = ""; pendingMoveTexts = emptyList()
        showClipboardPanel() // Chinese IME behavior note.
        clipboardView?.showPhraseTab(reopenCat) // Chinese IME behavior note.
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
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     * main thread (listener registered without a handler), so touching [inputView] here is safe.
     */
    private fun onSystemClipChanged() {
        // BUG3-1: restore the debug.13 short-circuit — don't even read the system clipboard when capture is
        // paused (history off). debug.17: secureField is passed as FALSE here (policy change) so
        // secure/password-field copies ARE captured; only history-off short-circuits the getPrimaryClip IPC.
        if (!com.aegis.ime.user.ClipboardPolicy.shouldReadSystemClip(false, historyEnabled())) return
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        if (!com.aegis.ime.user.ClipboardStore.shouldCapture(historyEnabled())) return // debug.17: secure fields recorded too
        // A2 (mirrors captureClip): never record a URI-only item as a bogus "content://…" text entry. U22 removed
        // → image/URI-only clips are simply ignored (no image save). When the item DOES carry explicit text,
        // coerceToText returns it WITHOUT resolving the URI, so this stays on the main thread with no IPC/jank.
        if (item.uri != null && item.text == null) return
        val t = runCatching { item.coerceToText(this)?.toString() }.getOrNull()?.trim().orEmpty()
        if (t.isNotEmpty()) recordTextClip(t)
    }

    /** Record a captured text clip + raise the copy-bar (unless composing). Shared by the text + URI paths. */
    private fun recordTextClip(t: String) {
        clipboardStore.record(t)
        refreshOpenClipboardPanel()
        lastCopy = t // U21: remember it so it survives an app switch / IME re-show
        if (inputView?.isComposing() != true) inputView?.showCopyBar(t) // don't clobber live candidates
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
      * Chinese IME behavior note.
     * editor) and confirm with a light toast so the tap doesn't feel like a no-op. Shared by the copy-bar
      * Chinese IME behavior note.
     */
    private fun copyBlockToAegis(block: String) {
        copyBlocksToAegis(listOf(block))
    }

    private fun copyBlocksToAegis(blocks: List<String>) {
        if (blocks.isEmpty()) return
        for (block in blocks) clipboardStore.record(block)
        refreshOpenClipboardPanel()
        toast("已存入剪贴板")
    }

    private fun refreshOpenClipboardPanel() {
        val cv = clipboardView ?: return
        if (inputView?.isPanelShowing(cv) == true) cv.refresh()
    }

    // C1/C2 clipboard controls wired to the clipboard panel controls.
    private fun historyEnabled() = getSharedPreferences("aegis", MODE_PRIVATE).getBoolean("clip_history", true)
    private fun setHistoryEnabled(on: Boolean) =
        getSharedPreferences("aegis", MODE_PRIVATE).edit().putBoolean("clip_history", on).apply()
    // Chinese IME behavior note.
    // Chinese IME behavior note.

    override fun onDestroy() {
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) }
        // debug.47: withdraw the live user-dict host (only if it is still ours) so the settings page falls
        // back to the file path instead of writing through a dead service's model.
        if (UserDictHot.host === liveUserDictHost) UserDictHot.host = null
        runCatching {
            getSharedPreferences("aegis", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(settingsHotApply)
        } // ③
        super.onDestroy()
    }

    // --- ImeHost ---

    // debug.16 Option A: every ImeHost output checks [panelInput] FIRST. While inline-editing it consumes the
    // output into the buffer and returns; otherwise (the normal case) it falls through to the editor UNCHANGED.
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

    /**
     * E5: commit a clipboard entry that may be huge (million-char paste) in binder-safe chunks — a single
      * Chinese IME behavior note.
     * (A third-party editor may still refuse an oversized paste of its own accord — that's outside the IME.)
     */
    private fun commitLargeText(text: CharSequence) {
        if (panelInput.commit(text)) return // debug.16: a clip tapped during inline edit goes into the buffer, not the app
        controller.expireCandidateChoiceUndo()
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

    override fun panelBackspace() {
        if (panelInput.backspace()) return
        controller.expireCandidateChoiceUndo()
        if (hasSelection()) deleteSelection() else deleteCodePointBackward()
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
