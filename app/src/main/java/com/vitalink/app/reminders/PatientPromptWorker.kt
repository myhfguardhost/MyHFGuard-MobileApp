package com.vitalink.app.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager

/** Retained so jobs queued by older app versions can finish harmlessly. Advice is now on the dashboard. */
class PatientPromptWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
    companion object {
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork("patient_steps_4pm_prompt")
            work.cancelUniqueWork("patient_saturday_education_prompt")
            android.app.NotificationManager::class.java.let { context.getSystemService(it).cancel(5201) }
            android.app.NotificationManager::class.java.let { context.getSystemService(it).cancel(5202) }
        }
    }
}
