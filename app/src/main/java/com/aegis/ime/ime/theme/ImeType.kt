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

/**
 * U-polish: a small MD3-flavoured type scale (SP) for the classic-View surfaces (panels / copy bar). It
 * collapses the ~13 ad-hoc near-duplicate sizes (9/10/11/13/15/17/20/21…) down to five roles so the panels
 * share one text rhythm. NOTE: the self-drawn views (CandidateView / KeyboardView / PreeditView) feed their
 * sizes into measureText()/cell layout, so they are intentionally NOT snapped here.
 */
object ImeType {
    const val caption = 12f // labelSmall — badges / hints / micro
    const val label = 14f   // labelLarge — secondary labels / chips
    const val body = 16f    // bodyLarge — list / card / button text
    const val title = 18f   // titleMedium — bar buttons / headers
    const val display = 22f // large symbol / emoji tiles
}
