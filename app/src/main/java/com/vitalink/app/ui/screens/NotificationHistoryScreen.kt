package com.vitalink.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitalink.app.reminders.LocalizedNotificationText
import com.vitalink.app.reminders.NotificationHistoryItem
import com.vitalink.app.reminders.NotificationHistoryStore
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.AppLanguageCode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationHistoryScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }

    LaunchedEffect(context) {
        AppLanguage.initialize(context)
        com.vitalink.app.reminders.AdminNotificationWorker.checkNow(context)
    }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(
            "myhfguard_notification_history",
            android.content.Context.MODE_PRIVATE
        )

        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "items") version++
            }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val language = AppLanguage.current
    val items = remember(version, language) {
        NotificationHistoryStore.read(context)
    }

    var filter by remember { mutableStateOf("all") }

    val unreadCount = items.count { !it.read }

    val filteredItems = items.filter { item ->
        val learning =
            item.route.contains("education", ignoreCase = true) ||
                    item.title.en.contains("learning", ignoreCase = true) ||
                    item.title.en.contains("video", ignoreCase = true)

        when (filter) {
            "unread" -> !item.read
            "health" -> !learning
            "learning" -> learning
            else -> true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            AppLanguage.text(
                                "Notifications",
                                "Notifikasi",
                                "通知记录",
                                "அறிவிப்புகள்"
                            ),
                            fontWeight = FontWeight.Bold
                        )

                        if (unreadCount > 0) {
                            Text(
                                "  $unreadCount",
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            NotificationHistoryStore.clear(context)
                            version++
                        }
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = AppLanguage.text(
                                "Clear notifications",
                                "Padam notifikasi",
                                "清除通知",
                                "அறிவிப்புகளை அழிக்கவும்"
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF5E35B1),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->

        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = Color(0xFF5E35B1)
                )

                Text(
                    AppLanguage.text(
                        "🔔 You’re all caught up",
                        "🔔 Semua telah dibaca",
                        "🔔 您已查看所有通知",
                        "🔔 அனைத்து அறிவிப்புகளும் பார்த்துவிட்டீர்கள்"
                    ),
                    modifier = Modifier.padding(top = 10.dp),
                    fontWeight = FontWeight.Bold
                )

                Text(
                    AppLanguage.text(
                        "No health warnings or learning alerts yet.",
                        "Tiada amaran kesihatan atau pembelajaran.",
                        "暂无健康警告或学习提醒。",
                        "சுகாதார எச்சரிக்கைகள் அல்லது கற்றல் நினைவூட்டல்கள் இல்லை."
                    ),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                item {
                    val filterLabels = listOf(
                        "all" to AppLanguage.text(
                            "All",
                            "Semua",
                            "全部",
                            "அனைத்தும்"
                        ),
                        "unread" to AppLanguage.text(
                            "Unread",
                            "Belum dibaca",
                            "未读",
                            "படிக்காதவை"
                        ),
                        "health" to AppLanguage.text(
                            "⚠️ Health",
                            "⚠️ Kesihatan",
                            "⚠️ 健康",
                            "⚠️ உடல்நலம்"
                        ),
                        "learning" to AppLanguage.text(
                            "📚 Learning",
                            "📚 Pembelajaran",
                            "📚 学习",
                            "📚 கற்றல்"
                        )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        filterLabels.forEach { (key, label) ->
                            FilterChip(
                                selected = filter == key,
                                onClick = { filter = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                if (filteredItems.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "🔎",
                                style = MaterialTheme.typography.headlineMedium
                            )

                            Text(
                                AppLanguage.text(
                                    "No notifications in this filter.",
                                    "Tiada notifikasi dalam penapis ini.",
                                    "此筛选条件没有通知。",
                                    "இந்த வடிப்பானில் அறிவிப்புகள் இல்லை."
                                ),
                                modifier = Modifier.padding(top = 8.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    var previousDate: LocalDate? = null

                    filteredItems.forEach { notification ->
                        val itemDate = runCatching {
                            Instant.parse(notification.timestamp)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }.getOrNull()

                        if (itemDate != null && itemDate != previousDate) {
                            item {
                                Text(
                                    text = if (itemDate == LocalDate.now()) {
                                        AppLanguage.text(
                                            "Today",
                                            "Hari ini",
                                            "今天",
                                            "இன்று"
                                        )
                                    } else {
                                        itemDate.toString()
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            previousDate = itemDate
                        }

                        item(key = notification.id) {
                            val displayCopy = localizedLegacyReminderCopy(
                                notification,
                                language
                            )

                            val isLearning =
                                notification.route.contains(
                                    "education",
                                    ignoreCase = true
                                ) ||
                                        notification.title.en.contains(
                                            "learning",
                                            ignoreCase = true
                                        ) ||
                                        notification.title.en.contains(
                                            "video",
                                            ignoreCase = true
                                        )

                            val iconColor = when {
                                isLearning -> Color(0xFF1976D2)

                                notification.title.en.contains(
                                    "critical",
                                    ignoreCase = true
                                ) ||
                                        notification.title.en.contains(
                                            "warning",
                                            ignoreCase = true
                                        ) -> Color(0xFFC62828)

                                else -> Color(0xFF5E35B1)
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        NotificationHistoryStore.markRead(
                                            context,
                                            notification.id
                                        )
                                        version++

                                        if (notification.route.isNotBlank()) {
                                            onNavigate(notification.route)
                                        }
                                    },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor =
                                        if (notification.read) {
                                            MaterialTheme.colorScheme.surface
                                        } else {
                                            Color(0xFFF3E5F5)
                                        }
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(15.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Notifications,
                                            contentDescription = null,
                                            tint = iconColor
                                        )

                                        Spacer(Modifier.width(8.dp))

                                        Text(
                                            displayCopy.first,
                                            modifier = Modifier.weight(1f),
                                            fontWeight = FontWeight.Bold
                                        )

                                        if (!notification.read) {
                                            Text("●", color = iconColor)
                                        }
                                    }

                                    Text(displayCopy.second)

                                    Text(
                                        formatNotificationTime(
                                            notification.timestamp,
                                            language
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun localizedLegacyReminderCopy(
    item: NotificationHistoryItem,
    language: AppLanguageCode
): Pair<String, String> {
    val translated = legacyReminderCopy(item.title.en, item.message.en)

    return if (translated != null) {
        translated.first.resolve(language) to translated.second.resolve(language)
    } else {
        item.title.resolve(language) to item.message.resolve(language)
    }
}

private fun legacyReminderCopy(
    title: String,
    message: String
): Pair<LocalizedNotificationText, LocalizedNotificationText>? {
    return when {
        title == "Salt reminder" &&
                message == "Please record today's salt intake." -> {
            Pair(
                LocalizedNotificationText(
                    "Salt reminder",
                    "Peringatan garam",
                    "盐分提醒",
                    "உப்பு நினைவூட்டல்"
                ),
                LocalizedNotificationText(
                    "Please record today's salt intake.",
                    "Sila rekod pengambilan garam hari ini.",
                    "请记录今天的盐分摄入量。",
                    "இன்று உப்பு உட்கொள்ளலைப் பதிவு செய்யுங்கள்."
                )
            )
        }

        title == "Water reminder" &&
                message == "Please record today's water intake." -> {
            Pair(
                LocalizedNotificationText(
                    "Water reminder",
                    "Peringatan air",
                    "饮水提醒",
                    "தண்ணீர் நினைவூட்டல்"
                ),
                LocalizedNotificationText(
                    "Please record today's water intake.",
                    "Sila rekod pengambilan air hari ini.",
                    "请记录今天的饮水量。",
                    "இன்று குடித்த தண்ணீரின் அளவைப் பதிவு செய்யுங்கள்."
                )
            )
        }

        title == "Symptom reminder" &&
                message == "Please complete today's symptom check." -> {
            Pair(
                LocalizedNotificationText(
                    "Symptom reminder",
                    "Peringatan simptom",
                    "症状提醒",
                    "அறிகுறி நினைவூட்டல்"
                ),
                LocalizedNotificationText(
                    "Please complete today's symptom check.",
                    "Sila lengkapkan semakan simptom hari ini.",
                    "请完成今天的症状检查。",
                    "இன்றைய அறிகுறி சரிபார்ப்பை முடிக்கவும்."
                )
            )
        }

        title == "Weight reminder" &&
                message == "Please record today's weight." -> {
            Pair(
                LocalizedNotificationText(
                    "Weight reminder",
                    "Peringatan berat",
                    "体重提醒",
                    "எடை நினைவூட்டல்"
                ),
                LocalizedNotificationText(
                    "Please record today's weight.",
                    "Sila rekod berat hari ini.",
                    "请记录今天的体重。",
                    "இன்றைய உங்கள் எடையைப் பதிவு செய்யுங்கள்."
                )
            )
        }

        title == "Blood pressure reminder" &&
                message == "Please record today's blood pressure." -> {
            Pair(
                LocalizedNotificationText(
                    "Blood pressure reminder",
                    "Peringatan tekanan darah",
                    "血压提醒",
                    "இரத்த அழுத்த நினைவூட்டல்"
                ),
                LocalizedNotificationText(
                    "Please record today's blood pressure.",
                    "Sila rekod tekanan darah hari ini.",
                    "请记录今天的血压。",
                    "இன்றைய உங்கள் இரத்த அழுத்தத்தைப் பதிவு செய்யுங்கள்."
                )
            )
        }

        else -> null
    }
}

private fun formatNotificationTime(
    raw: String,
    language: AppLanguageCode
): String {
    return runCatching {
        val instant = Instant
            .parse(raw)
            .atZone(ZoneId.systemDefault())

        val formatter = when (language) {
            AppLanguageCode.ENGLISH ->
                DateTimeFormatter.ofPattern(
                    "d MMM yyyy, h:mm a",
                    Locale.ENGLISH
                )

            AppLanguageCode.MALAY ->
                DateTimeFormatter.ofPattern(
                    "d MMM yyyy, h:mm a",
                    Locale("ms", "MY")
                )

            AppLanguageCode.MANDARIN ->
                DateTimeFormatter.ofPattern(
                    "yyyy年M月d日 HH:mm",
                    Locale.SIMPLIFIED_CHINESE
                )

            AppLanguageCode.TAMIL ->
                DateTimeFormatter.ofPattern(
                    "d MMM yyyy, h:mm a",
                    Locale("ta", "IN")
                )
        }

        instant.format(formatter)
    }.getOrElse {
        raw
    }
}