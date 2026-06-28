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
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Taskbar "copy bar": occupies the candidate-strip row after a clip is captured, showing
 * `[📋 + 被复制内容] | 拆 | ×`. Tapping the content 上屏s the whole entry; 拆 splits it into blocks; tapping
 * a block copies it to the aegis clipboard (not the editor, not the system clipboard); × leaves. Logic is
 * in the pure [CopyBarController]; this view only renders it and forwards taps. Existing flat styles — F
 * (MD3) restyles later.
 */
class CopyBarView(context: Context) : LinearLayout(context) {

    var onCommit: (String) -> Unit = {}     // ⑤ content → 上屏
    var onCopyBlock: (String) -> Unit = {}  // ③ block → aegis clipboard
    var onDismiss: () -> Unit = {}          // ④ × → leave

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT

    private val ctl = CopyBarController(
        commit = { onCommit(it) },
        copyToAegis = { onCopyBlock(it) },
        dismiss = { onDismiss() },
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = capsuleBg() // U-polish: floating capsule like the idle toolbar (was a flat keyboardBg row)
        setPadding(dp(14), 0, dp(14), 0)
    }

    /** U-polish: the copy bar now matches the idle toolbar's floating capsule — a rounded keySurface inset
     *  from the row edges (over the keyboardBg floor) — so capturing a clip no longer flips flat<->floating. */
    private fun capsuleBg() = InsetDrawable(
        GradientDrawable().apply { setColor(palette.keySurface); cornerRadius = ImeShapes.chipRadiusDp * density },
        dp(8), dp(5), dp(8), dp(5),
    )

    /** F1: recolour from the Monet palette (content rebuilt via render). */
    fun applyPalette(p: ImePalette) {
        palette = p
        background = capsuleBg()
        render()
    }

    /** Enter the copy-bar with the captured [text]. */
    fun show(text: String) { ctl.show(text); render() }

    private fun render() {
        removeAllViews()
        addView(icon(), lp(dp(26), dp(26)))
        if (!ctl.splitMode) {
            addView(content(ctl.content.orEmpty()), lp(0, WC, 1f))
            addView(divider(), lp(dp(1), dp(18)))
            addView(pill("拆") { ctl.toggleSplit(); render() }, lp(WC, WC))
        } else {
            val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (ctl.blocks.isEmpty()) chips.addView(TextView(context).apply {
                text = "无可拆分内容"; setTextColor(palette.keyHint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(dp(8), 0, dp(8), 0)
            }) // non-clickable placeholder (must NOT carry the content's 上屏 tap)
            for (b in ctl.blocks) chips.addView(chip(b) { ctl.tapBlock(b) })
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, WC, 1f))
            addView(pill("收") { ctl.toggleSplit(); render() }, lp(WC, WC))
        }
        addView(pill("×") { ctl.close() }, lp(dp(34), WC))
    }

    private fun icon(): View = TextView(context).apply {
        text = "📋"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setOnClickListener { ctl.tapContent() } // the whole left block (📋 + 内容) 上屏s
    }

    private fun content(s: String): TextView = TextView(context).apply {
        text = s
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.candidateText)
        setPadding(dp(8), 0, dp(8), 0)
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { ctl.tapContent() } // ⑤ 上屏 (CopyBarController fires commit; InputView hides the bar)
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.chipText) // U-polish: onSecondaryContainer pairs with the chipBg container
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply { setColor(palette.chipBg); cornerRadius = ImeShapes.chipRadiusDp * density }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WC, WC).apply { rightMargin = dp(6) }
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
        setTextColor(palette.icon)
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun divider(): View = View(context).apply { setBackgroundColor(palette.separator) }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private companion object { const val WC = LinearLayout.LayoutParams.WRAP_CONTENT }
}
