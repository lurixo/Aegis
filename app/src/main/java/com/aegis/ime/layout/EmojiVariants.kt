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

package com.aegis.ime.layout

/**
 * Emoji skin-tone and gender/profession variants, produced from COMBINATION RULES over a small set of base
 * forms rather than an enumerated table — so the ~1.5k skin-toned and man/woman sequences never live in code or
 * the dictionary. The rule outputs are all RGI (Recommended for General Interchange); EmojiVariantsDataTest
 * re-derives them and checks each against the bundled Unicode emoji-test.txt v16.0 with no sampling.
 *
 * - Skin tone: append one of the five [SKIN_TONES] modifiers after the emoji's first scalar (dropping a trailing
 *   VS16 there) — [skinCapable] lists the base forms for which this yields five RGI sequences.
 * - Gender: a role's man/woman forms are either the 🧑→👨/👩 person swap ([genderSwap]) or an appended ZWJ ♂️/♀️
 *   ([genderSign]); a few standalone singles (🧒/🧓) map to fixed pairs.
 *
 * The selector composes the two axes: pick a gender form, then a skin tone of THAT form (each gendered form is
 * itself in [skinCapable]), so gendered + toned sequences are reachable without being stored.
 */
object EmojiVariants {

    private const val ZWJ = '\u200D'
    private const val VS16 = '\uFE0F'
    private const val MALE_SIGN = '\u2642'
    private const val FEMALE_SIGN = '\u2640'
    private const val PERSON = "\uD83E\uDDD1"  // 🧑 U+1F9D1
    private const val MAN = "\uD83D\uDC68"     // 👨 U+1F468
    private const val WOMAN = "\uD83D\uDC69"   // 👩 U+1F469

    /** The five Fitzpatrick skin-tone modifiers U+1F3FB..U+1F3FF, light → dark. */
    val SKIN_TONES: List<String> = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")

    /** 🧒→👦/👧, 🧓→👴/👵 — standalone person singles whose man/woman are distinct scalars, not a 🧑 swap. */
    private val genderStandalone: Map<String, Pair<String, String>> = mapOf(
        "\uD83E\uDDD2" to Pair("\uD83D\uDC66", "\uD83D\uDC67"),
        "\uD83E\uDDD3" to Pair("\uD83D\uDC74", "\uD83D\uDC75"),
    )

    /** Base forms that accept a uniform skin tone (rule output RGI-verified). */
    val skinCapable: Set<String> = tokenSet(
        "👋 🤚 🖐️ ✋ 🖖 🫱 🫲 🫳 🫴 🫷 🫸 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ " +
        "👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦵 🦶 👂 🦻 👃 👶 🧒 👦 👧 🧑 👱 👨 🧔 🧔‍♂️ 🧔‍♀️ 👨‍🦰 " +
        "👨‍🦱 👨‍🦳 👨‍🦲 👩 👩‍🦰 🧑‍🦰 👩‍🦱 🧑‍🦱 👩‍🦳 🧑‍🦳 👩‍🦲 🧑‍🦲 👱‍♀️ 👱‍♂️ 🧓 👴 👵 🙍 🙍‍♂️ 🙍‍♀️ 🙎 🙎‍♂️ 🙎‍♀️ 🙅 🙅‍♂️ 🙅‍♀️ 🙆 🙆‍♂️ 🙆‍♀️ 💁 " +
        "💁‍♂️ 💁‍♀️ 🙋 🙋‍♂️ 🙋‍♀️ 🧏 🧏‍♂️ 🧏‍♀️ 🙇 🙇‍♂️ 🙇‍♀️ 🤦 🤦‍♂️ 🤦‍♀️ 🤷 🤷‍♂️ 🤷‍♀️ 🧑‍⚕️ 👨‍⚕️ 👩‍⚕️ 🧑‍🎓 👨‍🎓 👩‍🎓 🧑‍🏫 👨‍🏫 👩‍🏫 🧑‍⚖️ 👨‍⚖️ 👩‍⚖️ 🧑‍🌾 " +
        "👨‍🌾 👩‍🌾 🧑‍🍳 👨‍🍳 👩‍🍳 🧑‍🔧 👨‍🔧 👩‍🔧 🧑‍🏭 👨‍🏭 👩‍🏭 🧑‍💼 👨‍💼 👩‍💼 🧑‍🔬 👨‍🔬 👩‍🔬 🧑‍💻 👨‍💻 👩‍💻 🧑‍🎤 👨‍🎤 👩‍🎤 🧑‍🎨 👨‍🎨 👩‍🎨 🧑‍✈️ 👨‍✈️ 👩‍✈️ 🧑‍🚀 " +
        "👨‍🚀 👩‍🚀 🧑‍🚒 👨‍🚒 👩‍🚒 👮 👮‍♂️ 👮‍♀️ 🕵️ 🕵️‍♂️ 🕵️‍♀️ 💂 💂‍♂️ 💂‍♀️ 🥷 👷 👷‍♂️ 👷‍♀️ 🫅 🤴 👸 👳 👳‍♂️ 👳‍♀️ 👲 🧕 🤵 🤵‍♂️ 🤵‍♀️ 👰 " +
        "👰‍♂️ 👰‍♀️ 🤰 🫃 🫄 🤱 👩‍🍼 👨‍🍼 🧑‍🍼 👼 🎅 🤶 🧑‍🎄 🦸 🦸‍♂️ 🦸‍♀️ 🦹 🦹‍♂️ 🦹‍♀️ 🧙 🧙‍♂️ 🧙‍♀️ 🧚 🧚‍♂️ 🧚‍♀️ 🧛 🧛‍♂️ 🧛‍♀️ 🧜 🧜‍♂️ " +
        "🧜‍♀️ 🧝 🧝‍♂️ 🧝‍♀️ 💆 💆‍♂️ 💆‍♀️ 💇 💇‍♂️ 💇‍♀️ 🚶 🚶‍♂️ 🚶‍♀️ 🚶‍➡️ 🚶‍♀️‍➡️ 🚶‍♂️‍➡️ 🧍 🧍‍♂️ 🧍‍♀️ 🧎 🧎‍♂️ 🧎‍♀️ 🧎‍➡️ 🧎‍♀️‍➡️ 🧎‍♂️‍➡️ 🧑‍🦯 🧑‍🦯‍➡️ 👨‍🦯 👨‍🦯‍➡️ 👩‍🦯 " +
        "👩‍🦯‍➡️ 🧑‍🦼 🧑‍🦼‍➡️ 👨‍🦼 👨‍🦼‍➡️ 👩‍🦼 👩‍🦼‍➡️ 🧑‍🦽 🧑‍🦽‍➡️ 👨‍🦽 👨‍🦽‍➡️ 👩‍🦽 👩‍🦽‍➡️ 🏃 🏃‍♂️ 🏃‍♀️ 🏃‍➡️ 🏃‍♀️‍➡️ 🏃‍♂️‍➡️ 💃 🕺 🕴️ 🧖 🧖‍♂️ 🧖‍♀️ 🧗 🧗‍♂️ 🧗‍♀️ 🏇 🏂 " +
        "🏌️ 🏌️‍♂️ 🏌️‍♀️ 🏄 🏄‍♂️ 🏄‍♀️ 🚣 🚣‍♂️ 🚣‍♀️ 🏊 🏊‍♂️ 🏊‍♀️ ⛹️ ⛹️‍♂️ ⛹️‍♀️ 🏋️ 🏋️‍♂️ 🏋️‍♀️ 🚴 🚴‍♂️ 🚴‍♀️ 🚵 🚵‍♂️ 🚵‍♀️ 🤸 🤸‍♂️ 🤸‍♀️ 🤽 🤽‍♂️ 🤽‍♀️ " +
        "🤾 🤾‍♂️ 🤾‍♀️ 🤹 🤹‍♂️ 🤹‍♀️ 🧘 🧘‍♂️ 🧘‍♀️ 🛀 🛌 👭 👫 👬 💏 💑 ",
    )

    /** Neutral role forms whose man/woman are the 🧑→👨/👩 person swap (RGI-verified). */
    val genderSwap: Set<String> = tokenSet(
        "🧑 🧑‍🦰 🧑‍🦱 🧑‍🦳 🧑‍🦲 🧑‍⚕️ 🧑‍🎓 🧑‍🏫 🧑‍⚖️ 🧑‍🌾 🧑‍🍳 🧑‍🔧 🧑‍🏭 🧑‍💼 🧑‍🔬 🧑‍💻 🧑‍🎤 🧑‍🎨 🧑‍✈️ 🧑‍🚀 🧑‍🚒 🧑‍🍼 🧑‍🦯 🧑‍🦯‍➡️ 🧑‍🦼 🧑‍🦼‍➡️ 🧑‍🦽 🧑‍🦽‍➡️ ",
    )

    /** Neutral role forms whose man/woman append ZWJ ♂️/♀️ (RGI-verified). */
    val genderSign: Set<String> = tokenSet(
        "👱 🧔 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵️ 💂 👷 👳 🤵 👰 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 " +
        "🚶 🧍 🧎 🏃 👯 🧖 🧗 🏌️ 🏄 🚣 🏊 ⛹️ 🏋️ 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘 ",
    )

    /** Insert [tone] after the first scalar, dropping a trailing VS16 there (the Unicode modifier rule). */
    fun applyTone(base: String, tone: String): String {
        val first = base.offsetByCodePoints(0, 1)
        val rest = if (first < base.length && base[first] == VS16) first + 1 else first
        return base.substring(0, first) + tone + base.substring(rest)
    }

    /** The gender forms shown in the selector's gender row: [neutral, man, woman] — or just [base] if none. */
    fun genderForms(base: String): List<String> = when {
        base in genderSwap -> listOf(base, base.replaceFirst(PERSON, MAN), base.replaceFirst(PERSON, WOMAN))
        base in genderSign -> listOf(base, base + ZWJ + MALE_SIGN + VS16, base + ZWJ + FEMALE_SIGN + VS16)
        base in genderStandalone -> genderStandalone.getValue(base).let { listOf(base, it.first, it.second) }
        else -> listOf(base)
    }

    /** The skin-tone forms shown in the selector's tone row for [form]: [default, tone1..tone5] — or [form]. */
    fun skinForms(form: String): List<String> =
        if (form in skinCapable) listOf(form) + SKIN_TONES.map { applyTone(form, it) } else listOf(form)

    /** Whether [base] has any long-press variants (skin tone and/or gender) to offer. */
    fun hasVariants(base: String): Boolean =
        base in skinCapable || base in genderSwap || base in genderSign || base in genderStandalone

    private fun tokenSet(s: String): Set<String> =
        s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
}
