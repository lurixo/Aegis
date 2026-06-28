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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyboardLayout
import com.aegis.ime.layout.Lang

/** Root IME view: candidate strip stacked above the self-drawn keyboard (or an extras panel). */
class InputView(context: Context) : LinearLayout(context) {

    var onKey: (Key) -> Unit = {}
    var onPickCandidate: (Int) -> Unit = {}
    var onPickReading: (Int) -> Unit = {}   // A2 expanded screen: pick a pinyin combination (left column)
    var onFunction: (BarFunction) -> Unit = {}
    var onBackspaceSwipe: (Boolean) -> Unit = {}
    var onPanelBackspace: () -> Unit = {}   // A2 expanded screen: 退格
    var onPanelClear: () -> Unit = {}       // A2 expanded screen: 重输
    var onCollapse: () -> Unit = {}
    var onCopyCommit: (String) -> Unit = {} // 复制条 ⑤: 上屏 the copied content
    var onCopyBlock: (String) -> Unit = {}  // 复制条 ③: 拆词 block → aegis clipboard

    private val preeditView = PreeditView(context)
    private val candidateView = CandidateView(context)
    private val copyBarView = CopyBarView(context) // 复制条: shares the candidate-strip row
    private val keyboardView = KeyboardView(context)
    private val panelContainer = FrameLayout(context)
    private val gridView = CandidateGridView(context)
    private val body = LinearLayout(context) // grey-filled body: candidate bar + keyboard / panel
    private var lastCandidates: List<String> = emptyList()
    private var lastReadings: List<String> = emptyList()
    private var currentPanel: View? = null // which panel is showing (B-1: only the A2 grid auto-closes)
    private var palette = ImePalette.STATIC_LIGHT

    /** F1: fan the Monet palette out to the candidate strip / keyboard / preedit / copy-bar / expand grid. */
    fun applyPalette(p: ImePalette) {
        palette = p
        body.setBackgroundColor(p.keyboardBg)
        preeditView.applyPalette(p)
        candidateView.applyPalette(p)
        copyBarView.applyPalette(p)
        keyboardView.applyPalette(p)
        gridView.applyPalette(p)
    }

    /** The active Monet palette (the IME service hands new panels their colours on open). */
    fun palette(): ImePalette = palette

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
        candidateView.onCollapseExpanded = { showPanel(null) } // U14: flipped chevron closes the A2 grid
        // A2 expanded screen: don't force-close on pick — showCandidates() refreshes the grid if composing
        // continues (partial / per-syllable commit) and closes it once the buffer empties.
        gridView.onPick = { index -> onPickCandidate(index) }
        gridView.onPickReading = { index -> onPickReading(index) }
        gridView.onClose = { showPanel(null) }
        gridView.onBackspace = { onPanelBackspace() }
        gridView.onClear = { onPanelClear() }
        keyboardView.onKey = { key -> onKey(key) }
        keyboardView.onBackspaceSwipe = { up -> onBackspaceSwipe(up) }
        // 复制条: content → 上屏; block → aegis clipboard; both ⑤ and ④ leave via onDismiss.
        copyBarView.onCommit = { t -> onCopyCommit(t) }
        copyBarView.onCopyBlock = { b -> onCopyBlock(b) }
        copyBarView.onDismiss = { hideCopyBar() }
        // the preedit + candidate rows are FIXED-HEIGHT and ALWAYS present — only their
        // CONTENT changes, never their visibility — so the IME's total height never changes while typing.
        // (Toggling the preedit GONE/VISIBLE grew/shrank the window on every keystroke that started or
        // ended composing, which made hosts like Telegram re-layout their compose box.) The transparent root keeps
        // it fixed-height but transparent: AegisInputMethodService.onComputeInsets reports the content top
        // at the candidate bar, so the host app reclaims this band instead of being pushed up by it.
        addView(preeditView, LayoutParams(LayoutParams.MATCH_PARENT, barTopInsetPx()))

        body.orientation = VERTICAL
        body.setBackgroundColor(palette.keyboardBg)
        body.addView(candidateView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        copyBarView.visibility = GONE // 复制条 occupies the same 44dp row as the candidate strip when shown
        body.addView(copyBarView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
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
            // B4: lift the whole keyboard a little higher off the gesture/home bar (was dp(16) — too tight).
            val leftPad = maxOf(cut.left, side)
            body.setPadding(leftPad, 0, maxOf(cut.right, side), nav.bottom + dp(28))
            // U12: the preedit band is outside `body`, so feed it the same left inset to keep the pinyin
            // left-aligned with the first candidate (which is inset by body's left padding).
            preeditView.setLeftInset(leftPad.toFloat())
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

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean, lang: Lang) {
        keyboardView.setLayout(layout, shifted, lang)
    }

    /** 复制条: show the captured clip on the taskbar row (replacing the normal toolbar). */
    fun showCopyBar(text: String) {
        copyBarView.show(text)
        copyBarView.visibility = VISIBLE
        candidateView.visibility = GONE
    }

    /** Leave the copy-bar state → restore the normal candidate strip / toolbar. */
    fun hideCopyBar() {
        copyBarView.visibility = GONE
        candidateView.visibility = VISIBLE
    }

    val copyBarShown: Boolean get() = copyBarView.visibility == VISIBLE

    /** [preedit] is the pinyin tab text (separate from candidates, C1); [readings] = the active syllable's
     *  combinations for the expanded screen's left column (A2). */
    fun showCandidates(candidates: List<String>, preedit: String, readings: List<String>) {
        lastCandidates = candidates
        lastReadings = readings
        // Content-only: empty text just blanks the row; the row keeps its fixed height so the IME window
        // height is constant and the host's layout never jitters while typing.
        preeditView.setText(preedit)
        candidateView.setContent(candidates, preedit)
        // Composing wins the strip: once the user starts typing, drop the copy-bar so candidates show.
        if (copyBarShown && (candidates.isNotEmpty() || preedit.isNotEmpty())) hideCopyBar()
        // B-1/M-1/L-1: ONLY the A2 expanded candidate grid reacts to a render here — it live-refreshes and
        // closes when composing ends. Every OTHER panel (emoji / clipboard / symbols / 自定义 / edit) must
        // SURVIVE the render() that trails each onKey / setCustomSymbols / setEngine; the old code closed ANY
        // visible panel the moment the preedit emptied, so a panel opened then immediately auto-closed.
        if (currentPanel === gridView) {
            if (preedit.isEmpty()) showPanel(null)
            else { gridView.setCandidates(candidates); gridView.setReadings(readings) }
        }
    }

    internal fun showExpandedCandidates() {
        if (lastCandidates.isEmpty()) return
        gridView.setCandidates(lastCandidates)
        gridView.setReadings(lastReadings)
        showPanel(gridView)
    }

    /** Candidates actually rendered in the strip right now (test hook, U1 regression guard). */
    internal fun shownCandidateCount(): Int = candidateView.itemCount()

    /** U14 test seam: the candidate-bar chevron glyph (⌃ while the A2 grid is open, else ⌄). */
    internal fun barChevronGlyph(): String = candidateView.chevronGlyph()

    /** Swap the keyboard area for an extras panel (emoji / clipboard); null restores the keyboard. */
    fun showPanel(panel: View?) {
        panelContainer.removeAllViews()
        currentPanel = panel
        // U14: the candidate-bar chevron points up + collapses only while the A2 grid is the open panel.
        candidateView.setExpanded(panel === gridView)
        if (panel == null) {
            panelContainer.visibility = GONE
            keyboardView.visibility = VISIBLE
        } else {
            // U19: occupy EXACTLY the keyboard's current footprint so opening a panel never grows or shrinks
            // the IME window. The old fixed 250dp expanded the short 9-key (panel taller) and shrank the tall
            // 26-key (panel shorter) — "九键向上扩展 / 26键向下缩放". Matching the live keyboard height keeps it
            // constant on every layout; panels scroll internally if their content needs more room.
            setPanelHeight(keyboardView.height.takeIf { it > 0 } ?: dp(250))
            (panel.parent as? ViewGroup)?.removeView(panel)
            panelContainer.addView(
                panel,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            panelContainer.visibility = VISIBLE
            keyboardView.visibility = GONE
        }
    }

    /** U19: pin the panel slot to [px] (the keyboard footprint) so the IME height stays put on panel open. */
    private fun setPanelHeight(px: Int) {
        val lp = panelContainer.layoutParams
        if (lp.height != px) { lp.height = px; panelContainer.layoutParams = lp }
    }

    /** U19 test seam: the panel slot's current fixed height (px). */
    internal fun panelHeightPx(): Int = panelContainer.layoutParams.height

    /** U19 test seam: the live keyboard height (px) the panel must match. */
    internal fun keyboardHeightPx(): Int = keyboardView.height

    val panelShown: Boolean get() = panelContainer.visibility == VISIBLE

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
