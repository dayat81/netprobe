package com.aksa.mysignal

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Shows the sampler's latest NR verdict; the widget never samples on its own. */
class NrWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        RadioService.start(context)
        val snap = RadioState.latest?.toNrSnapshot()
        appWidgetIds.forEach { id ->
            NrWidgetUpdater.updateOne(context, appWidgetManager, id, snap)
        }
    }

    override fun onEnabled(context: Context) {
        RadioService.start(context)
    }
}
