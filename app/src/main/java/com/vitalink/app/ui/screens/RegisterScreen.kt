package com.vitalink.app.ui.screens

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalink.app.BuildConfig
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
import javax.inject.Inject

data class RegisterState(val loading: Boolean = false, val error: String? = null, val okMessage: String? = null)

@HiltViewModel
class RegisterViewModel @Inject constructor() : ViewModel() {
    private val _s = MutableStateFlow(RegisterState())
    val state = _s.asStateFlow()

    fun register(firstName: String, lastName: String, email: String, password: String) {
        if (firstName.isBlank() || email.isBlank() || password.length < 6) {
            _s.value = RegisterState(error = "Please enter name, email and password with at least 6 characters.")
            return
        }
        viewModelScope.launch {
            _s.value = RegisterState(loading = true)
            try {
                val body = JSONObject().apply {
                    put("email", email.trim())
                    put("password", password)
                    // Send users back to the MyHFGuard app login page after they confirm
                    // their signup email in Supabase.
                    put("redirect_to", BuildConfig.REGISTER_CONFIRM_REDIRECT_URL)
                    put("data", JSONObject().apply {
                        put("first_name", firstName.trim())
                        put("last_name", lastName.trim())
                    })
                }.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${BuildConfig.SUPABASE_URL}/auth/v1/signup")
                    .post(body)
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Content-Type", "application/json")
                    .build()
                val resp = withContext(Dispatchers.IO) { OkHttpClient().newCall(req).execute() }
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    _s.value = RegisterState(okMessage = "Account created. Please check your email and click the confirmation link, then login in the app.")
                } else {
                    _s.value = RegisterState(error = UserFacingError.http(resp.code, Action.REGISTER))
                }
            } catch (e: Exception) {
                _s.value = RegisterState(error = UserFacingError.from(e, Action.REGISTER))
            }
        }
    }
}

@Composable
fun RegisterScreen(
    onSuccess: () -> Unit,
    onLogin: () -> Unit,
    vm: RegisterViewModel = hiltViewModel()
) {
    val s by vm.state.collectAsState()
    val ms = AppLanguage.useMalay
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(8.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(AppLanguage.text("Register MyHFGuard Account", "Daftar Akaun MyHFGuard"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(AppLanguage.text("Create an account directly in the mobile app. After registration, sign in with your email and password.", "Cipta akaun terus dalam app mobile. Selepas daftar, log masuk menggunakan e-mel dan kata laluan anda."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    OutlinedTextField(first, { first = it }, label = { Text(AppLanguage.text("First name", "Nama pertama")) }, leadingIcon = { Icon(Icons.Default.Person, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(last, { last = it }, label = { Text(AppLanguage.text("Last name", "Nama keluarga")) }, leadingIcon = { Icon(Icons.Default.Person, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it }, label = { Text(AppLanguage.text("Email", "E-mel")) }, leadingIcon = { Icon(Icons.Default.Email, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(AppLanguage.text("Password", "Kata laluan")) },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) {
                                        AppLanguage.text("Hide password", "Sembunyikan kata laluan")
                                    } else {
                                        AppLanguage.text("Show password", "Tunjuk kata laluan")
                                    }
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    s.okMessage?.let { Text(AppLanguage.text("Account created. Please check your email and open the verification link, then sign in to the app.", "Akaun dicipta. Sila semak e-mel dan tekan pautan pengesahan, kemudian log masuk dalam app."), color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                    Button(onClick = { vm.register(first, last, email, password) }, enabled = !s.loading, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                        if (s.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text(AppLanguage.text("Register", "Daftar"))
                    }
                    OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.ArrowBack, null)
                        Spacer(Modifier.width(8.dp))
                        Text(AppLanguage.text("Back to Login", "Kembali ke Log Masuk"))
                    }
                }
            }
        }
    }
}
