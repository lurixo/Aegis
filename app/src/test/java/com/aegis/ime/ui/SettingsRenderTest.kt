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
import org.junit.Assert.assertFalse
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
        var drawn = 0
        val px = IntArray(wPx)
        for (band in 1..5) {
            bmp.getPixels(px, 0, wPx, 0, hPx * band / 6, wPx, 1)
            val row = px.count { it != Color.MAGENTA }
            assertTrue("$name band $band rendered nothing (still magenta)", row > 0)
            drawn += row
        }
        assertTrue("$name painted almost nothing: $drawn", drawn > wPx / 2)
    }


    private fun assertThemedPairDiffers(base: String) {
        val light = File(outDir, base + "light.png").readBytes()
        val dark = File(outDir, base + "dark.png").readBytes()
        assertFalse(base + " light and dark rendered identically", light.contentEquals(dark))
    }

    @Test fun settings_download_cards_and_toggle() {
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            snapCompose("settings_$t.png", dark) {
                GramDownloadCard()
                DictDownloadCard()
                AssociationToggleCard()
            }
        }
        assertThemedPairDiffers("settings_")
    }

    @Test fun download_card_update_states() {
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            snapCompose("dlcard_states_$t.png", dark, hDp = 1600) {
                DictDownloadCard(DownloadCardPreview(present = true))
                GramDownloadCard(DownloadCardPreview(present = true, checking = true))
                DictDownloadCard(DownloadCardPreview(present = true, status = ctx.getString(R.string.dict_status_update_current)))
                GramDownloadCard(DownloadCardPreview(present = true, status = ctx.getString(R.string.download_toast_update_offline)))
            }
        }
        assertThemedPairDiffers("dlcard_states_")
    }

    @Test fun settings_home_renders() {
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            snapCompose("settings_home_$t.png", dark) {
                SettingsHomePage(onOpenGroup = {})
            }
        }
        assertThemedPairDiffers("settings_home_")
    }

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
            assertThemedPairDiffers("userdict_page_")
        } finally {
            db.delete()
        }
    }
}
