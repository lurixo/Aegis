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
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.aegis.ime.R
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearnEdit
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import com.aegis.ime.user.UserStoreEdits
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private fun ctxString(id: Int) = RuntimeEnvironment.getApplication().getString(id)

private fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.assertSetupStepActionLayout(
    expectCenteredInset: Boolean = false,
) {
    onNodeWithText(ctxString(R.string.setup_steps_title)).performScrollTo().assertExists()

    val pageHorizontalPadding = 20f
    val cardContentPadding = 16f
    val buttonHorizontalContentPadding = 24f
    val rootBounds = onRoot().getUnclippedBoundsInRoot()
    val cardBounds = onNodeWithTag("setup_steps_card").getUnclippedBoundsInRoot()
    val actionsBounds = onNodeWithTag("setup_step_actions").getUnclippedBoundsInRoot()
    val enableButtonBounds = onNodeWithTag("setup_enable_action")
        .getUnclippedBoundsInRoot()
    val switchButtonBounds = onNodeWithTag("setup_switch_action")
        .getUnclippedBoundsInRoot()
    val enableLabelBounds = onNodeWithTag("setup_enable_label_block", useUnmergedTree = true)
        .getUnclippedBoundsInRoot()
    val switchLabelBounds = onNodeWithTag("setup_switch_label_block", useUnmergedTree = true)
        .getUnclippedBoundsInRoot()

    assertEquals(
        "setup steps card should fill the page content width",
        rootBounds.left.value + pageHorizontalPadding,
        cardBounds.left.value,
        1f,
    )
    assertEquals(
        "setup steps card should fill the page content width",
        rootBounds.right.value - pageHorizontalPadding,
        cardBounds.right.value,
        1f,
    )
    assertEquals(
        "setup actions should fill the padded card width",
        cardBounds.left.value + cardContentPadding,
        actionsBounds.left.value,
        1f,
    )
    assertEquals(
        "setup actions should fill the padded card width",
        cardBounds.right.value - cardContentPadding,
        actionsBounds.right.value,
        1f,
    )
    assertEquals(
        "enable setup button should fill the action width",
        actionsBounds.left.value,
        enableButtonBounds.left.value,
        0.5f,
    )
    assertEquals(
        "enable setup button should fill the action width",
        actionsBounds.right.value,
        enableButtonBounds.right.value,
        0.5f,
    )
    assertEquals(
        "switch setup button should fill the action width",
        actionsBounds.left.value,
        switchButtonBounds.left.value,
        0.5f,
    )
    assertEquals(
        "switch setup button should fill the action width",
        actionsBounds.right.value,
        switchButtonBounds.right.value,
        0.5f,
    )

    assertEquals(
        "setup label blocks should share the same left edge",
        enableLabelBounds.left.value,
        switchLabelBounds.left.value,
        0.5f,
    )
    assertEquals(
        "setup label blocks should share the same width",
        enableLabelBounds.right.value - enableLabelBounds.left.value,
        switchLabelBounds.right.value - switchLabelBounds.left.value,
        0.5f,
    )
    assertEquals(
        "enable setup label block should be centered in the button",
        (enableButtonBounds.left.value + enableButtonBounds.right.value) / 2f,
        (enableLabelBounds.left.value + enableLabelBounds.right.value) / 2f,
        1f,
    )
    assertEquals(
        "switch setup label block should be centered in the button",
        (switchButtonBounds.left.value + switchButtonBounds.right.value) / 2f,
        (switchLabelBounds.left.value + switchLabelBounds.right.value) / 2f,
        1f,
    )
    if (expectCenteredInset) {
        assertTrue(
            "setup label block should be centered beyond the button's horizontal content padding",
            enableLabelBounds.left.value > enableButtonBounds.left.value + buttonHorizontalContentPadding,
        )
        assertTrue(
            "setup label block should be centered beyond the button's horizontal content padding",
            enableLabelBounds.right.value < enableButtonBounds.right.value - buttonHorizontalContentPadding,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class InputSettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<InputSettingsActivity>()

    @Test fun holds_keyboard_mode_fuzzy_and_associations_and_back_finishes() {
        compose.onNodeWithText(ctxString(R.string.layout_card_title)).assertExists()
        compose.onNodeWithText(ctxString(R.string.fuzzy_card_title)).performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.association_title)).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }

    @Test fun back_affordance_is_the_drawn_chevron_not_a_thin_text_glyph() {
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).assertExists()
        compose.onAllNodesWithText("‹").assertCountEquals(0)
        val target = compose.onNodeWithTag("app_back_button").getUnclippedBoundsInRoot()
        val iconBox = compose.onNodeWithTag("app_back_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("app_page_title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("back target keeps the minimum width", (target.right - target.left).value >= 47.5f)
        assertTrue("back target keeps the minimum height", (target.bottom - target.top).value >= 47.5f)
        assertTrue(
            "back target carries the page title",
            title.left.value >= target.left.value && title.right.value <= target.right.value &&
                title.top.value >= target.top.value && title.bottom.value <= target.bottom.value,
        )
        assertTrue(
            "back target carries the chevron",
            iconBox.left.value >= target.left.value && iconBox.right.value <= title.left.value,
        )
        assertEquals("back icon box width", 24f, (iconBox.right - iconBox.left).value, 0.5f)
        assertEquals("back icon box height", 24f, (iconBox.bottom - iconBox.top).value, 0.5f)
    }

    @Test fun the_auto_learning_switch_starts_on_and_writes_the_preference_both_ways() {
        val prefs = compose.activity.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        compose.onNodeWithText(ctxString(R.string.auto_learn_title)).performScrollTo().assertExists()
        compose.onNodeWithTag("auto_learn_switch").performScrollTo().assertIsOn()

        compose.onNodeWithTag("auto_learn_switch").performClick()
        compose.waitForIdle()
        assertFalse("turning it off is written down", prefs.getBoolean(PREF_AUTO_LEARN_ON, AUTO_LEARN_DEFAULT_ON))
        compose.onNodeWithTag("auto_learn_switch").assertIsOff()

        compose.onNodeWithTag("auto_learn_switch").performClick()
        compose.waitForIdle()
        assertTrue("turning it back on is written down", prefs.getBoolean(PREF_AUTO_LEARN_ON, false))
    }

    @Test fun the_clear_button_is_dead_while_there_is_nothing_learned() {
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsNotEnabled()
        val zero = RuntimeEnvironment.getApplication().getString(R.string.user_dict_auto_count_format, 0)
        compose.onNodeWithText(zero).performScrollTo().assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class InputSettingsAutoLearnClearTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()
    private val learn = java.io.File(ctx.filesDir, "userlearn.txt")
    private var scenario: ActivityScenario<InputSettingsActivity>? = null
    private val hosts = ArrayList<LiveUserDictHost>()

    private fun liveHost(
        model: UserModel,
        userDb: java.io.File,
        userLearning: UserLearning? = null,
        userLearnFile: java.io.File? = null,
        onSaved: (Long?, Long?) -> Unit = { _, _ -> },
    ): LiveUserDictHost =
        LiveUserDictHost(model, userDb, userLearning, userLearnFile, onSaved).also { hosts += it }

    @Before fun reset() {
        AegisToast.reset()
        UserDictHot.host = null
        learn.delete()
    }

    @After fun cleanup() {
        drainEdits()
        scenario?.close()
        UserDictHot.host = null
        hosts.forEach { runCatching { it.stopSaving() } }
        learn.delete()
    }

    private fun drainEdits() {
        val lane = UserStoreEdits::class.java.getDeclaredField("lane").run {
            isAccessible = true
            get(UserStoreEdits) as ExecutorService
        }
        lane.submit { }.get(10, TimeUnit.SECONDS)
    }

    private fun reported(): String? {
        settled("the page reported what became of the edit") { AegisToast.textForTest() != null }
        return AegisToast.textForTest()
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

    @Test fun the_clear_button_lives_while_only_the_next_word_data_is_left() {
        learn.writeText("aegis-userlearn 1\nC\t\u4f60\t\u597d\t3.0\t1700000000000\n")
        assertTrue("there is no glued word to count", UserLearnEdit.list(learn).isEmpty())

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ctxString(R.string.user_dict_auto_clear_confirm)).performClick()
        assertEquals(ctxString(R.string.user_dict_toast_auto_cleared), reported())

        assertTrue("the next word data is gone", learn.readLines().none { it.startsWith("C\t") })
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsNotEnabled()
    }

    @Test fun a_count_of_zero_over_a_live_clear_button_says_what_the_button_would_clear() {
        learn.writeText("aegis-userlearn 1\nC\t你\t好\t3.0\t1700000000000\n")
        assertTrue("there is no glued word to count", UserLearnEdit.list(learn).isEmpty())

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)

        compose.onNodeWithText(ctxString(R.string.user_dict_auto_count_format).format(0))
            .performScrollTo()
            .assertExists()
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("auto_learn_pairs_only").performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.user_dict_auto_pairs_only)).performScrollTo().assertExists()
    }

    @Test fun a_dead_clear_button_is_left_to_speak_for_itself() {
        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)

        compose.onNodeWithText(ctxString(R.string.user_dict_auto_count_format).format(0))
            .performScrollTo()
            .assertExists()
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("auto_learn_pairs_only").assertDoesNotExist()
    }

    @Test fun learned_data_that_cannot_be_read_says_so_instead_of_bringing_the_page_down() {
        learn.writeText("not a learning file at all\n")

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)
        compose.onNodeWithTag("auto_learn_unreadable").performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.user_learn_unreadable)).performScrollTo().assertExists()
        compose.onAllNodesWithText(ctxString(R.string.user_learn_unreadable_kept)).assertCountEquals(0)
        compose.onNodeWithTag("auto_learn_count").assertDoesNotExist()
        assertTrue("the unreadable file is left as it was", learn.readText().startsWith("not a learning file"))

        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ctxString(R.string.user_dict_auto_clear_confirm)).performClick()
        assertEquals(ctxString(R.string.user_dict_toast_auto_cleared), reported())

        assertTrue(
            "discarding it on purpose is the way back to a store that saves again",
            learn.readText().startsWith("aegis-userlearn"),
        )
        compose.onNodeWithTag("auto_learn_count").performScrollTo().assertExists()
    }

    @Test fun a_live_keyboard_that_still_holds_the_learned_words_does_not_say_they_are_not_shown() {
        val learning = UserLearning()
        learn.writeText("aegis-userlearn 1\nF\tninen\t你呢嗯\t4.0\t1700000000000\n")
        learning.load(learn)
        assertTrue("precondition: the word was learned", learning.formedEntries().isNotEmpty())
        learn.writeText("aegis-userlearn 1\nF\tninen\t你呢嗯\t4.0\t1700000000000\nF\tzh")
        learning.load(learn)
        assertFalse("precondition: the store can no longer be read", learning.readable)
        assertTrue("precondition: the keyboard still holds it", learning.formedEntries().isNotEmpty())
        UserDictHot.host = liveHost(UserModel(), java.io.File(ctx.filesDir, "userdb.txt"), learning, learn)

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)

        compose.onNodeWithText(ctxString(R.string.user_learn_unreadable_kept)).performScrollTo().assertExists()
        compose.onAllNodesWithText(ctxString(R.string.user_learn_unreadable)).assertCountEquals(0)
    }

    @Test fun a_live_keyboard_holding_an_unreadable_learning_store_still_says_so_and_offers_the_way_out() {
        val learning = UserLearning()
        learn.writeText("not a learning file at all\n")
        learning.load(learn)
        assertFalse("precondition: the live store knows it could not be read", learning.readable)
        UserDictHot.host = liveHost(UserModel(), java.io.File(ctx.filesDir, "userdb.txt"), learning, learn)

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)

        compose.onNodeWithTag("auto_learn_unreadable").performScrollTo().assertExists()
        compose.onNodeWithTag("auto_learn_count").assertDoesNotExist()
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ctxString(R.string.user_dict_auto_clear_confirm)).performClick()
        assertEquals(ctxString(R.string.user_dict_toast_auto_cleared), reported())

        assertTrue(
            "clearing on purpose is the way back to a store that saves again",
            learn.readText().startsWith("aegis-userlearn"),
        )
    }

    private fun learnedRows(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(separator = "", prefix = "aegis-userlearn 1\n") { (reading, word) ->
            "F\t$reading\t$word\t3.0\t1700000000000\n"
        }

    @Test fun more_than_one_glued_word_is_counted_as_many_as_there_are() {
        learn.writeText(learnedRows("ninen" to "你呢嗯", "nihao" to "你好", "zaijian" to "再见"))

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)

        compose.onNodeWithTag("auto_learn_count").performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.user_dict_auto_count_format).format(3))
            .performScrollTo()
            .assertExists()
    }

    @Test fun the_count_catches_up_with_what_was_learned_while_the_page_was_away() {
        learn.writeText(learnedRows("ninen" to "你呢嗯"))

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)
        val one = ctxString(R.string.user_dict_auto_count_format).format(1)
        compose.onNodeWithText(one).performScrollTo().assertExists()

        learn.writeText(learnedRows("ninen" to "你呢嗯", "nihao" to "你好", "zaijian" to "再见"))
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)

        val three = ctxString(R.string.user_dict_auto_count_format).format(3)
        settled("the page counted the words learned while it was away") {
            drainEdits()
            compose.onAllNodesWithText(three).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(three).performScrollTo().assertExists()
        compose.onAllNodesWithText(one).assertCountEquals(0)
    }

    private class HoldingHost(
        private val gate: java.util.concurrent.CountDownLatch,
        private val reached: java.util.concurrent.atomic.AtomicBoolean,
    ) : UserDictHot.Host {
        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String) = false
        override fun importUserDict(importFile: java.io.File, merge: Boolean, now: Long) = false
        override fun reloadDictionary() = false
        override fun entries(): List<UserModel.Entry> = emptyList()
        override fun learnedEntries(): List<UserLearning.Formed> =
            if (reached.get()) emptyList() else listOf(UserLearning.Formed("你呢嗯", "ninen"))
        override fun hasLearnedData() = !reached.get()
        override fun removeLearned(word: String, reading: String) = false
        override fun clearLearned(): Boolean {
            gate.await(10, TimeUnit.SECONDS)
            reached.set(true)
            return true
        }
        override fun flush() = false
    }

    @Test fun a_clear_hands_the_store_over_instead_of_making_the_card_wait_for_it() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val reached = java.util.concurrent.atomic.AtomicBoolean(false)
        UserDictHot.host = HoldingHost(gate, reached)

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)
        AegisToast.reset()
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ctxString(R.string.user_dict_auto_clear_confirm)).performClick()

        assertFalse("the tap must come back before the store has been touched", reached.get())
        assertEquals("and nothing may be reported before there is anything to report", null, AegisToast.textForTest())

        gate.countDown()

        assertEquals(ctxString(R.string.user_dict_toast_auto_cleared), reported())
        assertTrue("the store really was cleared once the lane got to it", reached.get())
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsNotEnabled()
    }

    @Test fun a_clear_that_never_reached_storage_says_so_instead_of_claiming_success() {
        UserDictHot.host = object : UserDictHot.Host {
            override fun addWord(reading: String, word: String, now: Long) = false
            override fun removeWord(reading: String, word: String) = false
            override fun importUserDict(importFile: java.io.File, merge: Boolean, now: Long) = false
            override fun reloadDictionary() = false
            override fun entries(): List<UserModel.Entry> = emptyList()
            override fun learnedEntries(): List<UserLearning.Formed> =
                listOf(UserLearning.Formed("你呢嗯", "ninen"))
            override fun hasLearnedData() = true
            override fun removeLearned(word: String, reading: String) = false
            override fun clearLearned() = false
            override fun flush() = false
        }

        scenario = ActivityScenario.launch(InputSettingsActivity::class.java)
        AegisToast.reset()
        compose.onNodeWithTag("auto_learn_clear").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ctxString(R.string.user_dict_auto_clear_confirm)).performClick()
        settled("the refused clear is reported") { AegisToast.textForTest() != null }

        assertEquals(
            ctxString(R.string.user_dict_toast_write_failed),
            AegisToast.textForTest(),
        )
    }
}


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DictSettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<DictSettingsActivity>()

    @Test fun holds_the_dict_pack_and_model_cards_and_back_finishes() {
        compose.onNodeWithText(ctxString(R.string.dict_card_title)).assertExists()
        compose.onNodeWithText(ctxString(R.string.gram_card_title)).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }

    @Test fun source_links_stay_in_the_activity_task_and_have_no_local_single_click_gate() {
        val links = listOf(
            R.string.dict_source_link to ModelDownload.DICT_REPO_URL,
            R.string.gram_source_link to ModelDownload.REPO_URL,
        )
        for ((labelRes, url) in links) {
            repeat(2) { attempt ->
                compose.onNodeWithText(ctxString(labelRes)).performScrollTo().performClick()
                compose.waitForIdle()
                val started = shadowOf(compose.activity).nextStartedActivity
                assertEquals("$url attempt $attempt uses ACTION_VIEW", Intent.ACTION_VIEW, started?.action)
                assertEquals("$url attempt $attempt keeps its URL", url, started?.dataString)
                assertEquals(
                    "$url attempt $attempt must not start a new task",
                    0,
                    requireNotNull(started).flags and Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UserDictActivityTest {
    @get:Rule val compose = createAndroidComposeRule<UserDictActivity>()

    @Test fun holds_the_search_field_and_tools_and_back_finishes() {
        compose.onNodeWithTag("user_dict_search").assertExists()
        compose.onNodeWithTag("user_dict_open_more").assertExists()
        compose.onNodeWithText(ctxString(R.string.user_dict_export_button)).assertDoesNotExist()
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AboutActivityTest {
    @get:Rule val compose = createAndroidComposeRule<AboutActivity>()

    @Test fun holds_version_enable_steps_and_the_try_field() {
        compose.onNodeWithText(ctxString(R.string.app_version_card_title)).assertExists()
        compose.onNodeWithText(ctxString(R.string.setup_steps_title)).performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.setup_try_field_label)).performScrollTo().assertExists()
        compose.onNodeWithText(ctxString(R.string.settings_backup_title)).assertDoesNotExist()
    }

    @Test fun setup_step_actions_fill_card_width_and_labels_use_centered_shared_block() =
        compose.assertSetupStepActionLayout()

    @Test fun opening_licenses_starts_the_licenses_activity() {
        compose.onNodeWithText(ctxString(R.string.settings_about_licenses_title)).performScrollTo().performClick()
        compose.waitForIdle()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(LicensesActivity::class.java.name, started?.component?.className)
    }

    @Test fun back_arrow_finishes_the_activity() {
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.isFinishing)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
class AboutActivityChineseTest {
    @get:Rule val compose = createAndroidComposeRule<AboutActivity>()

    @Test fun setup_step_actions_align_the_chinese_labels_in_a_centered_shared_block() =
        compose.assertSetupStepActionLayout(expectCenteredInset = true)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LicensesActivityTest {
    @get:Rule val compose = createAndroidComposeRule<LicensesActivity>()

    @Test fun lists_components_and_back_finishes() {
        compose.onNodeWithText(ctxString(R.string.license_wanxiang_name)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ctxString(R.string.license_androidx_name)).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(ctxString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }
}
