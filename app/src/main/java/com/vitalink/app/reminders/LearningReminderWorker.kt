package com.vitalink.app.reminders
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.vitalink.app.navigation.Route
import com.vitalink.app.util.*
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
class LearningReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val auth = ReminderApiClient.open(applicationContext) ?: return Result.success()
        val context = applicationContext
        AppLanguage.initialize(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val week = MalaysiaDateTime.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        val prefs = context.getSharedPreferences("weekly_learning",Context.MODE_PRIVATE)
        if (prefs.getString(auth.patientId,null) == week) return Result.success()
        val title = LocalizedNotificationText("Weekly learning reminder", "Peringatan pembelajaran mingguan", "每周学习提醒", "வாராந்திர கற்றல் நினைவூட்டல்")
        val message = LocalizedNotificationText("Watch a heart-health video this week and collect your coins after watching.", "Tonton video kesihatan jantung minggu ini dan kutip syiling selepas menonton.", "本周观看心脏健康视频，观看完成后领取金币。", "இந்த வாரம் இதய ஆரோக்கியக் காணொளியைப் பார்த்து நாணயங்களைப் பெறுங்கள்.")
        val sound = NotificationSoundChannels.Sound.EDUCATION
        val channel = NotificationSoundChannels.ensureChannel(context,sound,title.resolve(),message.resolve(),NotificationManager.IMPORTANCE_DEFAULT)
        val notification = NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title.resolve()).setContentText(message.resolve()).setStyle(NotificationCompat.BigTextStyle().bigText(message.resolve())).setContentIntent(NotificationNavigation.pendingIntent(context,Route.Education.path,5202)).setAutoCancel(true).build()
        return try {
            context.getSystemService(NotificationManager::class.java).notify(5202,notification)
            NotificationHistoryStore.record(context,title,message,Route.Education.path)
            prefs.edit().putString(auth.patientId,week).apply()
            Result.success()
        } catch (_: SecurityException) { Result.success() }
    }
    companion object {
        fun schedule(context: Context) {
            val now = MalaysiaDateTime.now()
            var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).withHour(10).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusWeeks(1)
            val request = PeriodicWorkRequestBuilder<LearningReminderWorker>(7,TimeUnit.DAYS).setInitialDelay(Duration.between(now,next).seconds,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("weekly_learning_v2",ExistingPeriodicWorkPolicy.KEEP,request)
        }
    }
}
