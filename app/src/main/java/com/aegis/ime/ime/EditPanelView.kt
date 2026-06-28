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

import com.aegis.ime.ime.theme.ImePalette
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Actions the text-editing panel reports back to the IME service. */
enum class EditAction { UP, DOWN, LEFT, RIGHT, START_SELECT, DELETE, COPY, CUT, SELECT_ALL, HOME, END, PASTE, BACK }

/**
 * Text-editing panel (issue #4): a cursor D-pad with a center "开始选择" toggle,
 * a right column 删除/复制/剪切 (复制/剪切 disabled without a selection), and a bottom row 行首/全选/行尾/粘贴.
 * Pure UI — the service maps [EditAction]s onto the InputConnection.
 */
class EditPanelView(context: Context) : LinearLayout(context), ResettablePanel {

    var onAction: (EditAction) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private val copyBtn: TextView
    private val cutBtn: TextView
    private val selectBtn: TextView

    /** F1: recolour from the Monet palette (every button text → onSurface; disabled copy/cut stays muted). */
    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg) // P-A: see init
        recolor(this)
        setHasSelection(copyBtn.isEnabled)
    }

    private fun recolor(v: View) {
        when (v) {
            is TextView -> v.setTextColor(palette.keyLabel)
            is android.view.ViewGroup -> for (i in 0 until v.childCount) recolor(v.getChildAt(i))
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg) // P-A: panel floor == the strip/keyboard floor (no top seam)

        // Title bar
        addView(
            btn("‹  文字编辑", EditAction.BACK, big = false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, 0, 0) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40)),
        )

        // Middle: D-pad (left) + delete/copy/cut column (right)
        val mid = LinearLayout(context).apply { orientation = HORIZONTAL }
        val dpad = LinearLayout(context).apply { orientation = VERTICAL }
        selectBtn = btn("开始选择", EditAction.START_SELECT)
        dpad.addView(dpadRow(null, btn("↑", EditAction.UP), null), rowLp())
        dpad.addView(dpadRow(btn("←", EditAction.LEFT), selectBtn, btn("→", EditAction.RIGHT)), rowLp())
        dpad.addView(dpadRow(null, btn("↓", EditAction.DOWN), null), rowLp())
        mid.addView(dpad, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))

        copyBtn = btn("复制", EditAction.COPY)
        cutBtn = btn("剪切", EditAction.CUT)
        val rightCol = LinearLayout(context).apply { orientation = VERTICAL }
        rightCol.addView(btn("⌫ 删除", EditAction.DELETE), rowLp())
        rightCol.addView(copyBtn, rowLp())
        rightCol.addView(cutBtn, rowLp())
        mid.addView(rightCol, LayoutParams(0, LayoutParams.MATCH_PARENT, 2f))
        addView(mid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom row
        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
        bottom.addView(btn("|◀ 行首", EditAction.HOME), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(btn("☑ 全选", EditAction.SELECT_ALL), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(btn("行尾 ▶|", EditAction.END), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(btn("⊞ 粘贴", EditAction.PASTE), LayoutParams(0, LayoutParams.MATCH_PARENT, 2f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))

        setHasSelection(false)
    }

    /** Enable 复制/剪切 only when there is a selection. */
    fun setHasSelection(has: Boolean) {
        for (b in listOf(copyBtn, cutBtn)) {
            b.isEnabled = has
            b.setTextColor(if (has) palette.keyLabel else palette.disabled)
        }
    }

    fun setSelecting(selecting: Boolean) {
        selectBtn.text = if (selecting) "结束选择" else "开始选择"
    }

    /** P7 (#19): on dismissal, drop selection mode so the panel reopens showing "开始选择". The D-pad panel
     *  holds no tab/scroll state; the host re-syncs its own `selecting` flag when it next opens the panel. */
    override fun resetToDefault() = setSelecting(false)

    // P7 test seam.
    internal fun selectingLabelForTest(): CharSequence = selectBtn.text

    private fun rowLp() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun dpadRow(left: View?, center: View, right: View?): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(left ?: spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(center, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(right ?: spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

    private fun spacer(): View = View(context)

    private fun btn(label: String, action: EditAction, big: Boolean = true): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 17f else 16f)
        setTextColor(palette.keyLabel)
        isClickable = true
        setOnClickListener { onAction(action) }
    }
}
