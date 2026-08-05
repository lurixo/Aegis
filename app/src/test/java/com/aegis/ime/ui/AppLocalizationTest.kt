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

import com.aegis.ime.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLocalizationTest {

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chinese_locale_uses_chinese_app_ui_strings() {
        val ctx = RuntimeEnvironment.getApplication()

        assertEquals("Aegis 输入法", ctx.getString(R.string.setup_title))
        assertEquals("Aegis 输入法", ctx.getString(R.string.ime_label))
        assertEquals("学习词库", ctx.getString(R.string.user_dict_title))
        assertEquals("增强模型（万象离线大模型）", ctx.getString(R.string.gram_card_title))
        assertEquals("检测模型更新", ctx.getString(R.string.check_model_update_button))
        assertEquals("检测词库更新", ctx.getString(R.string.check_dict_update_button))
        assertEquals("应用版本", ctx.getString(R.string.app_version_card_title))
        assertEquals("批量管理剪贴板", ctx.getString(R.string.clip_edit_clipboard))
        assertEquals("批量管理常用语", ctx.getString(R.string.clip_edit_phrases))
        assertEquals("已选择 0 项", ctx.getString(R.string.clip_selected_count, 0))
        assertEquals("输入设置", ctx.getString(R.string.settings_group_input_title))
        assertEquals("词库与下载", ctx.getString(R.string.settings_group_dicts_title))
        assertEquals("用户词库", ctx.getString(R.string.settings_group_userdict_title))
        assertEquals("关于与启用", ctx.getString(R.string.settings_group_about_title))
        assertEquals("返回", ctx.getString(R.string.settings_back))
        assertEquals("搜索：词或拼音", ctx.getString(R.string.user_dict_search_hint))
        assertEquals("词数：3", ctx.getString(R.string.user_dict_count_format, 3))
        assertEquals("没有匹配的词。", ctx.getString(R.string.user_dict_search_no_match))
        assertEquals("自动学习", ctx.getString(R.string.user_dict_auto_title))
        assertEquals("自动学习：3", ctx.getString(R.string.user_dict_auto_count_format, 3))
        assertEquals("清空学习数据", ctx.getString(R.string.user_dict_auto_clear_button))
        assertEquals("重输", ctx.getString(R.string.kbd_redo))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun english_locale_uses_english_app_ui_strings() {
        val ctx = RuntimeEnvironment.getApplication()

        assertEquals("Aegis IME", ctx.getString(R.string.setup_title))
        assertEquals("Aegis IME", ctx.getString(R.string.ime_label))
        assertEquals("Learning dictionary", ctx.getString(R.string.user_dict_title))
        assertEquals("Enhancement model (Wanxiang offline model)", ctx.getString(R.string.gram_card_title))
        assertEquals("Check model updates", ctx.getString(R.string.check_model_update_button))
        assertEquals("Check dictionary updates", ctx.getString(R.string.check_dict_update_button))
        assertEquals("App release", ctx.getString(R.string.app_version_card_title))
        assertEquals("Batch clipboard", ctx.getString(R.string.clip_edit_clipboard))
        assertEquals("Batch phrases", ctx.getString(R.string.clip_edit_phrases))
        assertEquals("0 selected", ctx.getString(R.string.clip_selected_count, 0))
        assertEquals("Input settings", ctx.getString(R.string.settings_group_input_title))
        assertEquals("Dictionaries & downloads", ctx.getString(R.string.settings_group_dicts_title))
        assertEquals("User dictionary", ctx.getString(R.string.settings_group_userdict_title))
        assertEquals("About & enable", ctx.getString(R.string.settings_group_about_title))
        assertEquals("Back", ctx.getString(R.string.settings_back))
        assertEquals("Search word or pinyin", ctx.getString(R.string.user_dict_search_hint))
        assertEquals("Words: 3", ctx.getString(R.string.user_dict_count_format, 3))
        assertEquals("No matching words.", ctx.getString(R.string.user_dict_search_no_match))
        assertEquals("Auto learning", ctx.getString(R.string.user_dict_auto_title))
        assertEquals("Auto learned: 3", ctx.getString(R.string.user_dict_auto_count_format, 3))
        assertEquals("Clear learned data", ctx.getString(R.string.user_dict_auto_clear_button))
        assertEquals("Retype", ctx.getString(R.string.kbd_redo))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun third_language_locale_falls_back_to_english_app_ui_strings() {
        val ctx = RuntimeEnvironment.getApplication()

        assertEquals("Aegis IME", ctx.getString(R.string.setup_title))
        assertEquals("Aegis Chinese", ctx.getString(R.string.subtype_zh))
        assertEquals("Learning dictionary", ctx.getString(R.string.user_dict_title))
        assertEquals("Full dictionary pack (14 tables freq >= 1)", ctx.getString(R.string.dict_card_title))
        assertEquals("Check model updates", ctx.getString(R.string.check_model_update_button))
        assertEquals("Check dictionary updates", ctx.getString(R.string.check_dict_update_button))
        assertEquals("App release", ctx.getString(R.string.app_version_card_title))
        assertEquals("Input settings", ctx.getString(R.string.settings_group_input_title))
        assertEquals("User dictionary", ctx.getString(R.string.settings_group_userdict_title))
        assertEquals("Search word or pinyin", ctx.getString(R.string.user_dict_search_hint))
        assertEquals("Words: 3", ctx.getString(R.string.user_dict_count_format, 3))
    }
}
