package com.aegis.ime.ime

import android.content.Context
import android.widget.LinearLayout
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyboardLayout

class InputView(context: Context) : LinearLayout(context) {

    var onKey: (Key) -> Unit = {}
    var onPickCandidate: (Int) -> Unit = {}

    private val candidateView = CandidateView(context)
    private val keyboardView = KeyboardView(context)

    init {
        orientation = VERTICAL
        candidateView.onPick = { index -> onPickCandidate(index) }
        keyboardView.onKey = { key -> onKey(key) }
        addView(candidateView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean) {
        keyboardView.setLayout(layout, shifted)
    }

    fun showCandidates(candidates: List<String>, composing: String) {
        candidateView.setContent(candidates, composing)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
