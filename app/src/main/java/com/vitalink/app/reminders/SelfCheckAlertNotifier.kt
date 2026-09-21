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
import com.vitalink.app.navigation.Route
import com.vitalink.app.util.AppLanguage

object SelfCheckAlertNotifier {

    fun notifyBloodPressure(context: Context, patientId: String, systolic: Int, diastolic: Int, pulse: Int) {
        AppLanguage.initialize(context)
        val critical = systolic >= 180 || diastolic >= 120 ||
            systolic < 80 || diastolic < 50 || pulse < 50 || pulse > 150
        val warning = systolic >= 140 || diastolic >= 90 || pulse < 60 || pulse > 100
        if (!critical && !warning) return

        val title = if (critical) {
            LocalizedNotificationText(
                "Critical blood pressure alert",
                "Amaran tekanan darah kritikal",
                "严重血压警报",
                "முக்கிய இரத்த அழுத்த எச்சரிக்கை"
            )
        } else {
            LocalizedNotificationText(
                "Blood pressure alert",
                "Amaran tekanan darah",
                "血压警报",
                "இரத்த அழுத்த எச்சரிக்கை"
            )
        }
        val action = if (critical) {
            LocalizedNotificationText(
                "Rest and seek urgent medical help if you have chest pain, severe breathlessness, fainting, confusion, or blue lips.",
                "Berehat dan dapatkan bantuan perubatan segera jika anda mengalami sakit dada, sesak nafas teruk, pengsan, keliru atau bibir kebiruan.",
                "请休息；若出现胸痛、严重呼吸困难、晕厥、意识混乱或嘴唇发蓝，请立即寻求医疗帮助。",
                "ஓய்வெடுக்கவும்; நெஞ்சுவலி, கடுமையான மூச்சுத்திணறல், மயக்கம், குழப்பம் அல்லது உதடு நீலமாகுதல் இருந்தால் உடனடி மருத்துவ உதவி பெறவும்."
            )
        } else {
            LocalizedNotificationText(
                "Rest for 5 minutes, recheck the reading, and contact your care team if it remains high or you feel unwell.",
                "Berehat selama 5 minit, periksa semula dan hubungi pasukan penjagaan jika bacaan kekal tinggi atau anda tidak sihat.",
                "请休息 5 分钟后重新测量；若读数仍高或您感到不适，请联系护理团队。",
                "5 நிமிடங்கள் ஓய்வெடுத்து மீண்டும் அளவிடவும்; அளவு தொடர்ந்து அதிகமாக இருந்தாலோ உடல்நலம் சரியில்லையென உணர்ந்தாலோ பராமரிப்பு குழுவை தொடர்புகொள்ளவும்."
            )
        }
        val message = LocalizedNotificationText(
            "BP $systolic/$diastolic mmHg, pulse $pulse bpm. ${action.en}",
            "BP $systolic/$diastolic mmHg, nadi $pulse bpm. ${action.ms}",
            "血压 $systolic/$diastolic mmHg，脉搏 $pulse bpm。${action.zh}",
            "இரத்த அழுத்தம் $systolic/$diastolic mmHg, நாடித் துடிப்பு $pulse bpm. ${action.ta}"
        )
        show(context, patientId, critical, title, message, Route.Dashboard.path, 6101, NotificationSoundChannels.Sound.BLOOD_PRESSURE)
    }

    fun notifySymptoms(context: Context, patientId: String, values: Map<String, Int>) {
        AppLanguage.initialize(context)
        val severeCount = values.values.count { it >= 4 }
        val total = values.values.sum()
        val critical = severeCount >= 3 || total >= 20
        val warning = severeCount >= 1 || total >= 12
        if (!critical && !warning) return

        val highKeys = values.filterValues { it >= 3 }.keys.toList()
        fun symptomNames(language: String): String = highKeys.joinToString(", ") { key ->
            when (language) {
                "ms" -> when (key.lowercase()) {
                    "cough" -> "Batuk"
                    "breathlessness" -> "Sesak nafas"
                    "swelling" -> "Bengkak"
                    "abdomen" -> "Ketidakselesaan perut"
                    "sleeping" -> "Sukar baring rata"
                    else -> key
                }
                "zh" -> when (key.lowercase()) {
                    "cough" -> "咳嗽"
                    "breathlessness" -> "呼吸困难"
                    "swelling" -> "肿胀"
                    "abdomen" -> "腹部不适"
                    "sleeping" -> "难以平躺"
                    else -> key
                }
                "ta" -> when (key.lowercase()) {
                    "cough" -> "இருமல்"
                    "breathlessness" -> "மூச்சுத்திணறல்"
                    "swelling" -> "வீக்கம்"
                    "abdomen" -> "வயிற்று அசௌகரியம்"
                    "sleeping" -> "படுக்க சிரமம்"
                    else -> key
                }
                else -> when (key.lowercase()) {
                    "cough" -> "Cough"
                    "breathlessness" -> "Breathlessness"
                    "swelling" -> "Swelling"
                    "abdomen" -> "Abdominal discomfort"
                    "sleeping" -> "Difficulty lying flat"
                    else -> key
                }
            }
        }

        val title = if (critical) {
            LocalizedNotificationText(
                "Critical symptom alert",
                "Amaran simptom kritikal",
                "严重症状警报",
                "முக்கிய அறிகுறி எச்சரிக்கை"
            )
        } else {
            LocalizedNotificationText(
                "Symptom alert",
                "Amaran simptom",
                "症状警报",
                "அறிகுறி எச்சரிக்கை"
            )
        }
        val action = if (critical) {
            LocalizedNotificationText(
                "Contact your care team urgently. Seek emergency help for chest pain, severe breathlessness, fainting, confusion, or blue lips.",
                "Hubungi pasukan penjagaan dengan segera. Dapatkan bantuan kecemasan untuk sakit dada, sesak nafas teruk, pengsan, keliru atau bibir kebiruan.",
                "请立即联系护理团队。若出现胸痛、严重呼吸困难、晕厥、意识混乱或嘴唇发蓝，请寻求紧急帮助。",
                "பராமரிப்பு குழுவை உடனடியாக தொடர்புகொள்ளவும். நெஞ்சுவலி, கடுமையான மூச்சுத்திணறல், மயக்கம், குழப்பம் அல்லது உதடு நீலமாகுதல் இருந்தால் அவசர உதவி பெறவும்."
            )
        } else {
            LocalizedNotificationText(
                "Rest, follow your care plan, and contact your care team if symptoms worsen or do not improve.",
                "Berehat, ikut pelan penjagaan dan hubungi pasukan penjagaan jika simptom bertambah teruk atau tidak pulih.",
                "请休息并遵循护理计划；若症状加重或没有改善，请联系护理团队。",
                "ஓய்வெடுத்து பராமரிப்பு திட்டத்தை பின்பற்றவும்; அறிகுறிகள் மோசமடைந்தாலோ மேம்படாவிட்டாலோ பராமரிப்பு குழுவை தொடர்புகொள்ளவும்."
            )
        }

        val enNames = symptomNames("en")
        val msNames = symptomNames("ms")
        val zhNames = symptomNames("zh")
        val taNames = symptomNames("ta")
        val message = LocalizedNotificationText(
            "Symptom score $total/25${if (enNames.isBlank()) "" else "; higher symptoms: $enNames"}. ${action.en}",
            "Skor simptom $total/25${if (msNames.isBlank()) "" else "; simptom lebih tinggi: $msNames"}. ${action.ms}",
            "症状评分 $total/25${if (zhNames.isBlank()) "" else "；较高症状：$zhNames"}。${action.zh}",
            "அறிகுறி மதிப்பெண் $total/25${if (taNames.isBlank()) "" else "; அதிகமான அறிகுறிகள்: $taNames"}. ${action.ta}"
        )
        show(context, patientId, critical, title, message, Route.Dashboard.path, 6102, NotificationSoundChannels.Sound.SYMPTOM)
    }

    private fun show(
        context: Context,
        patientId: String,
        critical: Boolean,
        title: LocalizedNotificationText,
        message: LocalizedNotificationText,
        route: String,
        id: Int,
        sound: NotificationSoundChannels.Sound
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        if (critical && !RedStatusNotificationGate.tryNotify(context, patientId)) return
        NotificationHistoryStore.record(context, title, message, route)

        val channelId = NotificationSoundChannels.ensureChannel(
            context = context,
            sound = sound,
            channelName = AppLanguage.text(
                "Self-Check Alerts",
                "Amaran Pemeriksaan Kendiri",
                "自我检查警报",
                "சுய சரிபார்ப்பு எச்சரிக்கைகள்"
            ),
            description = AppLanguage.text(
                "Immediate alerts after high blood pressure or symptom entries.",
                "Amaran segera selepas bacaan tekanan darah atau simptom yang tinggi.",
                "输入高血压或高症状后立即发出警报。",
                "அதிக இரத்த அழுத்தம் அல்லது அறிகுறி பதிவுக்குப் பிறகு உடனடி எச்சரிக்கைகள்."
            ),
            importance = NotificationManager.IMPORTANCE_HIGH,
            vibration = true
        )

        val currentTitle = title.resolve()
        val currentMessage = message.resolve()
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(currentTitle)
            .setContentText(currentMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(currentMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(NotificationSoundChannels.soundUri(context, sound))
            .setContentIntent(NotificationNavigation.pendingIntent(context, route, id))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure {
                if (critical) RedStatusNotificationGate.release(context, patientId, java.time.LocalDate.now().toString())
            }
    }
}
