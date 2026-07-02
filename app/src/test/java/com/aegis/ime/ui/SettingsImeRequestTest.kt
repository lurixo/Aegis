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
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsImeRequestTest {

    @Test fun ime_request_focuses_the_test_target_before_showing_input() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val calls = ArrayList<String>()
        var shownFor: View? = null
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                calls.add("focus")
                target.requestFocus()
            },
            showSoftInput = {
                calls.add("show")
                shownFor = it
                true
            },
            isReady = { true },
            isImeVisible = { shownFor != null },
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("focus", "show"), calls)
        assertNotNull(shownFor)
    }

    @Test fun ime_request_waits_for_focused_target_before_showing_input_on_first_entry() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val calls = ArrayList<String>()
        var shownFor: View? = null
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                calls.add("focus-request")
                target.post {
                    calls.add("target-focus")
                    target.requestFocus()
                }
            },
            showSoftInput = {
                calls.add("show")
                shownFor = it
                true
            },
            isReady = { true },
            isImeVisible = { shownFor != null },
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("focus-request", "target-focus", "show"), calls)
        assertSame(target, shownFor)
    }

    @Test fun ime_request_repeats_for_an_already_focused_target() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)
        target.requestFocus()

        val calls = ArrayList<String>()
        val shownFor = ArrayList<View>()
        repeat(2) {
            val alreadyShown = shownFor.size
            host.requestImeWhenReady(
                context = activity,
                focusTarget = {
                    calls.add("focus")
                    target.requestFocus()
                },
                showSoftInput = {
                    calls.add("show")
                    shownFor.add(it)
                    true
                },
                isReady = { true },
                isImeVisible = { shownFor.size > alreadyShown },
            )
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(listOf("focus", "show", "focus", "show"), calls)
        assertEquals(listOf(target, target), shownFor)
    }

    @Test fun ime_request_retries_when_startup_show_request_is_not_accepted_yet() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val shownFor = ArrayList<View>()
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                target.requestFocus()
            },
            showSoftInput = {
                shownFor.add(it)
                shownFor.size >= 3
            },
            isReady = { true },
            isImeVisible = { shownFor.size >= 3 },
            retryDelaysMs = longArrayOf(0L, 0L, 0L, 0L),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(target, target, target), shownFor)
    }

    @Test fun ime_request_front_loads_retries_during_startup_rebind_latency() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val shownFor = ArrayList<View>()
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                target.requestFocus()
            },
            showSoftInput = {
                shownFor.add(it)
                true
            },
            restartInput = {},
            isReady = { true },
            isImeVisible = { false },
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))

        assertEquals(List(6) { target }, shownFor)
    }

    @Test fun ime_request_restarts_input_connection_before_showing_input() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val calls = ArrayList<String>()
        var shownFor: View? = null
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                calls.add("focus")
                target.requestFocus()
            },
            showSoftInput = {
                calls.add("show")
                shownFor = it
                true
            },
            restartInput = {
                calls.add("restart")
                assertSame(target, it)
            },
            isReady = { true },
            isImeVisible = { shownFor != null },
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("focus", "restart", "show"), calls)
    }

    @Test fun ime_request_keeps_retrying_when_accepted_show_is_still_not_visible() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val shownFor = ArrayList<View>()
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                target.requestFocus()
            },
            showSoftInput = {
                shownFor.add(it)
                true
            },
            isReady = { true },
            isImeVisible = { shownFor.size >= 3 },
            retryDelaysMs = longArrayOf(0L, 0L, 0L, 0L),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(target, target, target), shownFor)
    }

    @Test fun ime_request_stops_pending_retries_when_field_focus_predicate_turns_false() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        var fieldFocused = true
        val calls = ArrayList<String>()
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                calls.add("focus")
                target.requestFocus()
            },
            showSoftInput = {
                calls.add("show")
                true
            },
            restartInput = {
                calls.add("restart")
                assertSame(target, it)
            },
            isReady = { true },
            isImeVisible = { false },
            shouldContinue = { fieldFocused },
            retryDelaysMs = longArrayOf(0L, 1_000L, 1_000L),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("focus", "restart", "show"), calls)

        fieldFocused = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500))

        assertEquals(listOf("focus", "restart", "show"), calls)
    }

    @Test fun ime_request_stops_pending_retries_when_host_detaches() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val calls = ArrayList<String>()
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                calls.add("focus")
                target.requestFocus()
            },
            showSoftInput = {
                calls.add("show")
                true
            },
            restartInput = {
                calls.add("restart")
                assertSame(target, it)
            },
            isReady = { true },
            isImeVisible = { false },
            retryDelaysMs = longArrayOf(0L, 1_000L, 1_000L),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("focus", "restart", "show"), calls)

        activity.setContentView(FrameLayout(activity))
        assertFalse(host.isAttachedToWindow)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500))

        assertEquals(listOf("focus", "restart", "show"), calls)
    }

    @Test fun ime_request_cancel_stops_pending_retries() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val calls = ArrayList<String>()
        val request = host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                calls.add("focus")
                target.requestFocus()
            },
            showSoftInput = {
                calls.add("show")
                true
            },
            restartInput = {
                calls.add("restart")
                assertSame(target, it)
            },
            isReady = { true },
            isImeVisible = { false },
            retryDelaysMs = longArrayOf(0L, 1_000L, 1_000L),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("focus", "restart", "show"), calls)

        request.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500))

        assertEquals(listOf("focus", "restart", "show"), calls)
    }

    @Test fun ime_request_does_not_show_after_request_token_is_superseded() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        var activeToken = 1
        val requestToken = activeToken
        val calls = ArrayList<String>()
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                calls.add("focus")
                target.requestFocus()
            },
            showSoftInput = {
                calls.add("show")
                true
            },
            restartInput = {
                calls.add("restart")
            },
            isReady = { true },
            isImeVisible = { false },
            shouldContinue = { activeToken == requestToken },
            retryDelaysMs = longArrayOf(0L),
        )

        activeToken += 1
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<String>(), calls)
    }

    @Test fun ime_request_covers_slow_update_rebind_before_giving_up() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val target = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        host.addView(target)
        activity.setContentView(host)

        val shownFor = ArrayList<View>()
        host.requestImeWhenReady(
            context = activity,
            focusTarget = {
                target.requestFocus()
            },
            showSoftInput = {
                shownFor.add(it)
                true
            },
            restartInput = {},
            isReady = { true },
            isImeVisible = { shownFor.size >= 12 },
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_500))

        assertEquals(List(12) { target }, shownFor)
    }
}
