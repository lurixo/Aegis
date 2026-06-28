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

package com.aegis.ime.ime.theme

data class ImePalette(
    val keyboardBg: Int,
    val keySurface: Int,
    val functionSurface: Int,
    val keyLabel: Int,
    val keyLabelSecondary: Int,
    val keyHint: Int,
    val keySub: Int,
    val accentBottom: Int,
    val accentLabel: Int,
    val candidateFirst: Int,
    val candidateText: Int,
    val lockedReading: Int,
    val preeditText: Int,
    val separator: Int,
    val gridLine: Int,
    val panelBg: Int,
    val chipBg: Int,
    val chipText: Int,
    val icon: Int,
    val deletable: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val disabled: Int,
    val scrim: Int,
    val shadow: Int,
    val floatSurface: Int,
) {
    companion object {
        val STATIC_LIGHT = ImePalette(
            keyboardBg = 0xFFDCE2EA.toInt(),
            keySurface = 0xFFF4F7FB.toInt(),
            functionSurface = 0xFFB7BEC7.toInt(),
            keyLabel = 0xFF16181B.toInt(),
            keyLabelSecondary = 0xFF3C4A54.toInt(),
            keyHint = 0xFF46525C.toInt(),
            keySub = 0xFF828D94.toInt(),
            accentBottom = 0xFF6750A4.toInt(),
            accentLabel = 0xFFFFFFFF.toInt(),
            candidateFirst = 0xFF6750A4.toInt(),
            candidateText = 0xFF16181B.toInt(),
            lockedReading = 0xFF21005D.toInt(),
            preeditText = 0xFF33639C.toInt(),
            separator = 0xFFBCC5D1.toInt(),
            gridLine = 0xFF000000.toInt(),
            panelBg = 0xFFF4F7FB.toInt(),
            chipBg = 0xFFDCE2EA.toInt(),
            chipText = 0xFF16181B.toInt(),
            icon = 0xFF3C4A54.toInt(),
            deletable = 0xFFD32F2F.toInt(),
            errorContainer = 0xFFF9DEDC.toInt(),
            onErrorContainer = 0xFF410E0B.toInt(),
            disabled = 0xFF9AA5AC.toInt(),
            scrim = 0x66000000,
            shadow = 0x22000000,
            floatSurface = 0xFFFAFCFE.toInt(),
        )

        val STATIC_DARK = ImePalette(
            keyboardBg = 0xFF24282D.toInt(),
            keySurface = 0xFF525A64.toInt(),
            functionSurface = 0xFF383E45.toInt(),
            keyLabel = 0xFFE4E6EA.toInt(),
            keyLabelSecondary = 0xFFC3C9D0.toInt(),
            keyHint = 0xFFB4BCC4.toInt(),
            keySub = 0xFFA7B0B9.toInt(),
            accentBottom = 0xFFD0BCFF.toInt(),
            accentLabel = 0xFF381E72.toInt(),
            candidateFirst = 0xFFD0BCFF.toInt(),
            candidateText = 0xFFE4E6EA.toInt(),
            lockedReading = 0xFFEADDFF.toInt(),
            preeditText = 0xFF9FC9FF.toInt(),
            separator = 0xFF3E444B.toInt(),
            gridLine = 0xFFA5AEB9.toInt(),
            panelBg = 0xFF525A64.toInt(),
            chipBg = 0xFF262B30.toInt(),
            chipText = 0xFFE4E6EA.toInt(),
            icon = 0xFFB3BAC2.toInt(),
            deletable = 0xFFFFB4AB.toInt(),
            errorContainer = 0xFF8C1D18.toInt(),
            onErrorContainer = 0xFFF9DEDC.toInt(),
            disabled = 0xFF5D646B.toInt(),
            scrim = 0x99000000.toInt(),
            shadow = 0x40000000,
            floatSurface = 0xFF5D6672.toInt(),
        )

        const val HINT_INK_FLOOR = 3.0
    }
}
