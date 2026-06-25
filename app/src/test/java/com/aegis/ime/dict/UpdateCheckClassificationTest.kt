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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckClassificationTest {

    @Test
    fun onlyUnreachableConnectivityFailuresAreOffline() {
        assertEquals(ModelDownload.CheckFailure.OFFLINE, ModelDownload.classifyRequestFailure(UnknownHostException("timeout.example")))
        assertEquals(ModelDownload.CheckFailure.OFFLINE, ModelDownload.classifyRequestFailure(ConnectException("Network is unreachable")))
        assertEquals(ModelDownload.CheckFailure.OFFLINE, ModelDownload.classifyRequestFailure(ConnectException("failed to connect: ENETUNREACH (Network is unreachable)")))
        assertEquals(ModelDownload.CheckFailure.OFFLINE, ModelDownload.classifyRequestFailure(ConnectException("failed to connect: EHOSTUNREACH (No route to host)")))
        assertEquals(ModelDownload.CheckFailure.OFFLINE, ModelDownload.classifyRequestFailure(ConnectException("Host is unreachable")))
        assertEquals(ModelDownload.CheckFailure.OFFLINE, ModelDownload.classifyRequestFailure(ConnectException("wrapped").apply { initCause(NoRouteToHostException()) }))
        assertEquals(ModelDownload.CheckFailure.OFFLINE, ModelDownload.classifyRequestFailure(NoRouteToHostException()))
        assertEquals(ModelDownload.CheckFailure.OFFLINE, ModelDownload.classifyRequestFailure(PortUnreachableException()))
    }

    @Test
    fun reachedButFailedServerIsServerNotOffline() {
        assertEquals(ModelDownload.CheckFailure.SERVER, ModelDownload.classifyRequestFailure(ModelDownload.HttpStatusException(403)))
        assertEquals(ModelDownload.CheckFailure.SERVER, ModelDownload.classifyRequestFailure(ModelDownload.HttpStatusException(500)))
        assertEquals(ModelDownload.CheckFailure.SERVER, ModelDownload.classifyRequestFailure(SSLException("handshake failed")))
        assertEquals(ModelDownload.CheckFailure.SERVER, ModelDownload.classifyRequestFailure(IOException("operation timed out")))
    }

    @Test
    fun refusedConnectFailuresAreServerNotOffline() {
        listOf(
            ConnectException("Connection refused"),
            ConnectException("ECONNREFUSED (Connection refused)"),
        ).forEach { error ->
            val failure = ModelDownload.classifyRequestFailure(error)
            assertEquals(ModelDownload.CheckFailure.SERVER, failure)
            assertNotEquals(ModelDownload.CheckFailure.OFFLINE, failure)
            assertEquals(
                ModelDownload.UpdateCheck.SERVER_ERROR,
                ModelDownload.modelUpdateAction(true, "local", ModelDownload.ValidatorProbe.Failed(failure)),
            )
        }
    }

    @Test
    fun failuresWithoutARecognisedSignalAreNotIdentified() {
        listOf(
            FileNotFoundException("aegis_dict_pack.zip.part: open failed: EISDIR (Is a directory)"),
            IOException("write failed: ENOSPC (No space left on device)"),
            SocketException("Connection reset"),
        ).forEach { error ->
            assertNull(ModelDownload.identifyRequestFailure(error))
            assertEquals(ModelDownload.CheckFailure.SERVER, ModelDownload.classifyRequestFailure(error))
        }
    }

    @Test
    fun timeoutsHaveTheirOwnRetryableOutcome() {
        listOf(
            SocketTimeoutException("Read timed out"),
            IOException("wrapped", SocketTimeoutException("Read timed out")),
            ConnectException("connect timed out"),
            ConnectException("ETIMEDOUT"),
        ).forEach { error ->
            val failure = ModelDownload.classifyRequestFailure(error)
            assertEquals(ModelDownload.CheckFailure.TIMEOUT, failure)
            assertEquals(
                ModelDownload.UpdateCheck.TIMEOUT,
                ModelDownload.modelUpdateAction(true, "local", ModelDownload.ValidatorProbe.Failed(failure)),
            )
        }
    }
}
