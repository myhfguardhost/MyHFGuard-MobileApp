package com.vitalink.app.ui.screens

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalink.app.R
import com.vitalink.app.data.api.AiApiService
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.api.ServerApiService
import com.vitalink.app.data.model.*
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.reminders.SelfCheckAlertNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

// The reward table retains the latest claim per video, not lifetime earnings.
// Both screens must prefer the persisted lifetime balance, including zero.
private fun educationCoinTotal(profileCoins: Int?, rewards: List<EducationVideoReward>): Int =
    profileCoins ?: rewards.sumOf { it.coins_awarded ?: 10 }

@Composable
private fun RefreshCoinsOnResume(onRefresh: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val refresh by rememberUpdatedState(onRefresh)
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private val Blue = Color(0xFF22B8E6)
private val Green = Color(0xFF2ECC71)
private val Orange = Color(0xFFFF8A1F)
private val Red = Color(0xFFE53935)
private val ExercisePink = Color(0xFFB783A6)
private val ExercisePinkSoft = Color(0xFFF8EAF2)
private val ExercisePinkBackground = Color(0xFFFFF9FC)
private val DarkCard = Color(0xFF203044)

private fun formatAlphabeticalDate(raw: String): String {
    if (raw.isBlank()) return "-"
    val isoDate = raw.take(10)
    return runCatching {
        LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }.getOrElse { raw }
}

private fun formatAlphabeticalFullDate(raw: String): String {
    if (raw.isBlank()) return "-"
    val isoDate = raw.take(10)
    return runCatching {
        LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }.getOrElse { raw }
}

/** True when the supplied ISO date/timestamp falls within today and the previous 6 days. */
private fun isInLatestSevenDays(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return false
    val today = LocalDate.now()
    val start = today.minusDays(6)
    return !date.isBefore(start) && !date.isAfter(today)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    color: Color = Blue,
    showBack: Boolean = true,
    refresh: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            }
        },
        actions = { if (refresh != null) IconButton(onClick = refresh) { Icon(Icons.Default.Refresh, null) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = color, titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White)
    )
}

@Composable
private fun StatusText(text: String?) {
    if (!text.isNullOrBlank()) {
        val good = text.contains("saved", true) ||
            text.contains("loaded", true) ||
            text.contains("success", true) ||
            text.contains("collected", true) ||
            text.contains("claimed", true) ||
            text.contains("disimpan", true) ||
            text.contains("berjaya", true) ||
            text.contains("dikutip", true)
        Text(text, color = if (good) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WebsiteGraphCard(moduleName: String, websiteUrl: String? = null) {
    val ms = AppLanguage.useMalay
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.InsertChart, null, tint = Color(0xFFFF8F00))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AppLanguage.text("Graph only available on website", "Graf hanya di website"), fontWeight = FontWeight.Bold, color = Color(0xFF8A5A00))
                Text(AppLanguage.text("$moduleName data can be entered and shown in the app. Full graphs remain on the website.", "Data $moduleName boleh dimasukkan dan dipaparkan dalam app. Graf penuh kekal di website."), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A5A00))
                if (!websiteUrl.isNullOrBlank()) {
                    OutlinedButton(onClick = { uriHandler.openUri(websiteUrl) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text(AppLanguage.text("View chart on website", "Lihat carta di website"))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySimpleCard(text: String, icon: ImageVector, color: Color) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .10f)), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerInput(
    label: String,
    selectedDate: String,
    onDateSelected: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val initialMillis = remember(selectedDate) {
        runCatching {
            LocalDate.parse(selectedDate)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    OutlinedButton(
        onClick = { showPicker = true },
        modifier = Modifier.fillMaxWidth().height(62.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp)
    ) {
        Icon(Icons.Default.CalendarMonth, null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatAlphabeticalFullDate(selectedDate), fontWeight = FontWeight.Bold)
        }
        Icon(Icons.Default.ArrowDropDown, null)
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDateSelected(
                                Instant.ofEpochMilli(millis)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                    .toString()
                            )
                        }
                        showPicker = false
                    }
                ) { Text(AppLanguage.text("Select", "Pilih")) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(AppLanguage.text("Cancel", "Batal"))
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

// ---------------- WATER & SALT: same columns/design as website ----------------
@HiltViewModel
class WaterViewModel @Inject constructor(private val api: ApiService, session: SessionManager) : ViewModel() {
    private val _logs = MutableStateFlow<List<WaterSaltLog>>(emptyList())
    val logs = _logs.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _successEvent = MutableStateFlow(0)
    val successEvent = _successEvent.asStateFlow()

    private var pid = ""
    private var saveJob: Job? = null
    init { viewModelScope.launch { session.patientId.filterNotNull().collect { pid = it; fetch() } } }
    fun fetch() = viewModelScope.launch {
        if (pid.isBlank()) return@launch
        try { _logs.value = api.getWaterLogs("eq.$pid").body().orEmpty(); _message.value = null } catch (e: Exception) { _message.value = UserFacingError.from(e, Action.LOAD) }
    }
    fun save(date: String, limit: Int, cups: Int, breakfast: String, lunch: String, dinner: String) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (pid.isBlank()) {
                _message.value = AppLanguage.text(
                    "Please login again.",
                    "Sila log masuk semula.",
                    "请重新登录。",
                    "மீண்டும் உள்நுழையவும்."
                )
                return@launch
            }

            val ml = cups * 200
            val waterStatus = when {
                limit <= 0 -> "red"
                ml <= limit -> "green"
                ml <= limit + 200 -> "orange"
                else -> "red"
            }
            fun score(value: String) = when (value) {
                "natural" -> 1
                "moderate" -> 2
                else -> 3
            }
            val saltScore = score(breakfast) + score(lunch) + score(dinner)
            val saltStatus = com.vitalink.app.util.SaltScore.status(saltScore)

            _saving.value = true
            _message.value = null
            try {
                val response = api.upsertWaterSaltLog(
                    WaterSaltUpsert(
                        pid,
                        date,
                        limit,
                        cups,
                        ml,
                        waterStatus,
                        breakfast,
                        lunch,
                        dinner,
                        saltScore,
                        saltStatus
                    )
                )
                if (response.isSuccessful) {
                    _message.value = AppLanguage.text(
                        "Saved successfully.",
                        "Berjaya disimpan.",
                        "保存成功。",
                        "வெற்றிகரமாக சேமிக்கப்பட்டது."
                    )
                    _successEvent.value += 1
                    fetch()
                } else {
                    _message.value = UserFacingError.http(response.code(), Action.SAVE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = UserFacingError.from(e, Action.SAVE)
            } finally {
                _saving.value = false
            }
        }
    }

    fun cancelSave() {
        saveJob?.cancel()
        saveJob = null
        _saving.value = false
        _message.value = AppLanguage.text(
            "Save cancelled. You can edit and try again.",
            "Simpanan dibatalkan. Anda boleh mengedit dan cuba lagi.",
            "保存已取消，您可以修改后重试。",
            "சேமிப்பு ரத்துசெய்யப்பட்டது. திருத்தி மீண்டும் முயற்சிக்கலாம்."
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterSaltScreen(onBack: () -> Unit, focus: String? = null, vm: WaterViewModel = hiltViewModel()) {
    val ms = AppLanguage.useMalay
    val logs by vm.logs.collectAsState()
    val msg by vm.message.collectAsState()
    val saving by vm.saving.collectAsState()
    val successEvent by vm.successEvent.collectAsState()
    var handledSuccessEvent by remember { mutableIntStateOf(0) }
    var showDataCollected by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val today = LocalDate.now().toString()

    LaunchedEffect(successEvent) {
        if (successEvent > handledSuccessEvent) {
            handledSuccessEvent = successEvent
            showDataCollected = true
        }
    }
    var selectedDate by remember { mutableStateOf(today) }
    val selectedDateLog = logs.firstOrNull { it.entry_date == selectedDate }
    var waterLimit by remember(selectedDateLog?.water_limit_ml, selectedDate) { mutableStateOf((selectedDateLog?.water_limit_ml ?: 800).toString()) }
    var cups by remember(selectedDateLog?.water_cups, selectedDate) { mutableStateOf(selectedDateLog?.water_cups ?: 0) }
    var breakfast by remember(selectedDateLog?.breakfast_salt, selectedDate) { mutableStateOf(selectedDateLog?.breakfast_salt ?: "natural") }
    var lunch by remember(selectedDateLog?.lunch_salt, selectedDate) { mutableStateOf(selectedDateLog?.lunch_salt ?: "natural") }
    var dinner by remember(selectedDateLog?.dinner_salt, selectedDate) { mutableStateOf(selectedDateLog?.dinner_salt ?: "natural") }

    LaunchedEffect(focus) {
        val target = when (focus) { "water" -> 1; "salt" -> 2; else -> null } ?: return@LaunchedEffect
        kotlinx.coroutines.delay(220)
        listState.animateScrollToItem(target)
    }

    Scaffold(topBar = { ScreenTopBar(AppLanguage.text("My Water & Diet", "Air & Diet Saya"), onBack, Blue) { vm.fetch() } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DatePickerInput(
                    label = AppLanguage.text("Date", "Tarikh"),
                    selectedDate = selectedDate,
                    onDateSelected = { selectedDate = it }
                )
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AppLanguage.text("My Water Intake", "Pengambilan Air Saya"), fontWeight = FontWeight.Bold)
                    OutlinedTextField(waterLimit, { waterLimit = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(AppLanguage.text("Doctor Water Restriction (ml)", "Had air doktor (ml)")) })
                    Text(AppLanguage.text("Select Today Water Intake (8 cups)", "Pilih Pengambilan Air Hari Ini (8 cawan)"), fontWeight = FontWeight.Medium)
                    Text(AppLanguage.text("Each cup = 200 ml", "Setiap cawan = 200 ml"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..8).chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { c -> AssistChip(onClick = { cups = c }, label = { Text("🧋\n${c*200}ml", textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f), colors = AssistChipDefaults.assistChipColors(containerColor = if (c <= cups) Blue.copy(alpha=.25f) else MaterialTheme.colorScheme.surface)) }
                        } }
                    }
                    val ml = cups * 200; val limit = waterLimit.toIntOrNull() ?: 0
                    val isWaterOverLimit = limit > 0 && ml > limit
                    LinearProgressIndicator(
                        progress = { if (limit > 0) (ml.toFloat() / limit).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isWaterOverLimit) Red else MaterialTheme.colorScheme.primary
                    )
                    Text("${AppLanguage.text("Selected", "Dipilih")}: $ml ml  •  ${AppLanguage.text("Limit", "Had")}: $limit ml", fontWeight = FontWeight.Bold)
                } }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(AppLanguage.text("My Low Salt Diet", "Diet Rendah Garam Saya"), fontWeight = FontWeight.Bold)
                    SaltMealSelector(AppLanguage.text("Breakfast", "Sarapan"), breakfast) { breakfast = it }
                    SaltMealSelector(AppLanguage.text("Lunch", "Makan Tengah Hari"), lunch) { lunch = it }
                    SaltMealSelector(AppLanguage.text("Dinner", "Makan Malam"), dinner) { dinner = it }
                    fun saltValue(v: String): Int = when (v) { "natural" -> 1; "moderate" -> 2; else -> 3 }
                    val score = saltValue(breakfast) + saltValue(lunch) + saltValue(dinner)
                    LinearProgressIndicator(progress = { score / 9f }, modifier = Modifier.fillMaxWidth())
                    Text("${AppLanguage.text("Daily Salt Score", "Skor Garam Harian")}: $score / 9", fontWeight = FontWeight.Bold)
                } }
            }
            item { StatusText(msg) }
            item {
                Button(
                    onClick = { vm.save(selectedDate, waterLimit.toIntOrNull() ?: 0, cups, breakfast, lunch, dinner) },
                    enabled = !saving && cups > 0 && (waterLimit.toIntOrNull() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) {
                    Text(
                        if (selectedDateLog != null) {
                            AppLanguage.text("Update Entry", "Kemas Kini Rekod", "更新记录", "பதிவைப் புதுப்பிக்கவும்")
                        } else {
                            AppLanguage.text("Save Today Entry", "Simpan Rekod Hari Ini", "保存今日记录", "இன்றைய பதிவைச் சேமிக்கவும்")
                        }
                    )
                }
            }
            item { WaterSaltGraphCard(logs) }
        }
    }

    BlockingLoadingScreen(
        visible = saving,
        title = AppLanguage.text(
            "Saving water and diet data...",
            "Menyimpan data air dan diet...",
            "正在保存饮水与饮食数据……",
            "தண்ணீர் மற்றும் உணவுத் தரவு சேமிக்கப்படுகிறது..."
        ),
        onCancel = vm::cancelSave
    )

    DataCollectedSuccessScreen(
        visible = showDataCollected,
        onDismiss = { showDataCollected = false }
    )
}


@Composable
private fun WaterSaltGraphCard(logs: List<WaterSaltLog>) {
    val ms = AppLanguage.useMalay
    val data = logs.filter { isInLatestSevenDays(it.entry_date) }.sortedBy { it.entry_date }
    val chartData = data.ifEmpty {
        (0..6).map { index ->
            WaterSaltLog(patient_id = "", entry_date = "", water_intake_ml = 0, water_limit_ml = 0, salt_score = 0)
        }
    }
    val maxWater = chartData.maxOfOrNull { maxOf(it.water_intake_ml ?: it.water_ml ?: 0, it.water_limit_ml ?: 0) }?.coerceAtLeast(1) ?: 1
    val waterLimit = chartData.mapNotNull { it.water_limit_ml?.takeIf { limit -> limit > 0 } }.average().toFloat().takeIf { it > 0f } ?: maxWater.toFloat()
    val maxSalt = 9
    val hasData = data.isNotEmpty()

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertChart, null, tint = Blue)
                Spacer(Modifier.width(8.dp))
                Text(AppLanguage.text("Water & Salt Graph", "Graf Air & Garam"), fontWeight = FontWeight.Bold)
            }
            Text(AppLanguage.text("Water Intake (ml)", "Pengambilan Air (ml)"), fontWeight = FontWeight.Medium)
            SimpleBarChart(
                values = chartData.map { (it.water_intake_ml ?: it.water_ml ?: 0).toFloat() },
                labels = chartData.map { formatAlphabeticalDate(it.entry_date) },
                maxValue = maxWater.toFloat(),
                emptyText = AppLanguage.text("No water data yet.", "Tiada data air lagi."),
                limitSeries = chartData.map { it.water_limit_ml?.takeIf { limit -> limit > 0 }?.toFloat() },
                normalBands = listOf(Triple(AppLanguage.text("Within water limit", "Dalam had air"), 0f, waterLimit))
            )

            Text(AppLanguage.text("Daily Salt Score", "Skor Garam Harian"), fontWeight = FontWeight.Medium)
            SimpleBarChart(
                values = chartData.map { com.vitalink.app.util.SaltScore.display(it.salt_score ?: 3).toFloat() },
                labels = chartData.map { formatAlphabeticalDate(it.entry_date) },
                maxValue = maxSalt.toFloat(),
                emptyText = AppLanguage.text("No salt data yet.", "Tiada data garam lagi."),
                references = listOf(AppLanguage.text("Normal below 3", "Normal bawah 3") to 3f),
                normalBands = listOf(Triple(AppLanguage.text("Normal salt score <3", "Skor garam normal <3"), 0f, 3f)),
                pointPresence = chartData.map { it.salt_score != null && it.entry_date.isNotBlank() }
            )

            if (!hasData) Text(AppLanguage.text("Save records to display the graph.", "Simpan rekod untuk memaparkan graf."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class ChartZone(val low: Float, val high: Float, val color: Color)

@Composable
private fun SimpleBarChart(
    values: List<Float>,
    labels: List<String>,
    maxValue: Float,
    minValue: Float = 0f,
    emptyText: String,
    decimalPlaces: Int = 0,
    pointPresence: List<Boolean>? = null,
    references: List<Pair<String, Float>> = emptyList(),
    zones: List<ChartZone> = emptyList(),
    limitSeries: List<Float?> = emptyList(),
    normalBands: List<Triple<String, Float, Float>> = emptyList()
) {
    SimpleLineChart(
        values = values,
        labels = labels,
        maxValue = maxValue,
        minValue = minValue,
        emptyText = emptyText,
        decimalPlaces = decimalPlaces,
        pointPresence = pointPresence,
        references = references,
        zones = zones,
        limitSeries = limitSeries,
        normalBands = normalBands
    )
}

@Composable
private fun SimpleLineChart(
    values: List<Float>,
    labels: List<String>,
    maxValue: Float,
    minValue: Float = 0f,
    emptyText: String,
    decimalPlaces: Int = 0,
    pointPresence: List<Boolean>? = null,
    references: List<Pair<String, Float>> = emptyList(),
    zones: List<ChartZone> = emptyList(),
    limitSeries: List<Float?> = emptyList(),
    normalBands: List<Triple<String, Float, Float>> = emptyList()
) {
    val safeValues = if (values.isEmpty()) List(7) { 0f } else values
    val safeLabels = if (labels.isEmpty()) List(safeValues.size) { "-" } else labels
    val safePresence = pointPresence
        ?.let { presence -> List(safeValues.size) { index -> presence.getOrNull(index) == true } }
        ?: safeValues.map { it > 0f }
    val safeMin = minValue.coerceAtLeast(0f)
    val safeMax = maxOf(maxValue, safeMin + 1f)
    val valueRange = (safeMax - safeMin).coerceAtLeast(1f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val hasData = safePresence.any { it }
    val yTicks = (4 downTo 0).map { index -> safeMin + valueRange * index / 4f }

    Row(
        modifier = Modifier.fillMaxWidth().height(170.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.width(48.dp).fillMaxHeight().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            yTicks.forEach { value ->
                Text(
                    formatChartValue(value, decimalPlaces),
                    style = MaterialTheme.typography.labelSmall,
                    color = axisTextColor
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val chartTop = 8.dp.toPx()
            val chartBottom = size.height - 8.dp.toPx()
            val chartHeight = chartBottom - chartTop
            // Put each point at the centre of its label cell so the date sits directly below the dot.
            val stepX = if (safeValues.isEmpty()) size.width else size.width / safeValues.size

            // Keep black data and translucent regions readable in both light and dark mode.
            drawRect(
                color = Color.White,
                topLeft = Offset(0f, chartTop),
                size = androidx.compose.ui.geometry.Size(size.width, chartHeight)
            )

            // Shade the healthy (green) range behind the data so the status is
            // visible at a glance on every self-check graph.
            normalBands.forEach { (_, low, high) ->
                val bandLow = ((low - safeMin) / valueRange).coerceIn(0f, 1f)
                val bandHigh = ((high - safeMin) / valueRange).coerceIn(0f, 1f)
                if (bandHigh > bandLow) {
                    drawRect(
                        color = Color(0xFF2E9B57).copy(alpha = 0.16f),
                        topLeft = Offset(0f, chartBottom - chartHeight * bandHigh),
                        size = androidx.compose.ui.geometry.Size(size.width, chartHeight * (bandHigh - bandLow))
                    )
                }
            }
            // Draw every configured region, including the symptom yellow and red bands.
            zones.forEach { zone ->
                val bandLow = ((zone.low - safeMin) / valueRange).coerceIn(0f, 1f)
                val bandHigh = ((zone.high - safeMin) / valueRange).coerceIn(0f, 1f)
                if (bandHigh > bandLow) {
                    drawRect(
                        color = zone.color.copy(alpha = 0.16f),
                        topLeft = Offset(0f, chartBottom - chartHeight * bandHigh),
                        size = androidx.compose.ui.geometry.Size(size.width, chartHeight * (bandHigh - bandLow))
                    )
                }
            }

            for (i in 0..4) {
                val y = chartTop + (chartHeight / 4f) * i
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }


            var previous: Offset? = null
            safeValues.forEachIndexed { index, value ->
                val progress = ((value - safeMin) / valueRange).coerceIn(0f, 1f)
                val x = if (safeValues.size <= 1) size.width / 2f else stepX * (index + 0.5f)
                val y = chartBottom - (chartHeight * progress)
                val current = Offset(x, y)
                if (safePresence.getOrNull(index) == true) {
                    // Single-series graphs use black; SYS/DIA use their separate chart below.
                    previous?.let { drawLine(color = Color.Black, start = it, end = current, strokeWidth = 3.dp.toPx()) }
                    // A recorded value of 0 is valid data (especially for symptom score).
                    drawCircle(color = Color.White, radius = 6.dp.toPx(), center = current)
                    drawCircle(color = Color.Black, radius = 4.5.dp.toPx(), center = current)
                    previous = current
                } else {
                    // Do not connect a line through dates that have no record.
                    previous = null
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(56.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            safeLabels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = axisTextColor,
                    maxLines = 1
                )
            }
        }
    }

    if (!hasData) Text(emptyText, style = MaterialTheme.typography.bodySmall, color = axisTextColor)
}

private fun formatChartValue(value: Float, decimalPlaces: Int): String =
    if (decimalPlaces > 0) ("%." + decimalPlaces + "f").format(value) else value.toInt().toString()

@Composable
private fun SaltMealSelector(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("natural" to "Natural / Low Salt", "moderate" to "Moderate Salt", "high" to "High Salt").forEach { (v, text) ->
                val color = if (v == "natural") Green else if (v == "moderate") Orange else Red
                Card(Modifier.weight(1f).clickable { onChange(v) }, colors = CardDefaults.cardColors(containerColor = if (value == v) color.copy(alpha=.18f) else MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Box(Modifier.fillMaxWidth().height(8.dp).background(color, RoundedCornerShape(10.dp))); Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

private fun currentExerciseWeekKey(): String =
    LocalDate.now().with(WeekFields.SUNDAY_START.dayOfWeek(), 1).toString()

// ---------------- EXERCISE: weekly goal, rating and admin step target ----------------
data class ExerciseWeekDay(val day: String, val steps: Long)

@HiltViewModel
class ExerciseViewModel @Inject constructor(private val api: ApiService, session: SessionManager) : ViewModel() {
    private val _steps = MutableStateFlow(0L)
    val steps = _steps.asStateFlow()

    private val _spo2 = MutableStateFlow<Int?>(null)
    val spo2 = _spo2.asStateFlow()

    private val _lastSync = MutableStateFlow<String?>(null)
    val lastSync = _lastSync.asStateFlow()

    private val _weekSteps = MutableStateFlow<List<ExerciseWeekDay>>(emptyList())
    val weekSteps = _weekSteps.asStateFlow()

    private val _currentGoal = MutableStateFlow<String?>(null)
    val currentGoal = _currentGoal.asStateFlow()

    private val _stepTarget = MutableStateFlow(3000L)
    val stepTarget = _stepTarget.asStateFlow()

    private val _ratingWeekKey = MutableStateFlow<String?>(null)
    val ratingWeekKey = _ratingWeekKey.asStateFlow()

    private val _ratingGoal = MutableStateFlow<String?>(null)
    val ratingGoal = _ratingGoal.asStateFlow()

    private val _achievementRating = MutableStateFlow<Int?>(null)
    val achievementRating = _achievementRating.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()

    private val _successEvent = MutableStateFlow(0)
    val successEvent = _successEvent.asStateFlow()

    private var pid = ""
    private var currentWeekKey = ""
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            session.patientId.filterNotNull().collect {
                pid = it
                fetch()
            }
        }
    }

    fun fetch() = viewModelScope.launch {
        if (pid.isBlank()) return@launch
        _loading.value = true
        try {
            val today = LocalDate.now()
            val todayText = today.toString()
            currentWeekKey = today.with(WeekFields.SUNDAY_START.dayOfWeek(), 1).toString()
            val stepRows = api.getStepsDay("eq.$pid").body().orEmpty()
            val todayStepRow = stepRows.firstOrNull { it.date == todayText } ?: stepRows.firstOrNull()

            _steps.value = todayStepRow?.steps_total ?: 0L

            val spo2Rows = api.getSpo2Day("eq.$pid").body().orEmpty()
            val todaySpo2 = spo2Rows.firstOrNull { it.date == todayText } ?: spo2Rows.firstOrNull()
            _spo2.value = todaySpo2?.spo2_avg?.toInt()
            _lastSync.value = latestSyncTimestamp(
                todayStepRow?.updated_at,
                todayStepRow?.created_at,
                todaySpo2?.updated_at,
                todaySpo2?.created_at
            ) ?: todayStepRow?.date ?: todaySpo2?.date

            _weekSteps.value = stepRows
                .filter { isInLatestSevenDays(it.date) }
                .sortedBy { it.date }
                .map { ExerciseWeekDay(formatAlphabeticalDate(it.date), it.steps_total ?: 0L) }

            _stepTarget.value = runCatching {
                api.getProfile("eq.$pid").body().orEmpty().firstOrNull()?.target_steps
                    ?.toLong()?.coerceIn(500L, 50000L) ?: 3000L
            }.getOrDefault(3000L)

            val goals = runCatching { api.getExerciseGoals("eq.$pid").body().orEmpty() }.getOrDefault(emptyList())
            _currentGoal.value = goals.firstOrNull { it.week_key == currentWeekKey }?.goal

            val previousGoal = goals
                .filter { it.week_key < currentWeekKey }
                .maxByOrNull { it.week_key }
            _ratingWeekKey.value = previousGoal?.week_key
            _ratingGoal.value = previousGoal?.goal
            _achievementRating.value = previousGoal?.achievement_rating
            _msg.value = null
        } catch (e: Exception) {
            _msg.value = UserFacingError.from(e, Action.LOAD)
        } finally {
            _loading.value = false
        }
    }

    fun saveWeeklyGoal(goal: String) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (pid.isBlank()) {
                _msg.value = AppLanguage.text("Please login again.", "Sila log masuk semula.", "请重新登录。", "மீண்டும் உள்நுழையவும்.")
                return@launch
            }
            if (currentWeekKey.isBlank()) currentWeekKey = currentExerciseWeekKey()

            _saving.value = true
            _msg.value = null
            try {
                val body = ExerciseGoalUpsert(patient_id = pid, week_key = currentWeekKey, goal = goal)
                val r = api.upsertExerciseGoal(body)
                if (!r.isSuccessful) {
                    _msg.value = UserFacingError.http(r.code(), Action.SAVE)
                    return@launch
                }
                val changed = !_currentGoal.value.isNullOrBlank()
                _currentGoal.value = goal
                _msg.value = if (changed) {
                    AppLanguage.text("Weekly goal changed successfully.", "Matlamat mingguan berjaya diubah.", "每周目标已成功更改。", "வாராந்திர இலக்கு வெற்றிகரமாக மாற்றப்பட்டது.")
                } else {
                    AppLanguage.text("Weekly goal saved successfully.", "Matlamat mingguan berjaya disimpan.", "每周目标已成功保存。", "வாராந்திர இலக்கு வெற்றிகரமாக சேமிக்கப்பட்டது.")
                }
                _successEvent.value += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _msg.value = UserFacingError.from(e, Action.SAVE)
            } finally {
                _saving.value = false
            }
        }
    }

    fun saveAchievementRating(rating: Int) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            val weekKey = _ratingWeekKey.value ?: return@launch
            if (pid.isBlank() || rating !in 1..5) return@launch

            _saving.value = true
            _msg.value = null
            try {
                val r = api.updateExerciseGoal(
                    patientId = "eq.$pid",
                    weekKey = "eq.$weekKey",
                    body = mapOf("achievement_rating" to rating)
                )
                if (!r.isSuccessful) {
                    _msg.value = UserFacingError.http(r.code(), Action.SAVE)
                    return@launch
                }
                _achievementRating.value = rating
                _msg.value = AppLanguage.text(
                    "Last week's rating saved successfully.",
                    "Penilaian minggu lepas berjaya disimpan.",
                    "上周评分已成功保存。",
                    "கடந்த வார மதிப்பீடு வெற்றிகரமாக சேமிக்கப்பட்டது."
                )
                _successEvent.value += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _msg.value = UserFacingError.from(e, Action.SAVE)
            } finally {
                _saving.value = false
            }
        }
    }

    fun cancelSave() {
        saveJob?.cancel()
        saveJob = null
        _saving.value = false
        _msg.value = AppLanguage.text(
            "Save cancelled. You can edit and try again.",
            "Simpanan dibatalkan. Anda boleh mengedit dan cuba lagi.",
            "保存已取消，您可以修改后重试。",
            "சேமிப்பு ரத்துசெய்யப்பட்டது. திருத்தி மீண்டும் முயற்சிக்கலாம்."
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(onBack: () -> Unit, vm: ExerciseViewModel = hiltViewModel()) {
    val ms = AppLanguage.useMalay
    val steps by vm.steps.collectAsState()
    val spo2 by vm.spo2.collectAsState()
    val lastSync by vm.lastSync.collectAsState()
    val weekSteps by vm.weekSteps.collectAsState()
    val currentGoal by vm.currentGoal.collectAsState()
    val stepTarget by vm.stepTarget.collectAsState()
    val ratingWeekKey by vm.ratingWeekKey.collectAsState()
    val ratingGoal by vm.ratingGoal.collectAsState()
    val savedRating by vm.achievementRating.collectAsState()
    val saving by vm.saving.collectAsState()
    val msg by vm.msg.collectAsState()
    val successEvent by vm.successEvent.collectAsState()
    var handledSuccessEvent by remember { mutableIntStateOf(0) }
    var showDataCollected by remember { mutableStateOf(false) }

    LaunchedEffect(successEvent) {
        if (successEvent > handledSuccessEvent) {
            handledSuccessEvent = successEvent
            showDataCollected = true
        }
    }

    val remaining = (stepTarget - steps).coerceAtLeast(0L)
    val targetReached = steps >= stepTarget
    val toleratedWell = steps >= (stepTarget * 2 / 3)
    val recommendation = when {
        targetReached -> AppLanguage.text(
            "Great! Daily target reached. Continue light activity safely.",
            "Bagus! Sasaran harian telah dicapai. Teruskan aktiviti ringan dengan selamat.",
            "很好！已达到每日目标。请安全地继续轻度活动。",
            "அருமை! தினசரி இலக்கு எட்டப்பட்டது. பாதுகாப்பாக லேசான செயல்பாட்டைத் தொடரவும்."
        )
        toleratedWell -> AppLanguage.text(
            "You are getting close. Add gentle walking if you feel comfortable.",
            "Anda semakin hampir. Cuba tambah jalan kaki perlahan jika badan masih selesa.",
            "您快达成目标了。感觉舒适时可增加轻松步行。",
            "நீங்கள் இலக்கை நெருங்குகிறீர்கள். வசதியாக இருந்தால் மெதுவாக நடக்கவும்."
        )
        else -> AppLanguage.text(
            "Start slowly. Rest if you feel breathless, dizzy, or too tired.",
            "Mula perlahan-lahan. Ambil rehat jika sesak nafas, pening, atau terlalu letih.",
            "请慢慢开始。若气喘、头晕或过度疲劳，请休息。",
            "மெதுவாக தொடங்கவும். மூச்சுத்திணறல், மயக்கம் அல்லது அதிக சோர்வு இருந்தால் ஓய்வெடுக்கவும்."
        )
    }

    val weekStart = currentExerciseWeekKey()
    val weeklyGoals = listOf(
        "goalBetterSleep" to AppLanguage.text("Better sleep", "Tidur lebih baik", "睡得更好", "நல்ல தூக்கம்"),
        "goalBoostedEnergy" to AppLanguage.text("More energy", "Lebih bertenaga", "更有精神", "அதிக ஆற்றல்"),
        "goalWalkWithEase" to AppLanguage.text("Walk with ease", "Berjalan lebih mudah", "轻松步行", "எளிதாக நடக்க"),
        "goalLessPain" to AppLanguage.text("Less pain", "Kurang sakit", "减少疼痛", "குறைந்த வலி"),
        "goalFeelBetter" to AppLanguage.text("Feel better", "Rasa lebih sihat", "感觉更好", "நலமாக உணர"),
        "goalReducedBreathlessness" to AppLanguage.text("Less breathlessness", "Kurang sesak nafas", "减少气喘", "குறைந்த மூச்சுத்திணறல்"),
        "goalLessFatigue" to AppLanguage.text("Less fatigue", "Kurang keletihan", "减少疲劳", "குறைந்த சோர்வு"),
        "goalMoreHouseEnergy" to AppLanguage.text("Energy at home", "Tenaga di rumah", "居家更有活力", "வீட்டு ஆற்றல்"),
        "goalMoreSocialEnergy" to AppLanguage.text("Social energy", "Tenaga bersosial", "社交更有活力", "சமூக ஆற்றல்"),
        "goalImprovedAppetite" to AppLanguage.text("Better appetite", "Selera lebih baik", "食欲更好", "நல்ல பசி")
    )
    var selectedGoal by remember(weekStart, currentGoal) { mutableStateOf(currentGoal ?: weeklyGoals.first().first) }
    var showGoalConfirmation by remember { mutableStateOf(false) }
    var selectedRating by remember(savedRating) { mutableIntStateOf(savedRating ?: 0) }
    val currentGoalLabel = weeklyGoals.firstOrNull { it.first == (currentGoal ?: selectedGoal) }?.second ?: weeklyGoals.first().second
    val previousGoalLabel = weeklyGoals.firstOrNull { it.first == ratingGoal }?.second ?: ratingGoal.orEmpty()

    if (showGoalConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!saving) showGoalConfirmation = false },
            title = { Text(if (currentGoal.isNullOrBlank()) (AppLanguage.text("Confirm Goal", "Sahkan Matlamat", "确认目标", "இலக்கை உறுதிசெய்")) else (AppLanguage.text("Change Goal?", "Tukar Matlamat?", "更改目标？", "இலக்கை மாற்றவா?"))) },
            text = {
                Text(
                    AppLanguage.text("This week's goal will be set to: ${weeklyGoals.firstOrNull { it.first == selectedGoal }?.second.orEmpty()}", "Matlamat minggu ini akan ditetapkan kepada: ${weeklyGoals.firstOrNull { it.first == selectedGoal }?.second.orEmpty()}")
                )
            },
            confirmButton = {
                Button(
                    onClick = { showGoalConfirmation = false; vm.saveWeeklyGoal(selectedGoal) },
                    enabled = !saving
                ) {
                    Text(if (currentGoal.isNullOrBlank()) (AppLanguage.text("Save", "Simpan", "保存", "சேமி")) else (AppLanguage.text("Change", "Tukar", "更改", "மாற்று")))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoalConfirmation = false }, enabled = !saving) {
                    Text(AppLanguage.text("Cancel", "Batal", "取消", "ரத்துசெய்"))
                }
            }
        )
    }

    Scaffold(
        containerColor = ExercisePinkBackground,
        topBar = { ScreenTopBar(AppLanguage.text("My Exercise", "Senaman Saya", "我的运动", "என் உடற்பயிற்சி"), onBack, ExercisePink) { vm.fetch() } }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Keep smart-band summaries above the weekly goal, as requested.
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExerciseMetricCard(
                        Modifier.fillMaxWidth(),
                        Icons.Default.DirectionsWalk,
                        AppLanguage.text("Steps", "Langkah", "步数", "நடைகள்"),
                        "$steps",
                        AppLanguage.text("Today", "Hari ini", "今天", "இன்று"),
                        Blue
                    )
                    ExerciseMetricCard(
                        Modifier.fillMaxWidth(),
                        Icons.Default.Favorite,
                        "SpO₂",
                        "${spo2 ?: "--"}%",
                        AppLanguage.text("Latest reading", "Bacaan terkini", "最新读数", "சமீபத்திய அளவீடு"),
                        Color(0xFF5C6BC0)
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Smartphone, null, tint = Orange)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${AppLanguage.text("Last sync", "Sync terakhir", "上次同步", "கடைசி ஒத்திசைவு")}: ${formatLastSyncDateTime(lastSync)}",
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Flag, null, tint = ExercisePink)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                AppLanguage.text("Weekly Goal", "Matlamat Mingguan", "每周目标", "வார இலக்கு"),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Text("${AppLanguage.text("Week of", "Minggu bermula", "本周开始于", "வாரம் தொடங்கும் நாள்")} ${formatAlphabeticalFullDate(weekStart)}", color = MaterialTheme.colorScheme.onSurfaceVariant)

                        weeklyGoals.chunked(2).forEach { goalRow ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                goalRow.forEach { goal ->
                                    val selected = selectedGoal == goal.first
                                    OutlinedButton(
                                        onClick = { selectedGoal = goal.first },
                                        modifier = Modifier.weight(1f).heightIn(min = 74.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (selected) ExercisePink.copy(alpha = 0.12f) else Color.Transparent,
                                            contentColor = if (selected) ExercisePink else MaterialTheme.colorScheme.onSurface
                                        )
                                    ) {
                                        if (selected) {
                                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(
                                            goal.second,
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 3
                                        )
                                    }
                                }
                                if (goalRow.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }

                        Button(
                            onClick = { showGoalConfirmation = true },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ExercisePink)
                        ) {
                            Text(if (currentGoal.isNullOrBlank()) (AppLanguage.text("Save Goal", "Simpan Matlamat", "保存目标", "இலக்கை சேமி")) else (AppLanguage.text("Change Goal", "Tukar Matlamat", "更改目标", "இலக்கை மாற்று")))
                        }

                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ExercisePinkSoft), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(AppLanguage.text("Current goal", "Matlamat semasa", "当前目标", "தற்போதைய இலக்கு"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                                Text(currentGoalLabel, color = ExercisePink, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (!ratingWeekKey.isNullOrBlank()) {
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                AppLanguage.text("Last Week's Rating", "Penilaian Minggu Lepas", "上周评分", "கடந்த வார மதிப்பீடு"),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                AppLanguage.text(
                                    "How far have you achieved this goal in the last week?",
                                    "Sejauh mana anda telah mencapai matlamat ini pada minggu lepas?",
                                    "上周您在多大程度上实现了这个目标？",
                                    "கடந்த வாரத்தில் இந்த இலக்கை எந்த அளவிற்கு அடைந்தீர்கள்?"
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (previousGoalLabel.isNotBlank()) {
                                Text(previousGoalLabel, color = ExercisePink, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                AppLanguage.text(
                                    "Scale 1–5: 1 = Not achieved, 5 = Fully achieved",
                                    "Skala 1–5: 1 = Belum tercapai, 5 = Tercapai sepenuhnya",
                                    "评分 1–5：1 = 未实现，5 = 完全实现",
                                    "அளவுகோல் 1–5: 1 = அடையவில்லை, 5 = முழுமையாக அடைந்தது"
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                (1..5).forEach { value ->
                                    IconButton(onClick = { selectedRating = value }) {
                                        Icon(
                                            if (value <= selectedRating) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = "$value",
                                            tint = Color(0xFFFFB300)
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = { vm.saveAchievementRating(selectedRating) },
                                enabled = !saving && selectedRating in 1..5,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (savedRating == null) (AppLanguage.text("Save Rating", "Simpan Penilaian", "保存评分", "மதிப்பீட்டை சேமி")) else (AppLanguage.text("Update Rating", "Kemas Kini Penilaian", "更新评分", "மதிப்பீட்டை புதுப்பி")))
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ExercisePinkSoft), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(AppLanguage.text("Daily Step Target", "Sasaran Langkah Harian", "每日步数目标", "தினசரி நடை இலக்கு"), fontWeight = FontWeight.Bold, color = ExercisePink)
                        Text("$stepTarget", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.displaySmall, color = ExercisePink)
                        Text(AppLanguage.text("steps per day", "langkah sehari", "每天步数", "ஒரு நாளுக்கான நடைகள்"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { (steps.toFloat() / stepTarget.coerceAtLeast(1L)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("$steps / $stepTarget ${AppLanguage.text("steps", "langkah", "步", "நடைகள்")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (targetReached) (AppLanguage.text("Target achieved!", "Sasaran dicapai!", "目标已达成！", "இலக்கு எட்டப்பட்டது!")) else "$remaining ${AppLanguage.text("steps remaining", "langkah lagi", "步尚未完成", "நடைகள் மீதமுள்ளன")}", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsActive, null, tint = Color(0xFFFFC107))
                            Spacer(Modifier.width(8.dp))
                            Text(AppLanguage.text("Exercise Reminder", "Cadangan Senaman", "运动建议", "உடற்பயிற்சி நினைவூட்டல்"), fontWeight = FontWeight.Bold)
                        }
                        Text(recommendation, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp, lineHeight = 26.sp)
                    }
                }
            }

            item { ExerciseWeeklyBarChartCard(weekSteps, stepTarget) }
            item { StatusText(msg) }
        }
    }

    BlockingLoadingScreen(
        visible = saving,
        title = AppLanguage.text(
            "Saving exercise data...",
            "Menyimpan data senaman...",
            "正在保存运动数据……",
            "உடற்பயிற்சி தரவு சேமிக்கப்படுகிறது..."
        ),
        onCancel = vm::cancelSave
    )

    DataCollectedSuccessScreen(
        visible = showDataCollected,
        onDismiss = { showDataCollected = false }
    )
}


private fun latestSyncTimestamp(vararg values: String?): String? =
    values.filterNotNull().filter { it.isNotBlank() }.maxByOrNull { value ->
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrDefault(Long.MIN_VALUE)
    }

private fun formatLastSyncDateTime(raw: String?): String {
    if (raw.isNullOrBlank()) {
        return AppLanguage.text("Not synced yet", "Belum sync", "尚未同步", "இன்னும் ஒத்திசைக்கப்படவில்லை")
    }
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.ENGLISH)
    return runCatching {
        OffsetDateTime.parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(formatter)
    }.getOrElse {
        runCatching {
            LocalDateTime.parse(raw.substringBefore('+')).format(formatter)
        }.getOrElse {
            val cleaned = raw.replace('T', ' ').substringBefore('.').substringBefore('+')
            if (cleaned.length >= 19) "${cleaned.take(10)} ${cleaned.substring(11, 19)}" else raw
        }
    }
}

@Composable
private fun ExerciseWeeklyBarChartCard(weekSteps: List<ExerciseWeekDay>, targetSteps: Long) {
    val ms = AppLanguage.useMalay
    val data = weekSteps.ifEmpty { List(7) { ExerciseWeekDay("-", 0L) } }
    val maxSteps = data.maxOfOrNull { it.steps }?.coerceAtLeast(targetSteps) ?: targetSteps.coerceAtLeast(1L)

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertChart, null, tint = Blue)
                Spacer(Modifier.width(8.dp))
                Text(AppLanguage.text("Weekly Step Trend", "Trend Langkah Mingguan"), fontWeight = FontWeight.Bold)
            }
            SimpleLineChart(
                values = data.map { it.steps.toFloat() },
                labels = data.map { it.day },
                maxValue = maxSteps.toFloat(),
                emptyText = AppLanguage.text("No step data yet.", "Tiada data langkah lagi.")
            )
        }
    }
}

@Composable
private fun ExerciseMetricCard(modifier: Modifier, icon: ImageVector, title: String, value: String, subtitle: String, color: Color) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .10f))) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = Color(0xFF172032))
            }
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- SELF CHECK: website-matched daily records ----------------
data class SelfCheckDailyStatus(
    val hasWeight: Boolean = false,
    val hasSymptoms: Boolean = false,
    val hasVitals: Boolean = false
)

data class SmartWeightOcrResult(
    val valueKg: Double,
    val confidence: Double,
    val eventId: Long = System.nanoTime()
)

data class SmartBpOcrResult(
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val confidence: Double,
    val eventId: Long = System.nanoTime(),
    val annotatedImage: String? = null
)

@HiltViewModel
class SelfCheckViewModel @Inject constructor(
    private val api: ApiService,
    private val aiApi: AiApiService,
    private val serverApi: ServerApiService,
    @Named("ocr") private val ocrApi: ServerApiService,
    session: SessionManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {
    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()

    private val _status = MutableStateFlow(SelfCheckDailyStatus())
    val status = _status.asStateFlow()

    private val _profile = MutableStateFlow<ProfileRow?>(null)
    val profile = _profile.asStateFlow()

    private val _weightHistory = MutableStateFlow<List<WeightDay>>(emptyList())
    val weightHistory = _weightHistory.asStateFlow()

    private val _bpHistory = MutableStateFlow<List<BpEvent>>(emptyList())
    val bpHistory = _bpHistory.asStateFlow()

    private val _symptomHistory = MutableStateFlow<List<SymptomLog>>(emptyList())
    val symptomHistory = _symptomHistory.asStateFlow()

    private val _smartWeightOcr = MutableStateFlow<SmartWeightOcrResult?>(null)
    val smartWeightOcr = _smartWeightOcr.asStateFlow()

    private val _smartBpOcr = MutableStateFlow<SmartBpOcrResult?>(null)
    val smartBpOcr = _smartBpOcr.asStateFlow()

    private val _savingLabel = MutableStateFlow<String?>(null)
    val savingLabel = _savingLabel.asStateFlow()

    private val _successEvent = MutableStateFlow(0)
    val successEvent = _successEvent.asStateFlow()

    // Weight and BP have dedicated confirmation events so their success screen
    // is shown even while the refreshed form data is arriving from Supabase.
    private val _measurementSavedEvent = MutableStateFlow(0)
    val measurementSavedEvent = _measurementSavedEvent.asStateFlow()

    private val _ocrLoadingLabel = MutableStateFlow<String?>(null)
    val ocrLoadingLabel = _ocrLoadingLabel.asStateFlow()

    private val _ocrFailure = MutableStateFlow<String?>(null)
    val ocrFailure = _ocrFailure.asStateFlow()

    fun dismissOcrFailure() {
        _ocrFailure.value = null
    }

    private var pid = ""
    private var email: String? = null

    init {
        viewModelScope.launch {
            session.userEmail.collect { email = it }
        }
        viewModelScope.launch {
            session.patientId.filterNotNull().collect {
                pid = it
                ensurePatientRow()
                refreshStatus(LocalDate.now().toString())
            }
        }
    }

    private suspend fun ensurePatientRow(): Boolean {
        if (pid.isBlank()) return false

        // Match the website backend flow instead of depending on Android direct Supabase INSERT.
        // The server uses the service role key and creates the required public.patients row
        // before symptom/BP inserts, so the foreign-key error is removed.
        val namePart = email?.substringBefore("@")?.replace(Regex("[^A-Za-z]"), "")?.takeIf { it.isNotBlank() } ?: "Mobile"
        val serverResult = runCatching {
            serverApi.ensurePatient(
                ServerEnsurePatientRequest(
                    patientId = pid,
                    firstName = namePart,
                    lastName = "User",
                    dateOfBirth = "1970-01-01"
                )
            )
        }.getOrNull()

        if (serverResult?.isSuccessful == true) return true

        // Fallback only: keep direct Supabase upsert for local/dev builds.
        val existing = runCatching { api.getPatient("eq.$pid") }.getOrNull()
        if (existing?.isSuccessful == true && !existing.body().isNullOrEmpty()) return true
        val inserted = runCatching { api.upsertPatient(PatientUpsert(patientId = pid, firstName = namePart, lastName = "User")) }.getOrNull()
        return inserted?.isSuccessful == true || runCatching { api.getPatient("eq.$pid").body().orEmpty().isNotEmpty() }.getOrDefault(false)
    }

    private suspend fun loadStatus(date: String) {
        if (pid.isBlank()) return
        val filter = "eq.$pid"
        _profile.value = runCatching { api.getProfile(filter).takeIf { it.isSuccessful }?.body()?.firstOrNull() }.getOrNull() ?: _profile.value
        val weights = runCatching { api.getWeightDay(filter) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body() ?: _weightHistory.value
        val symptoms = runCatching { api.getSymptomLogs(filter) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body() ?: _symptomHistory.value
        val fetchedVitals = runCatching { api.getBpEvents(filter) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body() ?: _bpHistory.value
        // The server insert can become visible a fraction later. Keep the optimistic
        // selected-day value instead of making the form appear to lose the save.
        val optimisticVital = _bpHistory.value.firstOrNull {
            it.reading_date == date || it.recorded_at?.take(10) == date
        }
        val vitals = if (
            optimisticVital != null && fetchedVitals.none {
                it.reading_date == date || it.recorded_at?.take(10) == date
            }
        ) listOf(optimisticVital) + fetchedVitals else fetchedVitals
        _weightHistory.value = weights
        _symptomHistory.value = symptoms
        _bpHistory.value = vitals
        val hasWeight = weights.any { it.date == date }
        val hasSymptoms = symptoms.any { it.date == date || it.logged_at?.take(10) == date || it.created_at?.take(10) == date }
        val hasVitals = vitals.any { it.reading_date == date || it.recorded_at?.take(10) == date }
        _status.value = SelfCheckDailyStatus(hasWeight, hasSymptoms, hasVitals)
    }

    fun refreshStatus(date: String) = viewModelScope.launch { loadStatus(date) }

    private suspend fun encodeOcrImage(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = if (longest > 1280) 1280f / longest else 1f
        val prepared = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap

        val bytes = ByteArrayOutputStream().use { stream ->
            prepared.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            stream.toByteArray()
        }
        if (prepared !== bitmap) prepared.recycle()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun ocrRetryMessage() = AppLanguage.text("Couldn't read confidently. Adjust the display frame or enter the numbers manually.", "Bacaan tidak jelas. Laraskan bingkai paparan atau masukkan nombor secara manual.", "无法可靠识别，请调整显示屏边框或手动输入数字。", "நம்பகமாகப் படிக்க முடியவில்லை. திரைச் சட்டத்தைச் சரிசெய்யுங்கள் அல்லது எண்களை கைமுறையாக உள்ளிடுங்கள்.")

    private suspend fun websiteOcrImage(bitmap: Bitmap): MultipartBody.Part = withContext(Dispatchers.Default) {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1); val scale = if (longest > 1600) 1600f / longest else 1f
        val prepared = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val bytes = ByteArrayOutputStream().use { stream -> prepared.compress(Bitmap.CompressFormat.JPEG, 94, stream); stream.toByteArray() }
        if (prepared !== bitmap) prepared.recycle()
        MultipartBody.Part.createFormData("image", "machine-reading.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
    }

    fun smartScanWeight(bitmap: Bitmap) = viewModelScope.launch {
        if (pid.isBlank()) {
            _msg.value = AppLanguage.text("Please login again.", "Sila log masuk semula.", "请重新登录。", "மீண்டும் உள்நுழையவும்.")
            return@launch
        }

        // OCR progress is now shown using the blocking loading screen instead of
        // red status text below the Self-Check graphs.
        _msg.value = null
        _ocrFailure.value = null
        _ocrLoadingLabel.value = AppLanguage.text(
            "Checking weight...",
            "Menyemak berat...",
            "正在检查体重……",
            "எடையைச் சரிபார்க்கிறது..."
        )
        try {
            val response = kotlinx.coroutines.withTimeoutOrNull(20000) {
                serverApi.scanWeightImage(websiteOcrImage(bitmap), pid.toRequestBody("text/plain".toMediaType()))
            } ?: throw IllegalStateException("OCR timed out")
            val body = response.body()
            val value = (body?.weight ?: body?.detectedWeight)?.toDoubleOrNull()
            if (response.isSuccessful && value != null && value in 20.0..300.0) {
                _smartWeightOcr.value = SmartWeightOcrResult(value, 0.9)
            }
            else { _ocrFailure.value = ocrRetryMessage() }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
        } catch (_: Exception) {
            _ocrFailure.value = ocrRetryMessage()
        } finally {
            _ocrLoadingLabel.value = null
        }
    }

    fun smartScanBloodPressure(bitmap: Bitmap) = viewModelScope.launch {
        if (pid.isBlank()) {
            _msg.value = AppLanguage.text("Please login again.", "Sila log masuk semula.", "请重新登录。", "மீண்டும் உள்நுழையவும்.")
            return@launch
        }

        _msg.value = null
        _ocrFailure.value = null
        _ocrLoadingLabel.value = AppLanguage.text(
            "Checking vital readings...",
            "Menyemak bacaan vital...",
            "正在检查生命体征……",
            "உயிரளவு மதிப்புகளைச் சரிபார்க்கிறது..."
        )
        try {
            val response = kotlinx.coroutines.withTimeoutOrNull(90000) {
                ocrApi.scanBloodPressureImage(websiteOcrImage(bitmap), pid.toRequestBody("text/plain".toMediaType()))
            } ?: throw IllegalStateException("OCR timed out")
            val body = response.body()
            val sys = body?.sys?.toIntOrNull()
            val dia = body?.dia?.toIntOrNull()
            val pulse = body?.pulse?.toIntOrNull()
            // Keep genuinely low readings such as the supplied 60/38/56 sample.
            // They must be saved so the health-alert logic can warn the patient;
            // OCR validation should reject impossible digits, not real danger signs.
            val valid = sys != null && dia != null && pulse != null &&
                sys in 40..260 && dia in 25..160 && pulse in 30..220 && sys > dia
            if (response.isSuccessful && valid) {
                _smartBpOcr.value = SmartBpOcrResult(sys!!, dia!!, pulse!!, 0.9, annotatedImage = body?.annotatedImage)
            }
            else { _ocrFailure.value = ocrRetryMessage() }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
        } catch (_: Exception) {
            _ocrFailure.value = ocrRetryMessage()
        } finally {
            _ocrLoadingLabel.value = null
        }
    }

    fun saveWeight(date: String, kg: Double) = viewModelScope.launch {
        if (pid.isBlank()) {
            _msg.value = AppLanguage.text("Please login again.", "Sila log masuk semula.", "请重新登录。", "மீண்டும் உள்நுழையவும்.")
            return@launch
        }

        _savingLabel.value = AppLanguage.text(
            "Saving weight...",
            "Menyimpan berat...",
            "正在保存体重……",
            "எடை சேமிக்கப்படுகிறது..."
        )
        _msg.value = null
        try {
            if (!ensurePatientRow()) {
                _msg.value = AppLanguage.text(
                    "Unable to prepare your patient record. Please contact the administrator.",
                    "Tidak dapat menyediakan rekod pesakit anda. Sila hubungi pentadbir.",
                    "无法准备患者记录，请联系管理员。",
                    "நோயாளர் பதிவைத் தயாரிக்க முடியவில்லை. நிர்வாகியைத் தொடர்பு கொள்ளவும்."
                )
                return@launch
            }
            val wasUpdate = _status.value.hasWeight
            // public.weight_sample stores one kg reading with a timestamp.
            // Noon keeps one predictable entry for each selected calendar day.
            val record = WeightSampleInsert(
                patient_id = pid, time_ts = "${date}T12:00:00+08:00",
                recorded_at = OffsetDateTime.now().toString(),
                record_uid = "$pid|weight|$date", kg = kg
            )
            val updated = api.updateWeightSample("eq.$pid", "eq.${record.record_uid}", mapOf("kg" to kg))
            val r: retrofit2.Response<*> = if (!updated.isSuccessful) updated
                else if (!updated.body().isNullOrEmpty()) updated
                else api.insertWeightDay(record)

            if (r.isSuccessful) {
                _weightHistory.update { rows ->
                    listOf(WeightDay(pid, date, kg, kg, kg)) + rows.filterNot { it.date == date }
                }
                _status.update { it.copy(hasWeight = true) }
                _msg.value = if (wasUpdate) {
                    AppLanguage.text("Weight updated.", "Berat dikemas kini.", "体重已更新。", "எடை புதுப்பிக்கப்பட்டது.")
                } else {
                    AppLanguage.text("Weight saved.", "Berat disimpan.", "体重已保存。", "எடை சேமிக்கப்பட்டது.")
                }
                _successEvent.value += 1
                _measurementSavedEvent.value += 1
                loadStatus(date)
            } else {
                _msg.value = UserFacingError.http(r.code(), Action.SAVE)
            }
        } catch (e: Exception) {
            _msg.value = UserFacingError.from(e, Action.SAVE)
        } finally {
            _savingLabel.value = null
        }
    }

    fun saveSymptoms(date: String, values: Map<String, Int>) = viewModelScope.launch {
        if (pid.isBlank()) {
            _msg.value = AppLanguage.text("Please login again.", "Sila log masuk semula.", "请重新登录。", "மீண்டும் உள்நுழையவும்.")
            return@launch
        }

        _savingLabel.value = AppLanguage.text(
            "Saving symptoms...",
            "Menyimpan simptom...",
            "正在保存症状……",
            "அறிகுறிகள் சேமிக்கப்படுகின்றன..."
        )
        _msg.value = null
        try {
            if (!ensurePatientRow()) {
                _msg.value = AppLanguage.text(
                    "Unable to prepare your patient record. Please contact the administrator.",
                    "Tidak dapat menyediakan rekod pesakit anda. Sila hubungi pentadbir.",
                    "无法准备患者记录，请联系管理员。",
                    "நோயாளர் பதிவைத் தயாரிக்க முடியவில்லை. நிர்வாகியைத் தொடர்பு கொள்ளவும்."
                )
                return@launch
            }
            val existing = _symptomHistory.value.firstOrNull {
                it.date == date || it.logged_at?.take(10) == date || it.created_at?.take(10) == date
            }
            val updateBody = mapOf<String, Any?>(
                "cough" to (values["cough"] ?: 0),
                "sob_activity" to (values["breathlessness"] ?: 0),
                "leg_swelling" to (values["swelling"] ?: 0),
                "abd_discomfort" to (values["abdomen"] ?: 0),
                "orthopnea" to (values["sleeping"] ?: 0),
                "notes" to values.entries.joinToString(", ") { "${it.key}: ${it.value}/5" }
            )
            var saved = false
            if (existing != null) {
                val update = if (!existing.id.isNullOrBlank()) {
                    api.updateSymptomLogById(
                        id = "eq.${existing.id}",
                        body = updateBody
                    )
                } else {
                    api.updateSymptomLog(
                        patientId = "eq.$pid",
                        date = "eq.$date",
                        body = updateBody
                    )
                }
                saved = update.isSuccessful
            }
            if (!saved) {
                val timeTs = "${date}T12:00:00"
                val response = serverApi.addSymptomLog(
                    ServerSymptomRequest(
                        patientId = pid,
                        timeTs = timeTs,
                        cough = values["cough"] ?: 0,
                        breathlessness = values["breathlessness"] ?: 0,
                        swelling = values["swelling"] ?: 0,
                        abdomen = values["abdomen"] ?: 0,
                        sleeping = values["sleeping"] ?: 0,
                        notes = values.entries.joinToString(", ") { "${it.key}: ${it.value}/5" },
                        recordUid = "$pid|symptom|$date"
                    )
                )
                saved = response.isSuccessful
                if (!saved) {
                    _msg.value = UserFacingError.http(response.code(), Action.SAVE)
                }
            }
            if (saved) {
                _msg.value = if (existing == null) {
                    AppLanguage.text("Symptoms saved.", "Simptom disimpan.", "症状已保存。", "அறிகுறிகள் சேமிக்கப்பட்டன.")
                } else {
                    AppLanguage.text("Symptoms updated.", "Simptom dikemas kini.", "症状已更新。", "அறிகுறிகள் புதுப்பிக்கப்பட்டன.")
                }
                _successEvent.value += 1
                SelfCheckAlertNotifier.notifySymptoms(appContext, pid, values)
                loadStatus(date)
            }
        } catch (e: Exception) {
            _msg.value = UserFacingError.from(e, Action.SAVE)
        } finally {
            _savingLabel.value = null
        }
    }

    fun saveVitals(date: String, sys: Int, dia: Int, pulse: Int) = viewModelScope.launch {
        if (pid.isBlank()) {
            _msg.value = AppLanguage.text("Please login again.", "Sila log masuk semula.", "请重新登录。", "மீண்டும் உள்நுழையவும்.")
            return@launch
        }
        if (sys !in 0..260 || dia !in 0..160 || pulse !in 0..220 || sys <= dia) {
            _msg.value = AppLanguage.text(
                "Please enter valid SYS, DIA and Pulse values. SYS must be higher than DIA.",
                "Sila masukkan nilai SYS, DIA dan Nadi yang sah. SYS mesti lebih tinggi daripada DIA.",
                "请输入有效的 SYS、DIA 和脉搏数值，SYS 必须高于 DIA。",
                "சரியான SYS, DIA மற்றும் நாடித்துடிப்பு மதிப்புகளை உள்ளிடவும். SYS, DIA-வை விட அதிகமாக இருக்க வேண்டும்."
            )
            return@launch
        }

        _savingLabel.value = AppLanguage.text(
            "Saving vital readings...",
            "Menyimpan bacaan vital...",
            "正在保存生命体征……",
            "உயிரளவுகள் சேமிக்கப்படுகின்றன..."
        )
        _msg.value = null
        try {
            if (!ensurePatientRow()) {
                _msg.value = AppLanguage.text(
                    "Unable to prepare your patient record. Please contact the administrator.",
                    "Tidak dapat menyediakan rekod pesakit anda. Sila hubungi pentadbir.",
                    "无法准备患者记录，请联系管理员。",
                    "நோயாளர் பதிவைத் தயாரிக்க முடியவில்லை. நிர்வாகியைத் தொடர்பு கொள்ளவும்."
                )
                return@launch
            }
            val nowTime = java.time.LocalTime.now().withNano(0).toString()
            val existing = _bpHistory.value.firstOrNull {
                it.reading_date == date || it.recorded_at?.take(10) == date
            }
            val saved = if (existing != null) {
                val body = mapOf<String, Any?>(
                    "systolic" to sys,
                    "diastolic" to dia,
                    "pulse" to pulse,
                    "reading_date" to date,
                    "reading_time" to nowTime
                )
                val response = if (!existing.id.isNullOrBlank()) {
                    api.updateBpReadingById("eq.${existing.id}", body)
                } else {
                    api.updateBpReadingByDate("eq.$pid", "eq.$date", body)
                }
                response.isSuccessful
            } else {
                serverApi.addManualBp(
                    ServerBpManualRequest(
                        value1 = sys,
                        value2 = dia,
                        value3 = pulse,
                        patientId = pid,
                        timeTs = "${date}T$nowTime"
                    )
                ).isSuccessful
            }
            if (!saved) {
                _msg.value = AppLanguage.text(
                    "Vitals could not be saved. Please try again.",
                    "Bacaan vital tidak dapat disimpan. Sila cuba lagi.",
                    "无法保存生命体征，请重试。",
                    "உயிரளவுகளைச் சேமிக்க முடியவில்லை. மீண்டும் முயற்சிக்கவும்."
                )
                return@launch
            }

            val optimistic = BpEvent(
                id = existing?.id,
                patient_id = pid,
                systolic = sys,
                diastolic = dia,
                pulse = pulse,
                reading_date = date,
                reading_time = nowTime,
                recorded_at = existing?.recorded_at ?: "${date}T$nowTime",
                notes = existing?.notes
            )
            _bpHistory.update { rows ->
                listOf(optimistic) + rows.filterNot {
                    it.reading_date == date || it.recorded_at?.take(10) == date
                }
            }
            _status.update { it.copy(hasVitals = true) }
            _msg.value = if (existing == null) {
                AppLanguage.text("Vitals saved.", "Bacaan vital disimpan.", "生命体征已保存。", "உயிரளவுகள் சேமிக்கப்பட்டன.")
            } else {
                AppLanguage.text("Vitals updated.", "Bacaan vital dikemas kini.", "生命体征已更新。", "உயிரளவுகள் புதுப்பிக்கப்பட்டன.")
            }
            _successEvent.value += 1
            _measurementSavedEvent.value += 1
            loadStatus(date)
            SelfCheckAlertNotifier.notifyBloodPressure(appContext, pid, sys, dia, pulse)
        } catch (e: Exception) {
            _msg.value = UserFacingError.from(e, Action.SAVE)
        } finally {
            _savingLabel.value = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfCheckFullScreen(onBack: () -> Unit, focus: String? = null, vm: SelfCheckViewModel = hiltViewModel()) {
    val ms = AppLanguage.useMalay
    val msg by vm.msg.collectAsState()
    val status by vm.status.collectAsState()
    val successEvent by vm.successEvent.collectAsState()
    val measurementSavedEvent by vm.measurementSavedEvent.collectAsState()
    var handledSuccessEvent by remember { mutableIntStateOf(0) }
    var handledMeasurementSavedEvent by remember { mutableIntStateOf(0) }
    var showDataCollected by remember { mutableStateOf(false) }
    val weightHistory by vm.weightHistory.collectAsState()
    val profile by vm.profile.collectAsState()
    val bpHistory by vm.bpHistory.collectAsState()
    val symptomHistory by vm.symptomHistory.collectAsState()
    val smartWeightOcr by vm.smartWeightOcr.collectAsState()
    val smartBpOcr by vm.smartBpOcr.collectAsState()
    val savingLabel by vm.savingLabel.collectAsState()
    val ocrLoadingLabel by vm.ocrLoadingLabel.collectAsState()
    val ocrFailure by vm.ocrFailure.collectAsState()
    val today = LocalDate.now().toString()
    var date by remember { mutableStateOf(today) }
    var weight by remember { mutableStateOf("") }
    var sys by remember { mutableStateOf("") }
    var dia by remember { mutableStateOf("") }
    var pulse by remember { mutableStateOf("") }
    var localOcrLoadingLabel by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(focus) {
        val target = when (focus) { "bp" -> 2; "weight" -> 3; "symptom" -> 4; else -> null } ?: return@LaunchedEffect
        kotlinx.coroutines.delay(220)
        listState.animateScrollToItem(target)
    }

    LaunchedEffect(successEvent) {
        if (successEvent > handledSuccessEvent) {
            handledSuccessEvent = successEvent
            showDataCollected = true
        }
    }
    LaunchedEffect(measurementSavedEvent) {
        if (measurementSavedEvent > handledMeasurementSavedEvent) {
            handledMeasurementSavedEvent = measurementSavedEvent
            showDataCollected = true
        }
    }
    var symptoms by remember { mutableStateOf(mapOf("breathlessness" to 0, "swelling" to 0, "sleeping" to 0, "cough" to 0, "abdomen" to 0)) }

    LaunchedEffect(date) { vm.refreshStatus(date) }

    LaunchedEffect(smartWeightOcr?.eventId) {
        smartWeightOcr?.let { result ->
            weight = String.format(Locale.US, "%.2f", result.valueKg).trimEnd('0').trimEnd('.')
        }
    }

    var scanPreview by remember { mutableStateOf<String?>(null) }
    scanPreview?.let { encoded ->
        BpScanPreview(encoded, onDismiss = { scanPreview = null })
    }
    LaunchedEffect(smartBpOcr?.eventId) {
        smartBpOcr?.let { result ->
            sys = result.systolic.toString()
            dia = result.diastolic.toString()
            pulse = result.pulse.toString()
            scanPreview = result.annotatedImage
        }
    }

    // When the patient returns to a date that already has data, load those values
    // into the form so a second save is a true edit instead of an accidental reset.
    LaunchedEffect(date, weightHistory, symptomHistory, bpHistory) {
        val savedWeight = weightHistory.firstOrNull { it.date == date }?.kg_avg
        weight = savedWeight?.let {
            String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.')
        }.orEmpty()

        val savedSymptoms = symptomHistory.firstOrNull {
            it.date == date || it.logged_at?.take(10) == date || it.created_at?.take(10) == date
        }
        symptoms = savedSymptoms?.let { saved ->
            mapOf(
                "breathlessness" to (saved.sob_activity ?: 0),
                "swelling" to (saved.leg_swelling ?: 0),
                "sleeping" to (saved.orthopnea ?: 0),
                "cough" to (saved.cough ?: 0),
                "abdomen" to (saved.abd_discomfort ?: 0)
            )
        } ?: mapOf(
            "breathlessness" to 0,
            "swelling" to 0,
            "sleeping" to 0,
            "cough" to 0,
            "abdomen" to 0
        )

        val savedVitals = bpHistory.firstOrNull {
            it.reading_date == date || it.recorded_at?.take(10) == date
        }
        sys = savedVitals?.systolic?.toString() ?: ""
        dia = savedVitals?.diastolic?.toString() ?: ""
        pulse = savedVitals?.pulse?.toString().orEmpty()
    }

    Scaffold(topBar = { ScreenTopBar(AppLanguage.text("My Self-Check", "Pemeriksaan Kendiri", "我的自我检查", "என் சுய பரிசோதனை"), onBack, Green) { vm.refreshStatus(date) } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DatePickerInput(
                    label = AppLanguage.text("Date", "Tarikh"),
                    selectedDate = date,
                    onDateSelected = { date = it }
                )
            }

            item {
                DailyStatusCard(status, ms)
            }

            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(AppLanguage.text("Vital Tracker", "Penjejak Vital"), fontWeight = FontWeight.Bold)
                        // Healthy choices appear first; low patient readings remain selectable down to zero.
                        NumberRollFieldSimple(
                            value = sys,
                            values = ((0..79) + (80..139) + (140..260)).toList(),
                            label = "SYS",
                            onChange = { sys = it.toString() }
                        )

                        NumberRollFieldSimple(
                            value = dia,
                            values = ((0..49) + (50..89) + (90..160)).toList(),
                            label = "DIA",
                            onChange = { dia = it.toString() }
                        )

                        NumberRollFieldSimple(
                            value = pulse,
                            values = ((0..59) + (60..100) + (101..220)).toList(),
                            label = AppLanguage.text("Pulse", "Nadi / Pulse"),
                            onChange = { pulse = it.toString() }
                        )
                        BloodPressureMachineScanButtons(
                            onBloodPressureDetected = { detectedSys, detectedDia, detectedPulse ->
                                sys = detectedSys.toString()
                                dia = detectedDia.toString()
                                pulse = detectedPulse.toString()
                            },
                            onMessage = { },
                            onSmartFallback = vm::smartScanBloodPressure,
                            onLoadingChange = { loading ->
                                localOcrLoadingLabel = if (loading) {
                                    AppLanguage.text(
                                        "Scanning vital readings...",
                                        "Mengimbas bacaan vital...",
                                        "正在识别生命体征……",
                                        "உயிரளவு மதிப்புகளை ஸ்கேன் செய்கிறது..."
                                    )
                                } else null
                            }
                        )
                        Button(
                            onClick = { vm.saveVitals(date, sys.toIntOrNull() ?: 0, dia.toIntOrNull() ?: 0, pulse.toIntOrNull() ?: 0) },
                            enabled = savingLabel == null &&
                                (sys.toIntOrNull() ?: -1) in 0..260 &&
                                (dia.toIntOrNull() ?: -1) in 0..160 &&
                                (pulse.toIntOrNull() ?: -1) in 0..220 &&
                                (sys.toIntOrNull() ?: 0) > (dia.toIntOrNull() ?: 0),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (status.hasVitals) {
                                    AppLanguage.text("Update Vitals", "Kemas Kini Bacaan", "更新生命体征", "உயிரளவுகளை புதுப்பிக்கவும்")
                                } else {
                                    AppLanguage.text("Save Vitals", "Simpan Bacaan", "保存生命体征", "உயிரளவுகளை சேமிக்கவும்")
                                }
                            )
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(AppLanguage.text("Daily Weight (kg)", "Berat Harian (kg)"), fontWeight = FontWeight.Bold)
                        OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(), label = { Text(AppLanguage.text("Type your weight (kg)", "Taip berat anda (kg)")) }, singleLine = true)
                        WeightMachineScanButtons(
                            onWeightDetected = { detected -> weight = String.format(Locale.US, "%.2f", detected).trimEnd('0').trimEnd('.') },
                            onMessage = { },
                            onSmartFallback = vm::smartScanWeight,
                            onLoadingChange = { loading ->
                                localOcrLoadingLabel = if (loading) {
                                    AppLanguage.text(
                                        "Scanning weight...",
                                        "Mengimbas berat...",
                                        "正在识别体重……",
                                        "எடையை ஸ்கேன் செய்கிறது..."
                                    )
                                } else null
                            }
                        )
                        Button(
                            onClick = { weight.toDoubleOrNull()?.let { vm.saveWeight(date, it) } },
                            enabled = savingLabel == null && (weight.toDoubleOrNull() ?: 0.0) in 20.0..300.0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (status.hasWeight) AppLanguage.text("Update Weight", "Kemas Kini Berat") else AppLanguage.text("Save Weight", "Catat Berat")) }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(AppLanguage.text("Symptom Assessment", "Penilaian Simptom"), fontWeight = FontWeight.Bold)
                        Text(AppLanguage.text("Rate each symptom: 0 = No symptom, 1 = Mild, 5 = Severe", "Nilai setiap simptom: 0 = Tiada simptom, 1 = Ringan, 5 = Teruk"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val list = listOf(
                            "breathlessness" to (AppLanguage.text("More tired or short of breath during activity", "Lebih letih atau sesak nafas semasa aktiviti")),
                            "swelling" to (AppLanguage.text("More swelling in legs/ankles", "Kaki lebih bengkak")),
                            "sleeping" to (AppLanguage.text("Need more pillows or sitting up to sleep", "Guna lebih banyak bantal atau duduk semasa tidur")),
                            "cough" to (AppLanguage.text("More coughing", "Lebih banyak batuk")),
                            "abdomen" to (AppLanguage.text("More abdominal discomfort/swelling", "Lebih tidak selesa/bengkak pada abdomen"))
                        )
                        list.forEach { (id, label) ->
                            SymptomSliderRow(label = label, value = symptoms[id] ?: 0, ms = ms) { newValue ->
                                symptoms = symptoms.toMutableMap().apply { put(id, newValue) }
                            }
                        }
                        val total = symptoms.values.sum()
                        val totalColor = when { total <= 5 -> Green; total <= 15 -> Orange; else -> Red }
                        Text("${AppLanguage.text("Total Symptom Score", "Jumlah Skor Simptom")}: $total / 25", color = totalColor, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = { vm.saveSymptoms(date, symptoms) },
                            enabled = savingLabel == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (status.hasSymptoms) AppLanguage.text("Update Symptoms", "Kemas Kini Simptom", "更新症状", "அறிகுறிகளை புதுப்பிக்கவும்") else AppLanguage.text("Save Symptoms", "Catat Simptom", "保存症状", "அறிகுறிகளை சேமிக்கவும்")) }
                    }
                }
            }

            item { SelfCheckGraphCard(weightHistory, bpHistory, symptomHistory, profile) }
            item { StatusText(msg) }
        }
    }

    val activeLoadingLabel = savingLabel ?: ocrLoadingLabel ?: localOcrLoadingLabel
    BlockingLoadingScreen(
        visible = activeLoadingLabel != null,
        title = activeLoadingLabel ?: AppLanguage.text(
            "Please wait...",
            "Sila tunggu...",
            "请稍候……",
            "தயவுசெய்து காத்திருக்கவும்..."
        )
    )

    DataCollectedSuccessScreen(
        visible = showDataCollected,
        onDismiss = { showDataCollected = false }
    )

    OcrFailureScreen(
        message = ocrFailure,
        onEnterManually = vm::dismissOcrFailure
    )
}


@Composable
private fun SelfCheckGraphCard(weights: List<WeightDay>, bpEvents: List<BpEvent>, symptoms: List<SymptomLog>, profile: ProfileRow?) {
    val ms = AppLanguage.useMalay
    val weightData = weights
        .filter { isInLatestSevenDays(it.date) }
        .sortedBy { it.date }
    val bpData = bpEvents
        .filter { isInLatestSevenDays(it.reading_date ?: it.recorded_at) }
        .sortedWith(compareBy<BpEvent> { it.reading_date ?: it.recorded_at?.take(10).orEmpty() }.thenBy { it.reading_time ?: it.recorded_at.orEmpty() })
    val symptomData = symptoms
        .filter { isInLatestSevenDays(it.date ?: it.logged_at ?: it.created_at) }
        .sortedBy { it.date ?: it.logged_at?.take(10) ?: it.created_at?.take(10).orEmpty() }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertChart, null, tint = Green)
                Spacer(Modifier.width(8.dp))
                Text(AppLanguage.text("Self-Check Graphs", "Graf Pemeriksaan Kendiri"), fontWeight = FontWeight.Bold)
            }
            Text(AppLanguage.text("Blood Pressure — SYS / DIA (mmHg)", "Tekanan Darah — SYS / DIA (mmHg)"), fontWeight = FontWeight.Medium)
            SimpleTripleLineChart(
                firstValues = bpData.ifEmpty { List(7) { BpEvent(patient_id = "", systolic = 0, diastolic = 0, pulse = 0) } }.map { (it.systolic ?: 0).toFloat() },
                secondValues = bpData.ifEmpty { List(7) { BpEvent(patient_id = "", systolic = 0, diastolic = 0, pulse = 0) } }.map { (it.diastolic ?: 0).toFloat() },
                thirdValues = emptyList(),
                labels = bpData.ifEmpty { List(7) { BpEvent(patient_id = "", systolic = 0, diastolic = 0, pulse = 0) } }.map { formatAlphabeticalDate(it.reading_date ?: it.recorded_at?.take(10).orEmpty()) },
                maxValue = 220f,
                // Keep data lines separate from the red/yellow/green alert zones.
                // Dark purple and deep blue also keep SYS and DIA clearly distinct.
                firstColor = Color(0xFF6A1B9A),
                secondColor = Color(0xFF263238),
                thirdColor = Color(0xFF00796B),
                emptyText = AppLanguage.text("No BP data yet.", "Tiada data BP lagi."),
                references = listOfNotNull(
                    profile?.systolic_bp?.let { "SYS " + AppLanguage.text("baseline", "garis dasar") to it.toFloat() },
                    profile?.diastolic_bp?.let { "DIA " + AppLanguage.text("baseline", "garis dasar") to it.toFloat() }
                )
            )
            Text(AppLanguage.text("Pulse Trend (bpm)", "Trend Nadi (bpm)"), fontWeight = FontWeight.Medium)
            SimpleLineChart(
                values = bpData.map { (it.pulse ?: 0).toFloat() },
                labels = bpData.map { formatAlphabeticalDate(it.reading_date ?: it.recorded_at?.take(10).orEmpty()) },
                maxValue = maxOf(160f, bpData.maxOfOrNull { (it.pulse ?: 0).toFloat() } ?: 0f),
                emptyText = AppLanguage.text("No pulse data yet.", "Tiada data nadi lagi."),
                references = listOfNotNull(profile?.heart_rate?.let { AppLanguage.text("Baseline (bpm)", "Garis dasar (bpm)") to it.toFloat() }),
                normalBands = listOf(Triple(AppLanguage.text("Normal pulse: 60–100 bpm", "Nadi normal: 60–100 bpm"), 60f, 100f))
            )

            Text(AppLanguage.text("Weight Trend (kg)", "Trend Berat (kg)"), fontWeight = FontWeight.Medium)
            val recordedWeights = weightData.mapNotNull { (it.kg_avg ?: it.kg_max ?: it.kg_min)?.toFloat() }
            val dryWeight = profile?.dry_weight?.toFloat()
            // Use a focused range around the dry-weight baseline. This makes a 0.5–2 kg
            // change visible while still expanding automatically if a reading is outside it.
            val weightGraphMin = maxOf(0f, minOf(
                recordedWeights.minOrNull()?.minus(1f) ?: Float.MAX_VALUE,
                dryWeight?.minus(5f) ?: Float.MAX_VALUE
            )).takeIf { it != Float.MAX_VALUE } ?: 0f
            val weightGraphMax = maxOf(
                recordedWeights.maxOrNull()?.plus(1f) ?: 1f,
                dryWeight?.plus(5f) ?: 1f,
                weightGraphMin + 1f
            )
            SimpleBarChart(
                values = weightData.ifEmpty { List(7) { WeightDay("", "", 0.0, 0.0, 0.0) } }.map { (it.kg_avg ?: it.kg_max ?: it.kg_min ?: 0.0).toFloat() },
                labels = weightData.ifEmpty { List(7) { WeightDay("", "", 0.0, 0.0, 0.0) } }.map { formatAlphabeticalDate(it.date) },
                maxValue = weightGraphMax,
                minValue = weightGraphMin,
                emptyText = AppLanguage.text("No weight data yet.", "Tiada data berat lagi."),
                decimalPlaces = 2,
                references = listOfNotNull(profile?.dry_weight?.let { AppLanguage.text("Baseline (kg)", "Garis dasar (kg)") to it.toFloat() }),
                normalBands = listOfNotNull(profile?.dry_weight?.let { Triple(AppLanguage.text("Normal: dry weight ±2 kg", "Normal: berat kering ±2 kg"), (it-2).toFloat(), (it+2).toFloat()) })
            )


            Text(AppLanguage.text("Total Symptom Score", "Jumlah Skor Simptom"), fontWeight = FontWeight.Medium)
            val symptomChartRows = symptomData.ifEmpty { List(7) { SymptomLog() } }
            SimpleBarChart(
                values = symptomChartRows.map { ((it.cough ?: 0) + (it.sob_activity ?: 0) + (it.leg_swelling ?: 0) + (it.abd_discomfort ?: 0) + (it.orthopnea ?: 0)).toFloat() },
                labels = symptomChartRows.map { formatAlphabeticalDate(it.date ?: it.logged_at?.take(10) ?: it.created_at?.take(10).orEmpty()) },
                maxValue = 25f,
                zones = listOf(
                    ChartZone(0f, 5f, Green),
                    ChartZone(5f, 12f, Color(0xFFFFC107)),
                    ChartZone(12f, 25f, Red)
                ),
                references = listOf(AppLanguage.text("Normal below 5", "Normal bawah 5") to 5f),
                emptyText = AppLanguage.text("No symptom data yet.", "Tiada data simptom lagi."),
                pointPresence = if (symptomData.isEmpty()) List(7) { false } else List(symptomData.size) { true }
            )
        }
    }
}

@Composable
private fun SimpleTripleLineChart(
    firstValues: List<Float>,
    secondValues: List<Float>,
    thirdValues: List<Float>,
    labels: List<String>,
    maxValue: Float,
    firstColor: Color,
    secondColor: Color,
    thirdColor: Color,
    emptyText: String,
    references: List<Pair<String, Float>> = emptyList()
) {
    val data1 = if (firstValues.isEmpty()) List(7) { 0f } else firstValues
    val data2 = if (secondValues.isEmpty()) List(data1.size) { 0f } else secondValues
    val data3 = if (thirdValues.isEmpty()) List(data1.size) { 0f } else thirdValues
    val safeLabels = if (labels.isEmpty()) List(data1.size) { "-" } else labels
    val safeMax = maxOf(maxValue, (data1 + data2).maxOrNull()?.times(1.1f) ?: 0f, references.maxOfOrNull { it.second * 1.1f } ?: 0f).coerceAtLeast(1f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val hasData = data1.indices.any { (data1.getOrNull(it) ?: 0f) > 0f || (data2.getOrNull(it) ?: 0f) > 0f || (data3.getOrNull(it) ?: 0f) > 0f }
    val yTicks = (4 downTo 0).map { safeMax * it / 4f }

    Row(Modifier.fillMaxWidth().height(170.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.width(48.dp).fillMaxHeight().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            yTicks.forEach { Text(it.toInt().toString(), style = MaterialTheme.typography.labelSmall, color = axisTextColor) }
        }
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val chartTop = 8.dp.toPx()
            val chartBottom = size.height - 8.dp.toPx()
            val chartHeight = chartBottom - chartTop
            val stepX = size.width / data1.size.coerceAtLeast(1)
            // Keep the SYS/DIA plotting area consistent with the pulse graph.
            // The translucent green status bands are drawn on top of this base.
            drawRect(
                color = Color.White,
                topLeft = Offset(0f, chartTop),
                size = androidx.compose.ui.geometry.Size(size.width, chartHeight)
            )
            // Healthy BP bands: SYS 80–139, DIA 50–89 and pulse 60–100.
            listOf(
                Triple(80f, 139f, Color(0xFF2E9B57)),
                Triple(50f, 89f, Color(0xFF2E9B57)),
                Triple(60f, 100f, Color(0xFF2E9B57))
            ).forEach { (low, high, color) ->
                drawRect(color.copy(alpha = .30f), Offset(0f, chartBottom - chartHeight * high / safeMax), androidx.compose.ui.geometry.Size(size.width, chartHeight * (high-low) / safeMax))
            }
            for (i in 0..4) {
                val y = chartTop + (chartHeight / 4f) * i
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            fun drawSeries(data: List<Float>, color: Color) {
                var previous: Offset? = null
                data.forEachIndexed { index, value ->
                    val progress = (value / safeMax).coerceIn(0f, 1f)
                    val x = if (data.size <= 1) size.width / 2f else stepX * (index + 0.5f)
                    val y = chartBottom - (chartHeight * progress)
                    val current = Offset(x, y)
                    if (value > 0f) {
                        previous?.let { drawLine(color = color, start = it, end = current, strokeWidth = 3.dp.toPx()) }
                        drawCircle(color = color, radius = 3.8.dp.toPx(), center = current)
                        previous = current
                    } else previous = null
                }
            }
            drawSeries(data1, firstColor)
            drawSeries(data2, secondColor)
            if (thirdValues.isNotEmpty()) drawSeries(data3, thirdColor)

        }
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(56.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            safeLabels.forEach { label ->
                Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = axisTextColor, maxLines = 1)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("SYS", color = firstColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        Text("DIA", color = secondColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        if (thirdValues.isNotEmpty()) Text(AppLanguage.text("Pulse", "Nadi"), color = thirdColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
    if (!hasData) Text(emptyText, style = MaterialTheme.typography.bodySmall, color = axisTextColor)
}


@Composable
private fun NumberRollFieldSimple(
    value: String,
    values: List<Int>,
    label: String,
    onChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val normalIndex = when {
        label == "SYS" -> values.indexOfFirst { it in 80..139 }
        label == "DIA" -> values.indexOfFirst { it in 50..89 }
        else -> values.indexOfFirst { it in 60..100 }
    }.coerceAtLeast(0)

    LaunchedEffect(expanded, value) {
        if (expanded) {
            val selected = value.toIntOrNull()
            val index = selected
                ?.let { values.indexOf(it) }
                ?.takeIf { it >= 0 }
                ?: normalIndex

            listState.scrollToItem(index)
        }
    }

    OutlinedButton(
        onClick = { expanded = true },
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(value.ifBlank { "--" })
        Icon(Icons.Default.ArrowDropDown, null)
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(Modifier.fillMaxWidth(0.86f)) {
                Column(Modifier.padding(12.dp)) {
                    Text(label, fontWeight = FontWeight.Bold)

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    ) {
                        items(values, key = { it }) { number ->
                            DropdownMenuItem(
                                text = { Text(number.toString()) },
                                onClick = {
                                    onChange(number)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyStatusCard(status: SelfCheckDailyStatus, ms: Boolean) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                AppLanguage.text("Daily Check", "Pemeriksaan Harian", "每日检查", "தினசரி பரிசோதனை"),
                fontWeight = FontWeight.Bold,
                color = Color(0xFF33691E),
                style = MaterialTheme.typography.titleMedium
            )
            DailyCheckRow(AppLanguage.text("Weight", "Berat", "体重", "எடை"), status.hasWeight)
            DailyCheckRow(AppLanguage.text("Symptoms", "Simptom", "症状", "அறிகுறிகள்"), status.hasSymptoms)
            DailyCheckRow(AppLanguage.text("BP", "BP", "血压", "BP"), status.hasVitals)
        }
    }
}

@Composable
private fun DailyCheckRow(label: String, done: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (done) Green.copy(alpha = .14f) else Orange.copy(alpha = .12f)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(if (done) "✅" else "⏳", style = MaterialTheme.typography.headlineSmall)
            if (!done) {
                Spacer(Modifier.width(6.dp))
                Text(AppLanguage.text("Not done", "Belum siap", "未完成", "முடிக்கவில்லை"), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SymptomSliderRow(label: String, value: Int, ms: Boolean, onChange: (Int) -> Unit) {
    val color = symptomColor(value)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Surface(color = color, shape = RoundedCornerShape(999.dp)) { Text("$value", modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..5f,
            steps = 4,
            colors = SliderDefaults.colors(activeTrackColor = color, thumbColor = color)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall)
            Text(AppLanguage.text("Mild", "Ringan"), style = MaterialTheme.typography.labelSmall, color = Orange)
            Text(AppLanguage.text("Severe", "Teruk"), style = MaterialTheme.typography.labelSmall, color = Red)
        }
        Text("${AppLanguage.text("Current level", "Tahap semasa")}: ${symptomLabel(value, ms)}", color = color, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
    }
}

private fun symptomColor(value: Int): Color = when(value) {
    0 -> Color(0xFF22C55E)
    1 -> Color(0xFF84CC16)
    2 -> Color(0xFFEAB308)
    3 -> Color(0xFFF59E0B)
    4 -> Color(0xFFF97316)
    else -> Color(0xFFEF4444)
}

private fun symptomLabel(value: Int, ms: Boolean): String = when {
    value == 0 -> AppLanguage.text("No symptom", "Tiada simptom")
    value <= 2 -> AppLanguage.text("Mild", "Ringan")
    else -> AppLanguage.text("Severe", "Teruk")
}

data class LearningSubmoduleUi(
    val titleEn: String,
    val titleMs: String,
    val descriptionEn: String,
    val descriptionMs: String,
    val contentEn: String,
    val contentMs: String,
    val sourceUrl: String
)

data class LearningModuleUi(
    val id: String,
    val titleEn: String,
    val titleMs: String,
    val descriptionEn: String,
    val descriptionMs: String,
    val icon: ImageVector,
    val color: Color,
    val submodules: List<LearningSubmoduleUi>
)

data class EducationVideoUi(
    val key: String,
    val title: String,
    val desc: String,
    val url: String,
    val localRawResId: Int? = null,
    val captionResource: String? = null
)

@HiltViewModel
class EducationViewModel @Inject constructor(private val api: ApiService, session: SessionManager, @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context) : ViewModel() {
    companion object {
        private const val VIDEO_REWARD_COINS = 10
    }

    private val _rewards = MutableStateFlow<List<EducationVideoReward>>(emptyList())
    val rewards = _rewards.asStateFlow()
    private val _totalCoins = MutableStateFlow(0)
    val totalCoins = _totalCoins.asStateFlow()

    private val _claimingVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val claimingVideoIds = _claimingVideoIds.asStateFlow()

    private val _completedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val completedVideoIds = _completedVideoIds.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    // SessionManager stores the Supabase auth user UUID here. The website uses the same UUID as user_id.
    private var userId = ""
    private val completionPrefs = appContext.getSharedPreferences("education_completed", android.content.Context.MODE_PRIVATE)
    fun watchKey(videoId: String) = "$userId/$videoId"

    fun hasClaimedVideo(videoId: String): Boolean =
        _rewards.value.any { it.video_id == videoId }

    private fun loadCompletedVideos() {
        if (userId.isBlank()) return
        _completedVideoIds.value = completionPrefs.getStringSet(userId, emptySet()).orEmpty().toSet()
    }

    init {
        viewModelScope.launch {
            session.patientId.filterNotNull().collect {
                userId = it
                loadCompletedVideos()
                _rewards.value = emptyList()
                loadRewards()
            }
        }
    }

    fun loadRewards(showError: Boolean = true) = viewModelScope.launch {
        if (userId.isBlank()) return@launch
        try {
            val response = api.getEducationVideoRewards("eq.$userId")
            if (response.isSuccessful) {
                _rewards.value = response.body().orEmpty()
                val profileCoins = runCatching {
                    api.getProfile("eq.$userId").body().orEmpty().firstOrNull()?.coins
                }.getOrNull()
                _totalCoins.value = educationCoinTotal(profileCoins, _rewards.value)
            } else if (showError) {
                _message.value = AppLanguage.text(
                    "Coin history is temporarily unavailable. Please try again.",
                    "Sejarah syiling tidak dapat dimuatkan buat sementara waktu. Sila cuba lagi.",
                    "金币记录暂时无法加载，请重试。",
                    "நாணய வரலாறு தற்காலிகமாக கிடைக்கவில்லை. மீண்டும் முயற்சிக்கவும்."
                )
            }
        } catch (_: Exception) {
            if (showError) {
                _message.value = AppLanguage.text(
                    "Coin history is temporarily unavailable. Please try again.",
                    "Sejarah syiling tidak dapat dimuatkan buat sementara waktu. Sila cuba lagi.",
                    "金币记录暂时无法加载，请重试。",
                    "நாணய வரலாறு தற்காலிகமாக கிடைக்கவில்லை. மீண்டும் முயற்சிக்கவும்."
                )
            }
        }
    }

    fun markVideoCompleted(videoId: String) {
        loadCompletedVideos()
        _completedVideoIds.value = _completedVideoIds.value + videoId
        completionPrefs.edit().putStringSet(userId, _completedVideoIds.value).apply()
        _message.value = AppLanguage.text(
            "Video completed. You can now collect 10 coins.",
            "Video telah tamat. Anda kini boleh mengutip 10 syiling.",
            "视频已完成。您现在可以领取 10 枚金币。",
            "காணொளி முடிந்தது. இப்போது 10 நாணயங்களைப் பெறலாம்."
        )
    }

    fun claimReward(videoId: String) = viewModelScope.launch {
        if (userId.isBlank()) {
            _message.value = AppLanguage.text("Please login again.", "Sila log masuk semula.")
            return@launch
        }
        if (_claimingVideoIds.value.contains(videoId)) return@launch
        loadCompletedVideos()
        if (hasClaimedVideo(videoId)) {
            _message.value = AppLanguage.text("Coins already claimed for this video.", "Syiling untuk video ini telah dikutip.")
            return@launch
        }
        if (!_completedVideoIds.value.contains(videoId)) {
            _message.value = AppLanguage.text(
                "Please watch the video until it finishes before collecting coins.",
                "Sila tonton video sehingga tamat sebelum mengutip syiling.",
                "请观看视频至结束后再领取金币。",
                "நாணயங்களைப் பெறுவதற்கு முன் காணொளியை முழுமையாக பார்க்கவும்."
            )
            return@launch
        }

        _claimingVideoIds.value = _claimingVideoIds.value + videoId
        var rewardInserted = false
        try {
            // education_video_rewards is the source of truth for both web and mobile.
            // Once this row is inserted, the reward is considered collected even if
            // the optional cached balance in profiles cannot be updated.
            val insertResponse = api.insertEducationVideoReward(
                EducationVideoRewardInsert(
                    user_id = userId,
                    video_id = videoId,
                    coins_awarded = VIDEO_REWARD_COINS
                )
            )

            if (insertResponse.code() == 409) {
                _message.value = AppLanguage.text("Coins already claimed for this video.", "Syiling untuk video ini telah dikutip.")
                loadRewards(showError = false)
                return@launch
            }

            if (!insertResponse.isSuccessful) {
                _message.value = when (insertResponse.code()) {
                    401 -> AppLanguage.text("Your session has expired. Please login again.", "Sesi telah tamat. Sila log masuk semula.")
                    403 -> AppLanguage.text("Coin collection is temporarily unavailable. Please contact your care team.", "Kutipan syiling tidak tersedia buat sementara waktu. Sila hubungi pasukan penjagaan anda.")
                    else -> AppLanguage.text("Unable to collect coins right now. Please try again.", "Syiling tidak dapat dikutip sekarang. Sila cuba lagi.")
                }
                return@launch
            }

            rewardInserted = true
            val collectedReward = EducationVideoReward(
                user_id = userId,
                video_id = videoId,
                coins_awarded = VIDEO_REWARD_COINS,
                created_at = OffsetDateTime.now().toString()
            )
            _rewards.value = _rewards.value + collectedReward

            // Keep profiles.coins in sync when that row/policy is available.
            // This is intentionally best-effort: a profile cache problem must not
            // undo a successfully collected reward.
            runCatching {
                val profileResponse = api.getProfile("eq.$userId")
                val profile = profileResponse.body().orEmpty().firstOrNull()
                if (profileResponse.isSuccessful && profile != null) {
                    // A first-time video claim adds ten coins to the lifetime total.
                    val newCoinTotal = (profile.coins ?: 0) + VIDEO_REWARD_COINS
                    val balanceUpdate = api.updateProfile(
                        "eq.$userId",
                        mapOf("coins" to newCoinTotal)
                    )
                    if (balanceUpdate.isSuccessful) {
                        _totalCoins.value = newCoinTotal
                    }
                }
            }

            _message.value = AppLanguage.text("+10 coins collected.", "+10 syiling berjaya dikutip.")
            loadRewards(showError = false)
        } catch (_: Exception) {
            if (rewardInserted) {
                _message.value = AppLanguage.text("+10 coins collected.", "+10 syiling berjaya dikutip.")
                loadRewards(showError = false)
            } else {
                _message.value = AppLanguage.text(
                    "Unable to collect coins right now. Please check your internet connection and try again.",
                    "Syiling tidak dapat dikutip sekarang. Sila semak sambungan internet dan cuba lagi.",
                    "目前无法领取金币。请检查网络连接后重试。",
                    "இப்போது நாணயங்களைப் பெற முடியவில்லை. இணைய இணைப்பைச் சரிபார்த்து மீண்டும் முயற்சிக்கவும்."
                )
            }
        } finally {
            _claimingVideoIds.value = _claimingVideoIds.value - videoId
        }
    }
}

private fun educationModuleFor(video: EducationVideoUi): String {
    val title = video.title.lowercase(Locale.ROOT)
    return when {
        listOf("warning", "symptom", "feel sick", "weight gain").any(title::contains) -> "F"
        listOf("caregiver", "loneliness", "anxiety", "mental", "emotional").any(title::contains) -> "D"
        listOf("kidney", "diabetes", "cholesterol", "blood pressure", "aspirin", "statin").any(title::contains) -> "B"
        listOf("salt", "food", "diet", "sugar", "cooking", "oil", "exercise", "heart rate", "rehab", "resistance", "medicine", "ace inhibitor", "beta blocker", "sglt").any(title::contains) -> "C"
        else -> "A"
    }
}

@Composable
private fun ModuleVideoCard(
    video: EducationVideoUi,
    moduleColor: Color,
    claimed: Boolean,
    claiming: Boolean,
    completed: Boolean,
    onWatch: () -> Unit,
    onClaim: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, moduleColor.copy(alpha = .28f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (claimed) Icons.Default.CheckCircle else Icons.Default.PlayCircle, null, tint = if (claimed) Green else moduleColor, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(video.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
            }
            Text(video.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!claimed) {
                Text(
                    if (completed) AppLanguage.text("Completed — collect 10 coins.", "Selesai — kutip 10 syiling.") else AppLanguage.text("Watch to the end to unlock 10 coins.", "Tonton hingga tamat untuk membuka 10 syiling."),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (completed) Green else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onWatch, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (claimed) AppLanguage.text("Watch again", "Tonton semula") else AppLanguage.text("Watch", "Tonton"))
                }
                Button(
                    onClick = onClaim,
                    enabled = !claimed && !claiming && completed,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = moduleColor)
                ) {
                    Text(when { claimed -> AppLanguage.text("Claimed", "Sudah dikutip"); claiming -> AppLanguage.text("Collecting...", "Mengutip..."); completed -> AppLanguage.text("Collect +10", "Kutip +10"); else -> AppLanguage.text("Finish video", "Tamatkan video") }, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun EducationCoinCard(coins: Int) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(AppLanguage.text(
                                    "Coin Collection", "Kutipan Syiling",
                                    "金币收集", "நாணய சேகரிப்பு"
                                ), color = Color.White.copy(alpha = .78f))
                                Text("$coins", color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                                Text(AppLanguage.text(
                                    "Coins earned from education videos", "Syiling daripada video pendidikan",
                                    "通过教育视频获得的金币", "கல்வி வீடியோக்களிலிருந்து பெற்ற நாணயங்கள்"
                                ), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.MonetizationOn, null, tint = Color(0xFFFFD54F), modifier = Modifier.size(42.dp))
                        }
                    }
}

@Composable
fun EducationScreen(onBack: () -> Unit, vm: EducationViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val language = AppLanguage.current
    val videos = remember(language) { educationCatalogue(context).associateBy { it.key } }
    val modules = remember(language) { educationModuleGroups() }
    var expandedModules by remember { mutableStateOf(setOf("understanding")) }
    val rewards by vm.rewards.collectAsState()
    val claiming by vm.claimingVideoIds.collectAsState()
    val completed by vm.completedVideoIds.collectAsState()
    val coins by vm.totalCoins.collectAsState()
    val message by vm.message.collectAsState()
    var selected by remember { mutableStateOf<EducationVideoUi?>(null) }
    RefreshCoinsOnResume { vm.loadRewards(showError = false) }
    selected?.let { video ->
        OfflineEducationPlayer(video, vm.watchKey(video.key), { selected = null }, { vm.markVideoCompleted(video.key) })
    }
    Scaffold(topBar = { ScreenTopBar(AppLanguage.text("My Learning", "Pembelajaran Saya", "我的学习", "என் கற்றல்"), onBack, Blue) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                EducationCoinCard(coins)
                Spacer(Modifier.height(12.dp))
                Text(AppLanguage.text("Earn 10 coins once for each completed video.", "Dapatkan 10 syiling sekali bagi setiap video yang ditonton hingga tamat.", "每个视频完整观看后可领取一次10枚金币。", "ஒவ்வொரு காணொளியையும் முடித்து ஒருமுறை 10 நாணயங்கள் பெறுங்கள்."))
                message?.let { Text(it) }
            }
            modules.forEach { module ->
                item(key = "module-${module.id}") {
                    val expanded = module.id in expandedModules
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            expandedModules = if (expanded) expandedModules - module.id else expandedModules + module.id
                        }, shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Blue.copy(alpha = 0.10f)),
                        border = BorderStroke(1.dp, Blue.copy(alpha = 0.25f))
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(module.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(if (expanded) "−" else "+", Modifier.padding(start = 12.dp), fontSize = 26.sp, color = Blue)
                            }
                            Text(module.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (module.id in expandedModules) {
                    module.topics.forEachIndexed { topicIndex, topic ->
                        item(key = "${module.id}/$topicIndex/title") {
                            Text(topic.title, Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            if (topic.videoKeys.isEmpty()) Text(
                                AppLanguage.text("Video coming soon", "Video akan datang", "视频即将推出", "காணொளி விரைவில் வரும்"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        topic.videoKeys.forEach { videoKey ->
                            videos[videoKey]?.let { video ->
                                item(key = "${module.id}/$topicIndex/$videoKey") {
                                    ModuleVideoCard(video, Blue, rewards.any { it.video_id == video.key },
                                        video.key in claiming, video.key in completed,
                                        { selected = video }, { vm.claimReward(video.key) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(topBar = { ScreenTopBar(AppLanguage.text("Help & Support", "Bantuan & Sokongan"), onBack, Color(0xFF00897B)) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(AppLanguage.text("About MyHFGuard", "Tentang MyHFGuard"), fontWeight = FontWeight.Bold)
                        Text(AppLanguage.text(
                            "MyHFGuard helps heart failure patients monitor symptoms, manage reminders, record daily health data and learn self-care more easily.",
                            "MyHFGuard membantu pesakit kegagalan jantung memantau simptom, mengurus peringatan, merekod data kesihatan harian dan mempelajari penjagaan diri dengan lebih mudah."
                        ))
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(AppLanguage.text("Emergency Contact", "Hubungan Kecemasan"), fontWeight = FontWeight.Bold, color = Red)
                        Text(AppLanguage.text(
                            "If you have severe shortness of breath, chest pain, fainting or any urgent medical condition, please contact emergency services immediately. Do not rely on this app for urgent treatment.",
                            "Jika anda mengalami sesak nafas teruk, sakit dada, pengsan atau keadaan perubatan kecemasan, hubungi perkhidmatan kecemasan dengan segera. Jangan bergantung pada aplikasi ini untuk rawatan kecemasan."
                        ))
                        Button(onClick = { uri.openUri("tel:999") }, colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                            Text(AppLanguage.text("Call Emergency (999)", "Hubungi Kecemasan (999)"))
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(AppLanguage.text("Email Support", "Sokongan E-mel"), fontWeight = FontWeight.Bold)
                        Text(AppLanguage.text(
                            "For technical issues or general system support, contact the MyHFGuard support team by email.",
                            "Untuk masalah teknikal atau sokongan sistem, hubungi pasukan sokongan MyHFGuard melalui e-mel."
                        ))
                        Button(onClick = { uri.openUri("mailto:myhfguard.host@gmail.com") }) {
                            Text(AppLanguage.text("Email Support", "Sokongan E-mel"))
                        }
                        Text("myhfguard.host@gmail.com", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(AppLanguage.text("WhatsApp Support", "Sokongan WhatsApp"), fontWeight = FontWeight.Bold)
                        Text(AppLanguage.text(
                            "For quick communication, you may also contact support through WhatsApp.",
                            "Untuk komunikasi pantas, anda juga boleh menghubungi sokongan melalui WhatsApp."
                        ))
                        Button(onClick = { uri.openUri("https://wa.me/") }) {
                            Text(AppLanguage.text("Open WhatsApp", "Buka WhatsApp"))
                        }
                    }
                }
            }
            item {
                Text(
                    AppLanguage.text(
                        "This app is self-management support only and does not replace professional medical advice, diagnosis or treatment.",
                        "Aplikasi ini hanya untuk sokongan pengurusan diri dan tidak menggantikan nasihat, diagnosis atau rawatan perubatan profesional."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun SmartBandScreen(onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar(AppLanguage.text("Smart Band", "Gelang Pintar", "智能手环", "ஸ்மார்ட் பேண்ட்"), onBack, Color(0xFF5E35B1)) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Text(AppLanguage.text("Use Dashboard to connect and sync Health Connect data.", "Gunakan Dashboard untuk menyambung dan menyegerakkan data Health Connect."))
        }
    }
}

// ---------------- PROFILE: complete once, then lock baseline information ----------------
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val api: ApiService,
    session: SessionManager
) : ViewModel() {
    private val _profile = MutableStateFlow<ProfileRow?>(null)
    val profile = _profile.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg = _msg.asStateFlow()
    private val _educationCoins = MutableStateFlow(0)
    val educationCoins = _educationCoins.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _successEvent = MutableStateFlow(0)
    val successEvent = _successEvent.asStateFlow()
    private var pid = ""

    init {
        viewModelScope.launch {
            session.patientId.collect { id ->
                pid = id.orEmpty()
                if (pid.isNotBlank()) load()
            }
        }
    }

    fun load() = viewModelScope.launch {
        if (pid.isBlank()) return@launch
        try {
            val response = api.getProfile("eq.$pid")
            if (!response.isSuccessful) {
                _msg.value = UserFacingError.http(response.code(), Action.LOAD)
                return@launch
            }
            val profileRow = response.body().orEmpty().firstOrNull()
            _profile.value = profileRow
            val rewards = if (profileRow?.coins == null) {
                runCatching {
                    api.getEducationVideoRewards("eq.$pid").body().orEmpty()
                }.getOrDefault(emptyList())
            } else emptyList()
            _educationCoins.value = educationCoinTotal(profileRow?.coins, rewards)
            _msg.value = null
        } catch (e: Exception) {
            _msg.value = UserFacingError.from(e, Action.LOAD)
        }
    }

    fun submitAndLockProfile(
        fullName: String,
        age: String,
        ic: String,
        systolic: String,
        diastolic: String,
        heartRate: String,
        dryWeight: String,
        height: String,
        currentMedication: String,
        onSuccess: () -> Unit
    ) = viewModelScope.launch {
        if (pid.isBlank()) {
            _msg.value = AppLanguage.text(
                "Please login again.", "Sila log masuk semula.",
                "请重新登录。", "மீண்டும் உள்நுழையுங்கள்."
            )
            return@launch
        }
        if (_profile.value?.isCompletedAndLocked() == true) {
            _msg.value = AppLanguage.text(
                "Your submitted profile is locked.",
                "Profil yang telah dihantar sudah dikunci.",
                "您已提交的个人资料已锁定。",
                "சமர்ப்பிக்கப்பட்ட சுயவிவரம் பூட்டப்பட்டுள்ளது."
            )
            return@launch
        }

        val parsedAge = age.toIntOrNull()
        val parsedSys = systolic.toIntOrNull()
        val parsedDia = diastolic.toIntOrNull()
        val parsedHeartRate = heartRate.toIntOrNull()
        val parsedDryWeight = dryWeight.toDoubleOrNull()
        val parsedHeight = height.toDoubleOrNull()

        val validationMessage = when {
            fullName.trim().length < 2 -> AppLanguage.text(
                "Please enter your full name.", "Sila masukkan nama penuh.",
                "请输入您的全名。", "உங்கள் முழுப் பெயரை உள்ளிடுங்கள்."
            )
            parsedAge == null || parsedAge !in 1..120 -> AppLanguage.text(
                "Please enter a valid age from 1 to 120.",
                "Sila masukkan umur yang sah dari 1 hingga 120.",
                "请输入1至120之间的有效年龄。",
                "1 முதல் 120 வரை சரியான வயதை உள்ளிடுங்கள்."
            )
            ic.trim().length < 5 -> AppLanguage.text(
                "Please enter a valid IC or identification number.",
                "Sila masukkan nombor IC atau pengenalan yang sah.",
                "请输入有效的身份证号码。",
                "சரியான அடையாள அட்டை எண்ணை உள்ளிடுங்கள்."
            )
            parsedSys == null || parsedSys !in 60..250 -> AppLanguage.text(
                "Please enter a valid systolic BP from 60 to 250.",
                "Sila masukkan BP sistolik yang sah dari 60 hingga 250.",
                "请输入60至250之间的有效收缩压。",
                "60 முதல் 250 வரை சரியான சிஸ்டாலிக் இரத்த அழுத்தத்தை உள்ளிடுங்கள்."
            )
            parsedDia == null || parsedDia !in 30..150 -> AppLanguage.text(
                "Please enter a valid diastolic BP from 30 to 150.",
                "Sila masukkan BP diastolik yang sah dari 30 hingga 150.",
                "请输入30至150之间的有效舒张压。",
                "30 முதல் 150 வரை சரியான டயஸ்டாலிக் இரத்த அழுத்தத்தை உள்ளிடுங்கள்."
            )
            parsedSys <= parsedDia -> AppLanguage.text(
                "Systolic BP must be higher than diastolic BP.",
                "BP sistolik mesti lebih tinggi daripada BP diastolik.",
                "收缩压必须高于舒张压。",
                "சிஸ்டாலிக் அழுத்தம் டயஸ்டாலிக் அழுத்தத்தை விட அதிகமாக இருக்க வேண்டும்."
            )
            parsedHeartRate == null || parsedHeartRate !in 30..220 -> AppLanguage.text(
                "Please enter a valid heart rate from 30 to 220 bpm.",
                "Sila masukkan kadar jantung yang sah dari 30 hingga 220 bpm.",
                "请输入30至220 bpm之间的有效心率。",
                "30 முதல் 220 bpm வரை சரியான இதயத் துடிப்பை உள்ளிடுங்கள்."
            )
            parsedDryWeight == null || parsedDryWeight !in 20.0..300.0 -> AppLanguage.text(
                "Please enter a valid dry weight from 20 to 300 kg.",
                "Sila masukkan berat kering yang sah dari 20 hingga 300 kg.",
                "请输入20至300公斤之间的有效干体重。",
                "20 முதல் 300 கிலோ வரை சரியான உலர் எடையை உள்ளிடுங்கள்."
            )
            parsedHeight == null || parsedHeight !in 100.0..250.0 -> AppLanguage.text(
                "Please enter a valid height from 100 to 250 cm.",
                "Sila masukkan tinggi yang sah dari 100 hingga 250 cm.",
                "请输入100至250厘米之间的有效身高。",
                "100 முதல் 250 செ.மீ. வரை சரியான உயரத்தை உள்ளிடுங்கள்."
            )
            else -> null
        }
        if (validationMessage != null) {
            _msg.value = validationMessage
            return@launch
        }

        val validAge = requireNotNull(parsedAge)
        val validSys = requireNotNull(parsedSys)
        val validDia = requireNotNull(parsedDia)
        val validHeartRate = requireNotNull(parsedHeartRate)
        val validDryWeight = requireNotNull(parsedDryWeight)
        val validHeight = requireNotNull(parsedHeight)
        val bmi = validDryWeight / ((validHeight / 100.0) * (validHeight / 100.0))
        val languageCode = AppLanguage.current.storageValue
        _saving.value = true
        try {
            val existingProfile = _profile.value
            val response = if (existingProfile == null) {
                api.upsertProfile(
                    ProfileUpsert(
                        user_id = pid,
                        full_name = fullName.trim(),
                        age = validAge,
                        ic = ic.trim(),
                        systolic_bp = validSys,
                        diastolic_bp = validDia,
                        heart_rate = validHeartRate,
                        dry_weight = validDryWeight,
                        height = validHeight,
                        bmi = bmi,
                        current_medication = currentMedication.trim().takeIf { it.isNotBlank() },
                        language = languageCode,
                        profile_completed = true,
                        baseline_locked = true
                    )
                )
            } else {
                api.updateProfile(
                    "eq.$pid",
                    mapOf(
                        "full_name" to fullName.trim(),
                        "age" to validAge,
                        "ic" to ic.trim(),
                        "systolic_bp" to validSys,
                        "diastolic_bp" to validDia,
                        "heart_rate" to validHeartRate,
                        "dry_weight" to validDryWeight,
                        "height" to validHeight,
                        "bmi" to bmi,
                        "current_medication" to currentMedication.trim().takeIf { it.isNotBlank() },
                        "language" to languageCode,
                        "profile_completed" to true,
                        "baseline_locked" to true
                    )
                )
            }

            if (response.isSuccessful) {
                _msg.value = AppLanguage.text(
                    "Profile submitted and locked successfully.",
                    "Profil berjaya dihantar dan dikunci.",
                    "个人资料已成功提交并锁定。",
                    "சுயவிவரம் வெற்றிகரமாக சமர்ப்பிக்கப்பட்டு பூட்டப்பட்டது."
                )
                _successEvent.value += 1
                load()
                onSuccess()
            } else {
                _msg.value = UserFacingError.http(response.code(), Action.SAVE)
            }
        } catch (e: Exception) {
            _msg.value = UserFacingError.from(e, Action.SAVE)
        } finally {
            _saving.value = false
        }
    }

    fun saveCurrentMedication(currentMedication: String) = viewModelScope.launch {
        if (pid.isBlank()) {
            _msg.value = AppLanguage.text(
                "Please login again.", "Sila log masuk semula.",
                "请重新登录。", "மீண்டும் உள்நுழையுங்கள்."
            )
            return@launch
        }
        _saving.value = true
        try {
            val response = api.updateProfile(
                "eq.$pid",
                mapOf("current_medication" to currentMedication.trim().takeIf { it.isNotBlank() })
            )
            _msg.value = if (response.isSuccessful) {
                AppLanguage.text(
                    "Current medication saved.", "Ubat semasa berjaya disimpan.",
                    "目前药物已保存。", "தற்போதைய மருந்துகள் சேமிக்கப்பட்டன."
                )
            } else {
                UserFacingError.http(response.code(), Action.SAVE)
            }
            if (response.isSuccessful) {
                _successEvent.value += 1
                load()
            }
        } catch (e: Exception) {
            _msg.value = UserFacingError.from(e, Action.SAVE)
        } finally {
            _saving.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    forceCompletion: Boolean = false,
    onProfileCompleted: () -> Unit = {},
    onLogout: (() -> Unit)? = null,
    vm: ProfileViewModel = hiltViewModel()
) {
    val profile by vm.profile.collectAsState()
    val msg by vm.msg.collectAsState()
    val educationCoins by vm.educationCoins.collectAsState()
    RefreshCoinsOnResume { vm.load() }
    val saving by vm.saving.collectAsState()
    val successEvent by vm.successEvent.collectAsState()
    val locked = profile?.isCompletedAndLocked() == true

    var fullName by remember(profile?.full_name) { mutableStateOf(profile?.full_name.orEmpty()) }
    var age by remember(profile?.age) { mutableStateOf(profile?.age?.toString().orEmpty()) }
    var ic by remember(profile?.ic) { mutableStateOf(profile?.ic.orEmpty()) }
    var systolic by remember(profile?.systolic_bp) { mutableStateOf(profile?.systolic_bp?.toString().orEmpty()) }
    var diastolic by remember(profile?.diastolic_bp) { mutableStateOf(profile?.diastolic_bp?.toString().orEmpty()) }
    var heartRate by remember(profile?.heart_rate) { mutableStateOf(profile?.heart_rate?.toString().orEmpty()) }
    var dryWeight by remember(profile?.dry_weight) { mutableStateOf(profile?.dry_weight?.toString().orEmpty()) }
    var height by remember(profile?.height) { mutableStateOf(profile?.height?.toString().orEmpty()) }
    var currentMedication by remember(profile?.current_medication) { mutableStateOf(profile?.current_medication.orEmpty()) }
    var showLockConfirmation by remember { mutableStateOf(false) }
    var showDataCollected by remember { mutableStateOf(false) }
    var handledSuccessEvent by remember { mutableIntStateOf(0) }
    var waitForSuccessBeforeCompleting by remember { mutableStateOf(false) }

    val w = dryWeight.toDoubleOrNull()
    val h = height.toDoubleOrNull()
    val bmi = if (w != null && h != null && h > 0) w / ((h / 100.0) * (h / 100.0)) else null

    LaunchedEffect(successEvent) {
        if (successEvent > handledSuccessEvent) {
            handledSuccessEvent = successEvent
            showDataCollected = true
        }
    }

    LaunchedEffect(forceCompletion, locked, waitForSuccessBeforeCompleting) {
        if (forceCompletion && locked && !waitForSuccessBeforeCompleting) onProfileCompleted()
    }

    if (showLockConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!saving) showLockConfirmation = false },
            icon = { Icon(Icons.Default.Lock, null) },
            title = {
                Text(AppLanguage.text(
                    "Submit and lock profile?", "Hantar dan kunci profil?",
                    "提交并锁定个人资料？", "சுயவிவரத்தை சமர்ப்பித்து பூட்டவா?"
                ))
            },
            text = {
                Text(AppLanguage.text(
                    "Please check every detail carefully. After submission, your personal information and health baseline cannot be edited. Contact the administrator if a correction is needed.",
                    "Sila semak setiap maklumat dengan teliti. Selepas dihantar, maklumat peribadi dan baseline kesihatan tidak boleh diedit. Hubungi pentadbir jika pembetulan diperlukan.",
                    "请仔细检查所有资料。提交后，个人信息和健康基线将无法编辑。如需更正，请联系管理员。",
                    "ஒவ்வொரு விவரத்தையும் கவனமாக சரிபார்க்கவும். சமர்ப்பித்த பிறகு தனிப்பட்ட தகவல்களையும் உடல்நல அடிப்படையையும் திருத்த முடியாது. திருத்தம் தேவைப்பட்டால் நிர்வாகியை அணுகவும்."
                ))
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.submitAndLockProfile(
                            fullName, age, ic, systolic, diastolic, heartRate,
                            dryWeight, height, currentMedication
                        ) {
                            showLockConfirmation = false
                            waitForSuccessBeforeCompleting = forceCompletion
                        }
                    },
                    enabled = !saving
                ) {
                    Text(AppLanguage.text(
                        "Confirm and submit", "Sahkan dan hantar",
                        "确认并提交", "உறுதிசெய்து சமர்ப்பிக்கவும்"
                    ))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLockConfirmation = false }, enabled = !saving) {
                    Text(AppLanguage.text("Check again", "Semak semula", "再次检查", "மீண்டும் சரிபார்க்கவும்"))
                }
            }
        )
    }

    val title = if (forceCompletion && !locked) {
        AppLanguage.text(
            "Complete Your Profile", "Lengkapkan Profil Anda",
            "完成您的个人资料", "உங்கள் சுயவிவரத்தை நிறைவு செய்யுங்கள்"
        )
    } else {
        AppLanguage.text("My Profile", "Profil Saya", "我的个人资料", "என் சுயவிவரம்")
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = title,
                onBack = onBack,
                color = Color(0xFF00ACC1),
                refresh = { vm.load() },
                showBack = !forceCompletion
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (!forceCompletion) {
                item {
                    EducationCoinCard(educationCoins)
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (locked) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            if (locked) Icons.Default.Lock else Icons.Default.Info,
                            null,
                            tint = if (locked) Color(0xFF2E7D32) else Color(0xFFF57F17)
                        )
                        Text(
                            if (locked) {
                                AppLanguage.text(
                                    "Your personal information and health baseline are locked after submission. Contact the administrator for corrections. Current medication can still be updated.",
                                    "Maklumat peribadi dan baseline kesihatan dikunci selepas dihantar. Hubungi pentadbir untuk pembetulan. Ubat semasa masih boleh dikemas kini.",
                                    "提交后，您的个人信息和健康基线已锁定。如需更正，请联系管理员。目前药物仍可更新。",
                                    "சமர்ப்பித்த பிறகு தனிப்பட்ட தகவல்களும் உடல்நல அடிப்படையும் பூட்டப்பட்டுள்ளன. திருத்தத்திற்கு நிர்வாகியை அணுகவும். தற்போதைய மருந்துகளை மட்டும் புதுப்பிக்கலாம்."
                                )
                            } else {
                                AppLanguage.text(
                                    "Complete every required field before using the app. Your personal information and health baseline will be locked after the first successful submission.",
                                    "Lengkapkan semua ruangan wajib sebelum menggunakan aplikasi. Maklumat peribadi dan baseline kesihatan akan dikunci selepas penghantaran pertama berjaya.",
                                    "使用应用程序前，请填写所有必填项。首次成功提交后，个人信息和健康基线将被锁定。",
                                    "செயலியைப் பயன்படுத்துவதற்கு முன் அனைத்து கட்டாயப் புலங்களையும் நிரப்பவும். முதல் வெற்றிகரமான சமர்ப்பிப்புக்குப் பிறகு தனிப்பட்ட தகவல்களும் உடல்நல அடிப்படையும் பூட்டப்படும்."
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item { ProfileSectionTitle(AppLanguage.text(
                "Personal Information", "Maklumat Peribadi",
                "个人信息", "தனிப்பட்ட தகவல்"
            ), Icons.Default.Person) }
            item {
                OutlinedTextField(
                    fullName,
                    { if (!locked) fullName = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.text("Full Name *", "Nama Penuh *", "全名 *", "முழுப் பெயர் *")) },
                    singleLine = true,
                    readOnly = locked,
                    enabled = !locked
                )
            }
            item {
                OutlinedTextField(
                    age,
                    { if (!locked) age = it.filter(Char::isDigit).take(3) },
                    Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.text("Age *", "Umur *", "年龄 *", "வயது *")) },
                    singleLine = true,
                    readOnly = locked,
                    enabled = !locked
                )
            }
            item {
                OutlinedTextField(
                    ic,
                    { if (!locked) ic = it.take(30) },
                    Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.text("IC / Identification Number *", "No. IC / Pengenalan *", "身份证号码 *", "அடையாள அட்டை எண் *")) },
                    singleLine = true,
                    readOnly = locked,
                    enabled = !locked
                )
            }

            item { ProfileSectionTitle(AppLanguage.text(
                "Health Baseline", "Baseline Kesihatan",
                "健康基线", "உடல்நல அடிப்படை"
            ), Icons.Default.Favorite) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        systolic,
                        { if (!locked) systolic = it.filter(Char::isDigit).take(3) },
                        Modifier.weight(1f),
                        label = { Text("SYS *") },
                        singleLine = true,
                        readOnly = locked,
                        enabled = !locked
                    )
                    OutlinedTextField(
                        diastolic,
                        { if (!locked) diastolic = it.filter(Char::isDigit).take(3) },
                        Modifier.weight(1f),
                        label = { Text("DIA *") },
                        singleLine = true,
                        readOnly = locked,
                        enabled = !locked
                    )
                }
            }
            item {
                OutlinedTextField(
                    heartRate,
                    { if (!locked) heartRate = it.filter(Char::isDigit).take(3) },
                    Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.text(
                        "Resting Heart Rate / Pulse *", "Kadar Jantung / Nadi Rehat *",
                        "静息心率 / 脉搏 *", "ஓய்வு இதயத் துடிப்பு / நாடி *"
                    )) },
                    singleLine = true,
                    readOnly = locked,
                    enabled = !locked
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        dryWeight,
                        { if (!locked) dryWeight = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                        Modifier.weight(1f),
                        label = { Text(AppLanguage.text("Dry Weight kg *", "Berat Kering kg *", "干体重 kg *", "உலர் எடை kg *")) },
                        singleLine = true,
                        readOnly = locked,
                        enabled = !locked
                    )
                    OutlinedTextField(
                        height,
                        { if (!locked) height = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) },
                        Modifier.weight(1f),
                        label = { Text(AppLanguage.text("Height cm *", "Tinggi cm *", "身高 cm *", "உயரம் cm *")) },
                        singleLine = true,
                        readOnly = locked,
                        enabled = !locked
                    )
                }
            }
            item {
                Text(
                    "BMI: ${bmi?.let { "%.2f".format(it) } ?: "--"}",
                    fontWeight = FontWeight.Bold
                )
            }

            item { ProfileSectionTitle(AppLanguage.text(
                "Current Medication", "Ubat Semasa",
                "目前药物", "தற்போதைய மருந்துகள்"
            ), Icons.Default.Medication) }
            item {
                OutlinedTextField(
                    currentMedication,
                    { currentMedication = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.text(
                        "Current medication (optional)", "Ubat semasa (pilihan)",
                        "目前药物（可选）", "தற்போதைய மருந்துகள் (விருப்பம்)"
                    )) },
                    supportingText = { Text(AppLanguage.text(
                        "One medicine per line: name | dose | noon, night or both. Example: Medicine | 10 mg | night. Medicines without a time use noon.",
                        "Satu ubat setiap baris: nama | dos | noon, night atau both. Contoh: Ubat | 10 mg | night. Tanpa masa menggunakan tengah hari.",
                        "每行一种药：名称 | 剂量 | noon（中午）、night（晚上）或 both（两次）。未填写时间时默认为中午。",
                        "ஒவ்வொரு வரியிலும்: பெயர் | அளவு | noon, night அல்லது both. நேரம் இல்லையெனில் மதியம் பயன்படுத்தப்படும்."
                    )) },
                    minLines = 3
                )
            }

            item { ProfileSectionTitle(AppLanguage.text(
                "Language", "Bahasa", "语言", "மொழி"
            ), Icons.Default.Language) }
            item { LanguageSegmentedSwitch(modifier = Modifier.fillMaxWidth()) }

            if (!locked) {
                item {
                    Button(
                        onClick = { showLockConfirmation = true },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(AppLanguage.text(
                            "Submit and Lock Profile", "Hantar dan Kunci Profil",
                            "提交并锁定个人资料", "சுயவிவரத்தை சமர்ப்பித்து பூட்டவும்"
                        ))
                    }
                }
            } else {
                item {
                    Button(
                        onClick = { vm.saveCurrentMedication(currentMedication) },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))
                    ) {
                        Text(AppLanguage.text(
                            "Save Current Medication", "Simpan Ubat Semasa",
                            "保存目前药物", "தற்போதைய மருந்துகளைச் சேமிக்கவும்"
                        ))
                    }
                }
            }
            item { StatusText(msg) }
            if (!forceCompletion) {
                item { ProfilePasswordChangeSection() }
            }
            if (forceCompletion && onLogout != null) {
                item {
                    TextButton(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(AppLanguage.text(
                            "Sign out and use another account",
                            "Log keluar dan gunakan akaun lain",
                            "退出并使用其他账户",
                            "வெளியேறி வேறு கணக்கைப் பயன்படுத்தவும்"
                        ))
                    }
                }
            }
        }
    }

    BlockingLoadingScreen(
        visible = saving,
        title = if (locked) {
            AppLanguage.text(
                "Saving medication...",
                "Menyimpan ubat...",
                "正在保存药物……",
                "மருந்துகள் சேமிக்கப்படுகின்றன..."
            )
        } else {
            AppLanguage.text(
                "Saving profile...",
                "Menyimpan profil...",
                "正在保存个人资料……",
                "சுயவிவரம் சேமிக்கப்படுகிறது..."
            )
        }
    )

    DataCollectedSuccessScreen(
        visible = showDataCollected,
        onDismiss = {
            showDataCollected = false
            if (waitForSuccessBeforeCompleting) {
                waitForSuccessBeforeCompleting = false
            }
        }
    )
}

@Composable
private fun ProfileSectionTitle(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Color(0xFF00ACC1))
        Text(text, fontWeight = FontWeight.Bold)
    }
}
