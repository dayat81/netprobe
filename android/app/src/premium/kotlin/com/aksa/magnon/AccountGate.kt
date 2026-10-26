package com.aksa.magnon

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.aksa.magnon.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/** Premium tests and packet capture wait for the Gmail session that binds this device. */
internal object AccountGate {
    private var binding: ActivityMainBinding? = null

    fun install(host: MainActivity, views: ActivityMainBinding) {
        binding = views
        views.accountSignIn.setOnClickListener {
            views.accountSignIn.isEnabled = false
            views.accountStatus.setText(R.string.account_signing_in)
            host.lifecycleScope.launch {
                val profile = runCatching {
                    val google = MagnonAccount.requestGoogle(host)
                    MagnonAccount.completeSignIn(host, google)
                }
                views.accountSignIn.isEnabled = true
                profile.onSuccess {
                    views.accountStatus.text = it.email.ifBlank { it.name }
                    show(false)
                    ProbeChrome.start(host)
                    host.refreshStartButtons()
                }.onFailure { error ->
                    val message = error.message.orEmpty()
                    views.accountStatus.setText(when (message) {
                        "sign_in_cancelled" -> R.string.account_sign_in
                        "premium_not_granted" -> R.string.account_not_registered
                        else -> R.string.account_failed
                    })
                }
            }
        }
        show(!signedIn(host))
    }

    fun signedIn(host: MainActivity): Boolean = MagnonAccount.hasSession(host)

    fun allow(host: MainActivity): Boolean {
        if (signedIn(host)) {
            show(false)
            return true
        }
        show(true)
        return false
    }

    private fun show(needed: Boolean) {
        binding?.accountGate?.visibility = if (needed) View.VISIBLE else View.GONE
    }
}
