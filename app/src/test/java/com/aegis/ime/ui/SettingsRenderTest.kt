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
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AegisTheme
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

/**
  * Chinese IME behavior note.
 * toggle (D1) to PNGs in light AND dark, so the order/layout can be eyeballed without a device. Hosts the
 * Compose content in a ComposeView under a Robolectric ComponentActivity, NATIVE-graphics. Writes build/render/.
 * (Cards are in the not-downloaded state, so no network HEAD fires during composition.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsRenderTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val outDir = File("build/render").apply { mkdirs() }

    private fun snapCompose(name: String, dark: Boolean, hDp: Int = 1180, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity: Activity = controller.get()
        val compose = ComposeView(activity).apply {
            setContent { AegisTheme(darkTheme = dark) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() } } }
        }
        activity.setContentView(compose)
        shadowOf(Looper.getMainLooper()).idle() // flush composition + layout

        val hPx = (hDp * ctx.resources.displayMetrics.density).toInt() // tall enough for the cards being rendered
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

    @Test fun settings_download_cards_and_toggle() {
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            snapCompose("settings_$t.png", dark) {
                GramDownloadCard() // Chinese IME behavior note.
                DictDownloadCard() // Chinese IME behavior note.
                AssociationToggleCard() // Chinese IME behavior note.
            }
        }
    }

    /**
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     */
    @Test fun download_card_update_states() {
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            snapCompose("dlcard_states_$t.png", dark, hDp = 1600) {
                DictDownloadCard(DownloadCardPreview(present = true)) // Chinese IME behavior note.
                GramDownloadCard(DownloadCardPreview(present = true, checking = true)) // Chinese IME behavior note.
                DictDownloadCard(DownloadCardPreview(present = true, status = ctx.getString(R.string.dict_status_update_current))) // Chinese IME behavior note.
                GramDownloadCard(DownloadCardPreview(present = true, status = ctx.getString(R.string.download_toast_update_offline))) // Chinese IME behavior note.
            }
        }
    }

    /** debug.47: the grouped-navigation home (four group entries) — the new settings landing screen. */
    @Test fun settings_home_renders() {
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            snapCompose("settings_home_$t.png", dark) {
                SettingsNavGraph()
            }
        }
    }

    /** debug.47: the user-dictionary page (pinned search + lazy list) with a few seeded entries. */
    @Test fun user_dict_page_renders() {
        val db = File(ctx.filesDir, "userdb.txt")
        db.writeText("aegis-userdb 1\nR\tnihao\t你好\nR\tceshi\t测试\nR\thaode\t好的\n")
        try {
            for (dark in listOf(false, true)) {
                val t = if (dark) "dark" else "light"
                snapCompose("userdict_page_$t.png", dark) {
                    UserDictPage(onBack = {})
                }
            }
        } finally {
            db.delete()
        }
    }
}
