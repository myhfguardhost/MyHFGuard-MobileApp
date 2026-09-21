package com.vitalink.app.reminders
import android.content.Context
import androidx.work.*
class MedicationReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
    companion object {
        const val KEY_SLOT = "slot"
        const val SLOT_DAY = "day"
        const val SLOT_NIGHT = "night"
        fun scheduleNoonAndNight(context: Context) {
            listOf("medication_10am_reminder_v2", "medication_9pm_reminder_v2", "medication_10pm_reminder_v2", "medication_12pm_reminder_v3", "medication_9pm_reminder_v3", "medication_alarm_run_day", "medication_alarm_run_night").forEach { WorkManager.getInstance(context).cancelUniqueWork(it) }
            MedicationAlarmReceiver.schedule(context)
        }
    }
}
