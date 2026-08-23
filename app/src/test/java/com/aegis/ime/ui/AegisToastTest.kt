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

import android.os.Looper
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.aegis.ime.ui.theme.AegisTheme
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AegisToastTest {

    @get:Rule
    val compose = createComposeRule()

    @Before fun clear() = AegisToast.reset()

    @After fun release() = AegisToast.reset()

    @Test fun a_notice_holds_for_one_and_a_half_seconds_and_then_leaves() {
        assertEquals("the hold matches the keyboard toast", 1500L, AegisToast.HOLD_MS)
        AegisToast.show("词库已导出")
        assertNotNull(AegisToast.current.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AegisToast.FADE_IN_MS + AegisToast.HOLD_MS - 10L))
        assertNotNull("still up through the hold", AegisToast.current.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        assertNull("gone once the hold ends", AegisToast.current.value)
        assertEquals("the last text stays observable", "词库已导出", AegisToast.textForTest())
    }

    @Test fun a_second_notice_replaces_the_first_and_restarts_the_hold() {
        AegisToast.show("已复制")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        AegisToast.show("已剪切")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertEquals("已剪切", AegisToast.current.value?.text)
        assertEquals(2, AegisToast.shownCountForTest())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertNull(AegisToast.current.value)
    }

    @Test fun an_empty_notice_never_shows() {
        AegisToast.show("")
        assertNull(AegisToast.current.value)
        assertNull(AegisToast.textForTest())
        assertEquals(0, AegisToast.shownCountForTest())
    }

    @Test fun the_overlay_renders_the_notice_text_on_the_shared_card() {
        compose.setContent {
            AegisTheme {
                AegisToastOverlay()
            }
        }
        compose.onNodeWithTag("aegis_toast").assertDoesNotExist()

        compose.runOnUiThread { AegisToast.show("已存入剪贴板") }
        compose.waitForIdle()

        compose.onNodeWithTag("aegis_toast").assertExists()
        compose.onNodeWithText("已存入剪贴板").assertExists()
    }
}
