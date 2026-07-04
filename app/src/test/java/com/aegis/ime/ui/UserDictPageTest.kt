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

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.aegis.ime.R
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictHot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * debug.47 user-dictionary page: lazily-composed large list (thousand-entry class), live word/pinyin
 * search over the in-memory list (empty query / no result / pinyin prefix), and add/delete round-trips
 * into the same userdb.txt. Compose UI tests on Robolectric, reached by real navigation from the REAL
 * [SetupActivity] home (the userdb is seeded before the page is opened, so the page composes over it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UserDictPageTest {

    @get:Rule
    val compose = createAndroidComposeRule<SetupActivity>()

    private val ctx = RuntimeEnvironment.getApplication()
    private val db = File(ctx.filesDir, "userdb.txt")
    private fun s(id: Int) = ctx.getString(id)
    private fun row(word: String, reading: String) = ctx.getString(R.string.user_dict_entry_format, word, reading)

    @Before fun reset() {
        UserDictHot.host = null // page under test drives the file path; live routing is covered separately
        db.delete()
    }

    @After fun cleanup() {
        db.delete()
    }

    /** Navigate from home to the user-dictionary page (the activity starts on home; seed the file first). */
    private fun openUserDictPage() {
        compose.onNodeWithText(s(R.string.settings_group_userdict_title)).performScrollTo().performClick()
        compose.onNodeWithTag("user_dict_search").assertExists()
    }

    /** [n] filler entries (readings = 4 base-26 letters) plus explicit [extras] (reading → word). */
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
        // Rows exist…
        compose.onNodeWithTag("user_dict_list").assertExists()
        val composedDeleteButtons = compose.onAllNodesWithText(s(R.string.user_dict_delete_button))
            .fetchSemanticsNodes().size
        // …but only about a viewport's worth is composed: LazyColumn keeps 1200 entries smooth.
        assertTrue("expected at least one visible row, got $composedDeleteButtons", composedDeleteButtons > 0)
        assertTrue(
            "1200 entries must not all be composed (got $composedDeleteButtons rows)",
            composedDeleteButtons < 200,
        )
    }

    @Test fun search_by_pinyin_prefix_word_substring_and_the_empty_and_no_result_boundaries() {
        seed(50, "nihao" to "你好", "ceshi" to "测试")
        openUserDictPage()
        // Empty query: management tools are visible.
        compose.onNodeWithText(s(R.string.user_dict_export_button)).assertExists()

        // Pinyin prefix.
        compose.onNodeWithTag("user_dict_search").performTextInput("nih")
        compose.onNodeWithText(row("你好", "nihao")).assertExists()
        compose.onNodeWithText(row("测试", "ceshi")).assertDoesNotExist()
        // Tools hide while searching, so results sit right under the pinned search field.
        compose.onNodeWithText(s(R.string.user_dict_export_button)).assertDoesNotExist()

        // Word substring.
        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("测")
        compose.onNodeWithText(row("测试", "ceshi")).assertExists()
        compose.onNodeWithText(row("你好", "nihao")).assertDoesNotExist()

        // No result.
        compose.onNodeWithTag("user_dict_search").performTextClearance()
        compose.onNodeWithTag("user_dict_search").performTextInput("zzzz9")
        compose.onNodeWithText(s(R.string.user_dict_search_no_match)).assertExists()

        // Back to empty query: everything (incl. tools) returns; the row is deep in the lazy list, so
        // scroll the list to it (it is not composed until scrolled into view — that's the laziness).
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
        // The store agrees…
        assertEquals(listOf("测试词"), UserDictEdit.list(db).filter { it.reading == "ceshici" }.map { it.word })
        // …and the list shows it (search narrows to it deterministically).
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
        assertTrue(UserDictEdit.list(db).none { it.word == "删除词" })
    }
}
