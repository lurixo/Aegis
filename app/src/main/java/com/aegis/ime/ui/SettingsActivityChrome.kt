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
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.aegis.ime.ui.theme.AegisTheme
import com.aegis.ime.ui.theme.settingsBackgroundArgb

internal fun ComponentActivity.bootstrapSettingsEdgeToEdge() {
    val darkTheme = isSystemInDarkTheme()
    val barStyle = settingsSystemBarStyle(darkTheme)
    window.syncSettingsBackground(settingsBackgroundArgb(this, darkTheme))
    enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
    window.syncSettingsBackground(settingsBackgroundArgb(this, darkTheme))
}

@Composable
internal fun SettingsActivityChrome(content: @Composable () -> Unit) {
    AegisTheme {
        val window = LocalContext.current.findActivity()?.window
        val view = LocalView.current
        val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
        SideEffect {
            window?.syncSettingsBackground(backgroundColor)
            view.setBackgroundColor(backgroundColor)
            view.rootView.setBackgroundColor(backgroundColor)
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}

private fun settingsSystemBarStyle(darkTheme: Boolean): SystemBarStyle = if (darkTheme) {
    SystemBarStyle.dark(Color.TRANSPARENT)
} else {
    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
}

internal fun Window.syncSettingsBackground(backgroundColor: Int) {
    setBackgroundDrawable(ColorDrawable(backgroundColor))
    decorView.setBackgroundColor(backgroundColor)
}

private fun Context.isSystemInDarkTheme(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
