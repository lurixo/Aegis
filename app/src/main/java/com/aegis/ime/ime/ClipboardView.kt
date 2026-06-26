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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ClipboardView(context: Context) : LinearLayout(context) {

    var onPick: (String) -> Unit = {}
    var onBack: () -> Unit = {}
    var historyProvider: () -> List<String> = { emptyList() }
    var phraseProvider: () -> List<String> = { emptyList() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var tab = Tab.CLIPBOARD
    private val listColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val clipTab = tabButton("剪贴板") { switchTo(Tab.CLIPBOARD) }
    private val phraseTab = tabButton("常用语") { switchTo(Tab.PHRASE) }

    private enum class Tab { CLIPBOARD, PHRASE }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFF7F8FA.toInt())

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(0xFFE6E9ED.toInt())
            addView(tabButton("返回") { onBack() }, LayoutParams(dp(64), LayoutParams.MATCH_PARENT))
            addView(clipTab, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(phraseTab, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(ScrollView(context).apply { addView(listColumn) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() {
        clipTab.setTypeface(null, if (tab == Tab.CLIPBOARD) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        phraseTab.setTypeface(null, if (tab == Tab.PHRASE) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        listColumn.removeAllViews()
        val entries = if (tab == Tab.CLIPBOARD) historyProvider() else phraseProvider()
        if (entries.isEmpty()) {
            listColumn.addView(emptyHint("剪贴板为空　您复制的文本会显示在这里"))
            return
        }
        for (e in entries) listColumn.addView(entryRow(e))
    }

    private fun switchTo(t: Tab) { tab = t; refresh() }

    private fun tabButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun entryRow(text: String): View = TextView(context).apply {
        this.text = text
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(0xFF202124.toInt())
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        setOnClickListener { onPick(text) }
    }

    private fun emptyHint(text: String): View = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(0xFF9AA0A6.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), dp(40), dp(16), dp(16))
    }
}
