package com.vitalink.app.reminders

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vitalink.app.data.api.dataStore
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.AppLanguageCode
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

data class LocalizedNotificationText(
    val en: String,
    val ms: String,
    val zh: String,
    val ta: String
) {
    fun resolve(language: AppLanguageCode = AppLanguage.current): String = when (language) {
        AppLanguageCode.ENGLISH -> en
        AppLanguageCode.MALAY -> ms
        AppLanguageCode.MANDARIN -> zh
        AppLanguageCode.TAMIL -> ta
    }
}

data class NotificationHistoryItem(
    val id: String,
    val title: LocalizedNotificationText,
    val message: LocalizedNotificationText,
    val timestamp: String,
    val route: String,
    val read: Boolean,
    /** Patient the notification belongs to; the bell only shows the signed-in patient's. */
    val owner: String = ""
)

object NotificationHistoryStore {
    private const val PREFS = "myhfguard_notification_history"
    private const val KEY_ITEMS = "items"
    private const val KEY_ACTIVE_PATIENT = "active_patient"
    // Kept per patient, so several accounts on one phone each keep their own.
    private const val MAX_ITEMS = 300
    private const val MAX_ITEMS_PER_PATIENT = 100

    /**
     * Called on login and logout. Each account on the phone sees only its own
     * history; switching back to an account shows its history again.
     */
    @Synchronized
    fun setActivePatient(context: Context, patientId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_PATIENT, patientId.orEmpty())
            .apply()
    }

    /** The signed-in patient. Falls back to the saved session for users signed in before this update. */
    private fun activePatient(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ACTIVE_PATIENT)) return prefs.getString(KEY_ACTIVE_PATIENT, "").orEmpty()
        val fromSession = runCatching {
            runBlocking { context.dataStore.data.first()[stringPreferencesKey("patient_id")] }
        }.getOrNull().orEmpty()
        prefs.edit().putString(KEY_ACTIVE_PATIENT, fromSession).apply()
        return fromSession
    }

    /**
     * Backward-compatible method for older callers. The same text is stored for
     * all languages, while new notification producers should use the localized overload.
     */
    @Synchronized
    fun record(context: Context, title: String, message: String, route: String, at: Instant = Instant.now()) {
        record(
            context = context,
            title = LocalizedNotificationText(title, title, title, title),
            message = LocalizedNotificationText(message, message, message, message),
            route = route,
            at = at
        )
    }

    @Synchronized
    fun record(
        context: Context,
        title: LocalizedNotificationText,
        message: LocalizedNotificationText,
        route: String,
        at: Instant = Instant.now()
    ) {
        val owner = activePatient(context)
        // Nobody is signed in: do not create a history item that no account owns.
        if (owner.isBlank()) return
        val items = readAll(context).toMutableList()
        val duplicate = items.firstOrNull {
            it.owner == owner && it.title.en == title.en && it.message.en == message.en && it.route == route &&
                runCatching {
                    Instant.parse(it.timestamp).isAfter(at.minusSeconds(120))
                }.getOrDefault(false)
        }
        if (duplicate != null) return

        items.add(
            0,
            NotificationHistoryItem(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                // The time it was meant for, so a late delivery keeps its real time.
                timestamp = at.toString(),
                route = route,
                read = false,
                owner = owner
            )
        )
        // Newest first, even when an older message arrives late.
        val sorted = items.sortedByDescending {
            runCatching { Instant.parse(it.timestamp) }.getOrDefault(Instant.EPOCH)
        }
        val perPatient = mutableMapOf<String, Int>()
        val kept = sorted.filter { item ->
            val count = (perPatient[item.owner] ?: 0) + 1
            perPatient[item.owner] = count
            count <= MAX_ITEMS_PER_PATIENT
        }
        write(context, kept.take(MAX_ITEMS))
    }

    /** The signed-in patient's history only. */
    @Synchronized
    fun read(context: Context): List<NotificationHistoryItem> {
        val owner = activePatient(context)
        if (owner.isBlank()) return emptyList()
        return readAll(context).filter { it.owner == owner }
    }

    @Synchronized
    private fun readAll(context: Context): List<NotificationHistoryItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    // Older builds stored only the language active when the notification
                    // was created. Hide those legacy rows so every visible history item
                    // can switch immediately with the app language.
                    if (!item.has("title_en") || !item.has("message_en")) continue
                    // Items saved before histories were split per account cannot be
                    // matched to a patient, so they are hidden rather than shown to everyone.
                    val owner = item.optString("owner")
                    if (owner.isBlank()) continue
                    val legacyTitle = item.optString("title")
                    val legacyMessage = item.optString("message")
                    add(
                        NotificationHistoryItem(
                            id = item.optString("id"),
                            title = LocalizedNotificationText(
                                en = item.optString("title_en", legacyTitle),
                                ms = item.optString("title_ms", legacyTitle),
                                zh = item.optString("title_zh", legacyTitle),
                                ta = item.optString("title_ta", legacyTitle)
                            ),
                            message = LocalizedNotificationText(
                                en = item.optString("message_en", legacyMessage),
                                ms = item.optString("message_ms", legacyMessage),
                                zh = item.optString("message_zh", legacyMessage),
                                ta = item.optString("message_ta", legacyMessage)
                            ),
                            timestamp = item.optString("timestamp"),
                            route = item.optString("route"),
                            read = item.optBoolean("read", false),
                            owner = owner
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun markRead(context: Context, id: String) {
        val owner = activePatient(context)
        write(context, readAll(context).map { if (it.id == id && it.owner == owner) it.copy(read = true) else it })
    }

    @Synchronized
    fun markAllRead(context: Context) {
        val owner = activePatient(context)
        write(context, readAll(context).map { if (it.owner == owner) it.copy(read = true) else it })
    }

    /** Clears the signed-in patient's history; other accounts on the phone keep theirs. */
    @Synchronized
    fun clear(context: Context) {
        val owner = activePatient(context)
        write(context, readAll(context).filterNot { it.owner == owner })
    }

    private fun write(context: Context, items: List<NotificationHistoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title_en", item.title.en)
                    .put("title_ms", item.title.ms)
                    .put("title_zh", item.title.zh)
                    .put("title_ta", item.title.ta)
                    .put("message_en", item.message.en)
                    .put("message_ms", item.message.ms)
                    .put("message_zh", item.message.zh)
                    .put("message_ta", item.message.ta)
                    .put("timestamp", item.timestamp)
                    .put("route", item.route)
                    .put("read", item.read)
                    .put("owner", item.owner)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
