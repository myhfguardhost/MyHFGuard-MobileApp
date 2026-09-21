package com.vitalink.app.util
import java.time.LocalDateTime
object AdviceTiming {
    fun missingEntry(now: LocalDateTime, missingToday: List<String>, loading: Boolean): String? =
        if (!loading && now.hour >= 15) missingToday.firstOrNull() else null
}
