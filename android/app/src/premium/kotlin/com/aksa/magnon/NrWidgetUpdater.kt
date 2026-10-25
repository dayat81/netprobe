package com.aksa.magnon

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NrWidgetUpdater {
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun updateAll(context: Context, snapshot: NrSnapshot?) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, NrWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return
        ids.forEach { updateOne(context, manager, it, snapshot) }
    }

    fun updateOne(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: NrSnapshot?,
    ) {
        manager.updateAppWidget(appWidgetId, buildViews(context, snapshot))
    }

    fun buildViews(context: Context, snapshot: NrSnapshot?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_nr_status)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)

        if (snapshot == null) {
            views.setTextViewText(R.id.widget_path, context.getString(R.string.widget_placeholder))
            views.setTextViewText(R.id.widget_verdict, "—")
            views.setTextViewText(R.id.widget_layers, "ov=n/a  nr=n/a  data=n/a")
            views.setTextViewText(R.id.widget_updated, "")
            views.setInt(R.id.widget_root, "setBackgroundColor",
                ContextCompat.getColor(context, R.color.surface))
            return views
        }

        if (snapshot.error != null) {
            views.setTextViewText(R.id.widget_path, "need_perms")
            views.setTextViewText(R.id.widget_verdict, snapshot.error)
            views.setTextViewText(R.id.widget_layers, "Grant Location + Phone in netprobe")
            views.setTextViewText(
                R.id.widget_updated,
                "Updated ${timeFmt.format(Date(snapshot.tsMs))}",
            )
            views.setInt(
                R.id.widget_root,
                "setBackgroundColor",
                ContextCompat.getColor(context, R.color.verdict_lte),
            )
            return views
        }

        val path = NrPathStatus.from(snapshot.verdict.label)
        views.setTextViewText(R.id.widget_path, path.title)
        views.setTextViewText(R.id.widget_verdict, snapshot.verdict.summary)
        views.setTextViewText(
            R.id.widget_layers,
            "ov=${NrLabels.override(snapshot.overrideNetworkType)}  " +
                "nr=${NrLabels.nrState(snapshot.nrState)}  " +
                "data=${NrLabels.networkType(snapshot.dataNetworkType)}",
        )
        views.setTextViewText(
            R.id.widget_updated,
            "Updated ${timeFmt.format(Date(snapshot.tsMs))}",
        )
        views.setInt(R.id.widget_root, "setBackgroundColor", pathColor(context, path))
        // Ensure text stays readable on colored backgrounds.
        val fg = Color.WHITE
        views.setTextColor(R.id.widget_brand, 0xCCFFFFFF.toInt())
        views.setTextColor(R.id.widget_path, fg)
        views.setTextColor(R.id.widget_verdict, 0xEEFFFFFF.toInt())
        views.setTextColor(R.id.widget_layers, 0xDDFFFFFF.toInt())
        views.setTextColor(R.id.widget_updated, 0xBBFFFFFF.toInt())
        views.setViewVisibility(R.id.widget_brand, View.VISIBLE)
        return views
    }

    private fun pathColor(context: Context, path: NrPathStatus): Int = when (path) {
        NrPathStatus.ADVERTISED_ONLY ->
            ContextCompat.getColor(context, R.color.verdict_advertised)
        NrPathStatus.DATA_PATH ->
            ContextCompat.getColor(context, R.color.verdict_connected)
        NrPathStatus.LTE_ONLY ->
            ContextCompat.getColor(context, R.color.verdict_lte)
        NrPathStatus.UNKNOWN ->
            ContextCompat.getColor(context, R.color.surface)
    }
}
