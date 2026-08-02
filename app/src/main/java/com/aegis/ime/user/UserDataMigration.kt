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

import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object UserDataMigration {

    private val migrationLocks = Array(16) { Any() }

    fun open(
        root: File,
        preferences: SharedPreferences? = null,
        settingsStage: ((SettingsMigrationStage) -> Unit)? = null,
    ): UserDataDatabase = openInternal(root, preferences, settingsStage, null)

    internal fun openWithLegacyStage(
        root: File,
        preferences: SharedPreferences? = null,
        legacyStage: ((LegacyDataMigrationStage) -> Unit)? = null,
    ): UserDataDatabase = openInternal(root, preferences, null, legacyStage)

    private fun openInternal(
        root: File,
        preferences: SharedPreferences?,
        settingsStage: ((SettingsMigrationStage) -> Unit)?,
        legacyStage: ((LegacyDataMigrationStage) -> Unit)?,
    ): UserDataDatabase = synchronized(migrationLock(root)) {
        val database = UserDataDatabase.open(root)
        try {
            if (canSkipCompletedMigration(root, preferences, database)) return@synchronized database
            if (database.metadata(UserDataDatabase.SETTINGS_MIGRATION_KEY) != "complete" && preferences != null) {
                val legacySettings = UserSettingsSchema.legacyValues(preferences)
                val migratedSettings = LinkedHashMap(UserSettingsSchema.defaults).apply {
                    if (database.recoveryReport.kind != UserDataRecoveryKind.EXISTING &&
                        UserSettingsSchema.CLIPBOARD_HISTORY !in legacySettings
                    ) {
                        put(UserSettingsSchema.CLIPBOARD_HISTORY, StoredSettingValue.Bool(false))
                    }
                    putAll(legacySettings)
                }
                database.migrateLegacySettings(
                    migratedSettings,
                    legacySettings.size,
                    UserSettingsSchema.digest(legacySettings),
                    settingsStage,
                )
            }
            val settingsMigrated = database.metadata(UserDataDatabase.SETTINGS_MIGRATION_KEY) == "complete"
            if (settingsMigrated) settingsStage?.invoke(SettingsMigrationStage.BEFORE_LEGACY_CLEANUP)
            val settingsCleanupComplete = settingsMigrated && cleanupLegacySettings(preferences)
            if (settingsCleanupComplete) settingsStage?.invoke(SettingsMigrationStage.AFTER_LEGACY_CLEANUP)
            val sources = LegacyDataSources(
                root = root,
                userDictionary = File(root, "userdb.txt"),
                userLearning = File(root, "userlearn.txt"),
                clipboard = File(root, "clipboard.txt"),
                phrases = File(root, "phrases.txt"),
                recentSymbols = File(root, "symbol_usage.txt"),
                recentEmoji = File(File(root, "emoji"), "symbol_usage.txt"),
            )
            val identities = LinkedHashMap<String, String>()
            if (database.metadata("beta29_migration") == null) {
                identities["userdb"] = UserDataDatabase.fileIdentity(sources.userDictionary)
                identities["userlearn"] = UserDataDatabase.fileIdentity(sources.userLearning)
            }
            if (database.metadata("beta29_clipboard_migration") == null) {
                identities["clipboard"] = UserDataDatabase.fileIdentity(sources.clipboard)
                identities["phrases"] = UserDataDatabase.fileIdentity(sources.phrases)
            }
            val customItems = LinkedHashMap<String, List<String>>()
            if (preferences != null) {
                for (key in listOf("custom_symbols", "custom_operators")) {
                    val store = CustomSymbolStore(preferences, key)
                    if (database.metadata("beta29_custom_migration_$key") == null) {
                        customItems[store.storageKind()] = if (store.hasLegacyValue()) store.legacyItems() else emptyList()
                        identities[key] = if (store.hasLegacyValue()) store.legacyIdentity() else "absent"
                    }
                }
            }
            if (database.metadata("beta29_recent_migration_symbols") == null) {
                identities["recent_symbols"] = UserDataDatabase.fileIdentity(sources.recentSymbols)
            }
            if (database.metadata("beta29_recent_migration_emoji") == null) {
                identities["recent_emoji"] = UserDataDatabase.fileIdentity(sources.recentEmoji)
            }
            database.migrateLegacyStreams(sources, customItems, identities, legacyStage)
            legacyStage?.invoke(LegacyDataMigrationStage.BEFORE_LEGACY_CLEANUP)
            val legacyFileCleanupComplete = cleanupLegacyFiles(root, database)
            val collectionCleanupComplete = cleanupLegacyCollections(preferences, database)
            val cleanupComplete = settingsCleanupComplete && collectionCleanupComplete && legacyFileCleanupComplete &&
                allMigrationMarkersComplete(database)
            if (cleanupComplete) legacyStage?.invoke(LegacyDataMigrationStage.AFTER_LEGACY_CLEANUP)
            val status = if (cleanupComplete) "complete" else "cleanup-pending"
            val detail = if (cleanupComplete) {
                "beta.29 user data and settings migration verified"
            } else {
                "beta.29 user data migration verified; legacy cleanup pending"
            }
            writeStatus(root, status, detail)
            database
        } catch (failure: Exception) {
            database.close()
            runCatching { writeStatus(root, "failed", failure.javaClass.simpleName + ": " + failure.message.orEmpty()) }
            throw failure
        }
    }

    private fun migrationLock(root: File): Any {
        val key = root.absoluteFile.normalize().path.hashCode() and Int.MAX_VALUE
        return migrationLocks[key % migrationLocks.size]
    }

    private fun canSkipCompletedMigration(
        root: File,
        preferences: SharedPreferences?,
        database: UserDataDatabase,
    ): Boolean {
        val status = File(root, STATUS_NAME)
        if (!status.isFile || status.bufferedReader().use { it.readLine() } != "status=complete") return false
        if (database.metadata(UserDataDatabase.SETTINGS_MIGRATION_KEY) != "complete") return false
        if (!allMigrationMarkersComplete(database)) return false
        if (preferences != null) {
            if (runCatching { UserSettingsSchema.legacyValues(preferences).isNotEmpty() }.getOrDefault(true)) return false
            if (preferences.contains("custom_symbols") || preferences.contains("custom_operators")) return false
        }
        return legacyInputs(root).none(File::isFile)
    }

    private fun allMigrationMarkersComplete(database: UserDataDatabase): Boolean = listOf(
        "beta29_migration",
        "beta29_clipboard_migration",
        "beta29_custom_migration_custom_symbols",
        "beta29_custom_migration_custom_operators",
        "beta29_recent_migration_symbols",
        "beta29_recent_migration_emoji",
    ).all { database.metadata(it) == "complete" }

    private fun legacyInputs(root: File): List<File> = listOf(
        File(root, "userdb.txt"),
        File(root, "userlearn.txt"),
        File(root, "clipboard.txt"),
        File(root, "phrases.txt"),
        File(root, "symbol_usage.txt"),
        File(File(root, "emoji"), "symbol_usage.txt"),
    )

    private fun cleanupLegacySettings(preferences: SharedPreferences?): Boolean {
        if (preferences == null) return true
        val keys = preferences.all.keys.filterNotTo(LinkedHashSet()) { it in UserSettingsSchema.specialStorageKeys }
        if (keys.isEmpty()) return true
        val editor = preferences.edit()
        for (key in keys) editor.remove(key)
        if (!editor.commit()) return false
        return preferences.all.keys.none { it !in UserSettingsSchema.specialStorageKeys }
    }

    private fun cleanupLegacyCollections(
        preferences: SharedPreferences?,
        database: UserDataDatabase,
    ): Boolean {
        if (preferences == null) return true
        val keys = listOf("custom_symbols", "custom_operators").filter {
            preferences.contains(it) && database.metadata("beta29_custom_migration_$it") != null
        }
        if (keys.isEmpty()) return true
        val editor = preferences.edit()
        for (key in keys) editor.remove(key)
        if (!editor.commit()) return false
        return keys.none(preferences::contains)
    }

    private fun cleanupLegacyFiles(root: File, database: UserDataDatabase): Boolean {
        val sources = linkedMapOf(
            "beta29_migration" to listOf(File(root, "userdb.txt"), File(root, "userlearn.txt")),
            "beta29_clipboard_migration" to listOf(File(root, "clipboard.txt"), File(root, "phrases.txt")),
            "beta29_recent_migration_symbols" to listOf(File(root, "symbol_usage.txt")),
            "beta29_recent_migration_emoji" to listOf(File(File(root, "emoji"), "symbol_usage.txt")),
        )
        var complete = true
        for ((marker, files) in sources) {
            if (database.metadata(marker) != "complete") {
                complete = false
                continue
            }
            for (file in files) {
                if (file.exists() && (!file.isFile || !file.delete())) complete = false
            }
        }
        if (database.metadata("beta29_clipboard_migration") == "complete") {
            val clips = File(root, "clips")
            for (file in clips.listFiles().orEmpty()) {
                if (file.isFile && file.extension == "txt" && !file.delete()) complete = false
            }
            if (clips.isDirectory && clips.list().orEmpty().isEmpty() && !clips.delete()) complete = false
        }
        return complete
    }

    private fun writeStatus(root: File, status: String, detail: String) {
        val destination = File(root, STATUS_NAME)
        val temporary = File(root, "$STATUS_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write("status=$status\ndetail=$detail\n".toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    const val STATUS_NAME = "user-data-migration.txt"
}
