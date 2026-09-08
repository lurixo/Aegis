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

import android.content.Context
import com.aegis.ime.user.UserLexicon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailDomainsTest {
    @Test fun a_store_without_preferences_uses_the_ten_defaults_once() {
        val store = EmailDomains()
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, store.suggestions())
        store.record("gmail.com")
        assertEquals("gmail.com", store.suggestions().first())
        assertEquals(10, store.suggestions().distinct().size)
    }

    @Test fun candidates_follow_default_deletions_and_reset_on_an_already_created_store() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("email-catalog", Context.MODE_PRIVATE)
        val store = EmailDomains(prefs)
        val lexicon = UserLexicon(prefs)
        lexicon.add(UserLexicon.Kind.EMAIL, "private.example")
        store.record("private.example")
        store.record("gmail.com")
        assertTrue(lexicon.remove(UserLexicon.Kind.EMAIL, "gmail.com"))
        assertFalse(store.contains("gmail.com"))
        assertEquals(listOf("private.example") + UserLexicon.COMMON_EMAIL_DOMAINS.filterNot { it == "gmail.com" }, store.suggestions())
        assertTrue(lexicon.resetEmailDefaults())
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, store.suggestions())
        assertFalse(store.contains("private.example"))
        assertTrue(store.contains("gmail.com"))
    }

    @Test fun selected_counts_survive_recreation_and_keep_ties_in_default_order() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("email-test", Context.MODE_PRIVATE)
        val store = EmailDomains(prefs)
        store.record("gmail.com")
        store.record("163.com")
        store.record("gmail.com")
        val restored = EmailDomains(prefs)
        assertEquals(listOf("gmail.com", "163.com", "qq.com"), restored.suggestions().take(3))
        assertEquals(mapOf("email_domain_count_gmail.com" to 2L, "email_domain_count_163.com" to 1L), prefs.all)
    }

    @Test fun unknown_domains_and_malformed_counts_do_not_break_the_fixed_catalog() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("email-invalid", Context.MODE_PRIVATE)
        prefs.edit().putString("email_domain_count_qq.com", "bad")
            .putLong("email_domain_count_gmail.com", Long.MAX_VALUE).apply()
        val store = EmailDomains(prefs)
        store.record("name@private.example")
        store.record("gmail.com")
        assertEquals(Long.MAX_VALUE, prefs.getLong("email_domain_count_gmail.com", 0))
        assertEquals(10, store.suggestions().size)
        assertEquals("gmail.com", store.suggestions().first())
        assertFalse(prefs.all.keys.any { it.contains("private") })
    }
}
