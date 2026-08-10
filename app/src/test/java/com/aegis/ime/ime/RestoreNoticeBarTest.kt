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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.R
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.RestoreTrouble
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreNoticeBarTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    @Before fun noTroubleYet() { LiveUserData.restoreTrouble = null }

    @After fun leaveNoTrouble() { LiveUserData.restoreTrouble = null }

    private fun attached(): Pair<KeyboardController, InputView> {
        val view = InputView(ctx)
        val controller = KeyboardController(Host(), DictEngine(null, null, null)).apply { attachView(view) }
        return controller to view
    }

    private fun laidOut(): CandidateView {
        val v = CandidateView(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun draw(v: CandidateView) {
        v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
    }

    private fun tap(v: CandidateView, x: Float, y: Float) {
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test fun the_bar_carries_the_notice_while_nothing_is_being_typed() {
        LiveUserData.restoreTrouble = RestoreTrouble.ROLLBACK_FAILED

        val (controller, view) = attached()

        assertEquals(RestoreTrouble.ROLLBACK_FAILED, controller.restoreNoticeForTest())
        assertEquals(
            ctx.getString(R.string.restore_gate_rollback_failed),
            view.candidateRestoreNoticeForTest(),
        )
    }

    @Test fun the_bar_says_which_of_the_two_troubles_it_was() {
        LiveUserData.restoreTrouble = RestoreTrouble.ROLLBACK_IMPOSSIBLE

        val (_, view) = attached()

        assertEquals(
            ctx.getString(R.string.restore_gate_rollback_impossible),
            view.candidateRestoreNoticeForTest(),
        )
        assertTrue(
            "a rollback that failed and one that was never possible must not read the same",
            view.restoreNoticeLabelForTest(RestoreTrouble.ROLLBACK_FAILED) !=
                view.restoreNoticeLabelForTest(RestoreTrouble.ROLLBACK_IMPOSSIBLE),
        )
    }

    @Test fun a_bar_with_nothing_to_report_carries_no_notice() {
        val (controller, view) = attached()

        assertNull(controller.restoreNoticeForTest())
        assertNull(view.candidateRestoreNoticeForTest())
    }

    @Test fun typing_takes_the_bar_back_from_the_notice() {
        LiveUserData.restoreTrouble = RestoreTrouble.ROLLBACK_FAILED
        val (controller, view) = attached()
        controller.onKey(Key("", action = KeyAction.SWITCH_ALPHA))

        "ni".forEach { controller.onKey(Key(it.toString(), output = it.toString())) }

        assertNull("the strip belongs to what is being typed", controller.restoreNoticeForTest())
        assertNull(view.candidateRestoreNoticeForTest())
    }

    @Test fun the_toolbar_gives_up_the_bar_while_the_notice_is_up() {
        val idle = laidOut()
        idle.setContent(emptyList(), "")
        draw(idle)
        assertTrue(
            "precondition: an idle bar draws the toolbar",
            idle.toolbarControlBoundsForTest().any { !it.isEmpty },
        )

        val noticed = laidOut()
        noticed.setContent(emptyList(), "")
        noticed.setRestoreNotice(ctx.getString(R.string.restore_gate_rollback_failed))
        draw(noticed)

        assertTrue("the notice is what an idle bar shows now", noticed.restoreNoticeShownForTest())
        assertTrue(
            "the six functions and the collapse chevron must give way to the notice",
            noticed.toolbarControlBoundsForTest().all { it.isEmpty },
        )
    }

    @Test fun a_tap_on_the_notice_opens_backup_and_never_reaches_the_toolbar_underneath() {
        val v = laidOut()
        var opened = 0
        val pressed = mutableListOf<BarFunction>()
        var collapsed = 0
        v.onRestoreNotice = { opened++ }
        v.onFunction = { pressed += it }
        v.onCollapse = { collapsed++ }
        v.setContent(emptyList(), "")
        draw(v)
        val icon = v.toolbarControlBoundsForTest().first()
        assertFalse("precondition: an icon was laid out where the tap will land", icon.isEmpty)

        v.setRestoreNotice(ctx.getString(R.string.restore_gate_rollback_impossible))
        draw(v)
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, icon.centerX(), icon.centerY(), 0))
        assertNull("an icon nobody can see must not light up under the finger", v.pressedTargetForTest())
        v.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, icon.centerX(), icon.centerY(), 0))

        assertEquals("a tap anywhere on the notice must open backup and restore", 1, opened)
        assertTrue("no icon is on screen, so none of them may be pressed", pressed.isEmpty())
        assertEquals("nor may the tap collapse the keyboard", 0, collapsed)
    }

    @Test fun the_dictionary_gate_keeps_the_bar_when_the_notice_is_set_too() {
        val v = laidOut()
        var opened = 0
        var download = 0
        v.onRestoreNotice = { opened++ }
        v.onDictGate = { download++ }
        v.setContent(emptyList(), "ni", gate = true)
        v.setGateStatus(ctx.getString(R.string.dict_gate_cta))
        v.setRestoreNotice(ctx.getString(R.string.restore_gate_rollback_failed))
        draw(v)

        assertFalse("the download prompt owns the bar while the user is typing", v.restoreNoticeShownForTest())

        tap(v, v.width / 2f, v.height / 2f)

        assertEquals(1, download)
        assertEquals(0, opened)
    }

    @Test fun the_notice_is_drawn_in_the_colour_kept_for_trouble() {
        val v = CandidateView(ctx)
        val palette = ImePalette.STATIC_LIGHT
        v.applyPalette(palette)
        v.setRestoreNotice(ctx.getString(R.string.restore_gate_rollback_failed))

        assertEquals(palette.deletable, v.restoreNoticeTextColorForTest())
        assertTrue(
            "a notice drawn in the ordinary accent reads as a candidate",
            v.restoreNoticeTextColorForTest() != palette.candidateFirst,
        )
    }
}
