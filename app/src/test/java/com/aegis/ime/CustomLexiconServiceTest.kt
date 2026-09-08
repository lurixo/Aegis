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

package com.aegis.ime

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.EmailDomains
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.user.UserLexicon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomLexiconServiceTest {
    private class Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val prefs = service.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        val lexicon = UserLexicon(prefs)
        val controller = KeyboardController(service, DictEngine(null, null, null, userLexicon = lexicon),
            emailDomains = EmailDomains(prefs))
        val editor = BaseInputConnection(FrameLayout(service), true)
        init {
            service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
            service.onStartInput(EditorInfo().apply {
                packageName = "com.example.editor"
                fieldId = 101
                inputType = InputType.TYPE_CLASS_TEXT
            }, false)
            for (name in listOf("mInputConnection", "mStartedInputConnection")) {
                service.javaClass.superclass!!.getDeclaredField(name).apply { isAccessible = true; set(service, editor) }
            }
            controller.setEnAssociationsEnabled(true)
            controller.setEmailAssociationsEnabled(true)
        }
        fun notifyChanged(key: String) {
            val listener = service.javaClass.getDeclaredField("userLexiconListener").apply { isAccessible = true }
                .get(service) as SharedPreferences.OnSharedPreferenceChangeListener
            listener.onSharedPreferenceChanged(prefs, key)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun editing_custom_english_words_refreshes_the_current_prefix_and_commits_the_saved_spelling() {
        val f = Fixture()
        f.controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
        "ope".forEach { f.controller.onKey(Key(it.toString(), output = it.toString())) }
        assertEquals(listOf("ope"), f.controller.candidateWords())
        f.lexicon.add(UserLexicon.Kind.ENGLISH, "OpenAegis")
        f.notifyChanged(UserLexicon.PREF_ENGLISH_WORDS)
        assertEquals(listOf("ope", "OpenAegis"), f.controller.candidateWords())
        f.lexicon.remove(UserLexicon.Kind.ENGLISH, "OpenAegis")
        f.notifyChanged(UserLexicon.PREF_ENGLISH_WORDS)
        assertEquals(listOf("ope"), f.controller.candidateWords())
        f.lexicon.add(UserLexicon.Kind.ENGLISH, "OpenAegis")
        f.notifyChanged(UserLexicon.PREF_ENGLISH_WORDS)
        f.controller.onPickCandidate(1)
        assertEquals("OpenAegis", f.editor.editable.toString())
    }

    @Test fun custom_email_suffixes_refresh_and_commit_in_both_chinese_layouts_and_english() {
        for (layout in listOf("alpha", "nine", "english")) {
            val f = Fixture()
            f.controller.switchTextLayoutForTest(layout == "nine")
            if (layout == "english") f.controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
            f.editor.commitText("name@", 1)
            f.controller.onEditorContextChanged()
            f.lexicon.add(UserLexicon.Kind.EMAIL, "example.org")
            f.notifyChanged(UserLexicon.PREF_EMAIL_DOMAINS)
            assertTrue(layout, "example.org" in f.controller.candidateWords())
            f.controller.onPickCandidate(f.controller.candidateWords().indexOf("example.org"))
            assertEquals(layout, "name@example.org", f.editor.editable.toString())
            assertEquals("example.org", EmailDomains(f.prefs).suggestions().first())
        }
    }

    @Test fun a_removed_suffix_cannot_be_picked_from_a_stale_candidate_list() {
        val f = Fixture()
        f.lexicon.add(UserLexicon.Kind.EMAIL, "example.org")
        f.editor.commitText("name@", 1)
        f.controller.onEditorContextChanged()
        val index = f.controller.candidateWords().indexOf("example.org")
        assertTrue(index >= 0)
        f.lexicon.remove(UserLexicon.Kind.EMAIL, "example.org")
        f.controller.onPickCandidate(index)
        assertEquals("name@", f.editor.editable.toString())
        assertFalse(f.prefs.contains(UserLexicon.EMAIL_COUNT_PREFIX + "example.org"))
    }

    @Test fun default_email_deletion_refreshes_the_current_candidates_and_reset_restores_them() {
        val f = Fixture()
        f.editor.commitText("name@", 1)
        f.controller.onEditorContextChanged()
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, f.controller.candidateWords())
        EmailDomains(f.prefs).record("gmail.com")
        assertTrue(f.lexicon.remove(UserLexicon.Kind.EMAIL, "gmail.com"))
        f.notifyChanged(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS)
        assertFalse("gmail.com" in f.controller.candidateWords())
        assertFalse(f.prefs.contains(UserLexicon.EMAIL_COUNT_PREFIX + "gmail.com"))
        assertTrue(f.lexicon.resetEmailDefaults())
        f.notifyChanged(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS)
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, f.controller.candidateWords())
        f.controller.onPickCandidate(f.controller.candidateWords().indexOf("gmail.com"))
        assertEquals("name@gmail.com", f.editor.editable.toString())
    }

    @Test fun a_deleted_default_cannot_be_picked_before_its_preference_callback_arrives() {
        val f = Fixture()
        f.editor.commitText("name@", 1)
        f.controller.onEditorContextChanged()
        val index = f.controller.candidateWords().indexOf("qq.com")
        assertTrue(index >= 0)
        assertTrue(f.lexicon.remove(UserLexicon.Kind.EMAIL, "qq.com"))
        f.controller.onPickCandidate(index)
        assertEquals("name@", f.editor.editable.toString())
        assertFalse(f.prefs.contains(UserLexicon.EMAIL_COUNT_PREFIX + "qq.com"))
    }
}
