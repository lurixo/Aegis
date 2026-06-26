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

package com.aegis.ime.ime

import android.content.Context
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyboardLayout

/** Root IME view: candidate strip stacked above the self-drawn keyboard. */
class InputView(context: Context) : LinearLayout(context) {

    var onKey: (Key) -> Unit = {}
    var onPickCandidate: (Int) -> Unit = {}

    private val candidateView = CandidateView(context)
    private val keyboardView = KeyboardView(context)

    init {
        orientation = VERTICAL
        // Keyboard-grey fill so the inset gutters (behind the gesture bar / cutout) match the keys.
        setBackgroundColor(0xFFE2E6EA.toInt())
        candidateView.onPick = { index -> onPickCandidate(index) }
        keyboardView.onKey = { key -> onKey(key) }
        addView(candidateView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // targetSdk 36 forces the IME window edge-to-edge, so the input view would otherwise extend
        // behind the gesture nav bar and screen edges. Consume the insets ourselves: raise the bottom
        // above the nav/home bar and inset the sides off the cutout / edge-gesture zone.
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemGestures(),
            )
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean) {
        keyboardView.setLayout(layout, shifted)
    }

    fun showCandidates(candidates: List<String>, composing: String) {
        candidateView.setContent(candidates, composing)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
