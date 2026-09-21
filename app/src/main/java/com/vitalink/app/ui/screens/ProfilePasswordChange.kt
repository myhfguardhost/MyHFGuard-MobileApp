package com.vitalink.app.ui.screens

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalink.app.BuildConfig
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.util.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class PasswordChangeState(
    val saving: Boolean = false,
    val message: String? = null,
    val successVersion: Int = 0
)

private data class PasswordAuthResult(
    val successful: Boolean,
    val responseCode: Int,
    val accessToken: String = "",
    val refreshToken: String = "",
    val errorMessage: String = ""
)

@HiltViewModel
class ProfilePasswordViewModel @Inject constructor(
    private val session: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(PasswordChangeState())
    val state = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun changePassword(
        currentPassword: String,
        newPassword: String,
        confirmPassword: String
    ) {
        val validationMessage = when {
            currentPassword.isBlank() -> AppLanguage.text(
                "Please enter your current password.",
                "Sila masukkan kata laluan semasa.",
                "请输入当前密码。",
                "உங்கள் தற்போதைய கடவுச்சொல்லை உள்ளிடுங்கள்."
            )
            newPassword.length < 8 -> AppLanguage.text(
                "The new password must contain at least 8 characters.",
                "Kata laluan baharu mesti mengandungi sekurang-kurangnya 8 aksara.",
                "新密码必须至少包含8个字符。",
                "புதிய கடவுச்சொல்லில் குறைந்தது 8 எழுத்துகள் இருக்க வேண்டும்."
            )
            newPassword == currentPassword -> AppLanguage.text(
                "The new password must be different from your current password.",
                "Kata laluan baharu mestilah berbeza daripada kata laluan semasa.",
                "新密码必须与当前密码不同。",
                "புதிய கடவுச்சொல் தற்போதைய கடவுச்சொல்லிலிருந்து வேறுபட்டிருக்க வேண்டும்."
            )
            confirmPassword.isBlank() -> AppLanguage.text(
                "Please enter the new password again.",
                "Sila masukkan kata laluan baharu sekali lagi.",
                "请再次输入新密码。",
                "புதிய கடவுச்சொல்லை மீண்டும் உள்ளிடுங்கள்."
            )
            newPassword != confirmPassword -> AppLanguage.text(
                "The new passwords do not match.",
                "Kata laluan baharu tidak sepadan.",
                "两次输入的新密码不一致。",
                "புதிய கடவுச்சொற்கள் பொருந்தவில்லை."
            )
            else -> null
        }

        if (validationMessage != null) {
            _state.value = _state.value.copy(saving = false, message = validationMessage)
            return
        }

        viewModelScope.launch {
            val previousSuccessVersion = _state.value.successVersion
            _state.value = _state.value.copy(saving = true, message = null)
            try {
                val email = session.userEmail.first().orEmpty().trim()
                if (email.isBlank()) {
                    _state.value = _state.value.copy(
                        saving = false,
                        message = AppLanguage.text(
                            "Your login session is incomplete. Please sign out and log in again.",
                            "Sesi log masuk anda tidak lengkap. Sila log keluar dan log masuk semula.",
                            "您的登录会话不完整。请退出后重新登录。",
                            "உங்கள் உள்நுழைவு அமர்வு முழுமையற்றது. வெளியேறி மீண்டும் உள்நுழையுங்கள்."
                        )
                    )
                    return@launch
                }

                // Re-authenticate first so the current password must be correct.
                val verifiedSession = authenticate(email, currentPassword)
                if (!verifiedSession.successful) {
                    _state.value = _state.value.copy(
                        saving = false,
                        message = when {
                            verifiedSession.responseCode == 400 || verifiedSession.responseCode == 401 ->
                                AppLanguage.text(
                                    "The current password is incorrect.",
                                    "Kata laluan semasa tidak betul.",
                                    "当前密码不正确。",
                                    "தற்போதைய கடவுச்சொல் தவறானது."
                                )
                            verifiedSession.responseCode == 429 ->
                                AppLanguage.text(
                                    "Too many attempts. Please wait a moment and try again.",
                                    "Terlalu banyak percubaan. Sila tunggu sebentar dan cuba lagi.",
                                    "尝试次数过多。请稍后再试。",
                                    "அதிகமான முயற்சிகள். சிறிது நேரம் காத்திருந்து மீண்டும் முயற்சிக்கவும்."
                                )
                            else -> UserFacingError.http(verifiedSession.responseCode, Action.CHANGE_PASSWORD)
                        }
                    )
                    return@launch
                }

                val updateError = updatePassword(
                    accessToken = verifiedSession.accessToken,
                    newPassword = newPassword
                )
                if (updateError != null) {
                    _state.value = _state.value.copy(saving = false, message = updateError)
                    return@launch
                }

                // Refresh the stored tokens using the new password. If this extra refresh
                // is temporarily unavailable, keep the freshly verified session tokens.
                val refreshedSession = authenticate(email, newPassword)
                val tokenToSave = refreshedSession
                    .takeIf { it.successful && it.accessToken.isNotBlank() }
                    ?: verifiedSession
                session.updateTokens(tokenToSave.accessToken, tokenToSave.refreshToken)

                _state.value = PasswordChangeState(
                    message = AppLanguage.text(
                        "Password changed successfully.",
                        "Kata laluan berjaya ditukar.",
                        "密码已成功更改。",
                        "கடவுச்சொல் வெற்றிகரமாக மாற்றப்பட்டது."
                    ),
                    successVersion = previousSuccessVersion + 1
                )
            } catch (_: UnknownHostException) {
                _state.value = _state.value.copy(
                    saving = false,
                    message = UserFacingError.connection(Action.CHANGE_PASSWORD)
                )
            } catch (_: SocketTimeoutException) {
                _state.value = _state.value.copy(
                    saving = false,
                    message = AppLanguage.text(
                        "The request timed out. Please try again.",
                        "Permintaan tamat masa. Sila cuba lagi.",
                        "请求超时。请重试。",
                        "கோரிக்கை நேரம் முடிந்தது. மீண்டும் முயற்சிக்கவும்."
                    )
                )
            } catch (e: IOException) {
                _state.value = _state.value.copy(
                    saving = false,
                    message = UserFacingError.from(e, Action.CHANGE_PASSWORD)
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    message = UserFacingError.from(e, Action.CHANGE_PASSWORD)
                )
            }
        }
    }

    private suspend fun authenticate(email: String, password: String): PasswordAuthResult =
        withContext(Dispatchers.IO) {
            val requestBody = JSONObject().apply {
                put("email", email)
                put("password", password)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/auth/v1/token?grant_type=password")
                .post(requestBody)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                PasswordAuthResult(
                    successful = response.isSuccessful && json.optString("access_token").isNotBlank(),
                    responseCode = response.code,
                    accessToken = json.optString("access_token"),
                    refreshToken = json.optString("refresh_token"),
                    errorMessage = extractError(json, raw)
                )
            }
        }

    private suspend fun updatePassword(accessToken: String, newPassword: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("password", newPassword)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/auth/v1/user")
                .put(body)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    null
                } else {
                    val json = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                    val serverError = extractError(json, raw)
                    UserFacingError.http(response.code, Action.CHANGE_PASSWORD)
                }
            }
        }

    private fun extractError(json: JSONObject, raw: String): String {
        return json.optString("error_description")
            .ifBlank { json.optString("msg") }
            .ifBlank { json.optString("message") }
            .ifBlank { raw }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Composable
fun ProfilePasswordChangeSection(
    vm: ProfilePasswordViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showCurrent by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var handledSuccessVersion by remember { mutableStateOf(0) }

    LaunchedEffect(state.successVersion) {
        if (state.successVersion > handledSuccessVersion) {
            currentPassword = ""
            newPassword = ""
            confirmPassword = ""
            handledSuccessVersion = state.successVersion
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF00ACC1))
            Text(
                AppLanguage.text(
                    "Change Password",
                    "Tukar Kata Laluan",
                    "更改密码",
                    "கடவுச்சொல்லை மாற்றவும்"
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                AppLanguage.text(
                    "Enter your current password before choosing a new password.",
                    "Masukkan kata laluan semasa sebelum memilih kata laluan baharu.",
                    "选择新密码前，请先输入当前密码。",
                    "புதிய கடவுச்சொல்லைத் தேர்ந்தெடுப்பதற்கு முன் தற்போதைய கடவுச்சொல்லை உள்ளிடுங்கள்."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PasswordField(
                value = currentPassword,
                onValueChange = {
                    currentPassword = it
                    vm.clearMessage()
                },
                label = AppLanguage.text(
                    "Current Password", "Kata Laluan Semasa", "当前密码", "தற்போதைய கடவுச்சொல்"
                ),
                visible = showCurrent,
                onToggleVisibility = { showCurrent = !showCurrent },
                imeAction = ImeAction.Next,
                enabled = !state.saving
            )
            PasswordField(
                value = newPassword,
                onValueChange = {
                    newPassword = it
                    vm.clearMessage()
                },
                label = AppLanguage.text(
                    "New Password", "Kata Laluan Baharu", "新密码", "புதிய கடவுச்சொல்"
                ),
                placeholder = AppLanguage.text(
                    "At least 8 characters", "Sekurang-kurangnya 8 aksara", "至少8个字符", "குறைந்தது 8 எழுத்துகள்"
                ),
                visible = showNew,
                onToggleVisibility = { showNew = !showNew },
                imeAction = ImeAction.Next,
                enabled = !state.saving
            )
            PasswordField(
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    vm.clearMessage()
                },
                label = AppLanguage.text(
                    "Confirm New Password", "Sahkan Kata Laluan Baharu", "确认新密码", "புதிய கடவுச்சொல்லை உறுதிப்படுத்தவும்"
                ),
                visible = showConfirm,
                onToggleVisibility = { showConfirm = !showConfirm },
                imeAction = ImeAction.Done,
                enabled = !state.saving
            )

            state.message?.takeIf { it.isNotBlank() }?.let { message ->
                val success = state.successVersion > 0 &&
                    (message.contains("success", ignoreCase = true) ||
                        message.contains("berjaya", ignoreCase = true) ||
                        message.contains("成功") ||
                        message.contains("வெற்றிகரமாக"))
                Text(
                    text = message,
                    color = if (success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(2.dp))
            Button(
                onClick = { vm.changePassword(currentPassword, newPassword, confirmPassword) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !state.saving,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1)),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(22.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text(AppLanguage.text(
                        "Change Password", "Tukar Kata Laluan", "更改密码", "கடவுச்சொல்லை மாற்றவும்"
                    ))
                }
            }
        }
    }

    BlockingLoadingScreen(
        visible = state.saving,
        title = AppLanguage.text(
            "Updating password...",
            "Mengemas kini kata laluan...",
            "正在更新密码……",
            "கடவுச்சொல் புதுப்பிக்கப்படுகிறது..."
        )
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    imeAction: ImeAction,
    enabled: Boolean,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { text -> { Text(text) } },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction
        ),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility, enabled = enabled) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = AppLanguage.text(
                        if (visible) "Hide password" else "Show password",
                        if (visible) "Sembunyikan kata laluan" else "Tunjuk kata laluan",
                        if (visible) "隐藏密码" else "显示密码",
                        if (visible) "கடவுச்சொல்லை மறைக்கவும்" else "கடவுச்சொல்லைக் காட்டவும்"
                    )
                )
            }
        },
        shape = RoundedCornerShape(12.dp)
    )
}
