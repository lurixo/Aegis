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

package com.aegis.ime.user

import android.content.Context
import android.content.SharedPreferences
import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.dict.EnglishKey
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.EmailDomains
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserLexiconTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("custom-lexicon", Context.MODE_PRIVATE)
    private val store = UserLexicon(prefs)

    @Test fun english_entries_keep_spelling_deduplicate_folded_keys_and_survive_recreation() {
        assertEquals(UserLexicon.AddResult.ADDED, store.add(UserLexicon.Kind.ENGLISH, "  OpenAegis  "))
        assertEquals(UserLexicon.AddResult.EXISTS, store.add(UserLexicon.Kind.ENGLISH, "openaegis"))
        assertEquals(UserLexicon.AddResult.ADDED, store.add(UserLexicon.Kind.ENGLISH, "déjà vu"))
        assertEquals(UserLexicon.AddResult.ADDED, store.add(UserLexicon.Kind.ENGLISH, "don't"))
        assertEquals(listOf("déjà vu", "don't", "OpenAegis"), UserLexicon(prefs).entries(UserLexicon.Kind.ENGLISH))
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, store.entries(UserLexicon.Kind.EMAIL))
        for (invalid in listOf("", "你好", "abc你好", "name@example.com", "two\nlines", "<tag>", "123", "a".repeat(129))) {
            assertEquals(invalid, UserLexicon.AddResult.INVALID, store.add(UserLexicon.Kind.ENGLISH, invalid))
        }
    }

    @Test fun custom_completions_work_without_downloaded_tables_and_refresh_after_edits() {
        val engine = DictEngine(null, null, null, userLexicon = store)
        assertTrue(engine.englishCompletions("ope").isEmpty())
        store.add(UserLexicon.Kind.ENGLISH, "OpenAegis")
        assertEquals(listOf("OpenAegis"), engine.englishCompletions("ope"))
        assertEquals(listOf("OpenAegis"), engine.englishCompletions("OPEN"))
        assertTrue(engine.englishCompletions("OpenAegis").isEmpty())
        assertTrue(store.remove(UserLexicon.Kind.ENGLISH, "OpenAegis"))
        assertTrue(engine.englishCompletions("ope").isEmpty())
    }

    @Test fun custom_spelling_precedes_table_words_without_duplicate_normalized_entries() {
        store.add(UserLexicon.Kind.ENGLISH, "OpenAegis")
        val table = EngineFixture.build(listOf(
            EngineFixture.Row(EnglishKey.normalize("openaegis"), "openaegis", 5000),
            EngineFixture.Row("open", "open", 9000),
        ))
        val engine = DictEngine(null, null, null, englishDict = table, userLexicon = store)
        assertEquals(listOf("OpenAegis", "open"), engine.englishCompletions("ope"))
    }

    @Test fun suffixes_normalize_reject_addresses_and_join_frequency_ranking() {
        val domains = EmailDomains(prefs)
        assertFalse(domains.contains("example.org"))
        assertEquals(UserLexicon.AddResult.ADDED, store.add(UserLexicon.Kind.EMAIL, " @EXAMPLE.ORG "))
        assertEquals(UserLexicon.AddResult.EXISTS, store.add(UserLexicon.Kind.EMAIL, "example.org"))
        assertEquals(UserLexicon.AddResult.EXISTS, store.add(UserLexicon.Kind.EMAIL, "@GMAIL.COM"))
        for (invalid in listOf("name@example.org", "https://example.org", "example.org/path", "bad domain.org", "x..org", "-x.org", "x-.org", "localhost", "@@example.org", "1.2.3.4", "x.org.")) {
            assertEquals(invalid, UserLexicon.AddResult.INVALID, store.add(UserLexicon.Kind.EMAIL, invalid))
        }
        assertTrue(domains.contains("example.org"))
        domains.record("example.org")
        assertEquals("example.org", EmailDomains(prefs).suggestions().first())
        assertTrue(store.remove(UserLexicon.Kind.EMAIL, "example.org"))
        assertFalse(domains.contains("example.org"))
        assertFalse(prefs.contains(UserLexicon.EMAIL_COUNT_PREFIX + "example.org"))
        domains.record("example.org")
        assertFalse(prefs.contains(UserLexicon.EMAIL_COUNT_PREFIX + "example.org"))
        assertTrue(domains.contains("gmail.com"))
    }

    @Test fun email_entries_include_defaults_and_migrate_existing_custom_values_without_duplicates() {
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, store.entries(UserLexicon.Kind.EMAIL))
        assertTrue(prefs.all.isEmpty())
        prefs.edit().putStringSet(UserLexicon.PREF_EMAIL_DOMAINS, setOf("z.example", "@GMAIL.COM", "a.example", "A.EXAMPLE"))
            .putLong(UserLexicon.EMAIL_COUNT_PREFIX + "a.example", 4L).commit()
        val expected = UserLexicon.COMMON_EMAIL_DOMAINS + listOf("a.example", "z.example")
        assertEquals(expected, store.entries(UserLexicon.Kind.EMAIL))
        assertEquals(expected, UserLexicon(prefs).entries(UserLexicon.Kind.EMAIL))
        assertEquals("a.example", EmailDomains(prefs).suggestions().first())
        assertEquals(4L, prefs.getLong(UserLexicon.EMAIL_COUNT_PREFIX + "a.example", 0))
        assertFalse(prefs.contains(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS))
    }

    @Test fun deleting_a_default_is_persistent_invalidates_other_instances_and_clears_its_count() {
        val other = UserLexicon(prefs)
        val candidates = EmailDomains(prefs)
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, other.entries(UserLexicon.Kind.EMAIL))
        candidates.record("gmail.com")
        assertTrue(store.remove(UserLexicon.Kind.EMAIL, "gmail.com"))
        val expected = UserLexicon.COMMON_EMAIL_DOMAINS - "gmail.com"
        assertEquals(expected, store.entries(UserLexicon.Kind.EMAIL))
        assertEquals(expected, other.entries(UserLexicon.Kind.EMAIL))
        assertEquals(expected, UserLexicon(prefs).entries(UserLexicon.Kind.EMAIL))
        assertEquals(expected, candidates.suggestions())
        assertEquals(setOf("gmail.com"), prefs.getStringSet(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS, emptySet()))
        assertFalse(prefs.contains(UserLexicon.EMAIL_COUNT_PREFIX + "gmail.com"))
        candidates.record("gmail.com")
        assertFalse(prefs.contains(UserLexicon.EMAIL_COUNT_PREFIX + "gmail.com"))
        assertEquals(UserLexicon.AddResult.ADDED, other.add(UserLexicon.Kind.EMAIL, "@GMAIL.COM"))
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, store.entries(UserLexicon.Kind.EMAIL))
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, candidates.suggestions())
        assertEquals(UserLexicon.AddResult.EXISTS, store.add(UserLexicon.Kind.EMAIL, "gmail.com"))
    }

    @Test fun default_deletions_win_over_old_custom_duplicates_and_custom_edits_keep_them_deleted() {
        prefs.edit().putStringSet(UserLexicon.PREF_EMAIL_DOMAINS, setOf("qq.com", "private.example"))
            .putStringSet(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS, setOf("@QQ.COM")).commit()
        assertFalse("a migrated custom duplicate must not revive a deleted default", "qq.com" in store.entries(UserLexicon.Kind.EMAIL))
        assertEquals(UserLexicon.AddResult.ADDED, store.add(UserLexicon.Kind.EMAIL, "another.example"))
        assertTrue(store.remove(UserLexicon.Kind.EMAIL, "private.example"))
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS - "qq.com" + "another.example", store.entries(UserLexicon.Kind.EMAIL))
        assertEquals(setOf("another.example"), prefs.getStringSet(UserLexicon.PREF_EMAIL_DOMAINS, emptySet()))
    }

    @Test fun restoring_email_defaults_clears_custom_deleted_and_frequency_data_only() {
        val candidates = EmailDomains(prefs)
        store.add(UserLexicon.Kind.ENGLISH, "OpenAegis")
        store.add(UserLexicon.Kind.EMAIL, "private.example")
        candidates.record("private.example")
        candidates.record("163.com")
        store.remove(UserLexicon.Kind.EMAIL, "qq.com")
        prefs.edit().putBoolean("keep_other_setting", true)
            .putString(UserLexicon.EMAIL_COUNT_PREFIX + "old.example", "malformed").commit()
        assertTrue(store.resetEmailDefaults())
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, store.entries(UserLexicon.Kind.EMAIL))
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, candidates.suggestions())
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, UserLexicon(prefs).entries(UserLexicon.Kind.EMAIL))
        assertEquals(listOf("OpenAegis"), store.entries(UserLexicon.Kind.ENGLISH))
        assertEquals(mapOf(
            UserLexicon.PREF_ENGLISH_WORDS to setOf("OpenAegis"),
            UserLexicon.PREF_EMAIL_DOMAINS to emptySet<String>(),
            UserLexicon.PREF_DISABLED_EMAIL_DOMAINS to emptySet<String>(),
            "keep_other_setting" to true,
        ), prefs.all)
    }

}
