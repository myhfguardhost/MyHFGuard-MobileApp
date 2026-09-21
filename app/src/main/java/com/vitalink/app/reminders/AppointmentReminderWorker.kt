package com.vitalink.app.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager

/** Compatibility entry point for appointment saves and older queued jobs. */
class AppointmentReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SystemReminderWorker.checkNow(applicationContext)
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork("appointment_24_hour_reminder")
            work.cancelUniqueWork("appointment_reminder_check_now")
            SystemReminderWorker.schedule(context)
        }
        fun checkNow(context: Context) {
            schedule(context)
            SystemReminderWorker.checkNow(context)
        }
    }
}
