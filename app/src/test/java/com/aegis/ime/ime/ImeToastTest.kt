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

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeToastTest {

    @Test fun a_toast_holds_for_three_seconds_before_it_fades_out() {
        assertEquals("the hold is the agreed three seconds", 3000L, ImeToast.HOLD_MS)
        val t = ImeToast()
        t.show("已复制", 1_000L)
        assertTrue(t.isShowing(1_000L))
        assertTrue("still up at the end of the hold", t.isShowing(1_000L + ImeToast.HOLD_MS))
        assertTrue("the fade is still part of the toast", t.isShowing(1_000L + ImeToast.HOLD_MS + 1))
        assertFalse("gone once the fade completes", t.isShowing(1_000L + ImeToast.TOTAL_MS))
    }

    @Test fun the_toast_fades_in_holds_opaque_then_fades_out() {
        val t = ImeToast()
        t.show("已粘贴", 0L)
        assertEquals(0f, t.alphaAt(0L), 0.001f)
        assertEquals(0.5f, t.alphaAt(ImeToast.FADE_MS / 2), 0.01f)
        assertEquals(1f, t.alphaAt(ImeToast.FADE_MS), 0.001f)
        assertEquals(1f, t.alphaAt(ImeToast.HOLD_MS), 0.001f)
        assertEquals(0.5f, t.alphaAt(ImeToast.HOLD_MS + ImeToast.FADE_MS / 2), 0.01f)
        assertEquals(0f, t.alphaAt(ImeToast.TOTAL_MS), 0.001f)
    }

    @Test fun showing_a_second_toast_restarts_the_hold() {
        val t = ImeToast()
        t.show("已复制", 0L)
        t.show("已剪切", 2_000L)
        assertEquals("已剪切", t.message)
        assertTrue(t.isShowing(4_000L))
        assertEquals(1_000L, t.remainingMs(4_180L))
    }

    @Test fun an_empty_message_never_shows_and_hide_clears_it() {
        val t = ImeToast()
        t.show("", 0L)
        assertFalse(t.isShowing(0L))
        t.show("已全选", 0L)
        t.hide()
        assertFalse(t.isShowing(0L))
        assertEquals("", t.message)
    }

    @Test fun the_toast_sits_horizontally_centred_in_the_TUV_band_of_the_keyboard() {
        val keyboard = RectF(0f, 400f, 1000f, 1200f)
        val r = ImeToast.bounds(keyboard, contentWidth = 200f, contentHeight = 40f, paddingX = 16f, paddingY = 10f, minMargin = 24f)
        assertEquals("horizontally centred", keyboard.centerX(), r.centerX(), 0.001f)
        assertEquals("232 wide: content plus padding", 232f, r.width(), 0.001f)
        assertEquals("60 tall: content plus padding", 60f, r.height(), 0.001f)
        val band = keyboard.top + keyboard.height() * 0.625f
        assertEquals("anchored on the third of four key rows", band, r.centerY(), 0.001f)
        assertTrue("inside the 50%-75% band", r.centerY() > keyboard.top + keyboard.height() * 0.5f)
        assertTrue(r.centerY() < keyboard.top + keyboard.height() * 0.75f)
    }

    @Test fun a_long_toast_is_clamped_to_the_keyboard_width_less_its_margins() {
        val keyboard = RectF(0f, 0f, 400f, 800f)
        val r = ImeToast.bounds(keyboard, contentWidth = 900f, contentHeight = 40f, paddingX = 16f, paddingY = 10f, minMargin = 24f)
        assertEquals("clamped to the keyboard minus both margins", 352f, r.width(), 0.001f)
        assertTrue(r.left >= keyboard.left)
        assertTrue(r.right <= keyboard.right)
    }
}
