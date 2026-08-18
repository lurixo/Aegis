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

import com.aegis.ime.user.asClipEntries
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.SettingsHomePage
import com.aegis.ime.ui.theme.AegisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BilingualScreenshotTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val density = ctx.resources.displayMetrics.density
    private val pal = ImePalette.from(ctx, dark = false)

    private val baseDir = File("build/render/i18n").apply { mkdirs() }

    private val curatedFaces = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🙂", "🙃", "😉", "😊", "😇", "😍", "😘", "😗",
        "😙", "😚", "😋", "😛", "😜", "😝", "🤗", "🤔", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😌",
        "😔", "😪", "😴", "😷", "🤒", "😎", "😳", "😭", "😤", "😡", "😠", "🤯", "🥳", "😱", "🤠", "🤢",
    )

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    private fun snap(view: View, hPx: Int, dir: File, name: String, afterLayout: (View) -> Unit = {}) {
        view.measure(exactly(wPx), exactly(hPx))
        view.layout(0, 0, wPx, hPx)
        afterLayout(view)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.MAGENTA)
        view.draw(Canvas(bmp))
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertMeaningful(bmp, "$name (${dir.name})")
    }

    private fun assertMeaningful(bmp: Bitmap, label: String) {
        val w = bmp.width; val h = bmp.height; val total = w * h
        val px = IntArray(total)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = HashMap<Int, Int>()
        var sentinel = 0
        for (p in px) { if (p == Color.MAGENTA) sentinel++ else hist[p] = (hist[p] ?: 0) + 1 }
        val painted = total - sentinel
        val fill = hist.values.maxOrNull() ?: 0
        val content = painted - fill
        assertTrue("$label: rendered essentially nothing (still the magenta sentinel)", painted > total / 50)
        assertTrue("$label: a flat fill with nothing drawn on it", content > total / 500)
    }

    private fun categoryRailOf(panel: View): ScrollView {
        val hits = ArrayList<ScrollView>()
        fun walk(v: View) {
            if (v is ScrollView) {
                val child = v.getChildAt(0)
                if (child is LinearLayout && child.childCount > 0 &&
                    (0 until child.childCount).all { child.getChildAt(it) is TextView }
                ) hits.add(v)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(panel)
        return hits.single()
    }

    private fun alignCategoryRail(panel: View) {
        val scroll = categoryRailOf(panel)
        val rail = scroll.getChildAt(0) as LinearLayout
        val tabs = (0 until rail.childCount).map { rail.getChildAt(it) }
        val words = tabs.map { it.top + it.paddingTop to it.bottom - it.paddingBottom }
        val open = tabs.single { it.isSelected }
        val viewport = scroll.height
        fun cuts(edge: Int) = words.any { (top, bottom) -> edge > top && edge < bottom }
        fun clearance(edge: Int) = words.minOf { (top, bottom) ->
            minOf(kotlin.math.abs(edge - top), kotlin.math.abs(edge - bottom))
        }
        fun chipLoss(offset: Int) =
            maxOf(0, offset - open.top) + maxOf(0, open.bottom - (offset + viewport))
        val resting = (0..maxOf(0, rail.height - viewport))
            .filterNot { cuts(it) || cuts(it + viewport) }
            .minWithOrNull(
                compareBy<Int> { chipLoss(it) }
                    .thenByDescending { minOf(clearance(it), clearance(it + viewport)) },
            )
        assertNotNull("no rail position leaves every category label whole", resting)
        scroll.isVerticalScrollBarEnabled = false
        scroll.scrollTo(0, resting!!)
    }

    private fun snapCompose(dir: File, name: String, hDp: Int = 1180, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity: Activity = controller.get()
        val compose = ComposeView(activity).apply {
            setContent {
                AegisTheme(darkTheme = false) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                }
            }
        }
        activity.setContentView(compose)
        shadowOf(Looper.getMainLooper()).idle()

        val hPx = (hDp * density).toInt()
        compose.measure(exactly(wPx), exactly(hPx))
        compose.layout(0, 0, wPx, hPx)
        shadowOf(Looper.getMainLooper()).idle()

        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        compose.draw(Canvas(bmp))
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val total = wPx * hPx
        val px = IntArray(total); bmp.getPixels(px, 0, wPx, 0, 0, wPx, hPx)
        assertTrue(
            "$name (${dir.name}): settings composed nothing (canvas stayed blank white)",
            px.count { it != Color.WHITE } > total / 500,
        )
    }

    private fun renderSet(
        lang: Lang,
        dir: File,
        history: List<String>,
        phraseCats: List<String>,
        phrasesOf: (String) -> List<String>,
        notesOf: (String, String) -> String,
        symbols: List<String>,
    ) {
        dir.mkdirs()

        snap(
            KeyboardView(ctx).apply {
                applyPalette(pal)
                setLayout(Layouts.forId(LayoutId.ALPHA, lang), false, false, lang)
            },
            (230 * density).toInt(), dir, "keyboard-qwerty.png",
        )

        val readout = listOf("zhuang", "shuang", "chuang", "zhu", "yi", "zhua", "nü")
            .map { Key(it, output = it, action = KeyAction.PICK_READING) }
        snap(
            KeyboardView(ctx).apply {
                applyPalette(pal)
                setLayout(Layouts.nine(readout, composing = true), false, false, lang)
            },
            (230 * density).toInt(), dir, "keyboard-t9.png",
        )

        snap(
            EditPanelView(ctx).apply {
                applyPalette(pal)
                setHasSelection(true)
            },
            (230 * density).toInt(), dir, "edit-panel.png",
        )

        snap(
            EmojiView(ctx).apply {
                recentProvider = { curatedFaces }
                applyPalette(pal)
                openCategoryForTest(0)
            },
            (360 * density).toInt(), dir, "emoji.png", ::alignCategoryRail,
        )

        snap(
            ClipboardView(ctx).apply {
                historyProvider = { history.asClipEntries() }
                categoriesProvider = { phraseCats }
                phrasesInProvider = { c -> phrasesOf(c) }
                phraseNoteProvider = { c, t -> notesOf(c, t) }
                applyPalette(pal)
                forcePhrasesStateForTest(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID)
                refresh()
            },
            (360 * density).toInt(), dir, "clipboard.png",
        )

        snap(
            SymbolsView(ctx).apply {
                recentProvider = { symbols }
                applyPalette(pal)
                openCategoryForTest(1)
            },
            (360 * density).toInt(), dir, "symbols.png", ::alignCategoryRail,
        )

        snapCompose(dir, "settings.png") { SettingsHomePage(onOpenGroup = {}) }
    }

    @Test fun bilingual_readme_screenshots() {
        assertNotEquals(
            "precondition: dynamic colour fell back to the static palette",
            ImePalette.STATIC_LIGHT,
            pal,
        )
        assertEquals("precondition: default locale must resolve English chrome", "Space", ctx.getString(R.string.kbd_space))
        renderSet(
            lang = Lang.EN,
            dir = File(baseDir, "en"),
            history = listOf(
                "Moved the review to 3pm tomorrow",
                "https://example.com/handbook",
                "Meeting room is on the 12th floor",
            ),
            phraseCats = listOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID, "Work", "Travel"),
            phrasesOf = { c ->
                when (c) {
                    "Work" -> listOf("Reviewed, looks good to me", "Let's sync after standup")
                    "Travel" -> listOf("A window seat, please")
                    else -> listOf(
                        "On my way, about ten minutes out",
                        "Thanks, got it!",
                        "ssh root@10.0.0.1 -p 2222",
                        "Let me check with the team and come back to you today",
                    )
                }
            },
            notesOf = { _, t -> if (t.startsWith("ssh")) "Log in to the build server" else "" },
            symbols = listOf(",", ".", "?"),
        )

        RuntimeEnvironment.setQualifiers("+zh-rCN")
        assertEquals("locale switch did not reach the keyboard chrome", "空格", ctx.getString(R.string.kbd_space))
        assertEquals("locale switch did not reach the symbols chrome", "符号", ctx.getString(R.string.kbd_symbols))
        assertEquals("locale switch did not reach the clipboard back label", "返回", ctx.getString(R.string.clip_back))
        assertEquals("locale switch did not reach the clipboard tabs", "剪贴板", ctx.getString(R.string.clip_clipboard))
        assertEquals("locale switch did not reach the phrases tab", "常用语", ctx.getString(R.string.clip_phrases))
        assertEquals("locale switch did not reach the emoji recent rail", "常用", ctx.getString(R.string.emoji_cat_recent))
        assertEquals("locale switch did not reach the emoji smileys rail", "黄脸", ctx.getString(R.string.emoji_cat_face))
        renderSet(
            lang = Lang.CN,
            dir = File(baseDir, "zh"),
            history = listOf(
                "评审改到明天下午三点",
                "https://example.com/handbook",
                "会议室在 12 楼",
            ),
            phraseCats = listOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID, "工作", "出行"),
            phrasesOf = { c ->
                when (c) {
                    "工作" -> listOf("已审核，没有问题", "站会后我们对一下")
                    "出行" -> listOf("麻烦帮我留一个靠窗的座位")
                    else -> listOf(
                        "在路上了，大概十分钟到",
                        "收到，我马上处理",
                        "ssh root@10.0.0.1 -p 2222",
                        "不好意思，我这边临时有事，今天下午的会议能不能往后推半小时？",
                    )
                }
            },
            notesOf = { _, t -> if (t.startsWith("ssh")) "登录构建服务器" else "" },
            symbols = listOf("，", "。", "？"),
        )

        RuntimeEnvironment.setQualifiers("+en")
    }
}
