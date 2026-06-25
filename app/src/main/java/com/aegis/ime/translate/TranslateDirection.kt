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

package com.aegis.ime.translate

enum class TranslateMode { AUTO, ZH_EN, ZH_JA }

internal object TranslateDirection {
    const val CHINESE = "zh-CN"
    const val ENGLISH = "en"
    const val JAPANESE = "ja"

    fun hasHan(text: CharSequence): Boolean = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    fun hasKana(text: CharSequence): Boolean = text.any {
        val script = Character.UnicodeScript.of(it.code)
        script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
    }

    fun isChinese(detected: String): Boolean = detected == "zh" || detected.startsWith("zh-")

    fun misreadHan(detected: String): Boolean = !isChinese(detected) && detected != JAPANESE

    fun firstTarget(mode: TranslateMode, text: CharSequence): String = when (mode) {
        TranslateMode.AUTO, TranslateMode.ZH_EN -> if (hasHan(text) && !hasKana(text)) ENGLISH else CHINESE
        TranslateMode.ZH_JA -> if (hasHan(text) && !hasKana(text)) JAPANESE else CHINESE
    }

    fun chineseTarget(mode: TranslateMode): String = if (mode == TranslateMode.ZH_JA) JAPANESE else ENGLISH

    fun wantedTarget(mode: TranslateMode, detected: String): String? = when (mode) {
        TranslateMode.AUTO -> if (isChinese(detected)) ENGLISH else CHINESE
        TranslateMode.ZH_EN -> when {
            isChinese(detected) -> ENGLISH
            detected == ENGLISH -> CHINESE
            else -> null
        }
        TranslateMode.ZH_JA -> when {
            isChinese(detected) -> JAPANESE
            detected == JAPANESE -> CHINESE
            else -> null
        }
    }
}
