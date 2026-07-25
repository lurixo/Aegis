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

package com.aegis.ime.decoder

import com.aegis.ime.dict.CharBigramLM
import org.junit.Assert.assertEquals
import org.junit.Test

class LmHoistEquivalenceTest {

    private fun cp(c: Char) = c.code

    private fun lm(): CharBigramLM {
        val uni = mapOf(
            '词' to 800L, '库' to 900L, '苦' to 850L, '哭' to 800L, '酷' to 700L,
            '不' to 950L, '是' to 950L, '时' to 920L, '实' to 900L, '事' to 860L,
            '想' to 930L, '相' to 900L, '向' to 980L, '香' to 800L,
            '九' to 900L, '就' to 880L, '键' to 900L, '见' to 880L, '间' to 840L,
            '字' to 900L, '自' to 860L, '子' to 880L,
        ).mapKeys { it.key.code }
        val bi = mapOf(
            (cp('词') to cp('库')) to 5000L,
            (cp('不') to cp('是')) to 5000L,
            (cp('想') to cp('哭')) to 5000L,
            (cp('九') to cp('键')) to 3000L,
            (cp('不') to cp('时')) to 200L,
            (cp('想') to cp('相')) to 400L,
        )
        return EngineFixture.buildLm(uni, bi)
    }

    private val cases = listOf(
        "ku" to "", "ku" to "想", "ku" to "词", "ku" to "不",
        "shi" to "", "shi" to "不", "shi" to "我",
        "ci" to "", "ci" to "词",
        "jian" to "", "jian" to "九",
        "zi" to "", "zi" to "字",
        "xiang" to "", "xiang" to "想",
        "xiangku" to "", "xiangku" to "想",
        "bushi" to "", "bushi" to "不",
        "jiujian" to "",
    )

    private fun report(): String {
        val decoder = PinyinDecoder(EngineFixture.dict(), lm(), lambda = 1.0, contextWeight = 2.0)
        val sb = StringBuilder()
        for ((input, ctx) in cases) {
            sb.append("decode|$input|$ctx -> ")
                .append(decoder.decode(input, 12, ctx).joinToString(",")).append('\n')
            sb.append("covered|$input|$ctx -> ")
                .append(decoder.decodeCovered(input, 12, context = ctx).joinToString(",") { it.word }).append('\n')
            sb.append("atomic|$input|$ctx -> ")
                .append(decoder.decodeCoveredAtomic(input, 12, context = ctx).joinToString(",") { it.word }).append('\n')
        }
        return sb.toString()
    }

    private fun boundedGolden(limit: Int): String =
        GOLDEN.trim().lineSequence().joinToString("\n") { line ->
            val marker = " -> "
            val split = line.indexOf(marker)
            if (split < 0) line
            else line.take(split + marker.length) +
                line.drop(split + marker.length).split(',').take(limit).joinToString(",")
        }

    @Test fun optimized_decoder_output_matches_the_pre_optimization_order_within_the_bound() {
        assertEquals(boundedGolden(12), report().trim())
    }

    @Test fun repeated_and_interleaved_decodes_do_not_leak_state_across_calls() {
        val decoder = PinyinDecoder(EngineFixture.dict(), lm(), lambda = 1.0, contextWeight = 2.0)
        val a1 = decoder.decode("ku", 12, "想")
        val b = decoder.decode("shi", 12, "不")
        val a2 = decoder.decode("ku", 12, "想")
        val a3 = decoder.decode("ku", 12, "")
        assertEquals(a1, a2)
        assertEquals(a1, decoder.decode("ku", 12, "想"))
        assertEquals(b, decoder.decode("shi", 12, "不"))
        org.junit.Assert.assertNotEquals(a1, a3)
    }

    private companion object {
        const val GOLDEN = """
decode|ku| -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
covered|ku| -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
atomic|ku| -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
decode|ku|想 -> 哭,库,苦,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
covered|ku|想 -> 哭,库,苦,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
atomic|ku|想 -> 哭,库,苦,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
decode|ku|词 -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
covered|ku|词 -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
atomic|ku|词 -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
decode|ku|不 -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
covered|ku|不 -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
atomic|ku|不 -> 库,苦,哭,酷,裤,窟,𠁤,𠁥,𠁦,𠁧
decode|shi| -> 是,时,实,事,市,十,始,试,视,𠃦,𠃧,𠃨
covered|shi| -> 是,时,实,事,市,十,始,试,视,𠃦,𠃧,𠃨
atomic|shi| -> 是,时,实,事,市,十,始,试,视,𠃦,𠃧,𠃨
decode|shi|不 -> 是,时,实,事,市,十,始,试,视,𠃦,𠃧,𠃨
covered|shi|不 -> 是,时,实,事,实现,市,十,始,试,视,𠃦,𠃧
atomic|shi|不 -> 是,时,实,事,市,十,始,试,视,𠃦,𠃧,𠃨
decode|shi|我 -> 是,时,实,事,市,十,始,试,视,𠃦,𠃧,𠃨
covered|shi|我 -> 是,时,实,事,实现,市,十,始,试,视,𠃦,𠃧
atomic|shi|我 -> 是,时,实,事,市,十,始,试,视,𠃦,𠃧,𠃨
decode|ci| -> 次,此,词,刺,辞,磁,慈,茨,瓷,赐,雌,祠
covered|ci| -> 次,此,词库,词,刺,辞,磁,慈,茨,瓷,赐,雌,祠,疵,伺,𠀀,𠀁,𠀂,𠀃,𠀄,𠀅,𠀆,𠀇,𠀈,𠀉,𠀊,𠀋,𠀌,𠀍,𠀎,𠀏
atomic|ci| -> 次,此,词,刺,辞,磁,慈,茨,瓷,赐,雌,祠,疵,伺,𠀀,𠀁,𠀂,𠀃,𠀄,𠀅,𠀆,𠀇,𠀈,𠀉,𠀊,𠀋,𠀌,𠀍,𠀎,𠀏
decode|ci|词 -> 词,次,此,刺,辞,磁,慈,茨,瓷,赐,雌,祠
covered|ci|词 -> 词,词库,次,此,刺,辞,磁,慈,茨,瓷,赐,雌,祠,疵,伺,𠀀,𠀁,𠀂,𠀃,𠀄,𠀅,𠀆,𠀇,𠀈,𠀉,𠀊,𠀋,𠀌,𠀍,𠀎,𠀏
atomic|ci|词 -> 词,次,此,刺,辞,磁,慈,茨,瓷,赐,雌,祠,疵,伺,𠀀,𠀁,𠀂,𠀃,𠀄,𠀅,𠀆,𠀇,𠀈,𠀉,𠀊,𠀋,𠀌,𠀍,𠀎,𠀏
decode|jian| -> 键,见,件,间,简,减,建,𠄄,𠄅,𠄆
covered|jian| -> 键,见,件,间,简,减,建,𠄄,𠄅,𠄆
atomic|jian| -> 键,见,件,间,简,减,建,𠄄,𠄅,𠄆
decode|jian|九 -> 键,见,间,件,简,减,建,𠄄,𠄅,𠄆
covered|jian|九 -> 键,见,间,件,简,减,建,𠄄,𠄅,𠄆
atomic|jian|九 -> 键,见,间,件,简,减,建,𠄄,𠄅,𠄆
decode|zi| -> 字,子,自,紫,资,仔,籽,𠃒,𠃓,𠃔
covered|zi| -> 字,子,自,紫,资,仔,籽,𠃒,𠃓,𠃔
atomic|zi| -> 字,子,自,紫,资,仔,籽,𠃒,𠃓,𠃔
decode|zi|字 -> 字,子,自,紫,资,仔,籽,𠃒,𠃓,𠃔
covered|zi|字 -> 字,子,自,紫,资,仔,籽,𠃒,𠃓,𠃔
atomic|zi|字 -> 字,子,自,紫,资,仔,籽,𠃒,𠃓,𠃔
decode|xiang| -> 向,想,相,像,香,响,享,想哭
covered|xiang| -> 向,想,相,像,想哭,香,响,享,现,下,县,西,西安,限,先,显,夏,鲜,霞,险,嫌,𠃰,𠃱,𠃲
atomic|xiang| -> 向,想,相,像,香,响,享
decode|xiang|想 -> 相,向,想,香,像,响,享,想哭
covered|xiang|想 -> 相,向,想,想哭,香,像,响,享,现,下,县,西,西安,限,先,显,夏,鲜,霞,险,嫌,𠃰,𠃱,𠃲
atomic|xiang|想 -> 相,向,想,香,像,响,享
decode|xiangku| -> 想哭
covered|xiangku| -> 想哭,向,想,相,现,下,像,县,西,香,限,先,西安,响,显,夏,享,鲜,霞,险,嫌,𠃰,𠃱,𠃲
atomic|xiangku| -> 想哭,向库,想库,相库,像库,向苦,想苦,相苦,向,想,相,像,香,响,享
decode|xiangku|想 -> 想哭
covered|xiangku|想 -> 想哭,相,向,想,香,现,下,像,县,西,限,先,西安,响,显,夏,享,鲜,霞,险,嫌,𠃰,𠃱,𠃲
atomic|xiangku|想 -> 想哭,相库,相苦,相哭,相酷,向库,想库,向苦,相,向,想,香,像,响,享
decode|bushi| -> 不是
covered|bushi| -> 不是,不,部,布,步,补,捕,卜,哺,埠,簿,𠃜,𠃝,𠃞
atomic|bushi| -> 不是,不时,部是,部时,不实,部实,布是,步是,不,部,布,步,补,捕,卜,哺,埠,簿,𠃜,𠃝,𠃞
decode|bushi|不 -> 不是
covered|bushi|不 -> 不是,不,部,布,步,补,捕,卜,哺,埠,簿,𠃜,𠃝,𠃞
atomic|bushi|不 -> 不是,不时,不实,不事,不市,不十,部是,布是,不,部,布,步,补,捕,卜,哺,埠,簿,𠃜,𠃝,𠃞
decode|jiujian| -> 九键
covered|jiujian| -> 九键,九,就,久,酒,旧,救
atomic|jiujian| -> 九键,就键,九见,就见,久键,久见,酒键,酒见,九,就,久,酒,旧,救
"""
    }
}
