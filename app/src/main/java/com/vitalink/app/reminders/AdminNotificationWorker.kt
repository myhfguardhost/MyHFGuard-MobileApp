package com.vitalink.app.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vitalink.app.data.model.PatientNotification
import com.vitalink.app.navigation.Route
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Fetches care-team messages; it does not re-enable medicine or input reminders. */
class AdminNotificationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val auth = ReminderApiClient.open(context) ?: return Result.success()
        return try {
            val response = auth.api.getPatientNotifications("eq.${auth.patientId}")
            if (!response.isSuccessful) return Result.retry()
            response.body().orEmpty().filter(::isDue).forEach { showOnce(context, auth.patientId, it) }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    private fun isDue(row: PatientNotification): Boolean {
        val scheduled = row.scheduled_for.orEmpty()
        return scheduled.isBlank() || runCatching { !Instant.parse(scheduled).isAfter(Instant.now()) }.getOrDefault(true)
    }

    private fun showOnce(context: Context, patientId: String, row: PatientNotification) {
        val identity = row.id.ifBlank { row.dedupe_key.orEmpty() }
        if (identity.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$patientId|$identity"
        val title = row.title?.trim().takeUnless { it.isNullOrBlank() } ?: "Message from your care team"
        val message = row.message?.trim().takeUnless { it.isNullOrBlank() } ?: "You have a new message from your care team."
        // History is independent of Android permission and previous tray delivery.
        // A separate marker backfills older deliveries once without restoring cleared history.
        val historyKey = "history|$key"
        if (!prefs.getBoolean(historyKey, false)) {
            NotificationHistoryStore.record(context, title, message, Route.Dashboard.path)
            prefs.edit().putBoolean(historyKey, true).apply()
        }
        if (prefs.getBoolean(key, false)) return
        // Keep tray delivery pending until notification permission is available.
        if (!notificationsAllowed(context)) return

        val notificationId = ("admin|$patientId|$identity").hashCode()
        val channel = NotificationSoundChannels.ensureChannel(
            context,
            NotificationSoundChannels.Sound.LOG_ALL_DATA,
            "Care team messages",
            "Messages sent by your care team.",
            NotificationManager.IMPORTANCE_HIGH
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(NotificationSoundChannels.soundUri(context, NotificationSoundChannels.Sound.LOG_ALL_DATA))
            .setContentIntent(NotificationNavigation.pendingIntent(context, Route.Dashboard.path, notificationId))
            .setAutoCancel(true).build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            prefs.edit().putBoolean(key, true).apply()
        } catch (_: SecurityException) { }
    }

    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PERIODIC_WORK = "admin_patient_messages"
        private const val IMMEDIATE_WORK = "admin_patient_messages_now"
        private const val PREFS = "admin_patient_messages_seen"
        fun schedule(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<AdminNotificationWorker>(15, TimeUnit.MINUTES).build())
        }
        fun checkNow(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AdminNotificationWorker>().build())
        }
    }
}
