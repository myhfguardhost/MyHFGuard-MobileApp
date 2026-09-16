package com.vitalink.app.ui.screens

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.api.ServerApiService
import com.vitalink.app.data.model.AddBpRequest
import com.vitalink.app.data.model.BpEvent
import com.vitalink.app.data.model.PatientUpsert
import com.vitalink.app.data.model.ServerEnsurePatientRequest
import com.vitalink.app.data.model.ServerBpManualRequest
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.ui.theme.CautionRed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class VitalsState(
    val events: List<BpEvent> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

@HiltViewModel
class VitalsViewModel @Inject constructor(private val api: ApiService, private val serverApi: ServerApiService, private val session: SessionManager) : ViewModel() {
    private val _s = MutableStateFlow(VitalsState())
    val state = _s.asStateFlow()
    private var pid = ""
    private var email: String? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch { session.userEmail.collect { email = it } }
        viewModelScope.launch {
            session.patientId.filterNotNull().collect { id ->
                pid = id
                ensurePatientRow()
                fetch()
            }
        }
    }

    private suspend fun ensurePatientRow(): Boolean {
        if (pid.isBlank()) return false
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

        val existing = runCatching { api.getPatient("eq.$pid") }.getOrNull()
        if (existing?.isSuccessful == true && !existing.body().isNullOrEmpty()) return true
        val inserted = runCatching { api.upsertPatient(PatientUpsert(patientId = pid, firstName = namePart, lastName = "User")) }.getOrNull()
        return inserted?.isSuccessful == true || runCatching { api.getPatient("eq.$pid").body().orEmpty().isNotEmpty() }.getOrDefault(false)
    }

    fun fetch() {
        if (pid.isBlank()) return
        viewModelScope.launch {
            _s.update { it.copy(loading = true) }
            try {
                val r = api.getBpEvents("eq.$pid")
                _s.update { it.copy(events = r.body() ?: emptyList(), loading = false) }
            } catch (e: Exception) { _s.update { it.copy(loading = false, error = UserFacingError.from(e, Action.LOAD)) } }
        }
    }

    fun save(sys: Int, dia: Int, pulse: Int) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _s.update { it.copy(saving = true, error = null, saved = false) }
            try {
                if (pid.isBlank()) {
                    _s.update { it.copy(saving = false, error = "Cannot save vital reading because patient ID is empty. Please logout and login again.") }
                    return@launch
                }

                if (!ensurePatientRow()) {
                    _s.update { it.copy(saving = false, error = "Cannot create/find patient row. Please check patients INSERT/SELECT RLS policy.") }
                    return@launch
                }

                val timeTs = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.OffsetDateTime.now())
                val r = serverApi.addManualBp(
                    ServerBpManualRequest(
                        value1 = sys,
                        value2 = dia,
                        value3 = pulse,
                        patientId = pid,
                        timeTs = timeTs
                    )
                )

                if (!r.isSuccessful) {
                    _s.update { it.copy(saving = false, error = UserFacingError.http(r.code(), Action.SAVE)) }
                    return@launch
                }

                val latestResp = api.getBpEvents("eq.$pid")
                val latest = if (latestResp.isSuccessful) latestResp.body().orEmpty() else emptyList()
                _s.update { it.copy(events = latest, saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _s.update { it.copy(saving = false, error = UserFacingError.from(e, Action.SAVE)) }
            }
        }
    }

    fun cancelSave() {
        saveJob?.cancel()
        saveJob = null
        _s.update {
            it.copy(
                saving = false,
                error = AppLanguage.text(
                    "Save cancelled. You can edit and try again.",
                    "Simpanan dibatalkan. Anda boleh mengedit dan cuba lagi.",
                    "保存已取消，您可以修改后重试。",
                    "சேமிப்பு ரத்துசெய்யப்பட்டது. திருத்தி மீண்டும் முயற்சிக்கலாம்."
                )
            )
        }
    }

    fun clearSaved() { _s.update { it.copy(saved = false) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsScreen(onBack: () -> Unit, vm: VitalsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val ms = AppLanguage.useMalay
    var tab by remember { mutableIntStateOf(0) }
    var sys by remember { mutableStateOf("") }
    var dia by remember { mutableStateOf("") }
    var pulse by remember { mutableStateOf("") }
    var showDataCollected by remember { mutableStateOf(false) }

    LaunchedEffect(s.saved) {
        if (s.saved) {
            sys = ""
            dia = ""
            pulse = ""
            showDataCollected = true
            vm.clearSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AppLanguage.text("Vitals Tracker", "Jejak Bacaan Kesihatan"), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, AppLanguage.text("Back", "Kembali")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = Color.White, navigationIconContentColor = Color.White)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(AppLanguage.text("Manual", "Manual")) }, icon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) })
                Tab(selected = tab == 1, onClick = { tab = 1; vm.fetch() }, text = { Text(AppLanguage.text("History", "Sejarah")) }, icon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) })
            }
            when (tab) {
                0 -> ManualBpTab(sys, dia, pulse, { sys = it }, { dia = it }, { pulse = it }, s.saving, s.error, s.saved) {
                    val sv = sys.toIntOrNull(); val dv = dia.toIntOrNull(); val pv = pulse.toIntOrNull()
                    if (sv != null && dv != null && pv != null) vm.save(sv, dv, pv)
                }
                1 -> HistoryBpTab(s.events, s.loading)
            }
        }
    }

    BlockingLoadingScreen(
        visible = s.saving,
        title = AppLanguage.text(
            "Saving vital reading...",
            "Menyimpan bacaan vital...",
            "正在保存生命体征……",
            "உயிரளவு சேமிக்கப்படுகிறது..."
        ),
        onCancel = vm::cancelSave
    )

    DataCollectedSuccessScreen(
        visible = showDataCollected,
        onDismiss = { showDataCollected = false }
    )
}

@Composable
fun ManualBpTab(sys: String, dia: String, pulse: String, onSys: (String) -> Unit, onDia: (String) -> Unit, onPulse: (String) -> Unit, loading: Boolean, error: String?, saved: Boolean, onSave: () -> Unit) {
    val ms = AppLanguage.useMalay
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(AppLanguage.text("Enter Blood Pressure Reading", "Masukkan Bacaan Tekanan Darah"), fontWeight = FontWeight.Bold)
                Text(AppLanguage.text("From your BP monitor or device", "Daripada monitor tekanan darah atau peranti anda"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
            }
        }
        NumberRollField(
            value = sys,
            values = (70..220).toList(),
            label = AppLanguage.text("Systolic (mmHg)", "Sistolik (mmHg)"),
            icon = Icons.Default.ArrowUpward,
            onChange = { onSys(it.toString()) }
        )
        NumberRollField(
            value = dia,
            values = (40..140).toList(),
            label = AppLanguage.text("Diastolic (mmHg)", "Diastolik (mmHg)"),
            icon = Icons.Default.ArrowDownward,
            onChange = { onDia(it.toString()) }
        )
        NumberRollField(
            value = pulse,
            values = (40..160).toList(),
            label = AppLanguage.text("Pulse (bpm)", "Nadi (bpm)"),
            icon = Icons.Default.Favorite,
            onChange = { onPulse(it.toString()) }
        )

        if (error != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
        if (saved) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32)); Spacer(Modifier.width(8.dp)); Text(AppLanguage.text("Reading saved!", "Bacaan disimpan!"), color = Color(0xFF2E7D32))
            }
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !loading && sys.isNotBlank() && dia.isNotBlank() && pulse.isNotBlank(), shape = RoundedCornerShape(12.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text(AppLanguage.text("Save Reading", "Simpan Bacaan")) }
        }
    }
}

@Composable
private fun NumberRollField(
    value: String,
    values: List<Int>,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = value.ifBlank { "--" }

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp)
        ) {
            Icon(icon, null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(shown, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.KeyboardArrowDown, null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp)
        ) {
            values.forEach { n ->
                DropdownMenuItem(
                    text = { Text(n.toString(), fontWeight = if (value == n.toString()) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { onChange(n); expanded = false }
                )
            }
        }
    }
}

@Composable
fun HistoryBpTab(events: List<BpEvent>, loading: Boolean) {
    val ms = AppLanguage.useMalay
    if (loading) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.History, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f))
                Spacer(Modifier.height(12.dp))
                Text(AppLanguage.text("No readings yet", "Belum ada bacaan"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events) { BpCard(it) }
    }
}

@Composable
fun BpCard(e: BpEvent) {
    val ms = AppLanguage.useMalay
    val (statusLabel, statusColor) = when {
        (e.systolic ?: 0) >= 140 || (e.diastolic ?: 0) >= 90 -> (AppLanguage.text("High", "Tinggi")) to CautionRed
        (e.systolic ?: 0) < 90  || (e.diastolic ?: 0) < 60  -> (AppLanguage.text("Low", "Rendah"))  to Color(0xFF1E88E5)
        else -> (AppLanguage.text("Normal", "Normal")) to Color(0xFF43A047)
    }
    val dateStr = try {
        val raw = e.recorded_at ?: listOfNotNull(e.reading_date, e.reading_time).joinToString("T")
        val dt = if (raw.endsWith("Z")) Instant.parse(raw).atZone(ZoneId.systemDefault()) else java.time.LocalDateTime.parse(raw).atZone(ZoneId.systemDefault())
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").format(dt)
    } catch (_: Exception) {
        listOfNotNull(e.reading_date, e.reading_time).joinToString(" ").ifBlank { "-" }
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${e.systolic ?: "--"}/${e.diastolic ?: "--"}", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(" mmHg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.pulse != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(3.dp))
                    Text("${e.pulse} bpm", style = MaterialTheme.typography.bodySmall)
                }
                Text(dateStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(statusLabel, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}
