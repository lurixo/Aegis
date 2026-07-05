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

class InputView(context: Context) : LinearLayout(context) {

    var onKey: (Key) -> Unit = {}
    var onPickCandidate: (Int) -> Unit = {}
    var onPickReading: (Int) -> Unit = {}
    var onFunction: (BarFunction) -> Unit = {}
    var onBackspaceSwipe: (Boolean) -> Unit = {}
    var onPanelBackspace: () -> Unit = {}
    var onPanelClear: () -> Unit = {}
    var onExpandClosed: () -> Unit = {}
    var onCollapse: () -> Unit = {}
    var onCopyCommit: (String) -> Unit = {}
    var onCopyBlock: (String) -> Unit = {}
    var onCopyDismiss: () -> Unit = {}
    var onEditConfirm: () -> Unit = {}
    var onEditCancel: () -> Unit = {}
    var onOverlayChanged: () -> Unit = {}

    private val preeditView = PreeditView(context)
    private val candidateView = CandidateView(context)
    private val copyBarView = CopyBarView(context)
    private val editBarView = EditBarView(context)
    private val keyboardView = KeyboardView(context)
    private val panelContainer = FrameLayout(context)
    private val gridView = CandidateGridView(context)
    private val body = LinearLayout(context)
    private var lastCandidates: List<String> = emptyList()
    private var lastReadings: List<String> = emptyList()
    private var lastSelectedReading = -1
    private var composingNow = false
    private var currentPanel: View? = null
    private var copyBarActive = false
    private var editBarActive = false
    private var palette = ImePalette.STATIC_LIGHT

    fun applyPalette(p: ImePalette) {
        palette = p
        body.setBackgroundColor(p.keyboardBg)
        panelContainer.setBackgroundColor(p.keyboardBg)
        preeditView.applyPalette(p)
        candidateView.applyPalette(p)
        copyBarView.applyPalette(p)
        keyboardView.applyPalette(p)
        gridView.applyPalette(p)
        editBarView.applyPalette(p)
    }

    fun palette(): ImePalette = palette

    fun showEditBar(active: Boolean) {
        editBarActive = active
        if (active) {
            Motion.revealIn(editBarView, Motion.EnterFrom.TOP)
        } else {
            Motion.hide(editBarView, toward = Motion.EnterFrom.TOP)
        }
        onOverlayChanged()
    }
    fun isEditBarShowing(): Boolean = editBarView.visibility == VISIBLE
    fun setEditTitle(t: String) { editBarView.setTitle(t) }
    fun setEditText(t: String) { editBarView.setText(t) }

    init {
        orientation = VERTICAL
        candidateView.onPick = { index -> onPickCandidate(index) }
        candidateView.onFunction = { f -> onFunction(f) }
        candidateView.onExpand = { showExpandedCandidates() }
        candidateView.onCollapse = { onCollapse() }
        candidateView.onCollapseExpanded = { showPanel(null) }
        gridView.onPick = { index -> onPickCandidate(index) }
        gridView.onPickReading = { index -> onPickReading(index) }
        gridView.onClose = { showPanel(null) }
        gridView.onBackspace = { onPanelBackspace() }
        gridView.onClear = { onPanelClear() }
        keyboardView.onKey = { key -> onKey(key) }
        keyboardView.onBackspaceSwipe = { up -> onBackspaceSwipe(up) }
        copyBarView.onCommit = { t -> onCopyCommit(t) }
        copyBarView.onCopyBlock = { b -> onCopyBlock(b) }
        copyBarView.onDismiss = { hideCopyBar(); onCopyDismiss() }
        editBarView.onConfirm = { onEditConfirm() }
        editBarView.onCancel = { onEditCancel() }
        addView(preeditView, LayoutParams(LayoutParams.MATCH_PARENT, barTopInsetPx()))

        body.orientation = VERTICAL
        body.setBackgroundColor(palette.keyboardBg)
        editBarView.visibility = GONE
        body.addView(editBarView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        body.addView(candidateView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        copyBarView.visibility = GONE
        body.addView(copyBarView, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        body.addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panelContainer.visibility = GONE
        panelContainer.setBackgroundColor(palette.keyboardBg)
        body.addView(panelContainer, LayoutParams(LayoutParams.MATCH_PARENT, dp(250)))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        applyWindowPadding(lastNavBottomPx, 0, 0)

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            if (nav.bottom > 0) lastNavBottomPx = nav.bottom
            applyWindowPadding(nav.bottom, cut.left, cut.right)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun applyWindowPadding(navBottom: Int, cutLeft: Int, cutRight: Int) {
        val side = dp(4)
        val leftPad = maxOf(cutLeft, side)
        body.setPadding(leftPad, 0, maxOf(cutRight, side), navBottom + dp(28))
        preeditView.setLeftInset(leftPad.toFloat())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Motion.reset(keyboardView)
        Motion.reset(preeditView)
        Motion.reset(candidateView)
        Motion.reset(copyBarView)
        Motion.reset(editBarView)
        currentPanel?.let { Motion.reset(it) }
    }

    fun barTopInsetPx(): Int = dp(26)

    fun showKeyboard(layout: KeyboardLayout, shifted: Boolean, locked: Boolean, lang: Lang) {
        keyboardView.setLayout(layout, shifted, locked, lang)
    }

    fun setKeyHaptics(on: Boolean) { keyboardView.hapticEnabled = on }
    fun setKeyPreviewNine(on: Boolean) { keyboardView.previewNineEnabled = on }
    fun setKeyPreviewAlpha(on: Boolean) { keyboardView.previewAlphaEnabled = on }
    fun setLetterCase(mode: com.aegis.ime.ui.LetterCase) { keyboardView.caseMode = mode }

    fun showCopyBar(text: String) {
        copyBarActive = true
        copyBarView.show(text)
        Motion.swapIn(copyBarView, candidateView)
        onOverlayChanged()
    }

    fun hideCopyBar() {
        copyBarActive = false
        Motion.swapIn(candidateView, copyBarView)
        onOverlayChanged()
    }

    val copyBarShown: Boolean get() = copyBarView.visibility == VISIBLE

    fun isComposing(): Boolean = composingNow

    fun showCandidates(candidates: List<String>, preedit: String, readings: List<String>, selectedReading: Int = -1) {
        lastCandidates = candidates
        lastReadings = readings
        lastSelectedReading = selectedReading
        preeditView.setText(preedit)
        candidateView.setContent(candidates, preedit)
        composingNow = candidates.isNotEmpty() || preedit.isNotEmpty()
        if (copyBarShown && composingNow) { hideCopyBar(); onCopyDismiss() }
        if (currentPanel === gridView) {
            if (preedit.isEmpty()) showPanel(null)
            else { gridView.setCandidates(candidates); gridView.setReadings(readings, selectedReading) }
        }
    }

    internal fun showExpandedCandidates() {
        if (lastCandidates.isEmpty()) return
        gridView.setCandidates(lastCandidates)
        gridView.setReadings(lastReadings, lastSelectedReading)
        showPanel(gridView)
    }

    internal fun shownCandidateCount(): Int = candidateView.itemCount()

    internal fun expandedReadingTextColorForTest(index: Int): Int? =
        gridView.readingTextColorForTest(index)

    internal fun barChevronGlyph(): String = candidateView.chevronGlyph()

    fun showPanel(panel: View?) {
        val outgoing = currentPanel
        (outgoing as? ResettablePanel)?.takeIf { it !== panel }?.resetToDefault()
        if (outgoing === gridView && panel !== gridView) onExpandClosed()
        currentPanel = panel
        candidateView.setExpanded(panel === gridView)
        if (panel == null) {
            if (outgoing != null) {
                Motion.hide(outgoing, toward = Motion.EnterFrom.BOTTOM) {
                    if (currentPanel == null) {
                        panelContainer.removeAllViews()
                        panelContainer.visibility = GONE
                        keyboardView.visibility = VISIBLE
                        Motion.fadeIn(keyboardView)
                    }
                    Motion.reset(outgoing)
                }
            } else {
                panelContainer.removeAllViews()
                panelContainer.visibility = GONE
                keyboardView.visibility = VISIBLE
            }
        } else {
            panelContainer.removeAllViews()
            setPanelHeight(keyboardView.height.takeIf { it > 0 } ?: dp(250))
            (panel.parent as? ViewGroup)?.removeView(panel)
            panelContainer.addView(
                panel,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            panelContainer.visibility = VISIBLE
            keyboardView.visibility = GONE
            Motion.revealIn(panel, Motion.EnterFrom.BOTTOM)
        }
        onOverlayChanged()
    }

    private enum class BackKind { NONE, PANEL, COPY_BAR, EDIT_BAR }

    fun hasOverlay(): Boolean = currentPanel != null || copyBarActive || editBarActive

    private fun topOverlay(): Pair<BackKind, View?> = when {
        editBarActive -> BackKind.EDIT_BAR to editBarView
        currentPanel != null -> BackKind.PANEL to currentPanel
        copyBarActive -> BackKind.COPY_BAR to copyBarView
        else -> BackKind.NONE to null
    }

    fun closeTopOverlay(): Boolean = when (topOverlay().first) {
        BackKind.EDIT_BAR -> { onEditCancel(); true }
        BackKind.PANEL -> { showPanel(null); true }
        BackKind.COPY_BAR -> { hideCopyBar(); onCopyDismiss(); true }
        BackKind.NONE -> false
    }

    internal fun backTargetKindForTest(): String = topOverlay().first.name

    private fun setPanelHeight(px: Int) {
        val lp = panelContainer.layoutParams
        if (lp.height != px) { lp.height = px; panelContainer.layoutParams = lp }
    }

    internal fun panelHeightPx(): Int = panelContainer.layoutParams.height

    internal fun keyboardHeightPx(): Int = keyboardView.height

    internal fun panelFloorColorForTest(): Int? =
        (panelContainer.background as? android.graphics.drawable.ColorDrawable)?.color

    val panelShown: Boolean get() = panelContainer.visibility == VISIBLE

    fun isPanelShowing(panel: View?): Boolean = panelShown && panel != null && currentPanel === panel

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    internal fun bodyBottomPaddingPx(): Int = body.paddingBottom
    internal fun simulateNavInsetForTest(navBottomPx: Int) {
        lastNavBottomPx = navBottomPx
        applyWindowPadding(navBottomPx, 0, 0)
    }
    internal fun cachedNavBottomForTest(): Int = lastNavBottomPx

    private companion object {
        private var lastNavBottomPx = 0
    }
}
