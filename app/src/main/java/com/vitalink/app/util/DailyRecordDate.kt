package com.vitalink.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Explicit entry dates win; timestamp-only legacy rows are converted to local time. */
object DailyRecordDate {
    fun date(raw: String?): LocalDate? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.length == 10) runCatching { LocalDate.parse(it) }.getOrNull()
        else MalaysiaDateTime.localDate(it)
    }
    fun matches(today: LocalDate, explicit: String?, vararg timestamps: String?): Boolean =
        (date(explicit) ?: timestamps.firstNotNullOfOrNull(::date)) == today

    /** Dashboard-only comparison for patients whose phone calendar is not Malaysia time. */
    fun matchesSystemDate(today: LocalDate, explicit: String?, vararg timestamps: String?): Boolean {
        date(explicit)?.let { return it == today }
        val deviceZone = ZoneId.systemDefault()
        val timestampDate = timestamps.firstNotNullOfOrNull { raw ->
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                runCatching { OffsetDateTime.parse(value.replaceFirst(' ', 'T')).atZoneSameInstant(deviceZone).toLocalDate() }
                    .recoverCatching { Instant.parse(value).atZone(deviceZone).toLocalDate() }
                    .getOrNull()
            }
        }
        return timestampDate == today
    }
}
