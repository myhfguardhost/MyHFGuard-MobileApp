package com.vitalink.app.ui.screens

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalink.app.BuildConfig
import com.vitalink.app.R
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.util.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val DEFAULT_PATIENT_LOGIN_DOMAIN = "patients.myhfguard.local"
private const val LEGACY_PATIENT_LOGIN_DOMAIN = "myhfguard.local"

data class LoginState(
    val loading: Boolean = false,
    val error: String? = null,
    val ok: Boolean = false
)

private data class AuthAttempt(
    val success: Boolean,
    val responseCode: Int,
    val email: String,
    val json: JSONObject,
    val rawBody: String
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    private val authClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _passwordHelp = MutableStateFlow<String?>(null)
    val passwordHelp = _passwordHelp.asStateFlow()
    private val _requestingHelp = MutableStateFlow(false)
    val requestingHelp = _requestingHelp.asStateFlow()
    fun requestPasswordHelp(userId: String) = viewModelScope.launch {
        if (_requestingHelp.value) return@launch
        if (userId.isBlank()) {
            _passwordHelp.value = AppLanguage.text("Enter your User ID first.", "Masukkan ID Pengguna dahulu.", "请先输入用户 ID。", "முதலில் பயனர் ID-ஐ உள்ளிடுங்கள்.")
            return@launch
        }
        _requestingHelp.value = true
        try {
            val success = withContext(Dispatchers.IO) {
                val body = JSONObject().put("userId", userId.trim()).toString().toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/patient/password-help").post(body).build()
                authClient.newCall(request).execute().use { it.isSuccessful }
            }
            _passwordHelp.value = if (success) AppLanguage.text("If your User ID is registered, your administrator will receive your password-help request.", "Jika ID anda berdaftar, pentadbir akan menerima permintaan bantuan kata laluan anda.", "如果用户 ID 已注册，管理员将收到您的密码协助请求。", "உங்கள் ID பதிவு செய்யப்பட்டிருந்தால், நிர்வாகி கடவுச்சொல் உதவிக் கோரிக்கையைப் பெறுவார்.")
                else AppLanguage.text("Unable to send the request. Please try later or contact your administrator.", "Permintaan gagal dihantar. Cuba lagi atau hubungi pentadbir.", "无法发送请求，请稍后重试或联系管理员。", "கோரிக்கையை அனுப்ப முடியவில்லை. பின்னர் முயலவும் அல்லது நிர்வாகியை தொடர்புகொள்ளவும்.")
        } catch (_: Exception) {
            _passwordHelp.value = AppLanguage.text("Check your connection and try again.", "Semak sambungan dan cuba lagi.", "请检查网络后重试。", "இணைப்பைச் சரிபார்த்து மீண்டும் முயலவும்.")
        } finally { _requestingHelp.value = false }
    }

    fun login(userIdInput: String, password: String) {
        val normalizedUserId = normalizeUserId(userIdInput)

        if (!USER_ID_PATTERN.matches(normalizedUserId)) {
            _state.value = LoginState(
                error = "Enter a valid User ID, for example P002."
            )
            return
        }

        if (password.isBlank()) {
            _state.value = LoginState(error = "Please enter your password.")
            return
        }

        viewModelScope.launch {
            _state.value = LoginState(loading = true)

            try {
                val configuredDomain = BuildConfig.PATIENT_LOGIN_DOMAIN
                    .trim()
                    .lowercase(Locale.ROOT)
                    .ifBlank { DEFAULT_PATIENT_LOGIN_DOMAIN }

                // Current admin-created accounts use patients.myhfguard.local.
                // The second domain supports older accounts created as userId@myhfguard.local.
                val candidateDomains = linkedSetOf(
                    configuredDomain,
                    DEFAULT_PATIENT_LOGIN_DOMAIN,
                    LEGACY_PATIENT_LOGIN_DOMAIN
                )

                var lastAttempt: AuthAttempt? = null

                for (domain in candidateDomains) {
                    val loginEmail = "$normalizedUserId@$domain"
                    val attempt = authenticate(loginEmail, password)
                    lastAttempt = attempt

                    if (attempt.success) {
                        saveSuccessfulSession(
                            attempt = attempt,
                            assignedUserId = normalizedUserId
                        )
                        return@launch
                    }

                    // These errors are not caused by choosing the wrong hidden email domain,
                    // so there is no benefit in trying the legacy domain again.
                    if (attempt.responseCode == 429 || attempt.responseCode >= 500) {
                        break
                    }
                }

                _state.value = LoginState(
                    error = friendlyAuthError(lastAttempt)
                )
            } catch (error: UnknownHostException) {
                _state.value = LoginState(
                    error = UserFacingError.from(error, Action.SIGN_IN)
                )
            } catch (error: SocketTimeoutException) {
                _state.value = LoginState(
                    error = UserFacingError.from(error, Action.SIGN_IN)
                )
            } catch (error: IOException) {
                _state.value = LoginState(
                    error = UserFacingError.from(error, Action.SIGN_IN)
                )
            } catch (error: Exception) {
                _state.value = LoginState(
                    error = UserFacingError.from(error, Action.SIGN_IN)
                )
            }
        }
    }

    private suspend fun authenticate(email: String, password: String): AuthAttempt =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)

            val url = BuildConfig.SUPABASE_URL
                .trim()
                .trimEnd('/') + "/auth/v1/token?grant_type=password"

            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                // Supabase's gateway accepts apikey, while Authorization keeps the
                // request compatible with projects that enforce both headers.
                .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build()

            authClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(raw.ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }

                AuthAttempt(
                    success = response.isSuccessful &&
                        json.optString("access_token").isNotBlank(),
                    responseCode = response.code,
                    email = email,
                    json = json,
                    rawBody = raw
                )
            }
        }

    private suspend fun saveSuccessfulSession(
        attempt: AuthAttempt,
        assignedUserId: String
    ) {
        val userObject = attempt.json.optJSONObject("user")
        val authUserId = userObject?.optString("id").orEmpty()

        if (authUserId.isBlank()) {
            _state.value = LoginState(
                error = "Login succeeded, but Supabase did not return the patient account ID."
            )
            return
        }

        val appMetadata = userObject?.optJSONObject("app_metadata")
        val role = appMetadata
            ?.optString("role", "patient")
            ?.ifBlank { "patient" }
            ?: "patient"

        if (role.equals("admin", ignoreCase = true)) {
            _state.value = LoginState(
                error = "This is an admin account. Please use the admin website."
            )
            return
        }

        if (!role.equals("patient", ignoreCase = true)) {
            _state.value = LoginState(
                error = "This account is not authorised as a patient. Please contact the administrator."
            )
            return
        }

        val accessToken = attempt.json.optString("access_token")
        val refreshToken = attempt.json.optString("refresh_token")

        session.saveSession(
            token = accessToken,
            refresh = refreshToken,
            patientId = authUserId,
            email = attempt.email,
            role = "patient"
        )
        session.saveAssignedUserId(assignedUserId)

        _state.value = LoginState(ok = true)
    }

    private fun friendlyAuthError(attempt: AuthAttempt?): String {
        if (attempt == null) {
            return "Login failed. Please check your User ID, password and internet connection."
        }

        val json = attempt.json
        val code = json.optString("error_code")
            .ifBlank { json.optString("code") }
            .lowercase(Locale.ROOT)
        val serverMessage = json.optString("error_description")
            .ifBlank { json.optString("msg") }
            .ifBlank { json.optString("message") }
        val normalizedMessage = serverMessage.lowercase(Locale.ROOT)

        return when {
            attempt.responseCode == 429 ->
                UserFacingError.http(attempt.responseCode, Action.SIGN_IN)

            attempt.responseCode >= 500 ->
                UserFacingError.http(attempt.responseCode, Action.SIGN_IN)

            code.contains("email_not_confirmed") ||
                normalizedMessage.contains("email not confirmed") ->
                "This patient account has not been activated. Please contact the administrator."

            code.contains("invalid_credentials") ||
                normalizedMessage.contains("invalid login credentials") ||
                attempt.responseCode == 400 ||
                attempt.responseCode == 401 ->
                "Incorrect User ID or password."

            serverMessage.isNotBlank() -> UserFacingError.http(attempt.responseCode, Action.SIGN_IN)

            attempt.rawBody.isNotBlank() ->
                UserFacingError.http(attempt.responseCode, Action.SIGN_IN)

            else ->
                "Login failed. Please check your User ID, password and internet connection."
        }
    }

    private fun normalizeUserId(value: String): String {
        return value
            .trim()
            .substringBefore('@')
            .lowercase(Locale.ROOT)
    }

    companion object {
        private val USER_ID_PATTERN =
            Regex("^[a-z0-9][a-z0-9._-]{2,29}$")
        private val JSON_MEDIA_TYPE =
            "application/json; charset=utf-8".toMediaType()
    }
}

@Composable
fun LoginScreen(
    onSuccess: () -> Unit,
    vm: LoginViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val passwordHelp by vm.passwordHelp.collectAsState()
    val requestingHelp by vm.requestingHelp.collectAsState()
    var userId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.ok) {
        if (state.ok) onSuccess()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(36.dp))

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = AppLanguage.text("MyHFGuard Logo", "Logo MyHFGuard"),
                    modifier = Modifier
                        .size(82.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                "MyHFGuard",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Text(
                AppLanguage.text(
                    "Heart Failure Self-Care",
                    "Penjagaan Kendiri Kegagalan Jantung",
                    "心力衰竭自我护理",
                    "இதய செயலிழப்பு சுய பராமரிப்பு"
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f)
            )

            Spacer(Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        AppLanguage.text("Welcome Back", "Selamat Kembali", "欢迎回来", "மீண்டும் வரவேற்கிறோம்"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        AppLanguage.text(
                            "Enter the User ID and password assigned by your administrator.",
                            "Masukkan ID Pengguna dan kata laluan yang diberikan oleh pentadbir.",
                            "请输入管理员分配的用户 ID 和密码。",
                            "நிர்வாகி வழங்கிய பயனர் ID மற்றும் கடவுச்சொல்லை உள்ளிடவும்."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(20.dp))

                    OutlinedTextField(
                        value = userId,
                        onValueChange = { userId = it.trimStart() },
                        label = {
                            Text(AppLanguage.text("User ID", "ID Pengguna", "用户 ID", "பயனர் ID"))
                        },
                        placeholder = { Text("P002") },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = {
                            Text(AppLanguage.text("Password", "Kata Laluan", "密码", "கடவுச்சொல்"))
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = null
                                )
                            }
                        },
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (userId.isNotBlank() && password.isNotBlank()) {
                                    vm.login(userId, password)
                                }
                            }
                        )
                    )

                    // Keep one clear line of space between the password field and Sign In.
                    Spacer(Modifier.height(16.dp))

                    state.error?.let { message ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = localizedLoginError(message),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Button(
                        onClick = { vm.login(userId, password) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !state.loading &&
                            userId.isNotBlank() &&
                            password.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(
                                AppLanguage.text("Sign In", "Log Masuk", "登录", "உள்நுழைக"),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    TextButton(onClick = { vm.requestPasswordHelp(userId) }, enabled = !requestingHelp, modifier = Modifier.fillMaxWidth()) {
                        Text(AppLanguage.text("Forgot password? Ask admin for help", "Lupa kata laluan? Minta bantuan pentadbir", "忘记密码？请求管理员协助", "கடவுச்சொல் மறந்துவிட்டதா? நிர்வாகியின் உதவியைப் பெறுங்கள்"))
                    }
                    passwordHelp?.let { Text(it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(12.dp))

                    Text(
                        AppLanguage.text(
                            "Only User IDs linked to a patient account can sign in.",
                            "Nota: hanya ID Pengguna yang telah dipautkan kepada akaun boleh log masuk.",
                            "只有已关联患者账户的用户 ID 才能登录。",
                            "நோயாளர் கணக்குடன் இணைக்கப்பட்ட பயனர் ID மட்டுமே உள்நுழைய முடியும்."
                        ),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(36.dp))
        }

        LanguageSegmentedSwitch(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        )
    }
}

private fun localizedLoginError(message: String): String = when {
    AppLanguage.useMalay -> translateLoginErrorToMalay(message)
    AppLanguage.useMandarin -> when {
        message.startsWith("Enter a valid User ID") -> "请输入有效的用户 ID，例如 P002。"
        message == "Please enter your password." -> "请输入密码。"
        message.startsWith("Cannot connect to Supabase") -> "无法连接服务器。请检查网络后重试。"
        message.startsWith("The connection timed out") -> "连接超时。请检查网络后重试。"
        message.startsWith("Incorrect User ID or password") -> "用户 ID 或密码不正确。"
        message.startsWith("Too many login attempts") -> "登录尝试次数过多，请稍后再试。"
        message.startsWith("This patient account has not been activated") -> "此患者账户尚未启用，请联系管理员。"
        message.startsWith("This account is not authorised") -> "此账户无患者登录权限，请联系管理员。"
        else -> message
    }
    AppLanguage.useTamil -> when {
        message.startsWith("Enter a valid User ID") -> "P002 போன்ற சரியான பயனர் ID-ஐ உள்ளிடவும்."
        message == "Please enter your password." -> "கடவுச்சொல்லை உள்ளிடவும்."
        message.startsWith("Cannot connect to Supabase") -> "சேவையகத்துடன் இணைக்க முடியவில்லை. இணையத்தைச் சரிபார்த்து மீண்டும் முயற்சிக்கவும்."
        message.startsWith("The connection timed out") -> "இணைப்பு நேரம் முடிந்தது. இணையத்தைச் சரிபார்த்து மீண்டும் முயற்சிக்கவும்."
        message.startsWith("Incorrect User ID or password") -> "பயனர் ID அல்லது கடவுச்சொல் தவறானது."
        message.startsWith("Too many login attempts") -> "அதிகமான உள்நுழைவு முயற்சிகள். சிறிது நேரம் கழித்து முயற்சிக்கவும்."
        message.startsWith("This patient account has not been activated") -> "இந்த நோயாளர் கணக்கு செயல்படுத்தப்படவில்லை. நிர்வாகியை தொடர்புகொள்ளவும்."
        message.startsWith("This account is not authorised") -> "இந்த கணக்கிற்கு நோயாளர் அணுகல் அனுமதி இல்லை. நிர்வாகியை தொடர்புகொள்ளவும்."
        else -> message
    }
    else -> message
}

private fun translateLoginErrorToMalay(message: String): String {
    return when {
        message.startsWith("Enter a valid User ID") ->
            "Masukkan ID Pengguna yang sah, contohnya P002."

        message == "Please enter your password." ->
            "Sila masukkan kata laluan."

        message.startsWith("Cannot connect to Supabase") ->
            "Tidak dapat menyambung ke Supabase. Semak Wi-Fi atau data mudah alih dan cuba lagi."

        message.startsWith("The connection timed out") ->
            "Sambungan tamat masa. Semak sambungan internet dan cuba lagi."

        message.startsWith("Incorrect User ID or password") ->
            "ID Pengguna atau kata laluan tidak betul."

        message.startsWith("Too many login attempts") ->
            "Terlalu banyak percubaan log masuk. Tunggu sebentar dan cuba lagi."

        message.startsWith("This patient account has not been activated") ->
            "Akaun pesakit ini belum diaktifkan. Sila hubungi pentadbir."

        message.startsWith("This account is not authorised") ->
            "Akaun ini tidak dibenarkan sebagai pesakit. Sila hubungi pentadbir."

        else -> message
    }
}
