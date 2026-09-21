package com.vitalink.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val miFitnessPackageNames = setOf(
        // Mi Fitness / Xiaomi Wear package names seen on different Android/Honor builds.
        "com.xiaomi.wearable",
        "com.mi.health"
    )

    private val xiaomiDataOrigins = miFitnessPackageNames.map { packageName ->
        DataOrigin(packageName = packageName)
    }.toSet()

    /**
     * True only when Health Connect SDK reports it is available on this device/firmware.
     * On vivo phones the SDK is embedded inside the vivo Health app (com.bbk.healthapp).
     * Always check this before calling [healthConnectClient].
     */
    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) ==
                HealthConnectClient.SDK_AVAILABLE

    val healthConnectClient: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    // Smart-band sync only needs these 3 permissions.
    // Do not block HR capture just because optional BP/weight/sleep permissions
    // are missing or not supported on a specific Honor/Android build.
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        val grantedPermissions =
            healthConnectClient.permissionController.getGrantedPermissions()

        return grantedPermissions.containsAll(permissions)
    }

    suspend fun readTodaySteps(): Long {
        val zoneId = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zoneId)
            .atStartOfDay(zoneId)
            .toInstant()
        val now = Instant.now()

        // Read Xiaomi-source step records and sum them manually.
        // The previous working version used readRecords().sumOf { it.count }.
        // The broken latest version changed this to aggregate(), which can under-read
        // some Mi Fitness synced records on Honor devices.
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                dataOriginFilter = xiaomiDataOrigins
            )
        )

        return response.records.sumOf { it.count }
    }

    suspend fun readLatestHeartRate(): Long? {
        val now = Instant.now()
        val start = now.minus(30, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, now)

        val miFitnessRecords = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = range,
                dataOriginFilter = xiaomiDataOrigins
            )
        ).records

        val records = if (miFitnessRecords.isNotEmpty()) {
            miFitnessRecords
        } else {
            val visibleRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = range
                )
            ).records

            visibleRecords
                .filter { it.metadata.dataOrigin.packageName in miFitnessPackageNames }
                .ifEmpty { visibleRecords }
        }

        return records
            .flatMap { it.samples }
            .maxByOrNull { it.time }
            ?.beatsPerMinute
    }

    suspend fun readLatestSpo2(): Double? {
        val now = Instant.now()
        val start = now.minus(30, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, now)

        val miFitnessRecords = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = range,
                dataOriginFilter = xiaomiDataOrigins
            )
        ).records

        val records = if (miFitnessRecords.isNotEmpty()) {
            miFitnessRecords
        } else {
            val visibleRecords = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = range
                )
            ).records

            visibleRecords
                .filter { it.metadata.dataOrigin.packageName in miFitnessPackageNames }
                .ifEmpty { visibleRecords }
        }

        return records
            .maxByOrNull { it.time }
            ?.percentage
            ?.value
    }

    suspend fun readLatestWeightKg(): Double? {
        val now = Instant.now()
        val start = now.minus(30, ChronoUnit.DAYS)

        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, now)
            )
        )

        return response.records
            .maxByOrNull { it.time }
            ?.weight
            ?.inKilograms
    }
}