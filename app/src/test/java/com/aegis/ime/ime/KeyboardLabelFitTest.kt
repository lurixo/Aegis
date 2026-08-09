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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.LetterCase
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class KeyboardLabelFitTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class DrawnText(val text: String, val ink: RectF, val anchorX: Float, val anchorY: Float, val clip: RectF)

    private class TextRecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = ArrayList<DrawnText>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            super.drawText(text, x, y, paint)
            if (text.isEmpty()) return
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val advance = paint.measureText(text)
            val originX = when (paint.textAlign) {
                Paint.Align.CENTER -> x - advance / 2f
                Paint.Align.RIGHT -> x - advance
                else -> x
            }
            val clip = Rect()
            getClipBounds(clip)
            texts.add(
                DrawnText(
                    text,
                    RectF(originX + bounds.left, y + bounds.top, originX + bounds.right, y + bounds.bottom),
                    x, y,
                    RectF(clip),
                ),
            )
        }
    }

    private fun englishViews(): List<Pair<String, KeyboardView>> = listOf(
        "alpha" to alphaView(shifted = false),
        "alphaShifted" to alphaView(shifted = true),
        "nine" to nineView(LetterCase.AUTO),
        "nineLower" to nineView(LetterCase.LOWER),
        "number" to plainView(LayoutId.NUMBER),
        "symbol" to plainView(LayoutId.SYMBOL),
        "numpad" to numpadView(),
    )

    private fun alphaView(shifted: Boolean): KeyboardView = KeyboardView(ctx).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), shifted, false, Lang.EN)
    }

    private fun nineView(case: LetterCase): KeyboardView = KeyboardView(ctx).apply {
        setLayout(Layouts.nine(Lang.EN, Layouts.ninePunctuation()), false, false, Lang.EN)
        caseMode = case
    }

    private fun plainView(id: LayoutId): KeyboardView = KeyboardView(ctx).apply {
        setLayout(Layouts.forId(id, Lang.EN), false, false, Lang.EN)
    }

    private fun numpadView(): KeyboardView = KeyboardView(ctx).apply {
        setLayout(Layouts.numpad(Layouts.numpadOperators()), false, false, Lang.EN)
    }

    private fun laidOut(view: KeyboardView, widthPx: Int, heightPx: Int?): KeyboardView {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            if (heightPx == null) {
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            } else {
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            },
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun keyName(key: Key): String = key.label.ifEmpty { key.action.toString() }

    private fun edgeOverflow(ink: RectF, box: RectF): String? {
        val left = box.left - ink.left
        val right = ink.right - box.right
        val top = box.top - ink.top
        val bottom = ink.bottom - box.bottom
        if (left <= EPS && right <= EPS && top <= EPS && bottom <= EPS) return null
        return "left=${left.coerceAtLeast(0f)} right=${right.coerceAtLeast(0f)} " +
            "top=${top.coerceAtLeast(0f)} bottom=${bottom.coerceAtLeast(0f)}"
    }

    private fun ownerIndex(keys: List<Pair<Key, RectF>>, t: DrawnText): Int {
        val inside = keys.indexOfFirst { it.second.contains(t.anchorX, t.anchorY) }
        if (inside >= 0) return inside
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, entry) in keys.withIndex()) {
            val dx = t.anchorX - entry.second.centerX()
            val dy = t.anchorY - entry.second.centerY()
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun audit(tag: String, view: KeyboardView): List<String> {
        val fails = ArrayList<String>()
        val w = view.measuredWidth
        val h = view.measuredHeight
        val canvas = TextRecordingCanvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        view.draw(canvas)
        val keys = view.keyBoundsForTest()
        val byKey = HashMap<Int, ArrayList<DrawnText>>()
        val scrollVisible = ArrayList<Pair<String, RectF>>()
        for (t in canvas.texts) {
            if (t.clip.width() < w - EPS || t.clip.height() < h - EPS) {
                if (t.ink.left < t.clip.left - EPS || t.ink.right > t.clip.right + EPS) {
                    fails.add(
                        "$tag scroll cell '${t.text}' clipped horizontally: " +
                            "left=${(t.clip.left - t.ink.left).coerceAtLeast(0f)} " +
                            "right=${(t.ink.right - t.clip.right).coerceAtLeast(0f)}",
                    )
                }
                val visible = RectF(t.ink)
                if (visible.intersect(t.clip)) scrollVisible.add(t.text to visible)
                continue
            }
            val index = ownerIndex(keys, t)
            if (index < 0) {
                fails.add("$tag '${t.text}' has no owning key")
                continue
            }
            edgeOverflow(t.ink, keys[index].second)?.let {
                fails.add("$tag key '${keyName(keys[index].first)}' label '${t.text}' outside face: $it")
            }
            byKey.getOrPut(index) { ArrayList() }.add(t)
        }
        for ((index, list) in byKey) {
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val ox = minOf(a.ink.right, b.ink.right) - maxOf(a.ink.left, b.ink.left)
                    val oy = minOf(a.ink.bottom, b.ink.bottom) - maxOf(a.ink.top, b.ink.top)
                    if (ox > EPS && oy > EPS) {
                        fails.add("$tag key '${keyName(keys[index].first)}' labels '${a.text}' and '${b.text}' overlap ${ox}x${oy}px")
                    }
                }
            }
        }
        for (i in scrollVisible.indices) {
            for (j in i + 1 until scrollVisible.size) {
                val a = scrollVisible[i]
                val b = scrollVisible[j]
                val ox = minOf(a.second.right, b.second.right) - maxOf(a.second.left, b.second.left)
                val oy = minOf(a.second.bottom, b.second.bottom) - maxOf(a.second.top, b.second.top)
                if (ox > EPS && oy > EPS) {
                    fails.add("$tag scroll cells '${a.first}' and '${b.first}' overlap ${ox}x${oy}px")
                }
            }
        }
        return fails
    }

    @Test
    fun englishKeyLabelsFitTheirKeysAcrossPortraitWidths() {
        val fails = ArrayList<String>()
        for (q in listOf(
            "w250dp-h700dp-mdpi",
            "w320dp-h650dp-xhdpi",
            "w360dp-h740dp-xxhdpi",
            "w411dp-h891dp-xxhdpi",
            "w480dp-h900dp-hdpi",
        )) {
            RuntimeEnvironment.setQualifiers(q)
            val widthPx = ctx.resources.displayMetrics.widthPixels
            for ((state, view) in englishViews()) {
                fails += audit("$q $state", laidOut(view, widthPx, null))
            }
        }
        assertTrue("label overflow/collision: ${fails.joinToString("\n")}", fails.isEmpty())
    }

    @Test
    fun englishKeyLabelsFitTheirKeysAcrossLandscapeDocks() {
        val fails = ArrayList<String>()
        for (q in listOf(
            "w640dp-h291dp-land-hdpi",
            "w720dp-h360dp-land-xhdpi",
            "w853dp-h388dp-land-hdpi",
            "w320dp-h200dp-land-mdpi",
        )) {
            RuntimeEnvironment.setQualifiers(q)
            val density = ctx.resources.displayMetrics.density
            val configuration = ctx.resources.configuration
            val surface = LandscapeDockSizing.resolveWidth(
                landscape = true,
                slotWidth = ctx.resources.displayMetrics.widthPixels,
                preferredSurfaceWidth = (minOf(configuration.screenWidthDp, configuration.screenHeightDp) * density).roundToInt(),
                density = density,
                leftSystemInset = 0,
                rightSystemInset = 0,
            ).surfaceWidth
            val widthPx = surface - 2 * (4f * density).roundToInt()
            for ((state, view) in englishViews()) {
                val rows = view.rowCountForSizing()
                val spec = LandscapeDockSizing.resolveHeight(
                    availableHeight = ctx.resources.displayMetrics.heightPixels,
                    density = density,
                    rowCount = rows,
                    preferredKeyboardHeight = LandscapeDockSizing.preferredKeyboardHeight(rows, density),
                    fractionalRows = view.usesFractionalCellsForSizing(),
                    editBarVisible = false,
                    navBottom = 0,
                )
                fails += audit("$q $state", laidOut(view, widthPx, spec.keyboardHeight))
            }
        }
        assertTrue("label overflow/collision: ${fails.joinToString("\n")}", fails.isEmpty())
    }

    private companion object {
        const val EPS = 0.5f
    }
}
