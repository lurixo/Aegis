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

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.aegis.ime.R
import com.aegis.ime.ime.EmailDomains
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLexicon
import com.aegis.ime.user.UserStoreEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UserLexiconPageTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private val lexicon = UserLexicon(prefs)
    private val chineseDb = File(context.filesDir, "userdb.txt")
    private val chineseLearn = File(context.filesDir, "userlearn.txt")
    private var scenario: ActivityScenario<UserDictActivity>? = null

    @Before fun reset() {
        UserDictHot.host = null
        AegisToast.reset()
        prefs.edit().clear().commit()
        chineseDb.delete()
        chineseLearn.delete()
    }

    @After fun cleanup() {
        drainEdits()
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
        drainEdits()
        prefs.edit().clear().commit()
        chineseDb.delete()
        chineseLearn.delete()
        UserDictHot.host = null
        AegisToast.reset()
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
        drainEdits()
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun open(tab: String = "chinese") {
        scenario = ActivityScenario.launch(UserDictActivity::class.java)
        compose.onNodeWithTag("user_lexicon_tab_$tab").performClick()
        settleEdits()
    }

    private fun add(value: String) {
        compose.onNodeWithTag("user_lexicon_open_add").performClick()
        compose.onNodeWithTag("user_lexicon_new_value").assertIsFocused().performTextInput(value)
        compose.onNodeWithTag("user_lexicon_add").performClick()
        settleEdits()
    }

    private fun showEntry(value: String) {
        compose.onNodeWithTag("user_lexicon_list").performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun requestDelete(value: String) {
        val tag = "user_lexicon_delete_$value"
        compose.onNodeWithTag("user_lexicon_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    @Test fun chinese_management_keeps_existing_controls() {
        open()
        compose.onNodeWithTag("user_lexicon_tab_chinese").assertIsSelected()
        compose.onNodeWithTag("user_lexicon_tab_english").assertExists()
        compose.onNodeWithTag("user_lexicon_tab_email").assertExists()
        compose.onNodeWithTag("user_dict_search").assertExists()
        compose.onNodeWithTag("user_dict_open_add").assertExists()
        compose.onNodeWithTag("user_dict_open_more").performClick()
        compose.onNodeWithTag("user_dict_import").assertExists()
        compose.onNodeWithTag("user_dict_export").assertExists()
    }

    @Test fun horizontal_swipes_and_tab_taps_share_the_same_page_and_stop_at_the_edges() {
        open()
        val pager = compose.onNodeWithTag("user_lexicon_pager")
        fun selected(tab: String) {
            settleEdits()
            compose.onNodeWithTag("user_lexicon_tab_$tab").assertIsSelected()
            compose.onNodeWithTag(if (tab == "chinese") "user_dict_search" else "user_lexicon_search")
                .assertIsDisplayed()
        }
        pager.performTouchInput { swipeRight() }
        selected("chinese")
        pager.performTouchInput { swipeLeft() }
        selected("english")
        pager.performTouchInput { swipeLeft() }
        selected("email")
        pager.performTouchInput { swipeLeft() }
        selected("email")
        pager.performTouchInput { swipeRight() }
        selected("english")
        pager.performTouchInput { swipeRight() }
        selected("chinese")
        compose.onNodeWithTag("user_lexicon_tab_email").performClick()
        selected("email")
        pager.performTouchInput { swipeRight() }
        selected("english")
    }

    private fun assertAlignedPages() {
        chineseDb.writeText("aegis-userdb 1\nR\tceshi\t测试\n")
        lexicon.add(UserLexicon.Kind.ENGLISH, "AegisWord")
        open()
        val search = compose.onNodeWithTag("user_dict_search").getUnclippedBoundsInRoot()
        val overview = compose.onNodeWithTag("user_dict_overview").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("user_dict_list_surface").getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag("user_dict_open_add").getUnclippedBoundsInRoot()
        for (tab in listOf("english", "email", "chinese")) {
            compose.onNodeWithTag("user_lexicon_tab_$tab").performClick()
            settleEdits()
            val prefix = if (tab == "chinese") "user_dict" else "user_lexicon"
            val actualSearch = compose.onNodeWithTag("${prefix}_search").getUnclippedBoundsInRoot()
            val actualOverview = compose.onNodeWithTag("${prefix}_overview").getUnclippedBoundsInRoot()
            val actualList = compose.onNodeWithTag("${prefix}_list_surface").getUnclippedBoundsInRoot()
            val actualAction = compose.onNodeWithTag("${prefix}_open_add").getUnclippedBoundsInRoot()
            assertEquals("$tab search top", search.top.value, actualSearch.top.value, 0.5f)
            assertEquals("$tab search bottom", search.bottom.value, actualSearch.bottom.value, 0.5f)
            assertEquals("$tab overview top", overview.top.value, actualOverview.top.value, 0.5f)
            assertEquals("$tab overview bottom", overview.bottom.value, actualOverview.bottom.value, 0.5f)
            assertEquals("$tab list top", list.top.value, actualList.top.value, 0.5f)
            assertEquals("$tab list bottom", list.bottom.value, actualList.bottom.value, 0.5f)
            assertEquals("$tab actions top", action.top.value, actualAction.top.value, 0.5f)
            assertEquals("$tab actions bottom", action.bottom.value, actualAction.bottom.value, 0.5f)
        }
    }

    @Test fun english_pages_keep_overviews_actions_and_lists_aligned() = assertAlignedPages()

    @Config(qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    @Test fun chinese_pages_keep_overviews_actions_and_lists_aligned() = assertAlignedPages()

    @Test fun partial_drag_keeps_adjacent_cards_aligned_and_separated() {
        open()
        val pager = compose.onNodeWithTag("user_lexicon_pager")
        compose.mainClock.autoAdvance = false
        try {
            pager.performTouchInput {
                down(Offset(width * 0.8f, height * 0.65f))
                moveTo(Offset(width * 0.35f, height * 0.65f), delayMillis = 240)
            }
            compose.mainClock.advanceTimeBy(32)
            val chinese = compose.onNodeWithTag("user_dict_overview", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val english = compose.onNode(
                hasTestTag("user_lexicon_overview") and hasAnyAncestor(hasTestTag("user_lexicon_page_english")),
                useUnmergedTree = true,
            ).getUnclippedBoundsInRoot()
            assertEquals("drag keeps card tops aligned", chinese.top.value, english.top.value, 0.5f)
            assertEquals("drag keeps card bottoms aligned", chinese.bottom.value, english.bottom.value, 0.5f)
            assertEquals("adjacent pages keep a visible gap", 20f, english.left.value - chinese.right.value, 0.5f)
        } finally {
            pager.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    @Test fun english_word_addition_needs_no_pinyin_and_preserves_case() {
        open("english")
        compose.onNodeWithTag("user_lexicon_empty").assertExists()
        compose.onNodeWithTag("user_lexicon_search").assertIsNotFocused()
        compose.onNodeWithTag("user_lexicon_open_add").performClick()
        compose.onNodeWithTag("user_dict_new_reading").assertDoesNotExist()
        compose.onNodeWithTag("user_lexicon_new_value").assertIsFocused().performTextInput("AegisWord")
        compose.onNodeWithTag("user_lexicon_add").performClick()
        settleEdits()

        assertEquals(listOf("AegisWord"), lexicon.entries(UserLexicon.Kind.ENGLISH))
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, lexicon.entries(UserLexicon.Kind.EMAIL))
        compose.onNodeWithText("AegisWord").assertExists()
        compose.onNodeWithTag("user_lexicon_add_sheet").assertDoesNotExist()
        compose.onNodeWithTag("user_lexicon_open_more").assertExists()
        compose.onNodeWithTag("user_lexicon_reset_defaults").assertDoesNotExist()
    }

    @Test fun english_duplicate_shows_feedback_without_a_second_entry() {
        lexicon.add(UserLexicon.Kind.ENGLISH, "AegisWord")
        open("english")
        add("aegisword")

        assertEquals(listOf("AegisWord"), lexicon.entries(UserLexicon.Kind.ENGLISH))
        compose.onNodeWithTag("user_lexicon_add_sheet").assertExists()
        compose.onNodeWithText(context.getString(R.string.user_lexicon_exists)).assertExists()
    }

    @Test fun email_suffix_accepts_leading_at_and_normalizes_case() {
        open("email")
        compose.onNodeWithTag("user_lexicon_search").assertIsNotFocused()
        add("@Mail.Example.com")

        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS + "mail.example.com", lexicon.entries(UserLexicon.Kind.EMAIL))
        assertTrue(lexicon.entries(UserLexicon.Kind.ENGLISH).isEmpty())
        showEntry("mail.example.com")
    }

    @Test fun complete_email_is_rejected_and_built_in_suffix_is_not_saved_as_custom() {
        open("email")
        add("person@example.com")
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, lexicon.entries(UserLexicon.Kind.EMAIL))
        compose.onNodeWithText(context.getString(R.string.user_lexicon_invalid_email)).assertExists()

        compose.onNodeWithTag("user_lexicon_new_value").performTextClearance()
        compose.onNodeWithTag("user_lexicon_new_value").performTextInput("@gmail.com")
        compose.onNodeWithTag("user_lexicon_add").performClick()
        settleEdits()
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, lexicon.entries(UserLexicon.Kind.EMAIL))
        assertFalse(prefs.contains(UserLexicon.PREF_EMAIL_DOMAINS))
        compose.onNodeWithText(context.getString(R.string.user_lexicon_exists)).assertExists()
    }

    @Test fun delete_requires_confirmation_and_keeps_other_types() {
        val chinese = "aegis-userdb 1\nR\tceshi\t测试\n"
        chineseDb.writeText(chinese)
        lexicon.add(UserLexicon.Kind.ENGLISH, "AegisWord")
        lexicon.add(UserLexicon.Kind.EMAIL, "mail.example.com")
        open("email")

        requestDelete("mail.example.com")
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS + "mail.example.com", lexicon.entries(UserLexicon.Kind.EMAIL))
        compose.onNodeWithTag("user_lexicon_delete_cancel").performClick()
        compose.onNodeWithText("mail.example.com").assertExists()
        requestDelete("mail.example.com")
        compose.onNodeWithTag("user_lexicon_delete_confirm").performClick()
        settleEdits()

        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, lexicon.entries(UserLexicon.Kind.EMAIL))
        assertEquals(listOf("AegisWord"), lexicon.entries(UserLexicon.Kind.ENGLISH))
        assertEquals(chinese, chineseDb.readText())
        compose.onNodeWithTag("user_lexicon_empty").assertDoesNotExist()
        showEntry("qq.com")
        compose.onNodeWithTag("user_lexicon_tab_english").performClick()
        compose.onNodeWithText("AegisWord").assertExists()
    }

    @Test fun search_is_case_insensitive_and_each_tab_uses_its_own_list() {
        lexicon.add(UserLexicon.Kind.ENGLISH, "AegisWord")
        lexicon.add(UserLexicon.Kind.ENGLISH, "AnotherWord")
        lexicon.add(UserLexicon.Kind.EMAIL, "mail.example.com")
        open("english")

        compose.onNodeWithTag("user_lexicon_search").performTextInput("AEGIS")
        compose.onNodeWithText("AegisWord").assertExists()
        compose.onNodeWithText("AnotherWord").assertDoesNotExist()
        compose.onNodeWithText("mail.example.com").assertDoesNotExist()
        compose.onNodeWithTag("user_lexicon_tab_email").performClick()
        showEntry("mail.example.com")
        compose.onNodeWithText("AegisWord").assertDoesNotExist()
        compose.onNodeWithTag("user_lexicon_search").performTextInput("@MAIL")
        compose.onNodeWithText("mail.example.com").assertExists()
        compose.onNodeWithTag("user_lexicon_search").performTextInput("missing")
        compose.onNodeWithText(context.getString(R.string.user_lexicon_no_match)).assertExists()
    }

    @Test fun all_ten_default_email_suffixes_are_visible_and_searchable_without_custom_entries() {
        open("email")
        compose.onNodeWithTag("user_lexicon_tab_email").assertIsSelected()
        compose.onNodeWithTag("user_lexicon_empty").assertDoesNotExist()
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, lexicon.entries(UserLexicon.Kind.EMAIL))
        for (domain in UserLexicon.COMMON_EMAIL_DOMAINS) showEntry(domain)
        compose.onNodeWithTag("user_lexicon_search").performTextInput("@GMAIL")
        compose.onNodeWithText("gmail.com").assertIsDisplayed()
        compose.onNodeWithText("qq.com").assertDoesNotExist()
        compose.onNodeWithTag("user_lexicon_search").performTextClearance()
        showEntry("qq.com")
        assertFalse(prefs.contains(UserLexicon.PREF_EMAIL_DOMAINS))
        assertFalse(prefs.contains(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS))
    }

    @Test fun a_deleted_default_stays_disabled_after_recreation_and_can_be_added_again() {
        EmailDomains(prefs).record("gmail.com")
        open("email")
        requestDelete("gmail.com")
        assertTrue("opening confirmation must keep the default enabled", "gmail.com" in lexicon.entries(UserLexicon.Kind.EMAIL))
        compose.onNodeWithTag("user_lexicon_delete_confirm").performClick()
        settleEdits()
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS - "gmail.com", lexicon.entries(UserLexicon.Kind.EMAIL))
        assertEquals(setOf("gmail.com"), prefs.getStringSet(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS, emptySet()))
        assertFalse(prefs.contains(UserLexicon.EMAIL_COUNT_PREFIX + "gmail.com"))

        scenario!!.recreate()
        settleEdits()
        compose.onNodeWithTag("user_lexicon_tab_email").assertIsSelected()
        compose.onNodeWithTag("user_lexicon_search").performTextInput("gmail")
        compose.onNodeWithText(context.getString(R.string.user_lexicon_no_match)).assertExists()
        assertFalse(EmailDomains(prefs).contains("gmail.com"))
        compose.onNodeWithTag("user_lexicon_search").performTextClearance()
        add("@GMAIL.COM")
        compose.onNodeWithTag("user_lexicon_add_sheet").assertDoesNotExist()
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, lexicon.entries(UserLexicon.Kind.EMAIL))
        assertEquals(emptySet<String>(), prefs.getStringSet(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS, emptySet()))
        assertFalse("re-adding a default must not turn it into a custom entry", "gmail.com" in prefs.getStringSet(UserLexicon.PREF_EMAIL_DOMAINS, emptySet()).orEmpty())
        showEntry("gmail.com")
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, EmailDomains(prefs).suggestions())
    }

    @Test fun email_reset_tools_and_confirmation_survive_recreation() {
        lexicon.add(UserLexicon.Kind.EMAIL, "private.example")
        open("email")
        val before = prefs.all.toMap()
        compose.onNodeWithTag("user_lexicon_open_more").performClick()
        scenario!!.recreate()
        settleEdits()
        compose.onNodeWithTag("user_lexicon_reset_defaults").performClick()
        scenario!!.recreate()
        settleEdits()
        compose.onNodeWithText(context.getString(R.string.user_lexicon_reset_body)).assertExists()
        compose.onNodeWithTag("user_lexicon_reset_cancel").performClick()
        assertEquals(before, prefs.all)
        showEntry("private.example")
    }

    @Test fun restoring_defaults_requires_confirmation_and_clears_only_email_data() {
        lexicon.add(UserLexicon.Kind.ENGLISH, "AegisWord")
        lexicon.add(UserLexicon.Kind.EMAIL, "private.example")
        lexicon.remove(UserLexicon.Kind.EMAIL, "qq.com")
        val domains = EmailDomains(prefs)
        domains.record("private.example")
        repeat(3) { domains.record("gmail.com") }
        open("email")
        compose.onNodeWithTag("user_lexicon_search").performTextInput("private")
        val before = prefs.all.toMap()
        compose.onNodeWithTag("user_lexicon_open_more").performClick()
        compose.onNodeWithTag("user_lexicon_reset_defaults").performClick()
        compose.onNodeWithText(context.getString(R.string.user_lexicon_reset_body)).assertExists()
        assertEquals("opening confirmation must not reset data", before, prefs.all)
        compose.onNodeWithTag("user_lexicon_reset_cancel").performClick()
        compose.onNodeWithTag("user_lexicon_reset_confirm").assertDoesNotExist()
        assertEquals("cancelling must preserve entries and counts", before, prefs.all)
        compose.onNodeWithText("private.example").assertIsDisplayed()

        compose.onNodeWithTag("user_lexicon_open_more").performClick()
        compose.onNodeWithTag("user_lexicon_reset_defaults").performClick()
        compose.onNodeWithTag("user_lexicon_reset_confirm").performClick()
        settleEdits()
        compose.onNodeWithTag("user_lexicon_reset_confirm").assertDoesNotExist()
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, lexicon.entries(UserLexicon.Kind.EMAIL))
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, domains.suggestions())
        assertEquals(emptySet<String>(), prefs.getStringSet(UserLexicon.PREF_EMAIL_DOMAINS, null))
        assertEquals(emptySet<String>(), prefs.getStringSet(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS, null))
        assertFalse(prefs.all.keys.any { it.startsWith(UserLexicon.EMAIL_COUNT_PREFIX) })
        showEntry("qq.com")
        compose.onNodeWithText("private.example").assertDoesNotExist()
        scenario!!.recreate()
        settleEdits()
        showEntry("qq.com")
        compose.onNodeWithTag("user_lexicon_tab_english").performClick()
        compose.onNodeWithText("AegisWord").assertExists()
        compose.onNodeWithTag("user_lexicon_reset_defaults").assertDoesNotExist()
        assertEquals(listOf("AegisWord"), lexicon.entries(UserLexicon.Kind.ENGLISH))
    }

    @Test fun preferences_replacement_refreshes_visible_entries_and_survives_resume_and_recreation() {
        lexicon.add(UserLexicon.Kind.ENGLISH, "BeforeRestore")
        open("english")
        compose.onNodeWithText("BeforeRestore").assertExists()

        prefs.edit().putStringSet(UserLexicon.PREF_ENGLISH_WORDS, setOf("AfterRestore")).commit()
        settleEdits()
        compose.onNodeWithText("BeforeRestore").assertDoesNotExist()
        compose.onNodeWithText("AfterRestore").assertExists()

        scenario!!.moveToState(Lifecycle.State.CREATED)
        prefs.edit().putStringSet(UserLexicon.PREF_ENGLISH_WORDS, setOf("AfterResume")).commit()
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        settleEdits()
        compose.onNodeWithText("AfterResume").assertExists()
        scenario!!.recreate()
        settleEdits()
        compose.onNodeWithTag("user_lexicon_tab_english").assertIsSelected()
        compose.onNodeWithText("AfterResume").assertExists()
    }

}
