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
    var onBackspaceSwipe: (Boolean) -> Unit = {}
    var onCollapse: () -> Unit = {}

    private val preeditView = PreeditView(context)
    private val candidateView = CandidateView(context)
    private val keyboardView = KeyboardView(context)
    private val panelContainer = FrameLayout(context)
    private val gridView = CandidateGridView(context)
    private val body = LinearLayout(context) // grey-filled body: candidate bar + keyboard / panel
    private var lastCandidates: List<String> = emptyList()

    init {
        orientation = VERTICAL
        // the root is TRANSPARENT so the preedit band at the very top shows the app through it
        // — only the floating pinyin tab pokes above the candidate bar. The
        // keyboard-grey fill (for the inset gutters behind the gesture bar / cutout) lives on [body], which
        // is also where the window-insets padding is applied. Before this the whole top was a tall opaque
        // grey band, which read as "顶部背景过高".
        candidateView.onPick = { index -> onPickCandidate(index) }
        candidateView.onFunction = { f -> onFunction(f) }
        candidateView.onExpand = { showExpandedCandidates() }
        candidateView.onCollapse = { onCollapse() }
        gridView.onPick = { index -> showPanel(null); onPickCandidate(index) }
        gridView.onClose = { showPanel(null) }
        keyboardView.onKey = { key -> onKey(key) }
        keyboardView.onBackspaceSwipe = { up -> onBackspaceSwipe(up) }
        // the preedit + candidate rows are FIXED-HEIGHT and ALWAYS present — only their
        // CONTENT changes, never their visibility — so the IME's total height never changes while typing.
        // (Toggling the preedit GONE/VISIBLE grew/shrank the window on every keystroke that started or
        // ended composing, which made hosts like Telegram re-layout their compose box.) The transparent root keeps
        // it fixed-height but transparent: AegisInputMethodService.onComputeInsets reports the content top
        // at the candidate bar, so the host app reclaims this band instead of being pushed up by it.
        addView(preeditView, LayoutParams(LayoutParams.MATCH_PARENT, barTopInsetPx()))

        body.orientation = VERTICAL
        body.setBackgroundColor(0xFFE2E6EA.toInt())
        body.addView(candidateView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        body.addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panelContainer.visibility = GONE
        body.addView(panelContainer, LayoutParams(LayoutParams.MATCH_PARENT, dp(250)))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // targetSdk 36 forces the IME window edge-to-edge. Consume the insets on [body] (NOT the
        // transparent band): raise the bottom above the nav/home bar (#2) and keep only a small side
        // margin (#1) so the grey fill still covers the keyboard gutters without painting the top band.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val side = dp(4)
            body.setPadding(maxOf(cut.left, side), 0, maxOf(cut.right, side), nav.bottom + dp(16))
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Height of the transparent preedit band above the candidate bar. The IME service
     * reports its content/visible top this far below the input-view top, so the host app keeps this strip
     * and only the floating pinyin tab overlaps it — instead of a full-width grey bar. Constant height ⇒
     * the host layout still never jitters.
     */
    fun barTopInsetPx(): Int = dp(26)

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean) {
        keyboardView.setLayout(layout, shifted)
    }

    /** [preedit] is the pinyin tab text (separate from candidates, C1); empty hides the tab. */
    fun showCandidates(candidates: List<String>, preedit: String) {
        lastCandidates = candidates
        // Content-only: empty text just blanks the row; the row keeps its fixed height so the IME window
        // height is constant and the host's layout never jitters while typing.
        preeditView.setText(preedit)
        candidateView.setContent(candidates, preedit)
        if (panelShown && candidates.isEmpty()) showPanel(null) // composing ended → drop the grid
    }

    private fun showExpandedCandidates() {
        if (lastCandidates.isEmpty()) return
        gridView.setCandidates(lastCandidates)
        showPanel(gridView)
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
