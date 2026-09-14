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

import android.graphics.Rect
import android.app.Activity
import org.junit.After
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardImageViewTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()
    private val hosts = ArrayList<ActivityController<Activity>>()

    @After fun close() { hosts.forEach { it.pause().stop().destroy() } }

    private fun entry(): ClipEntry {
        val dir = temp.newFolder()
        val hash = "a".repeat(64)
        File(dir, "$hash.png").writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="))
        return ClipEntry.image(dir, hash, "image/png")
    }

    private fun layout(view: View, width: Int) {
        if (!view.isAttachedToWindow) {
            val host = Robolectric.buildActivity(Activity::class.java).setup()
            hosts.add(host)
            host.get().setContentView(view)
        }
        assertTrue(view.isAttachedToWindow)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, 640)
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun tap(root: ViewGroup, target: View) {
        val bounds = Rect(0, 0, target.width, target.height)
        root.offsetDescendantRectToMyCoords(target, bounds)
        assertTrue(bounds.left >= 0 && bounds.right <= root.width)
        assertTrue(bounds.top >= 0 && bounds.bottom <= root.height)
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 16L)) {
            val event = MotionEvent.obtain(0, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            root.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun thumbnail_tap_dispatches_image_and_never_uri_or_reference_text_at_both_widths() {
        for (width in listOf(320, 480)) {
            val image = entry()
            var picked: ClipEntry? = null
            val texts = ArrayList<String>()
            val view = ClipboardView(context).apply {
                historyProvider = { listOf(image, ClipEntry.of("text")) }
                onPickImage = { picked = it }
                onPick = { texts.add(it) }
                applyPalette(ImePalette.STATIC_LIGHT)
                refresh()
            }
            layout(view, width)
            val thumbnail = descendants(view).filterIsInstance<ImageView>().single()
            assertEquals(context.getString(R.string.clip_image_label), thumbnail.contentDescription)
            tap(view, thumbnail)
            assertEquals(image, picked)
            assertTrue(texts.isEmpty())
            assertFalse(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == image.key })
        }
    }

    @Test fun image_long_press_copies_image_and_has_no_text_edit_or_phrase_actions() {
        val image = entry()
        var copied: ClipEntry? = null
        val view = ClipboardView(context).apply {
            historyProvider = { listOf(image) }
            onCopyImage = { copied = it }
            applyPalette(ImePalette.STATIC_LIGHT)
            refresh()
        }
        layout(view, 320)
        descendants(view).filterIsInstance<ImageView>().single().performLongClick()
        layout(view, 320)
        val overlay = view.getChildAt(1)
        val items = descendants(overlay).filterIsInstance<TextView>()
        assertFalse(items.any { it.text.toString() == context.getString(R.string.clip_add_phrase) })
        assertFalse(items.any { it.text.toString() == context.getString(R.string.clip_split_title) })
        val copy = items.single { it.text.toString() == context.getString(R.string.clip_image_copy) }
        tap(view, copy)
        assertEquals(image, copied)
    }

    @Test fun missing_image_never_dispatches_a_text_pick() {
        val image = entry()
        image.imageFile()!!.delete()
        var picked = false
        val view = ClipboardView(context).apply {
            historyProvider = { listOf(image) }
            onPickImage = { picked = true }
            onPick = { picked = true }
            applyPalette(ImePalette.STATIC_LIGHT)
            refresh()
        }
        layout(view, 480)
        tap(view, descendants(view).filterIsInstance<ImageView>().single())
        assertFalse(picked)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString() == context.getString(R.string.clip_image_unavailable) })
    }
}
