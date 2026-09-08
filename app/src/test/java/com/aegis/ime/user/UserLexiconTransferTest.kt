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
import com.aegis.ime.backup.BackupManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserLexiconTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val allScopes = UserLexiconTransfer.Scope.entries.toSet()
    private val englishKey = UserLexicon.PREF_ENGLISH_WORDS
    private val emailKey = UserLexicon.PREF_EMAIL_DOMAINS
    private val disabledKey = UserLexicon.PREF_DISABLED_EMAIL_DOMAINS
    private val countPrefix = UserLexicon.EMAIL_COUNT_PREFIX
    private val emptyDb = "aegis-userdb 3\n"
    private val emptyLearn = "aegis-userlearn 1\n"

    @Before fun setUp() {
        filesDir = temporary.newFolder()
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("lexicon-transfer", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onLexiconsRestored = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.onBeforeExport = null
        LiveUserData.restoreInProgress = false
        LiveUserData.restoreTrouble = null
    }

    @After fun tearDown() {
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onLexiconsRestored = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.onBeforeExport = null
        LiveUserData.restoreInProgress = false
    }

    private fun db() = File(filesDir, "userdb.txt")
    private fun learn() = File(filesDir, "userlearn.txt")
    private fun export(vararg scopes: UserLexiconTransfer.Scope): ByteArray =
        UserLexiconTransfer.export(filesDir, prefs, scopes.toSet().ifEmpty { allScopes })

    private fun restore(bytes: ByteArray, mode: BackupManager.Mode = BackupManager.Mode.OVERWRITE,
        settings: SharedPreferences = prefs) {
        bytes.inputStream().use { UserLexiconTransfer.importData(filesDir, settings, it, mode) }
    }

    private fun json(fields: String): ByteArray =
        "{\"format\":\"aegis-lexicons\",\"version\":1,$fields}".toByteArray()

    private fun chinese(db: String = emptyDb, learning: String = emptyLearn): String =
        "\"chinese\":{\"userdb\":${JSONObject.quote(db)},\"userlearn\":${JSONObject.quote(learning)}}"

    private fun seedWord(word: String = "本地", reading: String = "bendi") {
        UserModel().apply { addManualWord(reading, word, System.currentTimeMillis()) }.save(db())
    }

    private fun words(): Set<String> = UserModel().apply { load(db(), sweepStale = false) }
        .userWordEntries().mapTo(LinkedHashSet()) { it.word }

    private fun fileSnapshot(): Map<String, String> = filesDir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(filesDir).path to it.readText() }

    private fun rejectedWithoutWrites(bytes: ByteArray) {
        val beforeFiles = fileSnapshot()
        val beforePrefs = prefs.all
        assertTrue("invalid input was accepted: ${bytes.take(100)}", runCatching { restore(bytes) }.isFailure)
        assertEquals(beforeFiles, fileSnapshot())
        assertEquals(beforePrefs, prefs.all)
        assertFalse(LiveUserData.restoreInProgress)
    }

    @Test fun all_scopes_round_trip_words_learning_original_case_email_state_and_counts() {
        seedWord("导入", "daoru")
        val learning = "aegis-userlearn 1\nF\tdaoru\t导入\t3.5\t1000\nC\t学习\t导入\t2.0\t1001\n"
        learn().writeText(learning)
        prefs.edit().putStringSet(englishKey, setOf("OpenAegis", "CamelCase"))
            .putStringSet(emailKey, setOf("example.org"))
            .putStringSet(disabledKey, setOf("qq.com"))
            .putLong(countPrefix + "example.org", 42L).commit()
        val bytes = export()
        assertEquals(allScopes, UserLexiconTransfer.inspect(bytes.inputStream()))
        seedWord()
        learn().writeText(emptyLearn)
        prefs.edit().clear().putString("theme", "local-theme").putString("dict_sha256", "local-model").commit()
        restore(bytes)
        assertEquals(setOf("导入"), words())
        assertEquals(learning, learn().readText())
        assertEquals(setOf("OpenAegis", "CamelCase"), prefs.getStringSet(englishKey, emptySet()))
        assertEquals(setOf("example.org"), prefs.getStringSet(emailKey, emptySet()))
        assertEquals(setOf("qq.com"), prefs.getStringSet(disabledKey, emptySet()))
        assertEquals(42L, prefs.getLong(countPrefix + "example.org", 0L))
        assertEquals("local-theme", prefs.getString("theme", null))
        assertEquals("local-model", prefs.getString("dict_sha256", null))
    }

    @Test fun english_only_overwrite_preserves_other_scopes_and_unsaved_chinese_state() {
        seedWord()
        learn().writeText("aegis-userlearn 1\nF\tbendi\t本地\t2.0\t1000\n")
        File(filesDir, "phrases.txt").writeText("untouched phrase bytes")
        prefs.edit().putStringSet(emailKey, setOf("local.example"))
            .putStringSet(disabledKey, setOf("qq.com"))
            .putLong(countPrefix + "qq.com", 7L).putString("dict_sha256", "local-model").commit()
        val beforeFiles = fileSnapshot()
        val beforePrefs = prefs.all
        val unsaved = UserModel().apply { addManualWord("weicun", "未存", System.currentTimeMillis()) }
        UserDictHot.host = object : TestHost() {
            override fun entries() = unsaved.userWordEntries()
            override fun flush(): Boolean = error("English import must not flush Chinese stores")
            override fun reloadDictionary(): Boolean = error("English import must not reload Chinese stores")
        }
        var clipboardFlushes = 0
        LiveUserData.onBeforeRestore = { clipboardFlushes++ }
        LiveUserData.onRestored = { error("scoped import must not reload every store") }
        LiveUserData.onLexiconsRestored = { error("English import must not reload Chinese stores") }
        restore(json("\"english\":{\"words\":[\"NewCase\"]}"))
        assertEquals(beforeFiles, fileSnapshot())
        assertEquals(beforePrefs, prefs.all.filterKeys { it != englishKey })
        assertEquals(setOf("NewCase"), prefs.getStringSet(englishKey, emptySet()))
        assertEquals(listOf("未存"), UserDictHot.host?.entries()?.map { it.word })
        assertTrue(unsaved.dirty)
        assertEquals(1, clipboardFlushes)
    }

    @Test fun email_only_overwrite_replaces_domains_disabled_defaults_and_removes_stale_counts() {
        prefs.edit().putStringSet(englishKey, setOf("KeepCase"))
            .putStringSet(emailKey, setOf("local.example"))
            .putStringSet(disabledKey, setOf("qq.com"))
            .putLong(countPrefix + "local.example", 9L).putLong(countPrefix + "gmail.com", 11L).commit()
        restore(json("\"email\":{\"domains\":[],\"disabledDefaults\":[],\"counts\":{}}"))
        assertEquals(setOf("KeepCase"), prefs.getStringSet(englishKey, emptySet()))
        assertEquals(emptySet<String>(), prefs.getStringSet(emailKey, null))
        assertEquals(emptySet<String>(), prefs.getStringSet(disabledKey, null))
        assertFalse(prefs.all.keys.any { it.startsWith(countPrefix) })
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, UserLexicon(prefs).entries(UserLexicon.Kind.EMAIL))
        assertFalse(db().exists())
        assertFalse(learn().exists())
    }

    @Test fun selected_empty_export_is_explicit_and_can_clear_all_scopes() {
        val bytes = export()
        assertEquals(allScopes, UserLexiconTransfer.inspect(bytes.inputStream()))
        assertFalse(db().exists())
        assertFalse(learn().exists())
        seedWord()
        learn().writeText("aegis-userlearn 1\nF\tbendi\t本地\t2.0\t1000\n")
        prefs.edit().putStringSet(englishKey, setOf("LocalCase"))
            .putStringSet(emailKey, setOf("local.example"))
            .putStringSet(disabledKey, UserLexicon.COMMON_EMAIL_DOMAINS.toSet())
            .putLong(countPrefix + "local.example", 8L).commit()
        restore(bytes)
        assertTrue(words().isEmpty())
        assertEquals(emptyLearn, learn().readText())
        assertEquals(emptySet<String>(), prefs.getStringSet(englishKey, null))
        assertEquals(emptySet<String>(), prefs.getStringSet(emailKey, null))
        assertEquals(emptySet<String>(), prefs.getStringSet(disabledKey, null))
        assertFalse(prefs.all.keys.any { it.startsWith(countPrefix) })
    }

    @Test fun empty_chinese_merge_retains_local_words_and_learning() {
        seedWord()
        val learning = "aegis-userlearn 1\nF\tbendi\t本地\t2.0\t1000\n"
        learn().writeText(learning)
        val beforeDb = db().readText()
        restore(json(chinese()), BackupManager.Mode.MERGE)
        assertEquals(beforeDb, db().readText())
        assertEquals(learning, learn().readText())
    }

    @Test fun chinese_merge_unions_learning_records_and_keeps_local_conflicts() {
        seedWord("导入", "daoru")
        learn().writeText("aegis-userlearn 1\nF\tdaoru\t导入\t7.0\t1000\nF\tbendi\t本地\t1.0\t1001\n")
        val bytes = export(UserLexiconTransfer.Scope.CHINESE)
        seedWord()
        val local = "F\tbendi\t本地\t9.0\t2000"
        learn().writeText("aegis-userlearn 1\n$local\nC\t本地\t学习\t2.0\t2001\n")
        restore(bytes, BackupManager.Mode.MERGE)
        assertEquals(setOf("本地", "导入"), words())
        assertEquals(setOf(local, "F\tdaoru\t导入\t7.0\t1000", "C\t本地\t学习\t2.0\t2001"),
            learn().readLines().drop(1).toSet())
    }

    @Test fun english_and_email_merge_union_sets_keep_local_counts_and_drop_disabled_counts() {
        prefs.edit().putStringSet(englishKey, setOf("LocalCase"))
            .putStringSet(emailKey, setOf("local.example"))
            .putStringSet(disabledKey, setOf("gmail.com"))
            .putLong(countPrefix + "local.example", 12L).commit()
        restore(json("\"english\":{\"words\":[\"IncomingCase\"]}," +
            "\"email\":{\"domains\":[\"local.example\",\"incoming.example\"]," +
            "\"disabledDefaults\":[\"qq.com\"],\"counts\":{\"local.example\":1,\"incoming.example\":6,\"gmail.com\":3}}"),
            BackupManager.Mode.MERGE)
        assertEquals(setOf("LocalCase", "IncomingCase"), prefs.getStringSet(englishKey, null))
        assertEquals(setOf("local.example", "incoming.example"), prefs.getStringSet(emailKey, null))
        assertEquals(setOf("qq.com", "gmail.com"), prefs.getStringSet(disabledKey, null))
        assertEquals(12L, prefs.getLong(countPrefix + "local.example", 0L))
        assertEquals(6L, prefs.getLong(countPrefix + "incoming.example", 0L))
        assertFalse(prefs.contains(countPrefix + "gmail.com"))
    }

    @Test fun legacy_dictionary_import_only_replaces_chinese_words() {
        seedWord("导入", "daoru")
        val bytes = db().readBytes()
        assertEquals(setOf(UserLexiconTransfer.Scope.CHINESE), UserLexiconTransfer.inspect(bytes.inputStream()))
        seedWord()
        learn().writeText("aegis-userlearn 1\nF\tbendi\t本地\t2.0\t1000\n")
        prefs.edit().putStringSet(englishKey, setOf("KeepCase")).putLong(countPrefix + "qq.com", 2L).commit()
        val beforePrefs = prefs.all
        val beforeLearn = learn().readText()
        restore(bytes)
        assertEquals(setOf("导入"), words())
        assertEquals(beforeLearn, learn().readText())
        assertEquals(beforePrefs, prefs.all)
    }

    @Test fun legacy_empty_and_tombstone_only_files_are_rejected_without_writes() {
        seedWord()
        for (text in listOf("", emptyDb, "aegis-userdb 4\nD\t待删\t\n")) {
            rejectedWithoutWrites(text.toByteArray())
        }
    }

    @Test fun export_omits_donor_tombstones_and_empty_overwrite_keeps_local_tombstones() {
        UserModel().apply {
            addManualWord("daoru", "导入", System.currentTimeMillis())
            addTombstone("捐赠", "juanzeng")
        }.save(db())
        val bytes = export(UserLexiconTransfer.Scope.CHINESE)
        assertFalse(JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("chinese").getString("userdb").contains("D\t"))
        UserModel().apply {
            addManualWord("bendi", "本地", System.currentTimeMillis())
            addTombstone("本删", "benshan")
        }.save(db())
        restore(json(chinese()))
        val current = UserModel().apply { load(db(), sweepStale = false) }
        assertTrue(current.isEmpty())
        assertEquals(listOf("本删" to "benshan"), current.tombstones())
    }

    @Test fun inspect_validates_every_scope_without_touching_files_preferences_or_live_hooks() {
        seedWord()
        prefs.edit().putStringSet(englishKey, setOf("LocalCase")).commit()
        val beforeFiles = fileSnapshot()
        val beforePrefs = prefs.all
        UserDictHot.host = object : TestHost() {
            override fun flush(): Boolean = error("inspect must not flush")
        }
        LiveUserData.onBeforeRestore = { error("inspect must not restore") }
        val bytes = json(chinese() + ",\"english\":{\"words\":[]}")
        assertEquals(setOf(UserLexiconTransfer.Scope.CHINESE, UserLexiconTransfer.Scope.ENGLISH),
            UserLexiconTransfer.inspect(bytes.inputStream()))
        assertEquals(beforeFiles, fileSnapshot())
        assertEquals(beforePrefs, prefs.all)
    }

    @Test fun malformed_json_types_versions_duplicates_and_content_are_rejected_before_any_write() {
        seedWord()
        prefs.edit().putStringSet(englishKey, setOf("LocalCase")).commit()
        val valid = json("\"english\":{\"words\":[]}").toString(Charsets.UTF_8)
        val inputs = listOf(
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":1.0"),
            valid.replace("\"words\":[]", "\"words\":null"),
            valid.replace("\"words\":[]", "\"words\":[123]"),
            valid.replace("\"words\":[]", "\"words\":[\"bad!word\"]"),
            valid.replace("\"words\":[]", "\"words\":[],\"words\":[]"),
            valid.replace("\"english\"", "\"unrecognized\""),
            valid + "{}",
            "{\"format\":\"aegis-lexicons\",\"version\":1}",
            "{\"format\":\"aegis-lexicons\",\"version\":1,\"chinese\":{\"userdb\":\"aegis-userdb 3\\n\"}}",
            json(chinese("aegis-userdb 3\nW\t坏\tNaN\t0\n")).toString(Charsets.UTF_8),
            json(chinese(learning = "aegis-userlearn 1\nF\tdaoru\t导入\tNaN\t0\n")).toString(Charsets.UTF_8),
            json("\"email\":{\"domains\":[],\"disabledDefaults\":[\"custom.example\"],\"counts\":{}}").toString(Charsets.UTF_8),
            json("\"email\":{\"domains\":[],\"disabledDefaults\":[],\"counts\":{\"missing.example\":1}}").toString(Charsets.UTF_8),
            json("\"email\":{\"domains\":[],\"disabledDefaults\":[],\"counts\":{\"qq.com\":-1}}").toString(Charsets.UTF_8),
            json("\"email\":{\"domains\":[],\"disabledDefaults\":[],\"counts\":{\"qq.com\":9223372036854775808}}").toString(Charsets.UTF_8),
        )
        for (input in inputs) rejectedWithoutWrites(input.toByteArray())
    }

    @Test fun malformed_utf8_and_oversized_inputs_are_rejected_without_writes() {
        seedWord()
        rejectedWithoutWrites(byteArrayOf(0xC3.toByte(), 0x28))
        rejectedWithoutWrites(ByteArray(UserLexiconTransfer.MAX_BYTES + 1) { ' '.code.toByte() })
    }

    @Test fun preferences_commit_failure_rolls_back_all_scopes_and_clears_restore_flag() {
        seedWord("导入", "daoru")
        learn().writeText("aegis-userlearn 1\nF\tdaoru\t导入\t3.0\t1000\n")
        prefs.edit().putStringSet(englishKey, setOf("IncomingCase")).commit()
        val bytes = export()
        seedWord()
        learn().writeText("aegis-userlearn 1\nF\tbendi\t本地\t7.0\t2000\n")
        prefs.edit().putStringSet(englishKey, setOf("LocalCase")).putString("theme", "local").commit()
        val beforeFiles = fileSnapshot()
        val beforePrefs = prefs.all
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean {
                        delegate.commit()
                        return false
                    }
                }
            }
        }
        assertTrue(runCatching { restore(bytes, settings = failing) }.isFailure)
        assertEquals(beforeFiles, fileSnapshot())
        assertEquals(beforePrefs, prefs.all)
        assertFalse(LiveUserData.restoreInProgress)
    }

    @Test fun learning_file_failure_rolls_back_dictionary_and_preferences() {
        seedWord("导入", "daoru")
        val bytes = export()
        seedWord()
        assertTrue(learn().mkdir())
        File(learn(), "keep").writeText("existing directory sentinel")
        prefs.edit().putStringSet(englishKey, setOf("LocalCase")).commit()
        rejectedWithoutWrites(bytes)
        assertTrue(learn().isDirectory)
    }

    @Test fun failed_live_reload_after_empty_dictionary_overwrite_rolls_back_the_file() {
        seedWord()
        val before = db().readText()
        var reloads = 0
        UserDictHot.host = object : TestHost() {
            override fun reloadDictionary(): Boolean { reloads++; return false }
        }
        rejectedWithoutWrites(json(chinese()))
        assertEquals(before, db().readText())
        assertTrue(reloads >= 1)
    }

    @Test fun chinese_export_reports_blocked_flush_but_other_scopes_export_independently() {
        UserDictHot.host = object : TestHost() {
            override fun flush(): Boolean = false
        }
        assertTrue(runCatching { export(UserLexiconTransfer.Scope.CHINESE) }.exceptionOrNull()
            is UserLexiconTransfer.ExportBlockedException)
        val bytes = export(UserLexiconTransfer.Scope.ENGLISH)
        assertEquals(setOf(UserLexiconTransfer.Scope.ENGLISH), UserLexiconTransfer.inspect(bytes.inputStream()))
    }

    private open class TestHost : UserDictHot.Host {
        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String) = false
        override fun importUserDict(importFile: File, merge: Boolean, now: Long) = false
        override fun reloadDictionary() = true
        override fun entries() = emptyList<UserModel.Entry>()
        override fun learnedEntries() = emptyList<UserLearning.Formed>()
        override fun hasLearnedData() = false
        override fun removeLearned(word: String, reading: String) = false
        override fun clearLearned() = false
        override fun flush() = true
    }
}
