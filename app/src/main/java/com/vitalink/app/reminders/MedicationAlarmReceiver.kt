package com.vitalink.app.reminders
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.TimeZone
class MedicationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        // The explicit alarm should immediately run the reminder check. Boot,
        // clock and timezone changes only need to rebuild the next alarms, but
        // checking now also covers a device that was restarted during a slot.
        SystemReminderWorker.checkNow(appContext)
        schedule(appContext)
    }
    companion object {
        private const val ACTION = "com.vitalink.app.MEDICATION_ALARM"
        private const val NOON_ID = 5101
        private const val NIGHT_ID = 5102

        fun schedule(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            scheduleOne(context, manager, NOON_ID, 12)
            scheduleOne(context, manager, NIGHT_ID, 21)
        }

        private fun scheduleOne(context: Context, manager: AlarmManager, requestCode: Int, hour: Int) {
            val zone = TimeZone.getTimeZone("Asia/Kuala_Lumpur")
            val now = Calendar.getInstance(zone)
            val next = Calendar.getInstance(zone).apply {
                timeInMillis = now.timeInMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DATE, 1)
            }
            val intent = Intent(context, MedicationAlarmReceiver::class.java).apply { action = ACTION }
            val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                // Exact alarms are a special app access on Android 12+. The
                // inexact idle-safe fallback still wakes the app when exact
                // alarm access has not yet been granted.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
                }.onFailure {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
                }
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
            }
        }

        fun cancel(context: Context) {
            listOf(NOON_ID, NIGHT_ID).forEach { id ->
                val intent = Intent(context, MedicationAlarmReceiver::class.java).apply { action = ACTION }
                PendingIntent.getBroadcast(context,id,intent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel() }
            }
        }
    }
}
