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

class InputView(context: Context) : LinearLayout(context) {

    var onKey: (Key) -> Unit = {}
    var onPickCandidate: (Int) -> Unit = {}
    var onFunction: (BarFunction) -> Unit = {}
    var onBackspaceSwipe: (Boolean) -> Unit = {}
    var onCollapse: () -> Unit = {}

    private val preeditView = PreeditView(context)
    private val candidateView = CandidateView(context)
    private val keyboardView = KeyboardView(context)
    private val panelContainer = FrameLayout(context)
    private val gridView = CandidateGridView(context)
    private var lastCandidates: List<String> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFE2E6EA.toInt())
        candidateView.onPick = { index -> onPickCandidate(index) }
        candidateView.onFunction = { f -> onFunction(f) }
        candidateView.onExpand = { showExpandedCandidates() }
        candidateView.onCollapse = { onCollapse() }
        gridView.onPick = { index -> showPanel(null); onPickCandidate(index) }
        gridView.onClose = { showPanel(null) }
        keyboardView.onKey = { key -> onKey(key) }
        keyboardView.onBackspaceSwipe = { up -> onBackspaceSwipe(up) }
        preeditView.visibility = GONE
        addView(preeditView, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        addView(candidateView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panelContainer.visibility = GONE
        addView(panelContainer, LayoutParams(LayoutParams.MATCH_PARENT, dp(250)))

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val side = dp(4)
            v.setPadding(maxOf(cut.left, side), 0, maxOf(cut.right, side), nav.bottom + dp(16))
            WindowInsetsCompat.CONSUMED
        }
    }

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean) {
        keyboardView.setLayout(layout, shifted)
    }

    fun showCandidates(candidates: List<String>, preedit: String) {
        lastCandidates = candidates
        preeditView.setText(preedit)
        preeditView.visibility = if (preedit.isEmpty()) GONE else VISIBLE
        candidateView.setContent(candidates, preedit)
        if (panelShown && candidates.isEmpty()) showPanel(null)
    }

    private fun showExpandedCandidates() {
        if (lastCandidates.isEmpty()) return
        gridView.setCandidates(lastCandidates)
        showPanel(gridView)
    }

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
