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
 * Curated emoji catalogue for the C8 表情 panel, split into categories: 黄脸 / 手势 / 旗帜
 * Pure data — no Android deps, no network — so the category contents are unit-testable.
 */
object EmojiCatalog {

    data class Category(val id: String, val title: String, val emoji: List<String>)

    val categories: List<Category> = listOf(
        Category("face", "黄脸", tokens(
            "😀 😁 😂 🤣 😃 😄 😅 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😜 🤪 😝 " +
                "🤑 🤗 🤭 🤫 🤔 🤐 😐 😑 😶 😏 😒 🙄 😬 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 " +
                "😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 " +
                "😣 😞 😓 😩 😫 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😻 🙀 😿 😾 " +
                "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💖 💗 💘 💝 ✨ ⭐ 🌟 💫 🔥 💯",
        )),
        Category("hand", "手势", tokens(
            "👍 👎 👌 ✌️ 🤞 🤟 🤙 🤘 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 👏 🙌 🙏 💪 " +
                "🤲 🤜 🤛 ✊ 👊 🫶 🫰 🤏 👐 🙆 🙅 💁 🙋 🤦 🤷",
        )),
        Category("flag", "旗帜", tokens(
            "🇨🇳 🇭🇰 🇲🇴 🇹🇼 🇺🇸 🇬🇧 🇯🇵 🇰🇷 🇫🇷 🇩🇪 🇮🇹 🇪🇸 🇷🇺 🇨🇦 🇦🇺 🇧🇷 🇮🇳 🇸🇬 🇲🇾 🇹🇭 " +
                "🇻🇳 🇵🇭 🇮🇩 🇳🇱 🇨🇭 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇵🇹 🇬🇷 🇹🇷 🇵🇱 🇺🇦 🇲🇽 🇦🇷 🇿🇦 🇪🇬 🇸🇦 🇦🇪 " +
                "🏁 🚩 🏳️ 🏴 🏳️‍🌈",
        )),
    )

    private fun tokens(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
