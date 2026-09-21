package com.vitalink.app.util

import com.vitalink.app.data.model.PatientSummary

enum class AlertLevel { GREEN, YELLOW, RED }

data class AlertResult(val level: AlertLevel, val title: String, val message: String, val reasons: List<String>)

object AlertEngine {
    private fun t(en: String, ms: String): String = AppLanguage.text(en, ms)

    fun evaluate(summary: PatientSummary?): AlertResult {
        if (summary == null) return AlertResult(
            AlertLevel.GREEN,
            t("Green / Stable", "Hijau / Stabil"),
            t("No red or yellow condition is found.", "Tiada keadaan merah atau kuning dikesan."),
            emptyList()
        )

        val bp = summary.latestBp
        val sys = bp?.systolic
        val dia = bp?.diastolic
        val pulse = bp?.pulse
        val hr = summary.avgHr?.toInt()
        val spo2 = summary.avgSpo2?.takeIf { it in 1..100 }
        val weight = summary.latestWeight
        val baselineWeight = summary.baselineWeight
        val previousWeight = summary.previousWeight
        val recentGain = summary.recentWeightGainKg
        val red = mutableListOf<String>()
        val yellow = mutableListOf<String>()

        if (sys != null && (sys >= 180 || sys < 80)) red += t(
            "Systolic BP is $sys mmHg (Normal: 80 - 139 mmHg)",
            "Tekanan darah sistolik ialah $sys mmHg (Normal: 80 - 139 mmHg)"
        )
        if (dia != null && (dia >= 120 || dia < 50)) red += t(
            "Diastolic BP is $dia mmHg (Normal: 50 - 89 mmHg)",
            "Tekanan darah diastolik ialah $dia mmHg (Normal: 50 - 89 mmHg)"
        )
        if (pulse != null && (pulse < 50 || pulse > 150)) red += t(
            "Pulse is $pulse bpm (Normal: 50 - 150 bpm)",
            "Nadi ialah $pulse bpm (Normal: 50 - 150 bpm)"
        )
        if (hr != null && (hr < 50 || hr > 150)) red += t(
            "Heart rate is $hr bpm (Normal: 50 - 150 bpm)",
            "Denyutan jantung ialah $hr bpm (Normal: 50 - 150 bpm)"
        )
        if (spo2 != null && spo2 < 90) red += t(
            "SpO₂ is $spo2% (Normal: 95 - 100%)",
            "SpO₂ ialah $spo2% (Normal: 95 - 100%)"
        )
        if (recentGain != null && recentGain >= 3.0) red += t("Weight increased ${"%.1f".format(recentGain)} kg recently", "Berat meningkat ${"%.1f".format(recentGain)} kg baru-baru ini")
        if (weight != null && baselineWeight != null && weight - baselineWeight >= 5.0) red += t("Weight is ${"%.1f".format(weight - baselineWeight)} kg above baseline", "Berat adalah ${"%.1f".format(weight - baselineWeight)} kg melebihi garis dasar")

        if (sys != null && sys in 140..179) yellow += t(
            "High systolic BP: $sys mmHg (Normal: 80 - 139 mmHg)",
            "Tekanan darah sistolik tinggi: $sys mmHg (Normal: 80 - 139 mmHg)"
        )
        if (dia != null && dia in 90..119) yellow += t(
            "High diastolic BP: $dia mmHg (Normal: 50 - 89 mmHg)",
            "Tekanan darah diastolik tinggi: $dia mmHg (Normal: 50 - 89 mmHg)"
        )
        if (hr != null && (hr < 60 || hr > 100)) yellow += t(
            "Heart rate is $hr bpm (Normal: 60 - 100 bpm)",
            "Denyutan jantung ialah $hr bpm (Normal: 60 - 100 bpm)"
        )
        if (pulse != null && (pulse < 60 || pulse > 100)) yellow += t(
            "Pulse is $pulse bpm (Normal: 60 - 100 bpm)",
            "Nadi ialah $pulse bpm (Normal: 60 - 100 bpm)"
        )
        if (spo2 != null && spo2 < 95) yellow += t(
            "SpO₂ is $spo2% (Normal: 95 - 100%)",
            "SpO₂ ialah $spo2% (Normal: 95 - 100%)"
        )
        if (previousWeight != null && weight != null && weight - previousWeight >= 1.5) yellow += t("Weight increased ${"%.1f".format(weight - previousWeight)} kg since previous reading", "Berat meningkat ${"%.1f".format(weight - previousWeight)} kg sejak bacaan sebelumnya")
        if (recentGain != null && recentGain >= 2.0) yellow += t("Weight increased ${"%.1f".format(recentGain)} kg over several days", "Berat meningkat ${"%.1f".format(recentGain)} kg dalam beberapa hari")
        if (weight != null && baselineWeight != null && weight - baselineWeight >= 3.0) yellow += t("Weight is ${"%.1f".format(weight - baselineWeight)} kg above baseline", "Berat adalah ${"%.1f".format(weight - baselineWeight)} kg melebihi garis dasar")
        if (summary.baselineSystolic != null && sys != null && sys - summary.baselineSystolic >= 20) yellow += t("Systolic BP is ${sys - summary.baselineSystolic} mmHg above baseline", "Tekanan darah sistolik ${sys - summary.baselineSystolic} mmHg melebihi garis dasar")
        if (summary.baselineHeartRate != null && hr != null && kotlin.math.abs(hr - summary.baselineHeartRate) >= 20) yellow += t("Heart rate differs from baseline by ${kotlin.math.abs(hr - summary.baselineHeartRate)} bpm", "Denyutan jantung berbeza ${kotlin.math.abs(hr - summary.baselineHeartRate)} bpm daripada garis dasar")
        if ((summary.steps ?: Long.MAX_VALUE) < 3000L) yellow += t("Steps today are below 3000", "Langkah hari ini kurang daripada 3000")
        if ((summary.daysWithoutWeightLogThisWeek ?: 0) >= 4) yellow += t("4 or more days without weight log this week", "4 hari atau lebih tanpa rekod berat minggu ini")
        if ((summary.daysWithoutSymptomLogThisWeek ?: 0) >= 4) yellow += t("4 or more days without symptom log this week", "4 hari atau lebih tanpa rekod simptom minggu ini")

        return when {
            red.isNotEmpty() -> AlertResult(
                AlertLevel.RED,
                t("Red / Critical Alert", "Merah / Amaran Kritikal"),
                t("This reading is high risk. Please rest now and notify your healthcare provider.", "Bacaan ini berisiko tinggi. Sila berehat sekarang dan maklumkan kepada penyedia kesihatan anda."),
                red
            )
            yellow.isNotEmpty() -> AlertResult(
                AlertLevel.YELLOW,
                t("Yellow / Warning Alert", "Kuning / Amaran"),
                t("Monitor closely and continue daily check-in.", "Pantau dengan teliti dan teruskan pemeriksaan harian."),
                yellow
            )
            else -> AlertResult(
                AlertLevel.GREEN,
                t("Green / Stable", "Hijau / Stabil"),
                t("No red or yellow condition is found.", "Tiada keadaan merah atau kuning dikesan."),
                emptyList()
            )
        }
    }
}
