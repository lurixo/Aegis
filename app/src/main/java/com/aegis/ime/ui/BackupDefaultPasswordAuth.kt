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

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.ComponentActivity

internal interface BackupDefaultPasswordAuthenticator {
    fun canAuthenticate(): Boolean
    fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onUnavailable: () -> Unit,
        onCanceledOrFailed: () -> Unit,
    )
    fun cancel()
}

internal class PlatformBackupDefaultPasswordAuthenticator(
    private val activity: ComponentActivity,
) : BackupDefaultPasswordAuthenticator {
    private var cancellationSignal: CancellationSignal? = null

    override fun canAuthenticate(): Boolean = runCatching {
        activity.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BACKUP_DEFAULT_PASSWORD_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    override fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onUnavailable: () -> Unit,
        onCanceledOrFailed: () -> Unit,
    ) {
        if (!canAuthenticate()) {
            onUnavailable()
            return
        }
        cancel()
        val signal = CancellationSignal()
        cancellationSignal = signal
        runCatching {
            BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BACKUP_DEFAULT_PASSWORD_AUTHENTICATORS)
                .build()
                .authenticate(
                    signal,
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            cancellationSignal = null
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            cancellationSignal = null
                            onCanceledOrFailed()
                        }
                    },
                )
        }.onFailure {
            cancellationSignal = null
            onUnavailable()
        }
    }

    override fun cancel() {
        cancellationSignal?.cancel()
        cancellationSignal = null
    }
}

private const val BACKUP_DEFAULT_PASSWORD_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
