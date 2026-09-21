package com.vitalink.app.util

import com.vitalink.app.data.model.MedicationEntry
import java.time.LocalDate
import java.time.LocalDateTime

data class MedicationDoseCheck(
    val id: String,
    val patientId: String,
    val date: String,
    val slot: String,
    val name: String,
    val dosage: String,
    val completedAt: String? = null
) {
    val completed: Boolean get() = completedAt != null
}

/** Show pending doses for today only; retain completion records across refreshes. */
object MedicationChecklist {
    fun dueSlots(now: LocalDateTime): List<String> = buildList {
        if (now.hour >= 12) add(MedicationTiming.DAY)
        if (now.hour >= 21) add(MedicationTiming.NIGHT)
    }

    // Compatible with the previous checkbox keys so existing ticks are preserved.
    fun doseId(patientId: String, date: String, slot: String, name: String, dosage: String): String =
        "$patientId|$date|$slot|$name|$dosage"

    fun reconcile(
        patientId: String,
        previous: List<MedicationDoseCheck>,
        medicinesBySlot: Map<String, List<MedicationEntry>>,
        now: LocalDateTime,
        legacyChecks: Map<String, Boolean> = emptyMap()
    ): List<MedicationDoseCheck> {
        if (patientId.isBlank()) return emptyList()
        val today = now.toLocalDate().toString()
        val records = previous.filter { it.patientId == patientId }.associateBy { it.id }.toMutableMap()
        dueSlots(now).forEach { slot ->
            medicinesBySlot[slot].orEmpty().filter { it.name.isNotBlank() }.forEach { medicine ->
                val id = doseId(patientId, today, slot, medicine.name, medicine.dosage)
                if (id !in records) {
                    records[id] = MedicationDoseCheck(
                        id, patientId, today, slot, medicine.name, medicine.dosage,
                        completedAt = if (legacyChecks[id] == true) now.toString() else null
                    )
                }
            }
        }
        // Expire missed doses at midnight and retain recent completion history.
        val cutoff = now.toLocalDate().minusDays(30).toString()
        return records.values.filter { if (it.completed) it.completedAt!!.take(10) >= cutoff else it.date == today }
            .sortedWith(compareBy<MedicationDoseCheck> { it.date }.thenBy { it.slot }.thenBy { it.name })
    }

    fun setCompleted(
        records: List<MedicationDoseCheck>,
        patientId: String,
        id: String,
        checked: Boolean,
        now: LocalDateTime
    ): List<MedicationDoseCheck> = records.map { record ->
        if (record.patientId == patientId && record.id == id) {
            record.copy(completedAt = if (checked) record.completedAt ?: now.toString() else null)
        } else record
    }

    fun visible(records: List<MedicationDoseCheck>, patientId: String, today: LocalDate): List<MedicationDoseCheck> =
        records.filter { record ->
            record.patientId == patientId &&
                !record.completed && record.date == today.toString()
        }
}
