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
    var onCopyDismiss: () -> Unit = {}      // U21: 复制条 ④/⑤ left → host forgets the persisted clip

    private val preeditView = PreeditView(context)
    private val candidateView = CandidateView(context)
    private val copyBarView = CopyBarView(context) // 复制条: shares the candidate-strip row
    private val keyboardView = KeyboardView(context)
    private val panelContainer = FrameLayout(context)
    private val gridView = CandidateGridView(context)
    private val body = LinearLayout(context) // grey-filled body: candidate bar + keyboard / panel
    private var lastCandidates: List<String> = emptyList()
    private var lastReadings: List<String> = emptyList()
    private var composingNow = false // U21: whether the candidate strip is showing candidates / preedit
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
        copyBarView.onDismiss = { hideCopyBar(); onCopyDismiss() }
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

        // S3: deterministic baseline BEFORE any insets dispatch. A dark/light switch makes the IME framework
        // re-inflate this view (new `body`, bottom padding 0) and the platform does not reliably re-dispatch
        // the unchanged navbar inset to the rebuilt tree — so the bottom raise was lost intermittently. Seed
        // the raise from the process-wide cache of the last navbar bottom, so a rebuilt view is never at 0
        // once any inset has been seen. Harmless when the cache is still 0 (gesture nav / first cold show):
        // the +28dp raise is applied either way, and the listener below refines it on the next real dispatch.
        applyWindowPadding(lastNavBottomPx, 0, 0)

        // targetSdk 36 forces the IME window edge-to-edge. Consume the insets on [body] (NOT the
        // transparent band): raise the bottom above the nav/home bar (#2) and keep only a small side
        // margin (#1) so the grey fill still covers the keyboard gutters without painting the top band.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            // S3: cache the live navbar inset so the next rebuild can restore it. Only cache a real (>0)
            // value: a transient 0 (window teardown / a briefly-immersive host) must not poison the baseline
            // a future rebuild seeds — keeping the last real height biases any residual frame to a harmless
            // over-raise (a small gap) rather than the bug's under-raise (keyboard glued to the nav bar).
            // onAttachedToWindow's requestApplyInsets corrects either way on the next real dispatch.
            if (nav.bottom > 0) lastNavBottomPx = nav.bottom
            applyWindowPadding(nav.bottom, cut.left, cut.right)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Apply the edge-to-edge padding to [body]: raise the bottom above the nav/home bar ([navBottom] + 28dp,
     * B4) and keep a small side margin clear of any display cutout (#1). [preeditView] sits outside `body`,
     * so it gets the same left inset to keep the pinyin left-aligned with the first candidate (U12). Shared
     * by the insets listener and the S3 cached baseline so both produce identical geometry.
     */
    private fun applyWindowPadding(navBottom: Int, cutLeft: Int, cutRight: Int) {
        val side = dp(4)
        val leftPad = maxOf(cutLeft, side)
        body.setPadding(leftPad, 0, maxOf(cutRight, side), navBottom + dp(28))
        preeditView.setLeftInset(leftPad.toFloat())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // S3: force a fresh insets dispatch on (re)attach so the raise is recomputed after a theme-switch
        // rebuild, instead of relying on the platform to spontaneously re-dispatch the unchanged navbar inset.
        ViewCompat.requestApplyInsets(this)
    }

    /**
     * Height of the transparent preedit band above the candidate bar. The IME service
     * reports its content/visible top this far below the input-view top, so the host app keeps this strip
     * and only the floating pinyin tab overlaps it — instead of a full-width grey bar. Constant height ⇒
     * the host layout still never jitters.
     */
    fun barTopInsetPx(): Int = dp(26)

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean, locked: Boolean, lang: Lang) {
        keyboardView.setLayout(layout, shifted, locked, lang)
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

    /** U21: whether the candidate strip is currently composing (so the host won't clobber it with the copy-bar). */
    fun isComposing(): Boolean = composingNow

    /** [preedit] is the pinyin tab text (separate from candidates, C1); [readings] = the active syllable's
     *  combinations for the expanded screen's left column (A2). */
    fun showCandidates(candidates: List<String>, preedit: String, readings: List<String>) {
        lastCandidates = candidates
        lastReadings = readings
        // Content-only: empty text just blanks the row; the row keeps its fixed height so the IME window
        // height is constant and the host's layout never jitters while typing.
        preeditView.setText(preedit)
        candidateView.setContent(candidates, preedit)
        composingNow = candidates.isNotEmpty() || preedit.isNotEmpty()
        // Composing wins the strip: once the user starts typing, drop the copy-bar so candidates show — and
        // forget the persisted clip (U21) so it does NOT resurrect on the next field; only an app-switch
        // re-show (no typing) keeps it. × / ⑤ already clear it via onCopyDismiss.
        if (copyBarShown && composingNow) { hideCopyBar(); onCopyDismiss() }
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
        // P7 (#19): the panel we're leaving returns to its default state, so the next open always starts
        // fresh (default tab/category/scroll, no lock/overlay). Every close funnels through here — 返回, a
        // commit, the P4 re-tap toggle, and onStartInputView's showPanel(null) — so one hook covers them all.
        (currentPanel as? ResettablePanel)?.takeIf { it !== panel }?.resetToDefault()
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

    /**
     * P4 (#4): is [panel] the one currently on screen? An entry-icon handler uses this to TOGGLE — re-tapping
     * the icon that opened a panel closes it (back to the keyboard) instead of rebuilding the same panel.
     */
    fun isPanelShowing(panel: View?): Boolean = panelShown && panel != null && currentPanel === panel

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // S3 test seams.
    internal fun bodyBottomPaddingPx(): Int = body.paddingBottom
    /** Mimic an insets dispatch the way the listener does: cache the navbar bottom + apply the padding. */
    internal fun simulateNavInsetForTest(navBottomPx: Int) {
        lastNavBottomPx = navBottomPx
        applyWindowPadding(navBottomPx, 0, 0)
    }
    /** F4: read the process-wide cache so a test can prove the real listener's >0 guard kept a transient 0 out. */
    internal fun cachedNavBottomForTest(): Int = lastNavBottomPx

    private companion object {
        // S3: the last real navbar bottom inset, kept process-wide (survives the input-view re-inflation on a
        // theme switch) so a rebuilt InputView can restore the bottom raise immediately in its init, instead
        // of waiting for a window-insets re-dispatch the platform may skip for an unchanged inset value. A
        // single value can't model two displays with different nav bars at once (last writer wins), but each
        // view corrects to its own inset on its first real dispatch, so any cross-display skew is ~one frame.
        private var lastNavBottomPx = 0
    }
}
