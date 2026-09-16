package com.vitalink.app.reminders

import android.content.Context
import java.time.LocalDate

/**
 * Allows only one critical/red notification per patient per calendar day.
 * The lock is shared by immediate Self-Check alerts and the hourly
 * health-deviation worker, so the same red status cannot notify twice.
 */
object RedStatusNotificationGate {
    private const val PREFS = "red_status_notification_gate"

    @Synchronized
    fun tryNotify(
        context: Context,
        patientId: String,
        date: String = LocalDate.now().toString()
    ): Boolean {
        val key = key(patientId)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(key, null) == date) return false
        return prefs.edit().putString(key, date).commit()
    }

    @Synchronized
    fun release(context: Context, patientId: String, date: String) {
        val key = key(patientId)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(key, null) == date) {
            prefs.edit().remove(key).apply()
        }
    }

    private fun key(patientId: String): String {
        val safeId = patientId.ifBlank { "device" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "last_red_date_$safeId"
    }
}
