package com.vitalink.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.vitalink.app.R
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.AppLanguageCode

/**
 * Central mapping between MyHFGuard notification types and the custom audio
 * supplied with the project.
 *
 * English, Bahasa Melayu, Mandarin and Tamil have separate audio resources.
 * The current app language is checked whenever a notification is created, so
 * each supported UI language plays its matching spoken notification audio.
 *
 * Android 8+ stores sound settings on notification channels, so the language
 * is included in the channel ID. This ensures that changing the app language
 * also changes the notification sound instead of Android reusing an older
 * channel sound (especially important on Samsung devices).
 */
object NotificationSoundChannels {

    enum class Sound(
        val channelKey: String,
        val englishRawResId: Int,
        val malayRawResId: Int,
        val mandarinRawResId: Int,
        val tamilRawResId: Int
    ) {
        APPOINTMENT(
            "appointment",
            R.raw.notification_appointment,
            R.raw.notification_appointment_ms,
            R.raw.notification_appointment_zh,
            R.raw.notification_appointment_ta
        ),
        BLOOD_PRESSURE(
            "blood_pressure",
            R.raw.notification_blood_pressure,
            R.raw.notification_blood_pressure_ms,
            R.raw.notification_blood_pressure_zh,
            R.raw.notification_blood_pressure_ta
        ),
        EDUCATION(
            "education",
            R.raw.notification_education,
            R.raw.notification_education_ms,
            R.raw.notification_education_zh,
            R.raw.notification_education_ta
        ),
        MEDICINE(
            "medicine",
            R.raw.notification_medicine,
            R.raw.notification_medicine_ms,
            R.raw.notification_medicine_zh,
            R.raw.notification_medicine_ta
        ),
        REFRESH_MI_BAND(
            "refresh_mi_band",
            R.raw.notification_refresh_mi_band,
            R.raw.notification_refresh_mi_band_ms,
            R.raw.notification_refresh_mi_band_zh,
            R.raw.notification_refresh_mi_band_ta
        ),
        LOG_ALL_DATA(
            "log_all_data",
            R.raw.notification_log_all_data,
            R.raw.notification_log_all_data_ms,
            R.raw.notification_log_all_data_zh,
            R.raw.notification_log_all_data_ta
        ),
        SALT(
            "salt",
            R.raw.notification_salt,
            R.raw.notification_salt_ms,
            R.raw.notification_salt_zh,
            R.raw.notification_salt_ta
        ),
        SYMPTOM(
            "symptom",
            R.raw.notification_symptom,
            R.raw.notification_symptom_ms,
            R.raw.notification_symptom_zh,
            R.raw.notification_symptom_ta
        ),
        STEPS(
            "steps",
            R.raw.notification_steps,
            R.raw.notification_steps_ms,
            R.raw.notification_steps_zh,
            R.raw.notification_steps_ta
        ),
        WATER(
            "water",
            R.raw.notification_water,
            R.raw.notification_water_ms,
            R.raw.notification_water_zh,
            R.raw.notification_water_ta
        ),
        WEIGHT(
            "weight",
            R.raw.notification_weight,
            R.raw.notification_weight_ms,
            R.raw.notification_weight_zh,
            R.raw.notification_weight_ta
        )
    }

    // Changing this suffix causes Android to create fresh channels, so existing
    // installations receive the matching custom sounds too.
    // Bump the channel namespace so existing installs recreate channels with
    // the current bundled medicine audio instead of keeping an older cached
    // channel sound chosen by Android.
    private const val CHANNEL_VERSION = "v9_system_audio"

    /** Refreshes the persisted app language before choosing a sound. */
    private fun soundLanguage(context: Context): AppLanguageCode {
        AppLanguage.initialize(context.applicationContext)
        return AppLanguage.current
    }

    private fun languageKey(context: Context): String = when (soundLanguage(context)) {
        AppLanguageCode.MALAY -> "ms"
        AppLanguageCode.MANDARIN -> "zh"
        AppLanguageCode.TAMIL -> "ta"
        else -> "en"
    }

    private fun rawResId(context: Context, sound: Sound): Int = when (soundLanguage(context)) {
        AppLanguageCode.MALAY -> sound.malayRawResId
        AppLanguageCode.MANDARIN -> sound.mandarinRawResId
        AppLanguageCode.TAMIL -> sound.tamilRawResId
        else -> sound.englishRawResId
    }

    fun channelId(context: Context, sound: Sound, importance: Int): String =
        "myhfguard_${sound.channelKey}_${languageKey(context)}_${importance}_$CHANNEL_VERSION"

    fun soundUri(context: Context, sound: Sound): Uri = Uri.parse(
        "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${rawResId(context, sound)}"
    )

    fun ensureChannel(
        context: Context,
        sound: Sound,
        channelName: String,
        description: String,
        importance: Int,
        vibration: Boolean = false
    ): String {
        val id = channelId(context, sound, importance)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(id) == null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val channel = NotificationChannel(id, channelName, importance).apply {
                    this.description = description
                    setSound(soundUri(context, sound), audioAttributes)
                    enableVibration(vibration)
                }
                manager.createNotificationChannel(channel)
            }
        }
        return id
    }
}
