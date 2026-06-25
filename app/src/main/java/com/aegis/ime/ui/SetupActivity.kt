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

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.R

@Composable
internal fun SettingsPageColumn(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppSettingsPage(title = title, onBack = onBack, content = content)
}

@Composable
internal fun DictSettingsPage(onBack: () -> Unit) {
    SettingsPageColumn(stringResource(R.string.settings_group_dicts_title), onBack) {
        GramDownloadCard()
        DictDownloadCard()
    }
}

@Composable
internal fun Modifier.settingsScrollInsets(
    scrollState: ScrollState,
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
): Modifier = this
    .background(MaterialTheme.colorScheme.background)
    .windowInsetsPadding(bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    .windowInsetsPadding(topInsets.only(WindowInsetsSides.Top))
    .verticalScroll(scrollState)

@Composable
internal fun settingsTopInset(): WindowInsets {
    val density = LocalDensity.current
    val liveTop = WindowInsets.systemBars.union(WindowInsets.displayCutout).getTop(density)
    val view = LocalView.current
    val rootTop = rememberRootTopInsetPx(view)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val seedTop = remember(context, configuration) { synchronousTopInsetPx(context) }
    return WindowInsets(top = resolveTopInsetPx(liveTop, seedTop, rootTop))
}

internal fun resolveTopInsetPx(liveTop: Int, seedTop: Int, rootTop: Int?): Int = when {
    liveTop > 0 -> liveTop
    rootTop != null -> rootTop
    else -> seedTop
}

@Composable
private fun rememberRootTopInsetPx(view: View): Int? {
    val rootTop = rootTopInsetPx(view)
    var rootInsetsDelivered by remember(view) { mutableStateOf(rootTop != null) }
    DisposableEffect(view, rootInsetsDelivered) {
        if (rootInsetsDelivered) {
            onDispose {}
        } else {
            val observer = view.viewTreeObserver
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (rootTopInsetPx(view) != null) rootInsetsDelivered = true
                    return true
                }
            }
            observer.addOnPreDrawListener(listener)
            ViewCompat.requestApplyInsets(view)
            onDispose {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
    }
    return rootTop
}

private fun rootTopInsetPx(view: View): Int? {
    val insets = ViewCompat.getRootWindowInsets(view) ?: return null
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    return insets.getInsets(types).top
}

private fun synchronousTopInsetPx(context: Context): Int {
    val wm = context.getSystemService(WindowManager::class.java) ?: return 0
    val types = AndroidWindowInsets.Type.systemBars() or AndroidWindowInsets.Type.displayCutout()
    val currentMetrics = wm.currentWindowMetrics
    val currentInsets = currentMetrics.windowInsets
    val visibleTop = currentInsets.getInsets(types).top
    val ignoringVisibilityTop = currentInsets.getInsetsIgnoringVisibility(types).top
    val maximumMetrics = wm.maximumWindowMetrics
    val isAttachedToDisplayTop = currentMetrics.bounds.top <= maximumMetrics.bounds.top
    val maximumIgnoringVisibilityTop = if (isAttachedToDisplayTop) {
        maximumMetrics.windowInsets.getInsetsIgnoringVisibility(types).top
    } else {
        0
    }
    return synchronousTopInsetPx(
        visibleTop = visibleTop,
        ignoringVisibilityTop = ignoringVisibilityTop,
        maximumIgnoringVisibilityTop = maximumIgnoringVisibilityTop,
        isAttachedToDisplayTop = isAttachedToDisplayTop,
    )
}

internal fun synchronousTopInsetPx(
    visibleTop: Int,
    ignoringVisibilityTop: Int,
    maximumIgnoringVisibilityTop: Int,
    isAttachedToDisplayTop: Boolean,
): Int = when {
    visibleTop > 0 -> visibleTop
    ignoringVisibilityTop > 0 -> ignoringVisibilityTop
    !isAttachedToDisplayTop -> 0
    maximumIgnoringVisibilityTop > 0 -> maximumIgnoringVisibilityTop
    else -> 0
}
