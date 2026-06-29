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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardClearSystemTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun view(): Pair<ClipboardView, () -> Int> {
        var clears = 0
        val v = ClipboardView(ctx).apply { onClearSystemClipboard = { clears++ } }
        return v to { clears }
    }

    @Test fun tapping_the_button_only_shows_a_confirm_and_does_not_clear() {
        val (v, clears) = view()
        v.requestClearSystemForTest()
        assertEquals("nothing cleared on the first tap", 0, clears())
        assertTrue("a confirm card is shown", v.isOverlayShownForTest())
    }

    @Test fun confirming_clears_the_system_clipboard_exactly_once() {
        val (v, clears) = view()
        v.requestClearSystemForTest()
        v.confirmClearSystemForTest()
        assertEquals("confirm fires the host clear once", 1, clears())
        assertFalse("the confirm card is dismissed after confirming", v.isOverlayShownForTest())
    }

    @Test fun cancelling_leaves_the_system_clipboard_untouched() {
        val (v, clears) = view()
        v.requestClearSystemForTest()
        v.cancelClearSystemForTest()
        assertEquals("取消 never clears", 0, clears())
        assertFalse("the confirm card is dismissed on 取消", v.isOverlayShownForTest())
    }

    @Test fun the_clear_button_is_present_left_of_the_edit_entry_in_normal_mode() {
        val v = ClipboardView(ctx).apply { applyPalette(com.aegis.ime.ime.theme.ImePalette.STATIC_LIGHT) }
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1080, 800)
        val edit = firstWithText(v, "☰")
        val clearBtn = firstImageView(v)
        assertTrue("the ☰ edit entry is present", edit != null)
        assertTrue("an icon button (清空系统剪贴板) is present", clearBtn != null)
        assertTrue("the 清空系统剪贴板 button sits to the LEFT of ☰", xInRoot(v, clearBtn!!) < xInRoot(v, edit!!))
    }

    private fun xInRoot(root: android.view.View, v: android.view.View): Int {
        var x = 0; var cur: android.view.View? = v
        while (cur != null && cur !== root) { x += cur.left; cur = cur.parent as? android.view.View }
        return x
    }

    private fun firstWithText(root: android.view.View, s: String): android.view.View? {
        val out = java.util.ArrayList<android.view.View>()
        root.findViewsWithText(out, s, android.view.View.FIND_VIEWS_WITH_TEXT)
        return out.firstOrNull()
    }

    private fun firstImageView(root: android.view.View): android.widget.ImageView? {
        if (root is android.widget.ImageView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) firstImageView(root.getChildAt(i))?.let { return it }
        }
        return null
    }
}
