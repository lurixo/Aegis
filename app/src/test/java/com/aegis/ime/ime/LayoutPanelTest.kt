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
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class LayoutPanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val light = ImePalette.STATIC_LIGHT

    private fun idleBar(widthDp: Int): CandidateView {
        val view = CandidateView(ctx)
        view.setContent(emptyList(), "")
        view.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        return view
    }

    private fun layoutPanel(panel: LayoutPanelView, widthDp: Int = 360, heightDp: Int = 240) {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun send(
        view: View,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long = 0L,
        eventTime: Long = downTime,
    ): Boolean {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        return try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test fun the_layout_and_edit_functions_have_the_final_toolbar_order() {
        assertEquals(
            listOf("BRAND", "EMOJI", "LAYOUT", "EDIT", "CLIPBOARD", "TRANSLATE"),
            BarFunction.entries.map { it.name },
        )
        val view = idleBar(360)
        assertEquals(
            listOf("BRAND", "EMOJI", "LAYOUT", "EDIT", "CLIPBOARD", "TRANSLATE"),
            view.toolbarFunctionsForTest().map { it.name },
        )
        assertEquals(view.toolbarFunctionsForTest().size + 1, view.toolbarControlBoundsForTest().size)
    }

    @Test fun idle_toolbar_layout_slot_renders_the_keyboard_icon() {
        val view = idleBar(320)
        val slot = view.toolbarControlBoundsForTest()[2]
        val s = 9f * density * view.toolbarIconScaleForTest(BarFunction.LAYOUT)
        val glyph = RectF(
            slot.centerX() - s * 0.70f,
            slot.centerY() - s * 0.700f,
            slot.centerX() + s * 0.70f,
            slot.centerY() + s * 0.700f,
        )
        assertTrue(slot.contains(glyph))
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val ink = RectF(glyph).apply { inset(-(0.9f * density + 1f), -(0.9f * density + 1f)) }
        var found = false
        for (y in slot.top.toInt() until slot.bottom.toInt()) {
            for (x in slot.left.toInt() until slot.right.toInt()) {
                if (bitmap.getPixel(x, y) != light.icon) continue
                found = true
                assertTrue(ink.contains(x.toFloat(), y.toFloat()))
            }
        }
        assertTrue(found)
    }

    @Test fun cards_keep_selection_card_touch_semantics_without_sticky_retarget_or_repeat() {
        val panel = LayoutPanelView(ctx).apply { applyPalette(light) }
        layoutPanel(panel)
        val picks = ArrayList<LayoutChoice>()
        panel.onPick = picks::add
        val card = panel.cardViewForTest(LayoutChoice.CN_NINE)
        val x = card.width / 2f
        val y = card.height / 2f

        send(card, MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue("holding a selection card never repeats", picks.isEmpty())
        send(card, MotionEvent.ACTION_MOVE, card.width + 100f, y, eventTime = 610L)
        assertEquals(0f, panel.cardFeedbackLevelForTest(LayoutChoice.CN_NINE), 0f)
        send(card, MotionEvent.ACTION_UP, card.width + 100f, y, eventTime = 620L)
        assertTrue("releasing outside neither sticks to nor retargets a layout choice", picks.isEmpty())

        assertTrue(card.performClick())
        assertEquals(listOf(LayoutChoice.CN_NINE), picks)
    }

    @Test fun the_active_card_paints_the_accent_green() {
        val panel = LayoutPanelView(ctx).apply {
            applyPalette(light)
            setActiveChoice(LayoutChoice.CN_ALPHA)
        }
        val w = (360 * density).toInt()
        val h = (240 * density).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(bitmap))
        var accent = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == light.accentBottom) accent++
            }
        }
        assertTrue(accent > 0)
    }

    @Config(sdk = [34], qualifiers = "zh-xxhdpi")
    @Test fun the_card_icon_ink_stays_centered_despite_the_badge() {
        val panel = LayoutPanelView(ctx).apply { applyPalette(light) }
        val icon = panel.cardIconForTest(LayoutChoice.CN_ALPHA)
        val w = icon.intrinsicWidth
        val h = icon.intrinsicHeight
        icon.setBounds(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        icon.draw(Canvas(bitmap))
        var minX = w
        var maxX = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 128) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        assertTrue(maxX >= 0)
        assertEquals(w / 2f, (minX + maxX) / 2f, 2f * density)
    }
}
