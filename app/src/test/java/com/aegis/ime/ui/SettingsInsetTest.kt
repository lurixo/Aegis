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

package com.aegis.ime.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.aegis.ime.ui.theme.AegisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * debug.16: regression guard for the settings-screen inset bug. The screen draws edge-to-edge, so when the IME
 * opens the content must (a) stay BELOW the status bar — not pan/scroll under it, the original symptom — and
 * (b) reserve the keyboard's height by SHRINKING the scroll viewport (insets applied OUTSIDE verticalScroll),
  * Chinese IME behavior note.
 * when it hides. Both are driven through the production [settingsScrollInsets] modifier with deterministic
 * literal insets, so the test exercises the real inset logic without a live window dispatching system insets.
 *
 * NOTE: the windowSoftInputMode=adjustResize half of the fix (stopping the window PAN that put the top cards
 * under the status bar) cannot be exercised by Robolectric — it is covered by on-device verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsInsetTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val statusDp = 36
    private val imeDp = 300
    private val statusPx = statusDp * density
    private val viewportPx = 1200 // deliberately shorter than the content so it scrolls

    private class Caps {
        var topY = Float.NaN
    }

    /** Render a tall scroller through [settingsScrollInsets] with a status-bar top inset and the given IME bottom inset. */
    private fun render(imeBottomDp: Int): Pair<Caps, ScrollState> {
        val caps = Caps()
        val scroll = ScrollState(0)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity: Activity = controller.get()
        val compose = ComposeView(activity).apply {
            setContent {
                AegisTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .settingsScrollInsets(
                                scrollState = scroll,
                                // the single safe-drawing inset the production code passes (here: status bar + IME).
                                insets = WindowInsets(top = statusDp.dp, bottom = imeBottomDp.dp),
                            )
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(40.dp)
                                .onGloballyPositioned { caps.topY = it.localToRoot(Offset.Zero).y },
                        )
                        repeat(20) { Box(Modifier.fillMaxWidth().height(80.dp)) }
                        Box(Modifier.fillMaxWidth().height(40.dp))
                    }
                }
            }
        }
        activity.setContentView(compose)
        shadowOf(Looper.getMainLooper()).idle()
        compose.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportPx, View.MeasureSpec.EXACTLY),
        )
        compose.layout(0, 0, wPx, viewportPx)
        shadowOf(Looper.getMainLooper()).idle()
        return caps to scroll
    }

    /** Goal #1: with a status-bar inset AND the IME open, the top content sits at/below the status bar — never under it. */
    @Test fun top_content_stays_below_status_bar_with_ime() {
        val (caps, _) = render(imeBottomDp = imeDp)
        assertTrue(
            "top content top (${caps.topY}px) must be >= the status-bar inset (${statusPx}px)",
            caps.topY >= statusPx - 0.5f,
        )
    }

    /**
     * Goal #2/#3: the IME inset is applied OUTSIDE the scroll, so opening the keyboard SHRINKS the scroll
     * viewport by exactly the keyboard height (and it grows back, leaving no blank, when the keyboard hides).
     * The shrunk viewport is what lets bring-into-view lift the focused field above the keyboard. This is the
     * property that distinguishes the fix from the regressed "IME inset inside the scroll" placement, which
     * would leave the viewport unchanged (delta 0) while only growing the scroll range.
     */
    @Test fun ime_inset_shrinks_the_scroll_viewport_not_just_the_range() {
        val (_, withIme) = render(imeBottomDp = imeDp)
        val (_, withoutIme) = render(imeBottomDp = 0)
        val shrink = (withoutIme.viewportSize - withIme.viewportSize).toFloat()
        val expected = imeDp * density
        assertEquals("opening the IME must shrink the scroll viewport by ~${expected}px", expected, shrink, density * 4f)
    }
}
