package com.aegis.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController

/**
 * Aegis IME entry point. Builds the input view, wires it to [KeyboardController], and bridges
 * the controller's editor operations to the current [android.view.inputmethod.InputConnection].
 *
 * P2: EN commits ASCII directly; CN (26-key) routes through [DictEngine] for real candidates.
 * T9 disambiguation lands at P4; full syllable-segmenting decoder at P3.
 */
class AegisInputMethodService : InputMethodService(), ImeHost {

    private lateinit var controller: KeyboardController

    override fun onCreate() {
        super.onCreate()
        val dict = loadDict("aegis_dict.bin")
        val t9Dict = loadDict("aegis_t9.bin")
        controller = KeyboardController(this, DictEngine(dict, t9Dict))
    }

    private fun loadDict(name: String): BinaryDict? =
        runCatching { BinaryDict.fromAssets(this, name) }
            .onFailure { Log.e("Aegis", "dict load failed: $name", it) }
            .getOrNull()

    override fun onCreateInputView(): View {
        val view = InputView(this).apply {
            onKey = { key -> controller.onKey(key) }
            onPickCandidate = { index -> controller.onPickCandidate(index) }
        }
        controller.attachView(view)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller.reset()
    }

    // --- ImeHost ---

    override fun commitText(text: CharSequence) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun deleteBackward() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    override fun performEnter() {
        sendDefaultEditorAction(true)
    }
}
