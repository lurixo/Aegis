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

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsPressResetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingIndication : IndicationNodeFactory {
        val sources = ArrayList<InteractionSource>()

        override fun create(interactionSource: InteractionSource): DelegatableNode {
            sources.add(interactionSource)
            return object : Modifier.Node() {}
        }

        override fun equals(other: Any?): Boolean = other === this

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private class OtherPage(context: Context, state: Lifecycle.State, private val shown: Boolean = true) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
        val view = object : View(context) {
            override fun getWindowVisibility(): Int = if (shown) VISIBLE else GONE
        }
        val page = SettingsPressHandoff.Page(this, view)
        val release = SettingsPressHandoff.attach(page)

        fun firstFrameMayDraw(): Boolean = !view.viewTreeObserver.dispatchOnPreDraw()
    }

    private val sources = ArrayList<MutableInteractionSource>()
    private var pressed = false

    private fun pause() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
    }

    private fun resume() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun elapse(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        compose.waitForIdle()
    }

    private fun drawThisPage() {
        compose.activity.window.decorView.viewTreeObserver.dispatchOnDraw()
    }

    private fun openingPage(): OtherPage = OtherPage(compose.activity, Lifecycle.State.RESUMED)

    private fun showHeldControl(): Pair<ArrayList<Interaction>, CoroutineScope> {
        compose.setContent {
            SettingsActivityChrome {
                val source = rememberSettingsPressSource()
                SideEffect { if (sources.lastOrNull() !== source) sources.add(source) }
                key(source) { pressed = source.collectIsPressedAsState().value }
                Box(
                    Modifier.size(96.dp).testTag("control").clickable(
                        interactionSource = source,
                        indication = LocalIndication.current,
                    ) {},
                )
            }
        }
        compose.waitForIdle()
        val held = ArrayList<Interaction>()
        val scope = MainScope()
        scope.launch { sources.single().interactions.collect { held.add(it) } }
        compose.waitForIdle()
        compose.onNodeWithTag("control").performTouchInput { down(center) }
        compose.waitForIdle()
        assertTrue("the held finger must show as a press before the next page opens", pressed)
        assertTrue(held.last() is PressInteraction.Press)
        return held to scope
    }

    @Test fun the_next_page_waits_for_the_covered_page_to_drop_its_press_and_redraw() {
        val (held, scope) = showHeldControl()
        val next = openingPage()
        try {
            pause()
            assertEquals("a page that is only paused keeps showing its press", 1, sources.size)
            assertTrue(pressed)

            assertFalse("the next page must not show while the covered page still shows the press", next.firstFrameMayDraw())
            compose.waitForIdle()
            assertEquals(2, sources.size)
            assertNotSame(sources[0], sources[1])
            assertTrue("the press is cancelled on the source it was shown on", held.last() is PressInteraction.Cancel)
            assertFalse(pressed)
            assertFalse("the covered page has not drawn without the press yet", next.firstFrameMayDraw())

            drawThisPage()
            assertFalse("the clean frame is drawn in this frame, so the next page waits one more", next.firstFrameMayDraw())
            elapse(16)
            assertTrue(next.firstFrameMayDraw())
        } finally {
            next.release()
            scope.cancel()
        }
    }

    @Test fun a_page_covered_by_another_app_drops_its_press_shortly_after_it_pauses() {
        val (held, scope) = showHeldControl()
        try {
            pause()
            elapse(SettingsPressHandoff.COVERED_PRESS_TIMEOUT_MS - 1)
            assertTrue("the press keeps playing while the next screen opens", pressed)
            assertEquals(1, sources.size)

            elapse(1)
            assertEquals(2, sources.size)
            assertTrue(held.last() is PressInteraction.Cancel)
            assertFalse(pressed)
        } finally {
            scope.cancel()
        }
    }

    @Test fun a_page_that_resumes_before_it_is_covered_keeps_its_press_source() {
        val (_, scope) = showHeldControl()
        try {
            pause()
            resume()
            elapse(SettingsPressHandoff.COVERED_PRESS_TIMEOUT_MS * 2)
            assertEquals(1, sources.size)
            assertTrue(pressed)
        } finally {
            scope.cancel()
        }
    }

    @Test fun a_page_still_in_front_is_left_alone_when_another_page_draws_its_first_frame() {
        val (held, scope) = showHeldControl()
        val next = openingPage()
        try {
            assertTrue(next.firstFrameMayDraw())
            compose.waitForIdle()
            assertEquals(1, sources.size)
            assertTrue(held.last() is PressInteraction.Press)
        } finally {
            next.release()
            scope.cancel()
        }
    }

    @Test fun the_next_page_stops_waiting_for_a_covered_page_that_never_redraws() {
        val silent = OtherPage(compose.activity, Lifecycle.State.STARTED)
        val hidden = OtherPage(compose.activity, Lifecycle.State.STARTED, shown = false)
        val next = openingPage()
        try {
            assertFalse(next.firstFrameMayDraw())
            assertFalse("another frame alone does not release the hold", next.firstFrameMayDraw())
            elapse(SettingsPressHandoff.FIRST_FRAME_HOLD_LIMIT_MS)
            assertTrue(next.firstFrameMayDraw())
            assertEquals("a hidden page cannot show a press, so it is not asked to drop one", 0, hidden.page.epoch.intValue)
            assertEquals(1, silent.page.epoch.intValue)
        } finally {
            next.release()
            hidden.release()
            silent.release()
        }
    }

    @Test fun each_time_its_page_is_covered_the_navigation_row_indication_moves_to_a_fresh_source() {
        val indication = RecordingIndication()
        compose.setContent {
            SettingsActivityChrome {
                CompositionLocalProvider(LocalIndication provides indication) {
                    AppNavigationRow(title = "title", description = "description", onClick = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, indication.sources.size)

        repeat(2) { round ->
            val next = openingPage()
            try {
                pause()
                assertFalse(next.firstFrameMayDraw())
                compose.waitForIdle()
                assertEquals(round + 2, indication.sources.size)
                assertNotSame(indication.sources[round], indication.sources[round + 1])
            } finally {
                next.release()
            }
            resume()
        }
    }
}
