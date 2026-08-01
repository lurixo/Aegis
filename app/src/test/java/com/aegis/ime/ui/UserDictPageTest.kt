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
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UserDictPageTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()
    private val db = File(ctx.filesDir, "userdb.txt")
    private var scenario: ActivityScenario<UserDictActivity>? = null
    private fun s(id: Int) = ctx.getString(id)
    private fun row(word: String, reading: String) = ctx.getString(R.string.user_dict_entry_format, word, reading)

    @Before fun reset() {
        UserDictHot.host = null
        clearUserDataFiles()
    }

    @After fun cleanup() {
        scenario?.close()
        clearUserDataFiles()
    }

    private fun clearUserDataFiles() {
        ctx.filesDir.listFiles()?.filter {
            it.name == "userdb.txt" || it.name.startsWith("user-data-v2") ||
                it.name == "user-data-migration.status"
        }?.forEach(File::delete)
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
                    append("W\t词$i\t1\t0\n")
                    append("R\t$reading\t词$i\n")
                }
                for ((reading, word) in extras) {
                    append("W\t$word\t1\t0\n")
                    append("R\t$reading\t$word\n")
                }
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
}
