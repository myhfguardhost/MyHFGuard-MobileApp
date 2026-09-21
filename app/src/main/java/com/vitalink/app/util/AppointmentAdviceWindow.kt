package com.vitalink.app.util

import java.time.LocalDateTime

object AppointmentAdviceWindow {
    fun visible(now: LocalDateTime, appointment: LocalDateTime): Boolean =
        !now.toLocalDate().isBefore(appointment.toLocalDate().minusDays(1)) &&
            now.isBefore(appointment.plusHours(1))
}
