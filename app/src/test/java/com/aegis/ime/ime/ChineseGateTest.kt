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

import android.view.MotionEvent
import android.view.View
import com.aegis.ime.R
import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.ui.DownloadCardSnapshot
import com.aegis.ime.ui.LocalizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChineseGateTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class Host : ImeHost {
        val commits = mutableListOf<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun controller(e: CandidateEngine): Pair<KeyboardController, InputView> {
        val view = InputView(ctx)
        val c = KeyboardController(Host(), e).apply { attachView(view) }
        return c to view
    }

    private fun type(c: KeyboardController, s: String) =
        s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }

    private fun chineseCapableEngine(): DictEngine =
        DictEngine(EngineFixture.build(listOf(EngineFixture.Row("ni", "你", 900))), null, null)

    @Test fun gate_locks_chinese_when_no_dict_and_pinyin_is_composing() {
        val (c, view) = controller(DictEngine(null, null, null))
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, "ni")
        assertTrue("no Chinese dict + pinyin typed must gate", c.chineseGateActiveForTest())
        assertTrue("the strip must receive the gate flag", view.candidateGateActiveForTest())
    }

    @Test fun gate_locks_chinese_on_the_nine_key_keyboard_too() {
        val (c, view) = controller(DictEngine(null, null, null))
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        type(c, "64")
        assertTrue("no Chinese dict + 9-key digits typed must gate", c.chineseGateActiveForTest())
        assertTrue("the strip must receive the gate flag", view.candidateGateActiveForTest())
    }

    @Test fun gate_is_inactive_before_the_user_types() {
        val (c, view) = controller(DictEngine(null, null, null))
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        assertFalse("empty composing must keep the functions toolbar", c.chineseGateActiveForTest())
        assertFalse(view.candidateGateActiveForTest())
    }

    @Test fun gate_is_inactive_in_english_layout() {
        val (c, view) = controller(DictEngine(null, null, null))
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        c.onKey(Key("", action = KeyAction.TOGGLE_LANG))
        type(c, "ni")
        assertFalse("English typing must never gate", c.chineseGateActiveForTest())
        assertFalse(view.candidateGateActiveForTest())
    }

    @Test fun gate_is_inactive_when_a_chinese_capable_engine_is_set() {
        val (c, view) = controller(chineseCapableEngine())
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, "ni")
        assertFalse("a Chinese-capable engine must never gate", c.chineseGateActiveForTest())
        assertFalse(view.candidateGateActiveForTest())
    }

    @Test fun hot_reloading_a_chinese_engine_clears_the_gate() {
        val host = Host()
        val view = InputView(ctx)
        val c = KeyboardController(host, DictEngine(null, null, null)).apply { attachView(view) }
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, "ni")
        assertTrue(c.chineseGateActiveForTest())
        c.setEngine(chineseCapableEngine())
        assertFalse("installing the pack (setEngine) clears the gate", c.chineseGateActiveForTest())
        assertFalse(view.candidateGateActiveForTest())
        assertEquals("the download-trigger input is removed", "", c.preeditForTest())
        assertTrue("download-trigger candidates are removed", c.candidateWords().isEmpty())
        assertTrue("clearing the gate must not commit raw pinyin", host.commits.isEmpty())
    }

    @Test fun nine_key_download_trigger_is_cleared_without_committing_digits_or_pinyin() {
        val host = Host()
        val view = InputView(ctx)
        val c = KeyboardController(host, DictEngine(null, null, null)).apply { attachView(view) }
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        type(c, "64")
        assertTrue(c.chineseGateActiveForTest())
        assertTrue("precondition: 9-key input is visible", c.preeditForTest().isNotEmpty())

        c.setEngine(chineseCapableEngine())

        assertEquals("9-key download-trigger input is removed", "", c.preeditForTest())
        assertTrue(c.candidateWords().isEmpty())
        assertFalse(view.candidateGateActiveForTest())
        assertTrue("clearing the 9-key gate must not commit", host.commits.isEmpty())
    }

    @Test fun ordinary_chinese_engine_hot_swap_preserves_active_composition() {
        val host = Host()
        val c = KeyboardController(host, chineseCapableEngine())
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, "ni")
        val preedit = c.preeditForTest()
        val candidates = c.candidateWords()

        c.setEngine(chineseCapableEngine())

        assertEquals("an ordinary Chinese-to-Chinese swap keeps the preedit", preedit, c.preeditForTest())
        assertEquals("an ordinary Chinese-to-Chinese swap re-decodes the same input", candidates, c.candidateWords())
        assertTrue(host.commits.isEmpty())
    }

    @Test fun completing_a_download_invalidates_the_inflight_decode_for_the_old_engine() {
        val workerQueue = ArrayDeque<Runnable>()
        val mainQueue = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQueue.addLast(it) }, Executor { mainQueue.addLast(it) })
        val host = Host()
        val c = KeyboardController(host, DictEngine(null, null, null), lane)
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, "ni")
        assertTrue(c.chineseGateActiveForTest())

        c.setEngine(chineseCapableEngine())
        while (workerQueue.isNotEmpty()) workerQueue.removeFirst().run()
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run()

        assertEquals("late decode output cannot restore the cleared preedit", "", c.preeditForTest())
        assertTrue("late decode output cannot restore stale candidates", c.candidateWords().isEmpty())
        assertTrue(host.commits.isEmpty())
    }

    @Test fun tapping_the_gated_strip_invokes_the_download_callback() {
        var tapped = false
        val v = CandidateView(ctx)
        v.onDictGate = { tapped = true }
        v.setContent(emptyList(), "ni", gate = true)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * ctx.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * ctx.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        assertTrue("the strip must be in gate mode", v.gateActiveForTest())
        val cx = v.width / 2f
        val cy = v.height / 2f
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, cx, cy, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, cx, cy, 0))
        assertTrue("a tap anywhere on the gated strip must start the download", tapped)
    }

    @Test fun gate_failed_state_draws_the_error_colour_while_the_others_keep_the_accent() {
        val v = CandidateView(ctx)
        val palette = ImePalette.STATIC_LIGHT
        assertTrue("the error colour must differ from the accent", palette.deletable != palette.candidateFirst)
        v.setContent(emptyList(), "ni", gate = true)

        v.setGateStatus(ctx.getString(R.string.dict_gate_cta), failed = false)
        assertEquals("idle gate keeps the accent", palette.candidateFirst, v.gateTextColorForTest())
        v.setGateStatus(ctx.getString(R.string.dict_gate_downloading) + " 40%", failed = false)
        assertEquals("downloading gate keeps the accent", palette.candidateFirst, v.gateTextColorForTest())
        v.setGateStatus(ctx.getString(R.string.dict_gate_verifying), failed = false)
        assertEquals("verifying gate keeps the accent", palette.candidateFirst, v.gateTextColorForTest())
        v.setGateStatus(ctx.getString(R.string.dict_gate_failed), failed = true)
        assertEquals("failed gate turns to the error colour", palette.deletable, v.gateTextColorForTest())
    }

    @Test fun only_a_failed_download_with_no_pack_flags_the_gate_as_failed() {
        val view = InputView(ctx)
        val notDownloaded = LocalizedText.Resource(R.string.dict_status_not_downloaded)
        val downloadFailed = LocalizedText.Resource(R.string.dict_status_download_failed)
        val installFailed = LocalizedText.Resource(R.string.dict_status_install_failed)
        val metadataFailed = LocalizedText.Resource(R.string.dict_status_metadata_failed)
        val blocked = LocalizedText.Resource(R.string.dict_status_download_blocked)
        val offline = LocalizedText.ResourceNested(
            R.string.download_status_failed_format,
            R.string.download_cause_offline,
        )
        val metadataOffline = LocalizedText.ResourceNested(
            R.string.dict_status_metadata_failed_format,
            R.string.download_cause_offline,
        )

        assertFalse("idle", view.gateShowsFailureForTest(snap(false, false, null, notDownloaded)))
        assertFalse("downloading", view.gateShowsFailureForTest(snap(false, true, 0.4f, notDownloaded)))
        assertFalse("verifying", view.gateShowsFailureForTest(snap(false, true, null, notDownloaded)))
        assertTrue("download failed", view.gateShowsFailureForTest(snap(false, false, null, downloadFailed)))
        assertTrue("install failed", view.gateShowsFailureForTest(snap(false, false, null, installFailed)))
        assertTrue("metadata failed", view.gateShowsFailureForTest(snap(false, false, null, metadataFailed)))
        assertTrue("download blocked", view.gateShowsFailureForTest(snap(false, false, null, blocked)))
        assertTrue("named transfer cause", view.gateShowsFailureForTest(snap(false, false, null, offline)))
        assertTrue("named metadata cause", view.gateShowsFailureForTest(snap(false, false, null, metadataOffline)))
        assertFalse("a failed status is moot once the pack is present", view.gateShowsFailureForTest(snap(true, false, null, downloadFailed)))
    }

    private fun snap(present: Boolean, downloading: Boolean, progress: Float?, status: LocalizedText) =
        DownloadCardSnapshot(present = present, downloading = downloading, progress = progress, status = status)
}
