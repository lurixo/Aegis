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

import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.nio.charset.CodingErrorAction

internal object UserDataTransfer {
    const val USER_DICTIONARY_HEADER = "aegis-userdb 1"

    sealed interface UserDictionaryRow {
        data class Word(val word: String, val count: Int, val lastUsed: Long) : UserDictionaryRow
        data class Bigram(val previous: String, val word: String, val count: Int) : UserDictionaryRow
        data class Reading(val reading: String, val word: String) : UserDictionaryRow
    }

    sealed interface PhraseRow {
        data class Category(val name: String) : PhraseRow
        data class Phrase(val text: String) : PhraseRow
        data class Note(val note: String) : PhraseRow
    }

    fun readUserDictionary(input: InputStream, accept: (UserDictionaryRow) -> Unit): Long {
        val reader = BoundedLineReader(strictReader(input))
        if (reader.readLine() != USER_DICTIONARY_HEADER) throw IOException("unsupported user dictionary header")
        var records = 0L
        while (true) {
            val line = reader.readLine() ?: break
            val fields = line.split('\t')
            val row = when (fields.firstOrNull()) {
                "W" -> {
                    if (fields.size != 4 || !validWord(fields[1])) throw IOException("invalid user dictionary word row")
                    val count = fields[2].toIntOrNull()
                    val lastUsed = fields[3].toLongOrNull()
                    if (count == null || count !in 1..MAX_COUNT || lastUsed == null || lastUsed < 0L) {
                        throw IOException("invalid user dictionary word values")
                    }
                    UserDictionaryRow.Word(fields[1], count, lastUsed)
                }
                "B" -> {
                    if (fields.size != 4 || !validWord(fields[1]) || !validWord(fields[2])) {
                        throw IOException("invalid user dictionary bigram row")
                    }
                    val count = fields[3].toIntOrNull()
                    if (count == null || count !in 1..MAX_COUNT) {
                        throw IOException("invalid user dictionary bigram count")
                    }
                    UserDictionaryRow.Bigram(fields[1], fields[2], count)
                }
                "R" -> {
                    if (fields.size != 3 || fields[1].isEmpty() || fields[1].any { it !in 'a'..'z' } ||
                        !validWord(fields[2])
                    ) {
                        throw IOException("invalid user dictionary reading row")
                    }
                    UserDictionaryRow.Reading(fields[1], fields[2])
                }
                else -> throw IOException("invalid user dictionary row")
            }
            accept(row)
            records++
        }
        return records
    }

    fun readPhrases(input: InputStream, accept: (PhraseRow) -> Unit): Long {
        val reader = BoundedLineReader(strictReader(input))
        var records = 0L
        var hasCategory = false
        var hasPhrase = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.length < 3 || line[1] != '\t') throw IOException("invalid phrase transfer row")
            val value = decode(line.substring(2)) ?: throw IOException("empty phrase transfer value")
            val row = when (line[0]) {
                'C' -> {
                    if (value.isBlank()) throw IOException("blank phrase category")
                    PhraseRow.Category(value).also { hasCategory = true; hasPhrase = false }
                }
                'P' -> {
                    if (!hasCategory || value.isBlank()) throw IOException("invalid phrase")
                    PhraseRow.Phrase(value).also { hasPhrase = true }
                }
                'N' -> {
                    if (!hasPhrase) throw IOException("note has no phrase")
                    PhraseRow.Note(value)
                }
                else -> throw IOException("invalid phrase transfer row")
            }
            accept(row)
            records++
        }
        return records
    }

    fun writer(output: OutputStream): BufferedWriter = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))

    fun writeEscaped(writer: BufferedWriter, type: Char, value: String) {
        writer.write(type.code)
        writer.write('\t'.code)
        for (character in value) {
            when (character) {
                '\\' -> writer.write("\\\\")
                '\n' -> writer.write("\\n")
                '\r' -> writer.write("\\r")
                else -> writer.write(character.code)
            }
        }
        writer.newLine()
    }

    private fun strictReader(input: InputStream): Reader {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return InputStreamReader(input, decoder)
    }

    private class BoundedLineReader(private val reader: Reader) {
        private var pending = -1

        fun readLine(): String? {
            val line = StringBuilder()
            while (true) {
                val value = if (pending >= 0) pending.also { pending = -1 } else reader.read()
                when (value) {
                    -1 -> return if (line.isEmpty()) null else line.toString()
                    '\n'.code -> return line.toString()
                    '\r'.code -> {
                        val next = reader.read()
                        if (next != '\n'.code) pending = next
                        return line.toString()
                    }
                    else -> {
                        if (line.length >= MAX_LINE_CHARS) throw IOException("transfer row is too long")
                        line.append(value.toChar())
                    }
                }
            }
        }
    }

    private fun validWord(word: String): Boolean =
        word.isNotEmpty() && word.none { it == '\t' || it == '\n' || it == '\r' }

    private fun decode(value: String): String? {
        if (value.isEmpty()) return null
        val decoded = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> decoded.append('\n')
                    'r' -> decoded.append('\r')
                    '\\' -> decoded.append('\\')
                    else -> decoded.append(value[index + 1])
                }
                index += 2
            } else {
                decoded.append(character)
                index++
            }
        }
        return decoded.toString()
    }

    private const val MAX_COUNT = 1_000_000_000
    internal const val MAX_LINE_CHARS = 1024 * 1024
}
