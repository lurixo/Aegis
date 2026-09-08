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
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.aegis.ime.backup.BackupManager
import com.aegis.ime.dict.EnglishKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object UserLexiconTransfer {
    internal const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    private const val FORMAT = "aegis-lexicons"
    private const val VERSION = 1L
    private const val EMPTY_DICTIONARY = "aegis-userdb 3\n"
    private const val EMPTY_LEARNING = "aegis-userlearn 1\n"

    enum class Scope { CHINESE, ENGLISH, EMAIL }

    internal class ExportBlockedException : IOException("user lexicons could not be flushed")

    internal class Chinese(val userdb: String, val userlearn: String?)
    internal class Email(val domains: Set<String>, val disabledDefaults: Set<String>, val counts: Map<String, Long>)
    internal class Data(
        val chinese: Chinese? = null,
        val english: Set<String>? = null,
        val email: Email? = null,
        val legacy: Boolean = false,
    ) {
        fun scopes(): Set<Scope> = buildSet {
            if (chinese != null) add(Scope.CHINESE)
            if (english != null) add(Scope.ENGLISH)
            if (email != null) add(Scope.EMAIL)
        }
    }

    fun export(filesDir: File, prefs: SharedPreferences, scopes: Set<Scope>): ByteArray {
        require(scopes.isNotEmpty()) { "no lexicon selected" }
        val chinese = if (Scope.CHINESE in scopes) {
            if (!UserDictEdit.flushBeforeExport()) throw ExportBlockedException()
            UserDictHot.host?.let {
                if (!it.dictionaryReadable() || !it.learnedReadable()) throw IOException("Chinese lexicons are unreadable")
            }
            val db = readStore(File(filesDir, "userdb.txt"), EMPTY_DICTIONARY)
            validateDictionary(db)
            val shared = ByteArrayOutputStream()
            db.byteInputStream().use { UserDictExport.copyWithoutTombstones(it, shared) }
            val learn = readStore(File(filesDir, "userlearn.txt"), EMPTY_LEARNING)
            validateLearning(learn)
            Chinese(shared.toString(Charsets.UTF_8.name()), learn)
        } else null
        val snapshot = prefs.all
        val english = if (Scope.ENGLISH in scopes) {
            normalizeValues(storedSet(snapshot, UserLexicon.PREF_ENGLISH_WORDS), UserLexicon.Kind.ENGLISH)
        } else null
        val email = if (Scope.EMAIL in scopes) {
            val domains = normalizeValues(storedSet(snapshot, UserLexicon.PREF_EMAIL_DOMAINS), UserLexicon.Kind.EMAIL)
                .filterTo(LinkedHashSet()) { it !in UserLexicon.COMMON_EMAIL_DOMAINS }
            val disabled = normalizeValues(storedSet(snapshot, UserLexicon.PREF_DISABLED_EMAIL_DOMAINS), UserLexicon.Kind.EMAIL)
            require(disabled.all { it in UserLexicon.COMMON_EMAIL_DOMAINS }) { "invalid disabled email default" }
            val available = domains + (UserLexicon.COMMON_EMAIL_DOMAINS - disabled)
            val counts = LinkedHashMap<String, Long>()
            for ((key, value) in snapshot.toSortedMap()) {
                if (!key.startsWith(UserLexicon.EMAIL_COUNT_PREFIX)) continue
                val domain = key.removePrefix(UserLexicon.EMAIL_COUNT_PREFIX)
                require(value is Long && value >= 0L && domain in available) { "invalid email usage count" }
                counts[domain] = value
            }
            Email(domains, disabled, counts)
        } else null
        return encode(Data(chinese, english, email))
    }

    fun inspect(input: InputStream): Set<Scope> = decode(input).scopes()

    fun importData(filesDir: File, prefs: SharedPreferences, input: InputStream, mode: BackupManager.Mode) {
        BackupManager.restoreLexicons(filesDir, prefs, decode(input), mode)
    }

    private fun decode(input: InputStream): Data {
        val text = decodeUtf8(readBounded(input))
        if (text.firstOrNull { !it.isWhitespace() } != '{') {
            require(validateDictionary(text)) { "the legacy dictionary contains no words" }
            return Data(chinese = Chinese(text, null), legacy = true)
        }
        var format: String? = null
        var version: Long? = null
        var chinese: Chinese? = null
        var english: Set<String>? = null
        var email: Email? = null
        JsonReader(StringReader(text)).use { reader ->
            readObject(reader) { key ->
                when (key) {
                    "format" -> format = readString(reader)
                    "version" -> version = readLong(reader)
                    "chinese" -> chinese = readChinese(reader)
                    "english" -> english = readEnglish(reader)
                    "email" -> email = readEmail(reader)
                    else -> throw IllegalArgumentException("unknown lexicon field")
                }
            }
            require(reader.peek() == JsonToken.END_DOCUMENT) { "trailing lexicon content" }
        }
        require(format == FORMAT && version == VERSION) { "unsupported lexicon format or version" }
        return Data(chinese, english, email).also { require(it.scopes().isNotEmpty()) { "no lexicon content" } }
    }

    private fun readChinese(reader: JsonReader): Chinese {
        var db: String? = null
        var learn: String? = null
        readObject(reader) { key ->
            when (key) {
                "userdb" -> db = readString(reader)
                "userlearn" -> learn = readString(reader)
                else -> throw IllegalArgumentException("unknown Chinese lexicon field")
            }
        }
        val dictionary = requireNotNull(db) { "missing user dictionary" }
        val learning = requireNotNull(learn) { "missing user learning" }
        validateDictionary(dictionary)
        validateLearning(learning)
        return Chinese(dictionary, learning)
    }

    private fun readEnglish(reader: JsonReader): Set<String> {
        var words: Set<String>? = null
        readObject(reader) { key ->
            require(key == "words") { "unknown English lexicon field" }
            words = readValues(reader, UserLexicon.Kind.ENGLISH)
        }
        return requireNotNull(words) { "missing English words" }
    }

    private fun readEmail(reader: JsonReader): Email {
        var domains: Set<String>? = null
        var disabled: Set<String>? = null
        var counts: Map<String, Long>? = null
        readObject(reader) { key ->
            when (key) {
                "domains" -> domains = readValues(reader, UserLexicon.Kind.EMAIL)
                "disabledDefaults" -> disabled = readValues(reader, UserLexicon.Kind.EMAIL)
                "counts" -> {
                    val values = LinkedHashMap<String, Long>()
                    readObject(reader) { raw ->
                        require(values.size < MAX_ENTRIES) { "too many email counts" }
                        val domain = requireNotNull(UserLexicon.normalize(UserLexicon.Kind.EMAIL, raw)) { "invalid email domain" }
                        val count = readLong(reader)
                        require(count >= 0L && values.put(domain, count) == null) { "invalid or duplicate email count" }
                    }
                    counts = values
                }
                else -> throw IllegalArgumentException("unknown email lexicon field")
            }
        }
        val custom = requireNotNull(domains) { "missing email domains" }
        val hidden = requireNotNull(disabled) { "missing disabled email defaults" }
        val usage = requireNotNull(counts) { "missing email counts" }
        require(custom.none { it in UserLexicon.COMMON_EMAIL_DOMAINS }) { "default email domain in custom domains" }
        require(hidden.all { it in UserLexicon.COMMON_EMAIL_DOMAINS }) { "invalid disabled email default" }
        val available = custom + (UserLexicon.COMMON_EMAIL_DOMAINS - hidden)
        require(usage.keys.all { it in available }) { "count for unavailable email domain" }
        return Email(custom, hidden, usage)
    }

    private fun readObject(reader: JsonReader, field: (String) -> Unit) {
        reader.beginObject()
        val seen = HashSet<String>()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(seen.add(key)) { "duplicate lexicon field" }
            field(key)
        }
        reader.endObject()
    }

    private fun readString(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING) { "expected a string" }
        return reader.nextString()
    }

    private fun readLong(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER) { "expected an integer" }
        val text = reader.nextString()
        require(text.isNotEmpty() && text.all { it in '0'..'9' }) { "expected a non-negative integer" }
        return requireNotNull(text.toLongOrNull()) { "integer is out of range" }
    }

    private fun readValues(reader: JsonReader, kind: UserLexicon.Kind): Set<String> {
        reader.beginArray()
        val values = ArrayList<String>()
        while (reader.hasNext()) {
            require(values.size < MAX_ENTRIES) { "too many lexicon entries" }
            values.add(readString(reader))
        }
        reader.endArray()
        return normalizeValues(values, kind)
    }

    private fun normalizeValues(values: Collection<String>, kind: UserLexicon.Kind): Set<String> {
        require(values.size <= MAX_ENTRIES) { "too many lexicon entries" }
        val normalized = LinkedHashMap<String, String>()
        for (raw in values.sorted()) {
            val value = requireNotNull(UserLexicon.normalize(kind, raw)) { "invalid lexicon entry" }
            val identity = if (kind == UserLexicon.Kind.ENGLISH) EnglishKey.normalize(value) else value
            normalized.putIfAbsent(identity, value)
        }
        return normalized.values.toCollection(LinkedHashSet())
    }

    private fun storedSet(snapshot: Map<String, *>, key: String): Set<String> {
        val raw = snapshot[key] ?: return emptySet()
        require(raw is Set<*> && raw.all { it is String }) { "invalid stored lexicon" }
        return raw.filterIsInstance<String>().toSet()
    }

    private fun validateDictionary(text: String): Boolean {
        require(text.count { it == '\n' } <= MAX_ENTRIES + 1) { "too many dictionary rows" }
        return UserModel.validateText(text)
    }

    private fun validateLearning(text: String) {
        require(text.count { it == '\n' } <= MAX_ENTRIES + 1) { "too many learning rows" }
        UserLearning.validateText(text)
    }

    private fun readStore(file: File, empty: String): String {
        if (!file.exists()) return empty
        if (!file.isFile) throw IOException("lexicon path is not a file")
        return file.inputStream().use { decodeUtf8(readBounded(it)) }.ifEmpty { empty }
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val value = input.read()
                if (value < 0) break
                if (output.size() == MAX_BYTES) throw IOException("lexicon file is too large")
                output.write(value)
            } else {
                if (count > MAX_BYTES - output.size()) throw IOException("lexicon file is too large")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun encode(data: Data): ByteArray {
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                if (output.size() == MAX_BYTES) throw IOException("lexicon file is too large")
                output.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > MAX_BYTES - output.size()) throw IOException("lexicon file is too large")
                output.write(bytes, offset, length)
            }
        }
        JsonWriter(OutputStreamWriter(bounded, Charsets.UTF_8)).use { writer ->
            writer.setIndent("  ")
            writer.beginObject().name("format").value(FORMAT).name("version").value(VERSION)
            data.chinese?.let {
                writer.name("chinese").beginObject().name("userdb").value(it.userdb)
                    .name("userlearn").value(it.userlearn).endObject()
            }
            data.english?.let {
                writer.name("english").beginObject().name("words")
                writeValues(writer, it)
                writer.endObject()
            }
            data.email?.let {
                writer.name("email").beginObject().name("domains")
                writeValues(writer, it.domains)
                writer.name("disabledDefaults")
                writeValues(writer, it.disabledDefaults)
                writer.name("counts").beginObject()
                for ((domain, count) in it.counts.toSortedMap()) writer.name(domain).value(count)
                writer.endObject().endObject()
            }
            writer.endObject()
        }
        return output.toByteArray()
    }

    private fun writeValues(writer: JsonWriter, values: Set<String>) {
        writer.beginArray()
        for (value in values.sorted()) writer.value(value)
        writer.endArray()
    }
}
