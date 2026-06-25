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
import android.graphics.RectF
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.min
import kotlin.math.roundToInt
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.KeyboardLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.ScrollColumn
import com.aegis.ime.ime.theme.ImeShapes

class KeyboardView(context: Context) : View(context) {

    private var layout: KeyboardLayout = Layouts.forId(LayoutId.ALPHA, Lang.CN)
    private var modeSwitches = 0
    private var layoutApplies = 0
    private var shifted = false
    private var shiftLocked = false
    private var lang = Lang.CN

    private val placed = ArrayList<Placed>()

    private var scrollColumn: ScrollColumn? = null
    private val scrollRegion = RectF()
    private val scrollTouch = RectF()
    private var scrollCellH = 0f
    private var scrollAccentIndex = -1
    private var pendingAccentReveal = false
    private var scrollY = 0f

    private val density = resources.displayMetrics.density
    private val rowHeight = 52f * density
    private val snapCap = rowHeight * 0.5f
    private val shortPageRowExtra = 2f * density
    private val gap = KEY_GAP_DP * density
    private val edgeInset = ImeShapes.edgeInsetDp * density

    private data class Placed(val rect: RectF, val key: Key, val groupId: Int = 0, val hitRect: RectF? = null)

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean, isLocked: Boolean, language: Lang) {
        if (newLayout == layout && isShifted == shifted && isLocked == shiftLocked && language == lang) return
        layoutApplies++
        val sameColumn = newLayout.scrollColumn?.items?.map { it.label } == layout.scrollColumn?.items?.map { it.label }
        val accentIndex = newLayout.scrollColumn?.items?.indexOfFirst { it.accent } ?: -1
        if (accentIndex >= 0 && (!sameColumn || accentIndex != scrollAccentIndex)) pendingAccentReveal = true
        scrollAccentIndex = accentIndex
        val modeChanged = newLayout.id != layout.id
        val sizingChanged = newLayout.rowCount != layout.rowCount ||
            usesFractionalCells(newLayout) != usesFractionalCells(layout)
        layout = newLayout
        shifted = isShifted
        shiftLocked = isLocked
        lang = language
        scrollColumn = newLayout.scrollColumn
        if (!sameColumn) { scrollY = 0f }
        if (width > 0) relayout()
        if (sizingChanged || width <= 0) requestLayout()
        invalidate()
        if (modeChanged && width > 0) { modeSwitches++ }
    }

    internal fun modeSwitchesForTest(): Int = modeSwitches

    internal fun layoutAppliesForTest(): Int = layoutApplies

    private fun usesFractionalCells(l: KeyboardLayout): Boolean = l.cells != null && l.id != LayoutId.ALPHA

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rowCount
        val rh = if (rows == 4) rowHeight + shortPageRowExtra else rowHeight
        val desiredHeight = (rows * rh + (rows + 1) * gap).toInt()

        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        placed.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val compactGrid = layout.id == LayoutId.ALPHA || layout.id == LayoutId.NINE || layout.id == LayoutId.NUMPAD
        val gapGeometry = if (layout.id == LayoutId.NUMBER || layout.id == LayoutId.SYMBOL) {
            Layouts.forId(LayoutId.ALPHA, lang)
        } else {
            layout
        }
        val constrainedPage = (layout.id == LayoutId.NUMBER || layout.id == LayoutId.SYMBOL) &&
            height < LandscapeDockSizing.preferredKeyboardHeight(layout.rowCount, density)
        val verticalRows = if (constrainedPage) gapGeometry.rowCount else layout.rowCount
        val maximumGap = if (compactGrid || gapGeometry.id == LayoutId.ALPHA) gap / 2f else gap
        val horizontalGap = effectiveHorizontalGap((w - 2f * edgeInset).coerceAtLeast(0f), maximumGap, gapGeometry)
        val outer = (edgeInset - horizontalGap).coerceAtLeast(0f)
        val span = (w - 2f * outer).coerceAtLeast(0f)
        val fractionalVertical = layout.cells != null && layout.id != LayoutId.ALPHA
        val availableVerticalGap = LandscapeDockSizing.effectiveVerticalGap(
            height,
            verticalRows,
            density,
            fractionalRows = fractionalVertical,
        )
        val verticalGap = if (layout.id == LayoutId.NINE || layout.id == LayoutId.NUMPAD) {
            minOf(gap / 2f, availableVerticalGap / 2f)
        } else {
            availableVerticalGap
        }
        val sc = layout.scrollColumn
        scrollColumn = sc
        if (sc != null && h > 0) {
            scrollRegion.set(
                outer + sc.x * span + horizontalGap,
                sc.y * h + verticalGap,
                outer + (sc.x + sc.w) * span - horizontalGap,
                (sc.y + sc.h) * h - verticalGap,
            )
            scrollTouch.set(
                maxOf(edgeInset, outer + sc.x * span),
                scrollRegion.top,
                minOf(w - edgeInset, outer + (sc.x + sc.w) * span),
                scrollRegion.bottom,
            )
            scrollCellH = scrollCellHeight(sc, h, verticalGap)
            clampScroll()
            if (pendingAccentReveal) {
                revealScrollIndex(scrollAccentIndex)
                pendingAccentReveal = false
            }
        }
        val cells = layout.cells
        if (cells != null) {
            val alphaFaceHeight = if (layout.id == LayoutId.ALPHA) {
                (h - (layout.rowCount + 1) * verticalGap) / layout.rowCount
            } else {
                0f
            }
            for (pk in cells) {
                val top = if (layout.id == LayoutId.ALPHA) {
                    val row = (pk.y * layout.rowCount).roundToInt()
                    verticalGap + row * (alphaFaceHeight + verticalGap)
                } else {
                    pk.y * h + verticalGap
                }
                val bottom = if (layout.id == LayoutId.ALPHA) top + alphaFaceHeight else (pk.y + pk.h) * h - verticalGap
                val rowIndex = (pk.y * layout.rowCount).roundToInt()
                val hitTop = when {
                    layout.id != LayoutId.ALPHA -> pk.y * h
                    rowIndex == 0 -> 0f
                    else -> top - verticalGap / 2f
                }
                val hitBottom = when {
                    layout.id != LayoutId.ALPHA -> (pk.y + pk.h) * h
                    rowIndex == layout.rowCount - 1 -> h
                    else -> bottom + verticalGap / 2f
                }
                placed.add(
                    Placed(
                        RectF(
                            outer + pk.x * span + horizontalGap,
                            top,
                            outer + (pk.x + pk.w) * span - horizontalGap,
                            bottom,
                        ),
                        pk.key, pk.groupId,
                        RectF(
                            maxOf(edgeInset, outer + pk.x * span),
                            hitTop,
                            minOf(w - edgeInset, outer + (pk.x + pk.w) * span),
                            hitBottom,
                        ),
                    ),
                )
            }
            return
        }
        if (layout.id == LayoutId.NUMBER || layout.id == LayoutId.SYMBOL) {
            val faceGap = horizontalGap * 2f
            val availableFaceHeight = (h - (verticalRows + 1) * verticalGap) / verticalRows
            val faceHeight = minOf(rowHeight, availableFaceHeight)
            var top = (h - layout.rowCount * faceHeight - (layout.rowCount - 1) * verticalGap) / 2f
            for (rowItem in layout.rows) {
                val finalRow = rowItem.keys.any { it.action == KeyAction.SPACE }
                fun rowWidths(total: Float): List<Float> {
                    val ordinaryWidth = total / 10f - faceGap
                    if (!finalRow) return rowItem.keys.map { ordinaryWidth * it.weight }
                    val nonSpaceWidth = rowItem.keys
                        .filter { it.action != KeyAction.SPACE }
                        .sumOf { (ordinaryWidth * it.weight).toDouble() }
                        .toFloat()
                    val spaceWidth = total - horizontalGap * 2f - faceGap * (rowItem.keys.size - 1) - nonSpaceWidth
                    return rowItem.keys.map { if (it.action == KeyAction.SPACE) spaceWidth else ordinaryWidth * it.weight }
                }
                val widths = rowWidths(span)
                val keyGap = (span - horizontalGap * 2f - widths.sum()) / (rowItem.keys.size - 1)
                var left = outer + horizontalGap
                var hitLeft = maxOf(edgeInset, left)
                for ((index, key) in rowItem.keys.withIndex()) {
                    val rect = RectF(left, top, left + widths[index], top + faceHeight)
                    val hitRight = if (index == rowItem.keys.lastIndex) minOf(w - edgeInset, rect.right) else rect.right + keyGap / 2f
                    val hitRect = RectF(hitLeft, rect.top - verticalGap / 2f, hitRight, rect.bottom + verticalGap / 2f)
                    placed.add(Placed(rect, key, hitRect = hitRect))
                    left += widths[index] + keyGap
                    hitLeft = hitRight
                }
                top += faceHeight + verticalGap
            }
            return
        }
        val rh = (h - (layout.rowCount + 1) * verticalGap) / layout.rowCount
        var top = verticalGap
        for (rowItem in layout.rows) {
            val totalWeight = rowItem.keys.sumOf { it.weight.toDouble() }.toFloat()
            val usable = span - 2 * horizontalGap - (rowItem.keys.size - 1) * horizontalGap
            var left = outer + horizontalGap
            for (key in rowItem.keys) {
                val keyW = usable * (key.weight / totalWeight)
                placed.add(Placed(RectF(left, top, left + keyW, top + rh), key))
                left += keyW + horizontalGap
            }
            top += rh + verticalGap
        }
    }

    private fun effectiveHorizontalGap(contentWidth: Float, maximumGap: Float, geometry: KeyboardLayout): Float {
        if (contentWidth <= 0f) return 0f
        val minimumFace = 20f * density + FACE_FLOOR_ROUNDING_PX
        var allowed = maximumGap
        val cells = geometry.cells
        if (cells != null) {
            val minimumFraction = cells.minOfOrNull { it.w } ?: 1f
            if (minimumFraction < 1f) {
                allowed = min(allowed, (minimumFraction * contentWidth - minimumFace) / (2f * (1f - minimumFraction)))
            }
        } else {
            for (row in geometry.rows) {
                if (row.keys.size < 2) continue
                val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat()
                val minimumWeight = row.keys.minOf { it.weight }
                allowed = min(
                    allowed,
                    (contentWidth - minimumFace * totalWeight / minimumWeight) / (row.keys.size - 1),
                )
            }
        }
        return allowed.coerceIn(0f, maximumGap)
    }

    private fun maxScroll(): Float {
        val sc = scrollColumn ?: return 0f
        return maxOf(0f, sc.items.size * scrollCellH - scrollRegion.height())
    }

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0f, maxScroll())
    }

    private fun revealScrollIndex(index: Int) {
        val sc = scrollColumn ?: return
        if (index !in sc.items.indices || scrollCellH <= 0f) return
        val top = index * scrollCellH
        val bottom = top + scrollCellH
        val window = scrollRegion.height()
        if (top < scrollY) scrollY = top else if (bottom > scrollY + window) scrollY = bottom - window
        clampScroll()
    }

    internal fun centerOfLabelForTest(label: String): Pair<Float, Float>? {
        if (placed.isEmpty()) relayout()
        val p = placed.firstOrNull { it.key.label == label } ?: return null
        return p.rect.centerX() to p.rect.centerY()
    }

    internal fun boundsOfLabelForTest(label: String): RectF? {
        if (placed.isEmpty()) relayout()
        return placed.firstOrNull { it.key.label == label }?.rect?.let(::RectF)
    }

    internal fun keyBoundsForTest(): List<Pair<Key, RectF>> {
        if (placed.isEmpty()) relayout()
        return placed.map { it.key to RectF(it.rect) }
    }

    internal fun keyHitBoundsForTest(): List<Pair<Key, RectF>> {
        if (placed.isEmpty()) relayout()
        return placed.map { it.key to RectF(it.hitRect ?: it.rect) }
    }

    internal fun keyAtForTest(x: Float, y: Float): Key? {
        if (placed.isEmpty()) relayout()
        return placedAt(x, y)?.key
    }

    internal fun minimumKeyWidthForTest(): Float {
        if (placed.isEmpty()) relayout()
        return placed.minOfOrNull { it.rect.width() } ?: 0f
    }

    internal fun scrollRegionForTest(): RectF = RectF(scrollRegion)
    internal fun scrollTouchForTest(): RectF = RectF(scrollTouch)

    private fun placedAt(x: Float, y: Float): Placed? {
        var nearest: Placed? = null
        var best = Float.MAX_VALUE
        var explicitHits = false
        for (p in placed) {
            val hitRect = p.hitRect
            if (hitRect != null) {
                explicitHits = true
                if (hitRect.contains(x, y)) return p
                continue
            }
            if (p.rect.contains(x, y)) return p
            val dx = when {
                x < p.rect.left -> p.rect.left - x
                x > p.rect.right -> x - p.rect.right
                else -> 0f
            }
            val dy = when {
                y < p.rect.top -> p.rect.top - y
                y > p.rect.bottom -> y - p.rect.bottom
                else -> 0f
            }
            val d = dx * dx + dy * dy
            if (d < best) { best = d; nearest = p }
        }

        if (explicitHits) return null

        val cap = snapCap
        val boundedCap = minOf(cap, (nearest?.rect?.height() ?: 0f) * 0.5f)
        return if (best <= boundedCap * boundedCap) nearest else null
    }

    internal companion object {
        const val KEY_GAP_DP = 6f
        private const val FACE_FLOOR_ROUNDING_PX = 0.001f

        fun scrollCellHeight(column: ScrollColumn, keyboardHeight: Float, verticalGap: Float): Float {
            val visible = (column.h / column.cellHFrac).roundToInt().coerceAtLeast(1)
            return (column.h * keyboardHeight - 2f * verticalGap) / visible
        }
    }
}

class FlingScroller(context: Context) {
    private val scroller = OverScroller(context)
    private val minVel = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxVel = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val sampleT = LongArray(SAMPLES)
    private val samplePos = FloatArray(SAMPLES)
    private var head = 0
    private var count = 0

    var stopArmed = false
        private set

    fun onDown() {
        stopArmed = !scroller.isFinished
        if (stopArmed) scroller.forceFinished(true)
        count = 0; head = 0
    }

    fun addSample(t: Long, pos: Float) {
        sampleT[head] = t; samplePos[head] = pos
        head = (head + 1) % SAMPLES
        if (count < SAMPLES) count++
    }

    fun velocity(): Float {
        if (count < 2) return 0f
        val newest = (head - 1 + SAMPLES) % SAMPLES
        val tNew = sampleT[newest]; val pNew = samplePos[newest]
        var ref = newest
        for (k in 1 until count) {
            val idx = (newest - k + SAMPLES) % SAMPLES
            ref = idx
            if (tNew - sampleT[idx] >= WINDOW_MS) break
        }
        val dt = (tNew - sampleT[ref]).toFloat()
        if (dt <= 0f) return 0f
        return ((pNew - samplePos[ref]) / dt * 1000f).coerceIn(-maxVel, maxVel)
    }

    fun fling(start: Float, max: Float): Boolean {
        val v = velocity()
        if (kotlin.math.abs(v) <= minVel || max <= 0f) return false
        scroller.fling(0, start.toInt(), 0, (-v).toInt(), 0, 0, 0, max.toInt())
        return true
    }

    fun predictFinalOffset(start: Float): Float {
        val v = velocity()
        if (kotlin.math.abs(v) <= minVel) return start
        scroller.fling(0, start.toInt(), 0, (-v).toInt(), 0, 0, 0, Int.MAX_VALUE)
        val end = scroller.finalY.toFloat()
        scroller.forceFinished(true)
        return end
    }

    val isFinished: Boolean get() = scroller.isFinished

    fun finalOffset(): Float = scroller.finalY.toFloat()

    private companion object {
        const val SAMPLES = 12
        const val WINDOW_MS = 100L
    }
}
