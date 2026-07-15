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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RenderHarness {

    private val ctx = RuntimeEnvironment.getApplication()
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val outDir = File("build/render").apply { mkdirs() }

    private fun snap(view: View, hPx: Int, name: String) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, wPx, hPx)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.MAGENTA)
        view.draw(Canvas(bmp))
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertMeaningful(bmp, name)
    }

    private fun assertMeaningful(bmp: Bitmap, name: String) {
        val w = bmp.width; val h = bmp.height; val total = w * h
        val px = IntArray(total)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = HashMap<Int, Int>()
        var sentinel = 0
        for (p in px) { if (p == Color.MAGENTA) sentinel++ else hist[p] = (hist[p] ?: 0) + 1 }
        val painted = total - sentinel
        val fill = hist.values.maxOrNull() ?: 0
        val content = painted - fill
        assertTrue(
            "$name: rendered essentially nothing (still the magenta sentinel) — onDraw painted no surface",
            painted > total / 50,
        )
        assertTrue(
            "$name: a flat fill with nothing drawn on it (glyphs/chips/icons missing)",
            content > total / 500,
        )
        assertTrue(
            "$name: too few distinct colours — looks like flat fills with no anti-aliased content (text/icons)",
            hist.size >= 64,
        )
    }

    private fun View.hasTextLeaf(s: String): Boolean {
        val out = ArrayList<View>()
        findViewsWithText(out, s, View.FIND_VIEWS_WITH_TEXT)
        return out.isNotEmpty()
    }

    private val density = ctx.resources.displayMetrics.density
    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    private fun stitchStripAndPanel(panel: View, panelHpx: Int, name: String, pal: ImePalette) {
        val stripH = (44 * density).toInt()
        val strip = CandidateView(ctx).apply { applyPalette(pal); setContent(emptyList(), "") }
        strip.measure(exactly(wPx), exactly(stripH)); strip.layout(0, 0, wPx, stripH)
        panel.measure(exactly(wPx), exactly(panelHpx)); panel.layout(0, 0, wPx, panelHpx)
        val totalH = stripH + panelHpx
        val bmp = Bitmap.createBitmap(wPx, totalH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.MAGENTA)
        val c = Canvas(bmp)
        strip.draw(c)
        c.save(); c.translate(0f, stripH.toFloat()); panel.draw(c); c.restore()
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val stripFloor = bmp.getPixel((2 * density).toInt(), (2 * density).toInt())
        assertTrue("$name: strip floor is not keyboardBg", stripFloor == pal.keyboardBg)
        var panelSharesFloor = false
        val row = IntArray(wPx)
        var y = stripH + (8 * density).toInt()
        val step = (8 * density).toInt()
        while (y < totalH && !panelSharesFloor) {
            bmp.getPixels(row, 0, wPx, 0, y, wPx, 1)
            if (row.any { it == stripFloor }) panelSharesFloor = true
            y += step
        }
        assertTrue("$name: panel body floor differs from the strip floor (a seam)", panelSharesFloor)
    }

    private val themes = listOf("light" to ImePalette.STATIC_LIGHT, "dark" to ImePalette.STATIC_DARK)

    @Test fun seam_strip_over_symbols() {
        for ((t, pal) in themes) {
            val panel = SymbolsView(ctx).apply {
                recentProvider = { listOf("，", "。", "@") }; applyPalette(pal); openCategoryForTest(1)
            }
            stitchStripAndPanel(panel, (300 * density).toInt(), "seam_symbols_$t.png", pal)
        }
    }

    @Test fun seam_strip_over_clipboard() {
        for ((t, pal) in themes) {
            val panel = ClipboardView(ctx).apply {
                historyProvider = { listOf("第一条复制内容", "second clip on the board") }; applyPalette(pal)
            }
            stitchStripAndPanel(panel, (300 * density).toInt(), "seam_clipboard_$t.png", pal)
        }
    }

    @Test fun symbols_locked() {
        for ((t, pal) in themes) {
            val v = SymbolsView(ctx).apply {
                recentProvider = { listOf("，", "。") }; applyPalette(pal); openCategoryForTest(0); toggleLockForTest()
            }
            snap(v, (300 * density).toInt(), "symbols_locked_$t.png")
        }
    }

    @Test fun emoji_panel() {
        for ((t, pal) in themes) {
            val v = EmojiView(ctx).apply {
                recentProvider = { listOf("😀", "👍", "❤️") }
                applyPalette(pal); openCategoryForTest(1)
            }
            snap(v, (560 * density).toInt(), "emoji_$t.png")
        }
    }

    @Test fun symbols_panel() {
        for ((t, pal) in themes) {
            val v = SymbolsView(ctx).apply {
                recentProvider = { listOf("，", "。", "？", "！", "https://", "@") }
                applyPalette(pal); openCategoryForTest(0)
            }
            snap(v, (560 * ctx.resources.displayMetrics.density).toInt(), "symbols_$t.png")
        }
    }

    @Test fun symbols_chinese_marks() {
        for ((t, pal) in themes) {
            val v = SymbolsView(ctx).apply {
                applyPalette(pal); openCategoryForTest(1)
            }
            val cells = v.gridCellTextsForTest()
            assertTrue("中文 single — / … are grid cells", cells.containsAll(listOf("—", "…")))
            assertFalse("中文 dropped the wide —— / ……", cells.any { it == "——" || it == "……" })
            assertFalse("中文 has no chip bar (single-cell marks ride the grid)", v.chipBarVisibleForTest())
            snap(v, (560 * density).toInt(), "symbols_chinese_$t.png")
        }
    }

    @Test fun symbols_math() {
        val mathIndex = SymbolCatalog.categories.indexOfFirst { it.id == "math" } + 1
        for ((t, pal) in themes) {
            val v = SymbolsView(ctx).apply { applyPalette(pal); openCategoryForTest(mathIndex) }
            val cells = v.gridCellTextsForTest()
            assertTrue("$t: trig functions are grid cells", cells.containsAll(listOf("sin", "arcsin", "tanh")))
            assertTrue("$t: units are grid cells", cells.containsAll(listOf("℃", "㎏", "㎡")))
            assertFalse("$t: 数学 multi-char cells ride the grid, no chip bar", v.chipBarVisibleForTest())
            snap(v, (880 * density).toInt(), "symbols_math_$t.png")
        }
    }

    @Test fun symbols_greek() {
        val greekIndex = SymbolCatalog.categories.indexOfFirst { it.id == "greek" } + 1
        for ((t, pal) in themes) {
            val v = SymbolsView(ctx).apply { applyPalette(pal); openCategoryForTest(greekIndex) }
            val cells = v.gridCellTextsForTest()
            assertTrue("$t: lowercase α…ω present", cells.containsAll(listOf("α", "π", "ς", "ω")))
            assertTrue("$t: uppercase Α…Ω present", cells.containsAll(listOf("Α", "Σ", "Ω")))
            assertFalse("$t: 希腊 is all single glyphs, no chip bar", v.chipBarVisibleForTest())
            snap(v, (560 * density).toInt(), "symbols_greek_$t.png")
        }
    }

    @Test fun symbols_net() {
        val netIndex = SymbolCatalog.categories.indexOfFirst { it.id == "net" } + 1
        for ((t, pal) in themes) {
            val v = SymbolsView(ctx).apply { applyPalette(pal); openCategoryForTest(netIndex) }
            assertTrue("$t: a url completion still chips on 网络", v.hasTextLeaf("https://"))
            assertFalse("$t: the 网址补全 caption header is gone", v.hasTextLeaf("网址补全"))
            snap(v, (560 * density).toInt(), "symbols_net_$t.png")
        }
    }

    @Test fun expand_syllable_column() {
        for ((t, pal) in themes) {
            val v = CandidateGridView(ctx).apply {
                applyPalette(pal)
                setReadings(listOf("ni", "hao"), 0)
                setCandidates(
                    listOf("你", "拟", "尼", "泥", "逆", "妮", "倪", "腻", "匿", "昵",
                        "溺", "睨", "坭", "祢", "旎", "铌", "鲵", "猊", "蜺", "霓"),
                )
            }
            snap(v, (300 * density).toInt(), "expand_syllable_$t.png")
        }
    }

    @Test fun preedit_band() {
        for ((t, pal) in themes) {
            val v = PreeditView(ctx).apply { applyPalette(pal); setText("ni'hao") }
            snap(v, (30 * ctx.resources.displayMetrics.density).toInt(), "preedit_$t.png")
        }
    }

    @Test fun candidate_toolbar() {
        val h = (44 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = CandidateView(ctx).apply { applyPalette(pal); setContent(emptyList(), "") }
            snap(v, h, "toolbar_$t.png")
        }
    }

    @Test fun candidate_strip() {
        val h = (44 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = CandidateView(ctx).apply {
                applyPalette(pal)
                setContent(listOf("你好", "你", "尼", "拟", "泥", "逆"), "ni'hao")
            }
            snap(v, h, "strip_$t.png")
        }
    }

    @Test fun candidate_grid() {
        val h = (300 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = CandidateGridView(ctx).apply {
                applyPalette(pal)
                setReadings(listOf("ni", "ní", "nǐ", "nì"))
                setCandidates(listOf("你", "你好", "尼", "拟", "泥", "逆", "妮", "倪", "腻", "匿", "昵", "旎"))
            }
            assertTrue("grid_$t: middle candidate grid did not populate", v.hasTextLeaf("你好"))
            assertTrue("grid_$t: left reading column did not populate", v.hasTextLeaf("nǐ"))
            snap(v, h, "grid_$t.png")
        }
    }

    @Test fun panel_fade_midpoint() {
        val pal = ImePalette.STATIC_LIGHT
        val h = (560 * ctx.resources.displayMetrics.density).toInt()
        val v = SymbolsView(ctx).apply {
            recentProvider = { listOf("，", "。", "@") }; applyPalette(pal); openCategoryForTest(0)
        }
        v.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, wPx, h)
        val bmp = Bitmap.createBitmap(wPx, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(pal.keyboardBg)
        val c = Canvas(bmp)
        c.saveLayerAlpha(0f, 0f, wPx.toFloat(), h.toFloat(), 102)
        v.draw(c)
        c.restore()
        FileOutputStream(File(outDir, "panel_fade_mid.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = IntArray(wPx * h)
        bmp.getPixels(pixels, 0, wPx, 0, 0, wPx, h)
        assertTrue("panel_fade_mid drew nothing over the keyboard floor", pixels.any { it != pal.keyboardBg })
    }

    @Test fun copy_bar() {
        val h = (44 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = CopyBarView(ctx).apply { applyPalette(pal); show("这是一段被复制的内容") }
            snap(v, h, "copybar_$t.png")
        }
    }

    @Test fun clipboard_normal() {
        val h = (300 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                historyProvider = { listOf("第一条复制内容", "second clip on the board", "三") }
                applyPalette(pal)
            }
            snap(v, h, "clip_normal_$t.png")
        }
    }

    @Test fun clipboard_history_recording_menu() {
        val h = (300 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                historyProvider = { listOf("第一条复制内容") }
                applyPalette(pal)
                showHistoryRecordingMenuForTest()
            }
            snap(v, h, "clip_recording_menu_$t.png")
        }
    }

    @Test fun clipboard_select_delete() {
        val h = (300 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                historyProvider = { listOf("hello world", "复制的一段文字") }
                applyPalette(pal)
                enterSelectForTest(listOf("hello world"))
            }
            snap(v, h, "clip_select_$t.png")
        }
    }

    @Test fun phrase_expanded_actions() {
        val h = (320 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作") }
                phrasesInProvider = { c -> if (c == "工作") listOf("已收到") else listOf("你好", "在吗") }
                applyPalette(pal)
                forcePhrasesStateForTest("默认"); refresh(); expandForTest("你好")
            }
            snap(v, h, "phrase_actions_$t.png")
            assertTrue("$t: missing 编辑", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_edit)))
            assertTrue("$t: missing 移动", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_move)))
            assertTrue("$t: missing 删除", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_delete)))
        }
    }

    @Test fun phrase_select_mode() {
        val h = (320 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作") }
                phrasesInProvider = { _ -> listOf("你好", "在吗", "稍等") }
                applyPalette(pal)
                forcePhrasesStateForTest("默认"); refresh()
                enterSelectForTest(listOf("你好"))
            }
            snap(v, h, "phrase_select_$t.png")
            assertTrue("$t: missing title 编辑常用语", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_edit_phrases)))
            assertTrue("$t: missing 移动到分类", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_move_to_category)))
            assertTrue("$t: missing 删除", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_delete)))
        }
    }

    @Test fun phrase_move_chooser() {
        val h = (320 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作", "私人") }
                phrasesInProvider = { _ -> listOf("你好") }
                applyPalette(pal)
                forcePhrasesStateForTest("默认"); refresh()
                showMoveChooserForTest("默认")
            }
            snap(v, h, "phrase_move_$t.png")
            assertTrue("$t: move chooser missing target 工作", v.hasTextLeaf("工作"))
            assertTrue("$t: move chooser missing target 私人", v.hasTextLeaf("私人"))
            assertTrue("$t: move chooser missing ＋ 新建分类…", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_new_category)))
        }
    }

    @Test fun edit_bar() {
        val h = (44 * density).toInt()
        for ((t, pal) in themes) {
            val v = EditBarView(ctx).apply { applyPalette(pal); setTitle(ctx.getString(com.aegis.ime.R.string.clip_edit_phrases)); setText("你好世界") }
            snap(v, h, "edit_bar_$t.png")
            assertTrue("$t: edit bar missing 确定", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.editbar_confirm)))
            assertTrue("$t: edit bar missing 取消", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        }
    }

    @Test fun phrase_topbar_icons() {
        val h = (60 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作") }
                phrasesInProvider = { _ -> listOf("你好") }
                applyPalette(pal)
                forcePhrasesStateForTest("默认"); refresh()
            }
            snap(v, h, "phrase_topbar_$t.png")
        }
    }

    @Test fun edit_panel() {
        val h = (230 * density).toInt()
        for ((t, pal) in themes) {
            val idle = EditPanelView(ctx).apply { applyPalette(pal); setHasSelection(false) }
            snap(idle, h, "edit_panel_$t.png")
            assertTrue("$t: header keeps the 文字编辑 label", idle.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.edit_title)))
            assertActionInkCentered(idle, pal, "edit_panel_$t")
            assertEditPanelGeometry(idle, h, "edit_panel_$t")
            val active = EditPanelView(ctx).apply { applyPalette(pal); setSelecting(true); setHasSelection(true) }
            snap(active, h, "edit_panel_selecting_$t.png")
        }
    }

    @Test fun clip_swipe_reveal() {
        val h = (300 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                historyProvider = { listOf("第一条复制内容", "second clip") }
                applyPalette(pal); refresh(); revealSwipeForTest("第一条复制内容")
            }
            snap(v, h, "clip_swipe_$t.png")
            assertFalse("$t: swipe row must not show 拆词", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_split_word)))
        }
    }

    @Test fun phrase_swipe_reveal() {
        val h = (320 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作") }
                phrasesInProvider = { _ -> listOf("你好", "在吗", "稍等") }
                applyPalette(pal); forcePhrasesStateForTest("默认"); refresh(); revealSwipeForTest("在吗")
            }
            snap(v, h, "phrase_swipe_$t.png")
            assertFalse("$t: swipe row must not show 备注", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_note)))
        }
    }

    @Test fun phrase_sort_mode() {
        val h = (320 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认") }
                phrasesInProvider = { _ -> listOf("你好", "在吗", "稍等") }
                applyPalette(pal); forcePhrasesStateForTest("默认"); refresh(); enterSortModeForTest()
            }
            snap(v, h, "phrase_sort_$t.png")
            assertTrue("$t: missing 拖动排序", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_drag_sort)))
            assertTrue("$t: missing 完成", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_done)))
        }
    }

    @Test fun split_overlay() {
        val h = (300 * density).toInt()
        for ((t, pal) in themes) {
            val neutral = ClipboardView(ctx).apply {
                historyProvider = { listOf("在铅笔下面abc") }; applyPalette(pal); refresh(); showSplitForTest("在铅笔下面abc")
            }
            snap(neutral, h, "split_neutral_$t.png")
            assertTrue("$t: 全部复制 present", neutral.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_copy_all)))
            val tapped = ClipboardView(ctx).apply {
                historyProvider = { listOf("在铅笔下面abc") }; applyPalette(pal); refresh(); showSplitForTest("在铅笔下面abc")
            }
            findViewsWithText(tapped, "在铅笔下面").firstOrNull { it.hasOnClickListeners() }?.performClick()
            snap(tapped, h, "split_tapped_$t.png")
        }
    }

    private fun findViewsWithText(root: View, s: String): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(x: View) {
            if (x is TextView && x.text?.toString() == s) out.add(x)
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(root); return out
    }

    private fun assertActionInkCentered(view: EditPanelView, pal: ImePalette, name: String) {
        for (action in listOf(EditAction.DELETE, EditAction.COPY, EditAction.CUT, EditAction.PASTE)) {
            val target = requireNotNull(view.actionViewForTest(action)) as TextView
            val bmp = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(pal.keySurface)
            target.draw(Canvas(bmp))
            val ink = target.currentTextColor
            var left = target.width
            var right = -1
            for (y in 0 until target.height) {
                for (x in 0 until target.width) {
                    if (bmp.getPixel(x, y) == ink) {
                        left = minOf(left, x)
                        right = maxOf(right, x)
                    }
                }
            }
            assertTrue("$name: $action content was not rendered", right >= left)
            val leftMargin = left
            val rightMargin = target.width - 1 - right
            assertTrue(
                "$name: $action content not centered — leftMargin=$leftMargin rightMargin=$rightMargin",
                kotlin.math.abs(leftMargin - rightMargin) <= 1,
            )
        }
    }

    private fun assertEditPanelGeometry(view: EditPanelView, hPx: Int, name: String) {
        fun bounds(action: EditAction): Rect {
            val target = requireNotNull(view.actionViewForTest(action))
            return Rect(0, 0, target.width, target.height).also { view.offsetDescendantRectToMyCoords(target, it) }
        }

        fun hitBounds(target: View): Rect {
            val hit = Rect()
            target.getHitRect(hit)
            view.offsetDescendantRectToMyCoords(target.parent as View, hit)
            return hit
        }

        val actions = listOf(EditAction.DELETE, EditAction.COPY, EditAction.CUT, EditAction.PASTE)
        val bottomNavigation = listOf(EditAction.HOME, EditAction.SELECT_ALL, EditAction.END)
        var firstGeometry: List<List<Rect>>? = null
        for ((pass, width) in listOf(wPx, (360 * density).toInt(), wPx).withIndex()) {
            view.measure(exactly(width), exactly(hPx))
            view.layout(0, 0, width, hPx)
            view.draw(Canvas(Bitmap.createBitmap(width, hPx, Bitmap.Config.ARGB_8888)))

            val geometry = actions.map { action ->
                val target = requireNotNull(view.actionViewForTest(action))
                val viewRect = bounds(action)
                val backgroundRect = Rect(requireNotNull(target.background).bounds).apply {
                    offset(viewRect.left, viewRect.top)
                }
                listOf(viewRect, backgroundRect, hitBounds(target))
            }
            if (pass == 0) {
                firstGeometry = geometry.map { rectangles -> rectangles.map { Rect(it) } }
            } else if (pass == 2) {
                assertEquals("$name: action geometry after A-B-A layout", firstGeometry, geometry)
            }

            val deleteRect = Rect(geometry.first().first()).apply { offset(0, -top) }
            for ((action, rectangles) in actions.zip(geometry)) {
                val viewTop = rectangles.first().top
                val relative = rectangles.map { Rect(it).apply { offset(0, -viewTop) } }
                assertEquals(
                    "$name/${width}px: $action View/background/hit rectangles relative to Delete",
                    List(3) { Rect(deleteRect) },
                    relative,
                )
            }
        }

        val navigationClicks = mutableListOf<EditAction>()
        view.onAction = navigationClicks::add
        for (action in bottomNavigation) {
            val target = requireNotNull(view.actionViewForTest(action))
            assertTrue("$name: $action keeps a transparent resting background", target.background == null)
            val ripple = target.foreground as? RippleDrawable
                ?: throw AssertionError("$name: $action lost pressed feedback")
            assertTrue("$name: $action pressed feedback remains stateful", ripple.isStateful)
            assertTrue("$name: $action click", target.performClick())
        }
        assertEquals("$name: navigation clicks", bottomNavigation, navigationClicks)

        val dispatched = mutableListOf<EditAction>()
        view.onAction = dispatched::add
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val root = requireNotNull(activity.get().findViewById<android.view.ViewGroup>(android.R.id.content))
            root.addView(view)
            assertTrue("$name: panel attaches directly to the Activity content root", view.parent === root)
            root.measure(exactly(wPx), exactly(hPx))
            root.layout(0, 0, wPx, hPx)

            val routedActions = listOf(EditAction.DELETE, EditAction.PASTE)
            val targets = routedActions.map { requireNotNull(view.actionViewForTest(it)) }
            val viewRects = targets.map { target ->
                Rect(0, 0, target.width, target.height).also {
                    root.offsetDescendantRectToMyCoords(target, it)
                }
            }
            val centers = viewRects.map { it.exactCenterX() to it.exactCenterY() }
            val hitRects = targets.map { target ->
                Rect().also {
                    target.getHitRect(it)
                    root.offsetDescendantRectToMyCoords(target.parent as View, it)
                }
            }
            for ((index, action) in routedActions.withIndex()) {
                val (x, y) = centers[index]
                assertEquals(
                    "$name: $action root hit ownership",
                    listOf(action),
                    routedActions.filterIndexed { hitIndex, _ ->
                        hitRects[hitIndex].contains(x.toInt(), y.toInt())
                    },
                )
                val downTime = index * 32L
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(downTime, downTime + 16L, MotionEvent.ACTION_UP, x, y, 0)
                try {
                    assertTrue("$name: $action root press", root.dispatchTouchEvent(down))
                    assertTrue("$name: $action root release", root.dispatchTouchEvent(up))
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("$name: root-routed actions", routedActions, dispatched)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun phrase_note_display() {
        val h = (300 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认") }
                phrasesInProvider = { _ -> listOf("ssh root@10.0.0.1 -p 2222", "你好") }
                phraseNoteProvider = { _, txt -> if (txt.startsWith("ssh")) "登录服务器" else "" }
                applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            }
            snap(v, h, "phrase_note_$t.png")
            assertTrue("$t: note alias shown", v.hasTextLeaf("登录服务器"))
        }
    }

    @Test fun phrase_manage_menu() {
        val h = (320 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认") }; phrasesInProvider = { _ -> listOf("你好") }
                applyPalette(pal); forcePhrasesStateForTest("默认"); refresh(); showPhraseManageMenuForTest()
            }
            snap(v, h, "phrase_manage_menu_$t.png")
            assertTrue("$t: 导入常用语", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_import_phrases)))
            assertTrue("$t: 导出常用语", v.hasTextLeaf(ctx.getString(com.aegis.ime.R.string.clip_export_phrases)))
        }
    }

    @Test fun keyboard_alpha() {
        val h = (230 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = KeyboardView(ctx).apply {
                applyPalette(pal)
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            }
            snap(v, h, "keyboard_$t.png")
        }
    }

    @Test fun keyboard_nine_readout() {
        val h = (230 * density).toInt()
        val readout = listOf("zhuang", "shuang", "chuang", "zhu", "yi", "zhua", "nü")
            .map { Key(it, output = it, action = KeyAction.PICK_READING) }
        for ((t, pal) in themes) {
            val v = KeyboardView(ctx).apply {
                applyPalette(pal)
                setLayout(Layouts.nine(Lang.CN, readout, composing = true), false, false, Lang.CN)
            }
            snap(v, h, "keyboard_nine_$t.png")
        }
    }

    @Test fun keyboard_numpad() {
        val h = (230 * density).toInt()
        for ((t, pal) in themes) {
            val v = KeyboardView(ctx).apply {
                applyPalette(pal)
                setLayout(Layouts.numpad(), false, false, Lang.CN)
            }
            snap(v, h, "keyboard_numpad_$t.png")
        }
    }
}
