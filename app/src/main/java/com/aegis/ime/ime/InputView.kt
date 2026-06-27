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
import com.aegis.ime.layout.Lang

class InputView(context: Context) : LinearLayout(context) {

    var onKey: (Key) -> Unit = {}
    var onPickCandidate: (Int) -> Unit = {}
    var onPickReading: (Int) -> Unit = {}
    var onFunction: (BarFunction) -> Unit = {}
    var onBackspaceSwipe: (Boolean) -> Unit = {}
    var onPanelBackspace: () -> Unit = {}
    var onPanelClear: () -> Unit = {}
    var onCollapse: () -> Unit = {}

    private val preeditView = PreeditView(context)
    private val candidateView = CandidateView(context)
    private val keyboardView = KeyboardView(context)
    private val panelContainer = FrameLayout(context)
    private val gridView = CandidateGridView(context)
    private val body = LinearLayout(context)
    private var lastCandidates: List<String> = emptyList()
    private var lastReadings: List<String> = emptyList()

    init {
        orientation = VERTICAL
        candidateView.onPick = { index -> onPickCandidate(index) }
        candidateView.onFunction = { f -> onFunction(f) }
        candidateView.onExpand = { showExpandedCandidates() }
        candidateView.onCollapse = { onCollapse() }
        gridView.onPick = { index -> onPickCandidate(index) }
        gridView.onPickReading = { index -> onPickReading(index) }
        gridView.onClose = { showPanel(null) }
        gridView.onBackspace = { onPanelBackspace() }
        gridView.onClear = { onPanelClear() }
        keyboardView.onKey = { key -> onKey(key) }
        keyboardView.onBackspaceSwipe = { up -> onBackspaceSwipe(up) }
        addView(preeditView, LayoutParams(LayoutParams.MATCH_PARENT, barTopInsetPx()))

        body.orientation = VERTICAL
        body.setBackgroundColor(0xFFE2E6EA.toInt())
        body.addView(candidateView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        body.addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panelContainer.visibility = GONE
        body.addView(panelContainer, LayoutParams(LayoutParams.MATCH_PARENT, dp(250)))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val side = dp(4)
            body.setPadding(maxOf(cut.left, side), 0, maxOf(cut.right, side), nav.bottom + dp(28))
            WindowInsetsCompat.CONSUMED
        }
    }

    fun barTopInsetPx(): Int = dp(26)

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean, lang: Lang) {
        keyboardView.setLayout(layout, shifted, lang)
    }

    fun showCandidates(candidates: List<String>, preedit: String, readings: List<String>) {
        lastCandidates = candidates
        lastReadings = readings
        preeditView.setText(preedit)
        candidateView.setContent(candidates, preedit)
        if (panelShown) {
            if (preedit.isEmpty()) showPanel(null)
            else { gridView.setCandidates(candidates); gridView.setReadings(readings) }
        }
    }

    private fun showExpandedCandidates() {
        if (lastCandidates.isEmpty()) return
        gridView.setCandidates(lastCandidates)
        gridView.setReadings(lastReadings)
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
