package com.vitalink.app.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vitalink.app.data.model.Appointment
import com.vitalink.app.navigation.Route
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.MalaysiaDateTime
import com.vitalink.app.util.MedicationTiming
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Patient reminders shown in Android notifications and the in-app history.
 */
class SystemReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!allowed(context)) return Result.success()
        val auth = ReminderApiClient.open(context) ?: return Result.success()
        return try {
            val now = MalaysiaDateTime.now()
            val today = now.toLocalDate().toString()
            val api = auth.api
            val medications = api.getMedications("eq.${auth.patientId}").body().orEmpty().filter { it.is_active }
            val medicationSchedules = runCatching {
                api.getMedicationSchedule("eq.${auth.patientId}").body().orEmpty()
            }.getOrDefault(emptyList())
            // Match the appointment page: entries can come from either table.
            val appointmentRows = runCatching {
                api.getAppointments("eq.${auth.patientId}").body().orEmpty()
            }.getOrDefault(emptyList())
            val reminderRows = runCatching {
                api.getReminders("eq.${auth.patientId}").body().orEmpty()
            }.getOrDefault(emptyList())
            val appointments = (appointmentRows + reminderRows
                .filter { it.type.equals("appointment", ignoreCase = true) }
                .filterNot { it.status?.lowercase() in setOf("cancelled", "canceled", "completed", "done") }
                .mapNotNull { row ->
                    val due = MalaysiaDateTime.parseTimestamp(row.due_ts) ?: return@mapNotNull null
                    Appointment(
                        id = row.id, patient_id = row.patient_id, title = row.title,
                        appointment_date = due.toLocalDate().toString(),
                        appointment_time = due.toLocalTime().toString(),
                        notes = row.notes, source = "reminders"
                    )
                }).distinctBy {
                    listOf(it.title.orEmpty().trim().lowercase(), it.appointment_date?.take(10), it.appointment_time?.take(5))
                }
            val weights = api.getWeightDay("eq.${auth.patientId}").body().orEmpty()
            val bp = api.getBpEvents("eq.${auth.patientId}").body().orEmpty()
            val symptoms = api.getSymptomLogs("eq.${auth.patientId}").body().orEmpty()
            val water = api.getWaterLogs("eq.${auth.patientId}").body().orEmpty()
            val steps = api.getStepsDay("eq.${auth.patientId}").body().orEmpty()
            val profile = api.getProfile("eq.${auth.patientId}").body().orEmpty().firstOrNull()

            // My Medication saves the patient's medicines in profiles.current_medication,
            // while administrator-managed medicines use the medication/medication_schedule
            // tables. Read both sources so reminders are not silently skipped when the
            // structured table is empty.
            val profileMedications = profile?.current_medication
                ?.let(MedicationTiming::parseProfile)
                .orEmpty()
            val medicationSlot = MedicationTiming.visibleSlot(now)
            val dueMedications = medicationSlot?.let { slot ->
                MedicationTiming.due(profileMedications, medications, medicationSchedules, slot)
            }.orEmpty()

            if (dueMedications.isNotEmpty()) {
                val slotKey = when (medicationSlot) {
                    MedicationTiming.DAY -> "medicine_day"
                    MedicationTiming.NIGHT -> "medicine_night"
                    else -> null
                }
                if (slotKey != null) {
                    val medicineNames = dueMedications.joinToString(", ") { it.name }.take(180)
                    val title = AppLanguage.text(
                        "Medicine reminder",
                        "Peringatan ubat",
                        "用药提醒",
                        "மருந்து நினைவூட்டல்"
                    )
                    val message = if (medicationSlot == MedicationTiming.DAY) {
                        AppLanguage.text(
                            "It is time for your daytime medicine${if (medicineNames.isBlank()) "." else ": $medicineNames."}",
                            "Sudah tiba masa untuk ubat siang anda${if (medicineNames.isBlank()) "." else ": $medicineNames."}",
                            "现在是服用日间药物的时间${if (medicineNames.isBlank()) "。" else "：$medicineNames。"}",
                            "உங்கள் பகல் மருந்தை எடுத்துக்கொள்ள வேண்டிய நேரம் இது${if (medicineNames.isBlank()) "." else ": $medicineNames."}"
                        )
                    } else {
                        AppLanguage.text(
                            "It is time for your night medicine${if (medicineNames.isBlank()) "." else ": $medicineNames."}",
                            "Sudah tiba masa untuk ubat malam anda${if (medicineNames.isBlank()) "." else ": $medicineNames."}",
                            "现在是服用夜间药物的时间${if (medicineNames.isBlank()) "。" else "：$medicineNames。"}",
                            "உங்கள் இரவு மருந்தை எடுத்துக்கொள்ள வேண்டிய நேரம் இது${if (medicineNames.isBlank()) "." else ": $medicineNames."}"
                        )
                    }
                    postOnce(context, auth.patientId, "$today|$slotKey", NotificationSoundChannels.Sound.MEDICINE, title, message, Route.Dashboard.path)
                }
            }

            if (now.hour >= 18) {
                if (weights.none { it.date == today }) postOnce(context, auth.patientId, "$today|weight", NotificationSoundChannels.Sound.WEIGHT, "Weight reminder", "Please record today's weight.", Route.SelfCheck.focus("weight"))
                if (bp.none { (it.reading_date ?: it.recorded_at?.take(10)) == today }) postOnce(context, auth.patientId, "$today|bp", NotificationSoundChannels.Sound.BLOOD_PRESSURE, "Blood pressure reminder", "Please record today's blood pressure.", Route.SelfCheck.focus("bp"))
                if (symptoms.none { (it.date ?: it.logged_at?.take(10) ?: it.created_at?.take(10)) == today }) postOnce(context, auth.patientId, "$today|symptom", NotificationSoundChannels.Sound.SYMPTOM, "Symptom reminder", "Please complete today's symptom check.", Route.SelfCheck.focus("symptom"))
                val waterToday = water.firstOrNull { it.entry_date == today }
                if (waterToday?.water_intake_ml == null && waterToday?.water_ml == null) postOnce(context, auth.patientId, "$today|water", NotificationSoundChannels.Sound.WATER, "Water reminder", "Please record today's water intake.", Route.WaterSalt.focus("water"))
                if (waterToday?.salt_score == null) postOnce(context, auth.patientId, "$today|salt", NotificationSoundChannels.Sound.SALT, "Salt reminder", "Please record today's salt intake.", Route.WaterSalt.focus("salt"))
            }

            if (now.hour >= 17) {
                val todaySteps = steps.firstOrNull { it.date == today }?.steps_total ?: 0L
                val target = profile?.target_steps?.toLong() ?: 3000L
                if (todaySteps < target) postOnce(context, auth.patientId, "$today|steps", NotificationSoundChannels.Sound.STEPS, "Daily step reminder", "You have $todaySteps of $target steps today.", Route.Exercise.path)
            }
            appointments.forEach { appointment -> notifyAppointmentIfDue(context, auth.patientId, appointment, now) }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    private fun notifyAppointmentIfDue(context: Context, patientId: String, item: Appointment, now: LocalDateTime) {
        val date = item.appointment_date?.take(10) ?: return
        val time = item.appointment_time?.take(5) ?: return
        val scheduled = runCatching { LocalDateTime.of(java.time.LocalDate.parse(date), LocalTime.parse(time)) }.getOrNull() ?: return
        val minutes = Duration.between(now, scheduled).toMinutes()
        val appointmentKey = "${item.title.orEmpty().trim().lowercase()}|$date|$time"
        when {
            minutes in 0..30 -> postOnce(context, patientId, "appointment_soon|$appointmentKey", NotificationSoundChannels.Sound.APPOINTMENT,
                "Appointment reminder", "${item.title ?: "Appointment"} is in about $minutes minutes.", Route.Medication.path)
            // A two-hour window makes the one-day reminder reliable with Android's
            // 15-minute background scheduling, while its key prevents duplicates.
            minutes in 1380..1500 -> postOnce(context, patientId, "appointment_day_before|$appointmentKey", NotificationSoundChannels.Sound.APPOINTMENT,
                "Appointment tomorrow", "${item.title ?: "Appointment"} is tomorrow at $time.", Route.Medication.path)
        }
    }

    private fun postOnce(context: Context, patientId: String, key: String, sound: NotificationSoundChannels.Sound, title: String, message: String, route: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("$patientId|$key", false)) return
        val channel = NotificationSoundChannels.ensureChannel(context, sound, title, message, NotificationManager.IMPORTANCE_DEFAULT)
        val id = ("system|$patientId|$key").hashCode()
        context.getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message)).setSound(NotificationSoundChannels.soundUri(context, sound))
            .setContentIntent(NotificationNavigation.pendingIntent(context, route, id)).setAutoCancel(true).build())
        NotificationHistoryStore.record(context, title, message, route)
        prefs.edit().putBoolean("$patientId|$key", true).apply()
    }

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    companion object {
        private const val PREFS = "system_only_reminders"
        fun schedule(context: Context) = WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork("system_only_reminders", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<SystemReminderWorker>(15, TimeUnit.MINUTES).build())
        fun checkNow(context: Context) = WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("system_only_reminders_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SystemReminderWorker>().build())
    }
}
