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

import java.nio.file.Files
import java.io.File
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.user.asClipEntries
import com.aegis.ime.user.clipEntries
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.user.ClipSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardViewInteractionTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun layout(v: View, w: Int = 480, h: Int = 700) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun send(v: View, action: Int, x: Float, y: Float, t: Long) =
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun sendPointers(
        view: View,
        action: Int,
        time: Long,
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
    ): Boolean {
        val properties = Array(ids.size) {
            MotionEvent.PointerProperties().apply {
                id = ids[it]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(ids.size) {
            MotionEvent.PointerCoords().apply {
                x = xs[it]
                y = ys[it]
                pressure = 1f
                size = 1f
            }
        }
        return view.dispatchTouchEvent(
            MotionEvent.obtain(0, time, action, ids.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0),
        )
    }

    private fun leftSwipe(target: View, dx: Float) {
        send(target, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        send(target, MotionEvent.ACTION_MOVE, 320f - dx, 12f, 16)
        send(target, MotionEvent.ACTION_UP, 320f - dx, 12f, 32)
    }

    private fun centerInRoot(root: View, target: View): Pair<Float, Float> {
        var x = target.width / 2f
        var y = target.height / 2f
        var current = target
        while (current !== root) {
            val parent = current.parent as View
            x += current.left + current.translationX - parent.scrollX
            y += current.top + current.translationY - parent.scrollY
            current = parent
        }
        return x to y
    }

    private fun rootSwipe(root: View, target: View, dx: Float) {
        val (x, y) = centerInRoot(root, target)
        send(root, MotionEvent.ACTION_DOWN, x, y, 0)
        send(root, MotionEvent.ACTION_MOVE, x + dx, y, 16)
        send(root, MotionEvent.ACTION_UP, x + dx, y, 32)
    }

    private fun rootTap(root: View, target: View) {
        val bounds = boundsInRoot(root as ViewGroup, target)
        send(root, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        send(root, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 16)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rootTap(root: View, x: Float, y: Float) {
        send(root, MotionEvent.ACTION_DOWN, x, y, 0)
        send(root, MotionEvent.ACTION_UP, x, y, 16)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun boundsInRoot(root: ViewGroup, target: View): Rect = Rect(0, 0, target.width, target.height).also {
        root.offsetDescendantRectToMyCoords(target, it)
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun textViews(root: View): List<TextView> = allViews(root).filterIsInstance<TextView>()
    private fun actionButtons(root: View): List<TextView> = textViews(root).filter {
        it.compoundDrawables[0] != null && it.foreground == null && it.hasOnClickListeners()
    }
    private fun bodyOf(root: View, text: String): TextView =
        textViews(root).first { it.text?.toString() == text }
    private fun mainOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(0)
    private fun overlayOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(1)
    private fun labels(root: View): List<String> = textViews(root).mapNotNull { it.text?.toString() }
    private fun clickText(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() } ?: return false
        tv.performClick(); return true
    }
    private fun clickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        v.performClick(); return true
    }
    private fun dp(value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()
    private fun draw(view: View) {
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
    }
    private fun rippleMask(view: View): GradientDrawable =
        ((view.foreground as RippleDrawable).findDrawableByLayerId(android.R.id.mask) as GradientDrawable)
    private fun assertBoxedSymbol(drawable: android.graphics.drawable.Drawable) {
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.draw(Canvas(bitmap))
        val middleX = bitmap.width / 2
        val middleY = bitmap.height / 2
        assertTrue(Color.alpha(bitmap.getPixel(middleX, 0)) > 0)
        assertTrue(Color.alpha(bitmap.getPixel(middleX, bitmap.height - 1)) > 0)
        assertTrue(Color.alpha(bitmap.getPixel(0, middleY)) > 0)
        assertTrue(Color.alpha(bitmap.getPixel(bitmap.width - 1, middleY)) > 0)
    }
    private fun assertBoxedSymbol(view: View) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val box = dp(15)
        val left = (view.width - box) / 2
        val top = (view.height - box) / 2
        val centerX = view.width / 2
        val centerY = view.height / 2
        assertTrue(bitmap.getPixel(centerX, top) != pal.keySurface)
        assertTrue(bitmap.getPixel(centerX, top + box - 1) != pal.keySurface)
        assertTrue(bitmap.getPixel(left, centerY) != pal.keySurface)
        assertTrue(bitmap.getPixel(left + box - 1, centerY) != pal.keySurface)
    }
    private fun headerOf(v: ClipboardView, text: String): View = bodyOf(v, text).parent as View

    private fun swipeActions(v: ClipboardView, text: String): List<View> {
        val strip = (headerOf(v, text).parent as ViewGroup).getChildAt(0) as ViewGroup
        return (0 until strip.childCount).map(strip::getChildAt)
    }

    private fun flushMotion() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun assertImmediateKey(owner: ClipboardView, action: View, name: String) {
        assertTrue("$name is registered as an immediate key", owner.isImmediateActionForTest(action))
        assertTrue("$name width is at least 48dp", action.width >= dp(48))
        assertTrue("$name height is at least 48dp", action.height >= dp(48))
        assertTrue("$name owns the keyboard feedback face", action.background === owner.immediateActionDrawableForTest(action))
        assertNull("$name has no platform ripple", action.foreground)
        owner.hapticEnabled = true
        send(action, MotionEvent.ACTION_DOWN, action.width / 2f, action.height / 2f, 0)
        assertEquals(
            "$name enters the keyboard pressed state",
            1f,
            requireNotNull(owner.immediateActionFeedbackLevelForTest(action)),
            0f,
        )
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(action).lastHapticFeedbackPerformed())
        send(action, MotionEvent.ACTION_MOVE, action.width / 2f + dp(2), action.height / 2f + dp(1), 12)
        assertEquals(
            "$name tolerates slight drift",
            1f,
            requireNotNull(owner.immediateActionFeedbackLevelForTest(action)),
            0f,
        )
        send(action, MotionEvent.ACTION_CANCEL, action.width / 2f + dp(2), action.height / 2f + dp(1), 24)
        flushMotion()
        assertEquals(
            "$name cancels cleanly",
            0f,
            requireNotNull(owner.immediateActionFeedbackLevelForTest(action)),
            0f,
        )
    }

    @Test fun immediate_clipboard_actions_use_keyboard_feedback_haptics_and_48dp_targets() {
        val top = phraseView(listOf("你好"))
        layout(top)
        assertImmediateKey(
            top,
            allViews(top).single { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_add_phrase) },
            "top add",
        )

        val swipe = clipView(listOf("第一条")).apply { revealSwipeForTest("第一条") }
        layout(swipe)
        assertImmediateKey(swipe, swipeActions(swipe, "第一条").first(), "revealed action")

        val expanded = clipView(listOf("第一条")).apply { expandForTest("第一条") }
        layout(expanded)
        assertImmediateKey(expanded, actionButtons(expanded).first(), "expanded action")

        val selected = clipView(listOf("a", "b")).apply { enterSelectForTest(listOf("a")) }
        layout(selected)
        assertImmediateKey(selected, requireNotNull(selected.listRowViewForTest(0)), "selection row")
        assertImmediateKey(selected, requireNotNull(selected.selectAllActionForTest()), "select all")
        assertImmediateKey(selected, requireNotNull(selected.cancelSelectActionForTest()), "cancel selection")

        val sorted = phraseView(listOf("你好")).apply { enterSortModeForTest() }
        layout(sorted)
        assertImmediateKey(
            sorted,
            textViews(sorted).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_done) },
            "finish sorting",
        )

        val categorySorted = phraseView(listOf("你好")).apply { enterCategorySortModeForTest() }
        layout(categorySorted)
        assertImmediateKey(
            categorySorted,
            textViews(categorySorted).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_done) },
            "finish category sorting",
        )

        val split = clipView(listOf("one two")).apply { showSplitForTest("one two") }
        layout(split)
        assertImmediateKey(
            split,
            textViews(overlayOf(split)).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_copy_all) },
            "copy all split blocks",
        )
    }

    @Test fun content_navigation_keeps_complex_gestures_while_simple_menu_actions_use_key_feedback() {
        val phrase = phraseView(listOf("你好"))
        layout(phrase)
        val body = bodyOf(phrase, "你好")
        val chevron = allViews(phrase).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_expand)
        }
        val tab = textViews(phrase).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_phrases) }
        val category = textViews(phrase).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
        for ((name, control) in listOf("body" to body, "chevron" to chevron, "tab" to tab, "category" to category)) {
            assertFalse("$name stays outside immediate-key feedback", phrase.isImmediateActionForTest(control))
            assertTrue("$name keeps its existing ripple", control.foreground is RippleDrawable)
        }
        phrase.showPhraseManageMenuForTest()
        val menu = textViews(overlayOf(phrase)).first { it.hasOnClickListeners() }
        assertTrue(phrase.isImmediateActionForTest(menu))
        assertTrue(menu.background === phrase.immediateActionDrawableForTest(menu))
        assertNull(menu.foreground)

        val split = clipView(listOf("one two")).apply { showSplitForTest("one two") }
        layout(split)
        val chip = textViews(overlayOf(split)).first { it.text?.toString() == "one" }
        assertFalse(split.isImmediateActionForTest(chip))
        assertTrue(chip.foreground is RippleDrawable)

        val sorted = phraseView(listOf("你好")).apply { enterCategorySortModeForTest() }
        layout(sorted)
        val handle = (requireNotNull(sorted.listRowViewForTest(0)) as ViewGroup).getChildAt(1)
        assertFalse(sorted.isImmediateActionForTest(handle))
    }

    private fun assertSwipeActionStrip(v: ClipboardView, text: String, descriptions: List<String>): List<View> {
        val actions = swipeActions(v, text)
        val size = dp(48)
        val gap = dp(4)
        val strip = actions.first().parent as View
        assertEquals(descriptions, actions.map { it.contentDescription?.toString() })
        assertTrue(actions.all { it !is TextView && it.hasOnClickListeners() })
        assertTrue(actions.all { it.width == size && it.height == size })
        assertTrue(actions.all { v.isImmediateActionForTest(it) })
        assertTrue(actions.all { it.background === v.immediateActionDrawableForTest(it) })
        assertTrue(actions.all { it.foreground == null })
        assertEquals(descriptions.size * (size + gap), strip.width)
        assertEquals(gap, actions.first().left)
        assertEquals(strip.width, actions.last().right)
        actions.zipWithNext().forEach { (left, right) ->
            assertEquals(gap, right.left - left.right)
            assertTrue(left.right <= right.left)
        }
        val header = bodyOf(v, text).parent as View
        val frame = strip.parent as View
        assertEquals(-strip.width.toFloat(), header.translationX, 0f)
        assertEquals(gap.toFloat(), strip.left + actions.first().left - (header.right + header.translationX), 0f)
        assertEquals(frame.width, strip.right)
        return actions
    }

    private fun clipView(history: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history.asClipEntries() }; applyPalette(pal); refresh()
    }
    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }


    @Test fun left_swipe_on_a_clipboard_card_reveals_actions_and_never_commits() {
        var picked: String? = null
        val v = clipView(listOf("第一条", "第二条")).apply { onPick = { picked = it } }
        layout(v)
        leftSwipe(bodyOf(v, "第一条"), dx = 200f)
        assertEquals("the card's action row is revealed", "第一条", v.swipeRevealedForTest())
        assertNull("a left swipe must NOT 上屏", picked)
    }

    @Test fun a_SHORT_left_swipe_on_a_clipboard_card_snaps_back_and_never_commits() {
        var picked: String? = null
        val v = clipView(listOf("第一条", "第二条")).apply { onPick = { picked = it } }
        layout(v)
        leftSwipe(bodyOf(v, "第一条"), dx = 22f)
        flushMotion()
        assertNull("a sub-midpoint left swipe settles back closed", v.swipeRevealedForTest())
        assertEquals(0f, headerOf(v, "第一条").translationX, 0f)
        assertNull("a short left swipe must NOT 上屏", picked)
    }

    @Test fun left_swipe_on_a_phrase_card_reveals_actions_and_never_commits() {
        var picked: String? = null
        val v = phraseView(listOf("你好", "在吗")).apply { onPick = { picked = it } }
        layout(v)
        leftSwipe(bodyOf(v, "你好"), dx = 200f)
        assertEquals("你好", v.swipeRevealedForTest())
        assertNull(picked)
    }

    @Test fun a_SHORT_left_swipe_on_a_phrase_card_snaps_back_and_never_commits() {
        var picked: String? = null
        val v = phraseView(listOf("你好", "在吗")).apply { onPick = { picked = it } }
        layout(v)
        leftSwipe(bodyOf(v, "你好"), dx = 22f)
        flushMotion()
        assertNull(v.swipeRevealedForTest())
        assertEquals(0f, headerOf(v, "你好").translationX, 0f)
        assertNull(picked)
    }

    @Test fun a_slow_horizontal_swipe_on_a_phrase_card_never_enters_drag() {
        val v = phraseView(listOf("你好", "在吗"))
        layout(v)
        val body = bodyOf(v, "你好")
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        send(body, MotionEvent.ACTION_MOVE, 320f - slop * 0.75f, 12f, 16)
        shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 100),
        )
        assertFalse("horizontal intent must cancel the pending long-press-drag", v.isDraggingForTest())
        send(body, MotionEvent.ACTION_MOVE, 120f, 12f, 32)
        send(body, MotionEvent.ACTION_UP, 120f, 12f, 48)
        assertEquals("the gesture settles as a swipe reveal, not a drag", "你好", v.swipeRevealedForTest())
        assertFalse(v.isDraggingForTest())
    }

    @Test fun a_swipe_clears_the_clipboard_card_press_highlight() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        val body = bodyOf(v, "第一条")
        send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        body.isPressed = true
        send(body, MotionEvent.ACTION_MOVE, 120f, 12f, 16)
        assertFalse("the swipe clears the pressed state as soon as it is recognized", body.isPressed)
        send(body, MotionEvent.ACTION_UP, 120f, 12f, 32)
        assertFalse("the item never stays stuck darkened after the swipe", body.isPressed)
        assertEquals("and the swipe still reveals", "第一条", v.swipeRevealedForTest())
    }

    @Test fun a_swipe_clears_the_phrase_card_press_highlight() {
        val v = phraseView(listOf("你好", "在吗"))
        layout(v)
        val body = bodyOf(v, "你好")
        send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        body.isPressed = true
        send(body, MotionEvent.ACTION_MOVE, 120f, 12f, 16)
        assertFalse("the phrase swipe clears the pressed state on recognition", body.isPressed)
        send(body, MotionEvent.ACTION_UP, 120f, 12f, 32)
        assertFalse("the phrase item never stays stuck darkened after the swipe", body.isPressed)
        assertEquals("你好", v.swipeRevealedForTest())
    }

    @Test fun a_plain_tap_does_not_reveal_and_still_commits() {
        var picked: String? = null
        val v = clipView(listOf("第一条")).apply { onPick = { picked = it } }
        layout(v)
        val body = bodyOf(v, "第一条")
        send(body, MotionEvent.ACTION_DOWN, 40f, 12f, 0)
        send(body, MotionEvent.ACTION_UP, 40f, 12f, 8)
        assertNull("a tap reveals nothing (the swipe handler did not consume it)", v.swipeRevealedForTest())
        assertTrue("the tap reaches the card's onClick", body.performClick())
        assertEquals("…which 上屏s the clip", "第一条", picked)
    }

    @Test fun a_clearly_vertical_drag_scrolls_and_neither_reveals_nor_commits() {
        var picked: String? = null
        val v = clipView(listOf("第一条", "第二条")).apply { onPick = { picked = it } }
        layout(v)
        val body = bodyOf(v, "第一条")
        send(body, MotionEvent.ACTION_DOWN, 40f, 12f, 0)
        send(body, MotionEvent.ACTION_MOVE, 40f, 212f, 16)
        send(body, MotionEvent.ACTION_UP, 40f, 212f, 32)
        assertNull("a vertical drag does not reveal", v.swipeRevealedForTest())
        assertNull("a vertical drag does not 上屏", picked)
    }

    @Test fun closed_row_swipe_tracks_the_finger_and_clamps_to_the_strip_width() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        val body = bodyOf(v, "第一条")
        val header = headerOf(v, "第一条")
        send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        send(body, MotionEvent.ACTION_MOVE, 260f, 12f, 16)
        assertEquals(-60f, header.translationX, 0f)
        send(body, MotionEvent.ACTION_MOVE, 100f, 12f, 32)
        assertEquals(-dp(208).toFloat(), header.translationX, 0f)
        send(body, MotionEvent.ACTION_MOVE, 400f, 12f, 48)
        assertEquals(0f, header.translationX, 0f)
        send(body, MotionEvent.ACTION_UP, 400f, 12f, 64)
        flushMotion()
        assertEquals(0f, header.translationX, 0f)
        assertNull(v.swipeRevealedForTest())
    }

    @Test fun release_past_half_settles_revealed_with_translation_animation() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val v = clipView(listOf("第一条", "第二条"))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            layout(v)
            val body = bodyOf(v, "第一条")
            val header = headerOf(v, "第一条")
            send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
            send(body, MotionEvent.ACTION_MOVE, 190f, 12f, 16)
            send(body, MotionEvent.ACTION_UP, 190f, 12f, 32)
            assertEquals("bookkeeping updates on release", "第一条", v.swipeRevealedForTest())
            assertEquals("the settle starts from the drag position", -130f, header.translationX, 0f)
            flushMotion()
            assertEquals(-dp(208).toFloat(), header.translationX, 0f)
            assertEquals(1f, header.alpha, 0f)
            assertTrue("the settled row is not rebuilt", header === headerOf(v, "第一条"))
            v.refresh()
            layout(v)
            assertEquals("a later rebuild pins the identical position", -dp(208).toFloat(), headerOf(v, "第一条").translationX, 0f)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun release_short_of_half_settles_closed() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        val body = bodyOf(v, "第一条")
        val header = headerOf(v, "第一条")
        send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        send(body, MotionEvent.ACTION_MOVE, 250f, 12f, 16)
        send(body, MotionEvent.ACTION_UP, 250f, 12f, 32)
        flushMotion()
        assertEquals(0f, header.translationX, 0f)
        assertNull(v.swipeRevealedForTest())
    }

    @Test fun revealed_row_rightward_drag_tracks_and_settles_closed() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        val body = bodyOf(v, "第一条")
        val header = headerOf(v, "第一条")
        leftSwipe(body, dx = 200f)
        flushMotion()
        assertEquals("第一条", v.swipeRevealedForTest())
        assertEquals(-dp(208).toFloat(), header.translationX, 0f)
        send(body, MotionEvent.ACTION_DOWN, 100f, 12f, 0)
        send(body, MotionEvent.ACTION_MOVE, 180f, 12f, 16)
        assertEquals(-dp(208) + 80f, header.translationX, 0f)
        send(body, MotionEvent.ACTION_MOVE, 220f, 12f, 32)
        send(body, MotionEvent.ACTION_UP, 220f, 12f, 48)
        flushMotion()
        assertEquals(0f, header.translationX, 0f)
        assertNull(v.swipeRevealedForTest())
        assertTrue("the closed row is not rebuilt", body === bodyOf(v, "第一条"))
    }

    @Test fun vertical_drag_never_translates_the_header() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        val body = bodyOf(v, "第一条")
        val header = headerOf(v, "第一条")
        send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        send(body, MotionEvent.ACTION_MOVE, 320f, 112f, 16)
        assertEquals(0f, header.translationX, 0f)
        send(body, MotionEvent.ACTION_MOVE, 300f, 312f, 32)
        assertEquals(0f, header.translationX, 0f)
        send(body, MotionEvent.ACTION_UP, 300f, 312f, 48)
        flushMotion()
        assertEquals(0f, header.translationX, 0f)
        assertNull(v.swipeRevealedForTest())
    }

    @Test fun phrase_horizontal_lock_cancels_the_pending_long_press_drag() {
        val v = phraseView(listOf("你好", "在吗"))
        layout(v)
        val body = bodyOf(v, "你好")
        val header = headerOf(v, "你好")
        send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        send(body, MotionEvent.ACTION_MOVE, 250f, 12f, 16)
        assertEquals(-70f, header.translationX, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        assertFalse("a locked-in swipe never morphs into drag", v.isDraggingForTest())
        send(body, MotionEvent.ACTION_MOVE, 120f, 12f, 32)
        send(body, MotionEvent.ACTION_UP, 120f, 12f, 48)
        flushMotion()
        assertFalse(v.isDraggingForTest())
        assertEquals("你好", v.swipeRevealedForTest())
        assertEquals(-dp(208).toFloat(), header.translationX, 0f)
    }

    @Test fun stationary_phrase_hold_still_starts_drag_reorder() {
        val v = phraseView(listOf("你好", "在吗"))
        layout(v)
        val body = bodyOf(v, "你好")
        send(body, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        assertTrue("a stationary hold still enters drag-reorder", v.isDraggingForTest())
        send(body, MotionEvent.ACTION_CANCEL, 320f, 12f, 16)
        assertFalse(v.isDraggingForTest())
        assertEquals(0f, headerOf(v, "你好").translationX, 0f)
    }

    @Test fun covered_strip_actions_stay_untappable_until_the_row_is_revealed() {
        var picked: String? = null
        val adds = ArrayList<List<String>>()
        val v = clipView(listOf("第一条")).apply {
            onPick = { picked = it }
            onAddCategoryThenAdd = { adds += it }
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            layout(v)
            val plus = allViews(v).single { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_add_phrase) }
            rootTap(v, plus)
            assertEquals("a tap over the covered strip lands on the row body", "第一条", picked)
            assertTrue(adds.isEmpty())
            picked = null
            rootSwipe(v, bodyOf(v, "第一条"), -200f)
            flushMotion()
            assertEquals("第一条", v.swipeRevealedForTest())
            rootTap(v, plus)
            assertEquals(listOf(listOf("第一条")), adds)
            assertNull(picked)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun revealing_a_second_row_closes_the_previously_revealed_row() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        leftSwipe(bodyOf(v, "第一条"), dx = 200f)
        flushMotion()
        assertEquals("第一条", v.swipeRevealedForTest())
        leftSwipe(bodyOf(v, "第二条"), dx = 200f)
        flushMotion()
        assertEquals("第二条", v.swipeRevealedForTest())
        layout(v)
        assertEquals(0f, headerOf(v, "第一条").translationX, 0f)
        assertEquals(-dp(208).toFloat(), headerOf(v, "第二条").translationX, 0f)
    }

    @Test fun refresh_renders_new_history_items_without_reopening_panel() {
        val history = mutableListOf("old")
        val v = ClipboardView(ctx).apply {
            historyProvider = { history.asClipEntries() }
            applyPalette(pal)
            refresh()
        }
        assertTrue("initial item is visible", "old" in labels(v))
        history.add(0, "new")
        v.refresh()
        assertTrue("new item appears in the existing panel", "new" in labels(v))
        assertTrue("existing item remains visible", "old" in labels(v))
    }

    @Test fun split_selection_rebuilds_in_source_order_and_toggles_duplicate_items_by_index() {
        val source = "检查一下，检查"
        assertEquals(listOf("检查", "一下", "，", "检查"), ClipSplitter.copyBlocks(source))
        val changed = ArrayList<String>()
        val copied = ArrayList<List<String>>()
        val v = ClipboardView(ctx).apply {
            onSplitSelectionChanged = { changed.add(it) }
            onCopyBlocksToAegis = { copied.add(it) }
            applyPalette(pal)
            refresh()
        }
        v.showSplitForTest(source)

        assertTrue(clickText(overlayOf(v), "检查"))
        assertEquals("检查", changed.last())
        assertEquals(setOf(0), v.splitSelectedForTest())
        assertTrue(clickText(overlayOf(v), "，"))
        assertEquals("检查，", changed.last())
        assertEquals(setOf(0, 2), v.splitSelectedForTest())
        assertTrue(clickText(overlayOf(v), "一下"))
        assertEquals("检查一下，", changed.last())
        assertEquals(setOf(0, 1, 2), v.splitSelectedForTest())
        assertTrue(clickText(overlayOf(v), "检查"))
        assertEquals("一下，", changed.last())
        assertEquals(setOf(1, 2), v.splitSelectedForTest())
        assertTrue(copied.isEmpty())

        assertTrue(clickText(overlayOf(v), "一下"))
        assertEquals("，", changed.last())
        assertTrue(clickText(overlayOf(v), "，"))
        assertEquals("", changed.last())
        assertTrue(v.splitSelectedForTest().isEmpty())
        assertTrue(copied.isEmpty())

        val duplicateChecks = textViews(overlayOf(v)).filter {
            it.text?.toString() == "检查" && it.hasOnClickListeners()
        }
        assertEquals(2, duplicateChecks.size)
        duplicateChecks[1].performClick()
        assertEquals("检查", changed.last())
        assertEquals(setOf(3), v.splitSelectedForTest())
        duplicateChecks[1].performClick()
        assertEquals("", changed.last())
        assertTrue(v.splitSelectedForTest().isEmpty())
    }

    @Test fun pure_punctuation_projections_match_the_taskbar_and_clipboard_entries() {
        val taskbarChanges = ArrayList<String>()
        val taskbar = CopyBarController(
            commit = {},
            selectionChanged = { taskbarChanges.add(it) },
            selectionFinished = {},
            dismiss = {},
        )
        taskbar.show("，。")
        taskbar.toggleSplit()
        assertEquals(listOf("，。"), taskbar.blocks)
        assertTrue(taskbar.tapBlock(0) == true)

        val clipboardChanges = ArrayList<String>()
        val clipboard = ClipboardView(ctx).apply {
            onSplitSelectionChanged = { clipboardChanges.add(it) }
            applyPalette(pal)
            refresh()
        }
        clipboard.showSplitForTest("，。")
        assertTrue(clickText(overlayOf(clipboard), "，。"))

        assertEquals(listOf("，。"), taskbarChanges)
        assertEquals(taskbarChanges, clipboardChanges)
    }

    @Test fun every_split_popup_exit_finishes_the_composing_session_once() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val input = InputView(ctx)
        val v = ClipboardView(ctx).apply { applyPalette(pal); refresh() }
        var finishes = 0
        v.onSplitSelectionFinished = { finishes++ }
        activity.get().setContentView(input)
        input.showPanelImmediately(v)

        fun assertSingleFinish(exit: () -> Unit) {
            val before = finishes
            v.showSplitForTest("检查一下，检查")
            exit()
            assertEquals(before + 1, finishes)
            v.hideOverlayForTest()
            assertEquals("a finished split session must not finish twice", before + 1, finishes)
        }

        try {
            assertSingleFinish { v.hideOverlayForTest() }
            assertSingleFinish {
                assertTrue(clickText(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_back)))
            }
            assertSingleFinish { overlayOf(v).performClick() }
            assertSingleFinish { v.resetToDefault() }

            val beforeSwitch = finishes
            v.showSplitForTest("检查一下，检查")
            input.showPanelImmediately(TextView(ctx))
            assertEquals(beforeSwitch + 1, finishes)

            input.showPanelImmediately(v)
            val beforeDetach = finishes
            v.showSplitForTest("检查一下，检查")
            (v.parent as ViewGroup).removeView(v)
            assertEquals(beforeDetach + 1, finishes)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun clipboard_swipe_reveals_four_icon_actions_while_dropdown_reveals_labeled_actions() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        rootSwipe(v, bodyOf(v, "第一条"), -200f)
        layout(v)
        val swipeActions = assertSwipeActionStrip(
            v,
            "第一条",
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_add_phrase),
                ctx.getString(com.aegis.ime.R.string.clip_edit),
                ctx.getString(com.aegis.ime.R.string.clip_split_word),
                ctx.getString(com.aegis.ime.R.string.clip_delete),
            ),
        )
        assertEquals("第一条", v.swipeRevealedForTest())
        assertTrue(actionButtons(v).isEmpty())
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_expand) in allViews(v).mapNotNull { it.contentDescription?.toString() })

        rootSwipe(v, swipeActions.last(), 200f)
        assertNull(v.swipeRevealedForTest())
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_expand)))
        layout(v)
        assertNull(v.swipeRevealedForTest())
        assertEquals(0f, (bodyOf(v, "第一条").parent as View).translationX, 0f)
        assertEquals(
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_phrases),
                ctx.getString(com.aegis.ime.R.string.clip_edit),
                ctx.getString(com.aegis.ime.R.string.clip_split_word),
                ctx.getString(com.aegis.ime.R.string.clip_delete),
            ),
            actionButtons(v).map { it.text.toString() },
        )
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_collapse) in allViews(v).mapNotNull { it.contentDescription?.toString() })
    }

    @Test fun narrow_phrase_swipe_keeps_four_actions_reachable_and_dropdown_actions_distinct() {
        for (width in listOf(320, 360)) {
            val v = phraseView(listOf("你好", "在吗"))
            layout(v, width)
            rootSwipe(v, bodyOf(v, "你好"), -200f)
            layout(v, width)
            assertEquals("你好", v.swipeRevealedForTest())
            val swipeActions = assertSwipeActionStrip(
                v,
                "你好",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_note),
                    ctx.getString(com.aegis.ime.R.string.clip_move),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            )
            swipeActions.forEach { action ->
                val (actionX, _) = centerInRoot(v, action)
                assertTrue(actionX - action.width / 2f >= 0f)
                assertTrue(actionX + action.width / 2f <= v.width)
            }
            assertTrue(actionButtons(v).isEmpty())
            rootSwipe(v, swipeActions.last(), 200f)
            assertNull(v.swipeRevealedForTest())
            layout(v, width)
            val expand = allViews(v).first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_expand) }
            assertTrue(expand.performClick())
            assertNull(v.swipeRevealedForTest())
            layout(v, width)
            assertEquals(
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_note),
                    ctx.getString(com.aegis.ime.R.string.clip_move),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
                actionButtons(v).map { it.text.toString() },
            )
        }
    }

    @Test fun clipboard_swipe_split_reuses_the_expanded_boxed_split_character() {
        val v = clipView(listOf("第一条"))
        v.revealSwipeForTest("第一条")
        layout(v)
        val swipeSplit = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_split_word)
        }
        val asset = requireNotNull(swipeSplit.tag)
        assertEquals("拆", asset)
        assertBoxedSymbol(swipeSplit)
        v.hideSwipeForTest()
        v.expandForTest("第一条")
        layout(v)
        val expandedSplit = actionButtons(v).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_split_word) }
        assertTrue(asset === expandedSplit.tag)
        assertTrue(expandedSplit.contentDescription?.toString()?.startsWith("拆 ") == true)
        assertBoxedSymbol(requireNotNull(expandedSplit.compoundDrawables[0]))
    }

    @Test fun clipboard_swipe_plus_keeps_its_geometry_hit_region_and_action() {
        val pending = ArrayList<List<String>>()
        val view = clipView(listOf("第一条")).apply { onAddCategoryThenAdd = { pending += it } }
        view.revealSwipeForTest("第一条")
        layout(view)
        val plus = allViews(view).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_add_phrase)
        }
        draw(plus)
        val hit = Rect()
        plus.getHitRect(hit)
        assertEquals(dp(48), plus.width)
        assertEquals(dp(48), plus.height)
        assertEquals(Rect(plus.left, plus.top, plus.right, plus.bottom), hit)
        assertTrue(view.isImmediateActionForTest(plus))
        assertTrue(plus.background === view.immediateActionDrawableForTest(plus))
        assertNull(plus.foreground)
        assertNull(plus.tag)
        assertTrue(plus.performClick())
        assertEquals(listOf(listOf("第一条")), pending)
    }

    @Test fun phrase_swipe_move_reuses_the_expanded_boxed_move_character() {
        val v = phraseView(listOf("你好"))
        v.revealSwipeForTest("你好")
        layout(v)
        val swipeMove = allViews(v).single {
            it !is TextView && it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_move)
        }
        val asset = requireNotNull(swipeMove.tag)
        assertEquals("移", asset)
        assertBoxedSymbol(swipeMove)
        v.hideSwipeForTest()
        v.expandForTest("你好")
        layout(v)
        val expandedMove = actionButtons(v).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_move) }
        assertTrue(asset === expandedMove.tag)
        assertTrue(expandedMove.contentDescription?.toString()?.startsWith("移 ") == true)
        assertBoxedSymbol(requireNotNull(expandedMove.compoundDrawables[0]))
    }

    @Test fun tabs_and_categories_dispatch_only_inside_their_own_capsules() {
        val clipboard = ctx.getString(com.aegis.ime.R.string.clip_clipboard)
        val phrases = ctx.getString(com.aegis.ime.R.string.clip_phrases)
        val v = phraseView(listOf("你好"))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            layout(v, w = 600)
            var tabs = textViews(v).filter { it.text?.toString() == clipboard || it.text?.toString() == phrases }
            val tray = tabs.first().parent as View
            assertEquals(tray.width / 2, tabs[0].width)
            assertEquals(tray.width / 2, tabs[1].width)
            assertEquals(tabs[0].right, tabs[1].left)
            tabs.forEach { tab ->
                draw(tab)
                val hit = Rect()
                tab.getHitRect(hit)
                assertEquals(Rect(tab.left, tab.top, tab.right, tab.bottom), hit)
                assertEquals(Rect(0, 0, tab.width, tab.height), tab.foreground.bounds)
            }
            val leftMaskRadii = requireNotNull(rippleMask(tabs[0]).cornerRadii)
            val rightMaskRadii = requireNotNull(rippleMask(tabs[1]).cornerRadii)
            assertTrue(leftMaskRadii[0] > 0f && leftMaskRadii[2] == 0f && leftMaskRadii[4] == 0f && leftMaskRadii[6] > 0f)
            assertTrue(rightMaskRadii[0] == 0f && rightMaskRadii[2] > 0f && rightMaskRadii[4] > 0f && rightMaskRadii[6] == 0f)
            rootTap(v, tabs.first { it.text?.toString() == clipboard })
            assertTrue(v.isClipboardTabForTest())
            layout(v, w = 600)
            tabs = textViews(v).filter { it.text?.toString() == clipboard || it.text?.toString() == phrases }
            rootTap(v, tabs.first { it.text?.toString() == phrases })
            assertFalse(v.isClipboardTabForTest())
            layout(v, w = 600)
            val defaults = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
            val work = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
            val defaultBounds = boundsInRoot(v, defaults)
            val workBounds = boundsInRoot(v, work)
            draw(defaults)
            draw(work)
            assertTrue(rippleMask(defaults).cornerRadius >= defaults.height / 2f)
            assertTrue(rippleMask(work).cornerRadius >= work.height / 2f)
            assertEquals(Rect(0, 0, defaults.width, defaults.height), defaults.foreground.bounds)
            assertEquals(Rect(0, 0, work.width, work.height), work.foreground.bounds)
            assertTrue(defaultBounds.right < workBounds.left)
            rootTap(v, work)
            assertEquals("工作", v.phraseCatForTest())
            layout(v, w = 600)
            rootTap(v, (defaultBounds.right + workBounds.left) / 2f, defaultBounds.exactCenterY())
            assertEquals("工作", v.phraseCatForTest())
            val refreshedDefault = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
            rootTap(v, refreshedDefault)
            assertEquals("默认", v.phraseCatForTest())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-mdpi")
    fun batch_management_entry_header_and_count_are_exact_single_line_text_at_supported_widths() {
        val cases = listOf(
            Triple(clipView(listOf("第一条")), "批量管理剪贴板", "第一条"),
            Triple(phraseView(listOf("你好")), "批量管理常用语", "你好"),
        )
        for ((view, title, item) in cases) {
            layout(view, 320)
            val entry = allViews(view).single { it.contentDescription?.toString() == title }
            assertEquals(title, entry.contentDescription?.toString())
            assertTrue(view.isImmediateActionForTest(entry))
            assertTrue(entry.background === view.immediateActionDrawableForTest(entry))
            assertNull(entry.foreground)
            view.enterSelectForTest()
            for (width in listOf(320, 360, 480)) {
                layout(view, width)
                val titleView = textViews(view).single { it.text?.toString() == title }
                val countView = textViews(view).single { it.text?.toString() == "已选择 0 项" }
                val header = titleView.parent as ViewGroup
                assertTrue(countView.parent === header)
                assertEquals(LinearLayout.HORIZONTAL, (header as LinearLayout).orientation)
                assertEquals(1, titleView.lineCount)
                assertEquals(1, countView.lineCount)
                assertTrue(titleView.left >= 0 && titleView.right <= header.width)
                assertTrue(countView.left >= titleView.right && countView.right <= header.width)
                assertTrue(titleView.paint.measureText(title) <= titleView.width - titleView.paddingLeft - titleView.paddingRight)
                assertTrue(countView.paint.measureText("已选择 0 项") <= countView.width - countView.paddingLeft - countView.paddingRight)
            }
            view.toggleSelectForTest(item)
            layout(view, 320)
            val updated = textViews(view).single { it.text?.toString() == "已选择 1 项" }
            assertEquals(1, updated.lineCount)
            assertTrue(updated.right <= (updated.parent as View).width)
        }
    }

    @Test fun the_clipboard_edit_action_hands_back_the_row_key_alone() {
        val long = "很长的一条内容".repeat(40)
        val v = clipView(listOf(long))
        val seen = ArrayList<String>()
        v.onEditClip = { key -> seen.add(key) }
        layout(v)
        rootSwipe(v, bodyOf(v, long), -300f)
        layout(v)
        val actions = swipeActions(v, long)
        val edit = actions.single { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_edit) }
        edit.performClick()
        assertEquals("the row key alone identifies what to edit", listOf(long), seen)
    }

    @Test fun the_dropdown_edit_action_hands_back_the_row_key_alone() {
        val v = clipView(listOf("第一条"))
        val seen = ArrayList<String>()
        v.onEditClip = { key -> seen.add(key) }
        layout(v)
        v.expandForTest("第一条")
        layout(v)
        val edit = actionButtons(v).single { it.text.toString() == ctx.getString(com.aegis.ime.R.string.clip_edit) }
        edit.performClick()
        assertEquals(listOf("第一条"), seen)
    }

    @Test fun rtl_swipe_strips_keep_physical_action_order_and_right_edge_anchor() {
        val cases = listOf(
            Triple(
                clipView(listOf("第一条")),
                "第一条",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_add_phrase),
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_split_word),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            ),
            Triple(
                phraseView(listOf("你好")),
                "你好",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_note),
                    ctx.getString(com.aegis.ime.R.string.clip_move),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            ),
        )
        for ((view, text, expected) in cases) {
            view.layoutDirection = View.LAYOUT_DIRECTION_RTL
            view.revealSwipeForTest(text)
            layout(view)
            val actions = assertSwipeActionStrip(view, text, expected)
            val strip = actions.first().parent as View
            val frame = strip.parent as View
            assertEquals(expected, actions.sortedBy { it.left }.map { it.contentDescription?.toString() })
            assertEquals(frame.width, strip.right)
        }
    }

    @Test fun rtl_dropdown_action_rows_keep_physical_order_and_left_alignment() {
        val cases = listOf(
            Triple(
                clipView(listOf("第一条")),
                "第一条",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_phrases),
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_split_word),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            ),
            Triple(
                phraseView(listOf("你好")),
                "你好",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_note),
                    ctx.getString(com.aegis.ime.R.string.clip_move),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            ),
        )
        for ((view, text, expected) in cases) {
            view.layoutDirection = View.LAYOUT_DIRECTION_RTL
            view.expandForTest(text)
            layout(view)
            val actions = actionButtons(view)
            val physical = actions.sortedBy { it.left }
            val row = actions.first().parent as ViewGroup
            assertEquals(expected, physical.map { it.text.toString() })
            assertEquals(row.paddingLeft, physical.first().left)
            assertTrue(physical.zipWithNext().all { (left, right) -> left.right <= right.left })
            assertTrue(actions.all {
                Gravity.getAbsoluteGravity(it.gravity, it.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT
            })
        }
    }


    @Test fun copy_all_records_each_split_block_separately() {
        val text = "visit https://x.com and copy each block"
        val blocks = ClipSplitter.copyBlocks(text)
        assertTrue("precondition: the text splits into ≥2 blocks", blocks.size >= 2)
        val batches = ArrayList<List<String>>()
        val v = ClipboardView(ctx).apply {
            historyProvider = { clipEntries(text) }
            onCopyBlocksToAegis = { batches.add(it) }
            applyPalette(pal)
            refresh()
        }
        v.showSplitForTest(text)
        assertTrue(clickText(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_copy_all)))
        assertEquals("Copy All invokes the batch callback exactly once", listOf(blocks), batches)
    }


    @Test fun manage_menu_renames_move_to_move_category() {
        val v = phraseView(listOf("你好"))
        v.showPhraseManageMenuForTest()
        val ls = labels(overlayOf(v))
        assertTrue("「移动分类」present", ctx.getString(com.aegis.ime.R.string.clip_move_category) in ls)
        assertFalse("the bare 「移动」 label is gone", ls.any { it == ctx.getString(com.aegis.ime.R.string.clip_move) })
    }


    @Test fun clear_history_top_icon_requires_confirmation() {
        var clears = 0
        val v = clipView(listOf("第一条")).apply { onClearHistory = { clears++; true } }
        layout(v)
        assertTrue("tap the clear-history icon", clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_clear_history)))
        assertEquals("top icon does not clear immediately", 0, clears)
        assertTrue(clickText(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_clear)))
        assertEquals("confirming clears history", 1, clears)
        assertFalse("old settings gear is gone", allViews(v).any { it.contentDescription?.toString() == "设置" })
    }

    @Test fun clipboard_top_slot_exposes_pause_and_resume_without_overloading_the_trash_button() {
        var enabled = true
        val changes = ArrayList<Boolean>()
        val v = clipView(listOf("第一条")).apply {
            historyEnabledProvider = { enabled }
            onSetHistoryEnabled = { next -> enabled = next; changes += next }
        }
        layout(v)

        val pause = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_pause_history)
        }
        assertImmediateKey(v, pause, "pause history")
        assertTrue(pause.performClick())
        assertEquals(listOf(false), changes)

        layout(v)
        val resume = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_resume_history)
        }
        assertImmediateKey(v, resume, "resume history")
        val trash = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_clear_history)
        }
        assertFalse("trash no longer hides the history toggle behind long press", trash.isLongClickable)
        assertTrue(resume.performClick())
        assertEquals(listOf(false, true), changes)
    }

    @Test fun confirmation_actions_are_compact_on_one_row_with_the_destructive_action_first() {
        val v = phraseView(listOf("你好"))
        v.confirmClearForTest()
        layout(v)
        val overlay = overlayOf(v)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_clear_category_confirm, "默认") in labels(overlay))
        val clear = textViews(overlay).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_clear) }
        val cancel = textViews(overlay).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_cancel) }
        val row = clear.parent as ViewGroup
        assertTrue(row === cancel.parent)
        assertEquals(3, row.childCount)
        assertTrue(clear.left < cancel.left)
        assertEquals(dp(14), row.getChildAt(1).width)
        assertTrue(v.isImmediateActionForTest(clear))
        assertTrue(v.isImmediateActionForTest(cancel))
        assertEquals(0f, (clear.layoutParams as LinearLayout.LayoutParams).weight, 0f)
        assertEquals(0f, (cancel.layoutParams as LinearLayout.LayoutParams).weight, 0f)
    }

    @Test fun overlay_backdrop_owns_the_full_touch_stream_and_resets_on_cancel_or_reopen() {
        val v = phraseView(listOf("你好"))
        v.showPhraseManageMenuForTest()
        layout(v)
        val backdrop = overlayOf(v)
        val card = (backdrop as ViewGroup).getChildAt(0)

        assertTrue(send(backdrop, MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        assertTrue(send(backdrop, MotionEvent.ACTION_MOVE, card.left + card.width / 2f, card.top + card.height / 2f, 16))
        assertTrue(send(backdrop, MotionEvent.ACTION_UP, card.left + card.width / 2f, card.top + card.height / 2f, 32))
        assertFalse(v.overlayVisibleForTest())

        v.showPhraseManageMenuForTest()
        layout(v)
        assertTrue(send(backdrop, MotionEvent.ACTION_DOWN, 1f, 1f, 40))
        assertTrue(send(backdrop, MotionEvent.ACTION_CANCEL, 1f, 1f, 56))
        assertTrue("cancel resets tracking without dismissing", v.overlayVisibleForTest())

        assertTrue(sendPointers(backdrop, MotionEvent.ACTION_DOWN, 58, intArrayOf(4), floatArrayOf(1f), floatArrayOf(1f)))
        assertTrue(sendPointers(
            backdrop,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            60,
            intArrayOf(4, 7),
            floatArrayOf(1f, 2f),
            floatArrayOf(1f, 2f),
        ))
        assertTrue(sendPointers(
            backdrop,
            MotionEvent.ACTION_POINTER_UP,
            62,
            intArrayOf(4, 7),
            floatArrayOf(1f, 2f),
            floatArrayOf(1f, 2f),
        ))
        assertTrue("lifting the original pointer transfers backdrop ownership", v.overlayVisibleForTest())
        assertTrue(sendPointers(backdrop, MotionEvent.ACTION_UP, 63, intArrayOf(7), floatArrayOf(2f), floatArrayOf(2f)))
        assertFalse("the replacement pointer closes on its final UP", v.overlayVisibleForTest())

        v.showPhraseManageMenuForTest()
        layout(v)

        assertTrue(send(backdrop, MotionEvent.ACTION_DOWN, 1f, 1f, 64))
        v.showPhraseManageMenuForTest()
        layout(v)
        send(backdrop, MotionEvent.ACTION_UP, 1f, 1f, 80)
        assertTrue("an old terminal event cannot close a freshly reopened popup", v.overlayVisibleForTest())

        val reopenedCard = (backdrop as ViewGroup).getChildAt(0)
        val insideX = reopenedCard.left + 1f
        val insideY = reopenedCard.top + 1f
        send(backdrop, MotionEvent.ACTION_DOWN, insideX, insideY, 96)
        send(backdrop, MotionEvent.ACTION_UP, insideX, insideY, 112)
        assertTrue("touches inside the popup never dismiss through the backdrop", v.overlayVisibleForTest())
    }

    @Test fun confirmed_bulk_clear_resets_item_actions_before_same_text_returns() {
        val expand = ctx.getString(com.aegis.ime.R.string.clip_expand)
        val collapse = ctx.getString(com.aegis.ime.R.string.clip_collapse)
        val clear = ctx.getString(com.aegis.ime.R.string.clip_clear)

        fun arm(v: ClipboardView, expanded: Boolean) {
            if (expanded) {
                v.expandForTest("x")
                assertTrue(collapse in allViews(v).mapNotNull { it.contentDescription?.toString() })
            } else {
                v.revealSwipeForTest("x")
                assertEquals("x", v.swipeRevealedForTest())
            }
        }

        fun assertNeutral(v: ClipboardView) {
            val descriptions = allViews(v).mapNotNull { it.contentDescription?.toString() }
            assertNull(v.swipeRevealedForTest())
            assertTrue(expand in descriptions)
            assertFalse(collapse in descriptions)
            assertTrue(actionButtons(v).isEmpty())
        }

        for (expanded in listOf(false, true)) {
            val history = mutableListOf("x")
            val clip = clipView(history).apply { onClearHistory = { history.clear(); true } }
            arm(clip, expanded)
            clip.confirmClearHistoryForTest()
            assertTrue(clickText(overlayOf(clip), clear))
            assertTrue(history.isEmpty())
            history.add("x")
            clip.refresh()
            assertTrue("x" in labels(mainOf(clip)))
            assertNeutral(clip)

            val phrases = mutableListOf("x")
            val phrase = phraseView(phrases).apply { onClearCategory = { phrases.clear() } }
            arm(phrase, expanded)
            phrase.confirmClearForTest()
            assertTrue(clickText(overlayOf(phrase), clear))
            assertTrue(phrases.isEmpty())
            phrases.add("x")
            phrase.refresh()
            assertTrue("x" in labels(mainOf(phrase)))
            assertNeutral(phrase)
        }
    }

    @Test fun a_full_rebuild_re_applies_scroll_once_the_deferred_rows_land() {
        val v = clipView((1..40).map { "item$it" })
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            while (v.runPendingListAppendForTest()) {}
            layout(v, h = 220)
            val viewport = v.listViewportForTest() as ViewGroup
            val maxScroll = (viewport.getChildAt(0).height - viewport.height).coerceAtLeast(0)
            assertTrue("precondition: the long list overflows the viewport", maxScroll > 0)
            val target = maxScroll / 2
            viewport.scrollTo(0, target)
            assertEquals(target, v.listScrollYForTest())
            v.applyPalette(pal)
            layout(v, h = 220)
            assertTrue("the intermediate short list cannot reach the old offset", v.listScrollYForTest() < target)
            while (v.runPendingListAppendForTest()) {}
            layout(v, h = 220)
            assertEquals("scroll is re-applied after the deferred rows append", target, v.listScrollYForTest())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun a_rapid_scroll_reversal_overrides_deferred_restoration_on_both_tabs() {
        val cases = listOf(
            "clipboard" to clipView((1..40).map { "clip$it" }),
            "phrases" to phraseView((1..40).map { "phrase$it" }),
        )
        for ((name, v) in cases) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                activity.get().setContentView(v)
                layout(v, h = 220)
                val viewport = v.listViewportForTest() as ViewGroup
                val maxScroll = (viewport.getChildAt(0).height - viewport.height).coerceAtLeast(0)
                assertTrue("$name precondition: initial rows overflow", maxScroll > 0)
                val target = maxScroll / 2
                send(viewport, MotionEvent.ACTION_DOWN, viewport.width / 2f, viewport.height / 2f, 0)
                viewport.scrollTo(0, target)
                send(viewport, MotionEvent.ACTION_CANCEL, viewport.width / 2f, viewport.height / 2f, 16)
                val reversedTarget = target / 2
                send(viewport, MotionEvent.ACTION_DOWN, viewport.width / 2f, viewport.height / 2f, 32)
                viewport.scrollTo(0, reversedTarget)
                send(viewport, MotionEvent.ACTION_CANCEL, viewport.width / 2f, viewport.height / 2f, 48)
                while (v.runPendingListAppendForTest()) {}
                layout(v, h = 220)
                assertEquals("$name keeps the reversed user offset", reversedTarget, v.listScrollYForTest())
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test fun an_entries_refresh_does_not_replace_a_pressed_back_button_on_either_tab() {
        val clips = (1..40).map { "clip$it" }.toMutableList()
        val phrases = (1..40).map { "phrase$it" }.toMutableList()
        val cases = listOf(
            "clipboard" to (clipView(clips) to { clips.add(0, "new clip") }),
            "phrases" to (phraseView(phrases) to { phrases.add(0, "new phrase") }),
        )
        for ((name, pair) in cases) {
            val (v, mutate) = pair
            var backs = 0
            v.onBack = { backs++ }
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                activity.get().setContentView(v)
                layout(v, h = 220)
                val desc = ctx.getString(com.aegis.ime.R.string.clip_back)
                val before = allViews(v).single { it.contentDescription?.toString() == desc }
                val bounds = boundsInRoot(v, before)
                send(v, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                mutate()
                v.refresh()
                layout(v, h = 220)
                val after = allViews(v).single { it.contentDescription?.toString() == desc }
                assertTrue("$name keeps the pressed back target attached", before === after)
                send(v, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 16)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("$name delivers the back click", 1, backs)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test fun a_split_copy_style_refresh_preserves_scroll_and_reuses_existing_rows() {
        val history = (1..40).map { "item$it" }.toMutableList()
        val v = clipView(history)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            while (v.runPendingListAppendForTest()) {}
            layout(v, h = 220)
            val viewport = v.listViewportForTest() as ViewGroup
            val maxScroll = (viewport.getChildAt(0).height - viewport.height).coerceAtLeast(0)
            assertTrue("precondition: the long list overflows the viewport", maxScroll > 0)
            val target = maxScroll / 2
            viewport.scrollTo(0, target)
            assertEquals(target, v.listScrollYForTest())
            val keptRow = requireNotNull(v.listRowViewForTest(5))
            history.add(0, "copied-block")
            v.refresh()
            layout(v, h = 220)
            assertEquals("the split→copy refresh keeps the scroll offset, no jump to top", target, v.listScrollYForTest())
            assertTrue("existing rows are reused in place, not rebuilt from scratch", keptRow === v.listRowViewForTest(6))
            assertTrue("the copied block is prepended", "copied-block" in v.listRowTextsForTest())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun ticking_a_select_row_updates_the_count_and_enables_actions_in_place() {
        val v = clipView(listOf("a", "b", "c")).apply { enterSelectForTest() }
        layout(v)
        val row0 = requireNotNull(v.listRowViewForTest(0))
        val delete = textViews(v).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete) }
        assertFalse("batch delete is disabled with an empty selection", delete.isClickable)
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 0, 0) in labels(v))
        row0.performClick()
        assertTrue(
            "the tick updates the header count in place",
            ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 1, 1) in labels(v),
        )
        assertTrue("the tick enables the batch actions in place", delete.isClickable)
        assertTrue("the tapped row is mutated, not rebuilt", row0 === v.listRowViewForTest(0))
    }

    @Test fun empty_batch_actions_have_no_press_haptic_or_click_until_a_row_is_selected() {
        val primaryPayloads = ArrayList<List<String>>()
        val v = clipView(listOf("a", "b")).apply {
            onAddCategoryThenAdd = { primaryPayloads += it }
            enterSelectForTest()
            hapticEnabled = true
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            layout(v)
            val primary = textViews(v).single {
                it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_add_phrase)
            }
            val delete = textViews(v).single {
                it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete)
            }

            for (action in listOf(primary, delete)) {
                assertFalse(action.isEnabled)
                assertFalse(action.isClickable)
                send(action, MotionEvent.ACTION_DOWN, action.width / 2f, action.height / 2f, 0)
                send(action, MotionEvent.ACTION_UP, action.width / 2f, action.height / 2f, 16)
                flushMotion()
                assertEquals(0f, requireNotNull(v.immediateActionFeedbackLevelForTest(action)), 0f)
                assertEquals(-1, shadowOf(action).lastHapticFeedbackPerformed())
            }
            assertTrue(primaryPayloads.isEmpty())
            assertEquals(View.GONE, overlayOf(v).visibility)

            requireNotNull(v.listRowViewForTest(0)).performClick()
            for (action in listOf(primary, delete)) {
                assertTrue(action.isEnabled)
                assertTrue(action.isClickable)
                assertTrue(action.hasOnClickListeners())
                send(action, MotionEvent.ACTION_DOWN, action.width / 2f, action.height / 2f, 32)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
                assertEquals(1f, requireNotNull(v.immediateActionFeedbackLevelForTest(action)), 0f)
                assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(action).lastHapticFeedbackPerformed())
                send(action, MotionEvent.ACTION_CANCEL, action.width / 2f, action.height / 2f, 48)
                flushMotion()
            }
            assertTrue(primaryPayloads.isEmpty())

            rootTap(v, primary)
            assertEquals(listOf(listOf("a")), primaryPayloads)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun select_all_rebinds_every_row_and_the_count_without_a_rebuild() {
        val v = clipView(listOf("a", "b", "c")).apply { enterSelectForTest() }
        layout(v)
        val row0 = requireNotNull(v.listRowViewForTest(0))
        val row2 = requireNotNull(v.listRowViewForTest(2))
        requireNotNull(v.selectAllActionForTest()).performClick()
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 3, 3) in labels(v))
        assertTrue("select-all rebinds rows in place", row0 === v.listRowViewForTest(0) && row2 === v.listRowViewForTest(2))
        assertTrue(v.isSelectModeForTest())
    }

    @Test fun expanding_a_card_leaves_its_sibling_rows_untouched() {
        val v = clipView(listOf("a", "b", "c"))
        layout(v)
        val row1 = v.listRowViewForTest(1)
        val row2 = v.listRowViewForTest(2)
        val chevron = allViews(requireNotNull(v.listRowViewForTest(0))).first {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_expand) && it.hasOnClickListeners()
        }
        chevron.performClick()
        layout(v)
        assertTrue("sibling rows keep their identity through a targeted expand", row1 === v.listRowViewForTest(1))
        assertTrue(row2 === v.listRowViewForTest(2))
        assertTrue("the expanded card renders its action row", actionButtons(v).isNotEmpty())
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_collapse) in allViews(v).mapNotNull { it.contentDescription?.toString() })
    }

    @Test fun card_replacement_and_entry_reconcile_release_removed_action_feedback() {
        val history = mutableListOf("a", "b", "c")
        val v = clipView(history)
        layout(v)
        val collapsedCount = v.immediateActionFeedbackCountForTest()
        var expandedCount = -1

        repeat(5) {
            val collapsedRow = requireNotNull(v.listRowViewForTest(0))
            val expand = allViews(collapsedRow).single {
                it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_expand)
            }
            expand.performClick()
            layout(v)
            val expandedRow = requireNotNull(v.listRowViewForTest(0))
            val oldActions = allViews(expandedRow).filter(v::isImmediateActionForTest)
            assertTrue(oldActions.isNotEmpty())
            val nowExpanded = v.immediateActionFeedbackCountForTest()
            if (expandedCount < 0) expandedCount = nowExpanded else assertEquals(expandedCount, nowExpanded)

            val collapse = allViews(expandedRow).single {
                it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_collapse)
            }
            collapse.performClick()
            layout(v)
            assertTrue(oldActions.none(v::isImmediateActionForTest))
            assertEquals(collapsedCount, v.immediateActionFeedbackCountForTest())
        }

        val expand = allViews(requireNotNull(v.listRowViewForTest(0))).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_expand)
        }
        expand.performClick()
        layout(v)
        val removedActions = allViews(requireNotNull(v.listRowViewForTest(0))).filter(v::isImmediateActionForTest)
        history[0] = "replacement"
        v.refresh()
        layout(v)
        assertTrue(removedActions.none(v::isImmediateActionForTest))
        assertEquals(collapsedCount, v.immediateActionFeedbackCountForTest())

        repeat(5) { index ->
            history[0] = "replacement-$index"
            v.refresh()
            layout(v)
            assertEquals(collapsedCount, v.immediateActionFeedbackCountForTest())
        }
    }

    @Test fun reopen_after_inline_from_the_clipboard_tab_stays_on_the_clipboard_tab() {
        val v = clipView(listOf("clip")).apply {
            categoriesProvider = { listOf("默认") }
            phrasesInProvider = { emptyList() }
        }
        layout(v)
        assertTrue(v.isClipboardTabForTest())
        v.reopenAfterInline("新建分类")
        layout(v)
        assertTrue("a clipboard-launched inline return does not jump to phrases", v.isClipboardTabForTest())
        assertTrue("the clipboard content is intact", "clip" in labels(mainOf(v)))
    }

    @Test fun reopen_after_inline_on_the_phrase_tab_retargets_the_category() {
        val v = phraseView(listOf("你好"))
        layout(v)
        assertFalse(v.isClipboardTabForTest())
        assertEquals("默认", v.phraseCatForTest())
        v.reopenAfterInline("工作")
        layout(v)
        assertFalse("an inline return keeps the phrase tab", v.isClipboardTabForTest())
        assertEquals("an inline return retargets the phrase category", "工作", v.phraseCatForTest())
    }


    private val bigBody = "第一段。第二段，第三段！".repeat(8000)

    private fun storeDir(): File = Files.createTempDirectory("clipview").toFile()

    private fun lazyStore(dir: File, vararg bodies: String): ClipboardStore {
        ClipboardStore(dir).apply {
            load()
            bodies.forEach { record(it) }
            flushPendingWrites()
        }
        return ClipboardStore(dir).apply { load() }
    }

    private fun storeView(store: ClipboardStore): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { store.history() }
        categoriesProvider = { listOf("默认") }
        applyPalette(pal)
        refresh()
    }

    private fun bigRow(v: ClipboardView): TextView =
        textViews(mainOf(v)).first { it.text?.toString()?.startsWith("第一段。") == true }

    @Test fun a_big_clipboard_row_renders_from_a_bounded_preview() {
        val dir = storeDir()
        val store = lazyStore(dir, bigBody)
        val v = storeView(store)
        layout(v)
        val row = bigRow(v)
        assertEquals("the row shows the panel's capped preview", v.displayCapForTest() + 1, row.text.length)
        assertTrue("listing a big clip keeps its body out of memory", store.residentBodyChars() <= ClipEntry.PREVIEW_CHARS)
        assertTrue(
            "the panel cap must fit inside the preview the store hands out",
            v.displayCapForTest() < ClipEntry.PREVIEW_CHARS,
        )
        dir.deleteRecursively()
    }

    @Test fun picking_a_big_clipboard_row_commits_the_whole_body() {
        val dir = storeDir()
        val store = lazyStore(dir, bigBody)
        var picked: String? = null
        val v = storeView(store).apply { onPick = { picked = it } }
        layout(v)
        assertTrue(bigRow(v).performClick())
        assertEquals("上屏 gets the whole original string", bigBody, picked)
        dir.deleteRecursively()
    }

    @Test fun splitting_a_big_clipboard_row_sees_the_whole_body() {
        val dir = storeDir()
        val store = lazyStore(dir, bigBody)
        var copied: List<String>? = null
        val v = storeView(store).apply { onCopyBlocksToAegis = { copied = it } }
        layout(v)
        v.showSplitForTest(store.history().first().key)
        assertTrue(clickText(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_copy_all)))
        assertEquals("拆分 works on the whole original string", ClipSplitter.copyBlocks(bigBody), copied)
        dir.deleteRecursively()
    }

    @Test fun saving_a_big_clipboard_row_as_a_phrase_carries_the_whole_body() {
        val dir = storeDir()
        val store = lazyStore(dir, bigBody)
        var saved: Pair<String, List<String>>? = null
        val v = storeView(store).apply { onSaveAsPhrasesTo = { c, l -> saved = c to l } }
        layout(v)
        v.expandForTest(store.history().first().key)
        layout(v)
        val toPhrases = actionButtons(mainOf(v)).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_phrases) }
        assertTrue(toPhrases.performClick())
        assertTrue(clickText(overlayOf(v), "默认"))
        assertEquals("存为短语 gets the whole original string", "默认" to listOf(bigBody), saved)
        dir.deleteRecursively()
    }

    @Test fun a_row_whose_sidecar_vanished_is_marked_and_commits_nothing() {
        val dir = storeDir()
        val hash = "d".repeat(64)
        File(dir, "clipboard.txt").writeText("B\t$hash\n")
        val store = ClipboardStore(dir).apply { load() }
        var picked: String? = null
        val v = storeView(store).apply { onPick = { picked = it } }
        layout(v)
        val row = textViews(mainOf(v)).first { it.text?.toString()?.startsWith("⚠") == true }
        assertTrue("the missing row is marked, never shown as clip text", row.text.toString().startsWith("⚠ "))
        assertTrue("no row impersonates the reference line", labels(mainOf(v)).none { it.startsWith("B\t") })
        assertTrue(row.performClick())
        assertNull("a missing body must never be committed as a substitute", picked)
        dir.deleteRecursively()
    }
}
