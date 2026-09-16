package com.vitalink.app.ui.screens

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalink.app.R
import com.vitalink.app.HealthConnectPermissionActivity
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.model.*
import com.vitalink.app.navigation.Route
import com.vitalink.app.reminders.NotificationNavigation
import com.vitalink.app.reminders.NotificationHistoryStore
import com.vitalink.app.util.AlertEngine
import com.vitalink.app.util.AlertLevel
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.MalaysiaDateTime
import com.vitalink.app.ui.theme.CautionRed
import com.vitalink.app.ui.theme.CautionYellow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import com.vitalink.app.util.DailyRecordDate
import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import javax.inject.Inject

/** True when the supplied ISO date/timestamp falls within today and the previous 6 days. */
private fun isDashboardLatestSevenDays(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return false
    val today = LocalDate.now()
    val start = today.minusDays(6)
    return !date.isBefore(start) && !date.isAfter(today)
}

data class DashState(
    val firstName: String = "",
    val patientId: String = "",
    val summary: PatientSummary? = null,
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val syncMessage: String? = null,
    val connectionAlertMessage: String? = null,
    val missingToday: List<String> = emptyList(),
    // Medicine added in My Medication page is stored in profiles.current_medication.
    // Keep this list in dashboard so both pages show the same medicine record.
    val profileMedications: List<MedicationEntry> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val medicationSchedules: List<MedicationSchedule> = emptyList(),
    val appointments: List<Appointment> = emptyList(),
    val reminders: List<ReminderRow> = emptyList(),
    val weightHistory: List<WeightDay> = emptyList(),
    val bpHistory: List<BpEvent> = emptyList(),
    val symptomHistory: List<SymptomLog> = emptyList(),
    val stepsHistory: List<StepsDay> = emptyList(),
    val bandMetricHistory: List<SmartBandDailyMetric> = emptyList(),
    val targetSteps: Int = 3000,
    val todayWaterIntakeMl: Int? = null,
    val todayWaterLimitMl: Int? = null,
    val todaySaltScore: Int? = null,
    val todaySaltStatus: String? = null,

    val syncedSteps: Int? = null,
    val syncedHeartRate: Int? = null,
    val syncedSpo2: Int? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val session: SessionManager,
    private val api: ApiService
) : ViewModel() {

    private val _s = MutableStateFlow(DashState())
    val state = _s.asStateFlow()
    private var syncSaveJob: Job? = null

    init {
        viewModelScope.launch {
            combine(session.firstName, session.patientId) { name, pid ->
                name to pid
            }.collect { (name, pid) ->
                _s.update {
                    it.copy(
                        firstName = name ?: "",
                        patientId = pid ?: ""
                    )
                }

                if (!pid.isNullOrBlank()) {
                    loadSummary(pid)
                }
            }
        }
    }

    fun refresh() {
        val pid = _s.value.patientId
        if (pid.isNotBlank() && !_s.value.loading) loadSummary(pid)
    }

    private fun loadSummary(pid: String) {
        viewModelScope.launch {
            _s.update { it.copy(loading = true) }

            try {
                val filter = "eq.$pid"
                // The overview is a patient-facing daily screen, so it follows the
                // calendar date configured on this phone rather than a fixed zone.
                val dashboardToday = LocalDate.now()
                val today = dashboardToday.toString()

                val failed = mutableSetOf<String>()
                val previous = _s.value
                suspend fun <T> readRows(key: String, fallback: List<T>, request: suspend () -> retrofit2.Response<List<T>>): List<T> = try {
                    val response = request()
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code()}")
                    response.body() ?: throw IllegalStateException("Empty response")
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { failed.add(key); fallback }
                val bpTask = async { readRows("BP", previous.bpHistory) { api.getBpEvents(filter) } }
                val weightTask = async { readRows("Weight", previous.weightHistory) { api.getWeightDay(filter) } }
                val stepsTask = async { readRows("Steps", previous.stepsHistory) { api.getStepsDay(filter) } }
                val bandTask = async { readRows("Band", previous.bandMetricHistory) { api.getSmartBandMetrics(filter) } }
                val spo2Task = async { readRows("SpO2", emptyList()) { api.getSpo2Day(filter) } }
                val symptomTask = async { readRows("Symptoms", previous.symptomHistory) { api.getSymptomLogs(filter) } }
                val waterTask = async { readRows("Water", emptyList()) { api.getWaterLogs(filter) } }
                val profileTask = async { readRows("Profile", emptyList()) { api.getProfile(filter) } }
                val medicineTask = async { readRows("Medicines", previous.medications) { api.getMedications(filter) } }
                val scheduleTask = async { readRows("Schedules", previous.medicationSchedules) { api.getMedicationSchedule(filter) } }
                val appointmentTask = async { readRows("Appointments", previous.appointments) { api.getAppointments(filter) } }
                val reminderTask = async { readRows("Reminders", previous.reminders) { api.getReminders(filter) } }
                val bpRows = bpTask.await().sortedByDescending { it.reading_date ?: it.recorded_at }
                val latestBp = bpRows.firstOrNull()
                val stepsRows = stepsTask.await()
                val sortedStepsRows = stepsRows.sortedWith(
                    compareByDescending<StepsDay> { it.date }
                        .thenByDescending { it.updated_at ?: "" }
                        .thenByDescending { it.created_at ?: "" }
                )
                val todaySteps = sortedStepsRows.firstOrNull {
                    DailyRecordDate.matchesSystemDate(dashboardToday, it.date, it.updated_at, it.created_at)
                }
                val spo2Rows = spo2Task.await()
                val todaySpo2 = spo2Rows.firstOrNull {
                    DailyRecordDate.matchesSystemDate(dashboardToday, it.date, it.updated_at, it.created_at)
                }
                val bandMetricsRows = bandTask.await()
                val todayBandMetrics = bandMetricsRows.firstOrNull {
                    DailyRecordDate.matchesSystemDate(dashboardToday, it.date, it.updated_at, it.created_at)
                }
                
                val weightRows = weightTask.await().sortedByDescending { it.date }
                val latestWeightRow = weightRows.firstOrNull()
                val symptomRows = symptomTask.await()
                val waterRows = waterTask.await()
                val profile = profileTask.await().firstOrNull()
                val profileMedicationRows = parseCurrentMedication(profile?.current_medication.orEmpty())
                val medicationRows = medicineTask.await()
                val scheduleRows = scheduleTask.await()
                val appointmentRowsRaw = appointmentTask.await()
                val appointmentRows = appointmentRowsRaw
                    .filter { it.appointment_date?.take(10)?.let { d -> d >= today } ?: true }
                    .sortedWith(compareBy<Appointment> { it.appointment_date ?: "9999-12-31" }.thenBy { it.appointment_time ?: "" })

                val reminderRowsRaw = reminderTask.await()
                val reminderRows = reminderRowsRaw
                    .filter { reminder ->
                        val reminderDate = MalaysiaDateTime.localDate(reminder.due_ts)?.toString()
                        val status = reminder.status.orEmpty().lowercase()
                        (reminderDate == null || reminderDate >= today) && status !in listOf("done", "completed", "cancelled", "canceled")
                    }
                    .sortedBy { it.due_ts ?: "9999-12-31T23:59:59" }

                val todayWeightEntry = weightRows.firstOrNull {
                    DailyRecordDate.matchesSystemDate(dashboardToday, it.date)
                }
                val todayWeight = todayWeightEntry
                val todaySymptom = symptomRows.firstOrNull {
                    DailyRecordDate.matchesSystemDate(dashboardToday, it.date, it.logged_at, it.created_at)
                }
                val todayBp = bpRows.firstOrNull {
                    DailyRecordDate.matchesSystemDate(dashboardToday, it.reading_date, it.recorded_at)
                }
                val todayWater = waterRows.firstOrNull {
                    DailyRecordDate.matchesSystemDate(dashboardToday, it.entry_date, it.logged_at)
                }
                val missing = buildList {
                    if (todayWeightEntry == null) add("Weight")
                    if (todaySymptom == null) add("Symptoms")
                    if (todayBp == null) add("BP")
                    if (todayWater?.let { it.water_intake_ml != null || it.water_ml != null } != true) add("Water")
                    if (todayWater?.let {
                            it.salt_score != null || it.salt_mg != null ||
                                !it.breakfast_salt.isNullOrBlank() ||
                                !it.lunch_salt.isNullOrBlank() ||
                                !it.dinner_salt.isNullOrBlank()
                        } != true) add("Salt")
                }

                val sortedWeightRows = weightRows
                    .filter { it.kg_avg != null }
                    .sortedByDescending { it.date }
                val latestWeightValue = todayWeight?.let { it.kg_avg ?: it.kg_max ?: it.kg_min } ?: latestWeightRow?.let { it.kg_avg ?: it.kg_max ?: it.kg_min }
                val previousWeightValue = sortedWeightRows
                    .firstOrNull { it.date != today }
                    ?.kg_avg
                val oldestRecentWeightValue = sortedWeightRows
                    .take(7)
                    .lastOrNull()
                    ?.kg_avg
                val recentWeightGainKg = when {
                    latestWeightValue != null && previousWeightValue != null -> latestWeightValue - previousWeightValue
                    else -> null
                }
                val severalDaysWeightGainKg = when {
                    latestWeightValue != null && oldestRecentWeightValue != null -> latestWeightValue - oldestRecentWeightValue
                    else -> null
                }
                val dryWeight = profile?.dry_weight ?: previous.summary?.baselineWeight
                val weekStart = LocalDate.now().with(DayOfWeek.MONDAY)
                val daysThisWeek = (0L..ChronoUnit.DAYS.between(weekStart, LocalDate.now())).map { weekStart.plusDays(it).toString() }
                val weightLogDatesThisWeek = weightRows.map { it.date }.toSet()
                val symptomLogDatesThisWeek = symptomRows.mapNotNull {
                    it.date ?: it.logged_at?.take(10) ?: it.created_at?.take(10)
                }.toSet()
                val daysWithoutWeightLogThisWeek = daysThisWeek.count { it !in weightLogDatesThisWeek }
                val daysWithoutSymptomLogThisWeek = daysThisWeek.count { it !in symptomLogDatesThisWeek }

                val summary = PatientSummary(
                    lastSyncTs = LocalDateTime.now().withNano(0).toString(),
                    latestBp = todayBp ?: latestBp,
                    latestWeight = latestWeightValue,
                    medicationCount = medicationRows.size,
                    steps = todaySteps?.steps_total ?: todayBandMetrics?.steps,
                    avgHr = todayBandMetrics?.avg_hr
                        ?: previous.summary?.avgHr?.takeIf { "Band" in failed },                    avgSpo2 = todaySpo2?.spo2_avg?.toInt()?.takeIf { it in 1..100 }
                        ?: todayBandMetrics?.avg_spo2?.takeIf { it in 1..100 }
                        ?: previous.summary?.avgSpo2?.takeIf { "SpO2" in failed || "Band" in failed },
                    baselineWeight = dryWeight ?: previous.summary?.baselineWeight,
                    previousWeight = previousWeightValue,
                    recentWeightGainKg = recentWeightGainKg ?: severalDaysWeightGainKg,
                    baselineSystolic = profile?.systolic_bp,
                    baselineHeartRate = profile?.heart_rate,
                    daysWithoutWeightLogThisWeek = daysWithoutWeightLogThisWeek,
                    daysWithoutSymptomLogThisWeek = daysWithoutSymptomLogThisWeek
                )

                _s.update {
                    it.copy(
                        syncMessage = if (failed.isNotEmpty()) AppLanguage.text("Some data could not refresh. Check your connection and try again.", "Sesetengah data tidak dapat dikemas kini. Semak sambungan dan cuba lagi.", "部分数据刷新失败，请检查网络后重试。", "சில தரவுகளைப் புதுப்பிக்க முடியவில்லை. இணைப்பைச் சரிபார்த்து மீண்டும் முயலவும்.") else it.syncMessage,
                        summary = summary,
                        missingToday = missing.filter { key -> key !in failed && !(key == "Salt" && "Water" in failed) },
                        profileMedications = profileMedicationRows,
                        medications = medicationRows,
                        medicationSchedules = scheduleRows,
                        appointments = appointmentRows,
                        reminders = reminderRows,
                        weightHistory = weightRows,
                        bpHistory = bpRows,
                        symptomHistory = symptomRows,
                        stepsHistory = stepsRows,
                        bandMetricHistory = bandMetricsRows,
                        targetSteps = profile?.target_steps?.coerceIn(500, 50000) ?: previous.targetSteps,
                        todayWaterIntakeMl = if ("Water" in failed) previous.todayWaterIntakeMl else todayWater?.water_intake_ml ?: todayWater?.water_ml,
                        todayWaterLimitMl = if ("Water" in failed) previous.todayWaterLimitMl else todayWater?.water_limit_ml,
                        todaySaltScore = if ("Water" in failed) previous.todaySaltScore else todayWater?.salt_score,
                        todaySaltStatus = if ("Water" in failed) previous.todaySaltStatus else todayWater?.salt_score?.let { com.vitalink.app.util.SaltScore.status(it) } ?: todayWater?.salt_status,
                        loading = false
                    )
                }

            } catch (e: Exception) {
                _s.update {
                    it.copy(
                        loading = false,
                        syncMessage = UserFacingError.from(e, Action.LOAD)
                    )
                }
            }
        }
    }

    fun syncSmartBandInfoFromHealthConnect(
        steps: Int,
        heartRate: Int?,
        spo2: Int?,
        stepWarning: String? = null
    ) {
        val pid = _s.value.patientId

        if (pid.isBlank()) {
            _s.update {
                it.copy(
                    syncing = false,
                    syncMessage = "Patient ID not found. Please login again."
                )
            }
            return
        }

        syncSaveJob?.cancel()
        syncSaveJob = viewModelScope.launch {
            _s.update {
                it.copy(
                    syncing = true,
                    syncMessage = "Reading Mi Fitness data only and saving to Supabase..."
                )
            }

            try {
                val validSpo2 = spo2?.takeIf { it in 1..100 }
                val today = LocalDate.now().toString()

                val stepsBody = StepsDayInsert(
                    patient_id = pid,
                    date = today,
                    steps_total = steps.toLong()
                )

                var stepsResponse = api.insertStepsDay(body = stepsBody)
                if (!stepsResponse.isSuccessful) {
                    // Fallback for Supabase projects where patient_id,date unique conflict
                    // is not created yet. This still saves the latest row, and dashboard
                    // loads the newest row by updated_at/created_at.
                    stepsResponse = api.insertStepsDayPlain(body = stepsBody)
                }

                val spo2Response = if (validSpo2 != null) {
                    val spo2Body = Spo2DayInsert(
                        patient_id = pid,
                        date = today,
                        spo2_min = validSpo2.toDouble(),
                        spo2_max = validSpo2.toDouble(),
                        spo2_avg = validSpo2.toDouble(),
                        spo2_count = 1L
                    )

                    var response = api.insertSpo2Day(body = spo2Body)
                    if (!response.isSuccessful) {
                        response = api.insertSpo2DayPlain(body = spo2Body)
                    }
                    response
                } else {
                    null
                }

                // Also try the older smart_band_daily_metrics table, but do not block success if it is missing.
                // Save HR even when SpO₂ is missing. The previous code only saved HR when SpO₂ existed,
                // which made heart-rate capture look broken on phones/bands that do not sync SpO₂.
                if (validSpo2 != null || heartRate != null) {
                    val request = MetricsSyncRequest(
                        patient_id = pid,
                        steps = steps.toLong(),
                        distance = 0L,
                        avg_hr = heartRate?.toLong(),
                        avg_spo2 = validSpo2,
                        date = today
                    )
                    try {
                        var metricsResponse = api.syncMetrics(request)
                        if (!metricsResponse.isSuccessful) {
                            metricsResponse = api.syncMetricsPlain(request)
                        }
                    } catch (_: Exception) {}
                }

                if (stepsResponse.isSuccessful && (spo2Response == null || spo2Response.isSuccessful)) {
                    loadSummary(pid)

                    _s.update {
                        it.copy(
                            syncing = false,
                            syncMessage = buildString {
                                append("Saved to Supabase successfully. Steps: $steps")
                                append(", HR: ")
                                append(heartRate?.let { "$it bpm" } ?: "No heart-rate data found")
                                append(", SpO₂: ")
                                append(validSpo2?.let { "$it%" } ?: "No SpO₂ data found")
                                if (!stepWarning.isNullOrBlank()) {
                                    append(" ")
                                    append(stepWarning)
                                }
                            },
                            syncedSteps = steps,
                            syncedHeartRate = heartRate,
                            syncedSpo2 = spo2
                        )
                    }
                } else {
                    val failedStatus = if (!stepsResponse.isSuccessful) stepsResponse.code() else spo2Response?.code() ?: 0

                    _s.update {
                        it.copy(
                            syncing = false,
                            syncMessage = UserFacingError.http(failedStatus, Action.SYNC),
                            connectionAlertMessage = UserFacingError.http(failedStatus, Action.SYNC)
                        )
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _s.update {
                    it.copy(
                        syncing = false,
                        syncMessage = UserFacingError.from(e, Action.SYNC),
                        connectionAlertMessage = UserFacingError.from(e, Action.SYNC)
                    )
                }
            }
        }
    }

    fun cancelSync() {
        syncSaveJob?.cancel()
        syncSaveJob = null
        _s.update {
            it.copy(
                syncing = false,
                syncMessage = AppLanguage.text(
                    "Sync cancelled. You can try again.",
                    "Penyegerakan dibatalkan. Anda boleh cuba lagi.",
                    "同步已取消，您可以重试。",
                    "ஒத்திசைவு ரத்துசெய்யப்பட்டது. மீண்டும் முயற்சிக்கலாம்."
                )
            )
        }
    }

    fun setSyncLoadingMessage(message: String) {
        _s.update {
            it.copy(
                syncing = true,
                syncMessage = message
            )
        }
    }

    fun setSyncErrorMessage(message: String) {
        _s.update {
            it.copy(
                syncing = false,
                syncMessage = message
            )
        }
    }

    fun dismissConnectionAlert() {
        _s.update { it.copy(connectionAlertMessage = null) }
    }

    fun logout() {
        viewModelScope.launch {
            session.clear()
        }
    }
}

private fun dashboardSyncLabel(raw: String?, useMalay: Boolean): String? {
    if (raw.isNullOrBlank()) return null

    val cleaned = raw
        .replace('T', ' ')
        .substringBefore('.')
        .substringBefore('+')
        .substringBefore('Z')
        .trim()

    val date = cleaned.take(10).takeIf { it.length == 10 } ?: return null
    val time = cleaned.drop(11).take(8).takeIf { it.length == 8 } ?: "00:00:00"
    val prefix = AppLanguage.text("Last sync", "Penyegerakan terakhir")

    return "$prefix: $date time $time"
}

private enum class TileStatus {
    GREY, GREEN, YELLOW, RED
}

private fun tileColor(status: TileStatus): Color {
    return when (status) {
        TileStatus.GREY -> Color(0xFFE5E7EB)
        TileStatus.GREEN -> Color(0xFFBBF7D0)
        TileStatus.YELLOW -> CautionYellow
        TileStatus.RED -> CautionRed
    }
}

private fun bpTileStatus(summary: PatientSummary?, missingToday: List<String>): TileStatus {
    val bp = summary?.latestBp ?: return TileStatus.GREY
    val systolic = bp.systolic ?: return TileStatus.GREY
    val diastolic = bp.diastolic ?: return TileStatus.GREY
    val pulse = bp.pulse

    if ("BP" in missingToday) return TileStatus.GREY

    val red = systolic >= 180 || systolic < 80 || diastolic >= 120 || diastolic < 50 ||
        (pulse != null && (pulse < 50 || pulse > 120))
    if (red) return TileStatus.RED

    val yellow = systolic in 140..179 || diastolic in 90..119 ||
        systolic in 121..139 || diastolic in 80..89 ||
        (summary.baselineSystolic != null && systolic - summary.baselineSystolic >= 20)
    if (yellow) return TileStatus.YELLOW

    return TileStatus.GREEN
}

private fun stepsTileStatus(steps: Int?, targetSteps: Int): TileStatus {
    val value = steps ?: return TileStatus.GREY
    return if (value < targetSteps.coerceAtLeast(1)) TileStatus.YELLOW else TileStatus.GREEN
}

private fun heartRateTileStatus(summary: PatientSummary?, heartRate: Int?): TileStatus {
    val value = heartRate ?: return TileStatus.GREY
    if (value < 50 || value > 120) return TileStatus.RED
    if (value < 60 || value > 100) return TileStatus.YELLOW
    val baseline = summary?.baselineHeartRate
    if (baseline != null && kotlin.math.abs(value - baseline) >= 20) return TileStatus.YELLOW
    return TileStatus.GREEN
}

private fun spo2TileStatus(spo2: Int?): TileStatus {
    val value = spo2 ?: return TileStatus.GREY
    if (value < 90) return TileStatus.RED
    if (value < 95) return TileStatus.YELLOW
    return TileStatus.GREEN
}

private fun weightTileStatus(summary: PatientSummary?, missingToday: List<String>): TileStatus {
    val latest = summary?.latestWeight ?: return TileStatus.GREY
    if ("Weight" in missingToday) return TileStatus.GREY

    val baseline = summary.baselineWeight
    val gain = summary.recentWeightGainKg

    if ((gain != null && gain >= 3.0) || (baseline != null && latest - baseline >= 5.0)) {
        return TileStatus.RED
    }

    if ((gain != null && gain >= 1.5) ||
        (baseline != null && latest - baseline >= 3.0) ||
        (summary.daysWithoutWeightLogThisWeek ?: 0) >= 4 ||
        (summary.daysWithoutSymptomLogThisWeek ?: 0) >= 4
    ) {
        return TileStatus.YELLOW
    }

    return TileStatus.GREEN
}

private fun displayDateTimeText(dateOrTimestamp: String?): String? {
    if (dateOrTimestamp.isNullOrBlank()) return null
    MalaysiaDateTime.displayTimestamp(dateOrTimestamp)?.let { return it }
    return runCatching {
        LocalDate.parse(dateOrTimestamp.take(10))
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }.getOrElse { dateOrTimestamp.replace('T', ' ').take(19) }
}

private fun dashboardDateText(date: String?): String? {
    if (date.isNullOrBlank()) return null
    return runCatching {
        LocalDate.parse(date.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }.getOrElse { date }
}

private data class FeatureCard(
    val label: String,
    val sub: String,
    val msLabel: String,
    val msSub: String,
    val zhLabel: String,
    val taLabel: String,
    val imageRes: Int,
    val icon: ImageVector,
    val colors: List<Color>,
    val route: String
)

private val features = listOf(
    FeatureCard(
        "My Self-Check", "", "Pemeriksaan Kendiri", "", "我的自我检查", "என் சுய பரிசோதனை",
        R.drawable.feature_self_check, Icons.Default.MonitorHeart,
        listOf(Color(0xFF43A047), Color(0xFF1B5E20)), Route.SelfCheck.path
    ),
    FeatureCard(
        "My Water & Diet", "", "Air & Diet Saya", "", "我的饮水与饮食", "என் நீர் மற்றும் உணவு",
        R.drawable.feature_water_diet, Icons.Default.WaterDrop,
        listOf(Color(0xFF039BE5), Color(0xFF01579B)), Route.WaterSalt.path
    ),
    FeatureCard(
        "My Exercise", "", "Senaman Saya", "", "我的运动", "என் உடற்பயிற்சி",
        R.drawable.feature_exercise, Icons.Default.FitnessCenter,
        listOf(Color(0xFFEC407A), Color(0xFFC2185B)), Route.Exercise.path
    )
)


private val miFitnessPackageNames = setOf(
    // Mi Fitness / Xiaomi Wear package names seen on different Android/Honor builds.
    // Keep this list narrow so normal phone sensor step records are not counted.
    "com.xiaomi.wearable",
    "com.mi.health"
)

private val xiaomiDataOrigins = miFitnessPackageNames.map { packageName ->
    DataOrigin(packageName = packageName)
}.toSet()

// Dashboard smart-band sync only needs steps, heart rate and SpO₂.
// Keeping this narrow prevents Honor from blocking HR capture when an
// unrelated permission such as BP/weight/sleep is missing.
private val healthConnectPermissions = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit,
    showMiFitnessReminder: Boolean = false,
    onMiFitnessReminderHandled: () -> Unit = {},
    vm: DashboardViewModel = hiltViewModel()
) {
    val s by vm.state.collectAsState()
    val dashboardNow by dashboardClock()
    LaunchedEffect(dashboardNow.toLocalDate()) { vm.refresh() }
    val dashboardLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(dashboardLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        dashboardLifecycle.lifecycle.addObserver(observer)
        onDispose { dashboardLifecycle.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    val unreadNotificationCount = NotificationHistoryStore.read(context).count { !it.read }
    val scope = rememberCoroutineScope()
    var syncReadJob by remember { mutableStateOf<Job?>(null) }
    val useMalay = AppLanguage.useMalay
    var syncHintVisible by remember { mutableStateOf(false) }

    val syncBorderAlpha by rememberInfiniteTransition(label = "syncButtonPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "syncBorderAlpha"
    )

    LaunchedEffect(syncHintVisible) {
        if (syncHintVisible) {
            delay(4200)
            syncHintVisible = false
        }
    }

    var logoutDialog by remember {
        mutableStateOf(false)
    }

    val now = dashboardNow
    val greetingEmoji = if (now.hour in 5..17) "☀️" else "🌙"

    val greeting = when (now.hour) {
        in 5..11 -> AppLanguage.text("Good Morning", "Selamat Pagi", "早上好", "காலை வணக்கம்")
        in 12..17 -> AppLanguage.text("Good Afternoon", "Selamat Petang", "下午好", "மதிய வணக்கம்")
        else -> AppLanguage.text("Good Evening", "Selamat Malam", "晚上好", "மாலை வணக்கம்")
    }

    val dateStr = now.format(
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
    )

    val alert = AlertEngine.evaluate(s.summary)

    val healthConnectClient = remember {
        try {
            // getSdkStatus MUST be called before getOrCreate.
            // On vivo devices Health Connect is embedded in the vivo Health app
            // (com.bbk.healthapp). Without the <queries> entries in the manifest AND
            // this status check, getOrCreate() throws an exception and the client is null.
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { _ ->
        // IMPORTANT: Do NOT use the returned set to check what was granted.
        // The callback only returns permissions granted in THIS dialog session.
        // If a permission was already granted before, it won't appear in this
        // set — so containsAll() would wrongly fail. Instead, always re-query
        // the actual granted state directly from the client.
        syncReadJob?.cancel()
        syncReadJob = scope.launch {
            try {
                if (healthConnectClient == null) {
                    vm.setSyncErrorMessage("Health Connect is not available. Please update/install Google Health Connect, then try Connect again.")
                    return@launch
                }

                val actualGranted = healthConnectClient.permissionController.getGrantedPermissions()
                val allGranted = actualGranted.containsAll(healthConnectPermissions)

                if (allGranted) {
                    vm.setSyncLoadingMessage("Reading Mi Fitness band data...")
                    val stepResult = readStableTodaySteps(healthConnectClient)
                    // Even when Mi Fitness has no step record today, continue saving 0.
                    // This clears stale values such as old phone-sensor steps that were already saved in Supabase.
                    val heartRate = readLatestHeartRate(healthConnectClient)
                    val spo2 = readLatestSpo2(healthConnectClient)

                    vm.syncSmartBandInfoFromHealthConnect(
                        steps = stepResult.steps,
                        heartRate = heartRate,
                        spo2 = spo2,
                        stepWarning = stepResult.warning
                    )
                } else {
                    val missing = healthConnectPermissions - actualGranted
                    val missingNames = missing.joinToString(", ") {
                        it.substringAfterLast(".")
                    }
                    vm.setSyncErrorMessage(
                        "Please open Health Connect and grant these permissions manually: $missingNames"
                    )
                    openHealthConnectSettings(context)
                }

            } catch (e: Exception) {
                vm.setSyncErrorMessage(
                    UserFacingError.from(e, Action.SYNC)
                )
            }
        }
    }

    // Do not auto-trigger Health Connect permission on screen open.
    // On Android 13/vivo this can silently fail. The user taps the visible
    // Connect button, which opens HealthConnectPermissionActivity.


    if (showMiFitnessReminder) {
        MiFitnessRefreshReminderDialog(
            onDismiss = onMiFitnessReminderHandled,
            onOpenMiFitness = {
                onMiFitnessReminderHandled()
                openMiFitnessAppOrStore(context)
            }
        )
    }

    s.connectionAlertMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissConnectionAlert() },
            title = { Text(AppLanguage.text("Sync not completed", "Penyegerakan belum selesai", "同步未完成", "ஒத்திசைவு முடிவடையவில்லை")) },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = { vm.dismissConnectionAlert() }) {
                    Text(AppLanguage.text("OK", "OK"))
                }
            }
        )
    }

    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = {
                logoutDialog = false
            },
            title = {
                Text(AppLanguage.text("Sign Out", "Log Keluar"))
            },
            text = {
                Text(AppLanguage.text("Are you sure you want to sign out?", "Adakah anda pasti mahu log keluar?"))
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.logout()
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(AppLanguage.text("Sign Out", "Log Keluar"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        logoutDialog = false
                    }
                ) {
                    Text(AppLanguage.text("Cancel", "Batal"))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "$greetingEmoji $greeting${if (s.firstName.isNotBlank()) ", ${s.firstName}" else ""}!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            dateStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.6f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate(Route.Notifications.path) }) {
                        BadgedBox(
                            badge = {
                                if (unreadNotificationCount > 0) {
                                    Badge {
                                        Text(if (unreadNotificationCount > 99) "99+" else unreadNotificationCount.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                AppLanguage.text("Notifications", "Notifikasi", "通知", "அறிவிப்புகள்")
                            )
                        }
                    }
                    LanguageSegmentedSwitch(
                        modifier = Modifier.padding(end = 4.dp)
                    )

                    IconButton(
                        onClick = {
                            logoutDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Logout, AppLanguage.text("Logout", "Log Keluar"))
                    }
                }
            )
        }
    ) { pad ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = AppLanguage.text("MyHFGuard Logo", "Logo MyHFGuard"),
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = "MyHFGuard",
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )

                    Text(
                        text = AppLanguage.text("Manage your heart health", "Urus kesihatan jantung anda"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val sum = s.summary
            val heartRateValue = s.syncedHeartRate ?: sum?.avgHr?.toInt()
            val spo2Value = s.syncedSpo2?.takeIf { it in 1..100 } ?: sum?.avgSpo2?.takeIf { it in 1..100 }
            val bpStatus = bpTileStatus(sum, s.missingToday)
            val heartRateStatus = heartRateTileStatus(sum, heartRateValue)
            val spo2Status = spo2TileStatus(spo2Value)
            val weightStatus = weightTileStatus(sum, s.missingToday)

            CompactHealthOverview(s, onNavigate)



            if (syncHintVisible) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF5E35B1).copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, Color(0xFF5E35B1).copy(alpha = syncBorderAlpha))
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, null, tint = Color(0xFF5E35B1))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            AppLanguage.text("Tap the purple button below to sync your smart band data.", "Tekan butang ungu di bawah untuk sync data smart band."),
                            color = Color(0xFF4A2AA0),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Button(
                onClick = {
                    // Wrap in run{} so we can use return@run for early exit
                    run {
                        // Step 1: check HC availability synchronously (no coroutine needed)
                        if (healthConnectClient == null) {
                            vm.setSyncErrorMessage("Health Connect is not available. Please update/install Google Health Connect, then try Connect again.")
                            openHealthConnectSettings(context)
                            return@run
                        }

                        // Step 2: check permissions and either sync or show the dialog.
                        // healthPermissionLauncher.launch() MUST be called on the main
                        // thread — never inside scope.launch{} which may switch dispatchers.
                        syncReadJob?.cancel()
                        syncReadJob = scope.launch {
                            val hasPermission = try {
                                hasHealthConnectPermissions(healthConnectClient)
                            } catch (_: Exception) {
                                false
                            }

                            if (hasPermission) {
                                try {
                                    vm.setSyncLoadingMessage("Reading Mi Fitness band data...")
                                    val stepResult = readStableTodaySteps(healthConnectClient)
                                    // Even when Mi Fitness has no step record today, continue saving 0.
                                    // This clears stale values such as old phone-sensor steps that were already saved in Supabase.
                                    val heartRate = readLatestHeartRate(healthConnectClient)
                                    val spo2 = readLatestSpo2(healthConnectClient)
                                    vm.syncSmartBandInfoFromHealthConnect(
                                        steps = stepResult.steps,
                                        heartRate = heartRate,
                                        spo2 = spo2,
                                        stepWarning = stepResult.warning
                                    )
                                } catch (e: Exception) {
                                    vm.setSyncErrorMessage(UserFacingError.from(e, Action.SYNC))
                                }
                            } else {
                                // Must invoke on main thread — use Main dispatcher to be safe
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    context.startActivity(Intent(context, HealthConnectPermissionActivity::class.java))
                                }
                            }
                        }
                    }
                },
                enabled = !s.syncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5E35B1)
                ),
                shape = RoundedCornerShape(14.dp),
                border = if (syncHintVisible) BorderStroke(3.dp, Color(0xFF5E35B1).copy(alpha = syncBorderAlpha)) else null
            ) {
                if (s.syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text(AppLanguage.text("Sync Smart Band Information", "Segerakkan Maklumat Gelang Pintar"))
                }
            }

            if (alert.level != AlertLevel.YELLOW) {
                AlertStatusCard(alert)
            }

            val dashboardAdvice = buildDashboardAdvice(s)
            UnifiedTodayAdvice(
                state = s, now = dashboardNow,
                healthTitle = dashboardAdvice.title,
                healthMessage = dashboardAdvice.message,
                onNavigate = onNavigate,
                healthPriority = dashboardAdvice.accent in setOf(Color(0xFFC62828), Color(0xFFEF6C00), Color(0xFF1565C0), Color(0xFFD81B60))
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                features.chunked(1).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { f ->
                            FeatureCardTile(
                                f,
                                Modifier.weight(1f)
                            ) {
                                onNavigate(f.route)
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    onNavigate(Route.Help.path)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Help,
                    null,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(Modifier.width(6.dp))

                Text(AppLanguage.text("Help & Support", "Bantuan & Sokongan"))
            }
        }
    }

    BlockingLoadingScreen(
        visible = s.syncing,
        title = AppLanguage.text(
            "Syncing smart band data...",
            "Menyegerakkan data gelang pintar...",
            "正在同步智能手环数据……",
            "ஸ்மார்ட் பேண்ட் தரவு ஒத்திசைக்கப்படுகிறது..."
        ),
        detail = AppLanguage.text(
            "Reading and updating your latest health data.",
            "Membaca dan mengemas kini data kesihatan terkini.",
            "正在读取并更新您的最新健康数据。",
            "உங்கள் சமீபத்திய உடல்நலத் தரவு படித்து புதுப்பிக்கப்படுகிறது."
        ),
        onCancel = {
            syncReadJob?.cancel()
            syncReadJob = null
            vm.cancelSync()
        }
    )
}

private suspend fun hasHealthConnectPermissions(
    healthConnectClient: HealthConnectClient
): Boolean {
    val grantedPermissions =
        healthConnectClient.permissionController.getGrantedPermissions()

    return grantedPermissions.containsAll(
        healthConnectPermissions
    )
}

private data class StepReadResult(
    val steps: Int,
    val warning: String? = null,
    val hasXiaomiStepSourceToday: Boolean = true
)

private suspend fun readStableTodaySteps(
    healthConnectClient: HealthConnectClient
): StepReadResult {
    val first = readTodaySteps(healthConnectClient)
    delay(2500)
    val second = readTodaySteps(healthConnectClient)
    val hasXiaomiSource = hasXiaomiStepRecordToday(healthConnectClient)

    val warning = when {
        !hasXiaomiSource && second == 0 ->
            "No Mi Fitness step record was found in Health Connect today, so MyHFGuard saved 0 steps instead of keeping an old value. Open Mi Fitness and sync the band, then press Sync again if the band has steps."
        abs(second - first) > 15 ->
            "Mi Fitness is still updating Health Connect. Open Mi Fitness, let it finish syncing, then press Sync again for the final number."
        else -> null
    }

    return StepReadResult(
        steps = maxOf(first, second),
        warning = warning,
        hasXiaomiStepSourceToday = hasXiaomiSource
    )
}

private suspend fun readTodaySteps(
    healthConnectClient: HealthConnectClient
): Int {
    val zoneId = ZoneId.systemDefault()

    val startOfDay = LocalDate.now(zoneId)
        .atStartOfDay(zoneId)
        .toInstant()

    val now = Instant.now()

    // Match the previous accurate version: read raw step records and sum them.
    // Still filtered to Mi Fitness only, so phone sensor steps are ignored.
    // The latest aggregate() version could return a smaller value such as 5
    // while Mi Fitness/Health Connect records total 18.
    val response = healthConnectClient.readRecords(
        ReadRecordsRequest(
            recordType = StepsRecord::class,
            timeRangeFilter = TimeRangeFilter.between(
                startOfDay,
                now
            ),
            dataOriginFilter = xiaomiDataOrigins
        )
    )

    return response.records.sumOf { it.count }.toInt()
}

private suspend fun hasXiaomiStepRecordToday(
    healthConnectClient: HealthConnectClient
): Boolean {
    val zoneId = ZoneId.systemDefault()
    val startOfDay = LocalDate.now(zoneId)
        .atStartOfDay(zoneId)
        .toInstant()
    val now = Instant.now()

    return healthConnectClient.readRecords(
        ReadRecordsRequest(
            recordType = StepsRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            dataOriginFilter = xiaomiDataOrigins
        )
    ).records.isNotEmpty()
}

private suspend fun readLatestHeartRate(
    healthConnectClient: HealthConnectClient
): Int? {
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

    // Some Honor + Mi Fitness builds expose HR records with a different source name.
    // If the exact Mi Fitness filter returns empty, read visible Health Connect HR
    // records and prefer known Mi Fitness package names; final fallback prevents
    // HR from staying blank when Health Connect only exposes a generic source.
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
        ?.toInt()
}

private suspend fun readLatestSpo2(
    healthConnectClient: HealthConnectClient
): Int? {
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
        ?.toInt()
        ?.takeIf { it in 1..100 }
}

@Composable
internal fun TargetStepsTopCard(
    targetSteps: Int,
    currentSteps: Int
) {
    val safeTarget = targetSteps.coerceAtLeast(1)
    val safeCurrent = currentSteps.coerceAtLeast(0)
    val progress = (safeCurrent.toFloat() / safeTarget.toFloat()).coerceIn(0f, 1f)
    val remaining = (safeTarget - safeCurrent).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color(0xFF7595D8).copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFFDDEBFF), Color(0xFFF2F6FF))
                    )
                )
                .padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsWalk,
                    contentDescription = null,
                    tint = Color(0xFF3157A4),
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = AppLanguage.text(
                        "Daily Step Progress",
                        "Kemajuan Langkah Harian",
                        "每日步数进度",
                        "தினசரி நடை முன்னேற்றம்"
                    ),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    color = Color(0xFF283A66),
                )
            }

            Spacer(Modifier.height(8.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                val travelDistance = if (maxWidth > 64.dp) maxWidth - 64.dp else 0.dp

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val startX = 32.dp.toPx()
                    val endX = size.width - 32.dp.toPx()
                    val y = size.height / 2f
                    val activeEnd = startX + ((endX - startX) * progress)

                    drawLine(
                        color = Color(0xFFB9C8E6),
                        start = Offset(startX, y),
                        end = Offset(endX, y),
                        strokeWidth = 14.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF3F6FC4),
                        start = Offset(startX, y),
                        end = Offset(activeEnd, y),
                        strokeWidth = 14.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = Color(0xFF3157A4),
                        radius = 8.dp.toPx(),
                        center = Offset(startX, y)
                    )
                    drawCircle(
                        color = Color(0xFF3157A4),
                        radius = 8.dp.toPx(),
                        center = Offset(endX, y)
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = travelDistance * progress),
                    shape = RoundedCornerShape(50),
                    color = Color.White,
                    shadowElevation = 5.dp,
                    border = BorderStroke(1.dp, Color(0xFF3F6FC4).copy(alpha = 0.25f))
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsWalk,
                        contentDescription = AppLanguage.text(
                            "Walking progress",
                            "Kemajuan berjalan",
                            "步行进度",
                            "நடை முன்னேற்றம்"
                        ),
                        tint = Color(0xFF3157A4),
                        modifier = Modifier.padding(12.dp).size(40.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TargetStepStat(
                    modifier = Modifier.weight(1f),
                    label = AppLanguage.text("Actual steps", "Langkah sebenar", "实际步数", "உண்மை நடைகள்"),
                    value = safeCurrent.toString(),
                    dotColor = Color(0xFF3F6FC4)
                )
                TargetStepStat(
                    modifier = Modifier.weight(1f),
                    label = AppLanguage.text("Target steps", "Langkah sasaran", "目标步数", "இலக்கு நடைகள்"),
                    value = safeTarget.toString(),
                    dotColor = Color(0xFF4E8D7C)
                )
                TargetStepStat(
                    modifier = Modifier.weight(1f),
                    label = AppLanguage.text("Steps remaining", "Baki langkah", "剩余步数", "மீதமுள்ள நடைகள்"),
                    value = remaining.toString(),
                    dotColor = Color(0xFF8A63B8)
                )
            }
        }
    }
}

@Composable
private fun TargetStepStat(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    dotColor: Color
) {
    Column(
        modifier = modifier.padding(horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(50))
                .background(dotColor)
        )
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            color = Color(0xFF26385F)
        )
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            color = Color(0xFF4D5B78),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}


@Composable
private fun MiFitnessRefreshReminderDialog(
    onDismiss: () -> Unit,
    onOpenMiFitness: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { MiFitnessReminderPicture() },
        title = {
            Text(
                text = AppLanguage.text(
                    "Refresh Mi Fitness first",
                    "Segarkan Mi Fitness dahulu",
                    "请先刷新 Mi Fitness",
                    "முதலில் Mi Fitness-ஐ புதுப்பிக்கவும்"
                ),
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    AppLanguage.text(
                        "To get the latest data from your smart band:",
                        "Untuk mendapatkan data terkini daripada gelang pintar anda:",
                        "要获取智能手环的最新数据：",
                        "உங்கள் ஸ்மார்ட் பேண்டிலிருந்து சமீபத்திய தரவைப் பெற:"
                    ),
                    fontWeight = FontWeight.SemiBold
                )
                ReminderStep(
                    number = "1",
                    text = AppLanguage.text(
                        "Open the Mi Fitness app.",
                        "Buka aplikasi Mi Fitness.",
                        "打开 Mi Fitness 应用。",
                        "Mi Fitness செயலியைத் திறக்கவும்."
                    )
                )
                ReminderStep(
                    number = "2",
                    text = AppLanguage.text(
                        "Pull down or tap Sync, then wait for the band to finish updating.",
                        "Tarik ke bawah atau tekan Sync, kemudian tunggu gelang selesai dikemas kini.",
                        "下拉或点击同步，然后等待手环完成更新。",
                        "கீழே இழுக்கவும் அல்லது Sync-ஐ தட்டவும்; பேண்ட் புதுப்பிப்பை முடிக்கும் வரை காத்திருக்கவும்."
                    )
                )
                ReminderStep(
                    number = "3",
                    text = AppLanguage.text(
                        "Return to MyHFGuard and tap Sync Smart Band Information.",
                        "Kembali ke MyHFGuard dan tekan Segerakkan Maklumat Gelang Pintar.",
                        "返回 MyHFGuard，然后点击“同步智能手环信息”。",
                        "MyHFGuard-க்கு திரும்பி, ஸ்மார்ட் பேண்ட் தகவலை ஒத்திசை என்பதைத் தட்டவும்."
                    )
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenMiFitness) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    AppLanguage.text(
                        "Open Mi Fitness",
                        "Buka Mi Fitness",
                        "打开 Mi Fitness",
                        "Mi Fitness-ஐ திறக்கவும்"
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppLanguage.text("Later", "Nanti", "稍后", "பின்னர்"))
            }
        }
    )
}

@Composable
private fun MiFitnessReminderPicture() {
    Surface(
        modifier = Modifier
            .width(142.dp)
            .height(82.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFFFF3E0),
        border = BorderStroke(1.dp, Color(0xFFFFB74D))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 15.dp)
                    .size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.White
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(31.dp),
                        tint = Color(0xFFFF6D00)
                    )
                    Text(
                        text = "Mi",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 3.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF6D00)
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp),
                tint = Color(0xFF5E35B1)
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 15.dp)
                    .size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.White
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Watch,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Color(0xFF5E35B1)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderStep(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF5E35B1)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun openMiFitnessAppOrStore(context: Context) {
    for (packageName in miFitnessPackageNames) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchIntent != null && runCatching { context.startActivity(launchIntent) }.isSuccess) {
            return
        }
    }

    val appPackage = "com.xiaomi.wearable"
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$appPackage")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    if (marketIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(marketIntent)
        return
    }

    val browserIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$appPackage")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(browserIntent) }
}

@Composable
private fun TodayOverviewCard(
    summary: PatientSummary?,
    syncedSteps: Int?,
    syncedHeartRate: Int?,
    syncedSpo2: Int?,
    missingToday: List<String>,
    targetSteps: Int,
    weightHistory: List<WeightDay>,
    bpHistory: List<BpEvent>,
    bandMetricHistory: List<SmartBandDailyMetric>,
    bpStatus: TileStatus,
    heartRateStatus: TileStatus,
    spo2Status: TileStatus,
    weightStatus: TileStatus,
    useMalay: Boolean,
    onSelfCheckClick: () -> Unit,
    onBandSyncHelpClick: () -> Unit
) {
    val bpValue = summary?.latestBp?.let { "${it.systolic ?: "--"}/${it.diastolic ?: "--"} mmHg" } ?: "--"
    val hrValue = syncedHeartRate?.let { "$it bpm" } ?: summary?.avgHr?.let { "$it bpm" } ?: "--"
    val spo2Value = syncedSpo2?.takeIf { it in 1..100 }?.let { "$it%" }
        ?: summary?.avgSpo2?.takeIf { it in 1..100 }?.let { "$it%" }
        ?: "--"
    val weightValue = summary?.latestWeight?.let { "%.1f kg".format(it) } ?: "--"

    fun compactTrend(values: List<Float>): List<Float> = when (values.size) {
        0 -> emptyList()
        1 -> listOf(values.first(), values.first())
        else -> values
    }

    val heartRateTrend = compactTrend(
        bandMetricHistory
            .filter { isDashboardLatestSevenDays(it.date) }
            .sortedBy { it.date }
            .mapNotNull { it.avg_hr?.toFloat()?.takeIf { value -> value > 0f } }
    )
    val spo2Trend = compactTrend(
        bandMetricHistory
            .filter { isDashboardLatestSevenDays(it.date) }
            .sortedBy { it.date }
            .mapNotNull { it.avg_spo2?.toFloat()?.takeIf { value -> value in 1f..100f } }
    )
    val weightTrend = compactTrend(
        weightHistory
            .filter { isDashboardLatestSevenDays(it.date) }
            .sortedBy { it.date }
            .mapNotNull { it.kg_avg?.toFloat() }
    )
    val orderedBpHistory = bpHistory
        .filter { isDashboardLatestSevenDays(it.reading_date ?: it.recorded_at) }
        .sortedBy { it.reading_date ?: it.recorded_at ?: "" }
    val systolicTrend = compactTrend(orderedBpHistory.mapNotNull { it.systolic?.toFloat() })
    val diastolicTrend = compactTrend(orderedBpHistory.mapNotNull { it.diastolic?.toFloat() })

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFF)),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        AppLanguage.text("Health Overview", "Gambaran Kesihatan"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

            }

            TargetStepsTopCard(
                targetSteps = targetSteps,
                currentSteps = syncedSteps ?: summary?.steps?.toInt() ?: 0
            )

            OverviewLineItem(
                label = AppLanguage.text("HR", "Denyutan Jantung"),
                value = hrValue,
                icon = Icons.Default.MonitorHeart,
                iconTint = Color(0xFFFF8A1F),
                status = heartRateStatus,
                trendValues = heartRateTrend,
                useMalay = useMalay,
                onClick = onBandSyncHelpClick
            )
            OverviewLineItem(
                label = "SpO₂",
                value = spo2Value,
                icon = Icons.Default.Air,
                iconTint = Color(0xFF8E24AA),
                status = spo2Status,
                trendValues = spo2Trend,
                useMalay = useMalay,
                onClick = onBandSyncHelpClick
            )
            OverviewLineItem(
                label = AppLanguage.text("Weight", "Berat"),
                value = weightValue,
                icon = Icons.Default.MonitorWeight,
                iconTint = Color(0xFF43A047),
                status = weightStatus,
                trendValues = weightTrend,
                trailing = if ("Weight" in missingToday) (AppLanguage.text("Missing", "Belum isi")) else null,
                useMalay = useMalay,
                onClick = onSelfCheckClick
            )
            OverviewLineItem(
                label = AppLanguage.text("Blood pressure", "Tekanan darah"),
                value = bpValue,
                icon = Icons.Default.Favorite,
                iconTint = Color(0xFFE53935),
                status = bpStatus,
                trendValues = systolicTrend,
                secondaryTrendValues = diastolicTrend,
                secondaryTrendTint = Color(0xFF1E88E5),
                trailing = when {
                    "BP" in missingToday -> AppLanguage.text("Missing", "Belum isi")
                    summary?.latestBp?.pulse != null -> summary?.latestBp?.pulse?.let { (AppLanguage.text("Pulse ", "Nadi ")) + "$it bpm" }
                    else -> null
                },
                useMalay = useMalay,
                onClick = onSelfCheckClick
            )

            dashboardSyncLabel(summary?.lastSyncTs, useMalay)?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun OverviewLineItem(
    label: String,
    value: String,
    icon: ImageVector,
    iconTint: Color,
    status: TileStatus,
    useMalay: Boolean,
    trendValues: List<Float> = emptyList(),
    secondaryTrendValues: List<Float> = emptyList(),
    secondaryTrendTint: Color = iconTint,
    trailing: String? = null,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, Color(0xFFEBEEF2))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = value,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (value.length >= 11) 21.sp else 24.sp,
                    lineHeight = 27.sp,
                    color = Color(0xFF20302D)
                )

                trailing?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            StatusPill(status = status, useMalay = useMalay)
        }
    }
}

@Composable
private fun HealthMiniTrend(
    values: List<Float>,
    accent: Color,
    secondaryValues: List<Float> = emptyList(),
    secondaryAccent: Color = accent,
    modifier: Modifier = Modifier
) {
    val primary = values
    val secondary = secondaryValues
    val allValues = primary + secondary

    if (allValues.isEmpty()) return

    val minValue = allValues.minOrNull() ?: 0f
    val maxValue = allValues.maxOrNull() ?: minValue
    val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f

    Canvas(
        modifier = modifier.height(30.dp)
    ) {
        if (secondary.isEmpty()) drawRect(color = Color.White)
        drawLine(
            color = Color(0xFFE7EBEF),
            start = Offset(0f, size.height - 1.dp.toPx()),
            end = Offset(size.width, size.height - 1.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )

        fun drawSeries(series: List<Float>, color: Color) {
            if (series.isEmpty()) return

            if (series.size == 1) {
                drawCircle(
                    color = color,
                    radius = 2.5.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f)
                )
                return
            }

            val stepX = size.width / (series.size - 1).coerceAtLeast(1)

            series.zipWithNext().forEachIndexed { index, pair ->
                val x1 = index * stepX
                val x2 = (index + 1) * stepX
                val y1 = size.height - ((pair.first - minValue) / range) * size.height
                val y2 = size.height - ((pair.second - minValue) / range) * size.height

                drawLine(
                    color = color,
                    start = Offset(x1, y1.coerceIn(1.dp.toPx(), size.height - 1.dp.toPx())),
                    end = Offset(x2, y2.coerceIn(1.dp.toPx(), size.height - 1.dp.toPx())),
                    strokeWidth = 2.4.dp.toPx()
                )
            }
        }

        // Keep paired SYS/DIA colours, but use black for a single metric's trend.
        drawSeries(primary, if (secondary.isEmpty()) Color.Black else accent)
        drawSeries(secondary, secondaryAccent)
    }
}

@Composable
private fun StatusPill(status: TileStatus, useMalay: Boolean) {
    val text = when (status) {
        TileStatus.GREEN -> AppLanguage.text("Normal", "Normal")
        TileStatus.YELLOW -> AppLanguage.text("Alert", "Amaran", "警示", "எச்சரிக்கை")
        TileStatus.RED -> AppLanguage.text("High", "Risiko")
        TileStatus.GREY -> AppLanguage.text("None", "Tiada")
    }
    val container = when (status) {
        TileStatus.GREEN -> Color(0xFFE8F8EE)
        TileStatus.YELLOW -> CautionYellow.copy(alpha = 0.55f)
        TileStatus.RED -> Color(0xFFFFE3E3)
        TileStatus.GREY -> Color(0xFFEFF2F4)
    }
    val content = when (status) {
        TileStatus.GREEN -> Color(0xFF1D7F4E)
        TileStatus.YELLOW -> Color(0xFF996300)
        TileStatus.RED -> Color(0xFFC62828)
        TileStatus.GREY -> Color(0xFF6B7280)
    }

    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = content
        )
    }
}


private data class DashboardAdviceUi(
    val title: String,
    val message: String,
    val icon: ImageVector,
    val accent: Color,
    val background: Color,
    val route: String?
)

private fun buildDashboardAdvice(state: DashState): DashboardAdviceUi {
    val summary = state.summary
    val bp = summary?.latestBp?.takeUnless { "BP" in state.missingToday }
    val systolic = bp?.systolic
    val diastolic = bp?.diastolic
    val heartRate = state.syncedHeartRate ?: summary?.avgHr?.toInt()
    val spo2 = state.syncedSpo2?.takeIf { it in 1..100 }
        ?: summary?.avgSpo2?.takeIf { it in 1..100 }
    val steps = state.syncedSteps ?: summary?.steps?.toInt() ?: 0
    val today = MalaysiaDateTime.today().toString()
    val symptom = state.symptomHistory.firstOrNull {
        DailyRecordDate.matches(MalaysiaDateTime.today(), it.date, it.logged_at, it.created_at)
    }
    val symptomPeak = listOfNotNull(
        symptom?.cough,
        symptom?.sob_activity,
        symptom?.leg_swelling,
        symptom?.abd_discomfort,
        symptom?.orthopnea
    ).maxOrNull() ?: 0
    val waterIntake = state.todayWaterIntakeMl
    val waterLimit = state.todayWaterLimitMl
    val saltStatus = state.todaySaltStatus.orEmpty().lowercase()
    val saltScore = state.todaySaltScore
    val weightGain = summary?.recentWeightGainKg
    val baselineSystolic = summary?.baselineSystolic

    fun advice(
        titleEn: String,
        titleMs: String,
        titleZh: String,
        titleTa: String,
        messageEn: String,
        messageMs: String,
        messageZh: String,
        messageTa: String,
        icon: ImageVector,
        accent: Color,
        background: Color,
        route: String?
    ) = DashboardAdviceUi(
        title = AppLanguage.text(titleEn, titleMs, titleZh, titleTa),
        message = AppLanguage.text(messageEn, messageMs, messageZh, messageTa),
        icon = icon,
        accent = accent,
        background = background,
        route = route
    )

    return when {
        systolic != null && diastolic != null && (systolic >= 180 || diastolic >= 120) ->
            advice(
                "Blood pressure very high", "Tekanan darah sangat tinggi", "血压非常高", "இரத்த அழுத்தம் மிகவும் அதிகம்",
                "🚨 Your blood pressure is dangerously high. Please seek urgent medical advice, especially if you have chest pain, breathing difficulty, weakness, or severe headache.",
                "🚨 Tekanan darah anda sangat tinggi. Dapatkan nasihat perubatan segera, terutamanya jika anda mengalami sakit dada, sukar bernafas, lemah atau sakit kepala teruk.",
                "🚨 您的血压处于危险高值。请立即寻求医疗建议，尤其是出现胸痛、呼吸困难、无力或剧烈头痛时。",
                "🚨 உங்கள் இரத்த அழுத்தம் ஆபத்தான அளவில் அதிகமாக உள்ளது. மார்பு வலி, மூச்சுத்திணறல், பலவீனம் அல்லது கடுமையான தலைவலி இருந்தால் உடனடி மருத்துவ உதவியைப் பெறவும்.",
                Icons.Default.WarningAmber, Color(0xFFC62828), Color(0xFFFFEBEE), Route.Vitals.path
            )

        (symptom?.sob_activity ?: 0) >= 4 || (symptom?.orthopnea ?: 0) >= 4 ->
            advice(
                "Severe breathing difficulty", "Kesukaran bernafas teruk", "严重呼吸困难", "கடுமையான மூச்சுத்திணறல்",
                "🚨 Severe or sudden breathing difficulty requires immediate medical attention. Please contact emergency services now.",
                "🚨 Kesukaran bernafas yang teruk atau berlaku secara tiba-tiba memerlukan rawatan segera. Sila hubungi perkhidmatan kecemasan sekarang.",
                "🚨 严重或突然出现的呼吸困难需要立即就医。请马上联系紧急服务。",
                "🚨 கடுமையான அல்லது திடீர் மூச்சுத்திணறலுக்கு உடனடி மருத்துவ கவனம் தேவை. இப்போது அவசர சேவையைத் தொடர்புகொள்ளவும்.",
                Icons.Default.Air, Color(0xFFC62828), Color(0xFFFFEBEE), Route.SelfCheck.path
            )

        spo2 != null && spo2 < 90 ->
            advice(
                "Low oxygen level", "Paras oksigen rendah", "血氧偏低", "குறைந்த ஆக்சிஜன் அளவு",
                "⚠️ Your oxygen level is low. Sit upright, rest, and check the reading again. Seek urgent help if you are struggling to breathe.",
                "⚠️ Paras oksigen anda rendah. Duduk tegak, berehat dan periksa semula bacaan. Dapatkan bantuan segera jika anda sukar bernafas.",
                "⚠️ 您的血氧偏低。请坐直休息并重新测量；若呼吸困难，请立即求助。",
                "⚠️ உங்கள் ஆக்சிஜன் அளவு குறைவாக உள்ளது. நேராக அமர்ந்து ஓய்வெடுத்து மீண்டும் அளவிடவும். மூச்சுவிட சிரமமிருந்தால் அவசர உதவி பெறவும்.",
                Icons.Default.MonitorHeart, Color(0xFFC62828), Color(0xFFFFEBEE), Route.SmartBand.path
            )

        weightGain != null && weightGain >= 1.5 ->
            advice(
                "Sudden weight increase", "Berat meningkat mendadak", "体重突然增加", "திடீர் எடை அதிகரிப்பு",
                "⚠️ Your weight has increased quickly. This may be caused by fluid buildup. Please check for swelling or breathing difficulty and contact your healthcare team.",
                "⚠️ Berat anda meningkat dengan cepat. Ini mungkin disebabkan pengumpulan cecair. Periksa bengkak atau kesukaran bernafas dan hubungi pasukan kesihatan anda.",
                "⚠️ 您的体重快速增加，可能与液体潴留有关。请检查是否肿胀或呼吸困难，并联系医护团队。",
                "⚠️ உங்கள் எடை விரைவாக அதிகரித்துள்ளது. இது திரவச் சேர்க்கையால் இருக்கலாம். வீக்கம் அல்லது மூச்சுத்திணறல் உள்ளதா எனச் சரிபார்த்து மருத்துவக் குழுவைத் தொடர்புகொள்ளவும்.",
                Icons.Default.MonitorWeight, Color(0xFFEF6C00), Color(0xFFFFF3E0), Route.SelfCheck.path
            )

        systolic != null && diastolic != null &&
            (systolic < 80 || diastolic < 50) && symptomPeak >= 2 ->
            advice(
                "Low blood pressure", "Tekanan darah rendah", "血压偏低", "குறைந்த இரத்த அழுத்தம்",
                "Your blood pressure is low. Sit or lie down safely. Please contact your healthcare provider if you feel dizzy, weak, confused, or faint.",
                "Tekanan darah anda rendah. Duduk atau baring dengan selamat. Hubungi penyedia kesihatan jika anda pening, lemah, keliru atau hampir pengsan.",
                "您的血压偏低。请安全坐下或躺下；若头晕、无力、意识混乱或昏厥，请联系医护人员。",
                "உங்கள் இரத்த அழுத்தம் குறைவாக உள்ளது. பாதுகாப்பாக அமரவும் அல்லது படுக்கவும். மயக்கம், பலவீனம், குழப்பம் அல்லது மயங்குதல் இருந்தால் மருத்துவரைத் தொடர்புகொள்ளவும்.",
                Icons.Default.South, Color(0xFF1565C0), Color(0xFFE3F2FD), Route.Vitals.path
            )

        heartRate != null && (heartRate < 60 || heartRate > 100) ->
            advice(
                "Heart rate unusual", "Kadar denyutan luar biasa", "心率异常", "அசாதாரண இதயத் துடிப்பு",
                "💓 Your heart rate is outside your usual range. Rest and check it again. Seek medical advice if it remains unusual or you feel unwell.",
                "💓 Kadar denyutan jantung anda di luar julat biasa. Berehat dan periksa semula. Dapatkan nasihat perubatan jika bacaan masih luar biasa atau anda tidak sihat.",
                "💓 您的心率超出常见范围。请休息后重新检查；若仍异常或感觉不适，请寻求医疗建议。",
                "💓 உங்கள் இதயத் துடிப்பு வழக்கமான வரம்பிற்கு வெளியே உள்ளது. ஓய்வெடுத்து மீண்டும் சரிபார்க்கவும். தொடர்ந்து அசாதாரணமாக இருந்தால் அல்லது உடல்நலக்குறைவு இருந்தால் மருத்துவ ஆலோசனை பெறவும்.",
                Icons.Default.Favorite, Color(0xFFD81B60), Color(0xFFFCE4EC), Route.SmartBand.path
            )

        systolic != null && diastolic != null &&
            (systolic >= 140 || diastolic >= 90 ||
                (baselineSystolic != null && systolic >= baselineSystolic + 20)) ->
            advice(
                "Blood pressure higher than usual", "Tekanan darah lebih tinggi", "血压高于平常", "வழக்கத்தை விட அதிக இரத்த அழுத்தம்",
                "⚠️ Your blood pressure is higher than your usual reading. Rest for a few minutes and measure it again.",
                "⚠️ Tekanan darah anda lebih tinggi daripada biasa. Berehat beberapa minit dan ukur semula.",
                "⚠️ 您的血压高于平常。请休息几分钟后重新测量。",
                "⚠️ உங்கள் இரத்த அழுத்தம் வழக்கத்தை விட அதிகமாக உள்ளது. சில நிமிடங்கள் ஓய்வெடுத்து மீண்டும் அளவிடவும்.",
                Icons.Default.Bloodtype, Color(0xFFEF6C00), Color(0xFFFFF3E0), Route.Vitals.path
            )

        (symptom?.orthopnea ?: 0) >= 2 ->
            advice(
                "Breathlessness when lying down", "Sesak nafas ketika baring", "平躺时呼吸困难", "படுக்கும்போது மூச்சுத்திணறல்",
                "Breathing difficulty when lying flat may be important. Sit upright and contact your healthcare provider as soon as possible.",
                "Kesukaran bernafas ketika baring rata mungkin penting. Duduk tegak dan hubungi penyedia kesihatan anda secepat mungkin.",
                "平躺时呼吸困难可能需要重视。请坐直，并尽快联系医护人员。",
                "நேராகப் படுக்கும்போது மூச்சுத்திணறல் முக்கியமான அறிகுறியாக இருக்கலாம். நேராக அமர்ந்து விரைவில் மருத்துவரைத் தொடர்புகொள்ளவும்.",
                Icons.Default.Air, Color(0xFFEF6C00), Color(0xFFFFF3E0), Route.SelfCheck.path
            )

        (symptom?.leg_swelling ?: 0) >= 2 ->
            advice(
                "Swollen feet or ankles", "Kaki atau buku lali bengkak", "脚或脚踝肿胀", "கால் அல்லது கணுக்கால் வீக்கம்",
                "🦶 New or worsening swelling may indicate fluid buildup. Record your weight and contact your healthcare team if it continues.",
                "🦶 Bengkak baharu atau semakin teruk mungkin menunjukkan pengumpulan cecair. Rekod berat anda dan hubungi pasukan kesihatan jika berterusan.",
                "🦶 新出现或加重的肿胀可能提示液体潴留。请记录体重，若持续请联系医护团队。",
                "🦶 புதிய அல்லது மோசமடையும் வீக்கம் திரவச் சேர்க்கையை குறிக்கலாம். எடையைப் பதிவு செய்து, தொடர்ந்தால் மருத்துவக் குழுவைத் தொடர்புகொள்ளவும்.",
                Icons.Default.AccessibilityNew, Color(0xFFEF6C00), Color(0xFFFFF3E0), Route.SelfCheck.path
            )

        (symptom?.sob_activity ?: 0) in 1..3 ->
            advice(
                "Mild shortness of breath", "Sesak nafas ringan", "轻微气短", "லேசான மூச்சுத்திணறல்",
                "Please sit upright and rest. Avoid strenuous activity until your breathing returns to normal.",
                "Sila duduk tegak dan berehat. Elakkan aktiviti berat sehingga pernafasan kembali normal.",
                "请坐直休息。在呼吸恢复正常前避免剧烈活动。",
                "நேராக அமர்ந்து ஓய்வெடுக்கவும். சுவாசம் இயல்பாகும் வரை கடினமான செயல்களைத் தவிர்க்கவும்.",
                Icons.Default.Air, Color(0xFFEF6C00), Color(0xFFFFF3E0), Route.SelfCheck.path
            )

        (symptom?.cough ?: 0) >= 2 ->
            advice(
                "Persistent cough", "Batuk berterusan", "持续咳嗽", "தொடர்ச்சியான இருமல்",
                "A new or worsening cough, especially at night or when lying down, should be monitored. Contact your healthcare provider if it continues.",
                "Batuk baharu atau semakin teruk, terutama pada waktu malam atau ketika baring, perlu dipantau. Hubungi penyedia kesihatan jika berterusan.",
                "新出现或加重的咳嗽，尤其在夜间或平躺时，应持续观察；若不缓解，请联系医护人员。",
                "புதிய அல்லது மோசமடையும் இருமல், குறிப்பாக இரவில் அல்லது படுக்கும்போது, கண்காணிக்கப்பட வேண்டும். தொடர்ந்தால் மருத்துவரைத் தொடர்புகொள்ளவும்.",
                Icons.Default.Sick, Color(0xFFEF6C00), Color(0xFFFFF3E0), Route.SelfCheck.path
            )

        waterIntake != null && waterLimit != null && waterLimit > 0 &&
            waterIntake >= (waterLimit * 0.8).toInt() ->
            advice(
                "Water intake approaching limit", "Pengambilan air hampir had", "饮水量接近上限", "நீர் அளவு வரம்பை நெருங்குகிறது",
                "💧 You are approaching your daily fluid target. Remember that drinks, soup, ice and watery desserts may also count.",
                "💧 Anda hampir mencapai sasaran cecair harian. Minuman, sup, ais dan pencuci mulut berair juga mungkin dikira.",
                "💧 您的每日液体摄入量已接近上限。饮料、汤、冰块和含水甜品也可能需要计算。",
                "💧 உங்கள் தினசரி திரவ இலக்கை நெருங்குகிறீர்கள். பானங்கள், சூப், பனி மற்றும் நீர்ச்சத்து அதிகமான இனிப்புகளும் கணக்கில் சேரலாம்.",
                Icons.Default.WaterDrop, Color(0xFF0288D1), Color(0xFFE1F5FE), Route.WaterSalt.path
            )

        saltStatus == "red" || (saltScore != null && saltScore >= 7) ->
            advice(
                "High-salt meal recorded", "Hidangan tinggi garam direkod", "已记录高盐餐食", "அதிக உப்பு உணவு பதிவு",
                "🧂 This meal may contain a lot of salt. Choose lower-sodium foods for your next meal to help reduce fluid retention.",
                "🧂 Hidangan ini mungkin mengandungi banyak garam. Pilih makanan lebih rendah natrium untuk hidangan seterusnya bagi membantu mengurangkan pengumpulan cecair.",
                "🧂 这餐可能含盐较高。下一餐请选择低钠食物，以帮助减少液体潴留。",
                "🧂 இந்த உணவில் உப்பு அதிகமாக இருக்கலாம். திரவச் சேர்க்கையை குறைக்க அடுத்த உணவில் குறைந்த சோடியம் உணவைத் தேர்ந்தெடுக்கவும்.",
                Icons.Default.Restaurant, Color(0xFFEF6C00), Color(0xFFFFF3E0), Route.WaterSalt.path
            )

        steps < state.targetSteps && "Weight" !in state.missingToday && "BP" !in state.missingToday ->
            advice(
                "Steps below target", "Langkah belum capai sasaran", "步数低于目标", "நடைகள் இலக்கிற்கு கீழே",
                "🚶 You are below your step target today. Try a gentle walk or light movement if you feel well and your healthcare provider allows it.",
                "🚶 Langkah anda masih di bawah sasaran hari ini. Cuba berjalan perlahan atau bergerak ringan jika anda berasa sihat dan dibenarkan oleh penyedia kesihatan.",
                "🚶 您今天的步数尚未达到目标。若感觉良好且医护人员允许，可尝试轻松步行或轻度活动。",
                "🚶 இன்று உங்கள் நடை எண்ணிக்கை இலக்கிற்கு கீழே உள்ளது. உடல்நலம் நன்றாக இருந்தும் மருத்துவர் அனுமதித்தும் இருந்தால் மெதுவாக நடக்கவும் அல்லது லேசாக இயங்கவும்.",
                Icons.Default.DirectionsWalk, Color(0xFFB783A6), Color(0xFFF8EAF2), Route.Exercise.path
            )

        saltStatus == "green" && saltScore != null ->
            advice(
                "Healthy meal recorded", "Hidangan sihat direkod", "已记录健康餐食", "ஆரோக்கியமான உணவு பதிவு",
                "🥗 Good choice! A balanced, lower-salt meal supports better heart-health management.",
                "🥗 Pilihan yang baik! Hidangan seimbang dan rendah garam menyokong pengurusan kesihatan jantung.",
                "🥗 很好的选择！均衡、低盐的餐食有助于更好地管理心脏健康。",
                "🥗 நல்ல தேர்வு! சமநிலையான குறைந்த உப்பு உணவு இதய ஆரோக்கிய மேலாண்மைக்கு உதவும்.",
                Icons.Default.Restaurant, Color(0xFF2E7D32), Color(0xFFE8F5E9), Route.WaterSalt.path
            )

        else ->
            advice(
                "Daily monitoring", "Pemantauan harian", "每日监测", "தினசரி கண்காணிப்பு",
                "Daily tracking of weight, symptoms, swelling and breathing changes can help identify worsening heart failure earlier.",
                "Pemantauan harian berat, simptom, bengkak dan perubahan pernafasan boleh membantu mengesan kemerosotan kegagalan jantung lebih awal.",
                "每天记录体重、症状、肿胀和呼吸变化，有助于更早发现心力衰竭恶化。",
                "எடை, அறிகுறிகள், வீக்கம் மற்றும் சுவாச மாற்றங்களை தினமும் பதிவு செய்வது இதய செயலிழப்பு மோசமாவதை முன்கூட்டியே கண்டறிய உதவும்.",
                Icons.Default.Lightbulb, Color(0xFF5E35B1), Color(0xFFF3E5F5), Route.SelfCheck.path
            )
    }
}

@Composable
private fun DashboardAdviceCard(
    advice: DashboardAdviceUi,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = advice.route != null, onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = advice.background),
        border = BorderStroke(1.dp, advice.accent.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = advice.icon,
                    contentDescription = null,
                    tint = advice.accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = AppLanguage.text("Today's Advice", "Nasihat Hari Ini", "今日建议", "இன்றைய ஆலோசனை"),
                    style = MaterialTheme.typography.labelLarge,
                    color = advice.accent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = advice.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = advice.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (advice.route != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = AppLanguage.text("Open related page", "Buka halaman berkaitan", "打开相关页面", "தொடர்புடைய பக்கத்தைத் திற"),
                    tint = advice.accent
                )
            }
        }
    }
}

@Composable
private fun DashboardUpcomingCard(
    appointments: List<Appointment>,
    reminders: List<ReminderRow>
) {
    if (appointments.isEmpty() && reminders.isEmpty()) return
    val ms = AppLanguage.useMalay
    val items = buildList {
        appointments.forEach { appt ->
            add(
                Triple(
                    appt.title ?: AppLanguage.text("Appointment", "Temu janji"),
                    listOfNotNull(dashboardDateText(appt.appointment_date), appt.appointment_time).filter { it.isNotBlank() }.joinToString(" • "),
                    Icons.Default.Event
                )
            )
        }
        reminders.forEach { reminder ->
            add(
                Triple(
                    reminder.title ?: AppLanguage.text("Reminder", "Peringatan"),
                    listOfNotNull(displayDateTimeText(reminder.due_ts), reminder.notes).filter { it.isNotBlank() }.joinToString(" • "),
                    Icons.Default.Notifications
                )
            )
        }
    }.distinctBy { it.first to it.second }.take(5)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, null, tint = Color(0xFF5E35B1))
                Spacer(Modifier.width(8.dp))
                Text(AppLanguage.text("Appointment within 30 minutes", "Temu janji dalam 30 minit", "30 分钟内的预约", "30 நிமிடங்களில் சந்திப்பு"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            }
            if (items.isEmpty()) {
                Text(
                    AppLanguage.text("No upcoming appointment or reminder.", "Tiada temu janji atau peringatan akan datang."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                items.forEach { (title, subtitle, icon) ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, Color(0xFFEBEEF2))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFEEE8FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, null, tint = Color(0xFF5E35B1), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                if (subtitle.isNotBlank()) {
                                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTrendCard(
    weightHistory: List<WeightDay>,
    bpHistory: List<BpEvent>,
    symptomHistory: List<SymptomLog>,
    stepsHistory: List<StepsDay>,
    bandMetricHistory: List<SmartBandDailyMetric>
) {
    val ms = AppLanguage.useMalay
    val weights = weightHistory
        .filter { isDashboardLatestSevenDays(it.date) }
        .sortedBy { it.date }
    val steps = stepsHistory
        .filter { isDashboardLatestSevenDays(it.date) }
        .sortedBy { it.date }
    val symptoms = symptomHistory
        .filter { isDashboardLatestSevenDays(it.date ?: it.logged_at ?: it.created_at) }
        .sortedBy { it.date ?: it.logged_at?.take(10) ?: it.created_at?.take(10).orEmpty() }
    val bps = bpHistory
        .filter { isDashboardLatestSevenDays(it.reading_date ?: it.recorded_at) }
        .sortedWith(compareBy<BpEvent> { it.reading_date ?: it.recorded_at?.take(10).orEmpty() }.thenBy { it.reading_time ?: it.recorded_at.orEmpty() })
    val hrs = bandMetricHistory
        .filter { isDashboardLatestSevenDays(it.date) && (it.avg_hr ?: 0) > 0 }
        .sortedBy { it.date }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ShowChart, null, tint = Color(0xFF0D7A5F))
                Spacer(Modifier.width(8.dp))
                Text(AppLanguage.text("Overall trends", "Trend keseluruhan"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            }

            MiniTrendRow(
                title = AppLanguage.text("Weight", "Berat"),
                subtitle = weights.lastOrNull()?.kg_avg?.let { "%.1f kg".format(it) } ?: "--",
                values = weights.map { (it.kg_avg ?: it.kg_max ?: it.kg_min ?: 0.0).toFloat() }
            )
            MiniTrendRow(
                title = AppLanguage.text("Steps", "Langkah"),
                subtitle = steps.lastOrNull()?.steps_total?.toString() ?: "--",
                values = steps.map { (it.steps_total ?: 0L).toFloat() }
            )
            MiniTrendRow(
                title = AppLanguage.text("HR", "Denyutan Jantung"),
                subtitle = hrs.lastOrNull()?.avg_hr?.let { "$it bpm" } ?: "--",
                values = hrs.map { (it.avg_hr ?: 0L).toFloat() }
            )
            MiniDualTrendRow(
                title = AppLanguage.text("Blood pressure", "Tekanan darah"),
                subtitle = bps.lastOrNull()?.let { "${it.systolic ?: "--"}/${it.diastolic ?: "--"} mmHg" } ?: "--",
                firstValues = bps.map { (it.systolic ?: 0).toFloat() },
                secondValues = bps.map { (it.diastolic ?: 0).toFloat() },
                firstAccent = Color(0xFFE53935),
                secondAccent = Color(0xFF1E88E5)
            )
            MiniTrendRow(
                title = AppLanguage.text("Symptoms", "Simptom"),
                subtitle = symptoms.lastOrNull()?.let {
                    ((it.cough ?: 0) + (it.sob_activity ?: 0) + (it.leg_swelling ?: 0) + (it.abd_discomfort ?: 0) + (it.orthopnea ?: 0)).toString()
                } ?: "--",
                values = symptoms.map { ((it.cough ?: 0) + (it.sob_activity ?: 0) + (it.leg_swelling ?: 0) + (it.abd_discomfort ?: 0) + (it.orthopnea ?: 0)).toFloat() }
            )
        }
    }
}

@Composable
private fun MiniTrendRow(title: String, subtitle: String, values: List<Float>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFEBEEF2))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(subtitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Sparkline(
                values = values,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )
        }
    }
}

@Composable
private fun MiniDualTrendRow(title: String, subtitle: String, firstValues: List<Float>, secondValues: List<Float>, firstAccent: Color, secondAccent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFEBEEF2))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(subtitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            DualSparkline(
                firstValues = firstValues,
                secondValues = secondValues,
                firstColor = firstAccent,
                secondColor = secondAccent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    val safeValues = values.let { if (it.isEmpty()) listOf(0f, 0f) else it }
    val max = safeValues.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val min = safeValues.minOrNull() ?: 0f
    val range = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier) {
        val stroke = 3.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        drawLine(
            color = Color(0xFFD9DEE5),
            start = Offset(0f, size.height - 1.dp.toPx()),
            end = Offset(size.width, size.height - 1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash
        )
        if (safeValues.size == 1) return@Canvas
        val stepX = size.width / (safeValues.size - 1).coerceAtLeast(1)
        var previous: Offset? = null
        safeValues.forEachIndexed { index, value ->
            val x = stepX * index
            val y = size.height - (((value - min) / range) * (size.height - stroke)).coerceIn(0f, size.height)
            val current = Offset(x, y)
            previous?.let { drawLine(color = Color.Black, start = it, end = current, strokeWidth = stroke) }
            drawCircle(color = Color.Black, radius = 3.4.dp.toPx(), center = current)
            previous = current
        }
    }
}

@Composable
private fun DualSparkline(firstValues: List<Float>, secondValues: List<Float>, firstColor: Color, secondColor: Color, modifier: Modifier = Modifier) {
    val data1 = firstValues.let { if (it.isEmpty()) listOf(0f, 0f) else it }
    val data2 = secondValues.let { if (it.isEmpty()) List(data1.size) { 0f } else it }
    val all = data1 + data2
    val max = all.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val min = all.minOrNull() ?: 0f
    val range = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier) {
        val stroke = 2.5.dp.toPx()
        val stepX = size.width / (data1.size - 1).coerceAtLeast(1)
        fun drawSeries(values: List<Float>, color: Color) {
            var previous: Offset? = null
            values.forEachIndexed { index, value ->
                val x = stepX * index
                val y = size.height - (((value - min) / range) * (size.height - stroke)).coerceIn(0f, size.height)
                val current = Offset(x, y)
                previous?.let { drawLine(color = color, start = it, end = current, strokeWidth = stroke) }
                previous = current
            }
        }
        drawSeries(data1, firstColor)
        drawSeries(data2, secondColor)
    }
}

@Composable
private fun AlertStatusCard(
    alert: com.vitalink.app.util.AlertResult
) {
    val color = when (alert.level) {
        AlertLevel.RED -> CautionRed
        AlertLevel.YELLOW -> CautionYellow
        AlertLevel.GREEN -> Color(0xFF43A047)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "alertBlink")
    val barAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (alert.level == AlertLevel.GREEN) 1f else 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alertBarAlpha"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .alpha(barAlpha)
                    .background(color)
            )

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (alert.level == AlertLevel.GREEN) Icons.Default.CheckCircle else Icons.Default.Warning,
                        null,
                        tint = color
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        alert.title,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Text(
                    alert.message,
                    style = MaterialTheme.typography.bodyMedium
                )

                alert.reasons.take(3).forEach {
                    Text(
                        "• $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            }
        }
    }
}

@Composable
private fun BandMetricTile(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    status: TileStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(122.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(3.dp, Color.Black),
        colors = CardDefaults.cardColors(containerColor = tileColor(status))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                null,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )

            Spacer(Modifier.height(6.dp))

            Text(
                value,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color(0xFF2D4D48)
            )

            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6C837D),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DashboardMedicineReminderCard(
    profileMedications: List<MedicationEntry>,
    medications: List<Medication>,
    schedules: List<MedicationSchedule>,
    appointments: List<Appointment>,
    reminders: List<ReminderRow>
) {
    val activeMeds = medications.filter { it.is_active }
    val profileMedLines = profileMedications.map { med ->
        listOf(med.name, med.dosage, med.reminderTime).filter { it.isNotBlank() }.joinToString(" • ")
    }
    val tableMedLines = activeMeds.map { med ->
        listOfNotNull(med.name, med.dosage, med.frequency, med.time_of_day).filter { it.isNotBlank() }.joinToString(" • ")
    }
    val medicineLines = (profileMedLines + tableMedLines).distinct().take(5)
    val appointmentLines = appointments.map { appt ->
        listOfNotNull(
            appt.title ?: AppLanguage.text("General appointment", "Temu janji umum"),
            dashboardDateText(appt.appointment_date),
            appt.appointment_time,
            appt.notes
        ).filter { it.isNotBlank() }.joinToString(" • ")
    }

    val reminderLines = reminders.map { reminder ->
        listOfNotNull(
            reminder.title ?: AppLanguage.text("General reminder", "Peringatan umum"),
            displayDateTimeText(reminder.due_ts),
            reminder.notes
        ).filter { it.isNotBlank() }.joinToString(" • ")
    }

    val upcomingAppointmentLines = (appointmentLines + reminderLines).distinct().take(5)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F1FF))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Medication, null, tint = Color(0xFF5E35B1))
                Spacer(Modifier.width(8.dp))
                Text(
                    AppLanguage.text("Medicine Record & General Appointment", "Rekod Ubat & Temu Janji Umum"),
                    fontWeight = FontWeight.Bold
                )
            }

            DashboardMiniSection(
                title = AppLanguage.text("Medicine Record", "Rekod Ubat"),
                emptyText = AppLanguage.text("No active medicine record.", "Tiada rekod ubat aktif."),
                lines = medicineLines
            )

            DashboardMiniSection(
                title = AppLanguage.text("General Appointment", "Temu Janji Umum"),
                emptyText = AppLanguage.text("No upcoming appointment or reminder.", "Tiada temu janji atau peringatan akan datang."),
                lines = upcomingAppointmentLines
            )
        }
    }
}

@Composable
private fun DashboardMiniSection(
    title: String,
    emptyText: String,
    lines: List<String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        if (lines.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            lines.forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )

        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f)
        )
    }
}

@Composable
private fun FeatureCardTile(
    f: FeatureCard,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(150.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Image(
                painter = painterResource(id = f.imageRes),
                contentDescription = AppLanguage.text(f.label, f.msLabel, f.zhLabel, f.taLabel),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.06f),
                                f.colors.first().copy(alpha = 0.20f),
                                f.colors.last().copy(alpha = 0.92f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.White.copy(alpha = 0.90f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        f.icon,
                        null,
                        tint = f.colors.last(),
                        modifier = Modifier.size(25.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color.Black.copy(alpha = 0.20f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        AppLanguage.text(f.label, f.msLabel, f.zhLabel, f.taLabel),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        lineHeight = 24.sp
                    )

                    Spacer(Modifier.height(2.dp))

                    if (AppLanguage.text(f.sub, f.msSub).isNotBlank()) Text(
                        AppLanguage.text(f.sub, f.msSub),
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 13.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

private fun showRedAlertNotificationIfAllowed(
    context: Context,
    title: String,
    message: String
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) return
    }

    showRedAlertNotification(
        context = context,
        title = title,
        message = message
    )
}

private fun showRedAlertNotification(
    context: Context,
    title: String,
    message: String
) {
    val channelId = "red_alert_channel"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            AppLanguage.text("Red Alert", "Amaran Merah"),
            NotificationManager.IMPORTANCE_HIGH
        )

        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    // HealthDeviationWorker and SelfCheckAlertNotifier store localized alert history.
    // Avoid adding a second dashboard-only history item in the currently selected language.

    val notification = NotificationCompat.Builder(
        context,
        channelId
    )
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(message)
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(NotificationNavigation.pendingIntent(context, Route.Dashboard.path, 2001))
        .setAutoCancel(true)
        .build()

    try {
        NotificationManagerCompat.from(context)
            .notify(2001, notification)
    } catch (_: SecurityException) {
    }
}

/**
 * Opens the Health Connect settings screen.
 *
 * On standard Android / Pixel devices this is the Google Health Connect app.
 * On **vivo** devices, Health Connect is embedded inside the vivo Health app
 * (package: com.bbk.healthapp). We try the standard action first; if that
 * fails we fall back to launching the vivo Health app directly.
 */
private fun openHealthConnectSettings(context: Context) {
    // Standard Health Connect settings intent (works on Pixel / most phones)
    val intents = listOf(
        Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
        // vivo Health app — direct launch fallback
        context.packageManager
            .getLaunchIntentForPackage("com.bbk.healthapp"),
        context.packageManager
            .getLaunchIntentForPackage("com.vivo.health")
    )
    for (intent in intents) {
        if (intent == null) continue
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // try next
        }
    }
}
