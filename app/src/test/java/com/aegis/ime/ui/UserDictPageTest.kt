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
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.aegis.ime.R
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearnEdit
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import com.aegis.ime.user.UserStoreEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UserDictPageTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()
    private val db = File(ctx.filesDir, "userdb.txt")
    private val learn = File(ctx.filesDir, "userlearn.txt")
    private var scenario: ActivityScenario<UserDictActivity>? = null
    private val hosts = ArrayList<LiveUserDictHost>()
    private fun s(id: Int) = ctx.getString(id)
    private fun row(word: String, reading: String) = ctx.getString(R.string.user_dict_entry_format, word, reading)

    private fun liveHost(
        model: UserModel,
        userDb: File,
        userLearning: UserLearning? = null,
        userLearnFile: File? = null,
        onSaved: (Long?, Long?) -> Unit = { _, _ -> },
    ): LiveUserDictHost =
        LiveUserDictHost(model, userDb, userLearning, userLearnFile, onSaved).also { hosts += it }

    @Before fun reset() {
        UserDictHot.host = null
        AegisToast.reset()
        db.delete()
        learn.delete()
    }

    @After fun cleanup() {
        drainEdits()
        scenario?.close()
        UserDictHot.host = null
        hosts.forEach { runCatching { it.stopSaving() } }
        db.delete()
        learn.delete()
    }

    private fun drainEdits() {
        val lane = UserStoreEdits::class.java.getDeclaredField("lane").run {
            isAccessible = true
            get(UserStoreEdits) as ExecutorService
        }
        lane.submit { }.get(10, TimeUnit.SECONDS)
    }

    private fun settleEdits() {
        drainEdits()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun settled(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) {
                compose.waitForIdle()
                return
            }
            Thread.yield()
        }
        fail("timed out waiting until $what")
    }

    private fun reported(): String? {
        settled("the page reported what became of the edit") { AegisToast.textForTest() != null }
        return AegisToast.textForTest()
    }

    private fun seedLearned(vararg steps: Pair<String, String>) {
        val now = 1_700_000_000_000L
        val learning = UserLearning { now }
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in steps) {
                learning.observeCommit(prev, word, reading, now)
                prev = word
            }
            learning.observeBreak()
        }
        learning.save(learn)
    }

    private fun openUserDictPage() {
        scenario = ActivityScenario.launch(UserDictActivity::class.java)
        compose.onNodeWithTag("user_dict_search").assertExists()
    }

    private fun openAddSheet() {
        compose.onNodeWithTag("user_dict_open_add").performClick()
        compose.onNodeWithTag("user_dict_add_sheet").assertExists()
    }

    private fun openMoreSheet() {
        compose.onNodeWithTag("user_dict_open_more").performClick()
        compose.onNodeWithTag("user_dict_more_sheet").assertExists()
    }

    private fun startExportFromTools() {
        openMoreSheet()
        compose.onNodeWithTag("user_dict_export").performScrollTo().performClick()
    }

    private fun seed(n: Int, vararg extras: Pair<String, String>) {
        db.writeText(
            buildString {
                append("aegis-userdb 1\n")
                for (i in 0 until n) {
                    val reading = buildString {
                        var v = i
                        repeat(4) { append('a' + v % 26); v /= 26 }
                    }
                    append("R\t$reading\t词$i\n")
                }
                for ((reading, word) in extras) append("R\t$reading\t$word\n")
            },
        )
    }

    @Test fun thousand_entry_list_is_lazily_composed_not_fully_inflated() {
        seed(1200)
        openUserDictPage()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 1200)).assertExists()
        compose.onNodeWithTag("user_dict_list").assertExists()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("词0", "aaaa")))
        val composedDeleteButtons = compose.onAllNodesWithText(s(R.string.user_dict_delete_button))
            .fetchSemanticsNodes().size
        assertTrue("expected at least one visible row, got $composedDeleteButtons", composedDeleteButtons > 0)
        assertTrue(
            "1200 entries must not all be composed (got $composedDeleteButtons rows)",
            composedDeleteButtons < 200,
        )
    }

    @Test fun search_by_pinyin_prefix_word_substring_and_the_empty_and_no_result_boundaries() {
        seed(50, "nihao" to "你好", "ceshi" to "测试")
        openUserDictPage()
        compose.onNodeWithTag("user_dict_open_more").assertExists()
        compose.onNodeWithText(s(R.string.user_dict_export_button)).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 52)).assertExists()

        compose.onNodeWithTag("user_dict_search").performTextInput("nih")
        compose.onNodeWithText(row("你好", "nihao")).assertExists()
        compose.onNodeWithText(row("测试", "ceshi")).assertDoesNotExist()
        compose.onNodeWithTag("user_dict_open_more").assertExists()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("测")
        compose.onNodeWithText(row("测试", "ceshi")).assertExists()
        compose.onNodeWithText(row("你好", "nihao")).assertDoesNotExist()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("zzzz9")
        compose.onNodeWithText(s(R.string.user_dict_search_no_match)).assertExists()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_open_more").assertExists()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你好", "nihao")))
        compose.onNodeWithText(row("你好", "nihao")).assertExists()
    }

    @Test fun add_writes_the_word_into_userdb_and_the_list() {
        seed(0)
        openUserDictPage()
        openAddSheet()
        compose.onNodeWithTag("user_dict_new_word").performScrollTo().performTextInput("测试词")
        compose.onNodeWithTag("user_dict_new_reading").performScrollTo().performTextInput("ceshici")
        compose.onNodeWithTag("user_dict_add").performScrollTo().performClick()
        assertEquals(s(R.string.user_dict_toast_added), reported())
        assertEquals(listOf("测试词"), UserDictEdit.list(db).filter { it.reading == "ceshici" }.map { it.word })
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 1)).assertExists()
        compose.onNodeWithTag("user_dict_search").performTextInput("ceshici")
        compose.onNodeWithText(row("测试词", "ceshici")).assertExists()
    }

    @Test fun delete_removes_the_row_and_the_userdb_entry() {
        seed(30, "shanchu" to "删除词")
        openUserDictPage()
        compose.onNodeWithTag("user_dict_search").performTextInput("shanchu")
        compose.onNodeWithText(row("删除词", "shanchu")).assertExists()
        compose.onNodeWithText(s(R.string.user_dict_delete_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.user_dict_delete_dialog_title)).assertExists()
        assertTrue("nothing is deleted before the confirmation", UserDictEdit.list(db).any { it.word == "删除词" })
        compose.onNodeWithTag("user_dict_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_deleted), reported())
        compose.onNodeWithText(s(R.string.user_dict_search_no_match)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 30)).assertExists()
        assertTrue(UserDictEdit.list(db).none { it.word == "删除词" })
    }

    @Test fun deleting_a_word_asks_first_and_cancelling_keeps_it() {
        seed(0, "nihao" to "你好")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_search").performTextInput("nihao")
        compose.onNodeWithText(s(R.string.user_dict_delete_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.getString(R.string.user_dict_delete_dialog_body, row("你好", "nihao")),
        ).assertExists()
        assertTrue("nothing is deleted before the confirmation", UserDictEdit.list(db).any { it.word == "你好" })

        compose.onNodeWithTag("user_dict_delete_cancel").performClick()
        compose.waitForIdle()

        assertTrue("cancelling keeps the word", UserDictEdit.list(db).any { it.word == "你好" })
        compose.onNodeWithText(row("你好", "nihao")).assertExists()
        assertNull("nothing may be reported when nothing was done", AegisToast.textForTest())
    }

    @Test fun deleting_a_learned_word_asks_first_and_cancelling_keeps_it() {
        seed(0)
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(s(R.string.user_dict_delete_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.getString(R.string.user_dict_delete_dialog_body, row("你呢嗯", "ninen")),
        ).assertExists()

        compose.onNodeWithTag("user_dict_delete_cancel").performClick()
        compose.waitForIdle()

        assertTrue("cancelling keeps the learned word", UserLearnEdit.list(learn).any { it.word == "你呢嗯" })
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertExists()
    }

    @Test fun the_auto_learning_section_lists_a_glued_word_and_deletes_it_on_its_own() {
        seed(0)
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        assertEquals(listOf("你呢嗯"), UserLearnEdit.list(learn).map { it.word })
        openUserDictPage()

        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(ctx.getString(R.string.user_dict_auto_count_format, 1)).assertExists()
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertExists()

        compose.onNodeWithText(s(R.string.user_dict_delete_button)).performClick()
        compose.waitForIdle()
        assertTrue("nothing is deleted before the confirmation", UserLearnEdit.list(learn).isNotEmpty())
        compose.onNodeWithTag("user_dict_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_deleted), reported())

        assertTrue("the learned word is gone from the store", UserLearnEdit.list(learn).isEmpty())
        compose.onNodeWithText(ctx.getString(R.string.user_dict_auto_count_format, 0)).assertExists()
        compose.onNodeWithText(s(R.string.user_dict_auto_empty)).assertExists()
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertDoesNotExist()
    }

    @Test fun a_count_of_zero_with_recorded_pairs_says_they_are_still_there() {
        seed(0)
        learn.writeText("aegis-userlearn 1\nC\t你\t好\t3.0\t1700000000000\n")
        assertTrue("there is no glued word to count", UserLearnEdit.list(learn).isEmpty())
        openUserDictPage()

        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasTestTag("user_dict_auto_pairs_only"))
        compose.onNodeWithText(ctx.getString(R.string.user_dict_auto_count_format, 0)).assertExists()
        compose.onNodeWithText(s(R.string.user_dict_auto_pairs_only)).assertExists()
    }

    @Test fun a_count_that_stands_on_its_own_says_nothing_about_next_word_pairs() {
        seed(0)
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithText(ctx.getString(R.string.user_dict_auto_count_format, 1)).assertExists()
        compose.onNodeWithTag("user_dict_auto_pairs_only").assertDoesNotExist()
    }

    @Test fun selection_mode_swaps_delete_buttons_for_ticks_and_leaves_no_tick_behind() {
        seed(0, "nihao" to "你好")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_search").performTextInput("nihao")
        compose.onNodeWithText(s(R.string.user_dict_delete_button)).assertExists()
        compose.onNodeWithTag("user_dict_select").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText(s(R.string.user_dict_delete_button)).assertCountEquals(0)
        compose.onNodeWithText(row("你好", "nihao")).assertIsOff()
        compose.onNodeWithText(row("你好", "nihao")).performClick()
        compose.onNodeWithText(row("你好", "nihao")).assertIsOn()

        compose.onNodeWithTag("user_dict_select_cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.user_dict_delete_button)).assertExists()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(row("你好", "nihao")).assertIsOff()
        assertTrue("leaving selection mode deletes nothing", UserDictEdit.list(db).any { it.word == "你好" })
    }

    @Test fun overview_status_actions_and_entry_text_keep_one_left_baseline_across_selection() {
        seed(0, "nihao" to "你好")
        openUserDictPage()
        compose.onNodeWithTag("user_dict_search").performTextInput("nihao")

        val countLeft = compose.onNodeWithTag("user_dict_count").getUnclippedBoundsInRoot().left.value
        val forgottenLeft = compose.onNodeWithTag("user_dict_forgotten").getUnclippedBoundsInRoot().left.value
        val manageLeft = compose.onNodeWithTag("user_dict_select").getUnclippedBoundsInRoot().left.value
        assertEquals("count and forgotten status share a left baseline", countLeft, forgottenLeft, 0.5f)
        assertEquals("status and batch management share a left baseline", countLeft, manageLeft, 0.5f)

        val normalEntryLeft = compose.onNodeWithTag("user_dict_entry_text", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().left.value
        compose.onNodeWithTag("user_dict_select").performClick()
        compose.waitForIdle()
        val selectionEntryLeft = compose.onNodeWithTag("user_dict_entry_text", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().left.value
        assertEquals("selection mode must not shift the word column", normalEntryLeft, selectionEntryLeft, 0.5f)
    }

    @Config(qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    @Test fun the_action_rows_spread_their_items_between_the_card_edges() {
        seed(0, "nihao" to "你好")
        openUserDictPage()

        val pad = 16f
        val overview = compose.onNodeWithTag("user_dict_overview").getUnclippedBoundsInRoot()
        val manage = compose.onNodeWithTag("user_dict_select").getUnclippedBoundsInRoot()
        val more = compose.onNodeWithTag("user_dict_open_more").getUnclippedBoundsInRoot()
        assertEquals("the first overview action hugs the content start", overview.left.value + pad, manage.left.value, 0.5f)
        assertEquals("the last overview action hugs the content end", overview.right.value - pad, more.right.value, 0.5f)

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.waitForIdle()

        val card = compose.onNodeWithTag("user_dict_overview").getUnclippedBoundsInRoot()
        val selectAll = compose.onNodeWithTag("user_dict_select_all").getUnclippedBoundsInRoot()
        val delete = compose.onNodeWithTag("user_dict_delete_selected").getUnclippedBoundsInRoot()
        assertEquals("the first selection action hugs the content start", card.left.value + pad, selectAll.left.value, 0.5f)
        assertEquals("the last selection action hugs the content end", card.right.value - pad, delete.right.value, 0.5f)
    }

    @Config(qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    @Test fun the_middle_action_sits_evenly_between_its_neighbours() {
        seed(0, "nihao" to "\u4f60\u597d")
        openUserDictPage()

        val manage = compose.onNodeWithTag("user_dict_select").getUnclippedBoundsInRoot()
        val add = compose.onNodeWithTag("user_dict_open_add").getUnclippedBoundsInRoot()
        val more = compose.onNodeWithTag("user_dict_open_more").getUnclippedBoundsInRoot()
        assertEquals(
            "the add action splits its neighbours evenly",
            add.left.value - manage.right.value,
            more.left.value - add.right.value,
            1f,
        )

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.waitForIdle()
        val selectAll = compose.onNodeWithTag("user_dict_select_all").getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithTag("user_dict_select_cancel").getUnclippedBoundsInRoot()
        val remove = compose.onNodeWithTag("user_dict_delete_selected").getUnclippedBoundsInRoot()
        assertEquals(
            "the cancel action splits its neighbours evenly",
            cancel.left.value - selectAll.right.value,
            remove.left.value - cancel.right.value,
            1f,
        )
    }

    @Test fun the_selection_count_stays_readable_when_the_actions_wrap() {
        seed(0, "nihao" to "你好")
        openUserDictPage()
        compose.onNodeWithTag("user_dict_select").performClick()
        compose.waitForIdle()

        val card = compose.onNodeWithTag("user_dict_overview").getUnclippedBoundsInRoot()
        val count = compose.onNodeWithTag("user_dict_selected_count").getUnclippedBoundsInRoot()
        val delete = compose.onNodeWithTag("user_dict_delete_selected").getUnclippedBoundsInRoot()
        assertTrue("the count line keeps a readable height", count.bottom.value - count.top.value >= 14f)
        assertTrue("the count line stays inside the card", count.top.value >= card.top.value - 0.5f)
        assertTrue("the delete action stays inside the card", delete.bottom.value <= card.bottom.value + 0.5f)
    }

    @Test fun selection_context_counts_ticks_and_system_back_exits_selection_before_the_activity() {
        seed(0, "nihao" to "你好")
        openUserDictPage()
        compose.onNodeWithTag("user_dict_search").performTextInput("nihao")
        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_selected_count_format, 0)).assertExists()
        compose.onNodeWithText(row("你好", "nihao")).performClick()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_selected_count_format, 1)).assertExists()

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag("user_dict_selection_context").assertDoesNotExist()
        compose.onNodeWithTag("user_dict_select").assertExists()
        scenario!!.onActivity { assertFalse("selection back must not finish the Activity", it.isFinishing) }
    }

    @Test fun the_delete_selected_action_sits_inside_the_selection_card_and_follows_the_ticks() {
        seed(0, "nihao" to "你好")
        openUserDictPage()
        compose.onNodeWithTag("user_dict_delete_selected").assertDoesNotExist()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("user_dict_selection_bottom_bar").assertDoesNotExist()
        val card = compose.onNodeWithTag("user_dict_overview").getUnclippedBoundsInRoot()
        val delete = compose.onNodeWithTag("user_dict_delete_selected").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("user_dict_list_surface").getUnclippedBoundsInRoot()
        assertTrue("the delete action must start inside the selection card", delete.top.value >= card.top.value - 0.5f)
        assertTrue("the delete action must end inside the selection card", delete.bottom.value <= card.bottom.value + 0.5f)
        assertTrue("the selection card must stay above the scrolling list", card.bottom.value <= list.top.value + 0.5f)

        compose.onNodeWithTag("user_dict_delete_selected").assertIsNotEnabled()
        compose.onNodeWithText(row("你好", "nihao")).performClick()
        compose.onNodeWithTag("user_dict_delete_selected").assertIsEnabled()
        compose.onNodeWithText(row("你好", "nihao")).performClick()
        compose.onNodeWithTag("user_dict_delete_selected").assertIsNotEnabled()
    }

    @Test fun entering_and_leaving_selection_keeps_the_cards_and_the_list_pinned_in_place() {
        seed(0, "nihao" to "你好")
        openUserDictPage()

        val overviewBefore = compose.onNodeWithTag("user_dict_overview").getUnclippedBoundsInRoot()
        val listBefore = compose.onNodeWithTag("user_dict_list_surface").getUnclippedBoundsInRoot()
        val entryBefore = compose.onNodeWithTag("user_dict_entry_text", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.waitForIdle()

        val cardDuring = compose.onNodeWithTag("user_dict_overview").getUnclippedBoundsInRoot()
        val listDuring = compose.onNodeWithTag("user_dict_list_surface").getUnclippedBoundsInRoot()
        val entryDuring = compose.onNodeWithTag("user_dict_entry_text", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals("the selection card must take the overview card's top", overviewBefore.top.value, cardDuring.top.value, 0.5f)
        assertEquals("the selection card must take the overview card's bottom", overviewBefore.bottom.value, cardDuring.bottom.value, 0.5f)
        assertEquals("entering selection must not move the list", listBefore.top.value, listDuring.top.value, 0.5f)
        assertEquals("entering selection must not resize the list", listBefore.bottom.value, listDuring.bottom.value, 0.5f)
        assertEquals("entering selection must not move the first entry", entryBefore.top.value, entryDuring.top.value, 0.5f)

        compose.onNodeWithTag("user_dict_select_cancel").performClick()
        compose.waitForIdle()

        val overviewAfter = compose.onNodeWithTag("user_dict_overview").getUnclippedBoundsInRoot()
        val listAfter = compose.onNodeWithTag("user_dict_list_surface").getUnclippedBoundsInRoot()
        val entryAfter = compose.onNodeWithTag("user_dict_entry_text", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals("leaving selection must hand the card back at the same top", overviewBefore.top.value, overviewAfter.top.value, 0.5f)
        assertEquals("leaving selection must hand the card back at the same bottom", overviewBefore.bottom.value, overviewAfter.bottom.value, 0.5f)
        assertEquals("leaving selection must not move the list", listBefore.top.value, listAfter.top.value, 0.5f)
        assertEquals("leaving selection must not move the first entry", entryBefore.top.value, entryAfter.top.value, 0.5f)
    }

    @Test fun select_all_asks_with_the_count_and_only_a_confirmation_empties_both_sections() {
        seed(0, "nihao" to "你好", "ceshi" to "测试")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithTag("user_dict_select_all").performClick()
        compose.onNodeWithTag("user_dict_delete_selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.resources.getQuantityString(R.plurals.user_dict_batch_delete_dialog_body, 3, 3),
        ).assertExists()
        assertEquals("nothing is deleted before the confirmation", 2, UserDictEdit.list(db).size)
        assertEquals("nothing is deleted before the confirmation", 1, UserLearnEdit.list(learn).size)

        compose.onNodeWithTag("user_dict_batch_delete_cancel").performClick()
        compose.waitForIdle()
        assertEquals("cancelling keeps every word", 2, UserDictEdit.list(db).size)
        assertEquals("cancelling keeps every learned word", 1, UserLearnEdit.list(learn).size)

        compose.onNodeWithTag("user_dict_delete_selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("user_dict_batch_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_batch_deleted), reported())

        assertTrue("every ticked word left the word list", UserDictEdit.list(db).isEmpty())
        assertTrue("every ticked learned word left the store", UserLearnEdit.list(learn).isEmpty())
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 0)).assertExists()
        compose.onNodeWithTag("user_dict_select").assertExists()
    }

    @Test fun select_all_under_a_search_reaches_no_further_than_what_the_search_found() {
        seed(0, "nihao" to "你好", "ceshi" to "测试", "liuxia" to "留下")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_search").performTextInput("nihao")
        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithTag("user_dict_select_all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(row("你好", "nihao")).assertIsOn()

        compose.onNodeWithTag("user_dict_delete_selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.resources.getQuantityString(R.plurals.user_dict_batch_delete_dialog_body, 1, 1),
        ).assertExists()
        compose.onNodeWithTag("user_dict_batch_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_batch_deleted), reported())

        assertEquals(
            "a word the search never showed must not be deleted",
            listOf("测试", "留下"),
            UserDictEdit.list(db).map { it.word }.sorted(),
        )
        assertTrue(
            "and neither may a learned word the search never showed",
            UserLearnEdit.list(learn).any { it.word == "你呢嗯" },
        )
    }

    @Test fun select_all_with_no_search_behind_it_ticks_every_word_in_both_sections() {
        seed(0, "nihao" to "你好", "ceshi" to "测试")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithTag("user_dict_select_all").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你好", "nihao")))
        compose.onNodeWithText(row("你好", "nihao")).assertIsOn()
        compose.onNodeWithText(row("测试", "ceshi")).assertIsOn()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertIsOn()

        compose.onNodeWithTag("user_dict_delete_selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.resources.getQuantityString(R.plurals.user_dict_batch_delete_dialog_body, 3, 3),
        ).assertExists()
    }

    @Test fun changing_the_search_term_drops_every_tick_including_the_rows_it_hides() {
        seed(0, "nihao" to "你好", "ceshi" to "测试", "liuxia" to "留下")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你好", "nihao")))
        compose.onNodeWithText(row("你好", "nihao")).performClick()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(row("你呢嗯", "ninen")).performClick()

        compose.onNodeWithTag("user_dict_search").performTextInput("ceshi")
        compose.waitForIdle()
        compose.onNodeWithText(row("测试", "ceshi")).assertIsOff()
        compose.onNodeWithTag("user_dict_delete_selected").assertIsNotEnabled()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你好", "nihao")))
        compose.onNodeWithText(row("你好", "nihao")).assertIsOff()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertIsOff()
        compose.onNodeWithTag("user_dict_delete_selected").assertIsNotEnabled()

        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("测试", "ceshi")))
        compose.onNodeWithText(row("测试", "ceshi")).performClick()
        compose.onNodeWithTag("user_dict_delete_selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.resources.getQuantityString(R.plurals.user_dict_batch_delete_dialog_body, 1, 1),
        ).assertExists()
        compose.onNodeWithTag("user_dict_batch_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_batch_deleted), reported())

        assertEquals(
            "a tick from before the search changed must not be carried along",
            listOf("你好", "留下"),
            UserDictEdit.list(db).map { it.word }.sorted(),
        )
        assertTrue(
            "and neither may a learned tick the later search had hidden",
            UserLearnEdit.list(learn).any { it.word == "你呢嗯" },
        )
    }

    @Test fun a_second_tap_on_select_all_clears_the_selection() {
        seed(0, "nihao" to "你好", "ceshi" to "测试")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithText(s(R.string.user_dict_select_all_button)).assertExists()
        compose.onNodeWithTag("user_dict_select_all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_selected_count_format, 3)).assertExists()
        compose.onNodeWithTag("user_dict_delete_selected").assertIsEnabled()
        compose.onNodeWithText(s(R.string.user_dict_deselect_all_button)).assertExists()

        compose.onNodeWithTag("user_dict_select_all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.user_dict_select_all_button)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_selected_count_format, 0)).assertExists()
        compose.onNodeWithTag("user_dict_delete_selected").assertIsNotEnabled()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你好", "nihao")))
        compose.onNodeWithText(row("你好", "nihao")).assertIsOff()
        compose.onNodeWithText(row("测试", "ceshi")).assertIsOff()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertIsOff()
    }

    @Test fun a_selection_from_an_earlier_search_never_reaches_the_deletion() {
        seed(0, "nihao" to "你好", "ceshi" to "测试", "liuxia" to "留下")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_search").performTextInput("nihao")
        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithTag("user_dict_select_all").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("ceshi")
        compose.onNodeWithTag("user_dict_select_all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(row("测试", "ceshi")).assertIsOn()

        compose.onNodeWithTag("user_dict_delete_selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.resources.getQuantityString(R.plurals.user_dict_batch_delete_dialog_body, 1, 1),
        ).assertExists()
        compose.onNodeWithTag("user_dict_batch_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_batch_deleted), reported())

        assertEquals(
            "what an earlier search had ticked must not be carried into this deletion",
            listOf("你好", "留下"),
            UserDictEdit.list(db).map { it.word }.sorted(),
        )
    }

    @Test fun ticking_two_rows_deletes_just_those_and_keeps_the_rest() {
        seed(0, "nihao" to "你好", "ceshi" to "测试", "liuxia" to "留下")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你好", "nihao")))
        compose.onNodeWithText(row("你好", "nihao")).performClick()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(row("你呢嗯", "ninen")).performClick()

        compose.onNodeWithTag("user_dict_delete_selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.resources.getQuantityString(R.plurals.user_dict_batch_delete_dialog_body, 2, 2),
        ).assertExists()
        compose.onNodeWithTag("user_dict_batch_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_batch_deleted), reported())

        assertEquals(
            "the words left unticked survive",
            listOf("测试", "留下"),
            UserDictEdit.list(db).map { it.word }.sorted(),
        )
        assertTrue("the ticked word is gone", UserDictEdit.list(db).none { it.word == "你好" })
        assertTrue("the ticked learned word is gone", UserLearnEdit.list(learn).none { it.word == "你呢嗯" })
    }

    @Test fun a_batch_delete_that_never_reached_storage_says_so() {
        UserDictHot.host = RefusingHost(
            listOf(UserModel.Entry("shanchu", "删除词", 1)),
            listOf(UserLearning.Formed("你呢嗯", "ninen")),
        )
        openUserDictPage()

        compose.onNodeWithTag("user_dict_select").performClick()
        compose.onNodeWithTag("user_dict_select_all").performClick()
        AegisToast.reset()
        compose.onNodeWithTag("user_dict_delete_selected").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            ctx.resources.getQuantityString(R.plurals.user_dict_batch_delete_dialog_body, 2, 2),
        ).assertExists()
        compose.onNodeWithTag("user_dict_batch_delete_confirm").performClick()

        assertEquals(s(R.string.user_dict_toast_write_failed), reported())
    }

    @Test fun re_adding_a_word_that_is_already_there_says_it_is_yours_from_now_on() {
        val used = System.currentTimeMillis()
        db.writeText("aegis-userdb 1\nW\t自动词\t3\t$used\nR\tzidongci\t自动词\n")
        openUserDictPage()

        AegisToast.reset()
        openAddSheet()
        compose.onNodeWithTag("user_dict_new_word").performScrollTo().performTextInput("自动词")
        compose.onNodeWithTag("user_dict_new_reading").performScrollTo().performTextInput("zidongci")
        compose.onNodeWithTag("user_dict_add").performScrollTo().performClick()
        assertEquals(s(R.string.user_dict_toast_kept), reported())
        assertEquals(
            "the word is marked as the user's own, which is what exempts it from fading out",
            mapOf("zidongci" to setOf("自动词")),
            UserModel().apply { load(db, sweepStale = false) }.manualSnapshot(),
        )
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 1)).assertExists()

        AegisToast.reset()
        openAddSheet()
        compose.onNodeWithTag("user_dict_new_word").performScrollTo().performTextInput("全新词")
        compose.onNodeWithTag("user_dict_new_reading").performScrollTo().performTextInput("quanxinci")
        compose.onNodeWithTag("user_dict_add").performScrollTo().performClick()
        assertEquals(
            "a word that was not there yet keeps the plain confirmation",
            s(R.string.user_dict_toast_added),
            reported(),
        )
    }

    @Test fun a_word_the_dictionary_cannot_hold_is_never_reported_as_added() {
        seed(0)
        openUserDictPage()

        AegisToast.reset()
        openAddSheet()
        compose.onNodeWithTag("user_dict_new_word").performScrollTo().performTextInput("词".repeat(257))
        compose.onNodeWithTag("user_dict_new_reading").performScrollTo().performTextInput("ceshi")
        compose.onNodeWithTag("user_dict_add").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(s(R.string.user_dict_toast_add_rejected), AegisToast.textForTest())
        compose.onNodeWithTag("user_dict_add_sheet_toast").assertExists()
        assertTrue("a word that cannot be stored must not be listed as though it had been", UserDictEdit.list(db).isEmpty())
    }

    @Test fun searching_also_reaches_the_automatically_learned_words() {
        seed(0, "nihao" to "你好")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_search").performTextInput("ninen")
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertExists()
        compose.onNodeWithText(row("你好", "nihao")).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.user_dict_search_no_match)).assertDoesNotExist()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("你呢")
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertExists()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("nihao")
        compose.onNodeWithText(row("你好", "nihao")).assertExists()
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertDoesNotExist()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("zzzz9")
        compose.onNodeWithText(s(R.string.user_dict_search_no_match)).assertExists()
    }

    @Test fun the_page_says_how_many_words_faded_out_and_keeps_the_ones_the_user_owns() {
        val ancient = System.currentTimeMillis() - 400L * 24L * 60L * 60L * 1000L
        db.writeText(
            "aegis-userdb 2\nW\t旧词\t1\t$ancient\nW\t留着\t1\t$ancient\n" +
                "R\tjiuci\t旧词\nR\tliuzhe\t留着\nM\tliuzhe\t留着\n",
        )
        openUserDictPage()

        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 1)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_forgotten_format, 1)).assertExists()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("留着", "liuzhe")))
        compose.onNodeWithText(row("留着", "liuzhe")).assertExists()
        compose.onNodeWithText(row("旧词", "jiuci")).assertDoesNotExist()
    }

    @Test fun a_word_list_that_cannot_be_read_says_so_instead_of_looking_empty() {
        db.writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_unreadable").assertExists()
        compose.onNodeWithTag("user_dict_count").assertDoesNotExist()
        compose.onNodeWithTag("user_dict_forgotten").assertDoesNotExist()
        compose.onNodeWithTag("user_dict_list").assertExists()
        assertThrows(
            "a list that could not be read must never be presented as an empty one",
            AssertionError::class.java,
        ) {
            compose.onNodeWithTag("user_dict_list").performScrollToNode(hasTestTag("user_dict_empty_note"))
        }
        assertTrue(
            "the file the page could not read must still be on disk untouched",
            db.readText().startsWith("this is not an aegis user dictionary"),
        )
    }

    private fun unreadableDictionaryHost(): LiveUserDictHost {
        db.writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n")
        val model = UserModel().apply {
            runCatching { load(db) }
            record(null, "打过字", 1L)
        }
        return liveHost(model, db, UserLearning(), learn)
    }

    @Test fun a_live_keyboard_holding_an_unreadable_word_list_still_says_so() {
        UserDictHot.host = unreadableDictionaryHost()
        openUserDictPage()

        compose.onNodeWithTag("user_dict_unreadable").assertExists()
        compose.onNodeWithTag("user_dict_count").assertDoesNotExist()
        compose.onNodeWithTag("user_dict_forgotten").assertDoesNotExist()
    }

    @Test fun a_word_added_onto_an_unreadable_word_list_is_never_shown_as_if_it_landed() {
        UserDictHot.host = unreadableDictionaryHost()
        openUserDictPage()

        AegisToast.reset()
        openAddSheet()
        compose.onNodeWithTag("user_dict_new_word").performScrollTo().performTextInput("幽灵词")
        compose.onNodeWithTag("user_dict_new_reading").performScrollTo().performTextInput("youlingci")
        compose.onNodeWithTag("user_dict_add").performScrollTo().performClick()

        assertEquals(s(R.string.user_dict_toast_write_failed), reported())
        compose.onNodeWithTag("user_dict_unreadable").assertExists()
        compose.onNodeWithTag("user_dict_list").assertExists()
        assertThrows(
            "a word that never reached storage must not be listed as though it had",
            AssertionError::class.java,
        ) {
            compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("幽灵词", "youlingci")))
        }
        assertTrue(
            "the file the page could not read must still be on disk untouched",
            db.readText().startsWith("this is not an aegis user dictionary"),
        )
    }

    private class HoldingHost(
        private val gate: CountDownLatch,
        private val reached: AtomicBoolean,
    ) : UserDictHot.Host {
        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String): Boolean {
            gate.await(10, TimeUnit.SECONDS)
            reached.set(true)
            return true
        }
        override fun importUserDict(importFile: File, merge: Boolean, now: Long) = false
        override fun reloadDictionary() = false
        override fun entries(): List<UserModel.Entry> =
            if (reached.get()) emptyList() else listOf(UserModel.Entry("shanchu", "删除词", 1))
        override fun learnedEntries(): List<UserLearning.Formed> = emptyList()
        override fun hasLearnedData() = false
        override fun removeLearned(word: String, reading: String) = false
        override fun clearLearned() = false
        override fun flush() = false
    }

    @Test fun a_deletion_hands_the_store_over_instead_of_making_the_page_wait_for_it() {
        val gate = CountDownLatch(1)
        val reached = AtomicBoolean(false)
        UserDictHot.host = HoldingHost(gate, reached)
        openUserDictPage()
        compose.onNodeWithTag("user_dict_search").performTextInput("shanchu")
        compose.onNodeWithText(row("删除词", "shanchu")).assertExists()
        AegisToast.reset()

        compose.onNodeWithText(s(R.string.user_dict_delete_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("user_dict_delete_confirm").performClick()

        assertFalse("the tap must come back before the store has been touched", reached.get())
        assertNull("and nothing may be reported before there is anything to report", AegisToast.textForTest())
        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("shan")

        gate.countDown()

        assertEquals(s(R.string.user_dict_toast_deleted), reported())
        assertTrue("the store really was written once the lane got to it", reached.get())
        compose.onNodeWithText(row("删除词", "shanchu")).assertDoesNotExist()
    }

    @Test fun an_export_is_not_blocked_by_a_word_list_that_could_not_be_read() {
        UserDictHot.host = unreadableDictionaryHost()
        openUserDictPage()

        AegisToast.reset()
        startExportFromTools()
        settleEdits()

        scenario!!.onActivity { activity ->
            assertNotNull(
                "carrying the broken file out to repair it by hand must stay possible",
                shadowOf(activity).peekNextStartedActivityForResult(),
            )
        }
    }

    @Test fun learned_data_that_cannot_be_read_says_so_instead_of_looking_empty() {
        seed(0, "nihao" to "你好")
        learn.writeText("not a learning file at all\n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(s(R.string.user_learn_unreadable)))
        compose.onNodeWithTag("user_learn_unreadable").assertExists()
        compose.onNodeWithTag("user_dict_auto_count").assertDoesNotExist()
        compose.onNodeWithText(s(R.string.user_dict_auto_empty)).assertDoesNotExist()
        compose.onNodeWithTag("user_dict_count").assertExists()
        assertTrue("the unreadable learning file is left as it was", learn.readText().startsWith("not a learning file"))
    }

    @Test fun learned_data_the_keyboard_still_holds_is_not_described_as_not_shown() {
        seed(0, "nihao" to "你好")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "en")
        val learning = UserLearning { 1_700_000_000_000L }.apply { load(learn) }
        assertTrue("precondition: the word was learned", learning.formedEntries().isNotEmpty())
        learn.writeText("aegis-userlearn 1\nF\tzh")
        learning.load(learn)
        assertTrue("precondition: the keyboard still holds it", learning.formedEntries().isNotEmpty())
        assertTrue("precondition: the store can no longer be read", !learning.readable)
        UserDictHot.host = liveHost(UserModel(), db, learning, learn)
        openUserDictPage()

        compose.onNodeWithTag("user_dict_list")
            .performScrollToNode(hasText(s(R.string.user_learn_unreadable_kept)))
        compose.onNodeWithTag("user_learn_unreadable").assertExists()
        compose.onAllNodesWithText(s(R.string.user_learn_unreadable)).assertCountEquals(0)
        compose.onNodeWithTag("user_dict_list")
            .performScrollToNode(hasText("你呢嗯", substring = true))
            .assertExists()
    }

    private class RefusingHost(
        private val entries: List<UserModel.Entry>,
        private val learned: List<UserLearning.Formed>,
    ) : UserDictHot.Host {
        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String) = false
        override fun importUserDict(importFile: File, merge: Boolean, now: Long) = false
        override fun reloadDictionary() = false
        override fun entries(): List<UserModel.Entry> = entries
        override fun learnedEntries(): List<UserLearning.Formed> = learned
        override fun hasLearnedData() = learned.isNotEmpty()
        override fun removeLearned(word: String, reading: String) = false
        override fun clearLearned() = false
        override fun flush() = false
    }

    @Test fun a_word_that_never_reached_storage_says_so_and_keeps_what_was_typed() {
        UserDictHot.host = RefusingHost(emptyList(), emptyList())
        openUserDictPage()

        AegisToast.reset()
        openAddSheet()
        compose.onNodeWithTag("user_dict_new_word").performScrollTo().performTextInput("测试词")
        compose.onNodeWithTag("user_dict_new_reading").performScrollTo().performTextInput("ceshici")
        compose.onNodeWithTag("user_dict_add").performScrollTo().performClick()

        assertEquals(s(R.string.user_dict_toast_write_failed), reported())
        compose.onNodeWithText("测试词").assertExists()
        compose.onNodeWithText("ceshici").assertExists()
    }

    @Test fun a_deletion_that_never_reached_storage_says_so() {
        UserDictHot.host = RefusingHost(
            listOf(UserModel.Entry("shanchu", "删除词", 1)),
            listOf(UserLearning.Formed("你呢嗯", "ninen")),
        )
        openUserDictPage()

        AegisToast.reset()
        compose.onNodeWithTag("user_dict_search").performTextInput("shanchu")
        compose.onNodeWithText(s(R.string.user_dict_delete_button)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("user_dict_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_write_failed), reported())

        AegisToast.reset()
        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertExists()
        compose.onAllNodesWithText(s(R.string.user_dict_delete_button)).onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("user_dict_delete_confirm").performClick()
        assertEquals(s(R.string.user_dict_toast_write_failed), reported())
    }

    @Test fun an_export_with_nothing_behind_it_says_so_instead_of_blaming_the_picked_file() {
        openUserDictPage()

        AegisToast.reset()
        startExportFromTools()
        settleEdits()

        assertEquals(s(R.string.user_dict_toast_export_empty), AegisToast.textForTest())
        assertFalse(
            "a device with no word list must not be told its file could not be written",
            s(R.string.user_dict_toast_export_failed) == AegisToast.textForTest(),
        )
        scenario!!.onActivity { activity ->
            assertNull(
                "and no empty document may be created for an export that has nothing to carry",
                shadowOf(activity).peekNextStartedActivityForResult(),
            )
        }
    }

    @Test fun an_export_is_cancelled_rather_than_shipped_stale_when_the_flush_is_refused() {
        UserDictHot.host = RefusingHost(listOf(UserModel.Entry("nihao", "你好", 1)), emptyList())
        openUserDictPage()

        AegisToast.reset()
        startExportFromTools()
        settleEdits()

        assertEquals(s(R.string.user_dict_toast_export_blocked), AegisToast.textForTest())
        scenario!!.onActivity { activity ->
            assertEquals(
                "no document picker may open when the dictionary on disk is out of date",
                null,
                shadowOf(activity).peekNextStartedActivityForResult(),
            )
        }
    }

    private class FlushWatchingHost(private val onMainThread: AtomicReference<Boolean?>) : UserDictHot.Host {
        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String) = false
        override fun importUserDict(importFile: File, merge: Boolean, now: Long) = false
        override fun reloadDictionary() = false
        override fun entries(): List<UserModel.Entry> = listOf(UserModel.Entry("nihao", "你好", 1))
        override fun learnedEntries(): List<UserLearning.Formed> = emptyList()
        override fun hasLearnedData() = false
        override fun removeLearned(word: String, reading: String) = false
        override fun clearLearned() = false
        override fun flush() = true
        override fun flushDictionary(): Boolean {
            onMainThread.set(Looper.myLooper() === Looper.getMainLooper())
            return true
        }
    }

    @Test fun an_export_never_waits_for_the_writer_on_the_thread_that_draws() {
        val onMainThread = AtomicReference<Boolean?>(null)
        seed(0, "nihao" to "你好")
        UserDictHot.host = FlushWatchingHost(onMainThread)
        openUserDictPage()

        startExportFromTools()
        settleEdits()

        assertNotNull("precondition: the export really did flush the store", onMainThread.get())
        assertEquals(
            "the flush waits up to five seconds for the writer, so it must not be waited for where the keys are drawn",
            false,
            onMainThread.get(),
        )
        scenario!!.onActivity { activity ->
            assertNotNull(
                "and the picker must still open once the flush came back",
                shadowOf(activity).peekNextStartedActivityForResult(),
            )
        }
    }

    private class GatedFlushHost(private val gate: CountDownLatch) : UserDictHot.Host {
        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String) = false
        override fun importUserDict(importFile: File, merge: Boolean, now: Long) = false
        override fun reloadDictionary() = false
        override fun entries(): List<UserModel.Entry> = listOf(UserModel.Entry("nihao", "你好", 1))
        override fun learnedEntries(): List<UserLearning.Formed> = emptyList()
        override fun hasLearnedData() = false
        override fun removeLearned(word: String, reading: String) = false
        override fun clearLearned() = false
        override fun flush() = true
        override fun flushDictionary(): Boolean {
            gate.await(10, TimeUnit.SECONDS)
            return true
        }
    }

    @Test fun leaving_the_page_before_the_flush_comes_back_does_not_bring_the_app_down() {
        val gate = CountDownLatch(1)
        seed(0, "nihao" to "你好")
        UserDictHot.host = GatedFlushHost(gate)
        openUserDictPage()
        startExportFromTools()

        scenario!!.close()
        scenario = null
        gate.countDown()
        drainEdits()

        shadowOf(Looper.getMainLooper()).idle()
        assertNull("nobody is there to be told anything either", AegisToast.textForTest())
    }

    @Test fun an_export_opens_the_document_picker_once_the_flush_succeeded() {
        seed(0, "nihao" to "你好")
        openUserDictPage()

        startExportFromTools()
        settleEdits()

        scenario!!.onActivity { activity ->
            assertTrue(
                "the picker must open when there is nothing left unwritten",
                shadowOf(activity).peekNextStartedActivityForResult() != null,
            )
        }
    }

    @Test fun an_export_over_a_longer_file_leaves_none_of_the_old_one_behind() {
        val target = File(ctx.cacheDir, "user-dict-over-a-longer-file.txt")
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(200_000))
        val stale = target.length()
        seed(0, "nihao" to "你好")
        openUserDictPage()

        AegisToast.reset()
        startExportFromTools()
        settleEdits()
        scenario!!.onActivity { activity ->
            val picked = shadowOf(activity).peekNextStartedActivityForResult()
            assertNotNull("precondition: the export must reach the document picker", picked)
            shadowOf(activity).receiveResult(
                picked.intent,
                Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(target)),
            )
        }

        assertEquals(
            "precondition: the export itself must go through",
            s(R.string.user_dict_toast_export_done),
            reported(),
        )
        assertTrue(
            "a word list export must not leave the tail of a longer file behind, was ${target.length()} of $stale bytes",
            target.length() < stale,
        )
    }
}
