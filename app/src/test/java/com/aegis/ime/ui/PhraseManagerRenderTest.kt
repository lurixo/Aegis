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

package com.aegis.ime.ui

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
import com.aegis.ime.ui.theme.AegisTheme
import com.aegis.ime.user.ClipboardStore
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
import java.nio.file.Files

/**
 * debug.16: rasterise the 常用语管理 screen with a phrase's INLINE EDIT field open (the 编辑输入态 the panel's
 * long-press 编辑 hands off to), light AND dark. Edit happens here, in a real Activity window, because an
 * EditText/TextField inside the IME panel cannot receive typing. Writes build/render/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PhraseManagerRenderTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val outDir = File("build/render").apply { mkdirs() }

    private fun snapCompose(name: String, dark: Boolean, hDp: Int = 900, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity: Activity = controller.get()
        val compose = ComposeView(activity).apply {
            setContent { AegisTheme(darkTheme = dark) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() } } }
        }
        activity.setContentView(compose)
        shadowOf(Looper.getMainLooper()).idle()

        val hPx = (hDp * ctx.resources.displayMetrics.density).toInt()
        compose.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY),
        )
        compose.layout(0, 0, wPx, hPx)
        shadowOf(Looper.getMainLooper()).idle()

        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.MAGENTA)
        compose.draw(Canvas(bmp))
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val px = IntArray(wPx); bmp.getPixels(px, 0, wPx, 0, hPx / 2, wPx, 1)
        assertTrue("$name rendered nothing (still magenta)", px.any { it != Color.MAGENTA })
    }

    @Test fun phrase_inline_edit_field() {
        val dir = Files.createTempDirectory("phrasemgr").toFile()
        val store = ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("你好", "在吗")) }
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            snapCompose("phrase_edit_$t.png", dark) {
                // focusPhrase pre-opens 你好's inline editor (OutlinedTextField + 取消/保存).
                PhraseManagerScreen(store, focusCategory = "工作", focusPhrase = "你好")
            }
        }
    }
}
