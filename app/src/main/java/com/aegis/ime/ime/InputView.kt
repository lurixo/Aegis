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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
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

    var onPanelChanged: (View?) -> Unit = {}

    private val preeditView = PreeditView(context)
    private val preeditSlot = CompactDock(context) { resolveDockWidth(it) }.apply { addDockedView(preeditView) }
    private val candidateView = CandidateView(context)
    private val copyBarView = CopyBarView(context)
    private val editBarView = EditBarView(context)
    private val keyboardView = KeyboardView(context)
    private val panelContainer = FrameLayout(context)
    private val gridView = CandidateGridView(context)

    private val body = LinearLayout(context)
    private val bodySlot = CompactDock(context) { resolveDockWidth(it) }.apply { addDockedView(body) }
    private var lastCandidates: List<String> = emptyList()
    private var lastReadings: List<String> = emptyList()
    private var lastSelectedReading = -1
    private var pendingGridBind: Any? = null
    private var composingNow = false
    private var currentPanel: View? = null
    private var copyBarActive = false
    private var editBarActive = false
    private var palette = ImePalette.STATIC_LIGHT
    private var windowNavBottomPx = lastNavBottomPx
    private var windowLeftSystemInsetPx = 0
    private var windowRightSystemInsetPx = 0
    private var measuredBottomExtraPx = dp(BOTTOM_RAISE_DP)
    private var lastDockHeightSpec: LandscapeDockSizing.HeightSpec? = null
    private var latestMeasuredSlotWidthPx = 0

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
            if (editBarView.visibility != VISIBLE || editBarView.alpha < 1f) {
                Motion.showNow(editBarView)
            }
        } else {
            Motion.hideNow(editBarView)
        }
        onOverlayChanged()
    }
    fun isEditBarShowing(): Boolean = editBarView.visibility == VISIBLE
    fun setEditTitle(t: String) { editBarView.setTitle(t) }
    fun setEditText(t: String) { editBarView.setText(t) }
    internal fun dismissEditBarForPanelReturn() {
        editBarActive = false
        Motion.reset(editBarView)
        editBarView.visibility = GONE
    }

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
        addView(preeditSlot, LayoutParams(LayoutParams.MATCH_PARENT, barTopInsetPx()))

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
        addView(bodySlot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        applyWindowPadding(lastNavBottomPx, 0, 0)

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val safeBottom = maxOf(nav.bottom, cut.bottom)
            if (safeBottom > 0) lastNavBottomPx = safeBottom

            applyWindowPadding(safeBottom, maxOf(nav.left, cut.left), maxOf(nav.right, cut.right))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun applyWindowPadding(navBottom: Int, cutLeft: Int, cutRight: Int) {
        val geometryChanged = windowNavBottomPx != navBottom ||
            windowLeftSystemInsetPx != cutLeft || windowRightSystemInsetPx != cutRight
        windowNavBottomPx = navBottom.coerceAtLeast(0)
        windowLeftSystemInsetPx = cutLeft.coerceAtLeast(0)
        windowRightSystemInsetPx = cutRight.coerceAtLeast(0)
        updateBodyPadding(measuredBottomExtraPx)

        if (geometryChanged) requestLayout()
    }

    private fun updateBodyPadding(bottomExtra: Int) {
        val side = dp(SIDE_PADDING_DP)

        val slotWidth = latestMeasuredSlotWidthPx.takeIf { it > 0 }
            ?: bodySlot.width.takeIf { it > 0 }
            ?: resources.configuration.screenWidthDp
                .takeIf { it > 0 && it != Configuration.SCREEN_WIDTH_DP_UNDEFINED }
                ?.let { (it * resources.displayMetrics.density).roundToInt() }
            ?: 0
        val dockLeft = (slotWidth - resolveDockWidth(slotWidth)).coerceAtLeast(0)
        val leftPad = maxOf((windowLeftSystemInsetPx - dockLeft).coerceAtLeast(0), side)
        body.setPadding(
            leftPad,
            0,
            maxOf(windowRightSystemInsetPx, side),
            (lastDockHeightSpec?.navBottom ?: windowNavBottomPx) + bottomExtra.coerceAtLeast(0),
        )
        preeditView.setLeftInset(leftPad.toFloat())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        latestMeasuredSlotWidthPx = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val constrainedLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            heightMode != MeasureSpec.UNSPECIFIED
        val rows = keyboardView.rowCountForSizing()
        val preferredKeyboard = LandscapeDockSizing.preferredKeyboardHeight(rows, resources.displayMetrics.density)
        var spec = if (constrainedLandscape) {
            LandscapeDockSizing.resolveHeight(
                availableHeight = MeasureSpec.getSize(heightMeasureSpec),
                density = resources.displayMetrics.density,
                rowCount = rows,
                preferredKeyboardHeight = preferredKeyboard,
                fractionalRows = keyboardView.usesFractionalCellsForSizing(),
                editBarVisible = editBarView.visibility != GONE,
                navBottom = windowNavBottomPx,
            )
        } else {
            val editVisible = editBarView.visibility != GONE
            LandscapeDockSizing.HeightSpec(
                preeditHeight = dp(PREEDIT_HEIGHT_DP),
                barHeight = dp(BAR_HEIGHT_DP),
                keyboardHeight = preferredKeyboard,
                bottomExtra = dp(BOTTOM_RAISE_DP),
                navBottom = windowNavBottomPx,
                rootHeight = dp(PREEDIT_HEIGHT_DP) + dp(BAR_HEIGHT_DP) * (if (editVisible) 2 else 1) +
                    preferredKeyboard + windowNavBottomPx + dp(BOTTOM_RAISE_DP),
                constrained = false,
                emergency = false,
            )
        }
        if (constrainedLandscape && heightMode == MeasureSpec.EXACTLY) {
            val exactHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
            val surplus = (exactHeight - spec.rootHeight).coerceAtLeast(0)
            if (surplus > 0) {

                spec = spec.copy(
                    bottomExtra = spec.bottomExtra + surplus,
                    rootHeight = exactHeight,
                )
            }
        }
        applyHeightSpec(spec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applyHeightSpec(spec: LandscapeDockSizing.HeightSpec) {
        lastDockHeightSpec = spec
        measuredBottomExtraPx = spec.bottomExtra
        setHeight(preeditSlot, spec.preeditHeight)
        setHeight(editBarView, spec.barHeight)
        setHeight(candidateView, spec.barHeight)
        setHeight(copyBarView, spec.barHeight)
        setHeight(keyboardView, spec.keyboardHeight)
        setPanelHeight(panelHeightFor(spec.keyboardHeight))
        updateBodyPadding(spec.bottomExtra)
    }

    private fun panelHeightFor(keyboardHeight: Int): Int =
        keyboardHeight + if (currentPanel === gridView) coveredBarHeightPx() else 0

    private fun coveredBarHeightPx(): Int = lastDockHeightSpec?.barHeight ?: dp(BAR_HEIGHT_DP)

    private fun setHeight(view: View, px: Int) {
        val lp = view.layoutParams ?: return
        if (lp.height != px) lp.height = px.coerceAtLeast(0)
    }

    private fun resolveDockWidth(slotWidth: Int): Int {
        if (slotWidth <= 0) return 0
        val c = resources.configuration
        val landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        val shortDp = listOf(c.screenWidthDp, c.screenHeightDp)
            .filter { it > 0 && it != Configuration.SCREEN_WIDTH_DP_UNDEFINED }
            .minOrNull()
        val preferred = shortDp?.let { (it * resources.displayMetrics.density).roundToInt() } ?: slotWidth
        return LandscapeDockSizing.resolveWidth(
            landscape = landscape,
            slotWidth = slotWidth,
            preferredSurfaceWidth = preferred,
            density = resources.displayMetrics.density,
            leftSystemInset = windowLeftSystemInsetPx,
            rightSystemInset = windowRightSystemInsetPx,
        ).surfaceWidth
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Motion.cancelCover(panelContainer)
        Motion.reset(keyboardView)
        Motion.reset(preeditView)
        Motion.reset(candidateView)
        Motion.reset(copyBarView)
        Motion.reset(editBarView)
        currentPanel?.let { Motion.reset(it) }
    }

    fun barTopInsetPx(): Int =
        lastDockHeightSpec?.preeditHeight
            ?: preeditSlot.layoutParams?.height?.takeIf { it >= 0 }
            ?: dp(PREEDIT_HEIGHT_DP)

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
        Motion.coverSwap(copyBarView, candidateView, palette.keyboardBg)
        onOverlayChanged()
    }

    fun hideCopyBar() {
        copyBarActive = false
        Motion.coverSwap(candidateView, copyBarView, palette.keyboardBg)
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
        if (copyBarActive && composingNow) { hideCopyBar(); onCopyDismiss() }
        if (currentPanel === gridView) {
            if (preedit.isEmpty()) showPanel(null)
            else if (pendingGridBind == null) bindExpandedCandidates()
        }
    }

    internal fun showExpandedCandidates() {
        if (lastCandidates.isEmpty()) return
        if (pendingGridBind != null && currentPanel === gridView) return
        val deferBinding = isAttachedToWindow &&
            gridView.needsPoolGrowth(lastCandidates.size, lastReadings.size)
        val token = if (deferBinding) Any().also { pendingGridBind = it } else null
        if (token != null) gridView.setSelectionContentVisible(false)
        showPanel(gridView)
        if (token != null) {
            gridView.postOnAnimation {
                if (pendingGridBind !== token || currentPanel !== gridView) return@postOnAnimation
                gridView.post(object : Runnable {
                    override fun run() {
                        if (pendingGridBind !== token || currentPanel !== gridView) return
                        if (!gridView.isAttachedToWindow) {
                            gridView.post(this)
                            return
                        }
                        pendingGridBind = null
                        bindExpandedCandidates()
                    }
                })
            }
        } else {
            bindExpandedCandidates()
        }
    }

    private fun bindExpandedCandidates() {
        if (currentPanel !== gridView) return
        gridView.setCandidates(lastCandidates)
        gridView.setReadings(lastReadings, lastSelectedReading)
        gridView.setSelectionContentVisible(true)
    }

    internal fun shownCandidateCount(): Int = candidateView.itemCount()

    internal fun expandedReadingTextColorForTest(index: Int): Int? =
        gridView.readingTextColorForTest(index)

    internal fun barChevronGlyph(): String = candidateView.chevronGlyph()

    internal fun toolbarShownForTest(): Boolean = candidateView.visibility == VISIBLE

    fun showPanel(panel: View?) = showPanel(panel, animateReveal = true)

    internal fun showPanelImmediately(panel: View) = showPanel(panel, animateReveal = false)

    private fun showPanel(panel: View?, animateReveal: Boolean) {
        val outgoing = currentPanel
        (outgoing as? ResettablePanel)?.takeIf { it !== panel }?.resetToDefault()
        if (outgoing === gridView && panel !== gridView) onExpandClosed()
        currentPanel = panel
        if (panel !== gridView) pendingGridBind = null
        candidateView.setExpanded(panel === gridView)
        val gridCoversBar = panel === gridView
        val restoredBar = outgoing === gridView && !gridCoversBar
        if (panel == null) {
            if (outgoing != null) {
                val snap = Motion.snapshot(outgoing, palette.keyboardBg)
                Motion.reset(outgoing)
                panelContainer.removeAllViews()
                panelContainer.visibility = GONE
                if (restoredBar) candidateView.visibility = VISIBLE
                keyboardView.visibility = VISIBLE
                Motion.reset(keyboardView)
                Motion.coverWith(keyboardView, snap, offsetY = if (restoredBar) -coveredBarHeightPx() else 0)
            } else {
                panelContainer.removeAllViews()
                panelContainer.visibility = GONE
                keyboardView.visibility = VISIBLE
            }
        } else {
            setPanelHeight(
                panelHeightFor(
                    lastDockHeightSpec?.keyboardHeight
                        ?: keyboardView.height.takeIf { it > 0 }
                        ?: LandscapeDockSizing.preferredKeyboardHeight(
                            keyboardView.rowCountForSizing(),
                            resources.displayMetrics.density,
                        ),
                ),
            )
            val snap = when {
                !animateReveal -> null
                outgoing != null && outgoing !== panel -> Motion.snapshot(outgoing, palette.keyboardBg)
                outgoing == null && gridCoversBar && candidateView.visibility == VISIBLE -> expandCoverSnapshot()
                outgoing == null && keyboardView.visibility == VISIBLE -> Motion.snapshot(keyboardView, palette.keyboardBg)
                else -> null
            }
            attachPanel(panel)
            if (gridCoversBar) candidateView.visibility = GONE
            keyboardView.visibility = GONE
            panel.visibility = VISIBLE
            Motion.reset(panel)
            Motion.coverWith(panelContainer, snap)
        }
        onPanelChanged(panel)
        onOverlayChanged()
    }

    private fun expandCoverSnapshot(): Bitmap? {
        if (!isAttachedToWindow || !Motion.enabled()) return null
        val w = candidateView.width
        val barH = candidateView.height
        val kbdH = keyboardView.height
        if (w <= 0 || barH <= 0 || kbdH <= 0) return null
        val bitmap = Bitmap.createBitmap(w, barH + kbdH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(palette.keyboardBg)
        val canvas = Canvas(bitmap)
        candidateView.draw(canvas)
        canvas.translate(0f, barH.toFloat())
        keyboardView.draw(canvas)
        return bitmap
    }

    private fun attachPanel(panel: View) {
        panelContainer.removeAllViews()
        (panel.parent as? ViewGroup)?.removeView(panel)
        panelContainer.addView(
            panel,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        panelContainer.visibility = VISIBLE
    }

    internal fun isExpandedCandidatePanel(panel: View): Boolean = panel === gridView

    internal fun clearEditorTransientUiImmediately() {
        val outgoing = currentPanel
        (outgoing as? ResettablePanel)?.resetToDefault()
        if (outgoing === gridView) onExpandClosed()
        currentPanel = null
        pendingGridBind = null
        candidateView.setExpanded(false)
        if (outgoing === gridView) candidateView.visibility = VISIBLE
        outgoing?.let(Motion::reset)
        Motion.cancelCover(panelContainer)
        panelContainer.removeAllViews()
        panelContainer.visibility = GONE
        Motion.reset(keyboardView)
        keyboardView.visibility = VISIBLE

        editBarActive = false
        Motion.reset(editBarView)
        editBarView.setTitle("")
        editBarView.setText("")
        editBarView.visibility = GONE

        onPanelChanged(null)
        onOverlayChanged()
    }

    private enum class BackKind { NONE, PANEL, EDIT_BAR }

    fun hasOverlay(): Boolean = !copyBarActive && (currentPanel != null || editBarActive)

    private fun topOverlay(): Pair<BackKind, View?> = when {
        copyBarActive -> BackKind.NONE to null
        editBarActive -> BackKind.EDIT_BAR to editBarView
        currentPanel != null -> BackKind.PANEL to currentPanel
        else -> BackKind.NONE to null
    }

    fun closeTopOverlay(): Boolean = when (topOverlay().first) {
        BackKind.EDIT_BAR -> { onEditCancel(); true }
        BackKind.PANEL -> { showPanel(null); true }
        BackKind.NONE -> false
    }

    internal fun backTargetKindForTest(): String = topOverlay().first.name

    private fun setPanelHeight(px: Int) {
        val lp = panelContainer.layoutParams
        if (lp.height != px) { lp.height = px; panelContainer.layoutParams = lp }
    }

    internal fun panelHeightPx(): Int = panelContainer.layoutParams.height

    internal fun keyboardHeightPx(): Int = keyboardView.height

    internal fun keyboardVisualWidthPx(): Int = keyboardView.width
    internal fun keyboardDockWidthPx(): Int = bodySlot.width
    internal fun keyboardVisualLeftPx(): Int = bodySlot.left + body.left + keyboardView.left
    internal fun keyboardVisualRightPx(): Int = keyboardVisualLeftPx() + keyboardView.width
    internal fun toolbarVisualWidthPx(): Int = candidateView.width
    internal fun toolbarDockWidthPx(): Int = bodySlot.width
    internal fun toolbarVisualLeftPx(): Int = bodySlot.left + body.left + candidateView.left
    internal fun toolbarVisualRightPx(): Int = toolbarVisualLeftPx() + candidateView.width
    internal fun editBarVisualLeftPx(): Int = bodySlot.left + body.left + editBarView.left
    internal fun editBarVisualRightPx(): Int = editBarVisualLeftPx() + editBarView.width
    internal fun panelVisualLeftPx(): Int = bodySlot.left + body.left + panelContainer.left
    internal fun panelVisualRightPx(): Int = panelVisualLeftPx() + panelContainer.width
    internal fun preeditVisualLeftPx(): Int = preeditSlot.left + preeditView.left
    internal fun preeditVisualRightPx(): Int = preeditVisualLeftPx() + preeditView.width
    internal fun preeditVisualTopPx(): Int = preeditSlot.top + preeditView.top
    internal fun preeditVisualBottomPx(): Int = preeditVisualTopPx() + preeditView.height
    internal fun toolbarVisualTopPx(): Int = bodySlot.top + body.top + candidateView.top
    internal fun toolbarVisualBottomPx(): Int = toolbarVisualTopPx() + candidateView.height
    internal fun editBarVisualTopPx(): Int = bodySlot.top + body.top + editBarView.top
    internal fun editBarVisualBottomPx(): Int = editBarVisualTopPx() + editBarView.height
    internal fun keyboardVisualTopPx(): Int = bodySlot.top + body.top + keyboardView.top
    internal fun keyboardVisualBottomPx(): Int = keyboardVisualTopPx() + keyboardView.height
    internal fun panelVisualTopPx(): Int = bodySlot.top + body.top + panelContainer.top
    internal fun panelVisualBottomPx(): Int = panelVisualTopPx() + panelContainer.height
    internal fun dockSurfaceWidthPx(): Int = body.width
    internal fun dockSurfaceLeftPx(): Int = bodySlot.left + body.left
    internal fun dockSurfaceRightPx(): Int = dockSurfaceLeftPx() + body.width
    internal fun dockSurfaceTopPx(): Int = bodySlot.top + body.top
    internal fun dockSurfaceBottomPx(): Int = dockSurfaceTopPx() + body.height
    internal fun dockHeightSpecForTest(): LandscapeDockSizing.HeightSpec? = lastDockHeightSpec
    internal fun keyboardMinimumKeyWidthPxForTest(): Float = keyboardView.minimumKeyWidthForTest()

    internal fun keyboardActionBoundsForTest(action: KeyAction): RectF? =
        keyboardView.boundsOfActionForTest(action)?.let { local ->
            RectF(
                keyboardVisualLeftPx() + local.left,
                keyboardVisualTopPx() + local.top,
                keyboardVisualLeftPx() + local.right,
                keyboardVisualTopPx() + local.bottom,
            )
        }

    internal fun keyboardLabelBoundsForTest(label: String): RectF? =
        keyboardView.boundsOfLabelForTest(label)?.let { local ->
            RectF(
                keyboardVisualLeftPx() + local.left,
                keyboardVisualTopPx() + local.top,
                keyboardVisualLeftPx() + local.right,
                keyboardVisualTopPx() + local.bottom,
            )
        }

    internal fun tapKeyboardActionForTest(action: KeyAction): Boolean =
        keyboardView.centerOfActionForTest(action)?.let { (x, y) ->
            dispatchTapForTest(keyboardVisualLeftPx() + x, keyboardVisualTopPx() + y)
        } ?: false

    internal fun tapKeyboardLabelForTest(label: String): Boolean =
        keyboardView.centerOfLabelForTest(label)?.let { (x, y) ->
            dispatchTapForTest(keyboardVisualLeftPx() + x, keyboardVisualTopPx() + y)
        } ?: false

    internal fun tapFirstCandidateForTest(): Boolean =
        candidateView.centerOfCandidateForTest(0)?.let { (x, y) ->
            dispatchTapForTest(toolbarVisualLeftPx() + x, toolbarVisualTopPx() + y)
        } ?: false

    internal fun tapExpandCandidatesForTest(): Boolean {
        val bounds = candidateView.expandControlBoundsForTest()
        return dispatchTapForTest(
            toolbarVisualLeftPx() + bounds.centerX(),
            toolbarVisualTopPx() + bounds.centerY(),
        )
    }

    internal fun editConfirmBoundsForTest(): Rect = boundsInRoot(editBarView.confirmButtonForTest())
    internal fun editBarForTest(): EditBarView = editBarView
    internal fun tapEditConfirmForTest(): Boolean {
        val b = editConfirmBoundsForTest()
        return dispatchTapForTest(b.exactCenterX(), b.exactCenterY())
    }

    internal fun expandedPanelControlBoundsForTest(): List<Rect> = listOf(
        gridView.returnButtonForTest(),
        gridView.backspaceButtonForTest(),
        gridView.clearButtonForTest(),
    ).map(::boundsInRoot)

    internal fun expandedGridForTest(): CandidateGridView = gridView
    internal fun panelDescendantBoundsForTest(view: View): Rect = boundsInRoot(view)

    private fun boundsInRoot(descendant: View): Rect {
        var x = 0
        var y = 0
        var current: View? = descendant
        while (current != null && current !== this) {
            x += current.left + current.translationX.roundToInt()
            y += current.top + current.translationY.roundToInt()
            val parentView = current.parent as? View
            if (parentView != null) {
                x -= parentView.scrollX
                y -= parentView.scrollY
            }
            current = parentView
        }
        return Rect(x, y, x + descendant.width, y + descendant.height)
    }

    private fun dispatchTapForTest(x: Float, y: Float): Boolean {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val accepted = dispatchTouchEvent(down)
            dispatchTouchEvent(up) && accepted
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    internal fun isCompactLandscapeDock(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            body.width > 0 && bodySlot.width > body.width

    internal fun dockSurfaceBoundsInWindow(): Rect {
        return boundsInWindow(body)
    }

    internal fun dockTouchableBoundsInWindow(): Rect =
        Rect(dockSurfaceBoundsInWindow()).apply { union(preeditSurfaceBoundsInWindow()) }

    internal fun preeditSurfaceBoundsInWindow(): Rect = boundsInWindow(preeditView)

    private fun boundsInWindow(view: View): Rect {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    internal fun panelFloorColorForTest(): Int? =
        (panelContainer.background as? android.graphics.drawable.ColorDrawable)?.color

    val panelShown: Boolean get() = panelContainer.visibility == VISIBLE

    fun isPanelShowing(panel: View?): Boolean = panelShown && panel != null && currentPanel === panel

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class CompactDock(
        context: Context,
        private val widthResolver: (Int) -> Int,
    ) : FrameLayout(context) {
        fun addDockedView(child: View) {
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val slotWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
            val visibleChildren = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
            if (slotWidth == 0 || visibleChildren.isEmpty()) {
                setMeasuredDimension(slotWidth, 0)
                return
            }
            val childWidth = widthResolver(slotWidth)
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = MeasureSpec.getSize(heightMeasureSpec)
            var measuredHeight = 0
            for (child in visibleChildren) {
                val childHeightSpec = when (heightMode) {
                    MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
                    MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.AT_MOST)
                    else -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                }
                child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY), childHeightSpec)
                measuredHeight = maxOf(measuredHeight, child.measuredHeight)
            }
            val resolvedHeight = resolveSize(measuredHeight, heightMeasureSpec)
            if (visibleChildren.any { it.measuredHeight > resolvedHeight }) {

                for (child in visibleChildren) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(resolvedHeight, MeasureSpec.EXACTLY),
                    )
                }
            }
            setMeasuredDimension(resolveSize(slotWidth, widthMeasureSpec), resolvedHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                val childLeft = (width - childWidth).coerceAtLeast(0)
                val childTop = (height - childHeight).coerceAtLeast(0)
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
            }
        }

    }

    internal fun bodyBottomPaddingPx(): Int = body.paddingBottom
    internal fun simulateNavInsetForTest(navBottomPx: Int) {
        lastNavBottomPx = navBottomPx
        applyWindowPadding(navBottomPx, 0, 0)
    }
    internal fun bodyLeftPaddingPxForTest(): Int = body.paddingLeft
    internal fun bodyRightPaddingPxForTest(): Int = body.paddingRight
    internal fun cachedNavBottomForTest(): Int = lastNavBottomPx

    private companion object {
        private const val SIDE_PADDING_DP = 4
        private const val PREEDIT_HEIGHT_DP = 26
        private const val BAR_HEIGHT_DP = 44
        private const val BOTTOM_RAISE_DP = 28

        private var lastNavBottomPx = 0
    }
}
