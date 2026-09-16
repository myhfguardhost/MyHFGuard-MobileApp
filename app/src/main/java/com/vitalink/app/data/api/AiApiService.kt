package com.vitalink.app.data.api

import com.vitalink.app.data.model.ChatRequest
import com.vitalink.app.data.model.ChatResponse
import com.vitalink.app.data.model.AiHealthResponse
import com.vitalink.app.data.model.SmartOcrRequest
import com.vitalink.app.data.model.SmartOcrResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Gemini heart-failure assistant API hosted by the existing MyHFGuard
 * Node/Express Render service. No separate FastAPI deployment is required.
 *
 * Chat uses /api/chat/symptoms on the existing server. Smart OCR keeps a
 * silent local fallback if /api/ocr/read is unavailable.
 */
interface AiApiService {
    /** Lightweight call used when the chat screen opens so a sleeping Render service can warm up early. */
    @GET("health")
    suspend fun health(): Response<AiHealthResponse>

    @POST("api/chat/symptoms")
    suspend fun chat(@Body request: ChatRequest): Response<ChatResponse>

    @POST("api/ocr/read")
    suspend fun smartOcr(@Body request: SmartOcrRequest): Response<SmartOcrResponse>
}
