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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.user.ClipboardStore
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
    private val clipboardStore by lazy { ClipboardStore(filesDir).also { it.load() } }

    override fun onCreate() {
        super.onCreate()
        // Start with an empty engine (ASCII typing works immediately); load the ~70 MB dictionaries
        // and the user model off the main thread and swap the real engine in when ready.
        controller = KeyboardController(this, DictEngine(null, null, null))
        controller.onShowEmoji = { showEmojiPanel() }
        controller.onShowClipboard = { showClipboardPanel() }
        controller.onClosePanel = { inputView?.showPanel(null) }
        Thread {
            runCatching { userModel.load(userDbFile); userDbMtime = userDbFile.lastModified() }
            val dict = loadDict("aegis_dict.bin")
            val t9Dict = loadDict("aegis_t9.bin")
            val fuzzyEnabled = getSharedPreferences("aegis", MODE_PRIVATE).getBoolean("fuzzy", true)
            val fuzzyDict = if (fuzzyEnabled) loadDict("aegis_fuzzy.bin") else null
            val initialsDict = loadDict("aegis_jianpin.bin")
            val lm = loadLm("aegis_lm.bin")
            // Optional top-tier context model, only if the user downloaded it.
            val octagram = runCatching { OctagramReader.fromDownloads(this, "wanxiang-lts-zh-hans.gram") }
                .onFailure { Log.e("Aegis", "octagram load failed", it) }.getOrNull()
            val enDict = loadDict("aegis_en.bin")
            val engine = DictEngine(dict, t9Dict, lm, userModel, fuzzyDict, initialsDict, octagram, enDict)
            Handler(Looper.getMainLooper()).post { controller.setEngine(engine) }
        }.apply { name = "aegis-dict-load"; isDaemon = true }.start()
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // Pick up an imported user dict (newer file, no unsaved edits) without restarting the IME.
        if (!userModel.dirty && userDbFile.lastModified() > userDbMtime) {
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
            onFunction = { f -> controller.onBarFunction(f) }
        }
        inputView = view
        controller.attachView(view)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputView?.showPanel(null)
        controller.reset()
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
            it.phraseProvider = { clipboardStore.phrases() }
            it.onPick = { t -> currentInputConnection?.commitText(t, 1); inputView?.showPanel(null) }
            it.onBack = { inputView?.showPanel(null) }
            clipboardView = it
        }
        cv.refresh()
        iv.showPanel(cv)
    }

    private fun captureClip() {
        runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0) clipboardStore.record(clip.getItemAt(0).coerceToText(this)?.toString())
        }
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
