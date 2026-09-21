package com.vitalink.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vitalink.app.data.model.SymptomLog
import com.vitalink.app.navigation.Route
import com.vitalink.app.util.AppLanguage
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class HealthDeviationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        AppLanguage.initialize(context)
        return try {
            val auth = ReminderApiClient.open(context) ?: return Result.success()
            val filter = "eq.${auth.patientId}"
            val todayDate = com.vitalink.app.util.MalaysiaDateTime.today()
            val today = todayDate.toString()
            val redReasons = mutableListOf<LocalizedNotificationText>()
            val warningReasons = mutableListOf<LocalizedNotificationText>()

            val bp = runCatching {
                auth.api.getBpEvents(filter).body().orEmpty().firstOrNull {
                    it.reading_date == today || it.recorded_at?.take(10) == today
                }
            }.getOrNull()
            bp?.systolic?.let { value ->
                when {
                    value >= 180 || value < 80 -> redReasons += LocalizedNotificationText("Systolic BP is $value mmHg", "Tekanan darah sistolik ialah $value mmHg", "收缩压为 $value mmHg", "சிஸ்டாலிக் இரத்த அழுத்தம் $value mmHg")
                    value in 140..179 -> warningReasons += LocalizedNotificationText("Systolic BP is $value mmHg", "Tekanan darah sistolik ialah $value mmHg", "收缩压为 $value mmHg", "சிஸ்டாலிக் இரத்த அழுத்தம் $value mmHg")
                }
            }
            bp?.diastolic?.let { value ->
                when {
                    value >= 120 || value < 50 -> redReasons += LocalizedNotificationText("Diastolic BP is $value mmHg", "Tekanan darah diastolik ialah $value mmHg", "舒张压为 $value mmHg", "டயஸ்டாலிக் இரத்த அழுத்தம் $value mmHg")
                    value in 90..119 -> warningReasons += LocalizedNotificationText("Diastolic BP is $value mmHg", "Tekanan darah diastolik ialah $value mmHg", "舒张压为 $value mmHg", "டயஸ்டாலிக் இரத்த அழுத்தம் $value mmHg")
                }
            }
            bp?.pulse?.let { value ->
                when {
                    value < 50 || value > 150 -> redReasons += LocalizedNotificationText("Pulse is $value bpm", "Nadi ialah $value bpm", "脉搏为 $value bpm", "நாடித் துடிப்பு $value bpm")
                    value < 60 || value > 100 -> warningReasons += LocalizedNotificationText("Pulse is $value bpm", "Nadi ialah $value bpm", "脉搏为 $value bpm", "நாடித் துடிப்பு $value bpm")
                }
            }

            val symptomRows = runCatching {
                auth.api.getSymptomLogs(filter).body().orEmpty()
            }.getOrDefault(emptyList())
            val todaySymptoms = symptomRows.firstOrNull { symptomDate(it) == today }
            todaySymptoms?.let { symptoms ->
                val scores = symptomScores(symptoms)
                val severeCount = scores.count { it >= 4 }
                val moderateCount = scores.count { it >= 3 }
                when {
                    severeCount >= 3 -> redReasons += LocalizedNotificationText("$severeCount symptoms are severe today", "$severeCount simptom teruk hari ini", "今天有 $severeCount 项严重症状", "இன்று $severeCount கடுமையான அறிகுறிகள் உள்ளன")
                    moderateCount >= 3 -> warningReasons += LocalizedNotificationText("$moderateCount symptoms are in the warning zone today", "$moderateCount simptom berada dalam zon amaran hari ini", "今天有 $moderateCount 项症状处于警告区", "இன்று $moderateCount அறிகுறிகள் எச்சரிக்கை நிலையில் உள்ளன")
                }
            }
            val severeForThreeDays = (0L..2L).all { daysAgo ->
                val date = todayDate.minusDays(daysAgo).toString()
                symptomRows.firstOrNull { symptomDate(it) == date }
                    ?.let { symptomScores(it).count { score -> score >= 4 } >= 2 } == true
            }
            if (severeForThreeDays) {
                redReasons += LocalizedNotificationText("At least 2 severe symptoms were recorded for 3 consecutive days", "Sekurang-kurangnya 2 simptom teruk direkodkan selama 3 hari berturut-turut", "连续 3 天记录到至少 2 项严重症状", "தொடர்ந்து 3 நாட்கள் குறைந்தது 2 கடுமையான அறிகுறிகள் பதிவாகியுள்ளன")
            }

            val waterSalt = runCatching {
                auth.api.getWaterLogs(filter).body().orEmpty().firstOrNull {
                    it.entry_date == today || it.logged_at?.take(10) == today
                }
            }.getOrNull()
            waterSalt?.let { row ->
                val intake = row.water_intake_ml ?: row.water_ml
                val limit = row.water_limit_ml
                if (intake != null && limit != null && limit > 0) {
                    when {
                        intake > limit + 200 -> redReasons +=
                            LocalizedNotificationText("Fluid intake is ${intake - limit} ml above the prescribed limit", "Pengambilan cecair melebihi had yang ditetapkan sebanyak ${intake - limit} ml", "液体摄入量超过规定限制 ${intake - limit} ml", "திரவ உட்கொள்ளல் பரிந்துரைக்கப்பட்ட வரம்பை ${intake - limit} ml மீறியுள்ளது")
                        intake > limit -> warningReasons +=
                            LocalizedNotificationText("Fluid intake is ${intake - limit} ml above the prescribed limit", "Pengambilan cecair melebihi had yang ditetapkan sebanyak ${intake - limit} ml", "液体摄入量超过规定限制 ${intake - limit} ml", "திரவ உட்கொள்ளல் பரிந்துரைக்கப்பட்ட வரம்பை ${intake - limit} ml மீறியுள்ளது")
                    }
                }
                when {
                    row.salt_score != null && row.salt_score >= 7 ->
                        warningReasons += LocalizedNotificationText("Salt score is high (${com.vitalink.app.util.SaltScore.display(row.salt_score)}/9)", "Skor garam tinggi (${com.vitalink.app.util.SaltScore.display(row.salt_score)}/9)", "盐分评分偏高（${com.vitalink.app.util.SaltScore.display(row.salt_score)}/9）", "உப்பு மதிப்பெண் அதிகம் (${com.vitalink.app.util.SaltScore.display(row.salt_score)}/9)")
                    row.salt_status.equals("red", ignoreCase = true) ->
                        warningReasons += LocalizedNotificationText("Salt intake status is high", "Status pengambilan garam tinggi", "盐分摄入状态偏高", "உப்பு உட்கொள்ளல் நிலை அதிகம்")
                }
            }

            val weights = runCatching {
                auth.api.getWeightDay(filter).body().orEmpty().sortedByDescending { it.date }
            }.getOrDefault(emptyList())
            val latest = weights.firstOrNull { it.date == today }?.kg_avg
            val previous = weights.firstOrNull { it.date < today }?.kg_avg
            val severalDaysAgo = weights.firstOrNull {
                runCatching { LocalDate.parse(it.date) <= todayDate.minusDays(2) }.getOrDefault(false)
            }?.kg_avg
            val baselineWeight = runCatching {
                auth.api.getProfile(filter).body().orEmpty().firstOrNull()?.dry_weight
            }.getOrNull()

            if (latest != null && previous != null) {
                val gain = latest - previous
                when {
                    gain >= 3.0 -> redReasons += LocalizedNotificationText("Weight increased by ${"%.1f".format(gain)} kg", "Berat meningkat sebanyak ${"%.1f".format(gain)} kg", "体重增加了 ${"%.1f".format(gain)} kg", "எடை ${"%.1f".format(gain)} கி.கி. அதிகரித்துள்ளது")
                    gain >= 1.5 -> warningReasons += LocalizedNotificationText("Weight increased by ${"%.1f".format(gain)} kg", "Berat meningkat sebanyak ${"%.1f".format(gain)} kg", "体重增加了 ${"%.1f".format(gain)} kg", "எடை ${"%.1f".format(gain)} கி.கி. அதிகரித்துள்ளது")
                }
            }
            if (latest != null && severalDaysAgo != null) {
                val gain = latest - severalDaysAgo
                when {
                    gain >= 3.0 -> redReasons += LocalizedNotificationText("Weight increased by ${"%.1f".format(gain)} kg over several days", "Berat meningkat sebanyak ${"%.1f".format(gain)} kg dalam beberapa hari", "数天内体重增加了 ${"%.1f".format(gain)} kg", "பல நாட்களில் எடை ${"%.1f".format(gain)} கி.கி. அதிகரித்துள்ளது")
                    gain >= 2.0 -> warningReasons += LocalizedNotificationText("Weight increased by ${"%.1f".format(gain)} kg over several days", "Berat meningkat sebanyak ${"%.1f".format(gain)} kg dalam beberapa hari", "数天内体重增加了 ${"%.1f".format(gain)} kg", "பல நாட்களில் எடை ${"%.1f".format(gain)} கி.கி. அதிகரித்துள்ளது")
                }
            }
            if (latest != null && baselineWeight != null) {
                val aboveBaseline = latest - baselineWeight
                when {
                    aboveBaseline >= 5.0 -> redReasons +=
                        LocalizedNotificationText("Weight is ${"%.1f".format(aboveBaseline)} kg above baseline", "Berat ${"%.1f".format(aboveBaseline)} kg melebihi garis dasar", "体重比基线高 ${"%.1f".format(aboveBaseline)} kg", "எடை அடிப்படை அளவை விட ${"%.1f".format(aboveBaseline)} கி.கி. அதிகம்")
                    aboveBaseline >= 3.0 -> warningReasons +=
                        LocalizedNotificationText("Weight is ${"%.1f".format(aboveBaseline)} kg above baseline", "Berat ${"%.1f".format(aboveBaseline)} kg melebihi garis dasar", "体重比基线高 ${"%.1f".format(aboveBaseline)} kg", "எடை அடிப்படை அளவை விட ${"%.1f".format(aboveBaseline)} கி.கி. அதிகம்")
                }
            }

            when {
                redReasons.isNotEmpty() -> notifyOnce(context, auth.patientId, today, true, redReasons)
                warningReasons.isNotEmpty() -> notifyOnce(context, auth.patientId, today, false, warningReasons)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun t(en: String, ms: String, zh: String, ta: String): String =
        AppLanguage.text(en, ms, zh, ta)

    private fun symptomDate(row: SymptomLog): String? =
        row.date ?: row.logged_at?.take(10) ?: row.created_at?.take(10)

    private fun symptomScores(row: SymptomLog): List<Int> = listOf(
        row.cough ?: 0,
        row.sob_activity ?: 0,
        row.leg_swelling ?: 0,
        row.abd_discomfort ?: 0,
        row.orthopnea ?: 0
    )

    private fun notifyOnce(
        context: Context,
        patientId: String,
        date: String,
        critical: Boolean,
        reasons: List<LocalizedNotificationText>
    ) {
        val severity = if (critical) "critical" else "warning"
        val key = "$patientId|$date|$severity|${reasons.joinToString("|") { it.en }}".hashCode().toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!notificationsAllowed(context)) return
        if (critical) {
            if (!RedStatusNotificationGate.tryNotify(context, patientId, date)) return
        } else if (prefs.getString(LAST_KEY, null) == key) {
            return
        }
        val sound = soundForReasons(reasons)
        val channelId = NotificationSoundChannels.ensureChannel(
            context = context,
            sound = sound,
            channelName = t("Health Alerts", "Amaran Kesihatan", "健康警报", "உடல்நல எச்சரிக்கைகள்"),
            description = t(
                "Warning and critical health-reading alerts.",
                "Amaran bacaan kesihatan tahap amaran dan kritikal.",
                "健康读数的警告和严重警报。",
                "எச்சரிக்கை மற்றும் முக்கிய உடல்நல அளவு அறிவிப்புகள்."
            ),
            importance = NotificationManager.IMPORTANCE_HIGH,
            vibration = true
        )

        val titleText = if (critical) {
            LocalizedNotificationText(
                "Critical health alert",
                "Amaran kesihatan kritikal",
                "严重健康警报",
                "முக்கிய உடல்நல எச்சரிக்கை"
            )
        } else {
            LocalizedNotificationText(
                "Health warning",
                "Amaran kesihatan",
                "健康警告",
                "உடல்நல எச்சரிக்கை"
            )
        }
        val actionText = if (critical) {
            LocalizedNotificationText(
                "Rest and seek urgent medical help if you have chest pain, severe breathlessness, fainting, confusion, or blue lips.",
                "Berehat dan dapatkan bantuan perubatan segera jika anda mengalami sakit dada, sesak nafas teruk, pengsan, keliru atau bibir kebiruan.",
                "请休息；若出现胸痛、严重呼吸困难、晕厥、意识混乱或嘴唇发蓝，请立即寻求医疗帮助。",
                "ஓய்வெடுக்கவும்; நெஞ்சுவலி, கடுமையான மூச்சுத்திணறல், மயக்கம், குழப்பம் அல்லது உதடு நீலமாகுதல் இருந்தால் உடனடி மருத்துவ உதவி பெறவும்."
            )
        } else {
            LocalizedNotificationText(
                "Rest, recheck the reading, and contact your care team if it remains abnormal or you feel unwell.",
                "Berehat, periksa semula bacaan dan hubungi pasukan penjagaan jika bacaan masih tidak normal atau anda tidak sihat.",
                "请休息并重新测量；若读数仍异常或您感到不适，请联系护理团队。",
                "ஓய்வெடுத்து அளவை மீண்டும் சரிபார்க்கவும்; அது தொடர்ந்து அசாதாரணமாக இருந்தாலோ உடல்நலம் சரியில்லையென உணர்ந்தாலோ பராமரிப்பு குழுவை தொடர்புகொள்ளவும்."
            )
        }
        val selectedReasons = reasons.take(3)
        val messageText = LocalizedNotificationText(
            selectedReasons.joinToString(". ") { it.en } + ". ${actionText.en}",
            selectedReasons.joinToString(". ") { it.ms } + ". ${actionText.ms}",
            selectedReasons.joinToString("。") { it.zh } + "。${actionText.zh}",
            selectedReasons.joinToString(". ") { it.ta } + ". ${actionText.ta}"
        )
        val title = titleText.resolve()
        val message = messageText.resolve()
        NotificationHistoryStore.record(context, titleText, messageText, Route.Dashboard.path)
        val notificationId = if (critical) 5301 else 5302
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(NotificationSoundChannels.soundUri(context, sound))
            .setContentIntent(
                NotificationNavigation.pendingIntent(context, Route.Dashboard.path, notificationId)
            )
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            if (!critical) prefs.edit().putString(LAST_KEY, key).apply()
        } catch (_: SecurityException) {
            if (critical) RedStatusNotificationGate.release(context, patientId, date)
        }
    }

    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun soundForReasons(reasons: List<LocalizedNotificationText>): NotificationSoundChannels.Sound {
        val text = reasons.joinToString(" ") { it.en }.lowercase()
        return when {
            "systolic bp" in text || "diastolic bp" in text || "pulse" in text ->
                NotificationSoundChannels.Sound.BLOOD_PRESSURE
            "symptom" in text -> NotificationSoundChannels.Sound.SYMPTOM
            "fluid intake" in text -> NotificationSoundChannels.Sound.WATER
            "salt" in text -> NotificationSoundChannels.Sound.SALT
            "weight" in text -> NotificationSoundChannels.Sound.WEIGHT
            else -> NotificationSoundChannels.Sound.LOG_ALL_DATA
        }
    }


    companion object {
        private const val WORK_NAME = "health_deviation_alert"
        private const val PREFS = "health_alert_preferences"
        private const val LAST_KEY = "last_alert_key"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthDeviationWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
