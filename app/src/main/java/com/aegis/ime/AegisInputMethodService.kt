package com.aegis.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController

class AegisInputMethodService : InputMethodService(), ImeHost {

    private lateinit var controller: KeyboardController

    override fun onCreate() {
        super.onCreate()
        val dict = loadDict("aegis_dict.bin")
        val t9Dict = loadDict("aegis_t9.bin")
        val lm = runCatching { CharBigramLM.fromAssets(this, "aegis_lm.bin") }
            .onFailure { Log.e("Aegis", "lm load failed", it) }
            .getOrNull()
        controller = KeyboardController(this, DictEngine(dict, t9Dict, lm))
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
