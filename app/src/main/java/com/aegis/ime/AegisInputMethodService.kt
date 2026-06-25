package com.aegis.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.aegis.ime.engine.StubEngine
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController

class AegisInputMethodService : InputMethodService(), ImeHost {

    private lateinit var controller: KeyboardController

    override fun onCreate() {
        super.onCreate()
        controller = KeyboardController(this, StubEngine())
    }

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
