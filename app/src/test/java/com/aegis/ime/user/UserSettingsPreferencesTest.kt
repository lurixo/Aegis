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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserSettingsPreferencesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    private fun preferences(): SharedPreferences = context.getSharedPreferences(
        "settings-v2-${nextPreferenceId.incrementAndGet()}",
        Context.MODE_PRIVATE,
    ).also { it.edit().clear().commit() }

    @Test fun beta29_settings_migrate_losslessly_by_original_type_and_cleanup_only_after_readback() {
        val root = temporary.newFolder("typed-migration")
        val legacy = preferences()
        legacy.edit()
            .putBoolean("typed_bool", false)
            .putInt("typed_int", Int.MIN_VALUE)
            .putLong("typed_long", Long.MAX_VALUE)
            .putFloat("typed_float", -0.0f)
            .putString("typed_string", "值\nwith separators")
            .putStringSet("typed_set", linkedSetOf("二", "one", ""))
            .putBoolean("clip_history", false)
            .putString("dict_sha256", "special-storage")
            .putString("custom_symbols", "甲\n乙")
            .commit()

        UserDataMigration.open(root, legacy).use { database ->
            assertEquals(StoredSettingValue.Bool(false), database.readSetting("typed_bool"))
            assertEquals(StoredSettingValue.Integer(Int.MIN_VALUE), database.readSetting("typed_int"))
            assertEquals(StoredSettingValue.LongValue(Long.MAX_VALUE), database.readSetting("typed_long"))
            assertEquals(StoredSettingValue.FloatValue(-0.0f), database.readSetting("typed_float"))
            assertEquals(StoredSettingValue.StringValue("值\nwith separators"), database.readSetting("typed_string"))
            assertEquals(
                StoredSettingValue.StringSetValue(setOf("二", "one", "")),
                database.readSetting("typed_set"),
            )
            assertEquals(StoredSettingValue.Bool(false), database.readSetting("clip_history"))
            assertEquals("complete", database.metadata(UserDataDatabase.SETTINGS_MIGRATION_KEY))
            assertEquals("7", database.metadata(UserDataDatabase.SETTINGS_MIGRATION_SOURCE_COUNT_KEY))
            assertEquals(
                database.settingCount().toString(),
                database.metadata(UserDataDatabase.SETTINGS_MIGRATION_RECORD_COUNT_KEY),
            )
            assertNotNull(database.metadata(UserDataDatabase.SETTINGS_MIGRATION_SOURCE_DIGEST_KEY))
            assertEquals(listOf("甲", "乙"), database.readCustomItems("custom_symbols"))
        }

        for (key in listOf("typed_bool", "typed_int", "typed_long", "typed_float", "typed_string", "typed_set", "clip_history")) {
            assertFalse("migrated legacy key must be removed: $key", legacy.contains(key))
        }
        assertEquals("special-storage", legacy.getString("dict_sha256", null))
        assertFalse(legacy.contains("custom_symbols"))
    }

    @Test fun committed_migration_never_replays_stale_preferences_after_cleanup_interruption() {
        val root = temporary.newFolder("commit-cleanup-gap")
        val legacy = preferences()
        legacy.edit().putString("cn_layout", "nine").commit()

        assertThrows(SimulatedInterruption::class.java) {
            UserDataMigration.open(root, legacy) { stage ->
                if (stage == SettingsMigrationStage.BEFORE_LEGACY_CLEANUP) throw SimulatedInterruption()
            }
        }
        assertEquals("nine", legacy.getString("cn_layout", null))
        UserDataDatabase.open(root).use { database ->
            database.updateSettings(mapOf("cn_layout" to StoredSettingValue.StringValue("alpha")))
            database.checkpointLastGood()
            database.markSettingsCheckpointed()
        }

        UserDataMigration.open(root, legacy).use { database ->
            assertEquals(StoredSettingValue.StringValue("alpha"), database.readSetting("cn_layout"))
        }
        assertFalse(legacy.contains("cn_layout"))
    }

    @Test fun every_settings_migration_phase_is_safely_retryable() {
        for (interruptedStage in SettingsMigrationStage.entries) {
            val root = temporary.newFolder("interrupted-${interruptedStage.name.lowercase()}")
            val legacy = preferences()
            legacy.edit()
                .putBoolean("clip_history", false)
                .putString("pref_default_lang", "en")
                .commit()
            assertThrows(SimulatedInterruption::class.java) {
                UserDataMigration.open(root, legacy) { stage ->
                    if (stage == interruptedStage) throw SimulatedInterruption()
                }
            }
            UserDataMigration.open(root, legacy).use { database ->
                assertEquals(StoredSettingValue.Bool(false), database.readSetting("clip_history"))
                assertEquals(StoredSettingValue.StringValue("en"), database.readSetting("pref_default_lang"))
                assertEquals("complete", database.metadata(UserDataDatabase.SETTINGS_MIGRATION_KEY))
            }
            assertFalse(legacy.contains("clip_history"))
            assertFalse(legacy.contains("pref_default_lang"))
        }
    }

    @Test fun migration_io_and_enospc_failures_keep_legacy_values_and_retry_cleanly() {
        for (message in listOf("I/O", "ENOSPC")) {
            val root = temporary.newFolder("failure-${message.lowercase().replace('/', '-')}")
            val legacy = preferences()
            legacy.edit().putLong("typed_long", 42L).commit()
            assertThrows(IOException::class.java) {
                UserDataMigration.open(root, legacy) { stage ->
                    if (stage == SettingsMigrationStage.BEFORE_DATABASE_COMMIT) throw IOException(message)
                }
            }
            assertEquals(42L, legacy.getLong("typed_long", -1L))
            UserDataMigration.open(root, legacy).use { database ->
                assertEquals(StoredSettingValue.LongValue(42L), database.readSetting("typed_long"))
            }
            assertFalse(legacy.contains("typed_long"))
        }
    }

    @Test fun failed_legacy_cleanup_is_retried_without_blocking_sqlite_reads() {
        val root = temporary.newFolder("cleanup-failure")
        val delegate = preferences()
        delegate.edit().putBoolean("pref_key_haptics", true).commit()
        val failing = FailingCommitPreferences(delegate)

        UserDataMigration.open(root, failing).use { database ->
            assertEquals(StoredSettingValue.Bool(true), database.readSetting("pref_key_haptics"))
        }
        assertTrue(delegate.contains("pref_key_haptics"))
        assertTrue(File(root, UserDataMigration.STATUS_NAME).readText().contains("status=cleanup-pending"))
        UserSettingsPreferences.notifyRestored(root)
        assertTrue(
            "restore notification must not mark unavailable legacy preferences as cleaned",
            File(root, UserDataMigration.STATUS_NAME).readText().contains("status=cleanup-pending"),
        )
        failing.failCommits = false
        UserDataMigration.open(root, failing).use { database ->
            assertEquals(StoredSettingValue.Bool(true), database.readSetting("pref_key_haptics"))
        }
        assertFalse(delegate.contains("pref_key_haptics"))
    }

    @Test fun fully_completed_and_clean_migration_uses_the_verified_fast_path() {
        val root = temporary.newFolder("completed-fast-path")
        val legacy = preferences()
        legacy.edit().putString("pref_default_lang", "en").commit()
        UserDataMigration.open(root, legacy).close()

        val stages = ArrayList<SettingsMigrationStage>()
        UserDataMigration.open(root, legacy, stages::add).use { database ->
            assertEquals(StoredSettingValue.StringValue("en"), database.readSetting("pref_default_lang"))
        }
        assertTrue("a clean completed migration must not rerun detailed migration stages", stages.isEmpty())
    }

    @Test fun unavailable_legacy_preferences_are_deferred_instead_of_marked_empty() {
        val root = temporary.newFolder("deferred-preference-source")
        File(root, "userdb.txt").writeText("aegis-userdb 1\nW\t迁移\t2\t3\nR\tqianyi\t迁移\n")
        val legacy = preferences()
        legacy.edit()
            .putString("pref_default_lang", "en")
            .putString("custom_symbols", "甲\n乙")
            .commit()

        UserDataMigration.open(root).use { database ->
            assertTrue(database.hasUserReading("qianyi", "迁移"))
            assertNull(database.metadata(UserDataDatabase.SETTINGS_MIGRATION_KEY))
            assertNull(database.metadata("beta29_custom_migration_custom_symbols"))
        }
        assertTrue(legacy.contains("pref_default_lang"))
        assertTrue(legacy.contains("custom_symbols"))
        assertTrue(File(root, UserDataMigration.STATUS_NAME).readText().contains("status=cleanup-pending"))

        UserDataMigration.open(root, legacy).use { database ->
            assertEquals(StoredSettingValue.StringValue("en"), database.readSetting("pref_default_lang"))
            assertEquals(listOf("甲", "乙"), database.readCustomItems("custom_symbols"))
            assertEquals("complete", database.metadata(UserDataDatabase.SETTINGS_MIGRATION_KEY))
            assertEquals("complete", database.metadata("beta29_custom_migration_custom_symbols"))
        }
        assertFalse(legacy.contains("pref_default_lang"))
        assertFalse(legacy.contains("custom_symbols"))
        assertTrue(File(root, UserDataMigration.STATUS_NAME).readText().contains("status=complete"))
    }

    @Test fun completed_migration_falls_back_when_a_stale_legacy_value_reappears() {
        val root = temporary.newFolder("completed-stale-fallback")
        val legacy = preferences()
        legacy.edit().putString("pref_default_lang", "en").commit()
        UserDataMigration.open(root, legacy).use { database ->
            database.updateSettings(mapOf("pref_default_lang" to StoredSettingValue.StringValue("cn")))
            database.checkpointLastGood()
            database.markSettingsCheckpointed()
        }
        legacy.edit().putString("pref_default_lang", "stale").commit()

        val stages = ArrayList<SettingsMigrationStage>()
        UserDataMigration.open(root, legacy, stages::add).use { database ->
            assertEquals(StoredSettingValue.StringValue("cn"), database.readSetting("pref_default_lang"))
        }
        assertTrue("stale input must force the idempotent cleanup path", stages.isNotEmpty())
        assertFalse(legacy.contains("pref_default_lang"))
    }

    @Test fun clipboard_history_is_fail_closed_on_read_and_write_failure_and_survives_reopen() {
        val root = temporary.newFolder("privacy")
        val legacy = preferences()
        val settings = UserSettingsPreferences(root, legacy)
        assertFalse(settings.getBoolean("clip_history", true))
        assertTrue(settings.edit().putBoolean("clip_history", false).commit())
        assertFalse(UserSettingsPreferences(root, legacy).getBoolean("clip_history", true))

        val unavailable = UserSettingsPreferences(root) { throw IOException("storage unavailable") }
        assertFalse(unavailable.getBoolean("clip_history", true))
        assertFalse(unavailable.edit().putBoolean("clip_history", true).commit())
        assertFalse(UserSettingsPreferences(root, legacy).getBoolean("clip_history", true))
    }

    @Test fun fresh_database_created_before_settings_bootstrap_is_private_but_explicit_beta29_true_is_preserved() {
        val freshRoot = temporary.newFolder("privacy-created-by-user-data-path")
        UserDataDatabase.open(freshRoot).use { database ->
            assertNull(database.readSetting("clip_history"))
        }
        val fresh = UserSettingsPreferences(freshRoot, preferences())
        assertFalse(fresh.getBoolean("clip_history", true))
        UserDataDatabase.open(freshRoot).use { database ->
            assertEquals(StoredSettingValue.Bool(false), database.readSetting("clip_history"))
        }

        val migratedRoot = temporary.newFolder("privacy-explicit-beta29-on")
        val legacy = preferences()
        assertTrue(legacy.edit().putBoolean("clip_history", true).commit())
        UserDataMigration.open(migratedRoot, legacy).use { database ->
            assertEquals(StoredSettingValue.Bool(true), database.readSetting("clip_history"))
        }
        assertFalse(legacy.contains("clip_history"))
    }

    @Test fun corruption_without_a_valid_snapshot_cannot_reenable_clipboard_history() {
        val root = temporary.newFolder("privacy-corruption")
        val legacy = preferences()
        val settings = UserSettingsPreferences(root, legacy)
        assertTrue(settings.edit().putBoolean("clip_history", false).commit())
        File(root, UserDataDatabase.LAST_GOOD_NAME).delete()
        File(root, "user-data-v2.last-good.sha256").delete()
        File(root, UserDataDatabase.DATABASE_NAME).writeText("corrupt")

        assertFalse(UserSettingsPreferences(root, legacy).getBoolean("clip_history", true))
    }

    @Test fun concurrent_setting_reads_and_writes_commit_complete_values() {
        val root = temporary.newFolder("concurrency")
        val settings = UserSettingsPreferences(root, preferences())
        val workers = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val failures = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workers)
        repeat(workers) { worker ->
            executor.execute {
                start.await()
                if (!settings.edit().putInt("concurrent_$worker", worker).commit()) failures.incrementAndGet()
                if (settings.getInt("concurrent_$worker", -1) != worker) failures.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(0, failures.get())
        repeat(workers) { worker -> assertEquals(worker, settings.getInt("concurrent_$worker", -1)) }
    }

    @Test fun repeated_reads_share_one_verified_snapshot_and_successful_commits_refresh_every_instance() {
        val root = temporary.newFolder("shared-read-snapshot")
        val legacy = preferences()
        legacy.edit()
            .putString("pref_default_lang", "en")
            .putBoolean("clip_history", false)
            .commit()
        UserDataMigration.open(root, legacy).close()

        val opens = AtomicInteger()
        val opener = {
            opens.incrementAndGet()
            UserDataDatabase.open(root)
        }
        val first = UserSettingsPreferences(root, opener)
        val second = UserSettingsPreferences(root, opener)

        repeat(20) {
            assertEquals("en", first.getString("pref_default_lang", null))
            assertFalse(second.getBoolean("clip_history", true))
            assertEquals("nine", second.getString("cn_layout", null))
        }
        assertEquals("all getters must share one recovery-verified SQLite snapshot", 1, opens.get())

        assertTrue(second.edit().putString("pref_default_lang", "cn").commit())
        assertEquals(2, opens.get())
        assertEquals("cn", first.getString("pref_default_lang", null))
        assertFalse(first.getBoolean("clip_history", true))
        assertEquals("the successful commit must publish without another open", 2, opens.get())
    }

    @Test fun restore_invalidation_forces_the_next_setting_read_back_to_sqlite() {
        val root = temporary.newFolder("restore-cache-invalidation")
        UserDataMigration.open(root, preferences()).use { database ->
            database.updateSettings(mapOf("pref_default_lang" to StoredSettingValue.StringValue("en")))
            database.checkpointLastGood()
            database.markSettingsCheckpointed()
        }
        val opens = AtomicInteger()
        val settings = UserSettingsPreferences(root) {
            opens.incrementAndGet()
            UserDataDatabase.open(root)
        }
        assertEquals("en", settings.getString("pref_default_lang", null))
        assertEquals(1, opens.get())

        UserDataDatabase.open(root).use { database ->
            database.updateSettings(mapOf("pref_default_lang" to StoredSettingValue.StringValue("cn")))
            database.checkpointLastGood()
            database.markSettingsCheckpointed()
        }
        UserSettingsPreferences.invalidateCache(root)

        assertEquals("cn", settings.getString("pref_default_lang", null))
        assertEquals(2, opens.get())
    }

    @Test fun successful_sqlite_commits_notify_hot_apply_listeners_and_failed_commits_do_not() {
        val root = temporary.newFolder("listeners")
        val settings = UserSettingsPreferences(root, preferences())
        val changed = ArrayList<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> key?.let(changed::add) }
        settings.registerOnSharedPreferenceChangeListener(listener)
        assertTrue(settings.edit().putString("cn_layout", "alpha").commit())
        assertEquals(listOf("cn_layout"), changed)

        val unavailable = UserSettingsPreferences(root) { throw IOException("unavailable") }
        assertFalse(unavailable.edit().putString("cn_layout", "nine").commit())
        assertEquals(listOf("cn_layout"), changed)
        val throwing = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> error("listener failure") }
        settings.registerOnSharedPreferenceChangeListener(throwing)
        assertTrue(settings.edit().putString("pref_default_lang", "en").commit())
        assertEquals("en", settings.getString("pref_default_lang", null))
        settings.unregisterOnSharedPreferenceChangeListener(throwing)
        settings.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private class SimulatedInterruption : IOException()

    private class FailingCommitPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var failCommits = true

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean = if (failCommits) false else editor.commit()
            }
        }
    }

    companion object {
        private val nextPreferenceId = AtomicInteger()
    }
}
