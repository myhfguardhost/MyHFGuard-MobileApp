package com.vitalink.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalink.app.navigation.Route
import com.vitalink.app.util.*
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime

@Composable
internal fun dashboardClock(): State<LocalDateTime> = produceState(MalaysiaDateTime.now()) {
    while (true) { value = MalaysiaDateTime.now(); delay(1000) }
}

@Composable
internal fun CompactHealthOverview(state: DashState, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val bandMessage = AppLanguage.text("Use Sync Smart Band Information below to update HR and SpO₂.", "Gunakan Segerak Maklumat Smart Band di bawah untuk mengemas kini HR dan SpO₂.", "请使用下方的同步手环信息按钮更新心率和血氧。", "இதயத் துடிப்பு மற்றும் SpO₂-ஐ புதுப்பிக்க கீழுள்ள பட்டை ஒத்திசைவு பொத்தானைப் பயன்படுத்தவும்.")
    val summary = state.summary
    val hr = state.syncedHeartRate ?: summary?.avgHr?.toInt()
    val oxygen = state.syncedSpo2 ?: summary?.avgSpo2
    val bp = summary?.latestBp?.takeUnless { "BP" in state.missingToday }
    val weight = summary?.latestWeight?.takeUnless { "Weight" in state.missingToday }
    val steps = state.syncedSteps ?: summary?.steps?.toInt() ?: 0
    val missing = AppLanguage.text("Missing", "Belum isi", "未记录", "பதிவில்லை")
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AppLanguage.text("Health Overview", "Gambaran Kesihatan", "健康概览", "உடல்நலச் சுருக்கம்"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TargetStepsTopCard(state.targetSteps, steps)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewMetric("HR", Icons.Default.Favorite, hr?.let { "$it bpm" } ?: missing, hr?.let { it !in 60..100 }, Modifier.weight(1f)) { android.widget.Toast.makeText(context, bandMessage, android.widget.Toast.LENGTH_LONG).show() }
                OverviewMetric("SpO₂", Icons.Default.Bloodtype, oxygen?.let { "$it%" } ?: missing, oxygen?.let { it < 95 }, Modifier.weight(1f)) { android.widget.Toast.makeText(context, bandMessage, android.widget.Toast.LENGTH_LONG).show() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewMetric("BP", Icons.Default.MonitorHeart, bp?.let { "${it.systolic}/${it.diastolic}" } ?: missing, bp?.let { (it.systolic ?: 0) !in 100..130 || (it.diastolic ?: 0) !in 70..90 }, Modifier.weight(1f)) { navigate(Route.SelfCheck.focus("bp")) }
                OverviewMetric(AppLanguage.text("Weight", "Berat", "体重", "எடை"), Icons.Default.Scale, weight?.let { "%.1f kg".format(it) } ?: missing, weight?.let { w -> summary?.baselineWeight?.let { kotlin.math.abs(w-it) > 2.0 } ?: false }, Modifier.weight(1f)) { navigate(Route.SelfCheck.focus("weight")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewMetric(AppLanguage.text("Water", "Air", "饮水", "நீர்"), Icons.Default.WaterDrop, state.todayWaterIntakeMl?.let { "$it ml" } ?: missing, state.todayWaterIntakeMl?.let { it > (state.todayWaterLimitMl ?: Int.MAX_VALUE) }, Modifier.weight(1f)) { navigate(Route.WaterSalt.focus("water")) }
                OverviewMetric(AppLanguage.text("Salt level", "Tahap garam", "盐分水平", "உப்பு அளவு"), Icons.Default.Restaurant, state.todaySaltScore?.let { SaltScore.label(it) } ?: missing, state.todaySaltScore?.let { SaltScore.status(it) != "green" }, Modifier.weight(1f)) { navigate(Route.WaterSalt.focus("salt")) }
            }
        }
    }
}

@Composable
private fun OverviewMetric(label: String, icon: ImageVector, value: String, alert: Boolean?, modifier: Modifier, onClick: () -> Unit) {
    val background = when (alert) { null -> Color.White; true -> Color(0xFFFFE2BD); false -> Color(0xFFD9F0D7) }
    Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), color = background, border = BorderStroke(1.dp, Color(0xFFCBD5E1))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color(0xFF5E35B1))
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    fontSize = 16.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFF374151),
                    maxLines = 2,
                    softWrap = true
                )
            }
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF172032), maxLines = 2)
        }
    }
}

@Composable
internal fun UnifiedTodayAdvice(state: DashState, now: LocalDateTime, healthTitle: String, healthMessage: String, healthPriority: Boolean = false, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val checklistStore = remember(context) { MedicationChecklistStore(context) }
    var doseChecks by remember(state.patientId, checklistStore) { mutableStateOf(checklistStore.read(state.patientId)) }
    val today = now.toLocalDate()
    val dueSlots = MedicationChecklist.dueSlots(now)
    LaunchedEffect(state.patientId, today, dueSlots, state.profileMedications, state.medications, state.medicationSchedules) {
        doseChecks = checklistStore.update(
            state.patientId,
            dueSlots.associateWith { slot ->
                MedicationTiming.due(state.profileMedications, state.medications, state.medicationSchedules, slot)
            },
            now
        )
    }
    val medicineGroups = MedicationChecklist.visible(doseChecks, state.patientId, today)
        .groupBy { it.date to it.slot }
    val reminderAppointments = state.reminders.filter { it.type.equals("appointment", true) && it.status?.lowercase() !in setOf("cancelled", "canceled", "completed", "done") }.mapNotNull { row ->
        val due = MalaysiaDateTime.parseTimestamp(row.due_ts) ?: return@mapNotNull null
        com.vitalink.app.data.model.Appointment(id = row.id, title = row.title, appointment_date = due.toLocalDate().toString(), appointment_time = due.toLocalTime().toString(), notes = row.notes)
    }
    val appointment = (state.appointments + reminderAppointments).mapNotNull { item ->
        val due = runCatching { LocalDateTime.of(java.time.LocalDate.parse(item.appointment_date?.take(10)), java.time.LocalTime.parse(item.appointment_time?.take(5))) }.getOrNull()
        due?.let { item to it }
    }.filter { (_, due) ->
        AppointmentAdviceWindow.visible(now, due)
    }.minByOrNull { it.second }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E8F8))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("💡 " + AppLanguage.text("Today's Advice", "Nasihat Hari Ini", "今日建议", "இன்றைய ஆலோசனை"), fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("🩺 " + healthTitle, fontWeight = FontWeight.SemiBold)
            Text(healthMessage, fontSize = 17.sp)
            medicineGroups.forEach { (group, medicines) ->
                val (doseDate, slot) = group
                HorizontalDivider()
                Text("💊 " + if (slot == MedicationTiming.NIGHT) AppLanguage.text("Night medicines", "Ubat malam", "晚间用药", "இரவு மருந்துகள்")
                    else AppLanguage.text("Noon medicines", "Ubat tengah hari", "午间用药", "மதிய மருந்துகள்"), fontWeight = FontWeight.Bold)
                if (doseDate != today.toString()) {
                    Text(
                        AppLanguage.text("Checklist date: ", "Tarikh senarai semak: ", "用药记录日期：", "சரிபார்ப்புப் பட்டியல் தேதி: ") + doseDate,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                medicines.forEach { dose ->
                    key(dose.id) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = dose.completed,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    doseChecks = checklistStore.setCompleted(state.patientId, dose.id, checked, MalaysiaDateTime.now())
                                }
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = dose.completed, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(listOf(dose.name, dose.dosage).filter { it.isNotBlank() }.joinToString(" · "))
                                Text(
                                    if (dose.completed) AppLanguage.text("Completed", "Selesai", "已完成", "முடிந்தது")
                                    else AppLanguage.text("Not marked as taken", "Belum ditandakan sebagai diambil", "尚未勾选已服用", "எடுத்ததாகக் குறிக்கப்படவில்லை"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (dose.completed) Color(0xFF087F5B) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            appointment?.let { (item, due) ->
                HorizontalDivider()
                Text("📅 " + if (due.toLocalDate() == now.toLocalDate().plusDays(1)) AppLanguage.text("Tomorrow's appointment", "Temu janji esok", "明天的预约", "நாளைய சந்திப்பு")
                    else AppLanguage.text("Today's appointment", "Temu janji hari ini", "今天的预约", "இன்றைய சந்திப்பு"), fontWeight = FontWeight.Bold)
                Text(item.title.orEmpty())
                Text(due.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy · 🕒 h:mm a")))
                item.notes?.takeIf { it.isNotBlank() }?.let { Text(it) }
            }
        }
    }
}
