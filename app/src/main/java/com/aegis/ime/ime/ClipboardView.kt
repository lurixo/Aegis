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

import com.aegis.ime.R
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.WeakHashMap
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.ClipboardPanelState.Tab

class ClipboardView(context: Context) : FrameLayout(context), ResettablePanel, CoversToolbar, KeyHapticsAware, LayeredPanel {

    var onPick: (String) -> Unit = {}
    var onBack: () -> Unit = {}
    var historyProvider: () -> List<ClipEntry> = { emptyList() }
    var categoriesProvider: () -> List<String> = { emptyList() }
    var phrasesInProvider: (String) -> List<String> = { emptyList() }
    var phraseNoteProvider: (String, String) -> String = { _, _ -> "" }
    override var hapticEnabled = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val edgeInset = (ImeShapes.edgeInsetDp * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private var ACCENT = palette.candidateFirst
    private var RED = palette.onErrorContainer
    private var GREY_PILL = palette.chipBg
    private var TEXT_DARK = palette.keyLabel
    private var HINT = palette.keyHint
    private var CARD = palette.keySurface
    private var BG = palette.keyboardBg

    fun applyPalette(p: ImePalette) {
        val changed = p != palette
        palette = p
        ACCENT = p.candidateFirst; RED = p.onErrorContainer
        GREY_PILL = p.chipBg
        TEXT_DARK = p.keyLabel; HINT = p.keyHint; CARD = p.keySurface
        BG = p.keyboardBg
        main.setBackgroundColor(BG)
        if (changed) {
            forceNextRebuild = true
        }
        refresh(animate = false)
    }

    private val st = ClipboardPanelState()
    private var phraseCat = ""
    private var categoryScrollX = 0
    private var revealSelectedCategory = false
    private var listScrollY = 0
    private var listScrollRestoreTarget = 0
    private var listScrollRestoreActive = false
    private var applyingListScroll = false
    private var listTouchActive = false

    private var renderedTab: ClipboardPanelState.Tab? = null
    private var tabTransitions = 0
    private var renderedMode = -1
    private var pendingCategoryFade = false
    private var pendingCategorySlideFromX = 0f
    private var contentFades = 0
    private var forceNextRebuild = false
    private var hasRenderedOnce = false
    private var renderedExpanded: String? = null
    private var renderedSelectedSig: List<String> = emptyList()
    private var renderedEntriesSig: List<String> = emptyList()
    private var renderedCategoriesSig: List<String> = emptyList()
    private var renderedCategorySig = ""

    fun showPhraseTab(category: String) {
        val switching = st.switchTab(ClipboardPanelState.Tab.PHRASE)
        st.collapse()
        val retarget = category.isNotEmpty() && phraseCat != category && category in categoriesProvider()
        if (retarget) phraseCat = category
        if (switching || retarget) {
            forceNextRebuild = true
            revealSelectedCategory = true
        }
        refresh(animate = false)
    }

    fun reopenAfterInline(category: String) {
        st.collapse()
        if (st.tab == ClipboardPanelState.Tab.PHRASE) {
            if (category.isNotEmpty() && category in categoriesProvider()) phraseCat = category
            forceNextRebuild = true
            revealSelectedCategory = true
        }
        refresh(animate = false)
    }

    private val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
    private val overlay = object : FrameLayout(context) {
        private var backdropPointerId = MotionEvent.INVALID_POINTER_ID
        private var backdropTracking = false

        init {
            visibility = GONE
        }

        fun resetBackdropGesture() {
            backdropPointerId = MotionEvent.INVALID_POINTER_ID
            backdropTracking = false
            isPressed = false
        }

        private fun contentContains(x: Float, y: Float): Boolean {
            val content = getChildAt(0) ?: return false
            return x >= content.left + content.translationX &&
                x < content.right + content.translationX &&
                y >= content.top + content.translationY &&
                y < content.bottom + content.translationY
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetBackdropGesture()
                    if (!contentContains(event.x, event.y)) {
                        backdropPointerId = event.getPointerId(0)
                        backdropTracking = true
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> if (backdropTracking) return true
                MotionEvent.ACTION_POINTER_UP -> if (backdropTracking) {
                    if (event.getPointerId(event.actionIndex) == backdropPointerId) {
                        val replacement = (0 until event.pointerCount).firstOrNull { it != event.actionIndex }
                        if (replacement == null) {
                            resetBackdropGesture()
                            performClick()
                        } else {
                            backdropPointerId = event.getPointerId(replacement)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> if (backdropTracking) {
                    val matches = event.getPointerId(event.actionIndex) == backdropPointerId
                    resetBackdropGesture()
                    if (matches) performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> if (backdropTracking) {
                    resetBackdropGesture()
                    return true
                }
            }
            return super.dispatchTouchEvent(event)
        }

        override fun performClick(): Boolean {
            if (visibility != VISIBLE || !isClickable) return false
            super.performClick()
            hideOverlay()
            return true
        }
    }
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
    private val listScroll = object : ScrollView(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    listTouchActive = true
                    if (listScrollRestoreActive) {
                        listScrollRestoreActive = false
                        listScrollY = scrollY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> listTouchActive = false
            }
            val handled = super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !handled) listTouchActive = false
            return handled
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            if (!applyingListScroll && !listScrollRestoreActive) listScrollY = t
            scheduleListAppendIfNeeded()
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            scheduleListAppendIfNeeded()
            if (!listScrollRestoreActive) return
            val max = (listColumn.height - height).coerceAtLeast(0)
            val target = listScrollRestoreTarget.coerceIn(0, max)
            if (scrollY != target) {
                applyingListScroll = true
                scrollTo(0, target)
                applyingListScroll = false
            }
            if (scrollY == target && pendingListAppend == null) {
                listScrollRestoreActive = false
                listScrollY = target
            }
        }
    }.apply {
        setPadding(edgeInset, 0, edgeInset, 0)
        clipToPadding = false
        addView(listColumn)
    }
    private val fixedChromeOriginalHeights = WeakHashMap<View, Int>()
    private val fixedDescendantOriginalHeights = WeakHashMap<View, Int>()
    private val fixedChromeOriginalPadding = WeakHashMap<View, IntArray>()
    private val fixedDescendantOriginalVisibility = WeakHashMap<View, Int>()
    private val fixedDescendantOriginalTextSize = WeakHashMap<TextView, Float>()
    private val fixedChromePreferredHeights = WeakHashMap<View, Int>()
    private val fixedChromeMinimumHeights = WeakHashMap<View, Int>()
    private var fixedChromePreferredWidth = -1
    private var fixedChromeCompressed: Boolean? = null
    private var listRenderGeneration = 0
    private var pendingListAppend: Runnable? = null
    private var pagedEntries: List<String> = emptyList()
    private var pagedRow: ((String, Int) -> View)? = null
    private var loadedRows = 0
    private val immediateActionFeedback = HashMap<View, ImeKeyFeedback>()

    private fun bindImmediateAction(
        view: View,
        stateColor: Int,
        faceColor: Int = CARD,
        radiusDp: Float = ImeShapes.toolbarFeedbackRadiusDp,
        faceInsetDp: Float = ImeKeyFeedback.DEFAULT_FACE_INSET_DP,
        faceAtRightEdge: Boolean = false,
    ): ImeKeyFeedback = ImeKeyFeedback(
        view,
        faceColor,
        stateColor,
        faceInsetDp = faceInsetDp,
        radiusDp = radiusDp,
        faceInsetLeftPx = if (faceAtRightEdge) 2f * faceInsetDp * density else null,
        faceInsetRightPx = if (faceAtRightEdge) 0f else null,
    ).also { feedback ->
        feedback.bind { hapticEnabled }
        immediateActionFeedback[view] = feedback
    }

    private fun forgetImmediateActions(root: View) {
        immediateActionFeedback.remove(root)?.reset()
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) forgetImmediateActions(root.getChildAt(i))
        }
    }

    private fun resetImmediateActions() {
        immediateActionFeedback.values.forEach(ImeKeyFeedback::reset)
    }

    private companion object {
        const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DISPLAY_CAP = 2000
        const val INITIAL_SYNC_ROWS = 12
        const val APPEND_ROWS_PER_FRAME = 12
        const val LIST_LOOKAHEAD_VIEWPORTS = 2

        const val TAB_PILL_DP = 76
        const val SHRINK_PASSES = 4
        const val CATEGORY_BAR_HEIGHT_DP = 40
        const val CATEGORY_TAB_PADDING_DP = 12
        const val CATEGORY_TAB_MIN_WIDTH_DP = 48
        const val ANCHORED_MENU_EDGE_DP = 8
        const val ANCHORED_MENU_GAP_DP = 4
    }

    private fun preview(s: String): CharSequence = if (s.length > DISPLAY_CAP) s.substring(0, DISPLAY_CAP) + "…" else s

    private var clipIndex: Map<String, ClipEntry> = emptyMap()

    private fun clipKeys(): List<String> {
        val entries = historyProvider()
        val index = HashMap<String, ClipEntry>(entries.size * 2 + 1)
        val keys = ArrayList<String>(entries.size)
        for (e in entries) {
            keys.add(e.key)
            index[e.key] = e
        }
        clipIndex = index
        return keys
    }

    private fun clipTab(): Boolean = st.tab == Tab.CLIPBOARD

    private fun entryDisplay(key: String): String {
        if (!clipTab()) return key
        val entry = clipIndex[key] ?: return key
        if (!entry.available) {
            return context.getString(R.string.clipboard_entry_lost_format, entry.hash.orEmpty().take(8))
        }
        return entry.preview()
    }

    private fun entryBody(key: String): String? {
        if (!clipTab()) return key
        val entry = clipIndex[key] ?: return if (ClipEntry.isReferenceKey(key)) null else key
        return entry.body()
    }

    private fun ll(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    init {
        addView(main, FrameLayout.LayoutParams(MP, MP))
        addView(overlay, FrameLayout.LayoutParams(MP, MP))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return super.dispatchTouchEvent(ev)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && main.childCount > 0) {
            val available = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
            val widthCap = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
            val fixed = (0 until main.childCount).map { main.getChildAt(it) }.filter { it !== listScroll }
            val preferredGeometryStale = fixedChromePreferredWidth != widthCap ||
                fixed.any { fixedChromePreferredHeights[it] == null }
            if (preferredGeometryStale) {

                if (fixedChromeCompressed == true) fixed.forEach(::restoreFixedChromeState)
                fixedChromePreferredHeights.clear()
                fixedChromeMinimumHeights.clear()
                for (child in fixed) {
                    val lp = child.layoutParams as LinearLayout.LayoutParams
                    val original = fixedChromeOriginalHeights.getOrPut(child) { lp.height }
                    val preferredHeight = if (original >= 0) original else {
                        child.measure(
                            MeasureSpec.makeMeasureSpec(widthCap, MeasureSpec.AT_MOST),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        )
                        child.measuredHeight
                    }
                    fixedChromePreferredHeights[child] = preferredHeight
                }
                for (child in fixed) {
                    fixedChromeMinimumHeights[child] = minimumReadableHeight(child)
                        .coerceAtMost(fixedChromePreferredHeights[child] ?: 0)
                }
                fixedChromePreferredWidth = widthCap
            }
            val preferred = fixed.associateWith { fixedChromePreferredHeights[it] ?: 0 }
            val desiredFixed = preferred.values.sum().coerceAtLeast(0)
            val listReserve = minOf(dp(32), (available * 0.25f).roundToInt()).coerceAtMost(available)
            val fixedBudget = (available - listReserve).coerceAtLeast(0)
            val compressed = desiredFixed > fixedBudget
            val targetHeights = mutableMapOf<View, Int>()
            if (compressed) {
                val minima = fixed.associateWith { child -> fixedChromeMinimumHeights[child] ?: 0 }
                val minimumTotal = minima.values.sum()
                if (minimumTotal <= fixedBudget) {

                    val remaining = fixedBudget - minimumTotal
                    val slackTotal = fixed.sumOf { child ->
                        ((preferred[child] ?: 0) - (minima[child] ?: 0)).coerceAtLeast(0)
                    }
                    var assigned = 0
                    for ((index, child) in fixed.withIndex()) {
                        val minimum = minima[child] ?: 0
                        val extra = if (index == fixed.lastIndex) {
                            remaining - assigned
                        } else if (slackTotal > 0) {
                            (remaining * (((preferred[child] ?: 0) - minimum).coerceAtLeast(0)).toFloat() / slackTotal)
                                .roundToInt()
                                .coerceAtMost(remaining - assigned)
                        } else {
                            0
                        }
                        targetHeights[child] = minimum + extra
                        assigned += extra
                    }
                } else {

                    var assigned = 0
                    for ((index, child) in fixed.withIndex()) {
                        val target = if (index == fixed.lastIndex) {
                            fixedBudget - assigned
                        } else if (minimumTotal > 0) {
                            (fixedBudget * (minima[child] ?: 0).toFloat() / minimumTotal).roundToInt()
                                .coerceAtMost(fixedBudget - assigned)
                        } else {
                            0
                        }
                        targetHeights[child] = target
                        assigned += target
                    }
                }
            }
            if (!compressed && fixedChromeCompressed == true) fixed.forEach(::restoreFixedChromeState)
            for (child in fixed) {
                val lp = child.layoutParams as LinearLayout.LayoutParams
                val original = fixedChromeOriginalHeights[child] ?: lp.height
                val targetHeight = if (compressed) targetHeights[child] ?: 0 else original
                if (lp.height != targetHeight) lp.height = targetHeight
                if (compressed) {
                    adaptFixedChrome(
                        child,
                        targetHeight = if (targetHeight >= 0) targetHeight else preferred[child] ?: 0,
                    )
                }
            }
            fixedChromeCompressed = compressed
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun restoreFixedChromeState(root: View) {
        fixedChromeOriginalHeights[root]?.let { original ->
            root.layoutParams?.let { lp -> if (lp.height != original) lp.height = original }
        }
        fixedDescendantOriginalHeights[root]?.let { original ->
            root.layoutParams?.let { lp -> if (lp.height != original) lp.height = original }
        }
        fixedChromeOriginalPadding[root]?.let { original ->
            setPaddingIfChanged(root, original[0], original[1], original[2], original[3])
        }
        fixedDescendantOriginalVisibility[root]?.let { if (root.visibility != it) root.visibility = it }
        if (root is TextView) {
            fixedDescendantOriginalTextSize[root]?.let { original ->
                if (root.textSize != original) root.setTextSize(TypedValue.COMPLEX_UNIT_PX, original)
            }
        }
        val group = root as? ViewGroup ?: return
        for (i in 0 until group.childCount) restoreFixedChromeState(group.getChildAt(i))
    }

    private fun minimumReadableHeight(root: View): Int {
        if (root.visibility == GONE) return 0
        if (root is TextView && !root.text.isNullOrEmpty()) return root.lineHeight.coerceAtLeast(1)
        val group = root as? ViewGroup ?: return if (root.isClickable) dp(20) else 1
        val children = (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility != GONE }
        if (children.isEmpty()) return if (root.isClickable) dp(20) else 1
        val childMinimum = children.maxOf(::minimumReadableHeight)

        return childMinimum
    }

    private fun setPaddingIfChanged(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (view.paddingLeft != left || view.paddingTop != top ||
            view.paddingRight != right || view.paddingBottom != bottom
        ) {
            view.setPadding(left, top, right, bottom)
        }
    }

    private fun adaptFixedChrome(root: View, targetHeight: Int) {
        val group = root as? ViewGroup ?: return
        fun scaledPadding(view: View, available: Int): Int {
            val originalPadding = fixedChromeOriginalPadding.getOrPut(view) {
                intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            }

            setPaddingIfChanged(view, originalPadding[0], 0, originalPadding[2], 0)
            return available
        }

        fun adaptDescendant(view: View, available: Int) {
            val lp = view.layoutParams ?: return
            val original = fixedDescendantOriginalHeights.getOrPut(view) { lp.height }
            val assigned = if (original > 0) minOf(original, available) else available
            if (lp.height != assigned) lp.height = assigned
            if (view is TextView) {
                val originalTextSize = fixedDescendantOriginalTextSize.getOrPut(view) { view.textSize }
                val targetTextSize = if (assigned > 0) {

                    val railScale = minOf(1f, assigned.toFloat() / dp(40).coerceAtLeast(1))
                    (originalTextSize * railScale).coerceAtLeast(1f)
                } else {
                    originalTextSize
                }
                if (view.textSize != targetTextSize) {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetTextSize)
                }
            }
            (view as? ViewGroup)?.let { vg ->
                val actual = if (assigned >= 0) assigned else available
                val childContent = scaledPadding(view, actual.coerceAtLeast(0))
                val allChildren = (0 until vg.childCount).map { vg.getChildAt(it) }
                allChildren.forEach { child ->
                    fixedDescendantOriginalVisibility.getOrPut(child) { child.visibility }
                }
                val authoredVisible = allChildren.filter {
                    fixedDescendantOriginalVisibility[it] != GONE
                }
                if (vg is LinearLayout && vg.orientation == LinearLayout.VERTICAL && authoredVisible.isNotEmpty()) {
                    val requiredTextLines = authoredVisible.sumOf { child ->
                        (child as? TextView)?.lineHeight?.coerceAtLeast(1) ?: 1
                    }
                    if (authoredVisible.size > 1 && childContent < requiredTextLines) {

                        if (authoredVisible.first().visibility == GONE) authoredVisible.first().visibility = VISIBLE
                        authoredVisible.drop(1).forEach { if (it.visibility != GONE) it.visibility = GONE }
                        adaptDescendant(authoredVisible.first(), childContent)
                    } else {

                        authoredVisible.forEach { child ->
                            val authored = fixedDescendantOriginalVisibility[child] ?: VISIBLE
                            if (child.visibility != authored) child.visibility = authored
                        }
                        var remaining = childContent
                        for ((index, child) in authoredVisible.withIndex()) {
                            val share = if (index == authoredVisible.lastIndex) remaining else childContent / authoredVisible.size
                            adaptDescendant(child, share)
                            remaining = (remaining - share).coerceAtLeast(0)
                        }
                    }
                } else {
                    for (child in authoredVisible) adaptDescendant(child, childContent)
                }
            }
        }
        val contentHeight = scaledPadding(root, targetHeight.coerceAtLeast(0))
        for (i in 0 until group.childCount) adaptDescendant(group.getChildAt(i), contentHeight)
    }

    fun reset() {
        invalidateListRender()
        revealSelectedCategory = false
        listTouchActive = false
        resetImmediateActions()
        st.reset(); hideOverlayImmediately()
    }

    override fun resetToDefault() {
        reset()
        phraseCat = ""
        categoryScrollX = 0
        listScrollY = 0
        listScrollRestoreTarget = 0
        listScrollRestoreActive = false
        listScroll.scrollTo(0, 0)
        listScroll.fling(0)
    }

    override fun hasInnerLayer(): Boolean = innerLayerCloser() != null

    override fun closeInnerLayer(): Boolean {
        val close = innerLayerCloser() ?: return false
        close()
        return true
    }

    private fun innerLayerCloser(): (() -> Unit)? = when {
        overlay.visibility == VISIBLE -> ::hideOverlay
        else -> null
    }

    internal fun isClipboardTabForTest(): Boolean = st.tab == ClipboardPanelState.Tab.CLIPBOARD
    internal fun phraseCatForTest(): String = phraseCat
    internal fun switchTabForTest(toClipboard: Boolean) {
        st.switchTab(if (toClipboard) ClipboardPanelState.Tab.CLIPBOARD else ClipboardPanelState.Tab.PHRASE)
        refresh()
    }
    internal fun forcePhrasesStateForTest(cat: String) { st.switchTab(ClipboardPanelState.Tab.PHRASE); phraseCat = cat }
    internal fun listScrollYForTest(): Int = listScroll.scrollY
    internal fun listRowViewForTest(index: Int): View? = listColumn.getChildAt(index)
    internal fun listRowCountForTest(): Int = listColumn.childCount
    internal fun initialSyncRowsForTest(): Int = INITIAL_SYNC_ROWS
    internal fun displayCapForTest(): Int = DISPLAY_CAP
    internal fun runPendingListAppendForTest(): Boolean {
        val r = pendingListAppend ?: return false
        removeCallbacks(r)
        pendingListAppend = null
        r.run()
        return true
    }
    internal fun fixedChromeViewsForTest(): List<View> =
        (0 until main.childCount).map { main.getChildAt(it) }.filter { it !== listScroll }
    internal fun listViewportForTest(): View = listScroll
    internal fun listRowTextsForTest(): List<String> {
        val out = ArrayList<String>()
        fun firstText(v: View): String? {
            if (v is TextView) return v.text?.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) firstText(v.getChildAt(i))?.let { return it }
            return null
        }
        for (i in 0 until listColumn.childCount) firstText(listColumn.getChildAt(i))?.let { out.add(it) }
        return out
    }

    fun refresh() = refresh(animate = true)

    private fun refresh(animate: Boolean) {
        val tabChanged = renderedTab != null && renderedTab != st.tab
        val previousMode = renderedMode
        val mode = currentRenderMode()
        val modeChanged = previousMode != -1 && previousMode != mode
        val categoryChanged = pendingCategoryFade
        pendingCategoryFade = false
        val transition = tabChanged || modeChanged || categoryChanged
        val forced = forceNextRebuild
        val skip = hasRenderedOnce && !forced && !transition && pagedRow != null && renderedContentMatchesCurrent()
        forceNextRebuild = false
        if (skip) {
            renderedTab = st.tab
            renderedMode = mode
            return
        }
        renderedTab = st.tab
        renderedMode = mode
        if (!transition && !forced && canReconcileEntriesOnly()) {
            reconcileEntriesInPlace()
            return
        }
        if (tabChanged) tabTransitions++
        if (transition) {
            listScrollY = 0
            listScrollRestoreTarget = 0
        } else {
            listScrollRestoreTarget = listScrollY
        }
        listScrollRestoreActive = true
        if (animate && transition && main.isShown) {
            contentFades++
            when {
                modeChanged && !tabChanged && !categoryChanged && (mode == 1 || previousMode == 1) ->
                    slideTransition(if (mode == 1) -selectLeadingGap().toFloat() else selectLeadingGap().toFloat())
                tabChanged ->
                    slideTransition(if (st.tab == Tab.CLIPBOARD) -selectLeadingGap().toFloat() else selectLeadingGap().toFloat())
                categoryChanged ->
                    slideTransition(pendingCategorySlideFromX)
                else ->
                    Motion.coverThrough(listScroll, BG) { rebuildContent() }
            }
        } else {
            listScroll.animate().cancel()
            listScroll.translationX = 0f
            rebuildContent()
        }
    }

    private fun renderedContentMatchesCurrent(): Boolean {
        if (st.expanded != renderedExpanded) return false
        if (st.selected.toList() != renderedSelectedSig) return false
        val categories = if (st.tab == Tab.PHRASE) categoriesProvider() else emptyList()
        if (categories != renderedCategoriesSig) return false
        val category = if (st.tab == Tab.PHRASE) currentCategory(categories) else ""
        if (category != renderedCategorySig) return false
        val entries = when {
            st.tab == Tab.CLIPBOARD -> clipKeys()
            else -> phrasesInProvider(category)
        }
        return entries == renderedEntriesSig
    }

    private fun recordRenderSignature(categories: List<String>, category: String, entries: List<String>) {
        renderedCategoriesSig = categories.toList()
        renderedCategorySig = category
        renderedEntriesSig = entries.toList()
    }

    private fun slideTransition(fromX: Float) {
        listScroll.animate().cancel()
        rebuildContent()
        if (!listScroll.isAttachedToWindow || !Motion.enabled()) {
            listScroll.translationX = 0f
            return
        }
        listScroll.translationX = fromX
        listScroll.animate()
            .translationX(0f)
            .setDuration(Motion.SHORT2)
            .setInterpolator(Motion.STANDARD_DECEL)
            .start()
    }

    private fun rebuildContent() {
        invalidateListRender()
        fixedChromeOriginalHeights.clear()
        fixedDescendantOriginalHeights.clear()
        fixedChromeOriginalPadding.clear()
        fixedDescendantOriginalVisibility.clear()
        fixedDescendantOriginalTextSize.clear()
        fixedChromePreferredHeights.clear()
        fixedChromeMinimumHeights.clear()
        fixedChromePreferredWidth = -1
        fixedChromeCompressed = null
        forgetImmediateActions(main)
        main.removeAllViews()
        when {
            else -> buildNormal()
        }
        renderedExpanded = st.expanded
        renderedSelectedSig = st.selected.toList()
        hasRenderedOnce = true
    }

    private fun canReconcileEntriesOnly(): Boolean {
        if (!hasRenderedOnce || st.selectMode) return false
        if (st.expanded != renderedExpanded) return false
        if (st.selected.toList() != renderedSelectedSig) return false
        val categories = if (st.tab == Tab.PHRASE) categoriesProvider() else emptyList()
        if (categories != renderedCategoriesSig) return false
        val category = if (st.tab == Tab.PHRASE) currentCategory(categories) else ""
        return category == renderedCategorySig
    }

    private fun reconcileEntriesInPlace() {
        val categories = if (st.tab == Tab.PHRASE) categoriesProvider() else emptyList()
        val category = if (st.tab == Tab.PHRASE) currentCategory(categories) else ""
        val entries = if (st.tab == Tab.CLIPBOARD) clipKeys() else phrasesInProvider(category)
        val rowFactory: (String, Int) -> View = { e, i -> card(e, i, category) }
        if (pendingListAppend == null && loadedRows in 1..renderedEntriesSig.size && listColumn.childCount == loadedRows) {
            val reuse = HashMap<String, ArrayDeque<View>>()
            for (i in 0 until loadedRows) {
                val child = listColumn.getChildAt(i) ?: continue
                reuse.getOrPut(renderedEntriesSig[i]) { ArrayDeque() }.addLast(child)
            }
            val grown = (entries.size - renderedEntriesSig.size).coerceIn(0, APPEND_ROWS_PER_FRAME.coerceAtLeast(1))
            val keep = min(entries.size, loadedRows + grown)
            val nextRows = ArrayList<View>(keep)
            for (i in 0 until keep) {
                nextRows.add(reuse[entries[i]]?.removeFirstOrNull() ?: rowFactory(entries[i], i))
            }
            for (queue in reuse.values) {
                for (child in queue) forgetImmediateActions(child)
            }
            listColumn.removeAllViews()
            pagedEntries = entries
            pagedRow = rowFactory
            loadedRows = 0
            if (entries.isEmpty()) {
                listColumn.addView(emptyHint())
            } else {
                for (child in nextRows) listColumn.addView(child)
                loadedRows = keep
                scheduleListAppendIfNeeded()
            }
        } else {
            val target = listScroll.scrollY
            invalidateListRender()
            listScrollY = target
            listScrollRestoreTarget = target
            listScrollRestoreActive = !listTouchActive
            populateListRows(entries, rowFactory)
        }
        recordRenderSignature(categories, category, entries)
        renderedExpanded = st.expanded
        renderedSelectedSig = st.selected.toList()
    }

    private fun currentRenderMode(): Int = when {
        else -> 0
    }

    internal fun tabTransitionsForTest(): Int = tabTransitions
    internal fun contentFadesForTest(): Int = contentFades

    private fun cancelPendingListAppend() {
        pendingListAppend?.let { removeCallbacks(it) }
        pendingListAppend = null
    }

    private fun invalidateListRender() {
        cancelPendingListAppend()
        listRenderGeneration++
        pagedEntries = emptyList()
        pagedRow = null
        loadedRows = 0
    }

    private fun populateListRows(entries: List<String>, row: (String, Int) -> View) {
        forgetImmediateActions(listColumn)
        listColumn.removeAllViews()
        pagedEntries = entries
        pagedRow = row
        loadedRows = 0
        if (entries.isEmpty()) {
            listColumn.addView(emptyHint())
            return
        }
        appendListRows(INITIAL_SYNC_ROWS.coerceAtLeast(1))
        scheduleListAppendIfNeeded()
    }

    private fun appendListRows(end: Int) {
        val row = pagedRow ?: return
        val limit = min(end, pagedEntries.size)
        for (i in loadedRows until limit) listColumn.addView(row(pagedEntries[i], i))
        loadedRows = maxOf(loadedRows, limit)
    }

    private fun listNeedsMoreRows(): Boolean {
        if (pagedRow == null || loadedRows >= pagedEntries.size) return false
        val viewport = listScroll.height
        if (viewport <= 0) return true
        val anchor = if (listScrollRestoreActive) maxOf(listScroll.scrollY, listScrollRestoreTarget) else listScroll.scrollY
        return listColumn.height < anchor + viewport * LIST_LOOKAHEAD_VIEWPORTS
    }

    private fun scheduleListAppendIfNeeded() {
        if (pendingListAppend != null || !listNeedsMoreRows()) return
        val generation = listRenderGeneration
        val r = Runnable {
            if (generation != listRenderGeneration) return@Runnable
            pendingListAppend = null
            appendListRows(loadedRows + APPEND_ROWS_PER_FRAME.coerceAtLeast(1))
            scheduleListAppendIfNeeded()
        }
        pendingListAppend = r
        postOnAnimation(r)
    }


    private fun buildNormal() {
        val categories = if (st.tab == Tab.PHRASE) categoriesProvider() else emptyList()
        val category = if (st.tab == Tab.PHRASE) currentCategory(categories) else ""
        val entries = if (st.tab == Tab.CLIPBOARD) clipKeys() else phrasesInProvider(category)
        recordRenderSignature(categories, category, entries)
        val backControl = PanelBackButton.control(
            context,
            context.getString(R.string.clip_back),
            TEXT_DARK,
        ) { onBack() }
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(4), edgeInset, dp(4))
            fun iconLp(spaced: Boolean = false) = ll(dp(48), dp(48)).apply { if (spaced) marginStart = dp(6) }
            val lastIconLp = ll(dp(48), dp(48)).apply { marginStart = dp(6) - (ImeKeyFeedback.DEFAULT_FACE_INSET_DP * density).toInt() }
            addView(View(context), ll(0, dp(1), 1f))
            addView(pillTray(), ll(WC, dp(34)))
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(topBar)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(backControl, ll(WC, dp(PanelBackButton.HIT_DP)).apply { leftMargin = edgeInset })
            addView(scroll, ll(0, MP, 1f))
        }
        main.addView(
            header,
            ll(MP, dp(56)),
        )
        populateListRows(entries) { e, i -> card(e, i, category) }
        main.addView(listScroll, ll(MP, 0, 1f))

        if (st.tab == Tab.PHRASE) {
            main.addView(
                categoryBar(categories, category),
                ll(MP, dp(CATEGORY_BAR_HEIGHT_DP)).apply { leftMargin = edgeInset; rightMargin = edgeInset },
            )
        }
    }

    private fun phraseDisplayText(category: String, text: String): String {
        val note = phraseNoteProvider(category, text)
        return if (note.isNotEmpty()) note else text
    }

    private fun pickEntry(key: String) {
        val entry = if (clipTab()) clipIndex[key] else null
        val body = entryBody(key)
        when {
            body != null -> onPick(body)
            entry?.available == true -> showNotice(R.string.clip_entry_unreadable_body)
            else -> showNotice(R.string.clip_entry_lost_body)
        }
    }

    private fun card(text: String, index: Int, category: String): View {
        val expanded = st.expanded == text
        val phrase = st.tab == Tab.PHRASE
        val display = if (phrase) phraseDisplayText(category, text) else entryDisplay(text)
        lateinit var header: LinearLayout
        val headerFrame = object : FrameLayout(context) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        val surface = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (expanded) background = rounded(CARD, ImeShapes.cardRadiusDp)
        }
        header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            if (!expanded) background = rounded(CARD, ImeShapes.cardRadiusDp)
        }

        val body = TextView(context).apply {
            this.text = preview(display)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(4), dp(12))
        }.apply {
            foreground = cardPressFeedback(leftSide = true, squareBottom = expanded)
            setOnClickListener {
                when {
                    else -> pickEntry(text)
                }
            }
        }
        header.addView(body, ll(0, WC, 1f))
        headerFrame.addView(header, FrameLayout.LayoutParams(MP, WC))
        surface.addView(headerFrame, ll(MP, WC))
        column.addView(surface, ll(MP, WC))
        return column
    }

    override fun onDetachedFromWindow() {
        resetImmediateActions()
        cancelPendingListAppend()
        super.onDetachedFromWindow()
    }

    private fun categoryBar(categories: List<String>, current: String): View {
        val rail = ImePanelCategoryRail(context, density).apply { underlineColor = palette.accentBottom }
        for (name in categories) rail.addView(categoryTab(name, name == current))
        rail.selectedIndex = categories.indexOf(current).coerceAtLeast(0)
        val tabs = ImePanelCategoryBar(context, density).apply {
            ruleColor = Color.TRANSPARENT
            addView(rail)
            setOnScrollChangeListener { _, left, _, _, _ -> categoryScrollX = left }
        }
        return object : FrameLayout(context) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                val saved = categoryScrollX
                super.onLayout(changed, left, top, right, bottom)
                val target = if (revealSelectedCategory) revealedCategoryScrollX(tabs, rail, saved) else saved
                if (tabs.scrollX != target) tabs.scrollTo(target, 0)
            }
        }.apply {
            addView(tabs, FrameLayout.LayoutParams(MP, MP))
        }
    }

    private fun revealedCategoryScrollX(tabs: HorizontalScrollView, rail: ImePanelCategoryRail, from: Int): Int {
        val tab = rail.getChildAt(rail.selectedIndex) ?: return from
        val viewport = tabs.width - tabs.paddingLeft - tabs.paddingRight
        if (viewport <= 0 || tab.width <= 0) return from
        revealSelectedCategory = false
        val start = rail.left + tab.left - tabs.paddingLeft
        val end = rail.left + tab.right - tabs.paddingLeft
        return when {
            start < from -> start
            end > from + viewport -> end - viewport
            else -> from
        }
    }

    private fun displayCat(name: String): String =
        if (name == com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID) context.getString(R.string.clip_default_category) else name

    private fun selectPhraseCategory(name: String) {
        st.collapse()
        if (phraseCat != name) {
            val cats = categoriesProvider()
            pendingCategorySlideFromX =
                if (cats.indexOf(name) >= cats.indexOf(phraseCat)) selectLeadingGap().toFloat() else -selectLeadingGap().toFloat()
            pendingCategoryFade = true
        }
        phraseCat = name
        refresh()
    }

    private fun categoryTab(name: String, on: Boolean): View = TextView(context).apply {
        text = displayCat(name)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(CATEGORY_TAB_PADDING_DP), 0, dp(CATEGORY_TAB_PADDING_DP), 0)
        minimumWidth = dp(CATEGORY_TAB_MIN_WIDTH_DP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        layoutParams = ll(WC, MP)
        isSelected = on
        val ink = if (on) palette.keyLabel else palette.keyLabelSecondary
        setTextColor(ink)
        bindImmediateAction(this, ink, faceColor = Color.TRANSPARENT)
        setOnClickListener { selectPhraseCategory(name) }
    }

    private fun selectLeadingGap(): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.body, resources.displayMetrics).roundToInt()

    private fun hideOverlay() {
        overlay.resetBackdropGesture()
        if (overlay.visibility != VISIBLE) {
            forgetImmediateActions(overlay)
            overlay.removeAllViews()
            return
        }
        forgetImmediateActions(overlay)
        overlay.setOnClickListener(null)
        overlay.isClickable = false
        disableClicks(overlay)
        Motion.hideNow(overlay) { overlay.removeAllViews() }
    }

    private fun hideOverlayImmediately() {
        overlay.resetBackdropGesture()
        forgetImmediateActions(overlay)
        overlay.setOnClickListener(null)
        overlay.isClickable = false
        Motion.reset(overlay)
        overlay.removeAllViews()
        overlay.visibility = GONE
    }

    private fun disableClicks(v: View) {
        v.isClickable = false
        v.isLongClickable = false
        if (v is ViewGroup) for (i in 0 until v.childCount) disableClicks(v.getChildAt(i))
    }

    private fun showOverlay(content: View, gravity: Int = Gravity.CENTER, maxWidthDp: Int? = null, anchor: View? = null, across: Boolean = false) {
        overlay.resetBackdropGesture()
        Motion.reset(overlay)
        forgetImmediateActions(overlay)
        overlay.removeAllViews()
        overlay.setBackgroundColor(0x00000000)
        overlay.setOnClickListener(null)
        overlay.isClickable = true
        val scroll = ScrollView(context).apply {
            isClickable = true
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            clipToOutline = true
            elevation = dp(8).toFloat()
            addView(content)
        }
        val margin = dp(ImeShapes.popupMarginDp)
        val requestedWidth = maxWidthDp?.let { ImeShapes.popupWidthPx(resources.displayMetrics, it, width) } ?: WC
        val anchored = anchor?.let { anchoredOverlayParams(scroll, it, requestedWidth.takeIf { w -> w > 0 }, across) }
        if (anchored != null) {
            overlay.addView(scroll, anchored)
            overlay.visibility = VISIBLE
            Motion.showNow(scroll)
            return
        }
        val side = if (across) edgeInset else margin
        val lp = FrameLayout.LayoutParams(requestedWidth, WC, gravity).apply { leftMargin = side; rightMargin = side; topMargin = margin; bottomMargin = margin }
        overlay.addView(scroll, lp)
        overlay.visibility = VISIBLE
        Motion.showNow(scroll)
        scroll.post {
            val maxH = (overlay.height * 0.82f).toInt()
            if (maxH in 1 until scroll.height) { lp.height = maxH; scroll.layoutParams = lp }
        }
    }

    private fun anchoredOverlayParams(card: View, anchor: View, fixedWidth: Int? = null, across: Boolean = false): FrameLayout.LayoutParams? {
        if (width <= 0 || generateSequence(anchor.parent) { it.parent }.none { it === this }) return null
        val spot = android.graphics.Rect(0, 0, anchor.width, anchor.height).also { offsetDescendantRectToMyCoords(anchor, it) }
        val edge = dp(ANCHORED_MENU_EDGE_DP)
        val side = edgeInset
        val gap = dp(ANCHORED_MENU_GAP_DP)
        val roomAbove = spot.top - gap - edge
        val roomBelow = height - edge - spot.bottom - gap
        val widthSpec = when {
            fixedWidth != null -> MeasureSpec.makeMeasureSpec(minOf(fixedWidth, width - side * 2), MeasureSpec.EXACTLY)
            across -> MeasureSpec.makeMeasureSpec(width - side * 2, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(minOf(width - side * 2, ImeShapes.popupWidthPx(resources.displayMetrics)), MeasureSpec.EXACTLY)
        }
        card.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val above = card.measuredHeight <= roomAbove || (card.measuredHeight > roomBelow && roomAbove >= roomBelow)
        val room = if (above) roomAbove else roomBelow
        if (room <= 0) return null
        if (card.measuredHeight > room) card.measure(widthSpec, MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST))
        val left = when {
            fixedWidth != null -> (width - card.measuredWidth) / 2
            across -> (spot.left + (spot.width() - card.measuredWidth) / 2).coerceIn(side, maxOf(side, width - side - card.measuredWidth))
            else -> spot.left.coerceIn(side, maxOf(side, width - side - card.measuredWidth))
        }
        val top = if (above) spot.top - gap - card.measuredHeight else spot.bottom + gap
        return FrameLayout.LayoutParams(card.measuredWidth, card.measuredHeight, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = left
            topMargin = top
        }
    }

    private fun showPopupCard(content: View) = showOverlay(content, maxWidthDp = ImeShapes.popupWidthDp)

    private fun currentCategory(categories: List<String>): String {
        if (phraseCat !in categories) phraseCat = categories.firstOrNull().orEmpty()
        return phraseCat
    }

    private fun pillTray(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(CARD, ImeShapes.toolbarPillRadiusDp)
        addView(pill(context.getString(R.string.clip_clipboard), st.tab == Tab.CLIPBOARD, true) { if (st.switchTab(Tab.CLIPBOARD)) { refresh() } }, ll(dp(TAB_PILL_DP), MP))
        addView(pill(context.getString(R.string.clip_phrases), st.tab == Tab.PHRASE, false) { if (st.switchTab(Tab.PHRASE)) { refresh() } }, ll(dp(TAB_PILL_DP), MP))
    }

    private fun shrinkToWidth(view: TextView, availablePx: Int) {
        if (availablePx <= 0) return
        val label = view.text?.toString() ?: return
        repeat(SHRINK_PASSES) {
            val needed = view.paint.measureText(label)
            if (needed <= availablePx) return
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * availablePx / needed)
        }
    }

    private fun pill(label: String, on: Boolean, left: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        background = if (on) tabSegment(GREY_PILL, left) else null
        setTextColor(if (on) ACCENT else TEXT_DARK)
        setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        shrinkToWidth(this, dp(TAB_PILL_DP))
        foreground = RippleDrawable(
            ColorStateList.valueOf(Motion.withAlpha(if (on) ACCENT else TEXT_DARK, 0x24)),
            null,
            tabSegment(Color.WHITE, left),
        )
        setOnClickListener { onClick() }
        bindImeTapFeedback()
    }

    private fun tabSegment(color: Int, left: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        val r = dp(17).toFloat()
        cornerRadii = if (left) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
    }

    private fun showNotice(messageRes: Int) = showNotice(context.getString(messageRes), RED)

    private fun showNotice(message: String, color: Int, onDone: () -> Unit = {}) {
        val card = menuCard()
        card.addView(menuTitle(message, color = color))
        card.addView(menuItem(context.getString(R.string.clip_done)) { hideOverlay(); onDone() })
        showPopupCard(card)
    }

    private fun emptyHint(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(40), dp(16), dp(16))
        if (st.tab == Tab.CLIPBOARD) {
            addView(hint(context.getString(R.string.clip_clipboard_empty), 16f, TEXT_DARK)); addView(hint(context.getString(R.string.clip_clipboard_empty_hint), 14f, HINT))
        } else {
            addView(hint(context.getString(R.string.clip_phrases_empty), 16f, TEXT_DARK)); addView(hint(context.getString(R.string.clip_phrases_empty_hint), 14f, HINT))
        }
    }

    private fun hint(s: String, size: Float, color: Int) = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, dp(3), 0, dp(3))
    }

    private fun menuCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun popupInset(): Int = ImeType.popupInsetPx(resources.displayMetrics)

    private fun menuTitle(s: String, color: Int = TEXT_DARK): View = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(popupInset(), dp(12), popupInset(), dp(4))
    }

    private fun menuItem(label: String, compact: Boolean = false, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
        val vertical = dp(if (compact) 8 else 16)
        setPadding(popupInset(), vertical, popupInset(), vertical)
        if (compact) minHeight = dp(48)
        setOnClickListener { onClick() }
        bindImmediateAction(this, TEXT_DARK, faceColor = Color.TRANSPARENT)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * density
    }

    private fun cardPressFeedback(leftSide: Boolean, squareBottom: Boolean): RippleDrawable {
        val r = ImeShapes.cardRadiusDp * density
        val b = if (squareBottom) 0f else r
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = if (leftSide) floatArrayOf(r, r, 0f, 0f, 0f, 0f, b, b) else floatArrayOf(0f, 0f, r, r, b, b, 0f, 0f)
        }
        return RippleDrawable(ColorStateList.valueOf(Motion.withAlpha(TEXT_DARK, 0x24)), null, mask)
    }
}
