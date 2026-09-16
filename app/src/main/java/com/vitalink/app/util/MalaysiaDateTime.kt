package com.vitalink.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Appointment timestamps are stored by Supabase as timestamptz values, which are
 * normally returned in UTC. Always convert them to Malaysia time before showing
 * or comparing them. Timestamp strings without an offset are treated as already
 * being Malaysia local time for compatibility with older records.
 */
object MalaysiaDateTime {
    val zoneId: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")

    fun today(): LocalDate = LocalDate.now(zoneId)

    fun now(): LocalDateTime = LocalDateTime.now(zoneId)

    fun appointmentTimestamp(date: LocalDate, time: LocalTime): String =
        LocalDateTime.of(date, time)
            .atZone(zoneId)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun parseTimestamp(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim().replaceFirst(' ', 'T')

        return runCatching {
            OffsetDateTime.parse(value)
                .atZoneSameInstant(zoneId)
                .toLocalDateTime()
        }.recoverCatching {
            ZonedDateTime.parse(value)
                .withZoneSameInstant(zoneId)
                .toLocalDateTime()
        }.recoverCatching {
            Instant.parse(value)
                .atZone(zoneId)
                .toLocalDateTime()
        }.recoverCatching {
            // Older rows may contain a local timestamp with no UTC offset.
            LocalDateTime.parse(value)
        }.getOrNull()
    }

    fun localDate(raw: String?): LocalDate? = parseTimestamp(raw)?.toLocalDate()

    fun localTime(raw: String?): LocalTime? = parseTimestamp(raw)?.toLocalTime()

    fun displayTimestamp(raw: String?): String? = parseTimestamp(raw)?.format(
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
    )
}
