package com.telcoagent.udpclient

import android.content.Context

object ProbePreferences {
    private const val PREFS = "netprobe_probe"
    private const val KEY_OFFSET_CORRECTION = "apply_offset_correction"

    fun isOffsetCorrectionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFFSET_CORRECTION, true)
    }

    fun setOffsetCorrectionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFSET_CORRECTION, enabled)
            .apply()
    }
}
