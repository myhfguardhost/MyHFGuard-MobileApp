package com.vitalink.app.reminders

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import com.vitalink.app.BuildConfig
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.api.dataStore
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Keeps reminder workers authenticated even when the app has been closed for several hours. */
object ReminderApiClient {
    data class AuthenticatedApi(val patientId: String, val api: ApiService)

    suspend fun open(context: Context): AuthenticatedApi? {
        val preferences = context.dataStore.data.first()
        val patientId = preferences[SessionManager.KEY_PATIENT].orEmpty()
        var accessToken = preferences[SessionManager.KEY_TOKEN].orEmpty()
        val refreshToken = preferences[SessionManager.KEY_REFRESH].orEmpty()
        if (patientId.isBlank() || accessToken.isBlank()) return null

        if (tokenNeedsRefresh(accessToken)) {
            if (refreshToken.isBlank()) return null
            val refreshed = refreshAccessToken(refreshToken) ?: return null
            accessToken = refreshed.first
            context.dataStore.edit { store ->
                store[SessionManager.KEY_TOKEN] = refreshed.first
                store[SessionManager.KEY_REFRESH] = refreshed.second ?: refreshToken
            }
        }

        return AuthenticatedApi(patientId, createApi(accessToken))
    }

    private fun tokenNeedsRefresh(token: String): Boolean {
        val payload = token.split('.').getOrNull(1) ?: return true
        return runCatching {
            val decoded = String(
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
            val expirySeconds = JSONObject(decoded).optLong("exp", 0L)
            expirySeconds == 0L || expirySeconds <= (System.currentTimeMillis() / 1000L) + 300L
        }.getOrDefault(true)
    }

    private fun refreshAccessToken(refreshToken: String): Pair<String, String?>? {
        return try {
            val body = JSONObject()
                .put("refresh_token", refreshToken)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/token?grant_type=refresh_token")
                .post(body)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json")
                .build()

            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
                access to json.optString("refresh_token").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createApi(accessToken: String): ApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Type", "application/json")
                        .build()
                )
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
