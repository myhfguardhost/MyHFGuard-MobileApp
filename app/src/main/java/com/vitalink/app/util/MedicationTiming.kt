package com.vitalink.app.util

import com.vitalink.app.data.model.*
import java.time.LocalDateTime
import java.util.Locale

object MedicationTiming {
    const val DAY = "day"
    const val NIGHT = "night"

    // Display the checklist during the scheduled hour, in Malaysia time.
    fun visibleSlot(now: LocalDateTime): String? = when (now.hour) {
        12 -> DAY
        21 -> NIGHT
        else -> null
    }

    fun matches(raw: String, slot: String): Boolean {
        val text = raw.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return false
        if (Regex("\\b(od|qd)\\b").containsMatchIn(text)) return slot == DAY
        if (Regex("\\b(on|bd|bid|tds|tid)\\b").containsMatchIn(text)) return slot == NIGHT
        if (text == "both" || text.contains("twice") || text.contains("2 times")) return true
        val night = Regex("night|evening|malam|晚上|இரவு|(?:9|10)(?::00)?\\s*pm|(?:21|22):00").containsMatchIn(text)
        val day = Regex("noon|morning|lunch|tengah|pagi|中午|மதியம்|12(?::00)?\\s*pm|10(?::00)?\\s*am|(?:10|12):00(?!\\s*pm)").containsMatchIn(text) || text == "day"
        return if (slot == NIGHT) night else day
    }

    fun parseProfile(text: String): List<MedicationEntry> = text.lines().mapNotNull { raw ->
        val parts = raw.trim().trim('-', '•').split('|', ',').map { it.trim() }
        val name = parts.firstOrNull().orEmpty()
        if (name.isBlank()) null else {
            // Read timing only from the timing field, never from a dose such as 10 mg.
            val timing = parts.drop(2).joinToString(",").ifBlank { if (matches(raw, DAY) || matches(raw, NIGHT)) raw else "12:00 PM" }
            MedicationEntry(name, parts.getOrNull(1).orEmpty(), when {
                matches(timing, DAY) && matches(timing, NIGHT) -> "12:00 PM, 9:00 PM"
                matches(timing, NIGHT) -> "9:00 PM"
                matches(timing, DAY) -> "12:00 PM"
                else -> timing
            })
        }
    }

    private fun matchesMedication(medicine: Medication?, rawTime: String, slot: String): Boolean {
        val frequency = medicine?.frequency.orEmpty()
        return if (Regex("\\b(od|qd|on|bd|bid|tds|tid)\\b", RegexOption.IGNORE_CASE).containsMatchIn(frequency)) matches(frequency, slot)
        else matches(rawTime, slot)
    }

    fun due(profile: List<MedicationEntry>, medications: List<Medication>, schedules: List<MedicationSchedule>, slot: String): List<MedicationEntry> {
        val profileNames = profile.map { it.name.trim().lowercase(Locale.ROOT) }.toSet()
        val table = medications.filter { it.is_active && it.name.trim().lowercase(Locale.ROOT) !in profileNames }
        val scheduled = schedules.filter { schedule ->
            val id = schedule.medication_id ?: schedule.medicine_id
            val medicine = medications.firstOrNull { it.id == id }
            (medicine == null || medicine.is_active) &&
                (medicine?.name ?: schedule.title).orEmpty().trim().lowercase(Locale.ROOT) !in profileNames && matchesMedication(medicine, schedule.time.orEmpty(), slot)
        }.mapNotNull { schedule ->
            val medicine = medications.firstOrNull { it.id == (schedule.medication_id ?: schedule.medicine_id) }
            val name = medicine?.name ?: schedule.title ?: return@mapNotNull null
            MedicationEntry(name, schedule.dosage ?: medicine?.dosage.orEmpty(), schedule.time.orEmpty())
        }
        return (profile.filter { matches(it.reminderTime, slot) } + table.filter { medication ->
            schedules.none { (it.medication_id ?: it.medicine_id) == medication.id } && matchesMedication(medication, medication.time_of_day.orEmpty().ifBlank { medication.frequency.orEmpty() }, slot)
        }.map { MedicationEntry(it.name, it.dosage.orEmpty(), it.time_of_day.orEmpty()) } + scheduled)
            .filter { it.name.isNotBlank() }.distinctBy { it.name.trim().lowercase(Locale.ROOT) to it.dosage.trim().lowercase(Locale.ROOT) }
    }
}
