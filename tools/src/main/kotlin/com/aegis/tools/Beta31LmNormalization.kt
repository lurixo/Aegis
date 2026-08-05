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

package com.aegis.tools

import java.io.BufferedReader
import java.io.File

internal object Beta31LmNormalization {
    const val PROFILE = "beta31-9484292"

    private val toneMap: Map<Char, Char> = buildMap {
        "āáǎà".forEach { put(it, 'a') }
        "ēéěèê".forEach { put(it, 'e') }
        "īíǐì".forEach { put(it, 'i') }
        "ōóǒò".forEach { put(it, 'o') }
        "ūúǔù".forEach { put(it, 'u') }
        "üǖǘǚǜ".forEach { put(it, 'v') }
        "ńňǹ".forEach { put(it, 'n') }
        put('ḿ', 'm')
    }

    private fun stripTones(value: String): String = buildString(value.length) {
        for (character in value) append(toneMap[character] ?: character.lowercaseChar())
    }

    private fun isAsciiSyllable(value: String): Boolean =
        value.isNotEmpty() && value.all { it in 'a'..'z' }

    class Converter private constructor(
        private val tsChar: Map<String, String>,
        private val tsPhrase: Map<String, String>,
        private val variant: Map<String, String>,
        private val overrideExact: Map<String, String>,
        private val overrideAny: Map<String, String>,
    ) {
        var convertedWords = 0L
            private set
        var phraseHits = 0L
            private set
        var charHits = 0L
            private set
        var overrideHits = 0L
            private set
        var misaligned = 0L
            private set

        fun convert(word: String, syllables: List<String>): String {
            tsPhrase[word]?.let {
                phraseHits++
                convertedWords++
                return it
            }
            val codePoints = ArrayList<String>(word.length)
            var index = 0
            while (index < word.length) {
                val codePoint = word.codePointAt(index)
                codePoints.add(String(Character.toChars(codePoint)))
                index += Character.charCount(codePoint)
            }
            val aligned = codePoints.size == syllables.size
            var changed = false
            val converted = StringBuilder(word.length)
            for ((position, source) in codePoints.withIndex()) {
                val syllable = if (aligned) syllables[position] else null
                val exact = if (syllable != null) overrideExact["$source\u0000$syllable"] else null
                val target = when {
                    exact != null -> {
                        overrideHits++
                        exact
                    }
                    overrideAny.containsKey(source) -> {
                        val value = overrideAny.getValue(source)
                        if (!aligned && overrideExact.keys.any { it.startsWith(source + "\u0000") }) {
                            misaligned++
                        }
                        if (value != source) overrideHits++
                        value
                    }
                    tsChar.containsKey(source) && tsChar.getValue(source) != source -> {
                        charHits++
                        tsChar.getValue(source)
                    }
                    variant.containsKey(source) -> {
                        charHits++
                        variant.getValue(source)
                    }
                    else -> source
                }
                if (target != source) changed = true
                converted.append(target)
            }
            if (changed) convertedWords++
            return converted.toString()
        }

        companion object {
            fun load(directory: File): Converter {
                fun rows(name: String): List<Pair<String, String>> =
                    File(directory, name).readLines()
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .mapNotNull { line ->
                            val columns = line.split("\t")
                            if (columns.size >= 2) columns[0] to columns[1] else null
                        }

                val characters = HashMap<String, String>()
                for ((traditional, images) in rows("TSCharacters.txt")) {
                    characters[traditional] = images.split(" ").first()
                }
                val phrases = HashMap<String, String>()
                for ((traditional, simplified) in rows("TSPhrases.txt")) {
                    phrases[traditional] = simplified.split(" ").first()
                }
                val variants = HashMap<String, String>()
                for ((variant, simplified) in rows("variant_to_simplified.tsv")) {
                    variants[variant] = simplified
                }
                val exact = HashMap<String, String>()
                val any = HashMap<String, String>()
                for (line in File(directory, "adjudications.tsv").readLines()) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val columns = line.split("\t")
                    if (columns.size < 3) continue
                    if (columns[1] == "*") {
                        any[columns[0]] = columns[2]
                    } else {
                        exact["${columns[0]}\u0000${columns[1]}"] = columns[2]
                    }
                }
                return Converter(characters, phrases, variants, exact, any)
            }
        }
    }

    fun scan(
        reader: BufferedReader,
        converter: Converter?,
        onRow: (NormalizedDictRow) -> Unit,
        onNonAscii: () -> Unit,
    ) {
        var inData = false
        while (true) {
            val line = reader.readLine() ?: break
            if (!inData) {
                if (line.trim() == "...") inData = true
                continue
            }
            if (line.isEmpty() || line.startsWith('#')) continue
            val columns = line.split('\t')
            if (columns.size < 2) continue
            val rawWord = columns[0]
            val syllables = stripTones(columns[1]).split(' ').filter { it.isNotEmpty() }
            val word = converter?.convert(rawWord, syllables) ?: rawWord
            if (syllables.isEmpty() || syllables.any { !isAsciiSyllable(it) }) {
                onNonAscii()
                continue
            }
            onRow(
                NormalizedDictRow(
                    word = word,
                    syllables = syllables,
                    freq = columns.getOrNull(2)?.trim()?.toIntOrNull() ?: 1,
                    sourceTag = if (converter != null && word != rawWord) rawWord else "",
                ),
            )
        }
    }
}
