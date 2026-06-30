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

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelIconAlignmentTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun layout(v: View, width: Int = 480, height: Int = 320) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun textSizePx(tv: TextView): Int = tv.textSize.roundToInt()

    @Test fun edit_panel_back_icon_matches_right_action_label_height() {
        val v = EditPanelView(ctx)
        val labels = textViews(v)
        val title = labels.first { it.text.toString() == "文字编辑" }
        val delete = labels.first { it.text.toString() == "删除" }
        val backIcon = title.compoundDrawables[0]

        assertNotNull("edit panel title must keep a leading back drawable", backIcon)
        val maxDelta = (3f * density).roundToInt().coerceAtLeast(3)
        val deleteTextSize = textSizePx(delete)
        assertTrue(
            "edit panel back icon box should stay close to the right action label text size: icon=${backIcon.intrinsicHeight}, text=$deleteTextSize",
            abs(backIcon.intrinsicHeight - deleteTextSize) <= maxDelta,
        )
    }

    @Test fun symbols_lock_control_is_centered_and_sized_like_text() {
        val v = SymbolsView(ctx)
        assertCenteredLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), "SymbolsView")

        v.toggleLockForTest()
        assertCenteredLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), "SymbolsView locked")
    }

    @Test fun emoji_lock_control_is_centered_and_sized_like_text() {
        val v = EmojiView(ctx)
        assertCenteredLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), "EmojiView")

        v.toggleLockForTest()
        assertCenteredLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), "EmojiView locked")
    }

    private fun assertCenteredLockControl(root: View, slot: View, lock: TextView, name: String) {
        layout(root)
        assertTrue("$name: lock label must live inside the centered slot", lock.parent === slot)
        val lp = lock.layoutParams as FrameLayout.LayoutParams
        assertEquals("$name: lock label should measure as a cohesive wrap-content control", ViewGroup.LayoutParams.WRAP_CONTENT, lp.width)
        assertEquals("$name: lock label should fill the bar height for vertical centering", ViewGroup.LayoutParams.MATCH_PARENT, lp.height)
        assertEquals("$name: lock label should be centered inside the middle slot", Gravity.CENTER, lp.gravity)

        val rootCenter = root.width / 2
        val lockCenter = slot.left + (lock.left + lock.right) / 2
        assertTrue("$name: lock control must sit on the panel center", abs(lockCenter - rootCenter) <= 1)
        assertTrue("$name: lock control should not spread across the whole middle slot", lock.width < slot.width / 2)

        val maxPadding = (2f * density).roundToInt() + 1
        assertTrue("$name: lock icon and text should sit close together", lock.compoundDrawablePadding <= maxPadding)

        val icon = lock.compoundDrawables[0]
        assertNotNull("$name: lock control must keep a leading icon", icon)
        val maxDelta = (2f * density).roundToInt().coerceAtLeast(2)
        val labelTextSize = textSizePx(lock)
        assertTrue(
            "$name: lock icon box should match the label text size: icon=${icon.intrinsicHeight}, text=$labelTextSize",
            abs(icon.intrinsicHeight - labelTextSize) <= maxDelta,
        )
    }
}
