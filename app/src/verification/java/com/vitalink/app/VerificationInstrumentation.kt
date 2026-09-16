package com.vitalink.app

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitalink.app.ui.screens.*
import com.vitalink.app.ui.theme.MyHFGuardTheme
import com.vitalink.app.data.model.*
import com.vitalink.app.util.*
import java.io.File

/** Only packaged in the separate verification build, never the delivered debug app. */
class VerificationInstrumentation : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val lang = args.getString("lang", "en")
            val screen = args.getString("screen", "overview")
            if (screen == "audio") {
                val id = targetContext.resources.getIdentifier("lesson_c4bd3fc6bfca_" + lang, "raw", targetContext.packageName)
                check(id != 0) { "Missing narration" }
                val audio = android.media.MediaPlayer.create(targetContext, id) ?: error("Cannot decode narration")
                try {
                    check(kotlin.math.abs(audio.duration - 101000) < 1000)
                    audio.start(); Thread.sleep(1600); check(audio.currentPosition > 800)
                    audio.pause(); val paused = audio.currentPosition; Thread.sleep(700)
                    check(kotlin.math.abs(audio.currentPosition - paused) < 150)
                    audio.seekTo(50000); Thread.sleep(800); audio.start(); Thread.sleep(800)
                    check(audio.currentPosition > 49500)
                    result.putString("status", "PASS: decode, duration, play, pause and seek")
                } finally { audio.release() }
                finish(Activity.RESULT_OK, result); return
            }
            if (screen == "ocrscan") {
                val bitmap = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bitmap).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawText("55.6 kg", 60f, 190f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.BLACK; textSize=120f; typeface=android.graphics.Typeface.MONOSPACE })
                }
                val rotated = Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,android.graphics.Matrix().apply { postRotate(90f) },true)
                val done = java.util.concurrent.CountDownLatch(1)
                var weight: Double? = null
                var failure: String? = null
                runOnMainSync { MachineOcrScanner.scanWeight(rotated, { weight=it.valueKg; done.countDown() }, { failure=it; done.countDown() }) }
                check(done.await(60,java.util.concurrent.TimeUnit.SECONDS)) { "OCR timeout" }
                check(weight == 55.6) { "OCR result $weight: $failure" }
                result.putString("status","PASS: rotated synthetic 55.6 kg recognized")
                finish(Activity.RESULT_OK,result); return
            }
            if (screen == "bpsamples") {
                val expected: List<Pair<String, Triple<Int, Int, Int>>> = listOf(
                    "sample_bp1" to Triple(110, 72, 69),
                    "sample_bp2" to Triple(60, 38, 56),
                    "sample_bp3" to Triple(143, 83, 77),
                    "sample_bp4" to Triple(109, 47, 65)
                )
                val outcomes = mutableListOf<String>()
                expected.forEach { (resourceName, reading) ->
                    val id = targetContext.resources.getIdentifier(resourceName, "drawable", targetContext.packageName)
                    check(id != 0) { "Missing OCR sample $resourceName" }
                    val bitmap = android.graphics.BitmapFactory.decodeResource(targetContext.resources, id)
                    val done = java.util.concurrent.CountDownLatch(1)
                    var scanned: MachineOcrScanner.BloodPressureResult? = null
                    var failure: String? = null
                    runOnMainSync {
                        MachineOcrScanner.scanBloodPressure(bitmap, { scanned = it; done.countDown() }, { failure = it; done.countDown() })
                    }
                    check(done.await(90, java.util.concurrent.TimeUnit.SECONDS)) { "$resourceName OCR timeout" }
                    outcomes += "$resourceName=${scanned?.systolic}/${scanned?.diastolic}/${scanned?.pulse ?: "?"}${if (failure != null) " ($failure)" else ""}"
                    bitmap.recycle()
                }
                result.putString("status", outcomes.joinToString("; "))
                finish(Activity.RESULT_OK, result); return
            }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            val state = DashState(patientId = "verification", firstName = "Test", summary = PatientSummary(null,
                BpEvent(patient_id = "verification", systolic = 120, diastolic = 80, pulse = 72),45.0,0,7,avgHr = 72,avgSpo2 = 98,baselineWeight = 45.0),
                todayWaterIntakeMl = 600, todayWaterLimitMl = 800, todaySaltScore = 3, todaySaltStatus = "green")
            runOnMainSync {
                AppLanguage.setLanguage(targetContext, AppLanguageCode.entries.first { it.storageValue == lang })
                activity.setContent {
                    MyHFGuardTheme(dark = false) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("MyHFGuard", style = MaterialTheme.typography.headlineSmall)
                                if (screen == "ocrreview") OcrImageReview(Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888).apply { android.graphics.Canvas(this).apply { drawColor(android.graphics.Color.LTGRAY);drawText("128 / 82",80f,250f,android.graphics.Paint().apply { color=android.graphics.Color.BLACK;textSize=100f }) } }, {}, {})
                                else if (screen == "player") OfflineEducationPlayer(educationCatalogue(targetContext).first(), "verification-player", {}, { result.putString("completed", "true") })
                                else if (screen != "advice") CompactHealthOverview(if (args.getString("missing") == "true") state.copy(summary = null, missingToday = listOf("BP","Weight"),todayWaterIntakeMl = null,todaySaltScore = null) else state) { }
                                if (screen == "advice") UnifiedTodayAdvice(state.copy(missingToday = listOf("BP", "Weight", "Symptoms", "Water", "Salt")), MalaysiaDateTime.today().atTime(15,0),
                                    AppLanguage.text("Daily monitoring", "Pemantauan harian", "每日监测", "தினசரி கண்காணிப்பு"), AppLanguage.text("Record your health measurements today.", "Rekod ukuran kesihatan hari ini.", "记录今天的健康数据。", "இன்றைய உடல்நல அளவுகளைப் பதிவு செய்யுங்கள்.")) { }
                            }
                        }
                    }
                }
            }
            waitForIdleSync(); Thread.sleep(1800)
            val screenshot = uiAutomation.takeScreenshot() ?: error("No screenshot")
            val folder = File(targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
            val file = File(folder, "$screen-$lang-${args.getString("missing", "false")}.png")
            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            result.putString("screenshot", file.absolutePath)
            result.putString("status", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) { result.putString("error", error.stackTraceToString()); finish(Activity.RESULT_CANCELED, result) }
    }
}
