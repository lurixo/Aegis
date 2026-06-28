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

import android.graphics.drawable.RippleDrawable
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeVisualPolishTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun shared_aegis_surface_radii_keep_the_taskbar_capsule() {
        assertEquals(10f, ImeShapes.keyRadiusDp, 0f)
        assertEquals(6f, ImeShapes.toolbarFeedbackRadiusDp, 0f)
        assertEquals(8f, ImeShapes.cardRadiusDp, 0f)
        assertEquals(8f, ImeShapes.inputRadiusDp, 0f)
        assertEquals(8f, ImeShapes.chipRadiusDp, 0f)
        assertEquals(999f, ImeShapes.toolbarPillRadiusDp, 0f)
    }

    @Test fun tap_feedback_helper_installs_a_rounded_ripple_foreground() {
        val v = View(ctx)
        Motion.applyTapFeedback(v, ImePalette.STATIC_LIGHT.keyLabel)
        assertTrue("clickable helper uses RippleDrawable feedback", v.foreground is RippleDrawable)
    }
}
