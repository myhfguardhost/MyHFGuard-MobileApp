package com.vitalink.app.data.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "myhfguard_prefs")

data class StoredSession(
    val accessToken: String?,
    val refreshToken: String?,
    val patientId: String?
) {
    val hasAccessToken: Boolean
        get() = !accessToken.isNullOrBlank()

    val isUsable: Boolean
        get() = hasAccessToken && !patientId.isNullOrBlank()
}

@Singleton
class SessionManager @Inject constructor(@ApplicationContext private val ctx: Context) {

    companion object {
        val KEY_TOKEN    = stringPreferencesKey("access_token")
        val KEY_REFRESH  = stringPreferencesKey("refresh_token")
        val KEY_PATIENT  = stringPreferencesKey("patient_id")
        val KEY_EMAIL    = stringPreferencesKey("email")
        val KEY_NAME     = stringPreferencesKey("first_name")
        val KEY_ROLE     = stringPreferencesKey("role")
        val KEY_ASSIGNED_USER_ID = stringPreferencesKey("assigned_user_id")
    }

    val accessToken:  Flow<String?> = ctx.dataStore.data.map { it[KEY_TOKEN] }
    val refreshToken: Flow<String?> = ctx.dataStore.data.map { it[KEY_REFRESH] }
    val patientId:    Flow<String?> = ctx.dataStore.data.map { it[KEY_PATIENT] }
    val userEmail:    Flow<String?> = ctx.dataStore.data.map { it[KEY_EMAIL] }
    val firstName:    Flow<String?> = ctx.dataStore.data.map { it[KEY_NAME] }
    val userRole:     Flow<String?> = ctx.dataStore.data.map { it[KEY_ROLE] }
    val assignedUserId: Flow<String?> = ctx.dataStore.data.map { it[KEY_ASSIGNED_USER_ID] }
    val sessionSnapshot: Flow<StoredSession> = ctx.dataStore.data.map {
        StoredSession(
            accessToken = it[KEY_TOKEN],
            refreshToken = it[KEY_REFRESH],
            patientId = it[KEY_PATIENT]
        )
    }
    val isLoggedIn: Flow<Boolean> = sessionSnapshot.map { it.isUsable }

    suspend fun saveSession(token: String, refresh: String, patientId: String, email: String, role: String) {
        ctx.dataStore.edit {
            it[KEY_TOKEN]   = token
            it[KEY_REFRESH] = refresh
            it[KEY_PATIENT] = patientId
            it[KEY_EMAIL]   = email
            it[KEY_ROLE]    = role
        }
    }

    suspend fun saveFirstName(name: String) { ctx.dataStore.edit { it[KEY_NAME] = name } }

    suspend fun saveAssignedUserId(userId: String) {
        ctx.dataStore.edit { it[KEY_ASSIGNED_USER_ID] = userId }
    }

    suspend fun updateTokens(token: String, refresh: String) {
        ctx.dataStore.edit { it[KEY_TOKEN] = token; it[KEY_REFRESH] = refresh }
    }

    suspend fun clear() { ctx.dataStore.edit { it.clear() } }
}
