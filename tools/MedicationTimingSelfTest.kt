package com.vitalink.app.util

import com.vitalink.app.data.model.*
import java.time.LocalDateTime

fun main() {
    val day = MedicationTiming.DAY
    val night = MedicationTiming.NIGHT
    for (time in listOf("12:00 PM", "12:00", "noon", "morning", "10:00 AM")) {
        check(MedicationTiming.matches(time, day)) { "Not recognized as noon: $time" }
        check(!MedicationTiming.matches(time, night)) { "Noon medicine leaked to night: $time" }
    }
    for (time in listOf("9:00 PM", "10:00 PM", "21:00", "22:00", "night", "malam")) {
        check(MedicationTiming.matches(time, night)) { "Not recognized as night: $time" }
        check(!MedicationTiming.matches(time, day)) { "Night medicine leaked to noon: $time" }
    }
    for (time in listOf("both", "morning,night", "12:00 PM, 9:00 PM")) {
        check(MedicationTiming.matches(time, day) && MedicationTiming.matches(time, night))
    }
    check(MedicationTiming.matches("OD", day) && !MedicationTiming.matches("OD", night))
    for (frequency in listOf("ON", "BD", "TDS")) check(MedicationTiming.matches(frequency, night) && !MedicationTiming.matches(frequency, day))
    check(!MedicationTiming.matches("", night))
    val profile = MedicationTiming.parseProfile("A | 10 mg | noon\nB | 5 mg | 10:00 PM\nC | 20 mg | both\nD | 10 mg\nE | 2 mg | 12:00 PM, 9:00 PM")
    check(profile.first { it.name == "D" }.reminderTime == "12:00 PM") { "Dose mistaken for time" }
    check(profile.first { it.name == "B" }.reminderTime == "10:00 PM")
    check(MedicationTiming.parseProfile("Medicine 10 mg night").single().reminderTime == "10:00 PM")
    check(MedicationTiming.parseProfile("Medicine 10 mg").single().reminderTime == "12:00 PM")
    val table = listOf(Medication("1", "p", "A", time_of_day = "night"), Medication("2", "p", "Inactive", time_of_day = "noon", is_active = false))
    val noon = MedicationTiming.due(profile, table, emptyList(), day).map { it.name }.toSet()
    val evening = MedicationTiming.due(profile, table, emptyList(), night).map { it.name }.toSet()
    check(noon == setOf("A", "C", "D", "E")) { noon }
    check(evening == setOf("B", "C", "E")) { evening }
    val bd = Medication("bd", "p", "BD medicine", frequency = "BD", time_of_day = "both")
    check(MedicationTiming.due(emptyList(), listOf(bd), emptyList(), day).isEmpty())
    check(MedicationTiming.due(emptyList(), listOf(bd), emptyList(), night).single().name == "BD medicine")
    val scheduledMed = Medication("3", "p", "Scheduled", dosage = "5 mg", time_of_day = "both")
    val schedules = listOf(MedicationSchedule("s", "p", medication_id = "3", time = "21:00"))
    check(MedicationTiming.due(emptyList(), listOf(scheduledMed), schedules, day).isEmpty())
    check(MedicationTiming.due(emptyList(), listOf(scheduledMed), schedules, night).single().name == "Scheduled")
    check(MedicationTiming.due(emptyList(), listOf(scheduledMed.copy(is_active = false)), schedules, night).isEmpty())
    fun slot(time: String) = MedicationTiming.visibleSlot(LocalDateTime.parse("2026-09-06T$time"))
    check(slot("11:59:59") == null)
    check(slot("12:00:00") == day)
    check(slot("12:59:59") == day)
    check(slot("13:00:00") == null)
    check(slot("20:59:59") == null)
    check(slot("21:00:00") == night)
    check(slot("21:59:59") == night)
    check(slot("22:00:00") == null)
    println("PASS: dose filtering, legacy times, duplicate sources, inactive medicines and noon/9 PM time-window boundaries")
}
