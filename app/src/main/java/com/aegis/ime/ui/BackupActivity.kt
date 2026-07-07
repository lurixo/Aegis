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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppShapes

internal const val BACKUP_MIN_PASSWORD_LENGTH = 6

@Composable
internal fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        shape = AppShapes.section,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }, shape = MaterialTheme.shapes.extraSmall) {
                Text(
                    stringResource(
                        if (visible) R.string.backup_password_hide
                        else R.string.backup_password_show,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun passwordProblem(password: String, confirm: String): Int? = when {
    password.isEmpty() -> R.string.backup_password_empty
    password.length < BACKUP_MIN_PASSWORD_LENGTH -> R.string.backup_password_too_short
    password != confirm -> R.string.backup_password_mismatch
    else -> null
}
