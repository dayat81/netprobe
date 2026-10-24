package com.aksa.mysignal

/** Why a speed or video round could not reach the test server. */
internal enum class ReachFailure {
    /** Device has no route for this family (ENETUNREACH). */
    NO_ROUTE,

    /** TCP or TLS to the server failed. */
    UNREACHABLE,

    /** HTTP, player, or other error. Keep the existing retry. */
    OTHER,
}

internal fun classifyReachFailure(error: Throwable): ReachFailure {
    val text = StringBuilder()
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 8) {
        text.append(current.javaClass.name).append(' ')
        current.message?.let { text.append(it).append(' ') }
        current = current.cause
        depth += 1
    }
    val blob = text.toString().lowercase()
    if (
        "enetunreach" in blob ||
        "ehostunreach" in blob ||
        "network is unreachable" in blob ||
        "no route to host" in blob
    ) {
        return ReachFailure.NO_ROUTE
    }
    if (
        "sslhandshake" in blob ||
        "connection closed" in blob ||
        "connection refused" in blob ||
        "econnrefused" in blob ||
        "econnreset" in blob ||
        "unknownhost" in blob ||
        "failed to connect" in blob ||
        "timed out" in blob ||
        "timeout" in blob ||
        "video did not start" in blob ||
        "no video frame" in blob
    ) {
        return ReachFailure.UNREACHABLE
    }
    return ReachFailure.OTHER
}
