package com.vitalink.app.data.model

import com.google.gson.annotations.SerializedName

data class PatientRow(
    @SerializedName("patient_id") val id: String,
    val first_name: String? = null,
    val last_name: String? = null,
    val email: String? = null,
    val dob: String? = null
)

data class PatientInfoResponse(val patient: PatientRow?)

data class PatientUpsert(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("first_name") val firstName: String = "Mobile",
    @SerializedName("last_name") val lastName: String = "User",
    val dob: String = "1970-01-01"
)

data class ProfileUpdateRequest(
    @SerializedName("first_name") val firstName: String,
    @SerializedName("last_name") val lastName: String,
    val email: String? = null
)

data class ProfileRow(
    val user_id: String? = null,
    val full_name: String? = null,
    val age: Int? = null,
    val ic: String? = null,
    val systolic_bp: Int? = null,
    val diastolic_bp: Int? = null,
    val heart_rate: Int? = null,
    val dry_weight: Double? = null,
    val height: Double? = null,
    val bmi: Double? = null,
    val current_medication: String? = null,
    val language: String? = null,
    val target_steps: Int? = null,
    val coins: Int? = null,
    val profile_completed: Boolean? = null,
    val baseline_locked: Boolean? = null
)

fun ProfileRow.isCompletedAndLocked(): Boolean =
    profile_completed == true && baseline_locked == true

data class ProfileUpsert(
    val user_id: String,
    val full_name: String? = null,
    val age: Int? = null,
    val ic: String? = null,
    val systolic_bp: Int? = null,
    val diastolic_bp: Int? = null,
    val heart_rate: Int? = null,
    val dry_weight: Double? = null,
    val height: Double? = null,
    val bmi: Double? = null,
    val current_medication: String? = null,
    val language: String? = null,
    val profile_completed: Boolean = true,
    val baseline_locked: Boolean = true
)

data class SummaryResponse(val summary: PatientSummary?)

data class PatientSummary(
    val lastSyncTs: String?,
    val latestBp: BpEvent?,
    val latestWeight: Double?,
    val medicationCount: Int?,
    val steps: Long?,
    val distanceMeters: Double? = null,
    val avgHr: Long?,
    val avgSpo2: Int?,
    val baselineWeight: Double? = null,
    val previousWeight: Double? = null,
    val recentWeightGainKg: Double? = null,
    val baselineSystolic: Int? = null,
    val baselineHeartRate: Int? = null,
    val daysWithoutWeightLogThisWeek: Int? = null,
    val daysWithoutSymptomLogThisWeek: Int? = null
)

data class BpEvent(
    val id: String? = null,
    val patient_id: String,
    val systolic: Int?,
    val diastolic: Int?,
    val pulse: Int?,
    val reading_date: String? = null,
    val reading_time: String? = null,
    val recorded_at: String? = null,
    val notes: String? = null
)

data class AddBpRequest(
    @SerializedName("patient_id") val patientId: String,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    @SerializedName("reading_date") val readingDate: String = java.time.LocalDate.now().toString(),
    @SerializedName("reading_time") val readingTime: String = java.time.LocalTime.now().withNano(0).toString()
)

data class SymptomLog(
    val id: String? = null,
    val patient_id: String? = null,
    val date: String? = null,
    val cough: Int? = null,
    val sob_activity: Int? = null,
    val leg_swelling: Int? = null,
    val abd_discomfort: Int? = null,
    val orthopnea: Int? = null,
    val notes: String? = null,
    val logged_at: String? = null,
    val created_at: String? = null
)

data class AddSymptomLogRequest(
    @SerializedName("patient_id") val patientId: String,
    val date: String,
    val cough: Int = 0,
    @SerializedName("sob_activity") val sobActivity: Int = 0,
    @SerializedName("leg_swelling") val legSwelling: Int = 0,
    @SerializedName("abd_discomfort") val abdDiscomfort: Int = 0,
    val orthopnea: Int = 0,
    val notes: String? = null
)

data class Medication(
    val id: String,
    val patient_id: String,
    val name: String,
    val dosage: String? = null,
    @SerializedName("class") val medication_class: String? = null,
    val frequency: String? = null,
    val time_of_day: String? = null,
    val notes: String? = null,
    @SerializedName("active") val is_active: Boolean = true,
    val created_at: String? = null
)

data class AddMedicationRequest(
    @SerializedName("patient_id") val patientId: String,
    val name: String,
    val dosage: String,
    val frequency: String,
    @SerializedName("time_of_day") val timeOfDay: String?,
    val notes: String?,
    @SerializedName("is_active") val isActive: Boolean = true
)
typealias MedicationInsert = AddMedicationRequest

data class MedicationEntry(
    val name: String,
    val dosage: String = "",
    val reminderTime: String = "12:00 PM"
)

data class MedicationSchedule(
    val id: String,
    val patient_id: String,
    val medicine_id: String? = null,
    val medication_id: String? = null,
    val title: String? = null,
    val time: String? = null,
    val dosage: String? = null,
    val notes: String? = null,
    val created_at: String? = null
)

data class MedicationScheduleInsert(
    @SerializedName("patient_id") val patientId: String,
    val title: String? = null,
    val time: String? = null,
    val dosage: String? = null,
    val notes: String? = null
)

data class Appointment(
    val id: String? = null,
    val patient_id: String? = null,
    val title: String? = null,
    val appointment_date: String? = null,
    val appointment_time: String? = null,
    val notes: String? = null,
    val created_at: String? = null,
    val source: String? = null
)

data class AddAppointmentRequest(
    @SerializedName("patient_id") val patientId: String,
    val title: String,
    @SerializedName("appointment_date") val appointmentDate: String,
    @SerializedName("appointment_time") val appointmentTime: String?,
    val notes: String?
)

data class ReminderRow(
    val id: String,
    val patient_id: String? = null,
    val title: String? = null,
    val due_ts: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val type: String? = null,
    val created_at: String? = null
)

data class ReminderInsert(
    val patient_id: String,
    val title: String,
    val type: String = "general",
    val due_ts: String,
    val notes: String? = null,
    val status: String = "upcoming"
)

/** Row supplied by the administrator through Supabase's patient_notifications table. */
data class PatientNotification(
    val id: String,
    val patient_id: String? = null,
    val notification_type: String? = null,
    val dedupe_key: String? = null,
    val title: String? = null,
    val message: String? = null,
    val scheduled_for: String? = null,
    val created_at: String? = null
)

data class WaterSaltLog(
    val id: String? = null,
    val patient_id: String,
    val entry_date: String,
    val water_limit_ml: Int? = null,
    val water_cups: Int? = null,
    val water_intake_ml: Int? = null,
    val water_status: String? = null,
    val breakfast_salt: String? = null,
    val lunch_salt: String? = null,
    val dinner_salt: String? = null,
    val salt_score: Int? = null,
    val salt_status: String? = null,
    // legacy nullable fields kept so older DB rows do not crash Gson
    val water_ml: Int? = null,
    val salt_mg: Int? = null,
    val logged_at: String? = null
)

data class AddWaterRequest(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("water_ml") val waterMl: Int,
    @SerializedName("salt_mg") val saltMg: Int?
)

data class WaterSaltUpsert(
    val patient_id: String,
    val entry_date: String,
    val water_limit_ml: Int,
    val water_cups: Int,
    val water_intake_ml: Int,
    val water_status: String,
    val breakfast_salt: String,
    val lunch_salt: String,
    val dinner_salt: String,
    val salt_score: Int,
    val salt_status: String
)

data class ExerciseLog(
    val id: String,
    val patient_id: String,
    val exercise_type: String,
    val duration_minutes: Int,
    val intensity: String,
    val notes: String? = null,
    val logged_at: String
)

data class AddExerciseRequest(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("exercise_type") val exerciseType: String,
    @SerializedName("duration_minutes") val durationMinutes: Int,
    val intensity: String,
    val notes: String?
)

data class ExerciseGoal(
    val id: String? = null,
    val patient_id: String,
    val week_key: String,
    val goal: String,
    val achievement_rating: Int? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class ExerciseGoalUpsert(
    val patient_id: String,
    val week_key: String,
    val goal: String
)



data class EducationVideoReward(
    val id: String? = null,
    val user_id: String? = null,
    val video_id: String? = null,
    val coins_awarded: Int? = null,
    val created_at: String? = null
)

data class EducationVideoRewardInsert(
    val user_id: String,
    val video_id: String,
    val coins_awarded: Int = 10
)

data class MetricsSyncRequest(
    val patient_id: String,
    val steps: Long,
    val distance: Long,
    val avg_hr: Long? = null,
    val avg_spo2: Int? = null,
    val date: String
)

data class SmartBandDailyMetric(
    val patient_id: String,
    val date: String,
    val steps: Long? = null,
    val distance: Long? = null,
    val avg_hr: Long? = null,
    val avg_spo2: Int? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class StepsDay(
    val patient_id: String,
    val date: String,
    val steps_total: Long? = null,
    val distance_meters: Double? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class StepsDayInsert(
    val patient_id: String,
    val date: String,
    val steps_total: Long,
    val distance_meters: Double? = null
)

data class StepsHour(val patient_id: String, val hour_ts: String, val steps_total: Long? = null)
data class StepsHourInsert(val patient_id: String, val hour_ts: String, val steps_total: Long)

data class Spo2Day(
    val patient_id: String,
    val date: String,
    val spo2_min: Double? = null,
    val spo2_max: Double? = null,
    val spo2_avg: Double? = null,
    val spo2_count: Long? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

data class Spo2DayInsert(
    val patient_id: String,
    val date: String,
    val spo2_min: Double?,
    val spo2_max: Double?,
    val spo2_avg: Double?,
    val spo2_count: Long = 1
)

data class Spo2Hour(
    val patient_id: String,
    val hour_ts: String,
    val spo2_min: Double? = null,
    val spo2_max: Double? = null,
    val spo2_avg: Double? = null,
    val spo2_count: Long? = null
)

data class Spo2HourInsert(
    val patient_id: String,
    val hour_ts: String,
    val spo2_min: Double?,
    val spo2_max: Double?,
    val spo2_avg: Double?,
    val spo2_count: Long = 1
)

/**
 * App-friendly view of a public.weight_sample row. The database stores one
 * measurement in `kg` at `time_ts`; the derived values keep existing charts
 * and trend logic compatible with the daily view.
 */
data class WeightDay(
    val patient_id: String,
    @SerializedName("time_ts") private val timeTs: String,
    @SerializedName("kg") private val kilogram: Double? = null,
    @SerializedName("recorded_at") val created_at: String? = null
) {
    val date: String get() = timeTs.take(10)
    val kg_min: Double? get() = kilogram
    val kg_max: Double? get() = kilogram
    val kg_avg: Double? get() = kilogram

    constructor(
        patient_id: String,
        date: String,
        kg_min: Double? = null,
        kg_max: Double? = null,
        kg_avg: Double? = null,
        created_at: String? = null
    ) : this(patient_id, date, kg_avg ?: kg_max ?: kg_min, created_at)
}

data class WeightSampleInsert(
    val patient_id: String,
    val time_ts: String,
    val recorded_at: String,
    val record_uid: String,
    val kg: Double,
    val origin_id: String = "manual",
    val device_id: String = "selfcheck"
)

data class ChatMessage(val role: String, val content: String)

/** Compact dated points used by the AI to reason over the latest seven days instead of a single snapshot. */
data class AiWeightTrendPoint(val date: String, val kg: Double)
data class AiSymptomTrendPoint(val date: String, val score: Int)
data class AiBpTrendPoint(val date: String, val systolic: Int? = null, val diastolic: Int? = null, val pulse: Int? = null)
data class AiStepsTrendPoint(val date: String, val steps: Long)
data class AiSpo2TrendPoint(val date: String, val spo2: Int)
data class AiHeartRateTrendPoint(val date: String, val bpm: Int)
data class AiWaterSaltTrendPoint(val date: String, val waterMl: Int? = null, val waterLimitMl: Int? = null, val saltScore: Int? = null)

/**
 * Fresh patient data sent with each AI request.
 * The backend may combine this with its own database lookup before prompting Gemini.
 */
data class AiContextState(
    val steps: Long? = null,
    val heartRate: Int? = null,
    val spo2: Int? = null,
    val weight: Double? = null,
    val weightTrendKg: Double? = null,
    val weightHistory7d: List<AiWeightTrendPoint> = emptyList(),
    val bp: String? = null,
    val bpHistory7d: List<AiBpTrendPoint> = emptyList(),
    val pulse: Int? = null,
    val medication: String? = null,
    val symptomScore: Int? = null,
    val symptomTrendDelta: Int? = null,
    val symptomHistory7d: List<AiSymptomTrendPoint> = emptyList(),
    val stepsHistory7d: List<AiStepsTrendPoint> = emptyList(),
    val spo2History7d: List<AiSpo2TrendPoint> = emptyList(),
    val heartRateHistory7d: List<AiHeartRateTrendPoint> = emptyList(),
    val waterMl: Int? = null,
    val waterLimitMl: Int? = null,
    val saltScore: Int? = null,
    val waterSaltHistory7d: List<AiWaterSaltTrendPoint> = emptyList(),
    val nextAppointmentTitle: String? = null,
    val nextAppointmentDate: String? = null,
    val nextAppointmentTime: String? = null,
    val targetSteps: Long = 3000L,
    val loaded: Boolean = false,
    val error: String? = null
)

data class AiKnowledgeContext(
    val title: String,
    val keyPoints: List<String>,
    val sourceUrl: String? = null
)

data class ChatRequest(
    val patientId: String,
    val message: String,
    val history: List<ChatMessage>,
    val context: AiContextState? = null,
    val knowledgeContext: AiKnowledgeContext? = null,
    val memoryNotes: List<String> = emptyList(),
    /** Explicit reply language so short follow-up messages stay consistent. */
    val language: String? = null,
    val clientTimeZone: String = "Asia/Kuala_Lumpur",
    val promptVersion: String = "myhfguard-hf-v9-personalised-bullet-nurse"
)

data class ChatResponse(
    val reply: String?,
    val error: String? = null,
    /** Identifies whether the reply came from Gemini, deterministic safety rules, or the backend fallback. */
    val source: String? = null,
    val riskLevel: String? = null,
    val suggestedAction: String? = null,
    val followUpQuestion: String? = null,
    val memoryUpdates: List<String>? = null,
    val emergency: Boolean = false,
    val model: String? = null
)


data class AiHealthResponse(
    val status: String? = null,
    val service: String? = null,
    val version: String? = null,
    val chatModel: String? = null,
    val geminiConfigured: Boolean? = null,
    val authenticationRequired: Boolean? = null
)

data class SmartOcrRequest(
    val patientId: String,
    val scanType: String,
    val imageBase64: String,
    val mimeType: String = "image/jpeg"
)

data class SmartOcrResponse(
    val success: Boolean = false,
    val scanType: String? = null,
    val weightKg: Double? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val pulse: Int? = null,
    val confidence: Double? = null,
    val message: String? = null,
    val source: String? = null
)
data class WebsiteWeightOcrResponse(val weight: String? = null, val detectedWeight: String? = null, val rawText: String? = null)
data class WebsiteBloodPressureOcrResponse(val sys: String? = null, val dia: String? = null, val pulse: String? = null, val annotatedImage: String? = null)


// Website/server API payloads. These match vitalink/web/src/lib/api.ts and vitalink/server/server.js.
data class ServerOkResponse(
    val ok: Boolean? = null,
    val success: Boolean? = null,
    val error: String? = null,
    val details: String? = null,
    val inserted: Int? = null
)

data class ServerEnsurePatientRequest(
    val patientId: String,
    val firstName: String = "Mobile",
    val lastName: String = "User",
    val dateOfBirth: String = "1970-01-01"
)

data class ServerBpManualRequest(
    val type: String = "blood_pressure",
    val value1: Int,
    val value2: Int,
    val value3: Int,
    val patientId: String,
    val timeTs: String
)

data class ServerSymptomRequest(
    val patientId: String,
    val timeTs: String,
    val cough: Int = 0,
    val breathlessness: Int = 0,
    val swelling: Int = 0,
    val weightGain: Int = 0,
    val abdomen: Int = 0,
    val sleeping: Int = 0,
    val notes: String = "",
    val tzOffsetMin: Int = 480,
    val originId: String = "manual",
    val recordUid: String = ""
)

data class ServerDailyStatusResponse(
    val has_weight: Boolean = false,
    val has_bp: Boolean = false,
    val has_symptoms: Boolean = false
)
