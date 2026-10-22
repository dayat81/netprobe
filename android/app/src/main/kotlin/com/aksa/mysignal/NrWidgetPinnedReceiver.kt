package com.aksa.mysignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Fired after the user confirms pinning the home-screen widget. */
class NrWidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PINNED) return
        RadioService.start(context.applicationContext)
        Toast.makeText(context, R.string.widget_pinned_ok, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_PINNED = "com.aksa.mysignal.action.WIDGET_PINNED"
    }
}
