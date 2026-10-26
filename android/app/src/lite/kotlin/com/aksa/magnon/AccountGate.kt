package com.aksa.magnon

import com.aksa.magnon.databinding.ActivityMainBinding

/** Lite does not ask for a Gmail session. */
internal object AccountGate {
    fun install(host: MainActivity, views: ActivityMainBinding) = Unit

    fun signedIn(host: MainActivity): Boolean = true

    fun allow(host: MainActivity): Boolean = true
}
