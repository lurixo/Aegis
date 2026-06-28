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

package com.aegis.ime.ime

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.engine.EmojiAssociations
import com.aegis.ime.engine.InputAssociations
import com.aegis.ime.engine.SymbolAssociations
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.EmojiVariants
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputAssociationReachabilityTest {

    private class Host : ImeHost {
        var text = ""
        override fun commitText(text: CharSequence) { this.text += text }
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = text.takeLast(n)
    }

    private val dictionaryRows = listOf(
        EngineFixture.Row("yinhao", "引号", 900),
        EngineFixture.Row("yingwenyinhao", "英文引号", 900),
        EngineFixture.Row("danyinhao", "单引号", 900),
        EngineFixture.Row("juhao", "句号", 900),
        EngineFixture.Row("kuohao", "括号", 900),
        EngineFixture.Row("zhongkuohao", "中括号", 900),
        EngineFixture.Row("dakuohao", "大括号", 900),
        EngineFixture.Row("shuijiao", "睡觉", 900),
        EngineFixture.Row("chushi", "厨师", 900),
        EngineFixture.Row("yanjing", "眼镜", 900),
        EngineFixture.Row("xiangyoupaobu", "向右跑步", 900),
        EngineFixture.Row("qianfusehuishou", "浅肤色挥手", 900),
    )

    private val engine by lazy {
        DictEngine(
            EngineFixture.build(dictionaryRows),
            EngineFixture.build(dictionaryRows.map { it.copy(key = T9Pinyin.toT9(it.key)) }),
            null,
        )
    }

    private fun controller(key: String, nine: Boolean, separated: Boolean = false): Pair<KeyboardController, Host> {
        val host = Host()
        val controller = KeyboardController(host, engine)
        controller.switchTextLayoutForTest(nine)
        val syllables = requireNotNull(T9Pinyin.segmentLetters(key)) { "Invalid pinyin: $key" }
        val input = when {
            nine -> T9Pinyin.toT9(key)
            separated -> syllables.joinToString("'")
            else -> key
        }
        input.forEach { controller.onKey(Key(it.toString(), output = it.toString())) }
        if (nine) {
            for (syllable in syllables) {
                val index = controller.expandedReadings().indexOfLast { it == syllable }
                assertTrue("$key: missing T9 reading $syllable", index >= 0)
                controller.onPickReadingIndex(index)
            }
        }
        return controller to host
    }

    private val supportedGlyphs by lazy {
        SymbolCatalog.categories.flatMap { it.symbols }.toSet() +
            EmojiCatalog.categories.flatMap { it.emoji }
                .flatMap { EmojiVariants.genderForms(it) }
                .flatMap { EmojiVariants.skinForms(it) }
    }

    private val routes by lazy {
        val names = LinkedHashMap<String, String>()
        for (row in SymbolAssociations.rows()) {
            for (glyph in row.glyphList) names.putIfAbsent(glyph, row.primaryKey)
        }
        for (row in EmojiAssociations.rows()) names.putIfAbsent(row.emoji, row.primaryKey)
        for ((key, glyphs) in InputAssociations.entriesForTest()) {
            for (glyph in glyphs) names.putIfAbsent(glyph, key)
        }
        assertTrue("Catalog glyphs without names: ${supportedGlyphs - names.keys}", names.keys.containsAll(supportedGlyphs))
        names.filterKeys { it in supportedGlyphs }.entries.groupBy({ it.value }, { it.key })
    }

    private fun replayCatalog(nine: Boolean) {
        var reached = 0
        for ((key, glyphs) in routes) {
            val (controller, _) = controller(key, nine)
            val offered = controller.candidateWords()
            for (glyph in glyphs) {
                assertTrue("${if (nine) 9 else 26}-key '$key': $glyph is missing from $offered", glyph in offered)
                reached++
            }
        }
        assertEquals(supportedGlyphs.size, reached)
        println("${if (nine) 9 else 26}-key DictEngine catalog replay: ${routes.size} readings, $reached glyphs reachable")
    }

    @Test fun every_catalog_glyph_is_reachable_with_the_26_key_decoder() = replayCatalog(nine = false)

    @Test fun every_catalog_glyph_is_reachable_with_the_9_key_decoder() = replayCatalog(nine = true)

    @Test fun chinese_punctuation_names_exclude_english_forms_in_both_layouts() {
        val corpus = listOf(
            Triple("yinhao", listOf("“", "”"), listOf("\"")),
            Triple("danyinhao", listOf("‘", "’"), listOf("'")),
            Triple("juhao", listOf("。"), listOf(".")),
            Triple("kuohao", listOf("（", "）"), listOf("(", ")")),
            Triple("zhongkuohao", listOf("［", "］"), listOf("[", "]")),
            Triple("dakuohao", listOf("｛", "｝"), listOf("{", "}")),
            Triple("jiankuohao", listOf("＜", "＞"), listOf("<", ">")),
            Triple("yingwenyinhao", listOf("\""), listOf("“", "”")),
            Triple("yingwendanyinhao", listOf("'"), listOf("‘", "’")),
            Triple("yingwenjuhao", listOf("."), listOf("。")),
        )
        for (nine in listOf(false, true)) {
            for ((key, expected, excluded) in corpus) {
                val words = controller(key, nine, separated = !nine).first.candidateWords()
                for (glyph in expected) assertTrue("$key must offer $glyph with nine=$nine", glyph in words)
                for (glyph in excluded) assertFalse("$key must exclude $glyph with nine=$nine", glyph in words)
            }
        }
    }

    @Test fun all_matching_emoji_remain_selectable_beyond_three_in_both_layouts() {
        val expected = listOf("😴", "🥟", "🛌", "💤")
        for (nine in listOf(false, true)) {
            val (controller, host) = controller("shuijiao", nine)
            val words = controller.candidateWords()
            assertEquals("睡觉", words.first())
            assertTrue(words.containsAll(expected))
            controller.onPickCandidate(words.indexOf("💤"))
            assertEquals("💤", host.text)
            assertTrue(controller.candidateWords().isEmpty())
        }
    }

    @Test fun newly_mapped_names_and_qualified_variants_commit_in_both_layouts() {
        val corpus = mapOf(
            "chushi" to "🧑‍🍳",
            "yanjing" to "👓",
            "xiangyoupaobu" to "🏃‍➡️",
            "qianfusehuishou" to "👋🏻",
            "nvxingchushi" to "👩‍🍳",
            "zhongshenfusenvxingchushi" to "👩🏾‍🍳",
            "yingwenwenhao" to "?",
            "shupaijuhao" to "︒",
            "changayin" to "ɑː",
            "changoyin" to "ɔː",
            "changyangyuanyin" to "ɜː",
            "changqianbibuyuanchunyuanyin" to "iː",
            "changwuyin" to "uː",
            "qingyinhoucayin" to "ʃ",
            "zhuoyinhoucayin" to "ʒ",
            "qingyinhousecayin" to "tʃ",
            "zhuoyinhousecayin" to "dʒ",
            "qingyinhousecayinhezi" to "ʧ",
            "zhuoyinhousecayinhezi" to "ʤ",
        )
        for (nine in listOf(false, true)) {
            for ((key, glyph) in corpus) {
                val (controller, host) = controller(key, nine)
                val index = controller.candidateWords().indexOf(glyph)
                assertTrue("$key: $glyph missing with nine=$nine", index >= 0)
                controller.onPickCandidate(index)
                assertEquals(glyph, host.text)
            }
        }
    }
}
