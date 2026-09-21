package com.vitalink.app.ui.screens

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import android.content.Context
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.model.AddAppointmentRequest
import com.vitalink.app.data.model.Appointment
import com.vitalink.app.data.model.MedicationEntry
import com.vitalink.app.data.model.ReminderInsert
import com.vitalink.app.data.model.ReminderRow
import com.vitalink.app.reminders.AppointmentReminderWorker
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.MalaysiaDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class AppointmentUiState(
    val appointments: List<Appointment> = emptyList(),
    val message: String? = null,
    val isSaving: Boolean = false,
    val deletingAppointmentId: String? = null
)

private fun ReminderRow.toAppointment(): Appointment {
    val malaysiaDue = MalaysiaDateTime.parseTimestamp(due_ts)
    return Appointment(
        id = id,
        patient_id = patient_id,
        title = title,
        appointment_date = malaysiaDue?.toLocalDate()?.toString(),
        appointment_time = malaysiaDue?.toLocalTime()?.format(DateTimeFormatter.ofPattern("HH:mm")),
        notes = notes,
        created_at = created_at,
        source = "reminders"
    )
}

private fun appointmentDueTimestamp(date: String, time: String): String =
    MalaysiaDateTime.appointmentTimestamp(LocalDate.parse(date), LocalTime.parse(time))

@HiltViewModel
class MedicationViewModel @Inject constructor(
    private val api: ApiService,
    session: SessionManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {
    private val _state = MutableStateFlow(AppointmentUiState())
    val state = _state.asStateFlow()
    private var pid = ""

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
        try {
            val today = MalaysiaDateTime.today().toString()

            // Some deployed MyHFGuard databases use the appointments table, while
            // the web project stores appointment entries in reminders. Read both so
            // records created from either client appear in the app.
            val appointmentRows: List<Appointment> = runCatching {
                val response = api.getAppointments("eq.$pid")
                if (response.isSuccessful) {
                    response.body().orEmpty().map { it.copy(source = "appointments") }
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())

            val reminderAppointments: List<Appointment> = runCatching {
                val response = api.getReminders("eq.$pid")
                if (response.isSuccessful) {
                    response.body().orEmpty()
                        .filter { it.type.equals("appointment", ignoreCase = true) }
                        .map { it.toAppointment() }
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())

            val appointments = (reminderAppointments + appointmentRows)
                .filter { it.appointment_date?.take(10)?.let { d -> d >= today } ?: false }
                .distinctBy {
                    listOf(
                        it.title.orEmpty().trim().lowercase(),
                        it.appointment_date.orEmpty().take(10),
                        it.appointment_time.orEmpty().take(5),
                        it.notes.orEmpty().trim().lowercase()
                    ).joinToString("|")
                }
                .sortedWith(
                    compareBy<Appointment> { it.appointment_date.orEmpty() }
                        .thenBy { it.appointment_time.orEmpty() }
                )

            _state.value = _state.value.copy(appointments = appointments)
        } catch (e: Exception) {
            _state.value = _state.value.copy(message = UserFacingError.from(e, Action.LOAD))
        }
    }

    fun saveAppointment(
        editing: Appointment?,
        title: String,
        date: String,
        time: String,
        period: String,
        notes: String,
        onSuccess: () -> Unit = {}
    ) = viewModelScope.launch {
        if (pid.isBlank()) {
            _state.value = _state.value.copy(
                message = AppLanguage.text(
                    "Please login again.",
                    "Sila log masuk semula.",
                    "请重新登录。",
                    "மீண்டும் உள்நுழையவும்."
                )
            )
            return@launch
        }
        if (title.isBlank()) {
            _state.value = _state.value.copy(
                message = AppLanguage.text(
                    "Please enter an appointment title.",
                    "Sila masukkan tajuk temu janji.",
                    "请输入预约标题。",
                    "சந்திப்பு தலைப்பை உள்ளிடவும்."
                )
            )
            return@launch
        }

        val appointmentDate = runCatching { LocalDate.parse(date) }.getOrNull()
        if (appointmentDate == null) {
            _state.value = _state.value.copy(
                message = AppLanguage.text(
                    "The appointment date is invalid.",
                    "Tarikh temu janji tidak sah.",
                    "预约日期无效。",
                    "சந்திப்பு தேதி செல்லாது."
                )
            )
            return@launch
        }
        if (appointmentDate.isBefore(MalaysiaDateTime.today())) {
            _state.value = _state.value.copy(
                message = AppLanguage.text(
                    "Please choose today or a future date.",
                    "Sila pilih hari ini atau tarikh akan datang.",
                    "请选择今天或未来日期。",
                    "இன்று அல்லது எதிர்கால தேதியைத் தேர்ந்தெடுக்கவும்."
                )
            )
            return@launch
        }

        val normalizedTime = time.trim()
        val normalizedPeriod = period.trim().uppercase(Locale.US)
        val appointmentTime = runCatching {
            LocalTime.parse(
                "$normalizedTime $normalizedPeriod",
                DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            )
        }.getOrNull()
        if (appointmentTime == null) {
            _state.value = _state.value.copy(
                message = AppLanguage.text(
                    "Enter a valid time and choose AM or PM, for example 08:30 AM.",
                    "Masukkan masa yang sah dan pilih AM atau PM, contohnya 08:30 AM.",
                    "请输入有效时间并选择 AM 或 PM，例如 08:30 AM。",
                    "சரியான நேரத்தை உள்ளிட்டு AM அல்லது PM-ஐ தேர்வு செய்யுங்கள், உதாரணம் 08:30 AM."
                )
            )
            return@launch
        }

        _state.value = _state.value.copy(isSaving = true, message = null)
        try {
            val cleanTitle = title.trim()
            val cleanNotes = notes.trim().ifBlank { null }
            val canonicalTime = appointmentTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            var saved = false
            var failureStatus = 0

            if (!editing?.id.isNullOrBlank()) {
                val idFilter = "eq.${editing?.id}"
                val reminderBody = mapOf<String, Any?>(
                    "title" to cleanTitle,
                    "type" to "appointment",
                    "due_ts" to appointmentDueTimestamp(appointmentDate.toString(), canonicalTime),
                    "notes" to cleanNotes,
                    "status" to "upcoming"
                )
                val appointmentBody = mapOf<String, Any?>(
                    "title" to cleanTitle,
                    "appointment_date" to appointmentDate.toString(),
                    "appointment_time" to canonicalTime,
                    "notes" to cleanNotes
                )

                when (editing?.source) {
                    "reminders" -> {
                        val response = api.updateReminder(idFilter, reminderBody)
                        saved = response.isSuccessful
                        if (!saved) failureStatus = response.code()
                    }
                    "appointments" -> {
                        val response = api.updateAppointment(idFilter, appointmentBody)
                        saved = response.isSuccessful
                        if (!saved) failureStatus = response.code()
                    }
                    else -> {
                        val reminderResponse = api.updateReminder(idFilter, reminderBody)
                        saved = reminderResponse.isSuccessful
                        if (!saved) {
                            val appointmentResponse = api.updateAppointment(idFilter, appointmentBody)
                            saved = appointmentResponse.isSuccessful
                            if (!saved) {
                                failureStatus = appointmentResponse.code()
                            }
                        }
                    }
                }
            } else {
                val reminderResponse = api.insertReminder(
                    ReminderInsert(
                        patient_id = pid,
                        title = cleanTitle,
                        type = "appointment",
                        due_ts = appointmentDueTimestamp(appointmentDate.toString(), canonicalTime),
                        notes = cleanNotes,
                        status = "upcoming"
                    )
                )
                saved = reminderResponse.isSuccessful
                failureStatus = if (saved) 0 else reminderResponse.code()

                if (!saved) {
                    val appointmentResponse = api.addAppointment(
                        AddAppointmentRequest(
                            patientId = pid,
                            title = cleanTitle,
                            appointmentDate = appointmentDate.toString(),
                            appointmentTime = canonicalTime,
                            notes = cleanNotes
                        )
                    )
                    saved = appointmentResponse.isSuccessful
                    if (!saved) {
                        failureStatus = appointmentResponse.code()
                    }
                }
            }

            if (saved) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    message = if (editing == null) {
                        AppLanguage.text(
                            "Appointment saved.",
                            "Temu janji disimpan.",
                            "预约已保存。",
                            "சந்திப்பு சேமிக்கப்பட்டது."
                        )
                    } else {
                        AppLanguage.text(
                            "Appointment updated.",
                            "Temu janji dikemas kini.",
                            "预约已更新。",
                            "சந்திப்பு புதுப்பிக்கப்பட்டது."
                        )
                    }
                )
                onSuccess()
                AppointmentReminderWorker.schedule(appContext)
                AppointmentReminderWorker.checkNow(appContext)
                fetch()
            } else {
                _state.value = _state.value.copy(
                    isSaving = false,
                    message = UserFacingError.http(failureStatus, Action.SAVE)
                )
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isSaving = false,
                message = UserFacingError.from(e, Action.SAVE)
            )
        }
    }

    fun deleteAppointment(
        appointment: Appointment,
        onSuccess: () -> Unit = {}
    ) = viewModelScope.launch {
        val appointmentId = appointment.id.orEmpty()
        if (appointmentId.isBlank()) {
            _state.value = _state.value.copy(
                message = AppLanguage.text(
                    "This appointment cannot be deleted because its record ID is missing.",
                    "Temu janji ini tidak boleh dipadam kerana ID rekod tiada.",
                    "无法删除此预约，因为记录 ID 缺失。",
                    "பதிவு ID இல்லாததால் இந்த சந்திப்பை நீக்க முடியாது."
                )
            )
            return@launch
        }

        _state.value = _state.value.copy(deletingAppointmentId = appointmentId, message = null)
        try {
            val idFilter = "eq.$appointmentId"
            val response = when (appointment.source) {
                "reminders" -> api.deleteReminder(idFilter)
                "appointments" -> api.deleteAppointment(idFilter)
                else -> {
                    val reminderResponse = api.deleteReminder(idFilter)
                    if (reminderResponse.isSuccessful) reminderResponse else api.deleteAppointment(idFilter)
                }
            }

            val stillExists = if (response.isSuccessful) {
                runCatching {
                    when (appointment.source) {
                        "reminders" -> api.getReminders("eq.$pid").body().orEmpty().any { it.id == appointmentId }
                        "appointments" -> api.getAppointments("eq.$pid").body().orEmpty().any { it.id == appointmentId }
                        else -> {
                            api.getReminders("eq.$pid").body().orEmpty().any { it.id == appointmentId } ||
                                api.getAppointments("eq.$pid").body().orEmpty().any { it.id == appointmentId }
                        }
                    }
                }.getOrNull()
            } else {
                true
            }

            if (response.isSuccessful && stillExists != true) {
                _state.value = _state.value.copy(
                    deletingAppointmentId = null,
                    message = AppLanguage.text(
                        "Appointment deleted.",
                        "Temu janji dipadam.",
                        "预约已删除。",
                        "சந்திப்பு நீக்கப்பட்டது."
                    )
                )
                onSuccess()
                AppointmentReminderWorker.checkNow(appContext)
                fetch()
            } else {
                _state.value = _state.value.copy(
                    deletingAppointmentId = null,
                    message = UserFacingError.http(response.code(), Action.DELETE)
                )
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                deletingAppointmentId = null,
                message = UserFacingError.from(e, Action.DELETE)
            )
        }
    }

}

// Kept for the existing medicine reminder worker/profile format. The Appointment UI no longer edits it.
fun parseCurrentMedication(text: String): List<MedicationEntry> =
    com.vitalink.app.util.MedicationTiming.parseProfile(text)

private fun appointmentDateLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "-"
    return runCatching {
        LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }.getOrElse { raw }
}

private fun appointmentTimeForInput(raw: String?): Pair<String, String> {
    val parsed = runCatching {
        LocalTime.parse(raw.orEmpty().take(5), DateTimeFormatter.ofPattern("H:mm", Locale.US))
    }.getOrElse { LocalTime.of(8, 0) }
    return parsed.format(DateTimeFormatter.ofPattern("hh:mm", Locale.US)) to
        parsed.format(DateTimeFormatter.ofPattern("a", Locale.US))
}

private fun appointmentTimeLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "-"
    return runCatching {
        LocalTime.parse(raw.take(5), DateTimeFormatter.ofPattern("H:mm", Locale.US))
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }.getOrElse { raw }
}

private fun manualAppointmentTime(time: String, period: String): LocalTime? = runCatching {
    LocalTime.parse(
        "${time.trim()} ${period.trim().uppercase(Locale.US)}",
        DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    )
}.getOrNull()

@Composable
private fun SwipeNumberPicker(
    label: String,
    values: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit
) {
    var dragDistance by remember { mutableStateOf(0f) }

    fun moveBy(delta: Int) {
        val size = values.size.coerceAtLeast(1)
        onSelectedIndexChange((selectedIndex + delta + size) % size)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { moveBy(-1) }) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
        }
        Surface(
            modifier = Modifier
                .width(82.dp)
                .height(68.dp)
                .pointerInput(selectedIndex, values) {
                    detectVerticalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onVerticalDrag = { _, dragAmount -> dragDistance += dragAmount },
                        onDragEnd = {
                            when {
                                dragDistance < -20f -> moveBy(1)
                                dragDistance > 20f -> moveBy(-1)
                            }
                            dragDistance = 0f
                        },
                        onDragCancel = { dragDistance = 0f }
                    )
                },
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF3E5F5),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF8E24AA).copy(alpha = 0.35f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(values[selectedIndex], fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF6A1B9A))
            }
        }
        IconButton(onClick = { moveBy(1) }) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EasyTimePickerDialog(
    initialHour24: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour24: Int, minute: Int) -> Unit
) {
    val hours = (1..12).map { String.format(Locale.US, "%02d", it) }
    val minutes = (0..55 step 5).map { String.format(Locale.US, "%02d", it) }
    val periods = listOf("AM", "PM")
    val initialHour12 = when {
        initialHour24 == 0 -> 12
        initialHour24 > 12 -> initialHour24 - 12
        else -> initialHour24
    }
    var hourIndex by remember(initialHour24) { mutableStateOf((initialHour12 - 1).coerceIn(0, 11)) }
    var minuteIndex by remember(initialMinute) { mutableStateOf(((initialMinute.coerceIn(0, 59) + 2) / 5).coerceIn(0, 11)) }
    var periodIndex by remember(initialHour24) { mutableStateOf(if (initialHour24 >= 12) 1 else 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppLanguage.text("Choose time", "Pilih masa", "选择时间", "நேரத்தைத் தேர்வு செய்"), fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    AppLanguage.text(
                        "Swipe the numbers up or down, then tap Select.",
                        "Leret nombor ke atas atau bawah, kemudian tekan Pilih.",
                        "上下滑动数字，然后点击“选择”。",
                        "எண்களை மேலே அல்லது கீழே நகர்த்தி, பிறகு தேர்வு என்பதைத் தட்டவும்."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SwipeNumberPicker(AppLanguage.text("Hour", "Jam", "小时", "மணி"), hours, hourIndex) { hourIndex = it }
                    Text(":", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    SwipeNumberPicker(AppLanguage.text("Minute", "Minit", "分钟", "நிமிடம்"), minutes, minuteIndex) { minuteIndex = it }
                    SwipeNumberPicker("AM/PM", periods, periodIndex) { periodIndex = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hour12 = hourIndex + 1
                val hour24 = when {
                    periodIndex == 0 && hour12 == 12 -> 0
                    periodIndex == 1 && hour12 != 12 -> hour12 + 12
                    else -> hour12
                }
                onConfirm(hour24, minuteIndex * 5)
            }) { Text(AppLanguage.text("Select", "Pilih", "选择", "தேர்வு")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppLanguage.text("Cancel", "Batal", "取消", "ரத்துசெய்")) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationScreen(onBack: () -> Unit, vm: MedicationViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val appointmentBusy = state.isSaving || state.deletingAppointmentId != null
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(MalaysiaDateTime.today().toString()) }
    var appointmentTime by remember { mutableStateOf("08:00") }
    var appointmentPeriod by remember { mutableStateOf("AM") }
    var notes by remember { mutableStateOf("") }
    var editingAppointment by remember { mutableStateOf<Appointment?>(null) }
    var appointmentPendingDelete by remember { mutableStateOf<Appointment?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDataCollected by remember { mutableStateOf(false) }

    fun clearForm() {
        editingAppointment = null
        title = ""
        date = MalaysiaDateTime.today().toString()
        appointmentTime = "08:00"
        appointmentPeriod = "AM"
        notes = ""
    }

    if (showDatePicker) {
        val initialDateMillis = remember(date) {
            runCatching {
                LocalDate.parse(date).toEpochDay() * 86_400_000L
            }.getOrNull()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text(AppLanguage.text("Select", "Pilih", "选择", "தேர்வு")) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(AppLanguage.text("Cancel", "Batal", "取消", "ரத்துசெய்"))
                }
            }
        ) { DatePicker(state = datePickerState) }
    }

    if (showTimePicker) {
        val selectedTime = manualAppointmentTime(appointmentTime, appointmentPeriod)
            ?: LocalTime.of(8, 0)
        EasyTimePickerDialog(
            initialHour24 = selectedTime.hour,
            initialMinute = selectedTime.minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                val pickedTime = LocalTime.of(hour, minute)
                appointmentTime = pickedTime.format(DateTimeFormatter.ofPattern("hh:mm", Locale.US))
                appointmentPeriod = pickedTime.format(DateTimeFormatter.ofPattern("a", Locale.US))
                showTimePicker = false
            }
        )
    }

    appointmentPendingDelete?.let { appointment ->
        AlertDialog(
            onDismissRequest = {
                if (state.deletingAppointmentId == null) appointmentPendingDelete = null
            },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = {
                Text(AppLanguage.text("Delete appointment?", "Padam temu janji?", "删除预约？", "சந்திப்பை நீக்கவா?"))
            },
            text = {
                Text(
                    AppLanguage.text(
                        "This will permanently delete ${appointment.title.orEmpty().ifBlank { "this appointment" }} on ${appointmentDateLabel(appointment.appointment_date)} at ${appointmentTimeLabel(appointment.appointment_time)}.",
                        "Ini akan memadam secara kekal ${appointment.title.orEmpty().ifBlank { "temu janji ini" }} pada ${appointmentDateLabel(appointment.appointment_date)} jam ${appointmentTimeLabel(appointment.appointment_time)}.",
                        "这将永久删除 ${appointment.title.orEmpty().ifBlank { "此预约" }}（${appointmentDateLabel(appointment.appointment_date)} ${appointmentTimeLabel(appointment.appointment_time)}）。",
                        "${appointment.title.orEmpty().ifBlank { "இந்த சந்திப்பு" }} ${appointmentDateLabel(appointment.appointment_date)} ${appointmentTimeLabel(appointment.appointment_time)} அன்று நிரந்தரமாக நீக்கப்படும்."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAppointment(appointment) {
                            if (editingAppointment?.id == appointment.id && editingAppointment?.source == appointment.source) {
                                clearForm()
                            }
                        }
                        appointmentPendingDelete = null
                    },
                    enabled = state.deletingAppointmentId == null,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(AppLanguage.text("Delete", "Padam", "删除", "நீக்கு"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { appointmentPendingDelete = null },
                    enabled = state.deletingAppointmentId == null
                ) {
                    Text(AppLanguage.text("Cancel", "Batal", "取消", "ரத்துசெய்"))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        AppLanguage.text("My Appointment", "Temu Janji Saya", "我的预约", "என் சந்திப்பு"),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { IconButton(onClick = vm::fetch) { Icon(Icons.Default.Refresh, null) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF8E24AA),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    AppLanguage.text("Upcoming Appointments", "Temu Janji Akan Datang", "即将到来的预约", "வரவிருக்கும் சந்திப்புகள்"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (state.appointments.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Text(
                            AppLanguage.text(
                                "No upcoming appointments.",
                                "Tiada temu janji akan datang.",
                                "暂无即将到来的预约。",
                                "வரவிருக்கும் சந்திப்புகள் இல்லை."
                            ),
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(state.appointments, key = { it.source.orEmpty() + it.id.orEmpty() }) { appointment ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F3FC)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1D6EE))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    appointment.title.orEmpty().ifBlank {
                                        AppLanguage.text("Appointment", "Temu janji", "预约", "சந்திப்பு")
                                    },
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        editingAppointment = appointment
                                        title = appointment.title.orEmpty()
                                        date = appointment.appointment_date.orEmpty().take(10).ifBlank { MalaysiaDateTime.today().toString() }
                                        val (displayTime, displayPeriod) = appointmentTimeForInput(appointment.appointment_time)
                                        appointmentTime = displayTime
                                        appointmentPeriod = displayPeriod
                                        notes = appointment.notes.orEmpty()
                                    },
                                    enabled = state.deletingAppointmentId == null
                                ) {
                                    Icon(Icons.Default.Edit, AppLanguage.text("Edit appointment", "Edit temu janji", "编辑预约", "சந்திப்பைத் திருத்து"))
                                }
                                IconButton(
                                    onClick = { appointmentPendingDelete = appointment },
                                    enabled = state.deletingAppointmentId == null
                                ) {
                                    if (state.deletingAppointmentId == appointment.id) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            Icons.Default.Delete,
                                            AppLanguage.text("Delete appointment", "Padam temu janji", "删除预约", "சந்திப்பை நீக்கு"),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            Text(appointmentDateLabel(appointment.appointment_date), fontWeight = FontWeight.SemiBold)
                            Surface(color = Color(0xFFEDE3F7), shape = RoundedCornerShape(12.dp)) {
                                Text(appointmentTimeLabel(appointment.appointment_time), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                            }
                            if (!appointment.notes.isNullOrBlank()) {
                                Text(appointment.notes, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (editingAppointment == null) {
                                AppLanguage.text("Add New Appointment", "Tambah Temu Janji Baharu", "添加新预约", "புதிய சந்திப்பைச் சேர்")
                            } else {
                                AppLanguage.text("Edit Appointment", "Edit Temu Janji", "编辑预约", "சந்திப்பைத் திருத்து")
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(AppLanguage.text("Title", "Tajuk", "标题", "தலைப்பு")) },
                            singleLine = true
                        )
                        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.DateRange, null)
                            Spacer(Modifier.width(6.dp))
                            Text(appointmentDateLabel(date))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = appointmentTime,
                                onValueChange = { raw ->
                                    appointmentTime = raw.filter { it.isDigit() || it == ':' }.take(5)
                                },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        AppLanguage.text(
                                            "Time (hh:mm)",
                                            "Masa (hh:mm)",
                                            "时间 (hh:mm)",
                                            "நேரம் (hh:mm)"
                                        )
                                    )
                                },
                                placeholder = { Text("08:30") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { showTimePicker = true }) {
                                        Icon(
                                            Icons.Default.Schedule,
                                            AppLanguage.text(
                                                "Choose time",
                                                "Pilih masa",
                                                "选择时间",
                                                "நேரத்தைத் தேர்வு செய்"
                                            )
                                        )
                                    }
                                }
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "AM / PM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(
                                        selected = appointmentPeriod == "AM",
                                        onClick = { appointmentPeriod = "AM" },
                                        label = { Text("AM") }
                                    )
                                    FilterChip(
                                        selected = appointmentPeriod == "PM",
                                        onClick = { appointmentPeriod = "PM" },
                                        label = { Text("PM") }
                                    )
                                }
                            }
                        }
                        Text(
                            AppLanguage.text(
                                "Type the time, then choose AM for morning or PM for afternoon/evening.",
                                "Taip masa, kemudian pilih AM untuk waktu pagi atau PM untuk petang/malam.",
                                "输入时间后，上午请选择 AM，下午或晚上请选择 PM。",
                                "நேரத்தை உள்ளிட்டு, காலை நேரத்திற்கு AM அல்லது பிற்பகல்/மாலை நேரத்திற்கு PM-ஐ தேர்வு செய்யுங்கள்."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(AppLanguage.text("Notes", "Nota", "备注", "குறிப்புகள்")) }
                        )
                        Button(
                            onClick = {
                                vm.saveAppointment(
                                    editingAppointment,
                                    title,
                                    date,
                                    appointmentTime,
                                    appointmentPeriod,
                                    notes
                                ) {
                                    clearForm()
                                    showDataCollected = true
                                }
                            },
                            enabled = title.isNotBlank() && !state.isSaving,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA))
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                when {
                                    state.isSaving -> AppLanguage.text("Saving...", "Menyimpan...", "保存中……", "சேமிக்கிறது...")
                                    editingAppointment != null -> AppLanguage.text("Update Appointment", "Kemas Kini Temu Janji", "更新预约", "சந்திப்பைப் புதுப்பி")
                                    else -> AppLanguage.text("Save Appointment", "Simpan Temu Janji", "保存预约", "சந்திப்பைச் சேமி")
                                }
                            )
                        }
                        if (editingAppointment != null) {
                            OutlinedButton(onClick = { clearForm() }, modifier = Modifier.fillMaxWidth()) {
                                Text(AppLanguage.text("Cancel Editing", "Batal Mengedit", "取消编辑", "திருத்தத்தை ரத்துசெய்"))
                            }
                        }
                    }
                }
            }

            state.message?.let { message ->
                item {
                    val success = message.contains("saved", true) ||
                        message.contains("updated", true) ||
                        message.contains("disimpan", true) ||
                        message.contains("dikemas kini", true) ||
                        message.contains("deleted", true) ||
                        message.contains("dipadam", true) ||
                        message.contains("已保存") || message.contains("已更新") || message.contains("已删除") ||
                        message.contains("நீக்கப்பட்டது")
                    Text(message, color = if (success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    BlockingLoadingScreen(
        visible = appointmentBusy,
        title = if (state.deletingAppointmentId != null) {
            AppLanguage.text(
                "Deleting appointment...",
                "Memadam temu janji...",
                "正在删除预约……",
                "சந்திப்பு நீக்கப்படுகிறது..."
            )
        } else {
            AppLanguage.text(
                "Saving appointment...",
                "Menyimpan temu janji...",
                "正在保存预约……",
                "சந்திப்பு சேமிக்கப்படுகிறது..."
            )
        }
    )

    DataCollectedSuccessScreen(
        visible = showDataCollected,
        onDismiss = { showDataCollected = false }
    )
}
