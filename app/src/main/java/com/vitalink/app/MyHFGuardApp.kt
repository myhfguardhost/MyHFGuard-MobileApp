package com.vitalink.app

import android.app.Application
import com.vitalink.app.reminders.AppointmentReminderWorker
import com.vitalink.app.reminders.MedicationReminderWorker
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.reminders.SmartBandBackgroundSyncWorker
import com.vitalink.app.reminders.AdminNotificationWorker
import com.vitalink.app.reminders.SystemReminderWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyHFGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()

        AppLanguage.initialize(applicationContext)

        // Keep the smart-band Health Connect sync scheduled even after the app is reopened.
        // Android WorkManager supports periodic background work with a minimum interval of 15 minutes.
        SmartBandBackgroundSyncWorker.schedule(applicationContext)
        AppointmentReminderWorker.schedule(applicationContext)
        MedicationReminderWorker.scheduleNoonAndNight(applicationContext)
        com.vitalink.app.reminders.LearningReminderWorker.schedule(applicationContext)
        AdminNotificationWorker.schedule(applicationContext)
        SystemReminderWorker.schedule(applicationContext)
    }
}
