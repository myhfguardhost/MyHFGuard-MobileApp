package com.vitalink.app.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vitalink.app.MainActivity

object NotificationNavigation {
    const val EXTRA_ROUTE = "myhfguard_notification_route"
    private const val ACTION_OPEN_RELATED_PAGE = "com.vitalink.app.OPEN_RELATED_NOTIFICATION"

    fun pendingIntent(context: Context, route: String, requestCode: Int): PendingIntent {
        val safeRoute = route.ifBlank { "dashboard" }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_RELATED_PAGE
            data = Uri.parse("myhfguard://notification/$safeRoute/$requestCode")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROUTE, safeRoute)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
