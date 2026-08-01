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
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object UserDataMigration {

    private val migrationLocks = Array(16) { Any() }

    fun open(
        root: File,
        preferences: SharedPreferences? = null,
        settingsStage: ((SettingsMigrationStage) -> Unit)? = null,
    ): UserDataDatabase = synchronized(migrationLock(root)) {
        val database = UserDataDatabase.open(root)
        try {
            if (canSkipCompletedMigration(root, preferences, database)) return@synchronized database
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
            settingsStage?.invoke(SettingsMigrationStage.BEFORE_LEGACY_CLEANUP)
            val settingsCleanupComplete = cleanupLegacySettings(preferences)
            if (settingsCleanupComplete) settingsStage?.invoke(SettingsMigrationStage.AFTER_LEGACY_CLEANUP)
            val userDb = File(root, "userdb.txt")
            val userLearn = File(root, "userlearn.txt")
            val identities = linkedMapOf(
                "userdb" to UserDataDatabase.fileIdentity(userDb),
                "userlearn" to UserDataDatabase.fileIdentity(userLearn),
            )
            val baseMigrationPending = database.metadata("beta29_migration") == null
            val legacyUser = if (baseMigrationPending && userDb.isFile) {
                runCatching { UserModel().apply { load(userDb) }.storageSnapshot() }
                    .onFailure { identities["userdb_status"] = "invalid:${it.javaClass.simpleName}" }
                    .getOrElse { throw IOException("legacy user dictionary is invalid", it) }
            } else {
                null
            }
            val legacyLearning = if (baseMigrationPending && userLearn.isFile) {
                UserLearning().let { model ->
                    model.load(userLearn)
                    if (model.lastFailure == null) model.storageSnapshot()
                    else {
                        identities["userlearn_status"] = "invalid"
                        throw IOException("legacy user learning data is invalid: ${model.lastFailure}")
                    }
                }
            } else {
                null
            }
            database.migrateLegacy(legacyUser, legacyLearning, identities)
            val collectionIdentities = LinkedHashMap<String, String>()
            val clipboardSource = database.metadata("beta29_clipboard_migration") == null &&
                (File(root, "clipboard.txt").isFile || File(root, "phrases.txt").isFile)
            val legacyClipboard = if (clipboardSource) {
                runCatching {
                    ClipboardStore(root).apply {
                        if (!load(purgeLegacyImages = false)) throw IllegalStateException(lastFailure)
                    }.storageSnapshot()
                }.onFailure { collectionIdentities["clipboard_status"] = "invalid:${it.javaClass.simpleName}" }
                    .getOrElse { throw IOException("legacy clipboard data is invalid", it) }
                    .also {
                        collectionIdentities["clipboard"] = UserDataDatabase.fileIdentity(File(root, "clipboard.txt"))
                        collectionIdentities["phrases"] = UserDataDatabase.fileIdentity(File(root, "phrases.txt"))
                    }
            } else {
                null
            }
            if (legacyClipboard != null) {
                val phraseCount = legacyClipboard.categories.sumOf { it.phrases.size }
                collectionIdentities["clipboard_records"] =
                    "history=${legacyClipboard.history.size},categories=${legacyClipboard.categories.size},phrases=$phraseCount"
                collectionIdentities["clipboard_snapshot"] = collectionIdentity(legacyClipboard)
            }
            val customItems = LinkedHashMap<String, List<String>>()
            if (preferences != null) {
                for (key in listOf("custom_symbols", "custom_operators")) {
                    val store = CustomSymbolStore(preferences, key)
                    if (database.metadata("beta29_custom_migration_$key") == null && store.hasLegacyValue()) {
                        customItems[store.storageKind()] = store.legacyItems()
                        collectionIdentities[key] = store.legacyIdentity()
                    }
                }
            }
            val recentItems = LinkedHashMap<String, List<Pair<String, StoredRecentItem>>>()
            if (database.metadata("beta29_recent_migration_symbols") == null) {
                migrateRecent(root, "symbols", collectionIdentities)?.let { recentItems["symbols"] = it }
            }
            if (database.metadata("beta29_recent_migration_emoji") == null) {
                migrateRecent(File(root, "emoji"), "emoji", collectionIdentities)?.let { recentItems["emoji"] = it }
            }
            database.migrateLegacyCollections(legacyClipboard, customItems, recentItems, collectionIdentities)
            val collectionCleanupComplete = cleanupLegacyCollections(preferences, database)
            val cleanupComplete = settingsCleanupComplete && collectionCleanupComplete
            val status = if (cleanupComplete) "complete" else "cleanup-pending"
            val detail = if (cleanupComplete) {
                "beta.29 user data and settings migration verified"
            } else {
                "beta.29 user data migration verified; legacy preference cleanup pending"
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
        if (database.metadata("beta29_migration") != "complete") return false
        if (preferences != null) {
            if (runCatching { UserSettingsSchema.legacyValues(preferences).isNotEmpty() }.getOrDefault(true)) return false
            if (preferences.contains("custom_symbols") || preferences.contains("custom_operators")) return false
        }
        return legacyInputs(root).none(File::isFile)
    }

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

    private fun migrateRecent(
        directory: File,
        kind: String,
        identities: MutableMap<String, String>,
    ): List<Pair<String, StoredRecentItem>>? {
        val source = File(directory, "symbol_usage.txt")
        if (!source.isFile) return null
        identities["recent_$kind"] = UserDataDatabase.fileIdentity(source)
        val store = SymbolUsageStore(directory)
        store.load()
        if (store.lastFailure != null) {
            identities["recent_${kind}_status"] = "invalid"
            throw IOException("legacy recent data is invalid: $kind: ${store.lastFailure}")
        }
        return store.storageEntries()
    }

    private fun collectionIdentity(snapshot: ClipboardDataSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var records = 0L
        fun update(type: Byte, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(type)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
            records++
        }
        for (entry in snapshot.history) update(1, entry)
        for (category in snapshot.categories) {
            update(2, category.name)
            for (phrase in category.phrases) {
                update(3, phrase.text)
                update(4, phrase.note)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$records:$hash"
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
