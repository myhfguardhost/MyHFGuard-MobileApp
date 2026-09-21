package com.vitalink.app.data.api

import com.vitalink.app.data.model.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    @GET("patients")
    suspend fun getPatient(@Query(value = "patient_id", encoded = true) patientId: String): Response<List<PatientRow>>

    @Headers("Prefer: resolution=merge-duplicates,return=minimal")
    @POST("patients")
    suspend fun upsertPatient(@Body body: PatientUpsert, @Query(value = "on_conflict", encoded = true) onConflict: String = "patient_id"): Response<Unit>

    @Headers("Prefer: return=representation")
    @PATCH("patients")
    suspend fun updatePatient(@Query(value = "patient_id", encoded = true) patientId: String, @Body req: ProfileUpdateRequest): Response<Unit>

    suspend fun getPatientInfo(patientId: String): Response<PatientInfoResponse> {
        val response = getPatient("eq.$patientId")
        return Response.success(PatientInfoResponse(response.body()?.firstOrNull()))
    }


    @GET("profiles")
    suspend fun getProfile(@Query(value = "user_id", encoded = true) userId: String): Response<List<ProfileRow>>

    @Headers("Prefer: return=representation")
    @PATCH("profiles")
    suspend fun updateProfile(@Query(value = "user_id", encoded = true) userId: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): Response<Unit>

    @Headers("Prefer: resolution=merge-duplicates,return=minimal")
    @POST("profiles")
    suspend fun upsertProfile(@Body body: ProfileUpsert, @Query(value = "on_conflict", encoded = true) onConflict: String = "user_id"): Response<Unit>

    @GET("steps_day")
    suspend fun getStepsDay(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "date.desc"): Response<List<StepsDay>>

    @Headers("Prefer: resolution=merge-duplicates,return=minimal")
    @POST("steps_day")
    suspend fun insertStepsDay(@Body body: StepsDayInsert, @Query(value = "on_conflict", encoded = true) onConflict: String = "patient_id,date"): Response<Unit>

    @Headers("Prefer: return=minimal")
    @POST("steps_day")
    suspend fun insertStepsDayPlain(@Body body: StepsDayInsert): Response<Unit>

    @GET("steps_hour")
    suspend fun getStepsHour(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "hour_ts.desc"): Response<List<StepsHour>>

    @Headers("Prefer: return=minimal") @POST("steps_hour") suspend fun insertStepsHour(@Body body: StepsHourInsert): Response<Unit>

    @GET("spo2_day")
    suspend fun getSpo2Day(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "date.desc"): Response<List<Spo2Day>>

    @Headers("Prefer: resolution=merge-duplicates,return=minimal")
    @POST("spo2_day")
    suspend fun insertSpo2Day(@Body body: Spo2DayInsert, @Query(value = "on_conflict", encoded = true) onConflict: String = "patient_id,date"): Response<Unit>

    @Headers("Prefer: return=minimal")
    @POST("spo2_day")
    suspend fun insertSpo2DayPlain(@Body body: Spo2DayInsert): Response<Unit>

    @GET("spo2_hour")
    suspend fun getSpo2Hour(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "hour_ts.desc"): Response<List<Spo2Hour>>

    @Headers("Prefer: return=minimal") @POST("spo2_hour") suspend fun insertSpo2Hour(@Body body: Spo2HourInsert): Response<Unit>

    @GET("weight_sample")
    suspend fun getWeightDay(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "time_ts.desc"): Response<List<WeightDay>>

    // weight_sample may not define a unique (patient_id, time_ts) constraint.
    // A normal insert avoids an invalid upsert conflict target blocking a save.
    @Headers("Prefer: return=representation")
    @PATCH("weight_sample")
    suspend fun updateWeightSample(
        @Query(value = "patient_id", encoded = true) patientId: String,
        @Query(value = "record_uid", encoded = true) recordUid: String,
        @Body body: Map<String, Double>
    ): Response<List<WeightDay>>

    @Headers("Prefer: return=minimal")
    @POST("weight_sample")
    suspend fun insertWeightDay(@Body body: WeightSampleInsert): Response<Unit>

    @GET("bp_readings")
    suspend fun getBpEvents(
        @Query(value = "patient_id", encoded = true) patientId: String,
        @Query(value = "order", encoded = true) order: String = "reading_date.desc,reading_time.desc"
    ): Response<List<BpEvent>>

    @Headers("Prefer: return=minimal")
    @POST("bp_readings")
    suspend fun addBpManual(@Body req: AddBpRequest): Response<Unit>

    @Headers("Prefer: return=representation")
    @PATCH("bp_readings")
    suspend fun updateBpReadingById(
        @Query(value = "id", encoded = true) id: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Unit>

    @Headers("Prefer: return=representation")
    @PATCH("bp_readings")
    suspend fun updateBpReadingByDate(
        @Query(value = "patient_id", encoded = true) patientId: String,
        @Query(value = "reading_date", encoded = true) readingDate: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Unit>

    @GET("symptom_log")
    suspend fun getSymptomLogs(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "created_at.desc"): Response<List<SymptomLog>>

    @Headers("Prefer: return=minimal") @POST("symptom_log") suspend fun addSymptomLog(@Body req: AddSymptomLogRequest): Response<Unit>
    @Headers("Prefer: return=representation")
    @PATCH("symptom_log")
    suspend fun updateSymptomLog(
        @Query(value = "patient_id", encoded = true) patientId: String,
        @Query(value = "date", encoded = true) date: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Unit>
    @Headers("Prefer: return=representation")
    @PATCH("symptom_log")
    suspend fun updateSymptomLogById(
        @Query(value = "id", encoded = true) id: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Unit>

    @GET("medication")
    suspend fun getMedications(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "created_at.desc"): Response<List<Medication>>

    @Headers("Prefer: return=minimal") @POST("medication") suspend fun insertMedication(@Body body: MedicationInsert): Response<Unit>
    @Headers("Prefer: return=minimal") @DELETE("medication") suspend fun deleteMedication(@Query(value = "id", encoded = true) id: String): Response<Unit>

    @GET("medication_schedule")
    suspend fun getMedicationSchedule(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "created_at.desc"): Response<List<MedicationSchedule>>

    @Headers("Prefer: return=minimal") @POST("medication_schedule") suspend fun insertMedicationSchedule(@Body body: MedicationScheduleInsert): Response<Unit>
    @Headers("Prefer: return=minimal") @DELETE("medication_schedule") suspend fun deleteMedicationSchedule(@Query(value = "id", encoded = true) id: String): Response<Unit>

    @GET("appointments")
    suspend fun getAppointments(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "appointment_date.asc"): Response<List<Appointment>>

    @Headers("Prefer: return=minimal") @POST("appointments") suspend fun addAppointment(@Body req: AddAppointmentRequest): Response<Unit>
    @Headers("Prefer: return=representation")
    @PATCH("appointments")
    suspend fun updateAppointment(@Query(value = "id", encoded = true) id: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): Response<Unit>
    @Headers("Prefer: return=minimal") @DELETE("appointments") suspend fun deleteAppointment(@Query(value = "id", encoded = true) id: String): Response<Unit>

    @GET("reminders")
    suspend fun getReminders(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "due_ts.asc"): Response<List<ReminderRow>>

    /** Messages created by an administrator for one patient. RLS limits this to the signed-in patient. */
    @GET("patient_notifications")
    suspend fun getPatientNotifications(
        @Query(value = "patient_id", encoded = true) patientId: String,
        @Query(value = "order", encoded = true) order: String = "scheduled_for.asc"
    ): Response<List<PatientNotification>>

    @Headers("Prefer: return=minimal") @POST("reminders") suspend fun insertReminder(@Body body: ReminderInsert): Response<Unit>
    @Headers("Prefer: return=representation")
    @PATCH("reminders")
    suspend fun updateReminder(@Query(value = "id", encoded = true) id: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): Response<Unit>
    @Headers("Prefer: return=minimal") @DELETE("reminders") suspend fun deleteReminder(@Query(value = "id", encoded = true) id: String): Response<Unit>

    @GET("water_salt_logs")
    suspend fun getWaterLogs(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "entry_date.desc"): Response<List<WaterSaltLog>>

    @Headers("Prefer: resolution=merge-duplicates,return=minimal")
    @POST("water_salt_logs")
    suspend fun upsertWaterSaltLog(@Body body: WaterSaltUpsert, @Query(value = "on_conflict", encoded = true) onConflict: String = "patient_id,entry_date"): Response<Unit>

    @Headers("Prefer: return=minimal") @POST("water_salt_logs") suspend fun addWaterLog(@Body req: AddWaterRequest): Response<Unit>

    @GET("exercise_logs")
    suspend fun getExerciseLogs(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "logged_at.desc"): Response<List<ExerciseLog>>

    @Headers("Prefer: return=minimal") @POST("exercise_logs") suspend fun addExerciseLog(@Body req: AddExerciseRequest): Response<Unit>

    @GET("exercise_goals")
    suspend fun getExerciseGoals(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "week_key.desc"): Response<List<ExerciseGoal>>

    @Headers("Prefer: resolution=merge-duplicates,return=minimal")
    @POST("exercise_goals")
    suspend fun upsertExerciseGoal(@Body body: ExerciseGoalUpsert, @Query(value = "on_conflict", encoded = true) onConflict: String = "patient_id,week_key"): Response<Unit>

    @Headers("Prefer: return=minimal")
    @POST("exercise_goals")
    suspend fun insertExerciseGoal(@Body body: ExerciseGoalUpsert): Response<Unit>

    @Headers("Prefer: return=representation")
    @PATCH("exercise_goals")
    suspend fun updateExerciseGoal(
        @Query(value = "patient_id", encoded = true) patientId: String,
        @Query(value = "week_key", encoded = true) weekKey: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Unit>

    @GET("education_video_rewards")
    suspend fun getEducationVideoRewards(
        @Query(value = "user_id", encoded = true) userId: String,
        @Query(value = "order", encoded = true) order: String? = null
    ): Response<List<EducationVideoReward>>

    @Headers("Prefer: return=minimal")
    @POST("education_video_rewards")
    suspend fun insertEducationVideoReward(@Body body: EducationVideoRewardInsert): Response<Unit>

    @Headers("Prefer: return=minimal")
    @DELETE("education_video_rewards")
    suspend fun deleteEducationVideoReward(
        @Query(value = "user_id", encoded = true) userId: String,
        @Query(value = "video_id", encoded = true) videoId: String
    ): Response<Unit>

    @GET("smart_band_daily_metrics")
    suspend fun getSmartBandMetrics(@Query(value = "patient_id", encoded = true) patientId: String, @Query(value = "order", encoded = true) order: String = "date.desc"): Response<List<SmartBandDailyMetric>>

    @Headers("Prefer: resolution=merge-duplicates,return=minimal")
    @POST("smart_band_daily_metrics")
    suspend fun syncMetrics(@Body body: MetricsSyncRequest, @Query(value = "on_conflict", encoded = true) onConflict: String = "patient_id,date"): Response<Unit>

    @Headers("Prefer: return=minimal")
    @POST("smart_band_daily_metrics")
    suspend fun syncMetricsPlain(@Body body: MetricsSyncRequest): Response<Unit>

    @POST("symptom-checker/chat") suspend fun chat(@Body req: ChatRequest): Response<ChatResponse>
}
