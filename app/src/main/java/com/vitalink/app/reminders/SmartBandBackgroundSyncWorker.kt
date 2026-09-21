package com.vitalink.app.reminders

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.vitalink.app.util.AppLanguage
import androidx.datastore.preferences.core.Preferences
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vitalink.app.BuildConfig
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.api.dataStore
import com.vitalink.app.data.model.MetricsSyncRequest
import com.vitalink.app.data.model.Spo2DayInsert
import com.vitalink.app.data.model.StepsDayInsert
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Silently refreshes Mi Band data already shared into Health Connect.
 * Patient-facing refresh reminders are handled only by the 9 PM daily worker.
 */
class SmartBandBackgroundSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val miFitnessPackageNames = setOf(
        // Mi Fitness / Xiaomi Wear package names seen on different Android/Honor builds.
        "com.xiaomi.wearable",
        "com.mi.health"
    )

    private val xiaomiDataOrigins = miFitnessPackageNames.map { packageName ->
        DataOrigin(packageName = packageName)
    }.toSet()

    override suspend fun doWork(): Result {
        val context = applicationContext
        AppLanguage.initialize(context)

        return try {
            val prefs: Preferences = context.dataStore.data.first()
            val patientId = prefs[SessionManager.KEY_PATIENT].orEmpty()
            val accessToken = prefs[SessionManager.KEY_TOKEN].orEmpty()

            if (patientId.isBlank() || accessToken.isBlank()) return Result.success()

            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                markBandVisibility(context, visible = false)
                return Result.success()
            }

            val client = HealthConnectClient.getOrCreate(context)
            val permissions = setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class)
            )
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(permissions)) {
                markBandVisibility(context, visible = false)
                return Result.success()
            }

            val snapshot = readBandSnapshot(client)
            // Keep the 15-minute background sync silent. The patient-facing refresh
            // prompt is sent only by DailyLogReminderWorker at 9:00 PM.
            markBandVisibility(context, snapshot.hasVisibleHealthConnectRecord)

            val api = createApiService(accessToken)
            val today = LocalDate.now().toString()
            val stepsBody = StepsDayInsert(
                patient_id = patientId,
                date = today,
                steps_total = snapshot.steps
            )

            var stepsResponse = api.insertStepsDay(stepsBody)
            if (!stepsResponse.isSuccessful) {
                // Fallback for Supabase projects where patient_id,date unique conflict
                // is not created yet.
                stepsResponse = api.insertStepsDayPlain(stepsBody)
            }

            val validSpo2 = snapshot.spo2?.takeIf { it in 1..100 }
            val spo2Response = if (validSpo2 != null) {
                val spo2Body = Spo2DayInsert(
                    patient_id = patientId,
                    date = today,
                    spo2_min = validSpo2.toDouble(),
                    spo2_max = validSpo2.toDouble(),
                    spo2_avg = validSpo2.toDouble(),
                    spo2_count = 1L
                )

                var response = api.insertSpo2Day(spo2Body)
                if (!response.isSuccessful) {
                    response = api.insertSpo2DayPlain(spo2Body)
                }
                response
            } else {
                null
            }

            // Save HR even when SpO₂ is missing. Some Mi Fitness/Health Connect setups sync
            // heart rate but not oxygen saturation, so HR must not depend on validSpo2.
            if (validSpo2 != null || snapshot.heartRate != null) {
                runCatching {
                    val metricsRequest = MetricsSyncRequest(
                        patient_id = patientId,
                        steps = snapshot.steps,
                        distance = 0L,
                        avg_hr = snapshot.heartRate,
                        avg_spo2 = validSpo2,
                        date = today
                    )
                    var metricsResponse = api.syncMetrics(metricsRequest)
                    if (!metricsResponse.isSuccessful) {
                        metricsResponse = api.syncMetricsPlain(metricsRequest)
                    }
                }
            }

            if (!stepsResponse.isSuccessful || (spo2Response != null && !spo2Response.isSuccessful)) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            markBandVisibility(applicationContext, visible = false)
            Result.retry()
        }
    }

    private fun createApiService(accessToken: String): ApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private suspend fun readBandSnapshot(client: HealthConnectClient): BandSnapshot {
        val zoneId = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant()
        val now = Instant.now()

        // Match the previous accurate version: read raw step records and sum them.
        // Still filtered to Mi Fitness only, so phone sensor steps are ignored.
        val stepRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                dataOriginFilter = xiaomiDataOrigins
            )
        ).records

        val steps = stepRecords.sumOf { it.count }
        val hasStepRecordToday = stepRecords.isNotEmpty()

        val start = now.minus(30, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, now)

        val miFitnessHeartRateRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = range,
                dataOriginFilter = xiaomiDataOrigins
            )
        ).records

        val heartRateRecords = if (miFitnessHeartRateRecords.isNotEmpty()) {
            miFitnessHeartRateRecords
        } else {
            val visibleRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = range
                )
            ).records

            visibleRecords
                .filter { it.metadata.dataOrigin.packageName in miFitnessPackageNames }
                .ifEmpty { visibleRecords }
        }

        val heartRate = heartRateRecords
            .flatMap { it.samples }
            .maxByOrNull { it.time }
            ?.beatsPerMinute

        val miFitnessSpo2Records = client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = range,
                dataOriginFilter = xiaomiDataOrigins
            )
        ).records

        val spo2Records = if (miFitnessSpo2Records.isNotEmpty()) {
            miFitnessSpo2Records
        } else {
            val visibleRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = range
                )
            ).records

            visibleRecords
                .filter { it.metadata.dataOrigin.packageName in miFitnessPackageNames }
                .ifEmpty { visibleRecords }
        }

        val spo2 = spo2Records
            .maxByOrNull { it.time }
            ?.percentage
            ?.value
            ?.toInt()

        return BandSnapshot(
            steps = steps,
            heartRate = heartRate,
            spo2 = spo2,
            hasVisibleHealthConnectRecord = hasStepRecordToday || heartRate != null || spo2 != null
        )
    }

    private data class BandSnapshot(
        val steps: Long,
        val heartRate: Long?,
        val spo2: Int?,
        val hasVisibleHealthConnectRecord: Boolean
    )

    companion object {
        private const val WORK_NAME = "smart_band_health_connect_background_sync"
        private const val ONE_TIME_WORK_NAME = "smart_band_health_connect_sync_now"
        private const val STATUS_PREFS = "smart_band_sync_status"
        private const val KEY_VISIBLE_DATA_DATE = "last_visible_health_connect_date"
        private const val LEGACY_MI_FITNESS_NOTIFICATION_ID = 5001
        private const val LEGACY_CONNECTION_NOTIFICATION_ID = 5002

        private fun markBandVisibility(context: Context, visible: Boolean) {
            val value = if (visible) LocalDate.now().toString() else ""
            context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VISIBLE_DATA_DATE, value)
                .apply()
        }

        fun hasVisibleDataToday(context: Context): Boolean {
            val savedDate = context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VISIBLE_DATA_DATE, null)
            return savedDate == LocalDate.now().toString()
        }

        fun schedule(context: Context) {
            // Remove notifications created by older builds. The periodic sync remains
            // silent; only the 9 PM daily worker may prompt the patient to refresh.
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(LEGACY_MI_FITNESS_NOTIFICATION_ID)
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(LEGACY_CONNECTION_NOTIFICATION_ID)

            val request = PeriodicWorkRequestBuilder<SmartBandBackgroundSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    syncConstraints()
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmartBandBackgroundSyncWorker>()
                .setConstraints(
                    syncConstraints()
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun syncConstraints(): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        }

    }
}
