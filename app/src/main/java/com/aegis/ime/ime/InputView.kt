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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyboardLayout

/** Root IME view: candidate strip stacked above the self-drawn keyboard (or an extras panel). */
class InputView(context: Context) : LinearLayout(context) {

    var onKey: (Key) -> Unit = {}
    var onPickCandidate: (Int) -> Unit = {}
    var onFunction: (BarFunction) -> Unit = {}

    private val candidateView = CandidateView(context)
    private val keyboardView = KeyboardView(context)
    private val panelContainer = FrameLayout(context)

    init {
        orientation = VERTICAL
        // Keyboard-grey fill so the inset gutters (behind the gesture bar / cutout) match the keys.
        setBackgroundColor(0xFFE2E6EA.toInt())
        candidateView.onPick = { index -> onPickCandidate(index) }
        candidateView.onFunction = { f -> onFunction(f) }
        keyboardView.onKey = { key -> onKey(key) }
        addView(candidateView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panelContainer.visibility = GONE
        addView(panelContainer, LayoutParams(LayoutParams.MATCH_PARENT, dp(250)))

        // targetSdk 36 forces the IME window edge-to-edge. Consume the insets ourselves: raise the
        // bottom above the nav/home bar (#2) and keep only a small side margin (#1 — the previous
        // systemGestures inset narrowed the keyboard far too much; the keys should nearly fill width).
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val side = dp(4)
            v.setPadding(maxOf(cut.left, side), 0, maxOf(cut.right, side), nav.bottom + dp(10))
            WindowInsetsCompat.CONSUMED
        }
    }

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean) {
        keyboardView.setLayout(layout, shifted)
    }

    fun showCandidates(candidates: List<String>, composing: String) {
        candidateView.setContent(candidates, composing)
    }

    /** Swap the keyboard area for an extras panel (emoji / clipboard); null restores the keyboard. */
    fun showPanel(panel: View?) {
        panelContainer.removeAllViews()
        if (panel == null) {
            panelContainer.visibility = GONE
            keyboardView.visibility = VISIBLE
        } else {
            (panel.parent as? ViewGroup)?.removeView(panel)
            panelContainer.addView(
                panel,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            panelContainer.visibility = VISIBLE
            keyboardView.visibility = GONE
        }
    }

    val panelShown: Boolean get() = panelContainer.visibility == VISIBLE

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
