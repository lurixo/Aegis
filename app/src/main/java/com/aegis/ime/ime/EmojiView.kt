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

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Scrollable emoji panel (issue #5). A grid of tappable emoji over a bottom bar with
 * a back-to-keyboard button and a code-point-aware backspace. Curated common set — no network.
 */
class EmojiView(context: Context) : LinearLayout(context) {

    var onEmoji: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFF7F8FA.toInt())

        val grid = GridLayout(context).apply {
            columnCount = COLUMNS
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
        }
        for (e in EMOJI) grid.addView(emojiCell(e))

        val scroll = ScrollView(context).apply {
            addView(grid)
            isFillViewport = true
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
    }

    private fun emojiCell(emoji: String): TextView = TextView(context).apply {
        text = emoji
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        val p = dp(8)
        setPadding(0, p, 0, p)
        isClickable = true
        setOnClickListener { onEmoji(emoji) }
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(0xFFE6E9ED.toInt())
        addView(barButton("返回") { onBack() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(barButton("⌫") { onBackspace() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private companion object {
        const val COLUMNS = 8

        /** Curated common emoji (faces, gestures, hearts, symbols, objects). */
        val EMOJI: List<String> = (
            "😀 😁 😂 🤣 😃 😄 😅 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😜 🤪 😝 " +
                "🤑 🤗 🤭 🤫 🤔 🤐 😐 😑 😶 😏 😒 🙄 😬 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 " +
                "😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 " +
                "😣 😞 😓 😩 😫 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😻 🙀 😿 😾 " +
                "👍 👎 👌 ✌️ 🤞 🤟 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 👏 🙌 🙏 💪 " +
                "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💖 💗 💘 💝 ✨ ⭐ 🌟 💫 🔥 💯 ✅ ❌ ❓ ❗ " +
                "🎉 🎊 🎁 🎈 🌹 🌸 🌞 🌙 ☔ ⚡ 🍎 🍔 🍟 🍦 🍺 ☕ ⚽ 🏀 🎮 🚗 ✈️ 🏠 📱 💡"
            ).trim().split(" ").filter { it.isNotBlank() }
    }
}
