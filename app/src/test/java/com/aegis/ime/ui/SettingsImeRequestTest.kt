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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
            },
            isReady = { true },
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
            },
            isReady = { true },
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
            host.requestImeWhenReady(
                context = activity,
                focusTarget = {
                    calls.add("focus")
                    target.requestFocus()
                },
                showSoftInput = {
                    calls.add("show")
                    shownFor.add(it)
                },
                isReady = { true },
            )
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(listOf("focus", "show", "focus", "show"), calls)
        assertEquals(listOf(target, target), shownFor)
    }
}
