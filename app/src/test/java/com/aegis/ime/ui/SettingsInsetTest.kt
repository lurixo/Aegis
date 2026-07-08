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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsInsetTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val statusDp = 36
    private val imeDp = 300
    private val leftDp = 17
    private val rightDp = 19
    private val statusPx = statusDp * density
    private val viewportPx = 1200

    private class Caps {
        var topY = Float.NaN
        var leftX = Float.NaN
        var width = 0
        var height = 0
    }

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
                                bottomInsets = WindowInsets(top = statusDp.dp, bottom = imeBottomDp.dp),
                                topInsets = WindowInsets(top = statusDp.dp),
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

    private fun renderUserDictInsets(imeBottomDp: Int, safeTopDp: Int): Caps {
        val caps = Caps()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity: Activity = controller.get()
        val compose = ComposeView(activity).apply {
            setContent {
                AegisTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .userDictPageInsets(
                                bottomInsets = WindowInsets(
                                    left = leftDp.dp,
                                    top = safeTopDp.dp,
                                    right = rightDp.dp,
                                    bottom = imeBottomDp.dp,
                                ),
                                topInsets = WindowInsets(top = statusDp.dp),
                            ),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onGloballyPositioned {
                                    val topLeft = it.localToRoot(Offset.Zero)
                                    caps.topY = topLeft.y
                                    caps.leftX = topLeft.x
                                    caps.width = it.size.width
                                    caps.height = it.size.height
                                },
                        )
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
        return caps
    }

    @Test fun top_content_stays_below_status_bar_with_ime() {
        val (caps, _) = render(imeBottomDp = imeDp)
        assertTrue(
            "top content top (${caps.topY}px) must be >= the status-bar inset (${statusPx}px)",
            caps.topY >= statusPx - 0.5f,
        )
    }

    @Test fun ime_inset_shrinks_the_scroll_viewport_not_just_the_range() {
        val (_, withIme) = render(imeBottomDp = imeDp)
        val (_, withoutIme) = render(imeBottomDp = 0)
        val shrink = (withoutIme.viewportSize - withIme.viewportSize).toFloat()
        val expected = imeDp * density
        assertEquals("opening the IME must shrink the scroll viewport by ~${expected}px", expected, shrink, density * 4f)
    }

    @Test fun seed_bridges_only_until_root_insets_are_delivered() {
        assertEquals(
            "first frame (holder still 0, root insets not delivered) uses the seed",
            63,
            resolveTopInsetPx(liveTop = 0, seedTop = 63, rootTop = null),
        )
        assertEquals(
            "delivered root inset wins while the Compose holder is still 0",
            63,
            resolveTopInsetPx(liveTop = 0, seedTop = 99, rootTop = 63),
        )
        assertEquals(
            "steady state matches",
            63,
            resolveTopInsetPx(liveTop = 63, seedTop = 63, rootTop = 63),
        )
        assertEquals(
            "live wins even if the seed read 0",
            63,
            resolveTopInsetPx(liveTop = 63, seedTop = 0, rootTop = 0),
        )
    }

    @Test fun shrinking_live_inset_is_not_clamped_up_to_a_stale_seed() {
        assertEquals(48, resolveTopInsetPx(liveTop = 48, seedTop = 63, rootTop = 63))
    }

    @Test fun delivered_zero_top_inset_is_not_replaced_by_a_stale_seed() {
        assertEquals(0, resolveTopInsetPx(liveTop = 0, seedTop = 63, rootTop = 0))
    }

    @Test fun synchronous_top_seed_prefers_visible_then_ignoring_visibility_then_maximum_then_resource() {
        assertEquals(
            "visible status/cutout inset wins first",
            11,
            synchronousTopInsetPx(
                visibleTop = 11,
                ignoringVisibilityTop = 22,
                maximumIgnoringVisibilityTop = 33,
                statusBarHeightTop = 44,
                isAttachedToDisplayTop = true,
            ),
        )
        assertEquals(
            "ignoring-visibility inset wins when visible inset is not available",
            22,
            synchronousTopInsetPx(
                visibleTop = 0,
                ignoringVisibilityTop = 22,
                maximumIgnoringVisibilityTop = 33,
                statusBarHeightTop = 44,
                isAttachedToDisplayTop = true,
            ),
        )
        assertEquals(
            "maximum metrics fallback is used before the resource height",
            33,
            synchronousTopInsetPx(
                visibleTop = 0,
                ignoringVisibilityTop = 0,
                maximumIgnoringVisibilityTop = 33,
                statusBarHeightTop = 44,
                isAttachedToDisplayTop = true,
            ),
        )
        assertEquals(
            "status_bar_height is the last top-attached fallback",
            44,
            synchronousTopInsetPx(
                visibleTop = 0,
                ignoringVisibilityTop = 0,
                maximumIgnoringVisibilityTop = 0,
                statusBarHeightTop = 44,
                isAttachedToDisplayTop = true,
            ),
        )
    }

    @Test fun synchronous_top_seed_skips_maximum_and_resource_fallbacks_when_not_top_attached() {
        assertEquals(
            0,
            synchronousTopInsetPx(
                visibleTop = 0,
                ignoringVisibilityTop = 0,
                maximumIgnoringVisibilityTop = 33,
                statusBarHeightTop = 44,
                isAttachedToDisplayTop = false,
            ),
        )
        assertEquals(
            22,
            synchronousTopInsetPx(
                visibleTop = 0,
                ignoringVisibilityTop = 22,
                maximumIgnoringVisibilityTop = 33,
                statusBarHeightTop = 44,
                isAttachedToDisplayTop = false,
            ),
        )
    }

    @Test fun user_dict_insets_use_seeded_top_not_safe_drawing_top() {
        val safeTopDp = 99
        val caps = renderUserDictInsets(imeBottomDp = imeDp, safeTopDp = safeTopDp)
        assertEquals("user dict top must come from settingsTopInset's source", statusPx, caps.topY, density)
        assertEquals("safeDrawing start inset must be preserved", leftDp * density, caps.leftX, density)
        assertEquals(
            "safeDrawing horizontal insets must be preserved",
            wPx - (leftDp + rightDp) * density,
            caps.width.toFloat(),
            density * 2f,
        )
    }

    @Test fun user_dict_ime_inset_shrinks_the_lazy_viewport() {
        val withIme = renderUserDictInsets(imeBottomDp = imeDp, safeTopDp = 99)
        val withoutIme = renderUserDictInsets(imeBottomDp = 0, safeTopDp = 99)
        val shrink = (withoutIme.height - withIme.height).toFloat()
        val expected = imeDp * density
        assertEquals("user dict IME inset must shrink the lazy-list viewport", expected, shrink, density * 4f)
    }
}
