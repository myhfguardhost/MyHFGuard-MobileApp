package com.vitalink.app.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.vitalink.app.data.model.MedicationEntry
import java.time.LocalDateTime

/** Keeps pending doses and immediate completion status across dashboard/app restarts. */
class MedicationChecklistStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("medication_dose_checklist", Context.MODE_PRIVATE))
    private val gson = Gson()

    fun read(patientId: String): List<MedicationDoseCheck> {
        if (patientId.isBlank()) return emptyList()
        val json = preferences.getString(storageKey(patientId), null) ?: return emptyList()
        return gson.fromJson(json, Array<MedicationDoseCheck>::class.java).orEmpty()
            .filter { it.patientId == patientId }
    }

    fun update(
        patientId: String,
        medicinesBySlot: Map<String, List<MedicationEntry>>,
        now: LocalDateTime
    ): List<MedicationDoseCheck> = synchronized(lock) {
        if (patientId.isBlank()) return@synchronized emptyList()
        val previous = read(patientId)
        val legacyChecks = preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith("$patientId|") && value is Boolean) key to value else null
        }.toMap()
        val updated = MedicationChecklist.reconcile(patientId, previous, medicinesBySlot, now, legacyChecks)
        if (updated != previous) write(patientId, updated)
        updated
    }

    fun setCompleted(patientId: String, id: String, checked: Boolean, now: LocalDateTime): List<MedicationDoseCheck> = synchronized(lock) {
        if (patientId.isBlank()) return@synchronized emptyList()
        val previous = read(patientId)
        val updated = MedicationChecklist.setCompleted(previous, patientId, id, checked, now)
        if (updated != previous) {
            // Also update the old key, so going back to a previous app build keeps the tick.
            preferences.edit().putString(storageKey(patientId), gson.toJson(updated))
                .putBoolean(id, checked).apply()
        }
        updated
    }

    private fun storageKey(patientId: String) = "dose_records_v2|$patientId"

    private fun write(patientId: String, records: List<MedicationDoseCheck>) {
        preferences.edit().putString(storageKey(patientId), gson.toJson(records)).apply()
    }

    private companion object {
        val lock = Any()
    }
}
