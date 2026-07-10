package com.aegis.ime

import android.graphics.Rect
import android.inputmethodservice.InputMethodService

internal data class ImeWindowInsetsSpec(
    val contentTop: Int,
    val visibleTop: Int,
    val touchableInsets: Int,
    val touchableRegion: Rect?,
)

internal object LandscapeImeWindowPolicy {
    fun resolve(
        compactLandscape: Boolean,
        normalTop: Int,
        windowBottom: Int,
        surfaceBounds: Rect,
    ): ImeWindowInsetsSpec {
        val validFloatingSurface = compactLandscape && !surfaceBounds.isEmpty
        return if (validFloatingSurface) {
            ImeWindowInsetsSpec(
                contentTop = windowBottom,
                visibleTop = windowBottom,
                touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION,
                touchableRegion = Rect(surfaceBounds),
            )
        } else {
            ImeWindowInsetsSpec(
                contentTop = normalTop,
                visibleTop = normalTop,
                touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE,
                touchableRegion = null,
            )
        }
    }
}
