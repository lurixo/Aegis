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

package com.aegis.ime.dict

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object ModelDownload {

    enum class CheckFailure { OFFLINE, TIMEOUT, SERVER, PARSE }

    enum class UpdateCheck { OFFLINE, TIMEOUT, UP_TO_DATE, UPDATE, UNKNOWN, SERVER_ERROR, PARSE_ERROR }

    private fun CheckFailure.toUpdateCheck(): UpdateCheck = when (this) {
        CheckFailure.OFFLINE -> UpdateCheck.OFFLINE
        CheckFailure.TIMEOUT -> UpdateCheck.TIMEOUT
        CheckFailure.SERVER -> UpdateCheck.SERVER_ERROR
        CheckFailure.PARSE -> UpdateCheck.PARSE_ERROR
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    internal fun classifyRequestFailure(t: Throwable): CheckFailure =
        identifyRequestFailure(t) ?: CheckFailure.SERVER

    internal fun identifyRequestFailure(t: Throwable): CheckFailure? = when (t) {
        is HttpStatusException -> CheckFailure.SERVER
        else -> when {
            t.hasTimeoutSignal() -> CheckFailure.TIMEOUT
            t is java.net.UnknownHostException -> CheckFailure.OFFLINE
            t is java.net.NoRouteToHostException -> CheckFailure.OFFLINE
            t is java.net.PortUnreachableException -> CheckFailure.OFFLINE
            t is java.net.ConnectException && t.hasExplicitOfflineConnectSignal() -> CheckFailure.OFFLINE
            else -> null
        }
    }

    private fun Throwable.hasTimeoutSignal(): Boolean =
        generateSequence(this) { it.cause }
            .any { error ->
                error is java.net.SocketTimeoutException ||
                    (error is java.net.ConnectException && error.message?.hasTimeoutSignal() == true)
            }

    private fun String.hasTimeoutSignal(): Boolean =
        lowercase(Locale.ROOT).let { "timed out" in it || "etimedout" in it }

    private fun Throwable.hasExplicitOfflineConnectSignal(): Boolean =
        generateSequence(this) { it.cause }
            .any { error ->
                error is java.net.UnknownHostException ||
                    error is java.net.NoRouteToHostException ||
                    error is java.net.PortUnreachableException ||
                    error.message?.hasOfflineConnectSignal() == true
            }

    private fun String.hasOfflineConnectSignal(): Boolean =
        lowercase(Locale.ROOT).let {
            "network is unreachable" in it ||
                "network unreachable" in it ||
                "no route to host" in it ||
                "host is unreachable" in it ||
                "host unreachable" in it ||
                "enetunreach" in it ||
                "ehostunreach" in it
        }

    sealed interface ValidatorProbe {
        data class Reached(val validator: String?) : ValidatorProbe
        data class Failed(val failure: CheckFailure) : ValidatorProbe
    }

    fun remoteValidatorProbe(url: String): ValidatorProbe {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            val code = conn.responseCode
            if (code !in 200..299) ValidatorProbe.Failed(CheckFailure.SERVER)
            else {
                ValidatorProbe.Reached(
                    trustworthyValidator(conn.getHeaderField("ETag"))
                        ?: trustworthyValidator(conn.getHeaderField("Last-Modified")),
                )
            }
        } catch (e: Exception) {
            ValidatorProbe.Failed(classifyRequestFailure(e))
        } finally {
            conn?.disconnect()
        }
    }

    fun validatorComparison(local: String?, remote: String?): UpdateCheck {
        val localValidator = trustworthyValidator(local)
        val remoteValidator = trustworthyValidator(remote)
        return when {
            localValidator == null || remoteValidator == null -> UpdateCheck.UNKNOWN
            remoteValidator == localValidator -> UpdateCheck.UP_TO_DATE
            else -> UpdateCheck.UPDATE
        }
    }

    fun modelUpdateAction(
        present: Boolean,
        local: String?,
        probe: ValidatorProbe,
    ): UpdateCheck? {
        if (!present) return null
        return when (probe) {
            is ValidatorProbe.Failed -> probe.failure.toUpdateCheck()
            is ValidatorProbe.Reached -> validatorComparison(local, probe.validator)
        }
    }

    private fun trustworthyValidator(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("size:", ignoreCase = true) }

    internal fun fetchText(url: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Aegis-resource-updater")
            }
            if (conn.responseCode !in 200..299) throw HttpStatusException(conn.responseCode)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }
}
