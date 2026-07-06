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

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
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
import com.aegis.ime.ui.SettingsNavGraph
import com.aegis.ime.ui.theme.AegisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BilingualScreenshotTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val density = ctx.resources.displayMetrics.density
    private val pal = ImePalette.STATIC_LIGHT

    private val baseDir = File("build/render/i18n").apply { mkdirs() }

    private val curatedFaces = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🙂", "🙃", "😉", "😊", "😇", "😍", "😘", "😗",
        "😙", "😚", "😋", "😛", "😜", "😝", "🤗", "🤔", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😌",
        "😔", "😪", "😴", "😷", "🤒", "😎", "😳", "😭", "😤", "😡", "😠", "🤯", "🥳", "😱", "🤠", "🤢",
    )

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    private fun snap(view: View, hPx: Int, dir: File, name: String) {
        view.measure(exactly(wPx), exactly(hPx))
        view.layout(0, 0, wPx, hPx)
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
                setLayout(Layouts.nine(lang, readout, composing = true), false, false, lang)
            },
            (230 * density).toInt(), dir, "keyboard-t9.png",
        )

        snap(
            EmojiView(ctx).apply {
                recentProvider = { curatedFaces }
                applyPalette(pal)
                openCategoryForTest(0)
            },
            (560 * density).toInt(), dir, "emoji.png",
        )

        snap(
            ClipboardView(ctx).apply {
                historyProvider = { history }
                categoriesProvider = { phraseCats }
                phrasesInProvider = { c -> phrasesOf(c) }
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
                openCategoryForTest(0)
            },
            (560 * density).toInt(), dir, "symbols.png",
        )

        snapCompose(dir, "settings.png") { SettingsNavGraph() }
    }

    @Test fun bilingual_readme_screenshots() {
        assertEquals("precondition: default locale must resolve English chrome", "Space", ctx.getString(R.string.kbd_space))
        renderSet(
            lang = Lang.EN,
            dir = File(baseDir, "en"),
            history = listOf("Meeting at 3pm", "https://example.com"),
            phraseCats = listOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID, "Work"),
            phrasesOf = { c -> if (c == "Work") listOf("On my way", "Thanks!") else listOf("On my way", "Thanks!") },
            symbols = listOf(",", ".", "?"),
        )

        RuntimeEnvironment.setQualifiers("+zh-rCN")
        assertEquals("locale switch did not reach the keyboard chrome", "空格", ctx.getString(R.string.kbd_space))
        assertEquals("locale switch did not reach the symbols chrome", "符号", ctx.getString(R.string.kbd_symbols))
        assertEquals("locale switch did not reach the clipboard back label", "返回", ctx.getString(R.string.clip_back))
        assertEquals("locale switch did not reach the clipboard tabs", "剪贴板", ctx.getString(R.string.clip_clipboard))
        assertEquals("locale switch did not reach the phrases tab", "常用语", ctx.getString(R.string.clip_phrases))
        assertEquals("locale switch did not reach the emoji recent rail", "最近", ctx.getString(R.string.emoji_cat_recent))
        assertEquals("locale switch did not reach the emoji smileys rail", "黄脸", ctx.getString(R.string.emoji_cat_face))
        renderSet(
            lang = Lang.CN,
            dir = File(baseDir, "zh"),
            history = listOf("第一条复制内容", "第二条内容"),
            phraseCats = listOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID, "工作"),
            phrasesOf = { _ -> listOf("你好", "在吗") },
            symbols = listOf("，", "。", "？"),
        )

        RuntimeEnvironment.setQualifiers("+en")
    }
}
