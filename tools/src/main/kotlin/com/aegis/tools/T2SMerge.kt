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

import java.io.File

enum class T2SReject {
    MISALIGNED_MAPPING,
    MISSING_TARGET_READING,
    INCOMPATIBLE_TARGET_READING,
}

data class T2SConversion(val word: String?, val written: String? = null)

class T2SMerge private constructor(
    private val tsChar: Map<String, String>,
    private val tsPhrase: Map<String, String>,
    private val variant: Map<String, String>,
    private val overrideExact: Map<String, String>,
    private val overrideAny: Map<String, String>,
    phraseRulings: List<Pair<String, String>>,
    private val bothForms: Set<String>,
    private val targetReadings: Map<String, Set<String>>,
) {
    private val overridePhrase = phraseRulings.map { (source, target) -> codePoints(source) to codePoints(target) }

    var convertedWords = 0L; private set
    var phraseHits = 0L; private set
    var charHits = 0L; private set
    var overrideHits = 0L; private set
    var rejectedMisaligned = 0L; private set
    var rejectedMissingReading = 0L; private set
    var rejectedIncompatibleReading = 0L; private set

    fun mappedSourceForms(): Set<String> {
        val out = HashSet<String>()
        for ((t, s) in tsChar) if (t != s && overrideAny[t] != t && !overrideExact.keys.any { it.startsWith(t + "\u0000") }) out.add(t)
        out.addAll(variant.keys)
        for ((c, tgt) in overrideAny) if (tgt != c) out.add(c)
        return out
    }

    fun convert(word: String, syllables: List<String>): T2SConversion {
        val source = codePoints(word)
        tsPhrase[word]?.let { target ->
            val rejection = validateChangedMappings(source, codePoints(target), syllables)
            if (rejection != null) return reject(rejection)
            phraseHits++
            if (target != word) convertedWords++
            return T2SConversion(target, writtenForm(word, target))
        }
        val ruled = arrayOfNulls<String>(source.size)
        for ((phrase, phraseTarget) in overridePhrase) {
            for (start in 0..source.size - phrase.size) {
                if (source.subList(start, start + phrase.size) == phrase) {
                    for (offset in phrase.indices) ruled[start + offset] = phraseTarget[offset]
                }
            }
        }
        val targets = ArrayList<String>(source.size)
        var localCharHits = 0L
        var localOverrideHits = 0L
        for ((idx, c) in source.withIndex()) {
            val syl = syllables.getOrNull(idx)
            val exact = if (syl != null) overrideExact["$c\u0000$syl"] else null
            val phraseRuling = ruled[idx]
            val target = when {
                phraseRuling != null -> {
                    if (phraseRuling != c) localOverrideHits++
                    phraseRuling
                }
                exact != null -> {
                    if (exact != c) localOverrideHits++
                    exact
                }
                overrideAny.containsKey(c) -> {
                    val t = overrideAny.getValue(c)
                    if (t != c) localOverrideHits++
                    t
                }
                tsChar.containsKey(c) && tsChar.getValue(c) != c -> {
                    localCharHits++
                    tsChar.getValue(c)
                }
                variant.containsKey(c) -> {
                    localCharHits++
                    variant.getValue(c)
                }
                else -> c
            }
            targets.add(target)
        }
        val targetWord = targets.joinToString("")
        val rejection = validateChangedMappings(source, codePoints(targetWord), syllables)
        if (rejection != null) return reject(rejection)
        charHits += localCharHits
        overrideHits += localOverrideHits
        if (targetWord != word) convertedWords++
        return T2SConversion(targetWord, writtenForm(word, targetWord))
    }

    private fun writtenForm(word: String, target: String): String? =
        if (target != word && word in bothForms) word else null

    private fun validateChangedMappings(
        source: List<String>,
        target: List<String>,
        syllables: List<String>,
    ): T2SReject? {
        if (source.size != syllables.size || target.size != source.size) return T2SReject.MISALIGNED_MAPPING
        for (index in source.indices) {
            if (source[index] == target[index]) continue
            val readings = targetReadings[target[index]] ?: return T2SReject.MISSING_TARGET_READING
            if (syllables[index] !in readings) return T2SReject.INCOMPATIBLE_TARGET_READING
        }
        return null
    }

    private fun reject(reason: T2SReject): T2SConversion {
        when (reason) {
            T2SReject.MISALIGNED_MAPPING -> rejectedMisaligned++
            T2SReject.MISSING_TARGET_READING -> rejectedMissingReading++
            T2SReject.INCOMPATIBLE_TARGET_READING -> rejectedIncompatibleReading++
        }
        return T2SConversion(null)
    }

    private fun codePoints(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            out.add(String(Character.toChars(cp)))
            index += Character.charCount(cp)
        }
        return out
    }

    companion object {
        fun load(dir: File, targetReadings: Map<String, Set<String>> = emptyMap()): T2SMerge {
            fun rows(name: String): List<Pair<String, String>> =
                File(dir, name).readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val c = line.split("\t")
                        if (c.size >= 2) c[0] to c[1] else null
                    }
            val ts = HashMap<String, String>()
            for ((t, images) in rows("TSCharacters.txt")) ts[t] = images.split(" ").first()
            val phrases = HashMap<String, String>()
            for ((t, s) in rows("TSPhrases.txt")) phrases[t] = s.split(" ").first()
            val variant = HashMap<String, String>()
            for ((v, s) in rows("variant_to_simplified.tsv")) variant[v] = s
            val exact = HashMap<String, String>()
            val any = HashMap<String, String>()
            val phraseRulings = ArrayList<Pair<String, String>>()
            val bothForms = HashSet<String>()
            for (line in File(dir, "adjudications.tsv").readLines()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val c = line.split("\t")
                if (c.size < 3) continue
                val sourceLength = c[0].codePointCount(0, c[0].length)
                if (c[1] == "+") {
                    require(c[2] == c[0]) {
                        "adjudications.tsv: a both-forms ruling repeats its word as the target: $line"
                    }
                    bothForms.add(c[0])
                } else if (sourceLength > 1) {
                    require(c[1] == "*" && c[2].codePointCount(0, c[2].length) == sourceLength) {
                        "adjudications.tsv: a phrase ruling covers every reading and keeps its length: $line"
                    }
                    phraseRulings.add(c[0] to c[2])
                } else if (c[1] == "*") any[c[0]] = c[2] else exact["${c[0]}\u0000${c[1]}"] = c[2]
            }
            val readings = targetReadings.mapValues { it.value.toSet() }
            return T2SMerge(ts, phrases, variant, exact, any, phraseRulings, bothForms, readings)
        }
    }
}
