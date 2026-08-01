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
import com.aegis.ime.dict.Fuzzy
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal enum class SettingsMigrationStage {
    BEFORE_DATABASE_COMMIT,
    AFTER_DATABASE_COMMIT,
    BEFORE_LEGACY_CLEANUP,
    AFTER_LEGACY_CLEANUP,
}

internal object UserSettingsSchema {
    const val CLIPBOARD_HISTORY = "clip_history"

    val defaults: Map<String, StoredSettingValue> = linkedMapOf<String, StoredSettingValue>(
        "cn_layout" to StoredSettingValue.StringValue("nine"),
        "pref_default_lang" to StoredSettingValue.StringValue("cn"),
        "pref_letter_case" to StoredSettingValue.StringValue("auto"),
        "pref_associations_on" to StoredSettingValue.Bool(false),
        "fuzzy" to StoredSettingValue.Bool(Fuzzy.DEFAULT_ON),
        "pref_key_haptics" to StoredSettingValue.Bool(false),
        "pref_key_preview_master" to StoredSettingValue.Bool(false),
        "pref_key_preview_nine" to StoredSettingValue.Bool(true),
        "pref_key_preview_alpha" to StoredSettingValue.Bool(true),
        CLIPBOARD_HISTORY to StoredSettingValue.Bool(true),
        "dl_hint_dismissed" to StoredSettingValue.Bool(false),
    ).apply {
        for (rule in Fuzzy.RULES) put(Fuzzy.prefKey(rule.key), StoredSettingValue.Bool(true))
    }

    val specialStorageKeys: Set<String> = setOf(
        "custom_symbols",
        "custom_operators",
        "gram_validator",
        "gram_sha256",
        "gram_size_bytes",
        "dict_validator",
        "dict_sha256",
        "dict_asset_name",
        "dict_asset_url",
        "dict_release_tag",
        "dict_release_published_at",
        "engine_pack_touch",
    )

    fun legacyValues(preferences: SharedPreferences?): Map<String, StoredSettingValue> {
        if (preferences == null) return emptyMap()
        val out = LinkedHashMap<String, StoredSettingValue>()
        for ((key, value) in preferences.all.toSortedMap()) {
            if (key in specialStorageKeys) continue
            out[key] = storedValue(key, value)
        }
        return out
    }

    fun digest(values: Map<String, StoredSettingValue>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((key, value) in values.toSortedMap()) {
            update(digest, key)
            when (value) {
                is StoredSettingValue.Bool -> update(digest, "b:${if (value.value) 1 else 0}")
                is StoredSettingValue.Integer -> update(digest, "i:${value.value}")
                is StoredSettingValue.LongValue -> update(digest, "l:${value.value}")
                is StoredSettingValue.FloatValue -> update(digest, "f:${value.value.toRawBits()}")
                is StoredSettingValue.StringValue -> {
                    update(digest, "s")
                    update(digest, value.value)
                }
                is StoredSettingValue.StringSetValue -> {
                    update(digest, "t:${value.value.size}")
                    for (item in value.value.sorted()) update(digest, item)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun asAny(value: StoredSettingValue): Any = when (value) {
        is StoredSettingValue.Bool -> value.value
        is StoredSettingValue.Integer -> value.value
        is StoredSettingValue.LongValue -> value.value
        is StoredSettingValue.FloatValue -> value.value
        is StoredSettingValue.StringValue -> value.value
        is StoredSettingValue.StringSetValue -> LinkedHashSet(value.value)
    }

    private fun storedValue(key: String, value: Any?): StoredSettingValue = when (value) {
        is Boolean -> StoredSettingValue.Bool(value)
        is Int -> StoredSettingValue.Integer(value)
        is Long -> StoredSettingValue.LongValue(value)
        is Float -> StoredSettingValue.FloatValue(value)
        is String -> StoredSettingValue.StringValue(value)
        is Set<*> -> {
            if (value.any { it !is String }) throw IOException("unsupported string-set member: $key")
            StoredSettingValue.StringSetValue(value.filterIsInstance<String>().toCollection(LinkedHashSet()))
        }
        else -> throw IOException("unsupported preference type: $key")
    }

    private fun update(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
}

internal class UserSettingsPreferences private constructor(
    private val root: File,
    private val legacyPreferences: SharedPreferences?,
    private val opener: () -> UserDataDatabase,
) : SharedPreferences {

    constructor(root: File, legacyPreferences: SharedPreferences?) : this(
        root,
        legacyPreferences,
        migrationAwareOpener(root, legacyPreferences),
    )

    internal constructor(root: File, opener: () -> UserDataDatabase) : this(root, null, opener)

    @Volatile
    var lastFailure: String? = null
        private set

    override fun getAll(): Map<String, *> = read(emptyMap()) { database ->
        database.readSettings().mapValues { UserSettingsSchema.asAny(it.value) }
    }

    override fun getString(key: String, defValue: String?): String? =
        typed(key, defValue) { (it as StoredSettingValue.StringValue).value }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        typed(key, defValues) { LinkedHashSet((it as StoredSettingValue.StringSetValue).value) }

    override fun getInt(key: String, defValue: Int): Int =
        typed(key, defValue) { (it as StoredSettingValue.Integer).value }

    override fun getLong(key: String, defValue: Long): Long =
        typed(key, defValue) { (it as StoredSettingValue.LongValue).value }

    override fun getFloat(key: String, defValue: Float): Float =
        typed(key, defValue) { (it as StoredSettingValue.FloatValue).value }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val fallback = if (key == UserSettingsSchema.CLIPBOARD_HISTORY) false else defValue
        return typed(key, fallback) { (it as StoredSettingValue.Bool).value }
    }

    override fun contains(key: String): Boolean = read(false) { it.readSetting(key) != null }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        UserSettingsChangeBus.register(root, listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        UserSettingsChangeBus.unregister(root, listener)
    }

    private fun <T> typed(key: String, fallback: T, convert: (StoredSettingValue) -> T): T =
        read(fallback) { database ->
            val value = database.readSetting(key) ?: return@read fallback
            convert(value)
        }

    private fun <T> read(fallback: T, block: (UserDataDatabase) -> T): T = try {
        opener().use { database -> block(database) }.also { lastFailure = null }
    } catch (failure: Exception) {
        lastFailure = failure.javaClass.simpleName + ": " + failure.message.orEmpty()
        fallback
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, StoredSettingValue?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            changes[key] = value?.let(StoredSettingValue::StringValue)
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
            changes[key] = values?.let { StoredSettingValue.StringSetValue(LinkedHashSet(it)) }
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            changes[key] = StoredSettingValue.Integer(value)
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            changes[key] = StoredSettingValue.LongValue(value)
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            changes[key] = StoredSettingValue.FloatValue(value)
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            changes[key] = StoredSettingValue.Bool(value)
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            var database: UserDataDatabase? = null
            var before = emptyMap<String, StoredSettingValue>()
            return try {
                database = opener()
                before = database.readSettings()
                val writes = LinkedHashMap<String, StoredSettingValue>()
                val removals = LinkedHashSet<String>()
                if (clearRequested) writes.putAll(UserSettingsSchema.defaults)
                for ((key, value) in changes) {
                    if (value != null) {
                        writes[key] = value
                        removals.remove(key)
                    } else {
                        val default = UserSettingsSchema.defaults[key]
                        if (default == null) removals.add(key) else writes[key] = default
                    }
                }
                database.updateSettings(writes, removals, clearRequested)
                database.checkpointLastGood()
                database.markSettingsCheckpointed()
                val after = database.readSettings()
                val changed = (before.keys + after.keys).filterTo(LinkedHashSet()) { before[it] != after[it] }
                lastFailure = null
                UserSettingsChangeBus.notify(root, this@UserSettingsPreferences, changed)
                true
            } catch (failure: Exception) {
                lastFailure = failure.javaClass.simpleName + ": " + failure.message.orEmpty()
                database?.let { opened ->
                    runCatching {
                        opened.replaceSettings(before)
                        opened.checkpointLastGood()
                        opened.markSettingsCheckpointed()
                    }
                    val previousPrivacy = before[UserSettingsSchema.CLIPBOARD_HISTORY] as? StoredSettingValue.Bool
                    if (previousPrivacy?.value == false) {
                        runCatching {
                            opened.updateSettings(
                                mapOf(UserSettingsSchema.CLIPBOARD_HISTORY to StoredSettingValue.Bool(false)),
                            )
                            opened.checkpointLastGood()
                            opened.markSettingsCheckpointed()
                        }
                    }
                }
                false
            } finally {
                runCatching { database?.close() }
            }
        }

        override fun apply() {
            commit()
        }
    }

    companion object {
        fun notifyRestored(root: File) {
            val preferences = UserSettingsPreferences(root, null)
            val keys = preferences.all.keys
            UserSettingsChangeBus.notify(root, preferences, keys)
        }

        private fun migrationAwareOpener(
            root: File,
            legacyPreferences: SharedPreferences?,
        ): () -> UserDataDatabase {
            val lock = Any()
            var migrationChecked = false
            return {
                synchronized(lock) {
                    if (migrationChecked) {
                        UserDataDatabase.open(root)
                    } else {
                        UserDataMigration.open(root, legacyPreferences).also { migrationChecked = true }
                    }
                }
            }
        }
    }
}

internal fun userSettings(context: Context): UserSettingsPreferences = UserSettingsPreferences(
    context.filesDir,
    context.getSharedPreferences("aegis", Context.MODE_PRIVATE),
)

private object UserSettingsChangeBus {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>>()

    fun register(root: File, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.computeIfAbsent(root.absoluteFile.normalize().path) { CopyOnWriteArraySet() }.add(listener)
    }

    fun unregister(root: File, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val key = root.absoluteFile.normalize().path
        listeners[key]?.let { registered ->
            registered.remove(listener)
            if (registered.isEmpty()) listeners.remove(key, registered)
        }
    }

    fun notify(root: File, preferences: SharedPreferences, keys: Collection<String>) {
        val registered = listeners[root.absoluteFile.normalize().path] ?: return
        for (key in keys) for (listener in registered) {
            runCatching { listener.onSharedPreferenceChanged(preferences, key) }
        }
    }
}
