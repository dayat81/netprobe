package com.telcoagent.udpclient

import android.content.Context

object ProviderResolver {
    fun needsIspLookup(context: Context): Boolean {
        return NetworkInfoCollector.usesExternalProviderLookup(context)
    }

    fun resolveOperator(context: Context, cellularOperator: String?): String? {
        if (!needsIspLookup(context)) {
            return cellularOperator
        }
        return IspLookup.displayName(IspLookup.cached()) ?: cellularOperator
    }
}
