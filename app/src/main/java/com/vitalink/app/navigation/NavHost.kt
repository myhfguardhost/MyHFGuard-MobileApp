package com.vitalink.app.navigation

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.model.isCompletedAndLocked
import com.vitalink.app.ui.screens.AIChatScreen
import com.vitalink.app.ui.screens.AiChatViewModel
import com.vitalink.app.ui.screens.FloatingAiNurse
import com.vitalink.app.ui.screens.DashboardScreen
import com.vitalink.app.ui.screens.EducationScreen
import com.vitalink.app.ui.screens.ExerciseScreen
import com.vitalink.app.ui.screens.HelpScreen
import com.vitalink.app.ui.screens.LoginScreen
import com.vitalink.app.ui.screens.MedicationScreen
import com.vitalink.app.ui.screens.NotificationHistoryScreen
import com.vitalink.app.ui.screens.ProfileScreen
import com.vitalink.app.ui.screens.RegisterScreen
import com.vitalink.app.ui.screens.SelfCheckFullScreen
import com.vitalink.app.ui.screens.SmartBandScreen
import com.vitalink.app.ui.screens.VitalsScreen
import com.vitalink.app.ui.screens.WaterSaltScreen
import com.vitalink.app.util.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class Route(val path: String) {
    object Login : Route("login")
    object Register : Route("register")
    object Dashboard : Route("dashboard")
    object Vitals : Route("vitals")
    object SelfCheck : Route("self_check") {
        fun focus(section: String) = "$path?focus=$section"
    }
    object Medication : Route("medication")
    object WaterSalt : Route("water_salt") {
        fun focus(section: String) = "$path?focus=$section"
    }
    object Exercise : Route("exercise")
    object Education : Route("education")
    object AIChat : Route("ai_chat")
    object Profile : Route("profile")
    object Help : Route("help")
    object SmartBand : Route("smart_band")
    object Notifications : Route("notifications")
}

data class ProfileGateState(
    val checked: Boolean = false,
    val complete: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val api: ApiService,
    private val session: SessionManager
) : ViewModel() {

    private val _sessionReady = MutableStateFlow(false)
    val sessionReady = _sessionReady.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn = _isLoggedIn.asStateFlow()

    private val _profileGate = MutableStateFlow(ProfileGateState())
    val profileGate = _profileGate.asStateFlow()
    private var patientId: String = ""
    private var profileGatePatientId: String? = null

    init {
        viewModelScope.launch {
            session.sessionSnapshot.collect { storedSession ->
                val storedPatientId = storedSession.patientId.orEmpty()
                patientId = storedPatientId
                _sessionReady.value = true
                _isLoggedIn.value = storedSession.isUsable

                if (!storedSession.isUsable) {
                    profileGatePatientId = null
                    _profileGate.value = ProfileGateState()

                    // An older build may have left only an access token without a
                    // patient ID. It cannot be used safely, so remove it instead of
                    // allowing the app to enter a broken half-logged-in state.
                    if (storedSession.hasAccessToken && storedPatientId.isBlank()) {
                        session.clear()
                    }
                } else if (profileGatePatientId != storedPatientId) {
                    profileGatePatientId = storedPatientId
                    refreshProfileGate()
                }
            }
        }
    }


    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        session.clear()
        profileGatePatientId = null
        _profileGate.value = ProfileGateState()
        onDone()
    }

    fun refreshProfileGate() = viewModelScope.launch {
        if (patientId.isBlank()) return@launch
        _profileGate.value = ProfileGateState(checked = false)
        try {
            val response = api.getProfile("eq.$patientId")
            if (!response.isSuccessful) {
                // A 401 means the session saved by an older app version is expired,
                // revoked, or belongs to a different Supabase project. Keeping that
                // session would trap the user on the HTTP 401 screen forever.
                if (response.code() == 401) {
                    session.clear()
                    profileGatePatientId = null
                    _profileGate.value = ProfileGateState()
                    return@launch
                }

                _profileGate.value = ProfileGateState(
                    checked = true,
                    complete = false,
                    error = UserFacingError.http(response.code(), Action.LOAD)
                )
                return@launch
            }
            val profile = response.body().orEmpty().firstOrNull()
            _profileGate.value = ProfileGateState(
                checked = true,
                complete = profile?.isCompletedAndLocked() == true,
                error = null
            )
        } catch (e: Exception) {
            _profileGate.value = ProfileGateState(
                checked = true,
                complete = false,
                error = UserFacingError.from(e, Action.LOAD)
            )
        }
    }
}

private data class BottomMenuItem(
    val route: String,
    val enLabel: String,
    val msLabel: String,
    val zhLabel: String,
    val taLabel: String,
    val icon: ImageVector
)

private val bottomMenuItems = listOf(
    BottomMenuItem(
        route = Route.Education.path,
        enLabel = "Learning",
        msLabel = "Belajar",
        zhLabel = "学习",
        taLabel = "கற்றல்",
        icon = Icons.AutoMirrored.Filled.MenuBook
    ),
    BottomMenuItem(
        route = Route.Medication.path,
        enLabel = "Appointment",
        msLabel = "Temu Janji",
        zhLabel = "预约",
        taLabel = "சந்திப்பு",
        icon = Icons.Default.Event
    ),
    // Home is intentionally the middle item. It always clears the nested
    // navigation history and opens Dashboard directly.
    BottomMenuItem(
        route = Route.Dashboard.path,
        enLabel = "Home",
        msLabel = "Utama",
        zhLabel = "首页",
        taLabel = "முகப்பு",
        icon = Icons.Default.Home
    ),
    BottomMenuItem(
        route = Route.AIChat.path,
        enLabel = "Chat",
        msLabel = "Chat",
        zhLabel = "聊天",
        taLabel = "அரட்டை",
        icon = Icons.AutoMirrored.Filled.Chat
    ),
    BottomMenuItem(
        route = Route.Profile.path,
        enLabel = "Profile",
        msLabel = "Profil",
        zhLabel = "资料",
        taLabel = "சுயவிவரம்",
        icon = Icons.Default.Person
    )
)

@Composable
fun AppNavHost(
    notificationRoute: String? = null,
    onNotificationRouteHandled: () -> Unit = {},
    showMiFitnessReminder: Boolean = false,
    onMiFitnessReminderHandled: () -> Unit = {}
) {
    val root: RootViewModel = hiltViewModel()
    val sessionReady by root.sessionReady.collectAsState()
    val loggedIn by root.isLoggedIn.collectAsState()
    val profileGate by root.profileGate.collectAsState()

    // DataStore loads asynchronously. Waiting here prevents the login page from
    // flashing for a moment before a saved session is restored.
    if (!sessionReady) {
        AppStartupLoadingScreen()
        return
    }

    val nav = rememberNavController()

    val startDestination = if (loggedIn) Route.Dashboard.path else Route.Login.path

    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: startDestination

    val showBottomMenu = loggedIn &&
            profileGate.checked &&
            profileGate.complete &&
            currentRoute != Route.Login.path &&
            currentRoute != Route.Register.path

    // The nurse is available after authentication even while the patient is
    // completing the first-login profile. The normal bottom menu still remains
    // locked until profile completion.
    val showAiNurse = loggedIn &&
            profileGate.checked &&
            profileGate.error == null &&
            currentRoute != Route.Login.path &&
            currentRoute != Route.Register.path &&
            currentRoute != Route.AIChat.path

    LaunchedEffect(loggedIn, currentRoute) {
        when {
            !loggedIn && currentRoute != Route.Login.path && currentRoute != Route.Register.path -> {
                nav.navigate(Route.Login.path) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }

            loggedIn && currentRoute == Route.Login.path -> {
                nav.navigate(Route.Dashboard.path) {
                    popUpTo(Route.Login.path) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(notificationRoute, loggedIn, profileGate.checked, profileGate.complete) {
        val route = notificationRoute ?: return@LaunchedEffect
        val allowedRoutes = setOf(
            Route.Dashboard.path,
            Route.SelfCheck.path,
            Route.Medication.path,
            Route.WaterSalt.path,
            Route.Exercise.path,
            Route.Education.path,
            Route.AIChat.path,
            Route.Notifications.path,
            Route.Vitals.path,
            Route.Profile.path,
            Route.Help.path,
            Route.SmartBand.path
        )
        if (loggedIn && profileGate.checked && profileGate.complete && route in allowedRoutes) {
            nav.navigate(Route.Dashboard.path) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            if (route != Route.Dashboard.path) {
                nav.navigate(route) {
                    launchSingleTop = true
                }
            }
            onNotificationRouteHandled()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomMenu) {
                AppBottomMenu(
                    currentRoute = currentRoute,
                    nav = nav
                )
            }
        }
    ) { scaffoldPadding ->
        Box(Modifier.fillMaxSize()) {
            NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    bottom = scaffoldPadding.calculateBottomPadding()
                )
                .consumeWindowInsets(
                    androidx.compose.foundation.layout.PaddingValues(bottom = scaffoldPadding.calculateBottomPadding())
                )
        ) {
            composable(Route.Login.path) {
                LoginScreen(
                    onSuccess = {
                        nav.navigate(Route.Dashboard.path) {
                            popUpTo(Route.Login.path) {
                                inclusive = true
                            }
                        }
                    }
                )
            }

            composable(Route.Register.path) {
                RegisterScreen(
                    onSuccess = {
                        nav.navigate(Route.Dashboard.path) {
                            popUpTo(Route.Register.path) {
                                inclusive = true
                            }
                        }
                    },
                    onLogin = {
                        nav.popBackStack()
                    }
                )
            }

            composable(Route.Dashboard.path) {
                when {
                    !profileGate.checked -> ProfileGateLoadingScreen()
                    profileGate.error != null -> ProfileGateErrorScreen(
                        error = profileGate.error.orEmpty(),
                        onRetry = { root.refreshProfileGate() }
                    )
                    !profileGate.complete -> ProfileScreen(
                        onBack = {},
                        forceCompletion = true,
                        onProfileCompleted = { root.refreshProfileGate() },
                        onLogout = {
                            root.logout {
                                nav.navigate(Route.Login.path) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                    )
                    else -> DashboardScreen(
                        onNavigate = { route -> nav.navigate(route) },
                        showMiFitnessReminder = showMiFitnessReminder,
                        onMiFitnessReminderHandled = onMiFitnessReminderHandled,
                        onLogout = {
                            nav.navigate(Route.Login.path) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }
            }

            composable(Route.Vitals.path) {
                VitalsScreen(
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(
                route = "${Route.SelfCheck.path}?focus={focus}",
                arguments = listOf(navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                SelfCheckFullScreen(
                    focus = entry.arguments?.getString("focus"),
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(Route.Medication.path) {
                MedicationScreen(
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(
                route = "${Route.WaterSalt.path}?focus={focus}",
                arguments = listOf(navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                WaterSaltScreen(
                    focus = entry.arguments?.getString("focus"),
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(Route.Exercise.path) {
                ExerciseScreen(
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(Route.Education.path) {
                EducationScreen(
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(Route.AIChat.path) {
                AIChatScreen(
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(Route.Notifications.path) {
                NotificationHistoryScreen(
                    onBack = { nav.popBackStack() },
                    onNavigate = { route -> nav.navigate(route) { launchSingleTop = true } }
                )
            }

            composable(Route.Profile.path) {
                ProfileScreen(
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(Route.Help.path) {
                HelpScreen(
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }

            composable(Route.SmartBand.path) {
                SmartBandScreen(
                    onBack = {
                        nav.popBackStack()
                    }
                )
            }
        }

            // Voice-first AI nurse: available on functional pages after login, except the AI chatbot page.
            // Draggable AI Nurse bubble: movable within the visible screen bounds
            // while keeping clear of the bottom navigation area.
            if (showAiNurse) {
                val nurseVm: AiChatViewModel = hiltViewModel()
                FloatingAiNurse(
                    vm = nurseVm,
                    modifier = Modifier.fillMaxSize(),
                    bottomReservedSpace = scaffoldPadding.calculateBottomPadding() + 12.dp
                )
            }
        }
    }
}

@Composable
private fun AppStartupLoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ProfileGateLoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(AppLanguage.text(
                "Checking your profile…", "Menyemak profil anda…",
                "正在检查您的个人资料…", "உங்கள் சுயவிவரம் சரிபார்க்கப்படுகிறது…"
            ))
        }
    }
}

@Composable
private fun ProfileGateErrorScreen(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                AppLanguage.text(
                    "Unable to check your profile. Please check your connection and try again.",
                    "Tidak dapat menyemak profil anda. Sila semak sambungan dan cuba lagi.",
                    "无法检查您的个人资料。请检查网络连接后重试。",
                    "உங்கள் சுயவிவரத்தை சரிபார்க்க முடியவில்லை. இணைய இணைப்பை சரிபார்த்து மீண்டும் முயற்சிக்கவும்."
                ),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(AppLanguage.text("Retry", "Cuba Lagi", "重试", "மீண்டும் முயற்சிக்கவும்"))
            }
        }
    }
}

@Composable
private fun AppBottomMenu(
    currentRoute: String,
    nav: NavHostController
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val labelStyle = MaterialTheme.typography.labelLarge.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            val labels = bottomMenuItems.map { item ->
                AppLanguage.text(item.enLabel, item.msLabel, item.zhLabel, item.taLabel)
            }
            // Measure translated labels at the user's actual font scale. Longer
            // labels get more width, instead of squeezing five equal-width tabs.
            val minimumWidths = labels.map { label ->
                val textWidth = textMeasurer.measure(label, style = labelStyle, softWrap = false).size.width
                with(density) { maxOf(textWidth + 12.dp.roundToPx(), 48.dp.roundToPx()) }
            }
            val homeIndex = bottomMenuItems.indexOfFirst { it.route == Route.Dashboard.path }
            val groups = listOf(
                (0 until homeIndex).toList(),
                listOf(homeIndex),
                (homeIndex + 1 until bottomMenuItems.size).toList()
            )
            // Equal-width side groups anchor Home to the exact centre. Labels
            // wrap and increase the bar's height without moving any menu item.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()
            ) {
                groups.forEachIndexed { groupIndex, indices ->
                    Row(
                        (if (groupIndex == 1) Modifier.width(68.dp) else Modifier.weight(1f))
                            .fillMaxHeight()
                    ) {
                        indices.forEach { index ->
                            val item = bottomMenuItems[index]
                            val selected = currentRoute == item.route
                            val isHome = item.route == Route.Dashboard.path
                            val foreground = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(
                                modifier = Modifier
                                    .weight(minimumWidths[index].toFloat())
                                    .fillMaxHeight()
                                    .heightIn(min = 92.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .selectable(
                                        selected = selected,
                                        role = Role.Tab,
                                        onClick = {
                                            when {
                                                isHome -> {
                                                    // Home always clears nested navigation history.
                                                    nav.navigate(Route.Dashboard.path) {
                                                        popUpTo(0) { inclusive = true }
                                                        launchSingleTop = true
                                                    }
                                                }
                                                !selected -> {
                                                    nav.navigate(item.route) {
                                                        launchSingleTop = true
                                                        restoreState = true
                                                        popUpTo(Route.Dashboard.path) { saveState = true }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top)
                            ) {
                                Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                                    Box(
                                        Modifier.size(if (isHome) 56.dp else 44.dp).background(
                                            color = when {
                                                isHome -> MaterialTheme.colorScheme.primary
                                                selected -> MaterialTheme.colorScheme.primaryContainer
                                                else -> Color.Transparent
                                            },
                                            shape = CircleShape
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(if (isHome) 36.dp else 28.dp),
                                            tint = if (isHome) MaterialTheme.colorScheme.onPrimary else foreground
                                        )
                                    }
                                }
                                Text(
                                    text = labels[index],
                                    modifier = Modifier.fillMaxWidth(),
                                    style = labelStyle,
                                    fontWeight = if (isHome) FontWeight.Bold else FontWeight.SemiBold,
                                    color = foreground,
                                    softWrap = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
