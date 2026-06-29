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
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * U-polish render harness (NOT a behavioural test). Robolectric NATIVE graphics rasterises the real onDraw /
 * layout to PNGs so the visual changes can be eyeballed in light AND dark without a full emulator. Writes to
 * build/render/. Run targeted: `:app:testDebugUnitTest --tests *RenderHarness`.
 */
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
        bmp.eraseColor(Color.MAGENTA) // so an unrendered (blank) view is obvious
        view.draw(Canvas(bmp))
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertMeaningful(bmp, name)
    }

    /**
     * A meaningful "this render is not broken" gate (replaces the old single-row != magenta check, a
     * false-positive shape that passed on an almost-blank view — the debug.11 trap). MAGENTA is the sentinel
     * for "the view painted nothing here". Over the WHOLE bitmap we require:
     *   (1) the view painted a surface over a real chunk of its bounds  -> catches an all-blank render;
     *   (2) it drew content ON that surface beyond the single dominant fill -> catches a flat one-colour fill;
     *   (3) that content is anti-aliased glyph/icon material (many distinct colours), not merely a SECOND flat
     *       region -> so a "two flat fills, no text/icons" render also goes RED.
     * Deliberately NOT a per-pixel golden (too brittle). NOTE it proves "a surface with real drawn content was
     * rasterised"; it does NOT by itself prove every sub-column of a multi-region panel populated (a second
     * flat background region clears (2) on its own) — the container snaps that care (candidate_grid) assert
     * their population STRUCTURALLY as well (findViewsWithText below).
     */
    private fun assertMeaningful(bmp: Bitmap, name: String) {
        val w = bmp.width; val h = bmp.height; val total = w * h
        val px = IntArray(total)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = HashMap<Int, Int>()
        var sentinel = 0
        for (p in px) { if (p == Color.MAGENTA) sentinel++ else hist[p] = (hist[p] ?: 0) + 1 }
        val painted = total - sentinel           // pixels the view actually drew over the sentinel
        val fill = hist.values.maxOrNull() ?: 0  // dominant painted colour = the surface fill
        val content = painted - fill             // everything drawn ON the dominant fill = glyphs / chips / icons
        assertTrue(
            "$name: rendered essentially nothing (still the magenta sentinel) — onDraw painted no surface",
            painted > total / 50, // > 2% of the view actually painted
        )
        assertTrue(
            "$name: a flat fill with nothing drawn on it (glyphs/chips/icons missing)",
            content > total / 500, // > 0.2% non-(dominant-fill) content
        )
        assertTrue(
            "$name: too few distinct colours — looks like flat fills with no anti-aliased content (text/icons)",
            hist.size >= 64, // real glyph/icon AA always yields hundreds of colours; flat regions ~one each
        )
    }

    /** Structural check: the view tree actually built a (TextView) leaf whose text contains [s]. Used to prove
     *  a populated column rendered its items, which the pixel gate alone cannot (a flat sibling region passes it). */
    private fun View.hasTextLeaf(s: String): Boolean {
        val out = ArrayList<View>()
        findViewsWithText(out, s, View.FIND_VIEWS_WITH_TEXT)
        return out.isNotEmpty()
    }

    private val density = ctx.resources.displayMetrics.density
    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    /**
     * debug.13 P-A: stitch the idle candidate strip (its own floor) directly ABOVE [panel] into ONE bitmap, so
     * the strip↔panel-body background boundary — the horizontal colour seam — is visible end to end.
     * After the fix both share keyboardBg, so the boundary must be invisible (asserted: the pixel rows just
     * above and just below the seam line are the same colour).
     */
    private fun stitchStripAndPanel(panel: View, panelHpx: Int, name: String, pal: ImePalette) {
        val stripH = (44 * density).toInt()
        val strip = CandidateView(ctx).apply { applyPalette(pal); setContent(emptyList(), "") } // idle toolbar
        strip.measure(exactly(wPx), exactly(stripH)); strip.layout(0, 0, wPx, stripH)
        panel.measure(exactly(wPx), exactly(panelHpx)); panel.layout(0, 0, wPx, panelHpx)
        val totalH = stripH + panelHpx
        val bmp = Bitmap.createBitmap(wPx, totalH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.MAGENTA)
        val c = Canvas(bmp)
        strip.draw(c)
        c.save(); c.translate(0f, stripH.toFloat()); panel.draw(c); c.restore()
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // No seam: the strip's floor (its top-left gutter — the capsule is inset, so this is the bare floor) and
        // the PANEL BODY's floor must be the SAME colour. Rather than a fragile single pixel (a panel's left
        // edge is the railBg tray, not the body floor), scan the panel rows for the strip-floor colour: if the
        // body shares the floor it appears; if it reverted to a distinct panelBg it would not. (In the dark
        // static palette keyboardBg==panelBg, so there this is trivially satisfied — the light case is the
        // load-bearing seam guard, matching where it actually appeared on screen.)
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

    /** P-A: the strip+panel seam check, on two structurally different panels (symbols grid, clipboard cards). */
    @Test fun seam_strip_over_symbols() {
        for ((t, pal) in themes) {
            val panel = SymbolsView(ctx).apply {
                recentProvider = { listOf("，", "。", "@") }; applyPalette(pal); openCategoryForTest(1) // 中文 glyph grid
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

    /** P-C: the symbols panel with 锁定 engaged — the self-drawn padlock should read closed + accent-tinted. */
    @Test fun symbols_locked() {
        for ((t, pal) in themes) {
            val v = SymbolsView(ctx).apply {
                recentProvider = { listOf("，", "。") }; applyPalette(pal); openCategoryForTest(0); toggleLockForTest()
            }
            snap(v, (300 * density).toInt(), "symbols_locked_$t.png")
        }
    }

    /** E2: the emoji panel — verifies the rail (最近 MRU + the new categories) + grid layout. NOTE: Robolectric's
     *  JVM font has no colour-emoji glyphs, so the cells render as tofu boxes here; the device shows real emoji. */
    @Test fun emoji_panel() {
        for ((t, pal) in themes) {
            val v = EmojiView(ctx).apply {
                recentProvider = { listOf("😀", "👍", "❤️") }
                applyPalette(pal); openCategoryForTest(1) // index 0 = 最近(MRU); 1 = 黄脸
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

    /** UI-2: the 26-key EXPAND screen — LEFT 分词 column (the drilled syllable highlighted) + that syllable's
     *  full 同音单字 grid (uncapped) + the 返回/⌫/重输 function column. Light AND dark. */
    @Test fun expand_syllable_column() {
        for ((t, pal) in themes) {
            val v = CandidateGridView(ctx).apply {
                applyPalette(pal)
                setReadings(listOf("ni", "hao"), 0) // syllable 'ni' is drilled → highlighted
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
            val v = CandidateView(ctx).apply { applyPalette(pal); setContent(emptyList(), "") } // idle = toolbar
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

    /** S1 expanded selection grid (A2): left readings + middle candidate grid + right 返回/⌫/重输 column.
     *  This is the rich grid the polish touched (right-column bg) but had no render case — now eyeball-able. */
    @Test fun candidate_grid() {
        val h = (300 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = CandidateGridView(ctx).apply {
                applyPalette(pal)
                setReadings(listOf("ni", "ní", "nǐ", "nì"))
                setCandidates(listOf("你", "你好", "尼", "拟", "泥", "逆", "妮", "倪", "腻", "匿", "昵", "旎"))
            }
            // Structurally prove BOTH the middle candidate grid and the left reading column populated — the
            // pixel gate can't (their flat sibling backgrounds satisfy it on their own).
            assertTrue("grid_$t: middle candidate grid did not populate", v.hasTextLeaf("你好"))
            assertTrue("grid_$t: left reading column did not populate", v.hasTextLeaf("nǐ"))
            snap(v, h, "grid_$t.png")
        }
    }

    /** U-anim: the panel-open fade is alpha-only; render the ~40% midpoint over the keyboard floor to show it. */
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
        bmp.eraseColor(pal.keyboardBg) // the keyboard floor the panel fades in over
        val c = Canvas(bmp)
        c.saveLayerAlpha(0f, 0f, wPx.toFloat(), h.toFloat(), 102) // 0.4 * 255 — the fade midpoint
        v.draw(c)
        c.restore()
        FileOutputStream(File(outDir, "panel_fade_mid.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // even at 40% alpha the panel must have drawn OVER the keyboard floor (the fade is visible, not vanished).
        val mid = IntArray(wPx); bmp.getPixels(mid, 0, wPx, 0, h / 2, wPx, 1)
        assertTrue("panel_fade_mid drew nothing over the keyboard floor", mid.any { it != pal.keyboardBg })
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

    @Test fun clipboard_select_delete() {
        val h = (300 * ctx.resources.displayMetrics.density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                historyProvider = { listOf("hello world", "复制的一段文字") }
                applyPalette(pal)
                enterSelectForTest(listOf("hello world")) // selection present -> 删除 enabled (destructive red)
            }
            snap(v, h, "clip_select_$t.png")
        }
    }

    /** debug.14 item2: the self-drawn 清空系统剪贴板 button sits in the top bar (left of ☰); tapping it pops a
     *  two-step confirm card. Render the panel WITH the confirm overlay up, light AND dark. */
    @Test fun clipboard_clear_system_confirm() {
        val h = (300 * density).toInt()
        for ((t, pal) in themes) {
            val v = ClipboardView(ctx).apply {
                historyProvider = { listOf("第一条复制内容", "second clip on the board") }
                applyPalette(pal)
                requestClearSystemForTest() // show the confirm card over the panel
            }
            snap(v, h, "clip_clear_confirm_$t.png")
        }
    }

    // debug.16 (#55): the polished 文字编辑面板 — hollow D-pad arrows, 段首/段尾 labels, the copy-bar clipboard
    // glyph on 粘贴, the "‹" back chevron, and per-key outline icons. Snapped with AND without a selection so
    // the muted-vs-active 复制/剪切 icons are both eyeballable.
    @Test fun edit_panel() {
        val h = (230 * density).toInt()
        for ((t, pal) in themes) {
            val idle = EditPanelView(ctx).apply { applyPalette(pal); setHasSelection(false) }
            snap(idle, h, "edit_panel_$t.png")
            val active = EditPanelView(ctx).apply { applyPalette(pal); setSelecting(true); setHasSelection(true) }
            snap(active, h, "edit_panel_selecting_$t.png")
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
}
