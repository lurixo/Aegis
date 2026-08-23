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

import android.view.View
import android.view.ViewGroup
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class LandscapeEmergencyEditBarTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun findEditBar(v: View): EditBarView? {
        if (v is EditBarView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findEditBar(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test fun the_deep_height_squeeze_still_fits_with_the_edit_bar_visible() {
        val nl = 10.toChar()
        val iv = InputView(ctx).apply {
            showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            showEditBar(true)
            setEditText("line1" + nl + "line2" + nl + "line3")
        }
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.AT_MOST),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)

        val spec = requireNotNull(iv.dockHeightSpecForTest())
        assertTrue("the deep branch is exercised", spec.emergency)
        assertTrue("the dock fits its cap, got ${iv.measuredHeight}", iv.measuredHeight <= 150)
        assertEquals(
            "the edit bar takes exactly its budget",
            spec.barHeight,
            requireNotNull(findEditBar(iv)).measuredHeight,
        )
    }
}
