package com.vitalink.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vitalink.app.navigation.AppNavHost
import com.vitalink.app.ui.components.InternetConnectionGuard
import com.vitalink.app.reminders.AppointmentReminderWorker
import com.vitalink.app.reminders.DailyLogReminderWorker
import com.vitalink.app.reminders.HealthDeviationWorker
import com.vitalink.app.reminders.MedicationReminderWorker
import com.vitalink.app.reminders.NotificationNavigation
import com.vitalink.app.reminders.PatientPromptWorker
import com.vitalink.app.reminders.AdminNotificationWorker
import com.vitalink.app.reminders.SystemReminderWorker
import com.vitalink.app.reminders.SmartBandBackgroundSyncWorker
import com.vitalink.app.ui.theme.MyHFGuardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var notificationRoute by mutableStateOf<String?>(null)
    private var miFitnessReminderPending by mutableStateOf(true)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationRoute = extractNotificationRoute(intent)
        miFitnessReminderPending = savedInstanceState?.getBoolean(STATE_MI_FITNESS_REMINDER, true) ?: true

        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        DailyLogReminderWorker.schedule(applicationContext)
        MedicationReminderWorker.scheduleNoonAndNight(applicationContext)
        AppointmentReminderWorker.schedule(applicationContext)
        PatientPromptWorker.schedule(applicationContext)
        HealthDeviationWorker.schedule(applicationContext)
        AdminNotificationWorker.schedule(applicationContext)
        AdminNotificationWorker.checkNow(applicationContext)
        SystemReminderWorker.schedule(applicationContext)
        SystemReminderWorker.checkNow(applicationContext)
        SmartBandBackgroundSyncWorker.syncNow(applicationContext)

        setContent {
            MyHFGuardTheme {
                InternetConnectionGuard(lifecycle = lifecycle) { connectionPromptVisible ->
                    AppNavHost(
                        notificationRoute = notificationRoute,
                        onNotificationRouteHandled = { notificationRoute = null },
                        showMiFitnessReminder = miFitnessReminderPending && !connectionPromptVisible,
                        onMiFitnessReminderHandled = { miFitnessReminderPending = false }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_MI_FITNESS_REMINDER, miFitnessReminderPending)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRoute = extractNotificationRoute(intent)
    }

    private fun extractNotificationRoute(intent: Intent?): String? {
        val route = intent?.getStringExtra(NotificationNavigation.EXTRA_ROUTE)
            ?.takeIf { it.isNotBlank() }
        intent?.removeExtra(NotificationNavigation.EXTRA_ROUTE)
        return route
    }

    private companion object {
        const val STATE_MI_FITNESS_REMINDER = "state_mi_fitness_reminder"
    }
}
