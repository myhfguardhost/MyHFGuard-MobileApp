package com.vitalink.app.di

import android.content.Context
import com.vitalink.app.BuildConfig
import com.vitalink.app.HealthConnectManager
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.AiApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.api.ServerApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton
import javax.inject.Named
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(session: SessionManager): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val supabaseInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val accessToken = runBlocking { session.accessToken.first() }
                ?.takeIf { it.isNotBlank() }
            val refreshToken = runBlocking { session.refreshToken.first() }
                ?.takeIf { it.isNotBlank() }

            // Very important for Supabase RLS:
            // apikey = anon key, but Authorization must be the logged-in user's access token.
            val firstBearerToken = accessToken ?: BuildConfig.SUPABASE_ANON_KEY
            val firstRequest = originalRequest.withSupabaseHeaders(firstBearerToken)
            val firstResponse = chain.proceed(firstRequest)

            // If the user stays logged in for a long time, Supabase access tokens expire.
            // Retry once with a refreshed token so Health Connect capture and inserts stay sensitive.
            if (firstResponse.code == 401 && refreshToken != null) {
                val newTokens = refreshSupabaseToken(refreshToken)
                if (newTokens != null) {
                    runBlocking { session.updateTokens(newTokens.first, newTokens.second ?: refreshToken) }
                    firstResponse.close()
                    val retryResponse = chain.proceed(
                        originalRequest.withSupabaseHeaders(newTokens.first)
                    )

                    // The refreshed token should always be accepted. If Supabase
                    // still returns 401, the saved session is no longer valid.
                    if (retryResponse.code == 401) {
                        runBlocking { session.clear() }
                    }
                    retryResponse
                } else {
                    // Refresh tokens can be revoked or can belong to an older
                    // Supabase project after an app update. Clear the stale local
                    // session so the UI returns to the login page automatically.
                    runBlocking { session.clear() }
                    firstResponse
                }
            } else {
                if (firstResponse.code == 401 && accessToken != null) {
                    runBlocking { session.clear() }
                }
                firstResponse
            }
        }

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(supabaseInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    private fun Request.withSupabaseHeaders(bearerToken: String): Request {
        val builder = newBuilder()
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $bearerToken")
            .header("Content-Type", "application/json")

        // Do NOT overwrite endpoint-specific Prefer headers.
        if (header("Prefer").isNullOrBlank()) {
            builder.header("Prefer", "return=minimal")
        }
        return builder.build()
    }

    private fun refreshSupabaseToken(refreshToken: String): Pair<String, String?>? {
        return try {
            val body = """{"refresh_token":"$refreshToken"}"""
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/token?grant_type=refresh_token")
                .post(body)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .build()
            OkHttpClient.Builder().build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
                val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
                access to refresh
            }
        } catch (_: Exception) {
            null
        }
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }



    @Provides
    @Singleton
    @Named("server")
    fun provideServerOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            // Render can cold-start, and an AI response may take longer than a normal REST call.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("server")
    fun provideServerRetrofit(@Named("server") client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideServerApiService(@Named("server") retrofit: Retrofit): ServerApiService {
        return retrofit.create(ServerApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("ocr")
    fun provideOcrServerApiService(@Named("server") client: OkHttpClient): ServerApiService {
        return Retrofit.Builder()
            .baseUrl("https://myhfguard-ocr.onrender.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ServerApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("ai")
    fun provideAiOkHttpClient(session: SessionManager): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val accessToken = runBlocking { session.accessToken.first() }
                ?.takeIf { it.isNotBlank() }
            val refreshToken = runBlocking { session.refreshToken.first() }
                ?.takeIf { it.isNotBlank() }

            fun authenticatedRequest(token: String?): Request {
                val builder = originalRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .header("X-MyHFGuard-Client", "android")
                if (!token.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $token")
                }
                return builder.build()
            }

            val firstResponse = chain.proceed(authenticatedRequest(accessToken))

            // The AI backend validates the same Supabase access token as the rest of the app.
            // Previously this client did NOT refresh an expired token, so a long-lived login
            // could receive HTTP 401 and silently fall back to the local Kotlin responder.
            // Refresh once and retry the exact AI request, matching the Supabase client behaviour.
            if (firstResponse.code == 401 && refreshToken != null) {
                val newTokens = refreshSupabaseToken(refreshToken)
                if (newTokens != null) {
                    runBlocking { session.updateTokens(newTokens.first, newTokens.second ?: refreshToken) }
                    firstResponse.close()
                    val retryResponse = chain.proceed(authenticatedRequest(newTokens.first))
                    if (retryResponse.code == 401) {
                        runBlocking { session.clear() }
                    }
                    retryResponse
                } else {
                    runBlocking { session.clear() }
                    firstResponse
                }
            } else {
                if (firstResponse.code == 401 && accessToken != null) {
                    runBlocking { session.clear() }
                }
                firstResponse
            }
        }
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // BODY logs may contain health data, so never log AI request bodies.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("ai")
    fun provideAiRetrofit(@Named("ai") client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.AI_API_BASE_URL.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideAiApiService(@Named("ai") retrofit: Retrofit): AiApiService {
        return retrofit.create(AiApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideHealthConnectManager(
        @ApplicationContext context: Context
    ): HealthConnectManager {
        return HealthConnectManager(context)
    }
}
