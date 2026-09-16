package com.vitalink.app.reminders

import android.content.Context
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.AppLanguageCode
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

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
    val read: Boolean
)

object NotificationHistoryStore {
    private const val PREFS = "myhfguard_notification_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 100

    /**
     * Backward-compatible method for older callers. The same text is stored for
     * all languages, while new notification producers should use the localized overload.
     */
    @Synchronized
    fun record(context: Context, title: String, message: String, route: String) {
        record(
            context = context,
            title = LocalizedNotificationText(title, title, title, title),
            message = LocalizedNotificationText(message, message, message, message),
            route = route
        )
    }

    @Synchronized
    fun record(
        context: Context,
        title: LocalizedNotificationText,
        message: LocalizedNotificationText,
        route: String
    ) {
        val items = read(context).toMutableList()
        val duplicate = items.firstOrNull {
            it.title.en == title.en && it.message.en == message.en && it.route == route &&
                runCatching {
                    Instant.parse(it.timestamp).isAfter(Instant.now().minusSeconds(120))
                }.getOrDefault(false)
        }
        if (duplicate != null) return

        items.add(
            0,
            NotificationHistoryItem(
                id = UUID.randomUUID().toString(),
                title = title,
                message = message,
                timestamp = Instant.now().toString(),
                route = route,
                read = false
            )
        )
        write(context, items.take(MAX_ITEMS))
    }

    @Synchronized
    fun read(context: Context): List<NotificationHistoryItem> {
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
                            read = item.optBoolean("read", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun markRead(context: Context, id: String) {
        write(context, read(context).map { if (it.id == id) it.copy(read = true) else it })
    }

    @Synchronized
    fun markAllRead(context: Context) {
        write(context, read(context).map { it.copy(read = true) })
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
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
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
