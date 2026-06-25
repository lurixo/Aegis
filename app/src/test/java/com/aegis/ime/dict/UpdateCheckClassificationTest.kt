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

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
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


    @Test
    fun modelUpdateActionReportsEachOutcomeDistinctly() {
        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, ModelDownload.modelUpdateAction(true, "e1", ModelDownload.ValidatorProbe.Reached("e1")))
        assertEquals(ModelDownload.UpdateCheck.UPDATE, ModelDownload.modelUpdateAction(true, "e1", ModelDownload.ValidatorProbe.Reached("e2")))
        assertEquals(ModelDownload.UpdateCheck.UNKNOWN, ModelDownload.modelUpdateAction(true, null, ModelDownload.ValidatorProbe.Reached("e2")))
        assertEquals(ModelDownload.UpdateCheck.UNKNOWN, ModelDownload.modelUpdateAction(true, "e1", ModelDownload.ValidatorProbe.Reached(null)))
        assertEquals(ModelDownload.UpdateCheck.OFFLINE, ModelDownload.modelUpdateAction(true, "e1", ModelDownload.ValidatorProbe.Failed(ModelDownload.CheckFailure.OFFLINE)))
        assertEquals(ModelDownload.UpdateCheck.TIMEOUT, ModelDownload.modelUpdateAction(true, "e1", ModelDownload.ValidatorProbe.Failed(ModelDownload.CheckFailure.TIMEOUT)))
        assertEquals(ModelDownload.UpdateCheck.SERVER_ERROR, ModelDownload.modelUpdateAction(true, "e1", ModelDownload.ValidatorProbe.Failed(ModelDownload.CheckFailure.SERVER)))
        assertEquals(ModelDownload.UpdateCheck.PARSE_ERROR, ModelDownload.modelUpdateAction(true, "e1", ModelDownload.ValidatorProbe.Failed(ModelDownload.CheckFailure.PARSE)))
    }

    @Test
    fun modelCheckResolvingAfterDeleteIsDiscarded() {
        assertNull(ModelDownload.modelUpdateAction(false, "e1", ModelDownload.ValidatorProbe.Reached("e2")))
        assertNull(ModelDownload.modelUpdateAction(false, null, ModelDownload.ValidatorProbe.Failed(ModelDownload.CheckFailure.OFFLINE)))
    }

    @Test
    fun modelProbeAgainstErroringServerIsServerNotOffline() {
        listOf(403, 500).forEach { code ->
            val probe = probeHead { it.sendResponseHeaders(code, -1) }
            assertEquals("HTTP $code is a reached server", ModelDownload.CheckFailure.SERVER, (probe as ModelDownload.ValidatorProbe.Failed).failure)
            assertEquals(ModelDownload.UpdateCheck.SERVER_ERROR, ModelDownload.modelUpdateAction(true, "local", probe))
            assertNotEquals(ModelDownload.UpdateCheck.OFFLINE, ModelDownload.modelUpdateAction(true, "local", probe))
        }
    }

    @Test
    fun modelProbeReadsValidatorFromA2xxResponse() {
        val probe = probeHead { exchange ->
            exchange.responseHeaders.add("ETag", "server-etag")
            exchange.sendResponseHeaders(200, -1)
        }
        assertEquals("server-etag", (probe as ModelDownload.ValidatorProbe.Reached).validator)
        assertEquals(ModelDownload.UpdateCheck.UP_TO_DATE, ModelDownload.modelUpdateAction(true, "server-etag", probe))
        assertEquals(ModelDownload.UpdateCheck.UPDATE, ModelDownload.modelUpdateAction(true, "old-etag", probe))
    }

    @Test
    fun modelProbeAgainstUnresolvableHostIsOffline() {
        val probe = ModelDownload.remoteValidatorProbe("http://aegis-nonexistent.invalid/gram")
        assertEquals(ModelDownload.CheckFailure.OFFLINE, (probe as ModelDownload.ValidatorProbe.Failed).failure)
        assertEquals(ModelDownload.UpdateCheck.OFFLINE, ModelDownload.modelUpdateAction(true, "local", probe))
    }


    private fun probeHead(handle: (HttpExchange) -> Unit): ModelDownload.ValidatorProbe =
        withServer(handle) { base -> ModelDownload.remoteValidatorProbe(base + "gram") }

    private fun <T> withServer(handle: (HttpExchange) -> Unit, use: (baseUrl: String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        return try {
            use("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }
}
