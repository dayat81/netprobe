package com.aksa.magnon

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import javax.net.ssl.SSLHandshakeException

class ServerReachTest {
    @Test
    fun handshakeClosedIsUnreachable() {
        val error = SSLHandshakeException("connection closed")
        error.initCause(EOFException("connection closed"))
        assertEquals(ReachFailure.UNREACHABLE, classifyReachFailure(error))
    }

    @Test
    fun noRouteBeatsFailedToConnect() {
        val error = java.net.ConnectException(
            "failed to connect to /2600:1901:0:f5fe:: (port 443): ENETUNREACH (Network is unreachable)",
        )
        assertEquals(ReachFailure.NO_ROUTE, classifyReachFailure(error))
    }

    @Test
    fun videoDidNotStartIsUnreachable() {
        assertEquals(
            ReachFailure.UNREACHABLE,
            classifyReachFailure(IllegalStateException("video did not start")),
        )
    }

    @Test
    fun otherErrorsStayOther() {
        assertEquals(ReachFailure.OTHER, classifyReachFailure(IllegalStateException("http 500")))
    }
}
