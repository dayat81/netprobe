package com.telcoagent.udpclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drive test client — runs ICMP MTU discovery each round.
 * Delegates to AbrClient.runMtuProbe() for the actual probing.
 */
class DriveClient {

    /**
     * Run a single drive round: ICMP MTU probe.
     */
    suspend fun runMtuRound(
        host: String = "netprobe.xyz",
        onLog: suspend (String) -> Unit,
    ): AbrResult = withContext(Dispatchers.IO) {
        onLog("─── MTU probe round ───")
        val client = AbrClient()
        client.runMtuProbe(
            host = host,
            onLog = onLog,
        )
    }
}
