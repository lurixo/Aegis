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

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UserDictPageTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()
    private val db = File(ctx.filesDir, "userdb.txt")
    private val learn = File(ctx.filesDir, "userlearn.txt")
    private var scenario: ActivityScenario<UserDictActivity>? = null
    private fun s(id: Int) = ctx.getString(id)
    private fun row(word: String, reading: String) = ctx.getString(R.string.user_dict_entry_format, word, reading)

    @Before fun reset() {
        UserDictHot.host = null
        db.delete()
        learn.delete()
    }

    @After fun cleanup() {
        scenario?.close()
        UserDictHot.host = null
        db.delete()
        learn.delete()
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
        compose.onNodeWithText(s(R.string.user_dict_export_button)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 52)).assertExists()

        compose.onNodeWithTag("user_dict_search").performTextInput("nih")
        compose.onNodeWithText(row("你好", "nihao")).assertExists()
        compose.onNodeWithText(row("测试", "ceshi")).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.user_dict_export_button)).assertDoesNotExist()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("测")
        compose.onNodeWithText(row("测试", "ceshi")).assertExists()
        compose.onNodeWithText(row("你好", "nihao")).assertDoesNotExist()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("zzzz9")
        compose.onNodeWithText(s(R.string.user_dict_search_no_match)).assertExists()

        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithText(s(R.string.user_dict_export_button)).assertExists()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你好", "nihao")))
        compose.onNodeWithText(row("你好", "nihao")).assertExists()
    }

    @Test fun add_writes_the_word_into_userdb_and_the_list() {
        seed(0)
        openUserDictPage()
        compose.onNodeWithTag("user_dict_new_word").performTextInput("测试词")
        compose.onNodeWithTag("user_dict_new_reading").performTextInput("ceshici")
        compose.onNodeWithTag("user_dict_add").performClick()
        compose.waitForIdle()
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
        compose.onNodeWithText(s(R.string.user_dict_search_no_match)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 30)).assertExists()
        assertTrue(UserDictEdit.list(db).none { it.word == "删除词" })
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

        assertTrue("the learned word is gone from the store", UserLearnEdit.list(learn).isEmpty())
        compose.onNodeWithText(ctx.getString(R.string.user_dict_auto_count_format, 0)).assertExists()
        compose.onNodeWithText(s(R.string.user_dict_auto_empty)).assertExists()
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertDoesNotExist()
    }

    @Test fun clearing_the_learned_data_asks_first_and_then_empties_the_section() {
        seed(0, "nihao" to "你好")
        seedLearned("你" to "ni", "呢" to "ne", "嗯" to "n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(s(R.string.user_dict_auto_clear_button)))
        compose.onNodeWithTag("user_dict_auto_clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.user_dict_auto_clear_dialog_body)).assertExists()
        assertTrue("nothing is cleared before the confirmation", UserLearnEdit.list(learn).isNotEmpty())

        compose.onNodeWithText(s(R.string.user_dict_auto_clear_cancel)).performClick()
        compose.waitForIdle()
        assertTrue("cancelling keeps the learned data", UserLearnEdit.list(learn).isNotEmpty())

        compose.onNodeWithTag("user_dict_auto_clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.user_dict_auto_clear_confirm)).performClick()
        compose.waitForIdle()

        assertTrue("confirming clears the learned data", UserLearnEdit.list(learn).isEmpty())
        compose.onNodeWithText(s(R.string.user_dict_auto_empty)).assertExists()
        assertTrue("the words the user added by hand survive", UserDictEdit.list(db).any { it.word == "你好" })
    }

    @Test fun re_adding_a_word_that_is_already_there_says_it_is_yours_from_now_on() {
        val used = System.currentTimeMillis()
        db.writeText("aegis-userdb 1\nW\t自动词\t3\t$used\nR\tzidongci\t自动词\n")
        openUserDictPage()

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_new_word").performTextInput("自动词")
        compose.onNodeWithTag("user_dict_new_reading").performTextInput("zidongci")
        compose.onNodeWithTag("user_dict_add").performClick()
        compose.waitForIdle()
        assertEquals(s(R.string.user_dict_toast_kept), ShadowToast.getTextOfLatestToast())
        assertEquals(
            "the word is marked as the user's own, which is what exempts it from fading out",
            mapOf("zidongci" to setOf("自动词")),
            UserModel().apply { load(db, sweepStale = false) }.manualSnapshot(),
        )
        compose.onNodeWithText(ctx.getString(R.string.user_dict_count_format, 1)).assertExists()

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_new_word").performTextInput("全新词")
        compose.onNodeWithTag("user_dict_new_reading").performTextInput("quanxinci")
        compose.onNodeWithTag("user_dict_add").performClick()
        compose.waitForIdle()
        assertEquals(
            "a word that was not there yet keeps the plain confirmation",
            s(R.string.user_dict_toast_added),
            ShadowToast.getTextOfLatestToast(),
        )
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

    @Test fun the_clear_button_still_works_when_only_the_next_word_data_is_left() {
        seed(0)
        learn.writeText("aegis-userlearn 1\nC\t你\t好\t3.0\t1700000000000\n")
        assertTrue("there is no glued word to list", UserLearnEdit.list(learn).isEmpty())
        openUserDictPage()

        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(s(R.string.user_dict_auto_clear_button)))
        compose.onNodeWithTag("user_dict_auto_clear").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.user_dict_auto_clear_confirm)).performClick()
        compose.waitForIdle()

        assertTrue("the next word data is gone", learn.readLines().none { it.startsWith("C\t") })
    }

    @Test fun a_word_list_that_cannot_be_read_says_so_instead_of_looking_empty() {
        db.writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_unreadable").assertExists()
        compose.onNodeWithTag("user_dict_count").assertDoesNotExist()
        compose.onNodeWithTag("user_dict_forgotten").assertDoesNotExist()
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
        return LiveUserDictHost(model, db, UserLearning(), learn)
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

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_new_word").performTextInput("幽灵词")
        compose.onNodeWithTag("user_dict_new_reading").performTextInput("youlingci")
        compose.onNodeWithTag("user_dict_add").performClick()
        compose.waitForIdle()

        assertEquals(s(R.string.user_dict_toast_write_failed), ShadowToast.getTextOfLatestToast())
        compose.onNodeWithTag("user_dict_unreadable").assertExists()
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

    @Test fun an_export_is_not_blocked_by_a_word_list_that_could_not_be_read() {
        UserDictHot.host = unreadableDictionaryHost()
        openUserDictPage()

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_export").performClick()
        compose.waitForIdle()

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
        compose.onNodeWithTag("user_dict_auto_clear").assertIsEnabled()
        assertTrue("the unreadable learning file is left as it was", learn.readText().startsWith("not a learning file"))
    }

    private class RefusingHost(
        private val entries: List<UserModel.Entry>,
        private val learned: List<UserLearning.Formed>,
    ) : UserDictHot.Host {
        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String) = false
        override fun importUserDict(importFile: File, merge: Boolean, now: Long) = false
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

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_new_word").performTextInput("测试词")
        compose.onNodeWithTag("user_dict_new_reading").performTextInput("ceshici")
        compose.onNodeWithTag("user_dict_add").performClick()
        compose.waitForIdle()

        assertEquals(s(R.string.user_dict_toast_write_failed), ShadowToast.getTextOfLatestToast())
        compose.onNodeWithText("测试词").assertExists()
        compose.onNodeWithText("ceshici").assertExists()
    }

    @Test fun a_deletion_that_never_reached_storage_says_so() {
        UserDictHot.host = RefusingHost(
            listOf(UserModel.Entry("shanchu", "删除词", 1)),
            listOf(UserLearning.Formed("你呢嗯", "ninen")),
        )
        openUserDictPage()

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_search").performTextInput("shanchu")
        compose.onNodeWithText(s(R.string.user_dict_delete_button)).performClick()
        compose.waitForIdle()
        assertEquals(s(R.string.user_dict_toast_write_failed), ShadowToast.getTextOfLatestToast())

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(row("你呢嗯", "ninen")))
        compose.onNodeWithText(row("你呢嗯", "ninen")).assertExists()
        compose.onAllNodesWithText(s(R.string.user_dict_delete_button)).onLast().performClick()
        compose.waitForIdle()
        assertEquals(s(R.string.user_dict_toast_write_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test fun clearing_the_learned_words_says_so_when_the_write_is_refused() {
        UserDictHot.host = RefusingHost(emptyList(), listOf(UserLearning.Formed("你呢嗯", "ninen")))
        openUserDictPage()

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_list").performScrollToNode(hasText(s(R.string.user_dict_auto_clear_button)))
        compose.onNodeWithTag("user_dict_auto_clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.user_dict_auto_clear_confirm)).performClick()
        compose.waitForIdle()

        assertEquals(s(R.string.user_dict_toast_write_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test fun an_export_is_cancelled_rather_than_shipped_stale_when_the_flush_is_refused() {
        UserDictHot.host = RefusingHost(listOf(UserModel.Entry("nihao", "你好", 1)), emptyList())
        openUserDictPage()

        ShadowToast.reset()
        compose.onNodeWithTag("user_dict_export").performClick()
        compose.waitForIdle()

        assertEquals(s(R.string.user_dict_toast_export_blocked), ShadowToast.getTextOfLatestToast())
        scenario!!.onActivity { activity ->
            assertEquals(
                "no document picker may open when the dictionary on disk is out of date",
                null,
                shadowOf(activity).peekNextStartedActivityForResult(),
            )
        }
    }

    @Test fun an_export_opens_the_document_picker_once_the_flush_succeeded() {
        seed(0, "nihao" to "你好")
        openUserDictPage()

        compose.onNodeWithTag("user_dict_export").performClick()
        compose.waitForIdle()

        scenario!!.onActivity { activity ->
            assertTrue(
                "the picker must open when there is nothing left unwritten",
                shadowOf(activity).peekNextStartedActivityForResult() != null,
            )
        }
    }
}
