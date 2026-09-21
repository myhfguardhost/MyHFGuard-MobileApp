package com.vitalink.app.data.api

import com.vitalink.app.data.model.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.POST
import retrofit2.http.Query
import okhttp3.MultipartBody
import okhttp3.RequestBody

interface ServerApiService {
    @Multipart @POST("api/ocr/weight")
    suspend fun scanWeightImage(@Part image: MultipartBody.Part, @Part("patientId") patientId: RequestBody): Response<WebsiteWeightOcrResponse>

    @Multipart @POST("api/process-image")
    suspend fun scanBloodPressureImage(@Part image: MultipartBody.Part, @Part("patientId") patientId: RequestBody): Response<WebsiteBloodPressureOcrResponse>

    @POST("admin/ensure-patient")
    suspend fun ensurePatient(@Body req: ServerEnsurePatientRequest): Response<ServerOkResponse>

    @POST("api/add-manual-event")
    suspend fun addManualBp(@Body req: ServerBpManualRequest): Response<ServerOkResponse>

    @POST("ingest/symptom-log")
    suspend fun addSymptomLog(@Body req: ServerSymptomRequest): Response<ServerOkResponse>

    @GET("patient/daily-status")
    suspend fun getDailyStatus(@Query("patientId") patientId: String, @Query("date") date: String): Response<ServerDailyStatusResponse>

    @POST("api/chat/symptoms")
    suspend fun chat(@Body req: ChatRequest): Response<ChatResponse>
}
