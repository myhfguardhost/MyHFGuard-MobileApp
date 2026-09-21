package com.vitalink.app.ui.screens

import com.vitalink.app.util.UserFacingError
import com.vitalink.app.util.UserFacingError.Action

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalink.app.data.api.ApiService
import com.vitalink.app.data.api.SessionManager
import com.vitalink.app.data.api.AiApiService
import com.vitalink.app.data.model.*
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.MalaysiaDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import com.vitalink.app.util.PcnaPatientAdvice
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

private const val AI_CHAT_TAG = "MyHFGuardAI"

private data class LocalAiMessage(val fromUser: Boolean, val text: String)

enum class AiReplyMode { ENGLISH, MALAY, ROJAK, MANDARIN, TAMIL }

data class AiRiskNotice(
    val level: String,
    val suggestedAction: String? = null
)

private fun AiReplyMode.apiLanguageCode(): String = when (this) {
    AiReplyMode.ENGLISH -> "en"
    AiReplyMode.MALAY -> "ms"
    AiReplyMode.ROJAK -> "rojak"
    AiReplyMode.MANDARIN -> "zh"
    AiReplyMode.TAMIL -> "ta"
}

private data class HeartFailureKnowledgeTopic(
    val titleEn: String,
    val titleMs: String,
    val keywords: List<String>,
    val adviceEn: List<String>,
    val adviceMs: List<String>,
    val sourceUrl: String
)

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val api: ApiService,
    private val aiApi: AiApiService,
    session: SessionManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {
    private val _ctx = MutableStateFlow(AiContextState())
    val ctx = _ctx.asStateFlow()
    private val _patientId = MutableStateFlow("")
    val patientId = _patientId.asStateFlow()
    private val _riskNotice = MutableStateFlow<AiRiskNotice?>(null)
    val riskNotice = _riskNotice.asStateFlow()
    private var pid = ""

    init {
        // Wake the Render AI service as soon as the screen/ViewModel is created.
        // This reduces the chance that the user's first message lands on the local fallback
        // simply because the free-tier backend was sleeping.
        viewModelScope.launch { warmAiBackend() }

        viewModelScope.launch {
            session.patientId.filterNotNull().collect {
                pid = it
                _patientId.value = it
                load()
            }
        }
    }

    private suspend fun warmAiBackend() {
        try {
            val response = aiApi.health()
            if (response.isSuccessful) {
                Log.d(AI_CHAT_TAG, "AI backend warm-up OK; version=${response.body()?.version}, geminiConfigured=${response.body()?.geminiConfigured}")
            } else {
                Log.w(AI_CHAT_TAG, "AI backend warm-up HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            // Warm-up failure is not user-facing. The real chat request still gets two attempts.
            Log.w(AI_CHAT_TAG, "AI backend warm-up failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun requestAiReplyWithRetry(request: ChatRequest): ChatResponse? {
        for (attempt in 1..2) {
            try {
                val response = aiApi.chat(request)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body == null) {
                        Log.w(AI_CHAT_TAG, "AI attempt $attempt returned an empty body")
                    } else {
                        Log.d(
                            AI_CHAT_TAG,
                            "AI attempt $attempt succeeded; source=${body.source ?: "unknown"}, model=${body.model ?: "unknown"}, error=${body.error ?: "none"}"
                        )

                        // The backend itself can safely fall back when Gemini has a temporary error.
                        // Give Gemini one more short chance before accepting that fallback.
                        val backendFallback = body.source == "backend_fallback" ||
                            body.error == "gemini_temporarily_unavailable"
                        if (backendFallback && attempt == 1) {
                            delay(1200)
                            continue
                        }
                        return body
                    }
                } else {
                    Log.w(AI_CHAT_TAG, "AI attempt $attempt failed with HTTP ${response.code()}")
                    // 5xx is commonly a Render cold-start/gateway problem; retry once.
                    if (attempt == 1 && response.code() in 500..599) {
                        delay(1200)
                        continue
                    }
                    return null
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (e: Exception) {
                Log.w(AI_CHAT_TAG, "AI attempt $attempt threw ${e.javaClass.simpleName}: ${e.message}")
                if (attempt == 1) {
                    delay(1200)
                    continue
                }
                return null
            }
        }
        return null
    }

    fun load() = viewModelScope.launch {
        if (pid.isBlank()) return@launch
        try {
            _ctx.value = fetchLatestContext(pid)
        } catch (e: Exception) {
            _ctx.value = _ctx.value.copy(error = UserFacingError.from(e, Action.LOAD), loaded = false)
        }
    }

    fun clearRiskNotice() {
        _riskNotice.value = null
    }

    /**
     * Voice-only floating AI nurse entry point.
     *
     * The nurse deliberately uses the SAME heart-care vocabulary and conversation
     * rules as the full My Chat page. Messages outside that scope are rejected on
     * the phone before they are sent to Gemini. Short contextual answers are still
     * accepted so natural follow-ups such as "once", "lower stomach" or "7/10"
     * continue the conversation instead of restarting it.
     */
    fun answerNurse(
        question: String,
        mode: AiReplyMode,
        history: List<ChatMessage>,
        memoryNotes: List<String>,
        onReply: (String, List<String>) -> Unit
    ) {
        val normalized = normalizeAiQuestion(question)

        if (!isAiNurseAllowedMessage(normalized, history)) {
            _riskNotice.value = null
            onReply(outOfScopeReply(mode), emptyList())
            return
        }
        answer(question, mode, history, memoryNotes, onReply)
    }

    fun answer(
        question: String,
        mode: AiReplyMode,
        history: List<ChatMessage>,
        memoryNotes: List<String>,
        onReply: (String, List<String>) -> Unit
    ) = viewModelScope.launch {
        if (pid.isBlank()) {
            PcnaPatientAdvice.answer(question, mode.apiLanguageCode())?.let { onReply(it, emptyList()); return@launch }
            onReply(
                when (mode) {
                    AiReplyMode.MALAY -> "Sila log masuk semula supaya saya boleh membaca data terkini."
                    AiReplyMode.ROJAK -> "Please log masuk semula supaya saya boleh check latest data anda."
                    AiReplyMode.MANDARIN -> "请重新登录，以便我读取您的最新健康数据。"
                    AiReplyMode.TAMIL -> "உங்கள் சமீபத்திய உடல்நலத் தரவைப் படிக்க மீண்டும் உள்நுழையவும்."
                    AiReplyMode.ENGLISH -> "Please login again so I can read your latest data."
                },
                emptyList()
            )
            return@launch
        }

        val latest = try { withTimeoutOrNull(6000) { fetchLatestContext(pid) } ?: _ctx.value }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { _ctx.value.copy(error = UserFacingError.from(error, Action.LOAD)) }
        _ctx.value = latest

        // Send the user's real latest message unchanged. The recent messages are
        // already supplied separately in `history`, allowing the backend to treat
        // short replies such as "1 time" as answers without duplicating old text
        // or accidentally re-triggering emergency keywords from an earlier reply.
        val normalized = normalizeAiQuestion(question)

        // Quick questions always use the freshly loaded patient context so a
        // generic network answer cannot hide the patient's actual readings.
        if (isOverallHealthQuestion(normalized)) {
            _riskNotice.value = localRiskNotice(normalized, latest, mode)
            onReply(patientFacingAnswer(buildOverallHealthReply(latest, mode)), emptyList())
            return@launch
        }
        if (isPersonalizedWarningQuestion(normalized)) {
            _riskNotice.value = localRiskNotice(normalized, latest, mode)
            onReply(patientFacingAnswer(buildPersonalizedWarningReply(latest, mode)), emptyList())
            return@launch
        }

        // V9: Scope-check the CURRENT user message before loading health data or
        // calling Gemini. This prevents a completely unrelated request such as
        // "write me python code" from ever falling through to the generic
        // health-overview fallback when the backend returns the correct scope
        // rejection or when the network is unavailable.
        if (isClearlyUnrelatedQuestion(normalized)) {
            _riskNotice.value = null
            onReply(outOfScopeReply(mode), emptyList())
            return@launch
        }

        // V11: Never send an ambiguous signed number such as "+1" straight to
        // Gemini when the previous turn asked for a 0-10 pain score. It can mean
        // "pain increased by 1" or "pain is 1/10". Clarify first so neither
        // Gemini nor the offline fallback invents the patient's meaning.
        val previousAssistantText = history.asReversed()
            .firstOrNull { it.role.equals("assistant", ignoreCase = true) && it.content.isNotBlank() }
            ?.content.orEmpty()
        ambiguousSignedPainReply(question, previousAssistantText, mode)?.let { clarification ->
            _riskNotice.value = null
            onReply(clarification, emptyList())
            return@launch
        }

        val knowledgeContext = com.vitalink.app.util.PcnaKnowledge.retrieve(appContext, question)

        // Send the freshly loaded Supabase context to the AI backend. The local
        // context-aware responder remains the safe fallback when Render/Gemini is
        // waking up, unavailable, using an older route, or replying in the wrong language.
        val chatRequest = ChatRequest(
            patientId = pid,
            // The existing Node route reads `message` and `patientId`. Fold recent
            // turns into the server message so Gemini can understand short follow-ups
            // such as "1 time", "lower stomach" or "7/10" without FastAPI.
            message = buildNodeConversationMessage(question, history, mode) + "\n\n" + com.vitalink.app.util.PcnaKnowledge.prompt(knowledgeContext),
            history = history.takeLast(20),
            context = latest,
            knowledgeContext = knowledgeContext,
            memoryNotes = memoryNotes.takeLast(5),
            language = mode.apiLanguageCode()
        )
        val serverResult = withTimeoutOrNull(20000) { requestAiReplyWithRetry(chatRequest) }

        val serverReply = serverResult?.reply?.trim()
        val usableServerReply = serverReply
            ?.takeIf { serverReplyIsUseful(it, normalized, mode) }
            ?.takeUnless { reply ->
                looksLikeOldScopeRejection(reply) && !isClearlyUnrelatedQuestion(normalized)
            }

        if (usableServerReply != null) {
            val riskLevel = serverResult.riskLevel?.lowercase(Locale.ROOT)
            _riskNotice.value = if (riskLevel in setOf("monitor", "urgent", "emergency")) {
                AiRiskNotice(riskLevel!!, serverResult.suggestedAction?.takeIf { it.isNotBlank() })
            } else {
                // Existing Node returns plain Gemini text, so retain Android safety flags.
                localRiskNotice(normalized, latest, mode)
            }

            val followUp = serverResult.followUpQuestion?.trim().orEmpty()
            val combinedReply = if (
                followUp.isNotBlank() &&
                !usableServerReply.lowercase(Locale.ROOT).contains(followUp.lowercase(Locale.ROOT))
            ) {
                "$usableServerReply\n\n$followUp"
            } else usableServerReply
            onReply(patientFacingAnswer(combinedReply), serverResult.memoryUpdates.orEmpty())
        } else {
            val fallback = generateContextReply(question, latest, mode, history)
            _riskNotice.value = localRiskNotice(normalized, latest, mode)
            onReply(patientFacingAnswer(fallback), emptyList())
        }
    }

    private suspend fun fetchLatestContext(patientId: String): AiContextState = coroutineScope {
        val filter = "eq.$patientId"
        val today = MalaysiaDateTime.today().toString()
        val todayDate = MalaysiaDateTime.today()
        val sevenDayStart = todayDate.minusDays(6)
        fun inLatestSevenDays(raw: String?): Boolean {
            val dateText = raw?.take(10) ?: return false
            val date = runCatching { java.time.LocalDate.parse(dateText) }.getOrNull() ?: return false
            return !date.isBefore(sevenDayStart) && !date.isAfter(todayDate)
        }

        suspend fun <T> read(request: suspend () -> retrofit2.Response<List<T>>): List<T> = try {
            withTimeoutOrNull(4500) { val response = request(); if (response.isSuccessful) response.body().orEmpty() else emptyList() }.orEmpty()
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
        val dataStepsDay = async { read { api.getStepsDay(filter) } }
        val dataSmartBandMetrics = async { read { api.getSmartBandMetrics(filter) } }
        val dataProfile = async { read { api.getProfile(filter) } }
        val dataSpo2Day = async { read { api.getSpo2Day(filter) } }
        val dataWeightDay = async { read { api.getWeightDay(filter) } }
        val dataBpEvents = async { read { api.getBpEvents(filter) } }
        val dataMedications = async { read { api.getMedications(filter) } }
        val dataSymptomLogs = async { read { api.getSymptomLogs(filter) } }
        val dataWaterLogs = async { read { api.getWaterLogs(filter) } }
        val dataAppointments = async { read { api.getAppointments(filter) } }
        val dataReminders = async { read { api.getReminders(filter) } }
        val stepsRows = dataStepsDay.await()
        val steps = stepsRows.firstOrNull { it.date == today } ?: stepsRows.firstOrNull()
        val bandRows = dataSmartBandMetrics.await()
        val latestBand = bandRows.firstOrNull { it.date == today } ?: bandRows.firstOrNull()
        val profile = dataProfile.await().firstOrNull()
        val heartRate = latestBand?.avg_hr?.toInt()?.takeIf { it > 0 }
        val spo2Rows = dataSpo2Day.await()
        val spo2 = spo2Rows.firstOrNull()?.spo2_avg?.toInt()?.takeIf { it in 1..100 }
            ?: latestBand?.avg_spo2?.toInt()?.takeIf { it in 1..100 }
        // These endpoints are ordered newest-first in ApiService. The chatbot now receives
        // the actual dated records inside the latest seven-day window and derives direction
        // from the first and last real record without pretending the trend is a diagnosis.
        val weightRows = dataWeightDay.await()
        val weight = weightRows.firstOrNull()?.let { it.kg_avg ?: it.kg_max ?: it.kg_min }
        val weightHistory7d = weightRows
            .filter { inLatestSevenDays(it.date) }
            .mapNotNull { row -> (row.kg_avg ?: row.kg_max ?: row.kg_min)?.let { AiWeightTrendPoint(row.date.take(10), it) } }
            .distinctBy { it.date }
            .sortedBy { it.date }
            .takeLast(7)
        val weightTrendKg = if (weightHistory7d.size >= 2) {
            weightHistory7d.last().kg - weightHistory7d.first().kg
        } else null

        val bpRows = dataBpEvents.await()
        val bpRow = bpRows.firstOrNull()
        val bpHistory7d = bpRows
            .filter { inLatestSevenDays(it.reading_date ?: it.recorded_at) }
            .mapNotNull { row ->
                val date = (row.reading_date ?: row.recorded_at)?.take(10) ?: return@mapNotNull null
                AiBpTrendPoint(date, row.systolic, row.diastolic, row.pulse)
            }
            .distinctBy { it.date }
            .sortedBy { it.date }
            .takeLast(7)
        val medNames = dataMedications.await().filter { it.is_active }.map { it.name }.take(5)
        val symptomRows = dataSymptomLogs.await()
        val symptom = symptomRows.firstOrNull()
        val symptomScore = symptom?.let {
            (it.cough ?: 0) + (it.sob_activity ?: 0) + (it.leg_swelling ?: 0) +
                (it.abd_discomfort ?: 0) + (it.orthopnea ?: 0)
        }
        val symptomHistory7d = symptomRows
            .filter { inLatestSevenDays(it.date ?: it.logged_at ?: it.created_at) }
            .mapNotNull { row ->
                val date = (row.date ?: row.logged_at ?: row.created_at)?.take(10) ?: return@mapNotNull null
                val score = (row.cough ?: 0) + (row.sob_activity ?: 0) + (row.leg_swelling ?: 0) +
                    (row.abd_discomfort ?: 0) + (row.orthopnea ?: 0)
                AiSymptomTrendPoint(date, score)
            }
            .distinctBy { it.date }
            .sortedBy { it.date }
            .takeLast(7)
        val symptomTrendDelta = if (symptomHistory7d.size >= 2) {
            symptomHistory7d.last().score - symptomHistory7d.first().score
        } else null

        val waterRows = dataWaterLogs.await()
        val water = waterRows.firstOrNull()
        val waterSaltHistory7d = waterRows
            .filter { inLatestSevenDays(it.entry_date) }
            .map { row -> AiWaterSaltTrendPoint(row.entry_date.take(10), row.water_intake_ml ?: row.water_ml, row.water_limit_ml, row.salt_score) }
            .distinctBy { it.date }
            .sortedBy { it.date }
            .takeLast(7)

        val stepsHistory7d = stepsRows
            .filter { inLatestSevenDays(it.date) }
            .mapNotNull { row -> row.steps_total?.let { AiStepsTrendPoint(row.date.take(10), it) } }
            .distinctBy { it.date }
            .sortedBy { it.date }
            .takeLast(7)
        val spo2History7d = spo2Rows
            .filter { inLatestSevenDays(it.date) }
            .mapNotNull { row -> row.spo2_avg?.toInt()?.takeIf { it in 1..100 }?.let { AiSpo2TrendPoint(row.date.take(10), it) } }
            .distinctBy { it.date }
            .sortedBy { it.date }
            .takeLast(7)
        val heartRateHistory7d = bandRows
            .filter { inLatestSevenDays(it.date) }
            .mapNotNull { row -> row.avg_hr?.toInt()?.takeIf { it > 0 }?.let { AiHeartRateTrendPoint(row.date.take(10), it) } }
            .distinctBy { it.date }
            .sortedBy { it.date }
            .takeLast(7)
        val appointmentRows: List<Appointment> = runCatching { dataAppointments.await() }
            .getOrDefault(emptyList())
        val reminderAppointments: List<Appointment> = runCatching {
            dataReminders.await()
                .filter { it.type.equals("appointment", ignoreCase = true) }
                .map { reminder ->
                    val malaysiaDue = MalaysiaDateTime.parseTimestamp(reminder.due_ts)
                    Appointment(
                        id = reminder.id,
                        patient_id = reminder.patient_id,
                        title = reminder.title,
                        appointment_date = malaysiaDue?.toLocalDate()?.toString(),
                        appointment_time = malaysiaDue?.toLocalTime()?.format(
                            java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        ),
                        notes = reminder.notes,
                        created_at = reminder.created_at
                    )
                }
        }.getOrDefault(emptyList())
        val nextAppointment = (appointmentRows + reminderAppointments)
            .filter { it.appointment_date?.take(10)?.let { date -> date >= today } == true }
            .distinctBy {
                listOf(it.title, it.appointment_date?.take(10), it.appointment_time?.take(5), it.notes)
                    .joinToString("|")
            }
            .sortedWith(compareBy<Appointment> { it.appointment_date.orEmpty() }.thenBy { it.appointment_time.orEmpty() })
            .firstOrNull()

        AiContextState(
            steps = steps?.steps_total,
            heartRate = heartRate,
            spo2 = spo2,
            weight = weight,
            weightTrendKg = weightTrendKg,
            weightHistory7d = weightHistory7d,
            bp = bpRow?.let { "${it.systolic}/${it.diastolic}" },
            bpHistory7d = bpHistory7d,
            pulse = bpRow?.pulse,
            medication = medNames.joinToString(", ").ifBlank { profile?.current_medication },
            symptomScore = symptomScore,
            symptomTrendDelta = symptomTrendDelta,
            symptomHistory7d = symptomHistory7d,
            stepsHistory7d = stepsHistory7d,
            spo2History7d = spo2History7d,
            heartRateHistory7d = heartRateHistory7d,
            waterMl = water?.water_intake_ml ?: water?.water_ml,
            waterLimitMl = water?.water_limit_ml,
            saltScore = water?.salt_score,
            waterSaltHistory7d = waterSaltHistory7d,
            nextAppointmentTitle = nextAppointment?.title,
            nextAppointmentDate = nextAppointment?.appointment_date?.take(10),
            nextAppointmentTime = nextAppointment?.appointment_time?.take(5),
            targetSteps = profile?.target_steps?.toLong()?.coerceIn(500L, 50000L) ?: 3000L,
            loaded = true
        )
    }
}

private fun detailedAiGreetingText(mode: AiReplyMode): String = when (mode) {
    AiReplyMode.ENGLISH -> """Hi! 👋 I’m your MyHFGuard health assistant.

I can help with:
• ❤️ Blood pressure, heart rate, SpO₂ and weight
• 🩺 Symptoms and warning signs
• 💧 Water, salt, medicines and appointments

How are you feeling today? 😊
🚨 Severe chest pain or breathing difficulty: call 999."""
    AiReplyMode.MALAY, AiReplyMode.ROJAK -> """Hai! 👋 Saya pembantu kesihatan MyHFGuard anda.

Saya boleh bantu:
• ❤️ Tekanan darah, denyutan jantung, SpO₂ dan berat
• 🩺 Simptom dan tanda amaran
• 💧 Air, garam, ubat dan temujanji

Bagaimana keadaan anda hari ini? 😊
🚨 Sakit dada atau sesak nafas teruk: hubungi 999."""
    AiReplyMode.MANDARIN -> """您好！👋 我是您的 MyHFGuard 健康助手。

我可以帮助您：
• ❤️ 查看血压、心率、SpO₂ 和体重
• 🩺 了解症状和警告信号
• 💧 管理饮水、盐分、药物和预约

您今天感觉怎么样？😊
🚨 严重胸痛或呼吸困难：立即拨打 999。"""
    AiReplyMode.TAMIL -> """வணக்கம்! 👋 நான் உங்கள் MyHFGuard உடல்நல உதவியாளர்.

நான் உதவ முடியும்:
• ❤️ இரத்த அழுத்தம், இதயத் துடிப்பு, SpO₂ மற்றும் எடை
• 🩺 அறிகுறிகள் மற்றும் எச்சரிக்கை அறிகுறிகள்
• 💧 நீர், உப்பு, மருந்துகள் மற்றும் சந்திப்புகள்

இன்று எப்படி உணர்கிறீர்கள்? 😊
🚨 கடுமையான நெஞ்சுவலி அல்லது மூச்சுத்திணறல்: 999-ஐ அழைக்கவும்."""
}

private fun currentAiGreetingMode(): AiReplyMode = when {
    AppLanguage.useMalay -> AiReplyMode.MALAY
    AppLanguage.useMandarin -> AiReplyMode.MANDARIN
    AppLanguage.useTamil -> AiReplyMode.TAMIL
    else -> AiReplyMode.ENGLISH
}

private fun defaultAiGreeting(): LocalAiMessage = LocalAiMessage(
    fromUser = false,
    text = detailedAiGreetingText(currentAiGreetingMode())
)

private val legacyAiGreetings = setOf(
    "Hi, chat with me!",
    "Hai, berbual dengan saya!",
    "嗨，和我聊聊吧！",
    "வணக்கம், என்னுடன் பேசுங்கள்!"
)

private val previousDetailedGreetingPrefixes = listOf(
    "Hello! 👋 I’m your MyHFGuard health companion.",
    "Hai! 👋 Saya teman kesihatan MyHFGuard anda.",
    "您好！👋 我是您的 MyHFGuard 健康助手。",
    "வணக்கம்! 👋 நான் உங்கள் MyHFGuard உடல்நலத் துணை."
)

private fun refreshDefaultGreeting(messages: List<LocalAiMessage>): List<LocalAiMessage> {
    if (messages.isEmpty()) return listOf(defaultAiGreeting())
    val first = messages.first()
    val trimmed = first.text.trim()
    val recognisedGreeting = legacyAiGreetings.contains(trimmed) ||
        AiReplyMode.entries.any { trimmed == detailedAiGreetingText(it).trim() } ||
        previousDetailedGreetingPrefixes.any { trimmed.startsWith(it) }
    return if (!first.fromUser && recognisedGreeting) {
        listOf(defaultAiGreeting()) + messages.drop(1)
    } else {
        messages
    }
}

private fun emotionalSupportReply(question: String, mode: AiReplyMode): String? {
    val q = normalizeAiQuestion(question)
    val distressWords = listOf(
        "sad", "unhappy", "down", "lonely", "cry", "crying", "upset", "depressed", "hopeless",
        "worried", "worry", "anxious", "anxiety", "scared", "afraid", "stressed", "overwhelmed",
        "sedih", "tak gembira", "sunyi", "menangis", "kecewa", "tertekan", "risau", "takut",
        "难过", "伤心", "不开心", "孤独", "哭", "沮丧", "担心", "焦虑", "害怕", "压力",
        "சோகம்", "வருத்தம்", "தனிமை", "அழுகிறேன்", "மனச்சோர்வு", "கவலை", "பயம்", "பதட்டம்"
    )
    if (distressWords.none { it in q }) return null

    return when (mode) {
        AiReplyMode.MANDARIN -> "听起来你现在很难受或很担心。谢谢你告诉我。先慢慢呼吸，坐在安全的地方，也可以联系你信任的家人或朋友陪你聊聊。你愿意告诉我发生了什么，或者哪一项健康状况让你担心吗？如果你可能伤害自己，请立即联系当地紧急服务或身边可信任的人。"
        AiReplyMode.TAMIL -> "நீங்கள் இப்போது வருத்தமாகவோ கவலையாகவோ இருப்பது போலத் தெரிகிறது. அதை பகிர்ந்ததற்கு நன்றி. மெதுவாக மூச்சை இழுத்து, பாதுகாப்பான இடத்தில் அமர்ந்து, நம்பகமான குடும்பத்தினர் அல்லது நண்பரிடம் பேசுங்கள். என்ன நடந்தது அல்லது எந்த உடல்நிலை உங்களை கவலைப்படுத்துகிறது என்று சொல்ல விரும்புகிறீர்களா? உங்களை காயப்படுத்திக் கொள்ளலாம் என்று தோன்றினால் உடனடியாக அவசர சேவையையோ நம்பகமான ஒருவரையோ அணுகவும்."
        AiReplyMode.MALAY, AiReplyMode.ROJAK -> "Saya dengar anda sedang berasa sedih atau risau, dan saya minta maaf anda melalui perkara ini. Cuba tarik nafas perlahan dan hubungi ahli keluarga atau rakan yang anda percayai. Mahu ceritakan apa yang berlaku atau bacaan kesihatan mana yang membuat anda risau? Jika anda rasa mungkin mencederakan diri, dapatkan bantuan kecemasan atau hubungi seseorang yang dipercayai dengan segera."
        AiReplyMode.ENGLISH -> "I’m sorry you’re feeling sad or worried. Thank you for telling me. Take a slow breath and consider contacting a trusted family member or friend so you do not have to carry this alone. Would you like to tell me what happened or which health reading is worrying you? If you may hurt yourself, contact emergency help or a trusted person immediately."
    }
}

private fun aiHistoryKey(patientId: String): String {
    val safePatientId = patientId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "messages_${safePatientId.ifBlank { "signed_out" }}"
}

private fun loadAiHistory(
    context: android.content.Context,
    patientId: String
): List<LocalAiMessage> {
    if (patientId.isBlank()) return listOf(defaultAiGreeting())
    val raw = context.getSharedPreferences("myhfguard_ai_chat", android.content.Context.MODE_PRIVATE)
        .getString(aiHistoryKey(patientId), null) ?: return listOf(defaultAiGreeting())
    return runCatching {
        val array = JSONArray(raw)
        val storedMessages = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val text = item.optString("text").trim()
                if (text.isNotBlank()) add(LocalAiMessage(item.optBoolean("fromUser"), text))
            }
        }.ifEmpty { listOf(defaultAiGreeting()) }
        refreshDefaultGreeting(storedMessages)
    }.getOrElse { listOf(defaultAiGreeting()) }
}

private fun saveAiHistory(
    context: android.content.Context,
    patientId: String,
    messages: List<LocalAiMessage>
) {
    if (patientId.isBlank()) return
    val array = JSONArray()
    messages.takeLast(60).forEach { message ->
        array.put(JSONObject().put("fromUser", message.fromUser).put("text", message.text))
    }
    context.getSharedPreferences("myhfguard_ai_chat", android.content.Context.MODE_PRIVATE)
        .edit().putString(aiHistoryKey(patientId), array.toString()).apply()
}

private fun aiMemoryKey(patientId: String): String {
    val safePatientId = patientId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "memory_${safePatientId.ifBlank { "signed_out" }}"
}

internal fun loadAiMemoryNotes(context: android.content.Context, patientId: String): List<String> {
    if (patientId.isBlank()) return emptyList()
    val raw = context.getSharedPreferences("myhfguard_ai_chat", android.content.Context.MODE_PRIVATE)
        .getString(aiMemoryKey(patientId), null) ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct().takeLast(5)
    }.getOrDefault(emptyList())
}

internal fun saveAiMemoryNotes(
    context: android.content.Context,
    patientId: String,
    existing: List<String>,
    updates: List<String>
) {
    if (patientId.isBlank() || updates.isEmpty()) return
    val cleaned = (existing + updates)
        .map { it.trim().replace(Regex("\\s+"), " ").take(180) }
        .filter { it.isNotBlank() }
        .distinct()
        .takeLast(5)
    val array = JSONArray()
    cleaned.forEach(array::put)
    context.getSharedPreferences("myhfguard_ai_chat", android.content.Context.MODE_PRIVATE)
        .edit().putString(aiMemoryKey(patientId), array.toString()).apply()
}

private fun clearAiMemoryNotes(context: android.content.Context, patientId: String) {
    if (patientId.isBlank()) return
    context.getSharedPreferences("myhfguard_ai_chat", android.content.Context.MODE_PRIVATE)
        .edit().remove(aiMemoryKey(patientId)).apply()
}

internal fun normalizeAiQuestion(question: String): String {
    return question.lowercase(Locale.ROOT)
        // English and Bahasa Melayu variants.
        .replace("sp02", "spo2")
        .replace("oxygen level", "spo2")
        .replace("bloodpressure", "blood pressure")
        .replace("blood pressure reading", "blood pressure")
        .replace("pressure darah", "blood pressure")
        .replace("heartbeat", "heart rate")
        .replace("heart beat", "heart rate")
        .replace("jantung laju", "heart rate high")
        .replace("jantung perlahan", "heart rate low")
        .replace("temu janji", "temujanji")
        .replace("temu-janji", "temujanji")
        .replace("follow-up", "follow up")
        .replace("meds", "medicine")
        .replace("pills", "medicine")
        .replace("pill", "medicine")
        .replace("makan ubat", "medicine")
        .replace("ubat apa", "medicine")
        .replace("workout", "exercise")
        .replace("walking", "walk")
        .replace("jalan kaki", "walk")
        .replace("tak cukup step", "remaining steps")
        .replace("tak cukup langkah", "remaining steps")
        .replace("sakit dada", "chest pain")
        .replace("bibir kebiruan", "blue lips")
        .replace("bibir biru", "blue lips")
        .replace("keliru", "confusion")
        .replace("semput", "breathless")
        .replace("susah nafas", "breathless")
        .replace("nafas pendek", "breathless")
        .replace("short of breath", "breathless")
        .replace("kaki sembab", "swelling")
        .replace("kaki bengkak", "swelling")
        .replace("ankle swell", "swelling")
        .replace("pening kepala", "dizzy")
        .replace("pening", "dizzy")
        .replace("fainted", "fainting")
        .replace("passed out", "fainting")
        .replace("pengsan", "fainting")
        .replace("palpitation", "heart rate")
        .replace("berdebar", "heart rate")
        .replace("salty", "salt")
        .replace("berapa lagi", "remaining")
        .replace("belum capai", "remaining")
        .replace("cukup tak", "target")
        .replace("cukup ke", "target")
        .replace("okay tak", "normal")
        .replace("ok tak", "normal")
        .replace("okay ke", "normal")
        .replace("tak okay", "abnormal")
        .replace("tak normal", "abnormal")
        .replace("takde", "tiada")
        .replace("tak ada", "tiada")
        .replace("xde", "tiada")
        .replace("mcm mana", "macam mana")
        .replace("camne", "macam mana")
        .replace("rasa nak muntah", "nausea")
        .replace("nak muntah", "nausea")
        .replace("loya", "nausea")
        .replace("mual", "nausea")
        .replace("muntah", "vomiting")

        // Mandarin variants are mapped to canonical health intents. The final
        // answer still follows the language detected from the original question.
        .replace("我的健康状况", "how is my health")
        .replace("我的健康", "how is my health")
        .replace("我今天怎么样", "how is my health today")
        .replace("我还好吗", "how is my health")
        .replace("心力衰竭", "heart failure")
        .replace("心脏衰竭", "heart failure")
        .replace("血氧饱和度", "spo2")
        .replace("血氧", "spo2")
        .replace("氧气水平", "spo2")
        .replace("高血压", "blood pressure high")
        .replace("低血压", "blood pressure low")
        .replace("血压", "blood pressure")
        .replace("心跳", "heart rate")
        .replace("心率", "heart rate")
        .replace("脉搏", "heart rate")
        .replace("心悸", "heart rate")
        .replace("体重增加", "weight gain")
        .replace("体重下降", "weight loss")
        .replace("体重", "weight")
        .replace("呼吸困难", "breathless")
        .replace("气喘", "breathless")
        .replace("喘不过气", "breathless")
        .replace("腿部肿胀", "swelling")
        .replace("腿肿", "swelling")
        .replace("水肿", "swelling")
        .replace("警告症状", "warning sign")
        .replace("危险信号", "warning sign")
        .replace("咳嗽", "cough")
        .replace("胸痛", "chest pain")
        .replace("恶心", "nausea")
        .replace("想吐", "nausea")
        .replace("呕吐", "vomiting")
        .replace("吐了", "vomiting")
        .replace("头晕", "dizzy")
        .replace("昏厥", "fainting")
        .replace("晕倒", "fainting")
        .replace("意识混乱", "confusion")
        .replace("嘴唇发蓝", "blue lips")
        .replace("症状", "symptom")
        .replace("喝水", "water")
        .replace("饮水", "water")
        .replace("液体摄入", "water")
        .replace("液体", "water")
        .replace("钠", "salt")
        .replace("盐分", "salt")
        .replace("盐", "salt")
        .replace("饮食", "diet")
        .replace("食物", "food")
        .replace("步数", "steps")
        .replace("走路", "walk")
        .replace("运动", "exercise")
        .replace("还差多少", "remaining")
        .replace("目标", "target")
        .replace("药物", "medicine")
        .replace("吃药", "medicine")
        .replace("药", "medicine")
        .replace("复诊", "appointment")
        .replace("预约", "appointment")
        .replace("医院", "hospital")

        // Tamil variants are mapped to the same canonical intents.
        .replace("என் உடல்நிலை எப்படி", "how is my health")
        .replace("என் உடல்நலம்", "how is my health")
        .replace("இதய செயலிழப்பு", "heart failure")
        .replace("இரத்த ஆக்சிஜன்", "spo2")
        .replace("ஆக்சிஜன் அளவு", "spo2")
        .replace("ஆக்சிஜன்", "spo2")
        .replace("உயர் இரத்த அழுத்தம்", "blood pressure high")
        .replace("குறைந்த இரத்த அழுத்தம்", "blood pressure low")
        .replace("இரத்த அழுத்தம்", "blood pressure")
        .replace("நாடித் துடிப்பு", "heart rate")
        .replace("இதயத் துடிப்பு", "heart rate")
        .replace("இதய துடிப்பு", "heart rate")
        .replace("எடை அதிகரிப்பு", "weight gain")
        .replace("எடை குறைவு", "weight loss")
        .replace("எடை", "weight")
        .replace("மூச்சுத்திணறல்", "breathless")
        .replace("சுவாச சிரமம்", "breathless")
        .replace("கால் வீக்கம்", "swelling")
        .replace("வீக்கம்", "swelling")
        .replace("இருமல்", "cough")
        .replace("நெஞ்சுவலி", "chest pain")
        .replace("குமட்டல்", "nausea")
        .replace("வாந்தி வருவது போல்", "nausea")
        .replace("வாந்தி", "vomiting")
        .replace("எச்சரிக்கை அறிகுறிகள்", "warning sign")
        .replace("எச்சரிக்கை", "warning sign")
        .replace("மயங்கி விழுதல்", "fainting")
        .replace("மயக்கம்", "dizzy")
        .replace("குழப்பம்", "confusion")
        .replace("உதடு நீலமாகுதல்", "blue lips")
        .replace("அறிகுறிகள்", "symptom")
        .replace("அறிகுறி", "symptom")
        .replace("திரவ உட்கொள்ளல்", "water")
        .replace("திரவம்", "water")
        .replace("தண்ணீர்", "water")
        .replace("நீர்", "water")
        .replace("உப்பு", "salt")
        .replace("உணவு", "food")
        .replace("நடைகள்", "steps")
        .replace("நடை", "walk")
        .replace("உடற்பயிற்சி", "exercise")
        .replace("இலக்கு", "target")
        .replace("மீதம்", "remaining")
        .replace("மருந்துகள்", "medicine")
        .replace("மருந்து", "medicine")
        .replace("மருத்துவ சந்திப்பு", "appointment")
        .replace("சந்திப்பு", "appointment")
        .replace(Regex("\\s+"), " ")
        .trim()
}

internal fun isLikelyShortConversationAnswer(normalized: String): Boolean {
    if (normalized.length > 48) return false
    if (Regex("^\\d+(?:\\s*(?:time|times|x|kali|次|回|முறை))?$").matches(normalized)) return true
    return listOf(
        "yes", "no", "yeah", "yup", "nope", "once", "twice", "one time", "two times",
        "can", "cannot", "can't", "a little", "not yet", "just now", "today", "yesterday",
        "ya", "ye", "tidak", "tak", "boleh", "tak boleh", "belum", "sekali", "dua kali",
        "是", "不是", "可以", "不可以", "还没有", "一次", "两次",
        "ஆம்", "இல்லை", "முடியும்", "முடியாது", "இன்னும் இல்லை", "ஒரு முறை", "இரண்டு முறை"
    ).any { normalized == it || normalized.startsWith("$it ") }
}

private fun nodeLanguageAnchor(mode: AiReplyMode): String = when (mode) {
    // The existing Node/Render route historically detects language from `message`.
    // These short anchors keep even one-word follow-ups in the selected language.
    AiReplyMode.MALAY -> "Bahasa Melayu diperlukan. Saya nak jawapan dalam Bahasa Melayu. Boleh jawab dalam Bahasa Melayu."
    AiReplyMode.ROJAK -> "Saya nak jawapan Malaysian English dan Bahasa Melayu secara natural."
    AiReplyMode.MANDARIN -> "请始终使用简体中文回答。"
    AiReplyMode.TAMIL -> "தயவுசெய்து எப்போதும் தமிழில் பதிலளிக்கவும்."
    AiReplyMode.ENGLISH -> "Always answer in English."
}

private fun buildNodeConversationMessage(
    currentQuestion: String,
    history: List<ChatMessage>,
    mode: AiReplyMode
): String {
    val recent = history.takeLast(4)
        .filter { it.content.isNotBlank() }
        .takeLast(4)

    val transcript = recent.joinToString("\n") { turn ->
        val who = if (turn.role.equals("assistant", ignoreCase = true)) "Assistant" else "Patient"
        val clean = turn.content.replace(Regex("\\s+"), " ").trim().take(160)
        "$who: $clean"
    }

    return buildString {
        append(nodeLanguageAnchor(mode))
        append("\n")
        append("Continue the existing MyHFGuard conversation. ")
        append("Use previous turns only as context and answer the CURRENT patient message naturally. ")
        append("Do not repeat a question the patient already answered.\n\n")
        if (transcript.isNotBlank()) {
            append("Previous conversation:\n")
            append(transcript)
            append("\n\n")
        }
        append("CURRENT patient message: ")
        append(currentQuestion.trim().take(600))
    }
}

private fun conversationText(history: List<ChatMessage>): String = history.takeLast(10)
    .filter { it.role.equals("user", ignoreCase = true) }
    .joinToString(" ") { it.content }
    .let(::normalizeAiQuestion)

private fun assistantAskedVomitingCount(text: String): Boolean {
    val q = normalizeAiQuestion(text)
    return listOf(
        "how many times", "how often", "berapa kali", "几次", "多少次", "எத்தனை முறை"
    ).any { it in q }
}

private fun assistantAskedCanKeepFluids(text: String): Boolean {
    val q = normalizeAiQuestion(text)
    return listOf(
        "keep small sips", "keep fluids", "keep water", "keep medicines", "keep medication",
        "air boleh kekal", "boleh minum", "simpan air", "simpan ubat",
        "喝得下", "留得住", "液体", "药物",
        "திரவத்தை வைத்திருக்க", "தண்ணீர் குடிக்க", "மருந்தை வைத்திருக்க"
    ).any { it in q }
}

private fun parseVomitingCount(answer: String): Int? {
    val q = normalizeAiQuestion(answer)
    Regex("\\b(\\d{1,2})\\b").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val values = linkedMapOf(
        "none" to 0, "zero" to 0, "not yet" to 0, "belum" to 0, "还没有" to 0, "இன்னும் இல்லை" to 0,
        "once" to 1, "one time" to 1, "sekali" to 1, "satu kali" to 1, "一次" to 1, "ஒரு முறை" to 1,
        "twice" to 2, "two times" to 2, "dua kali" to 2, "两次" to 2, "இரண்டு முறை" to 2,
        "three times" to 3, "tiga kali" to 3, "三次" to 3, "மூன்று முறை" to 3
    )
    return values.entries.firstOrNull { (phrase, _) -> q == phrase || q.startsWith("$phrase ") }?.value
}

private fun parseSimpleYesNo(answer: String): Boolean? {
    val q = normalizeAiQuestion(answer)
    val yes = listOf("yes", "yeah", "yup", "can", "boleh", "ya", "是", "可以", "ஆம்", "முடியும்")
    val no = listOf("no", "nope", "cannot", "can't", "tak boleh", "tidak", "不可以", "不能", "இல்லை", "முடியாது")
    if (no.any { q == it || q.startsWith("$it ") }) return false
    if (yes.any { q == it || q.startsWith("$it ") }) return true
    return null
}

private fun nauseaFollowUpReply(
    question: String,
    history: List<ChatMessage>,
    mode: AiReplyMode
): String? {
    val previousAssistant = history.asReversed()
        .firstOrNull { it.role.equals("assistant", ignoreCase = true) && it.content.isNotBlank() }
        ?.content
        ?: return null
    val nauseaContext = isNauseaOrVomitingQuestion(conversationText(history)) ||
        isNauseaOrVomitingQuestion(normalizeAiQuestion(question))
    if (!nauseaContext) return null

    val count = parseVomitingCount(question)
    if (count != null && assistantAskedVomitingCount(previousAssistant)) {
        return when {
            count == 0 -> replyText(mode,
                "Thanks for clarifying—you have not vomited, but you feel nauseous. Sit upright, breathe slowly, and try only small sips within your prescribed fluid limit. Avoid oily, spicy, or strong-smelling food for now. Can you keep small sips and your usual medicines down?",
                "Terima kasih kerana menjelaskan—anda belum muntah, tetapi berasa loya. Duduk tegak, tarik nafas perlahan dan cuba teguk kecil sahaja dalam had cecair yang ditetapkan. Elakkan makanan berminyak, pedas atau berbau kuat buat sementara. Bolehkah anda mengekalkan sedikit air dan ubat biasa anda?",
                "谢谢说明——您还没有呕吐，但感到恶心。请坐直、慢慢呼吸，并只在医生规定的液体限制内小口喝水；暂时避免油腻、辛辣或气味强烈的食物。少量液体和日常药物能留得住吗？",
                "தெளிவுபடுத்தியதற்கு நன்றி—நீங்கள் இன்னும் வாந்தி எடுக்கவில்லை, ஆனால் குமட்டல் உள்ளது. நேராக அமர்ந்து மெதுவாக மூச்செடுத்து, மருத்துவர் கூறிய திரவ வரம்பிற்குள் சிறிய குடிகளாக மட்டும் குடிக்கவும். எண்ணெய், காரம் அல்லது கடுமையான மணம் உள்ள உணவை இப்போது தவிர்க்கவும். சிறிய அளவு திரவமும் வழக்கமான மருந்துகளும் தங்குகிறதா?"
            )
            count == 1 -> replyText(mode,
                "Thanks—that means you vomited once. Sit upright and take small sips only within your prescribed fluid plan. Monitor whether it happens again. Can you keep small sips and your usual heart medicines down? If you cannot, or vomiting repeats, contact your healthcare team promptly.",
                "Terima kasih—ini bermaksud anda muntah sekali. Duduk tegak dan ambil teguk kecil sahaja dalam pelan cecair yang ditetapkan. Pantau sama ada ia berulang. Bolehkah anda mengekalkan sedikit air dan ubat jantung biasa? Jika tidak boleh, atau muntah berulang, hubungi pasukan kesihatan dengan segera.",
                "谢谢——这表示您呕吐了一次。请坐直，并只在医生规定的液体计划内小口喝水，留意是否再次呕吐。少量液体和日常心脏药物能留得住吗？若不能，或再次呕吐，请尽快联系医疗团队。",
                "நன்றி—நீங்கள் ஒருமுறை வாந்தி எடுத்துள்ளீர்கள். நேராக அமர்ந்து, மருத்துவர் கூறிய திரவ திட்டத்திற்குள் சிறிய குடிகளாக மட்டும் குடிக்கவும். மீண்டும் வாந்தி வருகிறதா என்று கவனிக்கவும். சிறிய அளவு திரவமும் வழக்கமான இதய மருந்துகளும் தங்குகிறதா? முடியாவிட்டாலோ வாந்தி மீண்டும் வந்தாலோ மருத்துவக் குழுவை விரைவாக தொடர்புகொள்ளவும்."
            )
            else -> replyText(mode,
                "You have vomited about $count times, which is more concerning than a single episode. Contact your healthcare team promptly today, especially if you cannot keep fluids or heart medicines down, urine is very low, or you feel dizzy or weak. If there is blood or coffee-ground material, severe chest or abdominal pain, fainting, confusion, or severe breathlessness, call 999 now. Can you keep any small sips down?",
                "Anda telah muntah kira-kira $count kali, yang lebih membimbangkan daripada sekali. Hubungi pasukan kesihatan dengan segera hari ini, terutamanya jika air atau ubat jantung tidak dapat kekal, air kencing sangat kurang, atau anda pening/lemah. Jika ada darah atau bahan seperti serbuk kopi, sakit dada/perut teruk, pengsan, keliru atau sesak nafas teruk, hubungi 999 sekarang. Bolehkah anda mengekalkan sedikit air?",
                "您已呕吐约 $count 次，比单次呕吐更需要关注。请今天尽快联系医疗团队，尤其是无法留住液体或心脏药物、尿量明显减少，或出现头晕/虚弱时。若有血或咖啡渣样物质、严重胸痛或腹痛、晕厥、意识混乱或严重呼吸困难，请立即拨打 999。少量液体能留得住吗？",
                "நீங்கள் சுமார் $count முறை வாந்தி எடுத்துள்ளீர்கள்; இது ஒருமுறை விட அதிக கவலைக்குரியது. திரவம் அல்லது இதய மருந்து தங்காவிட்டால், சிறுநீர் மிகவும் குறைந்தால், அல்லது மயக்கம்/பலவீனம் இருந்தால் இன்று உடனடியாக மருத்துவக் குழுவை தொடர்புகொள்ளவும். இரத்தம்/காபித்தூள் போன்ற வாந்தி, கடுமையான நெஞ்சு அல்லது வயிற்றுவலி, மயக்கம், குழப்பம் அல்லது கடுமையான மூச்சுத்திணறல் இருந்தால் இப்போதே 999-ஐ அழைக்கவும். சிறிய அளவு திரவம் தங்குகிறதா?"
            )
        }
    }

    val canKeepFluids = parseSimpleYesNo(question)
    if (canKeepFluids != null && assistantAskedCanKeepFluids(previousAssistant)) {
        return if (canKeepFluids) {
            replyText(mode,
                "Good—you can keep small sips down. Continue slowly within your prescribed fluid limit, rest upright, and avoid heavy or greasy food for now. Monitor for another episode. Contact your healthcare team if vomiting repeats or weakness, dizziness, very low urine, chest pain, or breathlessness develops.",
                "Baik—anda masih boleh mengekalkan sedikit air. Teruskan perlahan-lahan dalam had cecair yang ditetapkan, berehat dalam posisi tegak dan elakkan makanan berat atau berminyak buat sementara. Pantau jika muntah berlaku lagi. Hubungi pasukan kesihatan jika muntah berulang atau timbul lemah, pening, air kencing sangat kurang, sakit dada atau sesak nafas.",
                "好的——少量液体还能留得住。请在医生规定的液体限制内慢慢小口喝，保持坐直休息，并暂时避免大量或油腻食物。留意是否再次呕吐；若反复呕吐，或出现虚弱、头晕、尿量很少、胸痛或呼吸困难，请联系医疗团队。",
                "நன்று—சிறிய அளவு திரவம் தங்குகிறது. மருத்துவர் கூறிய திரவ வரம்பிற்குள் மெதுவாக குடித்து, நேராக அமர்ந்து ஓய்வெடுத்து, கனமான அல்லது எண்ணெய் உணவை இப்போது தவிர்க்கவும். மீண்டும் வாந்தி வருகிறதா என்று கவனிக்கவும். வாந்தி மீண்டும் வந்தாலோ பலவீனம், மயக்கம், சிறுநீர் மிகவும் குறைதல், நெஞ்சுவலி அல்லது மூச்சுத்திணறல் ஏற்பட்டாலோ மருத்துவக் குழுவை தொடர்புகொள்ளவும்."
            )
        } else {
            replyText(mode,
                "Because you cannot keep even small sips or your medicines down, please contact your healthcare team promptly now for advice. Do not take extra medicine to replace a vomited dose unless a clinician tells you to. Call 999 for severe chest pain, severe breathlessness, fainting, confusion, blood, or coffee-ground vomit.",
                "Oleh sebab sedikit air atau ubat pun tidak dapat kekal, sila hubungi pasukan kesihatan dengan segera sekarang untuk nasihat. Jangan ambil dos tambahan bagi menggantikan ubat yang dimuntahkan kecuali diarahkan oleh doktor. Hubungi 999 jika ada sakit dada teruk, sesak nafas teruk, pengsan, keliru, darah atau muntah seperti serbuk kopi.",
                "由于连少量液体或药物都无法留住，请立即联系医疗团队寻求建议。除非临床人员指示，否则不要自行补服可能吐出的药物。若出现严重胸痛、严重呼吸困难、晕厥、意识混乱、吐血或咖啡渣样呕吐物，请拨打 999。",
                "சிறிய அளவு திரவம் அல்லது மருந்தும் தங்காததால், உடனடியாக மருத்துவக் குழுவை தொடர்புகொண்டு ஆலோசனை பெறவும். மருத்துவர் கூறாமல் வாந்தியுடன் வெளியேறிய மருந்துக்கு பதிலாக கூடுதல் அளவு எடுக்க வேண்டாம். கடுமையான நெஞ்சுவலி, மூச்சுத்திணறல், மயக்கம், குழப்பம், இரத்தம் அல்லது காபித்தூள் போன்ற வாந்தி இருந்தால் 999-ஐ அழைக்கவும்."
            )
        }
    }
    return null
}

private fun isGreetingQuestion(q: String): Boolean {
    val greetings = listOf(
        "hi", "hello", "hey", "hai", "helo", "good morning", "good afternoon",
        "good evening", "selamat pagi", "selamat petang", "selamat malam",
        "你好", "您好", "嗨", "வணக்கம்", "ஹாய்"
    )
    return greetings.any { q == it || q.startsWith("$it ") }
}

private fun isThanksQuestion(q: String): Boolean =
    listOf("thank", "thanks", "thank you", "terima kasih", "谢谢", "நன்றி")
        .any { it in q }

private fun isNauseaOrVomitingQuestion(q: String): Boolean =
    listOf(
        "nausea", "nauseous", "vomit", "vomiting", "throw up", "throwing up",
        "feel sick", "feeling sick", "sick to my stomach", "want to vomit", "gonna vomit",
        "loya", "mual", "muntah", "nak muntah", "rasa nak muntah",
        "恶心", "想吐", "要吐", "呕吐", "吐了",
        "குமட்டல்", "வாந்தி", "வாந்தி வருவது", "வாந்தி வர மாதிரி"
    ).any { it in q }

private fun isEmotionalDistressQuestion(q: String): Boolean =
    listOf(
        "sad", "unhappy", "down", "lonely", "cry", "crying", "upset", "depressed",
        "hopeless", "worried", "worry", "anxious", "anxiety", "scared", "afraid",
        "stressed", "overwhelmed", "sedih", "tak gembira", "sunyi", "menangis",
        "kecewa", "tertekan", "risau", "takut", "难过", "伤心", "不开心", "孤独",
        "沮丧", "担心", "焦虑", "害怕", "压力", "சோகம்", "வருத்தம்", "தனிமை",
        "மனச்சோர்வு", "கவலை", "பயம்", "பதட்டம்"
    ).any { it in q }

private fun nauseaFallbackReply(mode: AiReplyMode): String = when (mode) {
    AiReplyMode.MANDARIN -> "听起来你有恶心或想吐的感觉。先坐直并慢慢呼吸；若能喝水，可在医生规定的每日液体限制内小口喝，不要一次喝很多。暂时避免油腻、辛辣和气味很重的食物，也不要自行服用止吐药。若反复呕吐、无法留住水或心脏药物，请尽快联系医疗团队。若吐血或像咖啡渣、剧烈胸痛/腹痛、晕厥、意识混乱或严重呼吸困难，请立即寻求紧急帮助。你已经吐了吗？大约几次？"
    AiReplyMode.TAMIL -> "உங்களுக்கு குமட்டல் அல்லது வாந்தி வருவது போல இருக்கிறது. முதலில் நேராக அமர்ந்து மெதுவாக மூச்சை இழுக்கவும். குடிக்க முடிந்தால், மருத்துவர் கூறிய தினசரி திரவ வரம்பிற்குள் சிறு சிறு சிப்புகளாக குடிக்கவும்; ஒரே நேரத்தில் அதிகமாக குடிக்க வேண்டாம். எண்ணெய், காரம் மற்றும் கடுமையான மணம் உள்ள உணவை தற்காலிகமாக தவிர்க்கவும்; தானாக வாந்தி மருந்து எடுத்துக்கொள்ள வேண்டாம். மீண்டும் மீண்டும் வாந்தி, தண்ணீர் அல்லது இதய மருந்தை வைத்திருக்க முடியாமை இருந்தால் மருத்துவக் குழுவை விரைவாக தொடர்புகொள்ளவும். இரத்த வாந்தி/காபித் தூள் போன்ற வாந்தி, கடுமையான நெஞ்சு அல்லது வயிற்று வலி, மயக்கம், குழப்பம் அல்லது கடுமையான மூச்சுத்திணறல் இருந்தால் உடனடி அவசர உதவி பெறவும். நீங்கள் ஏற்கனவே வாந்தி எடுத்தீர்களா? எத்தனை முறை?"
    AiReplyMode.MALAY -> "Nampaknya anda berasa loya atau mahu muntah. Duduk tegak dan tarik nafas perlahan. Jika boleh minum, ambil teguk kecil sahaja dalam had cecair harian yang doktor tetapkan—jangan minum banyak sekaligus. Elakkan sementara makanan berminyak, pedas dan berbau kuat, serta jangan ambil ubat tahan muntah sendiri. Jika muntah berulang, tidak dapat menyimpan air atau ubat jantung, hubungi pasukan kesihatan dengan segera. Jika muntah darah/warna seperti serbuk kopi, sakit dada atau perut yang teruk, pengsan, keliru atau sesak nafas teruk, dapatkan bantuan kecemasan segera. Adakah anda sudah muntah, dan berapa kali?"
    AiReplyMode.ROJAK -> "Sounds like anda rasa loya atau nak muntah. Sit upright dan tarik nafas perlahan. Kalau boleh minum, ambil small sips dalam fluid limit yang doctor tetapkan—jangan minum banyak sekali gus. Avoid oily, spicy atau strong-smelling food sementara, dan jangan ambil anti-vomiting medicine sendiri. Kalau muntah berulang atau tak boleh keep water/heart medicine down, contact healthcare team cepat. Kalau muntah darah/coffee-ground, severe chest atau stomach pain, pengsan, keliru atau sesak nafas teruk, seek emergency help sekarang. Anda sudah muntah ke, dan berapa kali?"
    AiReplyMode.ENGLISH -> "It sounds like you feel nauseous or may vomit. Sit upright and breathe slowly. If you can drink, take small sips within the daily fluid limit your doctor gave you—do not drink a large amount at once. Avoid oily, spicy, or strong-smelling food for now, and do not start an anti-nausea medicine on your own. Contact your healthcare team promptly if vomiting repeats or you cannot keep fluids or heart medicines down. Seek emergency help for blood or coffee-ground vomit, severe chest or abdominal pain, fainting, confusion, or severe breathlessness. Have you vomited yet, and how many times?"
}

private fun emotionalFallbackReply(mode: AiReplyMode): String = when (mode) {
    AiReplyMode.MANDARIN -> "听起来你现在很难受或很担心。谢谢你告诉我。先坐在安全的地方，慢慢呼吸，也可以联系一位你信任的家人或朋友陪着你。你愿意告诉我发生了什么，或者现在最困扰你的感觉是什么吗？如果你可能伤害自己，请立即联系紧急服务或可信任的人。"
    AiReplyMode.TAMIL -> "நீங்கள் இப்போது வருத்தமாகவோ கவலையாகவோ இருப்பது போலத் தெரிகிறது. பகிர்ந்ததற்கு நன்றி. பாதுகாப்பான இடத்தில் அமர்ந்து மெதுவாக மூச்சை இழுத்து, நம்பகமான குடும்பத்தினர் அல்லது நண்பரை தொடர்புகொள்ளுங்கள். என்ன நடந்தது அல்லது இப்போது எந்த உணர்வு மிகவும் சிரமமாக உள்ளது என்று சொல்ல விரும்புகிறீர்களா? உங்களை காயப்படுத்திக் கொள்ளலாம் என்று தோன்றினால் உடனடியாக அவசர சேவையையோ நம்பகமான ஒருவரையோ அணுகவும்."
    AiReplyMode.MALAY -> "Saya dengar anda sedang berasa sedih, risau atau tertekan. Terima kasih kerana memberitahu saya. Duduk di tempat yang selamat, tarik nafas perlahan dan cuba hubungi ahli keluarga atau rakan yang anda percayai. Mahu ceritakan apa yang berlaku atau perasaan mana yang paling mengganggu sekarang? Jika anda rasa mungkin mencederakan diri, hubungi bantuan kecemasan atau seseorang yang dipercayai dengan segera."
    AiReplyMode.ROJAK -> "Saya dengar anda tengah rasa sad, risau atau overwhelmed. Thank you sebab beritahu saya. Sit somewhere safe, tarik nafas perlahan dan cuba contact family atau friend yang anda percaya. Apa yang berlaku, atau feeling mana paling susah sekarang? Kalau anda rasa mungkin mencederakan diri, contact emergency help atau trusted person sekarang."
    AiReplyMode.ENGLISH -> "I hear that you are feeling sad, worried, or overwhelmed. Thank you for telling me. Sit somewhere safe, breathe slowly, and consider contacting a trusted family member or friend. What happened, or which feeling is hardest right now? If you may hurt yourself, contact emergency help or a trusted person immediately."
}

private val aiHeartCareKeywords = listOf(
    "my bp", "blood pressure", "bp", "spo2", "oxygen", "heart rate", "pulse", "weight",
    "symptom", "warning", "water", "fluid", "salt", "steps", "target", "remaining",
    "medicine", "medication", "appointment", "today", "latest", "reading", "record",
    "food", "diet", "meal", "healthy plate", "sleep", "tired", "fatigue", "cough",
    "swelling", "breathless", "breathlessness", "dizzy", "chest pain", "heart failure",
    "tekanan darah", "oksigen", "nadi", "berat", "simptom", "amaran", "air", "garam",
    "langkah", "ubat", "temujanji", "hari ini", "bacaan", "makanan", "tidur", "penat",
    "batuk", "bengkak", "sesak nafas", "pening", "kegagalan jantung",
    "nausea", "nauseous", "vomit", "vomiting", "loya", "mual", "muntah",
    "stomach pain", "stomach ache", "abdominal pain", "belly pain", "sakit perut",
    "headache", "sakit kepala", "diarrhea", "cirit-birit", "constipation", "sembelit",
    "fever", "demam", "palpitation", "poor appetite", "tiada selera",
    "self care", "monitor", "systolic", "diastolic", "sodium", "nutrition",
    "weigh", "gain", "fluid retention", "activity", "rehab", "cardiac rehabilitation",
    "active", "tablet", "diuretic", "water tablet", "side effect", "dose",
    "doctor", "nurse", "clinic", "care team", "review", "follow up",
    "lifestyle", "smoking", "vaping", "alcohol", "stress", "sex", "travel",
    "herbal", "supplement", "natural", "alternative", "traditional", "vitamin",
    "caregiver", "family", "support", "partner", "relative", "carer",
    "penjagaan", "pantau", "harian", "rekod", "semak", "dos", "kesan sampingan",
    "doktor", "jururawat", "klinik", "susulan", "gaya hidup", "merokok", "vape",
    "alkohol", "stres", "herba", "suplemen", "semula jadi", "tradisional",
    "alternatif", "penjaga", "keluarga", "sokongan", "pasangan"
)

private val aiConversationKeywords = listOf(
    "hi", "hello", "hey", "hai", "thank", "thanks", "terima kasih", "sad", "worried",
    "scared", "anxious", "sedih", "risau", "takut"
)

// These are not new health topics. They are the answer shapes already used by
// the My Chat symptom interview (location, timing, frequency and severity).
// Keeping them here lets the floating nurse accept natural follow-up answers
// without opening the scope to arbitrary Gemini questions.
private val aiConversationFollowUpKeywords = listOf(
    "yes", "no", "once", "twice", "time", "times", "today", "yesterday", "just now",
    "morning", "afternoon", "evening", "night", "hour", "hours", "day", "days",
    "left", "right", "upper", "lower", "middle", "stomach", "abdomen", "chest", "leg",
    "mild", "moderate", "severe", "little", "much", "better", "worse", "same",
    "sharp", "dull", "burning", "tight", "tightness", "pressure", "cramp", "aching",
    "dry", "mucus", "regular", "irregular", "one leg", "both legs", "lying flat",
    "can drink", "cannot drink", "keep fluids", "keep water", "keep medicine",
    "boleh", "tak", "tidak", "sekali", "dua kali", "pagi", "petang", "malam",
    "jam", "hari", "kiri", "kanan", "atas", "bawah", "perut", "dada", "kaki",
    "ringan", "sederhana", "teruk", "lebih baik", "lebih teruk",
    "是", "不是", "一次", "两次", "今天", "昨天", "刚才", "早上", "晚上",
    "左", "右", "上", "下", "腹部", "胸部", "腿", "轻微", "严重",
    "ஆம்", "இல்லை", "ஒரு முறை", "இரண்டு முறை", "இன்று", "நேற்று",
    "காலை", "இரவு", "இடது", "வலது", "மேல்", "கீழ்", "வயிறு", "நெஞ்சு", "கால்"
)

private fun isAiNurseAllowedMessage(normalized: String, history: List<ChatMessage>): Boolean {
    val q = normalized.trim().lowercase(Locale.ROOT)
    if (q.isBlank()) return false
    if (isClearlyUnrelatedQuestion(q)) return false

    if (isGreetingQuestion(q) || isThanksQuestion(q) || isEmotionalDistressQuestion(q)) return true
    if (hasRecognizedLocalSymptom(q) || isOverallHealthQuestion(q)) return true
    if (aiHeartCareKeywords.any { containsAiKeyword(q, it) }) return true
    if (aiConversationKeywords.any { containsAiKeyword(q, it) }) return true

    // Unknown health vocabulary is handled by the responder, not a restrictive local whitelist.
    return true
}

private fun shouldPreferLocalReply(q: String, mode: AiReplyMode): Boolean {
    if (mode == AiReplyMode.MANDARIN || mode == AiReplyMode.TAMIL) return true
    if (isClearlyUnrelatedQuestion(q) || isOverallHealthQuestion(q)) return true
    if (findHeartFailureMattersTopic(q) != null) return true
    return aiHeartCareKeywords.any { containsAiKeyword(q, it) } ||
        aiConversationKeywords.any { containsAiKeyword(q, it) }
}

private fun serverReplyIsUseful(reply: String, q: String, mode: AiReplyMode): Boolean {
    val trimmed = reply.trim()
    val reminderOnly = listOf("log your", "record your", "log today's", "missing data", "记录体重", "记录您的", "rekod berat", "பதிவு செய்ய").any { it in trimmed.lowercase(Locale.ROOT) }
    if (PcnaPatientAdvice.answer(q, mode.apiLanguageCode()) != null && reminderOnly) return false
    if (listOf("ai service is currently busy", "please try again later", "service unavailable").any { it in trimmed.lowercase(Locale.ROOT) }) return false
    val conversational = isGreetingQuestion(q) || isThanksQuestion(q) ||
        hasRecognizedLocalSymptom(q) || isEmotionalDistressQuestion(q)
    if (trimmed.length < if (conversational) 4 else 20) return false
    if (looksLikeOldScopeRejection(trimmed) && !isClearlyUnrelatedQuestion(q)) return false

    val temporaryBackendMessages = listOf(
        "ai service is currently busy",
        "please try again later",
        "gemini request failed",
        "service unavailable"
    )
    if (hasRecognizedLocalSymptom(q) && temporaryBackendMessages.any { it in trimmed.lowercase(Locale.ROOT) }) {
        return false
    }

    // Reject the old overall-health data dump when the patient just reported a
    // concrete symptom. This prevents stomach pain, headache, dizziness, etc.
    // from turning into a BP/SpO2/weight summary.
    val normalizedReply = normalizeAiQuestion(trimmed)
    val genericOverviewDump = listOf(
        "based on your latest records",
        "based on latest record",
        "your readings are",
        "no critical reading is identified",
        "missing data:"
    ).count { it in normalizedReply } >= 2
    if (hasRecognizedLocalSymptom(q) && genericOverviewDump) return false
    if (mode == AiReplyMode.MANDARIN && !Regex("[\u4E00-\u9FFF]").containsMatchIn(trimmed)) return false
    if (mode == AiReplyMode.TAMIL && !Regex("[\u0B80-\u0BFF]").containsMatchIn(trimmed)) return false
    if (mode == AiReplyMode.ENGLISH && (
            Regex("[\u4E00-\u9FFF]").containsMatchIn(trimmed) ||
            Regex("[\u0B80-\u0BFF]").containsMatchIn(trimmed)
        )
    ) return false
    if (mode == AiReplyMode.MALAY) {
        val malayMarkers = listOf(
            "anda", "saya", "hai", "boleh", "kesihatan", "rasa", "perlu", "hari",
            "bacaan", "tekanan", "denyutan", "berat", "simptom", "ubat",
            "langkah", "temujanji", "hubungi", "dapatkan", "sila", "jika", "dan"
        )
        val markerCount = malayMarkers.count {
            containsAiKeyword(trimmed.lowercase(Locale.ROOT), it)
        }
        if (markerCount < if (conversational) 1 else 2) return false
    }
    return true
}

private fun containsAiKeyword(text: String, keyword: String): Boolean {
    if (' ' in keyword) return keyword in text
    return Regex("(^|[^\\p{L}\\p{N}_])${Regex.escape(keyword)}([^\\p{L}\\p{N}_]|$)")
        .containsMatchIn(text)
}

internal fun detectReplyMode(question: String, fallbackMalay: Boolean): AiReplyMode {
    val q = question.lowercase(Locale.ROOT)
    if (Regex("[\\u4E00-\\u9FFF]").containsMatchIn(question)) return AiReplyMode.MANDARIN
    if (Regex("[\\u0B80-\\u0BFF]").containsMatchIn(question)) return AiReplyMode.TAMIL

    val malayWords = listOf(
        "apa", "adakah", "bagaimana", "macam mana", "camne", "kenapa", "boleh", "saya",
        "aku", "nak", "dah", "belum", "tak", "berapa", "bacaan", "ubat", "boleh ke",
        "garam", "air", "berat", "langkah", "sesak", "temu janji", "temujanji", "simptom",
        "jantung", "pening", "berdebar", "bengkak", "penat", "makan", "minum"
    )
    val englishWords = listOf(
        "what", "how", "why", "can", "should", "my", "reading", "medicine", "salt",
        "water", "weight", "steps", "appointment", "symptom", "heart", "bp", "spo2",
        "pulse", "exercise", "walk", "normal", "okay", "target", "remaining", "doctor"
    )
    val malayScore = malayWords.count { containsAiKeyword(q, it) }
    val englishScore = englishWords.count { containsAiKeyword(q, it) }
    return when {
        malayScore > 0 && englishScore > 0 -> AiReplyMode.ROJAK
        malayScore > englishScore -> AiReplyMode.MALAY
        englishScore > malayScore -> AiReplyMode.ENGLISH
        AppLanguage.useMandarin -> AiReplyMode.MANDARIN
        AppLanguage.useTamil -> AiReplyMode.TAMIL
        fallbackMalay -> AiReplyMode.MALAY
        else -> AiReplyMode.ENGLISH
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatScreen(onBack: () -> Unit, vm: AiChatViewModel = hiltViewModel()) {
    val ms = AppLanguage.useMalay
    val activeLanguage = AppLanguage.current
    val context = LocalContext.current
    val patientId by vm.patientId.collectAsState()
    val riskNotice by vm.riskNotice.collectAsState()
    val botColor = Color(0xFF00897B)
    var input by remember { mutableStateOf("") }
    var replying by remember { mutableStateOf(false) }
    var loadedHistoryPatientId by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf(defaultAiGreeting()) }
    val chatListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(messages.size, replying) {
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        val last = chatListState.layoutInfo.totalItemsCount - 1
        if (last >= 0) chatListState.animateScrollToItem(last)
    }
    val quickQuestions = listOf(
        AppLanguage.text(
            "How is my health for today?",
            "Bagaimana keadaan kesihatan saya hari ini?",
            "我今天的健康状况如何？",
            "இன்று என் உடல்நிலை எப்படி உள்ளது?"
        ),
        AppLanguage.text(
            "Which warning signs should I watch for?",
            "Apakah tanda amaran yang perlu saya perhatikan?",
            "我应该留意哪些警告症状？",
            "எந்த எச்சரிக்கை அறிகுறிகளை கவனிக்க வேண்டும்?"
        )
    )

    LaunchedEffect(patientId, activeLanguage) {
        loadedHistoryPatientId = null
        messages.clear()
        messages.addAll(loadAiHistory(context, patientId))
        loadedHistoryPatientId = patientId
    }
    LaunchedEffect(messages.size, patientId, loadedHistoryPatientId) {
        if (loadedHistoryPatientId == patientId) {
            saveAiHistory(context, patientId, messages)
        }
    }

    fun submitQuestion(question: String) {
        val q = question.trim()
        if (q.isBlank() || replying) return
        val history = messages.takeLast(20).map { message ->
            ChatMessage(
                role = if (message.fromUser) "user" else "assistant",
                content = message.text
            )
        }
        messages += LocalAiMessage(true, q)
        input = ""
        vm.clearRiskNotice()
        replying = true
        val languageSource = if (isLikelyShortConversationAnswer(normalizeAiQuestion(q))) {
            history.asReversed().firstOrNull { it.role == "user" }?.content ?: q
        } else q
        val replyMode = detectReplyMode(languageSource, ms)
        val memoryNotes = loadAiMemoryNotes(context, patientId)
        vm.answer(q, replyMode, history, memoryNotes) { reply, memoryUpdates ->
            messages += LocalAiMessage(false, patientFacingAnswer(reply))
            saveAiMemoryNotes(context, patientId, memoryNotes, memoryUpdates)
            replying = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = .2f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.SmartToy, null, tint = Color.White) }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                AppLanguage.text("My chat", "Sembang Saya", "我的聊天", "என் அரட்டை"),
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "English • BM • 中文 • தமிழ்",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = .85f)
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                actions = {
                    IconButton(onClick = vm::load) { Icon(Icons.Default.Refresh, null, tint = Color.White) }
                    IconButton(onClick = {
                        messages.clear()
                        messages += defaultAiGreeting()
                        vm.clearRiskNotice()
                        clearAiMemoryNotes(context, patientId)
                        saveAiHistory(context, patientId, messages)
                    }) { Icon(Icons.Default.DeleteSweep, AppLanguage.text("Clear history", "Padam sejarah"), tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = botColor)
            )
        },
        bottomBar = {
            Surface(tonalElevation = 6.dp, modifier = Modifier.imePadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(AppLanguage.text("Chat with me", "Berbual dengan saya", "和我聊天", "என்னுடன் பேசுங்கள்")) },
                        shape = RoundedCornerShape(18.dp),
                        maxLines = 3
                    )
                    Spacer(Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = { submitQuestion(input) },
                        containerColor = botColor
                    ) { Icon(Icons.Default.Send, null, tint = Color.White) }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 14.dp),
            state = chatListState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp)
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F2F1)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            AppLanguage.text("Example questions", "Contoh soalan", "示例问题", "உதாரணக் கேள்விகள்"),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00695C)
                        )
                        quickQuestions.forEach { question ->
                            OutlinedButton(
                                onClick = { submitQuestion(question) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = .65f))
                            ) {
                                Text(question, Modifier.fillMaxWidth(), textAlign = TextAlign.Start, color = Color(0xFF00695C), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            riskNotice?.let { notice ->
                item {
                    val isEmergency = notice.level == "emergency"
                    val isUrgent = notice.level == "urgent"
                    val background = when {
                        isEmergency -> Color(0xFFFFEBEE)
                        isUrgent -> Color(0xFFFFF3E0)
                        else -> Color(0xFFFFF8E1)
                    }
                    val foreground = when {
                        isEmergency -> Color(0xFFB71C1C)
                        isUrgent -> Color(0xFFE65100)
                        else -> Color(0xFF795548)
                    }
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = background),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                if (isEmergency || isUrgent) Icons.Default.Warning else Icons.Default.Info,
                                contentDescription = null,
                                tint = foreground
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    when (notice.level) {
                                        "emergency" -> AppLanguage.text("Emergency warning", "Amaran kecemasan", "紧急警告", "அவசர எச்சரிக்கை")
                                        "urgent" -> AppLanguage.text("Needs prompt attention", "Perlu perhatian segera", "需要尽快处理", "விரைவான கவனம் தேவை")
                                        else -> AppLanguage.text("Please monitor", "Sila pantau", "请继续观察", "தொடர்ந்து கண்காணிக்கவும்")
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = foreground
                                )
                                notice.suggestedAction?.takeIf { it.isNotBlank() }?.let { action ->
                                    Text(action, style = MaterialTheme.typography.bodySmall, color = foreground)
                                }
                            }
                        }
                    }
                }
            }
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                    Card(
                        Modifier.fillMaxWidth(.86f),
                        colors = CardDefaults.cardColors(containerColor = if (message.fromUser) botColor else MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            if (message.fromUser) message.text else patientFacingAnswer(message.text),
                            Modifier.padding(14.dp),
                            color = if (message.fromUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (replying) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
                        Text(AppLanguage.text("Preparing an answer…", "Sedang menyediakan jawapan…"), Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val heartFailureMattersKnowledge = listOf(
    HeartFailureKnowledgeTopic(
        titleEn = "Understanding heart failure",
        titleMs = "Memahami heart failure",
        keywords = listOf("what is", "meaning", "understand", "heart failure", "cause", "causes", "jantung lemah", "maksud", "punca"),
        adviceEn = listOf(
            "Heart failure means the heart is not pumping blood as well as the body needs. It can cause tiredness, breathlessness, swelling and reduced activity tolerance.",
            "It is usually a long-term condition, so daily monitoring, medicines, lifestyle changes and follow-up appointments all work together.",
            "Your readings are useful because changes in BP, pulse, oxygen, weight and symptoms can show whether your condition is stable or worsening."
        ),
        adviceMs = listOf(
            "Heart failure bermaksud jantung tidak mengepam darah sebaik yang badan perlukan. Ia boleh menyebabkan cepat penat, sesak nafas, bengkak dan kurang mampu aktif.",
            "Keadaan ini biasanya jangka panjang, jadi pemantauan harian, ubat, gaya hidup dan temujanji susulan semuanya penting.",
            "Bacaan anda berguna kerana perubahan BP, nadi, oksigen, berat dan simptom boleh menunjukkan sama ada keadaan stabil atau semakin teruk."
        ),
        sourceUrl = "https://www.heartfailurematters.org/understanding-heart-failure/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Warning signs and symptoms",
        titleMs = "Tanda amaran dan simptom",
        keywords = listOf("warning", "danger", "symptom", "breath", "breathless", "cough", "swelling", "pillow", "abdomen", "chest pain", "amaran", "bahaya", "simptom", "sesak", "nafas", "batuk", "bengkak", "bantal", "dada"),
        adviceEn = listOf(
            "Watch for worsening breathlessness, new or increasing swelling, needing more pillows to sleep, more coughing, chest discomfort, fainting or sudden weight gain.",
            "If symptoms suddenly become severe, or you have chest pain, fainting, blue lips, severe breathlessness at rest or confusion, seek urgent medical help.",
            "Record symptoms once per day so your doctor can see the pattern, not only one isolated reading."
        ),
        adviceMs = listOf(
            "Pantau sesak nafas yang makin teruk, bengkak baru/bertambah, perlu tambah bantal semasa tidur, batuk bertambah, rasa tidak selesa dada, pengsan atau berat naik mendadak.",
            "Jika simptom tiba-tiba teruk, sakit dada, pengsan, bibir kebiruan, sesak nafas teruk semasa rehat atau keliru, dapatkan bantuan perubatan segera.",
            "Catat simptom sekali sehari supaya doktor dapat melihat trend, bukan hanya satu bacaan sahaja."
        ),
        sourceUrl = "https://www.heartfailurematters.org/understanding-heart-failure/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Self-care and daily monitoring",
        titleMs = "Penjagaan diri dan pemantauan harian",
        keywords = listOf("self care", "monitor", "daily", "record", "check", "chart", "diary", "penjagaan", "pantau", "harian", "rekod", "semak"),
        adviceEn = listOf(
            "Good self-care includes taking medicines as prescribed, checking important readings, tracking symptoms, eating sensibly, staying active within your limit and attending reviews.",
            "Try to measure weight at a similar time each day. A quick rise can mean fluid retention and should not be ignored.",
            "Bring your records to appointments so the doctor or nurse can adjust care based on your real pattern."
        ),
        adviceMs = listOf(
            "Penjagaan diri yang baik termasuk ambil ubat ikut arahan, semak bacaan penting, rekod simptom, makan secara sesuai, kekal aktif mengikut kemampuan dan hadir temujanji.",
            "Cuba timbang berat pada masa yang hampir sama setiap hari. Berat naik cepat boleh menandakan pengumpulan air dalam badan.",
            "Bawa rekod ke temujanji supaya doktor atau jururawat boleh ubah penjagaan berdasarkan trend sebenar anda."
        ),
        sourceUrl = "https://www.heartfailurematters.org/what-you-can-do/introduction/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Blood pressure and pulse",
        titleMs = "Tekanan darah dan nadi",
        keywords = listOf("bp", "blood pressure", "pulse", "heart rate", "systolic", "diastolic", "tekanan", "darah", "nadi"),
        adviceEn = listOf(
            "Rest quietly for about 5 minutes before measuring. Sit supported, keep feet flat, and measure at about heart level.",
            "Very high, very low, or fast/slow pulse readings should be rechecked. If the reading stays abnormal or symptoms appear, contact your care team.",
            "Do not change BP or heart medicines by yourself unless your doctor has given a clear plan."
        ),
        adviceMs = listOf(
            "Rehat dengan tenang kira-kira 5 minit sebelum ukur. Duduk bersandar, kaki rata, dan ukur pada paras jantung.",
            "Bacaan terlalu tinggi, terlalu rendah, atau nadi terlalu laju/perlahan perlu disemak semula. Jika kekal tidak normal atau ada simptom, hubungi pasukan rawatan.",
            "Jangan ubah ubat BP atau ubat jantung sendiri kecuali doktor sudah beri pelan yang jelas."
        ),
        sourceUrl = "https://www.heartfailurematters.org/what-you-can-do/introduction/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Salt, fluid and diet",
        titleMs = "Garam, air dan pemakanan",
        keywords = listOf("salt", "sodium", "fluid", "water", "drink", "diet", "food", "eat", "meal", "nutrition", "garam", "air", "minum", "makanan", "makan", "diet"),
        adviceEn = listOf(
            "Many patients are advised to reduce salt because salt can make the body retain fluid. Choose fresh foods more often and limit salty processed foods.",
            "Fluid advice is personal. Follow the amount given by your doctor, nurse or dietitian because some patients need a stricter limit than others.",
            "Soup, porridge, ice, jelly and juicy fruits can also add to fluid intake, so count them when your care team asks you to limit fluids."
        ),
        adviceMs = listOf(
            "Ramai pesakit dinasihatkan kurangkan garam kerana garam boleh menyebabkan badan menyimpan air. Pilih makanan segar lebih kerap dan hadkan makanan proses yang masin.",
            "Nasihat air adalah peribadi. Ikut jumlah yang diberi oleh doktor, jururawat atau dietitian kerana sesetengah pesakit perlu had yang lebih ketat.",
            "Sup, bubur, ais, jeli dan buah yang banyak air juga boleh menambah pengambilan cecair, jadi kira sekali jika pasukan rawatan minta hadkan air."
        ),
        sourceUrl = "https://www.heartfailurematters.org/what-you-can-do/adjusting-your-diet-salt/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Weight changes",
        titleMs = "Perubahan berat badan",
        keywords = listOf("weight", "weigh", "kg", "gain", "fluid retention", "berat", "timbang", "naik berat"),
        adviceEn = listOf(
            "Sudden weight gain can be a sign of extra fluid, especially when it comes with swelling, breathlessness or needing more pillows to sleep.",
            "Use the same scale and similar timing each day. Record the value even when you feel well so the trend is clear.",
            "Contact your care team if weight rises quickly compared with your usual baseline or your app shows a warning."
        ),
        adviceMs = listOf(
            "Berat naik mendadak boleh menandakan lebihan air dalam badan, terutama jika bersama bengkak, sesak nafas atau perlu tambah bantal semasa tidur.",
            "Guna penimbang yang sama dan masa yang hampir sama setiap hari. Rekod nilai walaupun rasa sihat supaya trend jelas.",
            "Hubungi pasukan rawatan jika berat naik cepat berbanding baseline biasa atau aplikasi menunjukkan amaran."
        ),
        sourceUrl = "https://www.heartfailurematters.org/what-you-can-do/adjusting-your-diet-maintaining-a-healthy-weight/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Activity, exercise and cardiac rehabilitation",
        titleMs = "Aktiviti, senaman dan rehabilitasi jantung",
        keywords = listOf("exercise", "activity", "walk", "steps", "rehab", "cardiac rehabilitation", "active", "senaman", "aktiviti", "jalan", "langkah", "rehabilitasi"),
        adviceEn = listOf(
            "Regular gentle activity can help many people with heart failure, but the safe level depends on your condition and doctor’s advice.",
            "Start slowly, warm up, and stop if you feel chest pain, severe breathlessness, dizziness, faintness or unusual palpitations.",
            "A cardiac rehabilitation programme can teach safer exercise, symptom monitoring and confidence-building."
        ),
        adviceMs = listOf(
            "Aktiviti ringan yang konsisten boleh membantu ramai pesakit heart failure, tetapi tahap selamat bergantung pada keadaan anda dan nasihat doktor.",
            "Mula perlahan, panaskan badan, dan berhenti jika sakit dada, sesak nafas teruk, pening, rasa hendak pengsan atau degupan tidak biasa.",
            "Program rehabilitasi jantung boleh ajar senaman yang lebih selamat, pemantauan simptom dan keyakinan diri."
        ),
        sourceUrl = "https://www.heartfailurematters.org/living-with-heart-failure/lifestyle/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Medicines and routines",
        titleMs = "Ubat dan rutin",
        keywords = listOf("medicine", "medication", "tablet", "diuretic", "water tablet", "side effect", "dose", "ubat", "dos", "kesan sampingan"),
        adviceEn = listOf(
            "Many people with heart failure take several medicines. A medicine chart helps you remember names, doses, times and refill needs.",
            "Take medicines exactly as prescribed. Do not stop them because you feel better unless your doctor tells you to.",
            "Ask your doctor or pharmacist what side effects to watch for and what to do if a dose is missed."
        ),
        adviceMs = listOf(
            "Ramai pesakit heart failure mengambil beberapa jenis ubat. Carta ubat membantu ingat nama, dos, masa dan bila perlu tambah stok.",
            "Ambil ubat tepat seperti diarahkan. Jangan berhenti kerana rasa lebih baik kecuali doktor suruh.",
            "Tanya doktor atau ahli farmasi kesan sampingan yang perlu dipantau dan apa perlu dibuat jika terlupa dos."
        ),
        sourceUrl = "https://www.heartfailurematters.org/living-with-heart-failure/managing-your-medicines/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Appointments and care team",
        titleMs = "Temujanji dan pasukan rawatan",
        keywords = listOf("appointment", "doctor", "nurse", "clinic", "care team", "review", "follow up", "temujanji", "doktor", "jururawat", "klinik", "susulan"),
        adviceEn = listOf(
            "Heart failure care often involves a team, such as doctors, nurses, pharmacists, dietitians and rehabilitation staff.",
            "Before appointments, prepare your symptom changes, weight trend, BP/pulse readings, medicines and questions.",
            "Ask clearly when you should call the clinic, go to emergency care, or adjust any treatment plan."
        ),
        adviceMs = listOf(
            "Penjagaan heart failure selalunya melibatkan pasukan seperti doktor, jururawat, ahli farmasi, dietitian dan staf rehabilitasi.",
            "Sebelum temujanji, sediakan perubahan simptom, trend berat, bacaan BP/nadi, ubat dan soalan.",
            "Tanya dengan jelas bila perlu hubungi klinik, pergi kecemasan, atau ikut pelan perubahan rawatan."
        ),
        sourceUrl = "https://www.heartfailurematters.org/what-your-doctor-can-do/people-that-may-be-involved-in-your-care/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Lifestyle habits",
        titleMs = "Tabiat gaya hidup",
        keywords = listOf("lifestyle", "smoking", "vaping", "alcohol", "sleep", "stress", "sex", "travel", "gaya hidup", "merokok", "vape", "alkohol", "tidur", "stres"),
        adviceEn = listOf(
            "Lifestyle changes can support medical treatment. Avoid smoking or vaping, discuss alcohol limits, manage stress and keep regular follow-up.",
            "Do not push through symptoms. Planning rest, pacing activity and asking for support can make daily life safer.",
            "For travel, sex, alcohol or special situations, ask your doctor because advice depends on your stability and treatment."
        ),
        adviceMs = listOf(
            "Perubahan gaya hidup boleh menyokong rawatan. Elakkan merokok atau vape, bincang had alkohol, urus stres dan teruskan follow-up.",
            "Jangan paksa diri bila ada simptom. Rancang rehat, kawal tempo aktiviti dan minta sokongan supaya aktiviti harian lebih selamat.",
            "Untuk perjalanan, hubungan seks, alkohol atau situasi khas, tanya doktor kerana nasihat bergantung pada kestabilan dan rawatan anda."
        ),
        sourceUrl = "https://www.heartfailurematters.org/what-you-can-do/adapting-your-lifestyle/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Alternative or herbal remedies",
        titleMs = "Rawatan alternatif atau herba",
        keywords = listOf("herbal", "supplement", "natural", "alternative", "traditional", "vitamin", "herba", "suplemen", "semula jadi", "tradisional", "alternatif"),
        adviceEn = listOf(
            "Be careful with herbal, natural or alternative remedies. Some have weak evidence and some may interact with heart-failure medicines.",
            "Check with your doctor or pharmacist before starting supplements, traditional medicine or over-the-counter products.",
            "Bring the product name or photo to your appointment so the care team can check ingredients."
        ),
        adviceMs = listOf(
            "Berhati-hati dengan herba, rawatan semula jadi atau alternatif. Ada yang bukti lemah dan ada yang boleh berinteraksi dengan ubat heart failure.",
            "Semak dengan doktor atau ahli farmasi sebelum mula suplemen, ubat tradisional atau produk farmasi biasa.",
            "Bawa nama produk atau gambar semasa temujanji supaya pasukan rawatan boleh semak bahan kandungan."
        ),
        sourceUrl = "https://www.heartfailurematters.org/what-your-doctor-can-do/what-about-alternative-or-natural-remedies/"
    ),
    HeartFailureKnowledgeTopic(
        titleEn = "Caregiver support",
        titleMs = "Sokongan penjaga",
        keywords = listOf("caregiver", "family", "support", "partner", "relative", "carer", "penjaga", "keluarga", "sokongan", "pasangan"),
        adviceEn = listOf(
            "Family or caregivers can help with medicine reminders, low-salt meals, exercise support, symptom monitoring and appointment preparation.",
            "Support should be practical and kind. Encourage the patient, but do not blame them when lifestyle changes feel hard.",
            "Caregivers should also ask for help when they feel overwhelmed."
        ),
        adviceMs = listOf(
            "Keluarga atau penjaga boleh membantu dengan peringatan ubat, makanan rendah garam, sokongan senaman, pemantauan simptom dan persediaan temujanji.",
            "Sokongan perlu praktikal dan baik. Galakkan pesakit, tetapi jangan menyalahkan mereka bila perubahan gaya hidup terasa sukar.",
            "Penjaga juga perlu minta bantuan jika rasa terlalu terbeban."
        ),
        sourceUrl = "https://www.heartfailurematters.org/for-caregivers/simple-things-you-can-do-to-help/"
    )
)

private fun isClearlyUnrelatedQuestion(q: String): Boolean {
    val text = q.trim().lowercase(Locale.ROOT)
    if (text.isBlank()) return false

    // Keep this check focused on clearly non-health intents. Using both phrases
    // and word-boundary regexes catches natural requests such as
    // "write me python code" that V8 missed because it only looked for
    // the exact contiguous phrase "write code".
    val blockedPhrases = listOf(
        "capital of", "movie review", "game cheat", "coding tutorial",
        "programming homework", "weather today", "football score",
        "stock price", "recipe for", "translate this", "solve this equation",
        "write me code", "write a program", "make a program", "create a program",
        "write an essay", "do my homework", "do my assignment",
        "bitcoin price", "crypto price", "lyrics for", "song lyrics"
    )
    if (blockedPhrases.any { it in text }) return true

    val blockedWords = listOf(
        "python", "javascript", "typescript", "java", "kotlin", "coding", "programming",
        "compiler", "algorithm", "leetcode", "sql query", "html", "css",
        "homework", "assignment", "equation", "calculus", "algebra",
        "celebrity", "movie", "anime", "game", "gaming",
        "weather", "football", "soccer", "basketball", "stock", "bitcoin", "crypto",
        "recipe", "lyrics"
    )
    return blockedWords.any { keyword ->
        if (keyword.contains(' ')) {
            text.contains(keyword)
        } else {
            Regex("(^|[^a-z0-9])${Regex.escape(keyword)}([^a-z0-9]|$)").containsMatchIn(text)
        }
    }
}

private fun outOfScopeReply(mode: AiReplyMode): String = replyText(
    mode,
    "I’m here for MyHFGuard heart-care support, so I can’t help with programming or other unrelated topics. You can ask me about symptoms, BP, SpO₂, heart rate, weight, medicines, water and salt, exercise, or appointments.",
    "Saya di sini untuk sokongan penjagaan jantung MyHFGuard, jadi saya tidak boleh membantu dengan pengaturcaraan atau topik yang tidak berkaitan. Anda boleh tanya tentang simptom, BP, SpO₂, denyutan jantung, berat, ubat, air dan garam, senaman atau temujanji.",
    "我是 MyHFGuard 的心脏护理助手，因此不能帮助编程或其他无关主题。您可以询问症状、血压、SpO₂、心率、体重、药物、饮水与盐分、运动或预约。",
    "நான் MyHFGuard இதய பராமரிப்பு ஆதரவுக்காக இருக்கிறேன்; programming அல்லது தொடர்பில்லாத தலைப்புகளில் உதவ முடியாது. அறிகுறிகள், இரத்த அழுத்தம், SpO₂, இதய துடிப்பு, எடை, மருந்துகள், நீர்/உப்பு, உடற்பயிற்சி அல்லது சந்திப்புகள் பற்றி கேட்கலாம்."
)

private fun looksLikeOldScopeRejection(reply: String): Boolean {
    val text = reply.lowercase(Locale.ROOT)
    return listOf(
        "focus on heart care",
        "only help with heart failure",
        "try asking about bp",
        "cuba tanya tentang bp",
        "cuba tanya pasal bp"
    ).any { it in text }
}

private fun questionReportsEmergency(q: String): Boolean {
    val emergencyPhrases = listOf(
        "chest pain", "severe breathless", "cannot breathe", "can't breathe",
        "fainting", "confusion", "blue lips", "stroke", "one-sided weakness",
        "sakit dada", "sesak nafas teruk", "bibir kebiruan", "pengsan",
        "胸痛", "严重呼吸困难", "昏厥", "意识混乱", "嘴唇发蓝",
        "நெஞ்சுவலி", "கடுமையான மூச்சுத்திணறல்", "மயங்கி விழுதல்",
        "குழப்பம்", "உதடு நீலமாகுதல்"
    )
    return emergencyPhrases.any { it in q }
}

private fun localRiskNotice(q: String, c: AiContextState, mode: AiReplyMode): AiRiskNotice? {
    fun action(en: String, ms: String, zh: String, ta: String): String = replyText(mode, en, ms, zh, ta)
    if (questionReportsEmergency(q)) {
        return AiRiskNotice(
            "emergency",
            action(
                "Call Malaysia emergency services at 999 now.",
                "Hubungi perkhidmatan kecemasan Malaysia di 999 sekarang.",
                "请立即拨打马来西亚紧急电话 999。",
                "மலேசிய அவசர சேவை 999-ஐ உடனே அழைக்கவும்."
            )
        )
    }

    val nearFainting = listOf(
        "going to pass out", "about to pass out", "feel faint", "everything is spinning",
        "room is spinning", "rasa nak pengsan", "快要晕倒", "天旋地转", "மயங்கி விழுவது போல"
    ).any { it in q }
    val severeAbdominalPain = listOf(
        "severe abdominal pain", "severe stomach pain", "sudden severe stomach pain",
        "sakit perut teruk", "剧烈腹痛", "严重腹痛", "கடுமையான வயிற்று வலி"
    ).any { it in q }
    val bpParts = c.bp?.split("/")
    val sys = bpParts?.getOrNull(0)?.toIntOrNull()
    val dia = bpParts?.getOrNull(1)?.toIntOrNull()
    val hr = c.heartRate ?: c.pulse
    val urgent = nearFainting || severeAbdominalPain || isChestDiscomfortQuestion(q) ||
        (c.spo2 != null && c.spo2 < 90) ||
        (sys != null && sys >= 180) || (dia != null && dia >= 120) ||
        (hr != null && (hr < 50 || hr > 150)) ||
        (c.weightTrendKg != null && c.weightTrendKg >= 3.0) ||
        (c.symptomScore != null && c.symptomScore >= 18)
    if (urgent) {
        return AiRiskNotice(
            "urgent",
            action(
                "Recheck the reading if it is safe and contact your healthcare team promptly; call 999 if severe symptoms are present.",
                "Semak semula bacaan jika selamat dan hubungi pasukan kesihatan dengan segera; hubungi 999 jika ada simptom teruk.",
                "如情况允许请重新测量，并尽快联系医疗团队；若出现严重症状，请拨打 999。",
                "பாதுகாப்பாக இருந்தால் அளவை மீண்டும் சரிபார்த்து மருத்துவக் குழுவை விரைவாக தொடர்புகொள்ளவும்; கடுமையான அறிகுறிகள் இருந்தால் 999-ஐ அழைக்கவும்."
            )
        )
    }

    val monitor = (c.spo2 != null && c.spo2 < 95) ||
        (sys != null && sys >= 140) || (dia != null && dia >= 90) ||
        (hr != null && (hr < 60 || hr > 100)) ||
        (c.weightTrendKg != null && c.weightTrendKg >= 1.5) ||
        (c.symptomTrendDelta != null && c.symptomTrendDelta > 0) ||
        (c.symptomScore != null && c.symptomScore >= 10) ||
        hasRecognizedLocalSymptom(q)
    return if (monitor) {
        AiRiskNotice(
            "monitor",
            action(
                "Keep monitoring your readings and symptoms and contact your healthcare team if the pattern worsens.",
                "Terus pantau bacaan dan simptom anda serta hubungi pasukan kesihatan jika trend semakin teruk.",
                "请继续观察读数和症状；如果趋势变差，请联系医疗团队。",
                "உங்கள் அளவுகளையும் அறிகுறிகளையும் தொடர்ந்து கண்காணிக்கவும்; நிலை மோசமடைந்தால் மருத்துவக் குழுவை தொடர்புகொள்ளவும்."
            )
        )
    } else null
}

private fun isAbdominalPainQuestion(q: String): Boolean = listOf(
    "stomach pain", "stomach hurts", "stomach hurt", "stomach ache", "stomachache",
    "tummy pain", "tummy ache", "belly pain", "belly ache", "abdominal pain", "abdomen pain",
    "abdominal discomfort", "pain in my stomach", "pain in stomach", "my stomach is pain",
    "my stomach is so pain", "stomach is pain", "stomach is so pain", "stomach is painful",
    "stomach very pain", "stomach very painful", "stomach hurts so bad", "tummy hurts",
    "tummy hurts so bad", "belly hurts", "belly hurts so bad", "abdomen hurts", "gastric pain",
    "cramp in stomach", "stomach cramp", "stomach cramps", "abdominal cramp", "abdominal cramps",
    "sakit perut", "perut sakit", "perut pedih", "perut memulas", "sakit bahagian perut",
    "肚子痛", "肚子疼", "胃痛", "腹痛", "腹部不适",
    "வயிற்று வலி", "வயிறு வலிக்கிறது", "வயிற்றில் வலி", "அடிவயிற்று வலி"
).any { it in q }

private fun isHeadacheQuestion(q: String): Boolean = listOf(
    "headache", "head hurts", "head pain", "migraine", "sakit kepala", "pening kepala",
    "头痛", "头疼", "தலைவலி", "தலை வலி"
).any { it in q }

private fun isDiarrheaQuestion(q: String): Boolean = listOf(
    "diarrhea", "diarrhoea", "watery stool", "loose stool", "loose stools", "cirit", "cirit-birit",
    "拉肚子", "腹泻", "水样便", "வயிற்றுப்போக்கு", "தண்ணீர் மலம்"
).any { it in q }

private fun isConstipationQuestion(q: String): Boolean = listOf(
    "constipation", "constipated", "cannot poop", "can't poop", "hard stool", "sembelit", "susah berak",
    "便秘", "大便很硬", "மலச்சிக்கல்", "மலம் கழிக்க முடியவில்லை"
).any { it in q }

private fun isFeverQuestion(q: String): Boolean = listOf(
    "fever", "feverish", "high temperature", "chills", "demam", "menggigil", "发烧", "发热", "发冷",
    "காய்ச்சல்", "குளிர்ச்சல்"
).any { it in q }

private fun isChestDiscomfortQuestion(q: String): Boolean = listOf(
    "chest pain", "chest hurts", "chest hurt", "chest pressure", "pressure in chest",
    "chest tightness", "tight chest", "chest feels tight", "sakit dada", "dada sakit", "dada ketat",
    "胸痛", "胸口痛", "胸闷", "胸部压迫感", "நெஞ்சுவலி", "நெஞ்சு வலி", "நெஞ்சு இறுக்கம்"
).any { it in q }

private fun isBreathlessnessQuestion(q: String): Boolean = listOf(
    "breathless", "breathlessness", "short of breath", "shortness of breath", "hard to breathe",
    "difficulty breathing", "can't catch my breath", "cannot catch my breath", "can't catch breath",
    "sesak nafas", "susah bernafas", "nafas pendek", "nafas tak cukup", "semput",
    "呼吸困难", "喘不过气", "气喘",
    "மூச்சுத்திணறல்", "மூச்சு விட சிரமம்"
).any { it in q }

private fun isDizzinessQuestion(q: String): Boolean = listOf(
    "dizzy", "dizziness", "lightheaded", "light headed", "giddy", "spinning", "feel faint",
    "going to pass out", "about to pass out", "pening", "pening kepala", "rasa nak pengsan",
    "头晕", "眩晕", "快要晕倒", "மயக்கம்", "தலைசுற்றல்"
).any { it in q }

private fun isSwellingQuestion(q: String): Boolean = listOf(
    "swelling", "swollen", "puffy", "ankle swelling", "leg swelling", "feet swelling", "bengkak",
    "kaki bengkak", "肿", "水肿", "脚肿", "வீக்கம்", "கால் வீக்கம்"
).any { it in q }

private fun isCoughQuestion(q: String): Boolean = listOf(
    "cough", "coughing", "persistent cough", "dry cough", "batuk", "batuk berterusan", "咳嗽", "一直咳",
    "இருமல்", "தொடர்ந்த இருமல்"
).any { it in q }

private fun isPalpitationQuestion(q: String): Boolean = listOf(
    "palpitation", "palpitations", "heart racing", "heart pounding", "heart beating fast", "fluttering",
    "berdebar", "jantung laju", "jantung berdegup laju", "心悸", "心跳很快", "இதயத் துடிப்பு", "இதயம் வேகமாக துடிக்கிறது"
).any { it in q }

private fun isFatigueQuestion(q: String): Boolean = listOf(
    "tired", "very tired", "fatigue", "fatigued", "weak", "weakness", "no energy", "exhausted",
    "penat", "sangat penat", "lemah", "tiada tenaga", "疲倦", "很累", "无力", "没力气",
    "சோர்வு", "மிகவும் சோர்வு", "பலவீனம்"
).any { it in q }

private fun isPoorAppetiteQuestion(q: String): Boolean = listOf(
    "no appetite", "poor appetite", "not hungry", "don't feel like eating", "dont feel like eating",
    "tak lalu makan", "tiada selera", "tak ada selera", "食欲不振", "没胃口", "பசி இல்லை", "சாப்பிட மனமில்லை"
).any { it in q }

private fun isGeneralPainQuestion(q: String): Boolean = listOf(
    "pain", "painful", "ache", "aching", "hurts", "hurt", "sore", "cramp", "sakit", "pedih",
    "疼", "痛", "酸痛", "வலி", "வலிக்கிறது"
).any { it in q }

private fun hasRecognizedLocalSymptom(q: String): Boolean =
    isNauseaOrVomitingQuestion(q) || isAbdominalPainQuestion(q) || isHeadacheQuestion(q) ||
        isChestDiscomfortQuestion(q) || isDiarrheaQuestion(q) || isConstipationQuestion(q) || isFeverQuestion(q) ||
        isBreathlessnessQuestion(q) || isDizzinessQuestion(q) || isSwellingQuestion(q) ||
        isCoughQuestion(q) || isPalpitationQuestion(q) || isFatigueQuestion(q) ||
        isPoorAppetiteQuestion(q) || isGeneralPainQuestion(q)

private fun assistantAskedPainScore(text: String): Boolean {
    val q = normalizeAiQuestion(text)
    return listOf(
        "0-10", "0–10", "out of 10", "how strong is it", "how strong is the pain",
        "pain score", "rate the pain", "tahap sakit", "berapa kuat sakit",
        "0到10", "0 到 10", "疼痛评分", "有多痛",
        "0–10 இல்", "0-10 இல்", "எவ்வளவு வலி"
    ).any { it in q }
}

private fun parsePainScore(text: String): Int? {
    val q = normalizeAiQuestion(text).trim()

    // A signed value such as "+1" or "-1" is ambiguous. It may mean the
    // symptom changed by one point rather than "my pain is 1/10".
    if (Regex("^[+-]\\s*\\d{1,2}$").matches(q)) return null

    // Do not accidentally treat counts, durations or measurements as pain scores.
    if (Regex("\\b(time|times|x|kali|次|回|முறை|hour|hours|day|days|minute|minutes|kg|bpm|mmhg|ml)\\b").containsMatchIn(q)) {
        return null
    }

    val match = Regex(
        "^(?:(?:pain|pain score|score|rating)(?:\\s+is|\\s*=|\\s*:)?\\s*)?(10|[0-9])(?:\\s*(?:/\\s*10|out of 10))?$"
    ).matchEntire(q) ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 10)
}

private fun ambiguousSignedPainReply(
    question: String,
    previousAssistant: String,
    mode: AiReplyMode
): String? {
    val q = normalizeAiQuestion(question).trim()
    if (!Regex("^[+-]\\s*\\d{1,2}$").matches(q)) return null
    if (!assistantAskedPainScore(previousAssistant)) return null

    val value = q.replace(" ", "")
    return replyText(mode,
        "Just to make sure I understand: does $value mean your pain changed by that amount, or do you mean your current pain is ${value.removePrefix("+").removePrefix("-")}/10? Please reply with something like ‘pain is 1/10’ or ‘pain increased by 1’.",
        "Saya nak pastikan maksud anda: adakah $value bermaksud tahap sakit berubah sebanyak itu, atau sakit anda sekarang ${value.removePrefix("+").removePrefix("-")}/10? Balas contohnya ‘sakit 1/10’ atau ‘sakit naik 1’.",
        "我想确认一下：$value 是表示疼痛增加/减少了这个数值，还是您现在的疼痛是 ${value.removePrefix("+").removePrefix("-")}/10？请回复例如“疼痛 1/10”或“疼痛增加 1 分”。",
        "$value என்பது வலி அந்த அளவு அதிகரித்தது/குறைந்தது என்பதா, அல்லது தற்போதைய வலி ${value.removePrefix("+").removePrefix("-")}/10 என்பதா என்பதை உறுதிப்படுத்த விரும்புகிறேன். ‘வலி 1/10’ அல்லது ‘வலி 1 அதிகரித்தது’ என்று பதிலளிக்கவும்."
    )
}

private fun abdominalPainFollowUpReply(
    question: String,
    history: List<ChatMessage>,
    mode: AiReplyMode
): String? {
    val previousAssistant = history.asReversed()
        .firstOrNull { it.role.equals("assistant", ignoreCase = true) && it.content.isNotBlank() }
        ?.content ?: return null
    val priorUserText = conversationText(history)
    val hasAbdominalContext = isAbdominalPainQuestion(priorUserText) ||
        isAbdominalPainQuestion(normalizeAiQuestion(previousAssistant))
    if (!hasAbdominalContext) return null

    val q = normalizeAiQuestion(question)

    // "+1"/"-1" is not a pain score. Ask what the patient means instead of
    // confidently converting it to 1/10.
    ambiguousSignedPainReply(question, previousAssistant, mode)?.let { return it }

    val score = parsePainScore(q)
    val scoreQuestionWasAsked = assistantAskedPainScore(previousAssistant)
    val scoreWasExplicit = Regex("^(?:pain|pain score|score|rating)\\b").containsMatchIn(q) ||
        Regex("^(?:10|[0-9])\\s*(?:/\\s*10|out of 10)$").matches(q)
    if (score != null && q.length <= 28 && (scoreQuestionWasAsked || scoreWasExplicit)) {
        return when {
            score >= 8 -> replyText(mode,
                "Thanks—that is severe pain at $score/10. Please do not ignore it. If it is sudden or worsening, your abdomen is hard or very swollen, you keep vomiting, have blood or black stool, faint, develop chest pain or severe breathlessness, seek urgent medical help now. If none of those are present, contact your healthcare team promptly today. Where exactly is the pain—upper, lower, left, right or middle?",
                "Terima kasih—itu sakit yang kuat, $score/10. Jangan abaikannya. Jika sakit tiba-tiba atau semakin teruk, perut keras atau sangat bengkak, muntah berulang, najis berdarah/hitam, pengsan, sakit dada atau sesak nafas teruk, dapatkan bantuan perubatan segera. Jika tiada tanda tersebut, hubungi pasukan kesihatan dengan segera hari ini. Sakit di bahagian mana—atas, bawah, kiri, kanan atau tengah?",
                "谢谢说明——$score/10 属于较严重的疼痛，请不要忽视。如果疼痛突然或加重、腹部变硬或明显胀大、反复呕吐、出现血便/黑便、晕厥、胸痛或严重呼吸困难，请立即就医。若没有这些危险信号，也建议今天尽快联系医疗团队。疼痛具体在上腹、下腹、左侧、右侧还是中间？",
                "நன்றி—$score/10 என்பது கடுமையான வலி. இதை புறக்கணிக்க வேண்டாம். திடீர் அல்லது மோசமடையும் வலி, வயிறு கடினமாக அல்லது மிகவும் வீங்கியிருத்தல், தொடர்ந்து வாந்தி, இரத்த/கருப்பு மலம், மயக்கம், நெஞ்சுவலி அல்லது கடுமையான மூச்சுத்திணறல் இருந்தால் உடனடி மருத்துவ உதவி பெறவும். இவை இல்லாவிட்டாலும் இன்று விரைவாக மருத்துவக் குழுவை தொடர்புகொள்ளவும். வலி மேல், கீழ், இடது, வலம் அல்லது நடுப்பகுதியில் எங்கு உள்ளது?"
            )
            score >= 4 -> replyText(mode,
                "Thanks—you rate the stomach pain $score/10. Rest comfortably and avoid a large, oily or spicy meal for now. Because you are managing heart failure, avoid taking extra painkillers unless they are already approved for you. Where exactly is the pain, and did it start today or earlier?",
                "Terima kasih—anda menilai sakit perut $score/10. Berehat dalam posisi selesa dan elakkan makanan banyak, berminyak atau pedas buat sementara. Oleh sebab anda mengurus kegagalan jantung, elakkan ubat tahan sakit tambahan melainkan sudah diluluskan. Sakit di bahagian mana, dan ia bermula hari ini atau lebih awal?",
                "谢谢——您把腹痛评为 $score/10。先舒服地休息，并暂时避免大量、油腻或辛辣食物。由于您正在管理心衰，不要自行加用止痛药，除非医护人员已确认可以使用。疼痛具体在哪里？是今天开始还是更早？",
                "நன்றி—வயிற்று வலியை $score/10 என கூறுகிறீர்கள். வசதியாக ஓய்வெடுத்து, இப்போது அதிகமான/எண்ணெய்/கார உணவைத் தவிர்க்கவும். இதய செயலிழப்பு பராமரிப்பில் இருப்பதால் முன்பே அனுமதிக்கப்படாத கூடுதல் வலி மருந்தை எடுத்துக்கொள்ள வேண்டாம். வலி எங்கு உள்ளது, இன்று தொடங்கியதா அல்லது முன்பே தொடங்கியதா?"
            )
            else -> replyText(mode,
                "Thanks—you rate it $score/10. That sounds mild right now, but keep watching it. Rest, avoid a heavy meal, and tell me where the pain is and when it started. If it suddenly becomes severe or you develop repeated vomiting, black/bloody stool, fainting, chest pain or severe breathlessness, seek urgent help.",
                "Terima kasih—anda menilainya $score/10. Buat masa ini ia kedengaran ringan, tetapi terus pantau. Berehat, elakkan makanan berat dan beritahu sakit di bahagian mana serta bila ia bermula. Jika tiba-tiba menjadi teruk atau ada muntah berulang, najis hitam/berdarah, pengsan, sakit dada atau sesak nafas teruk, dapatkan bantuan segera.",
                "谢谢——目前疼痛评分为 $score/10，听起来较轻，但仍需观察。请休息、避免大餐，并告诉我疼痛位置以及什么时候开始。如果突然变得剧烈，或出现反复呕吐、黑便/血便、晕厥、胸痛或严重呼吸困难，请立即就医。",
                "நன்றி—இப்போது வலி $score/10; இது லேசாகத் தோன்றினாலும் தொடர்ந்து கவனிக்கவும். ஓய்வெடுத்து, கனமான உணவைத் தவிர்த்து, வலி எங்கு உள்ளது மற்றும் எப்போது தொடங்கியது என்று சொல்லவும். திடீரென கடுமையாகினால் அல்லது தொடர்ந்து வாந்தி, கருப்பு/இரத்த மலம், மயக்கம், நெஞ்சுவலி அல்லது கடுமையான மூச்சுத்திணறல் ஏற்பட்டால் உடனடி உதவி பெறவும்."
            )
        }
    }

    val locationWords = listOf(
        "upper stomach", "upper abdomen", "lower stomach", "lower abdomen", "left side", "right side",
        "middle", "center", "centre", "upper", "lower", "left", "right",
        "bahagian atas", "bahagian bawah", "sebelah kiri", "sebelah kanan", "tengah",
        "上腹", "下腹", "左边", "右边", "中间",
        "மேல் வயிறு", "கீழ் வயிறு", "இடது", "வலது", "நடு"
    )
    val location = locationWords.firstOrNull { it in q }
    if (location != null && q.length <= 60) {
        return replyText(mode,
            "Thanks—that helps. You’re describing pain around the $location area. How strong is it from 0–10, and when did it begin? If it is severe, suddenly worsening, or comes with repeated vomiting, a hard/swollen abdomen, blood/black stool, fainting, chest pain or severe breathlessness, seek urgent medical help.",
            "Terima kasih, itu membantu. Anda menggambarkan sakit di kawasan $location. Berapa kuat sakit itu dari 0–10, dan bila ia bermula? Jika sakit teruk, tiba-tiba semakin kuat, atau bersama muntah berulang, perut keras/bengkak, najis berdarah/hitam, pengsan, sakit dada atau sesak nafas teruk, dapatkan bantuan perubatan segera.",
            "谢谢，这很有帮助。您描述的是 $location 附近的疼痛。疼痛 0–10 分有多强？什么时候开始？如果疼痛严重或突然加重，或伴反复呕吐、腹部胀硬、血便/黑便、晕厥、胸痛或严重呼吸困难，请立即就医。",
            "நன்றி, இது உதவுகிறது. $location பகுதியில் வலி இருப்பதாக கூறுகிறீர்கள். 0–10 இல் எவ்வளவு வலி, எப்போது தொடங்கியது? கடுமையான/திடீரென மோசமடையும் வலி, தொடர்ந்து வாந்தி, கடினமான/வீங்கிய வயிறு, இரத்த/கருப்பு மலம், மயக்கம், நெஞ்சுவலி அல்லது கடுமையான மூச்சுத்திணறல் இருந்தால் உடனடி மருத்துவ உதவி பெறவும்."
        )
    }
    return null
}

private fun focusedSymptomFallbackReply(q: String, mode: AiReplyMode): String? {
    return when {
        isAbdominalPainQuestion(q) -> replyText(mode,
            "I’m sorry—stomach pain can be really uncomfortable. For now, rest in a comfortable position and avoid a large, oily or spicy meal. Because you have heart-failure care needs, don’t take extra painkillers or stomach medicines unless they are already approved for you. Please tell me where the pain is (upper/lower/left/right/middle) and how strong it is from 0–10. If the pain is severe or sudden, your abdomen becomes very swollen/hard, you keep vomiting, pass blood/black stool, faint, or develop chest pain or severe breathlessness, get urgent medical help.",
            "Saya faham—sakit perut memang sangat tidak selesa. Buat masa ini, berehat dalam posisi yang selesa dan elakkan makanan banyak, berminyak atau pedas. Oleh sebab anda mempunyai penjagaan kegagalan jantung, jangan ambil ubat tahan sakit atau ubat perut tambahan melainkan ia memang telah diluluskan untuk anda. Boleh beritahu sakit di bahagian mana (atas/bawah/kiri/kanan/tengah) dan tahap sakit 0–10? Jika sakit sangat kuat atau tiba-tiba, perut menjadi sangat bengkak/keras, muntah berulang, najis berdarah/hitam, pengsan, sakit dada atau sesak nafas teruk, dapatkan bantuan perubatan segera.",
            "听起来胃/腹部疼痛让您很不舒服。先用舒服的姿势休息，暂时避免大量、油腻或辛辣食物。由于您正在进行心衰管理，不要自行加用止痛药或胃药，除非医护人员已确认适合您。请告诉我具体哪里痛（上/下/左/右/中间），以及 0–10 分有多痛？如果疼痛突然或非常严重、腹部明显胀硬、反复呕吐、出现血便/黑便、晕厥、胸痛或严重呼吸困难，请立即就医。",
            "வயிற்று வலி மிகவும் அசௌகரியமாக இருக்கலாம். இப்போது வசதியான நிலையில் ஓய்வெடுத்து, அதிகமான, எண்ணெய் அல்லது காரமான உணவைத் தவிர்க்கவும். இதய செயலிழப்பு பராமரிப்பு இருப்பதால், மருத்துவர் ஏற்கனவே அனுமதிக்காத கூடுதல் வலி மருந்து அல்லது வயிற்று மருந்தை தானாக எடுத்துக்கொள்ள வேண்டாம். வலி எங்கு உள்ளது (மேல்/கீழ்/இடது/வலது/நடு), 0–10 இல் எவ்வளவு கடுமை என்று சொல்ல முடியுமா? வலி திடீரென அல்லது மிகவும் கடுமையாக இருந்தால், வயிறு மிகவும் வீங்கி/கடினமாக இருந்தால், தொடர்ந்து வாந்தி, இரத்த/கருப்பு மலம், மயக்கம், நெஞ்சுவலி அல்லது கடுமையான மூச்சுத்திணறல் இருந்தால் உடனடி மருத்துவ உதவி பெறவும்."
        )
        isChestDiscomfortQuestion(q) -> replyText(mode,
            "Chest pain, pressure or tightness needs careful attention. Stop what you’re doing and sit or rest. If it is severe, new, lasts more than a few minutes, keeps returning, or comes with severe breathlessness, sweating, nausea, fainting, or pain spreading to the arm/jaw/back, call Malaysia emergency services at 999. If it is mild but new or unusual, contact your healthcare team promptly. When did it start, and is it pain, pressure, burning or tightness?",
            "Sakit, tekanan atau rasa ketat di dada perlu diberi perhatian. Hentikan aktiviti dan duduk atau berehat. Jika sakit teruk, baharu, berlarutan lebih beberapa minit, kerap berulang, atau bersama sesak nafas teruk, berpeluh, loya, pengsan, atau sakit merebak ke lengan/rahang/belakang, hubungi 999. Jika ringan tetapi baharu atau luar biasa, hubungi pasukan kesihatan dengan segera. Bila ia bermula, dan rasanya sakit, tekanan, pedih atau ketat?",
            "胸痛、胸部压迫感或胸闷需要认真对待。请停止活动并坐下休息。如果症状严重、新出现、持续数分钟以上、反复出现，或伴严重呼吸困难、出汗、恶心、晕厥，或疼痛放射到手臂/下巴/背部，请拨打马来西亚 999。若症状较轻但新出现或异常，也应尽快联系医疗团队。什么时候开始的？感觉是疼痛、压迫、灼热还是发紧？",
            "நெஞ்சுவலி, அழுத்தம் அல்லது இறுக்கம் கவனிக்க வேண்டியது. செயலை நிறுத்தி அமர்ந்து ஓய்வெடுக்கவும். இது கடுமையாகவோ புதிதாகவோ, சில நிமிடங்களுக்கு மேலாக நீடித்தாலோ, மீண்டும் மீண்டும் வந்தாலோ, கடுமையான மூச்சுத்திணறல், வியர்வை, குமட்டல், மயக்கம் அல்லது கை/தாடை/முதுகிற்கு பரவும் வலி இருந்தாலோ மலேசிய 999-ஐ அழைக்கவும். லேசானதாக இருந்தாலும் புதிதாக அல்லது வழக்கமற்றதாக இருந்தால் மருத்துவக் குழுவை விரைவாக தொடர்புகொள்ளவும். எப்போது தொடங்கியது, வலி/அழுத்தம்/எரிச்சல்/இறுக்கம் எது போல உள்ளது?"
        )
        isBreathlessnessQuestion(q) -> replyText(mode,
            "I’m sorry you’re feeling short of breath. Sit upright, stop strenuous activity, loosen tight clothing and rest. If you have a pulse oximeter, recheck SpO₂ while your hand is warm and still. Is the breathlessness new or worse than usual, and does it happen at rest or when lying flat? If it is severe/sudden, you cannot speak comfortably, you faint, have chest pain, confusion or blue lips, call 999 now.",
            "Saya minta maaf anda sesak nafas. Duduk tegak, hentikan aktiviti berat, longgarkan pakaian ketat dan berehat. Jika ada pulse oximeter, periksa semula SpO₂ dengan tangan yang hangat dan tidak bergerak. Adakah sesak nafas ini baharu atau lebih teruk daripada biasa, dan berlaku ketika rehat atau baring? Jika teruk/tiba-tiba, tidak boleh bercakap dengan selesa, pengsan, sakit dada, keliru atau bibir kebiruan, hubungi 999 sekarang.",
            "很抱歉您感到呼吸困难。请坐直、停止剧烈活动、放松紧身衣物并休息。如果有血氧仪，可在手指温暖且静止时重新测量 SpO₂。呼吸困难是新出现的还是比平时更严重？在休息或平躺时也会发生吗？若突然或严重到说话困难，或伴晕厥、胸痛、意识混乱、嘴唇发蓝，请立即拨打 999。",
            "மூச்சுத்திணறல் இருப்பது வருத்தமாக உள்ளது. நேராக அமர்ந்து, கடுமையான செயல்பாட்டை நிறுத்தி, இறுக்கமான உடையை தளர்த்தி ஓய்வெடுக்கவும். pulse oximeter இருந்தால் கை சூடாகவும் அசையாமலும் வைத்து SpO₂-ஐ மீண்டும் அளவிடவும். இது புதிதா அல்லது வழக்கத்தை விட மோசமா, ஓய்வில் அல்லது படுக்கும் போது வருகிறதா? திடீர்/கடுமையான மூச்சுத்திணறல், பேச முடியாமை, மயக்கம், நெஞ்சுவலி, குழப்பம் அல்லது நீல உதடுகள் இருந்தால் 999-ஐ உடனே அழைக்கவும்."
        )
        isDizzinessQuestion(q) -> replyText(mode,
            "That sounds uncomfortable. Sit or lie down somewhere safe so you do not fall, and stand up slowly when it passes. If possible, check your blood pressure and pulse after resting. Did the dizziness start suddenly, and are you also having fainting, chest pain, severe breathlessness, weakness on one side, or confusion? Those warning signs need urgent help.",
            "Itu memang tidak selesa. Duduk atau baring di tempat yang selamat supaya tidak jatuh, dan bangun perlahan-lahan selepas rasa pening reda. Jika boleh, periksa tekanan darah dan nadi selepas berehat. Adakah pening bermula secara tiba-tiba, dan adakah anda juga pengsan, sakit dada, sesak nafas teruk, lemah sebelah badan atau keliru? Tanda ini memerlukan bantuan segera.",
            "头晕时请先坐下或躺在安全的地方，避免跌倒；缓解后再慢慢站起。如果可以，休息后测量血压和脉搏。头晕是突然开始的吗？是否同时有晕厥、胸痛、严重呼吸困难、单侧无力或意识混乱？这些警告症状需要及时就医。",
            "மயக்கம் இருந்தால் விழாமல் பாதுகாப்பான இடத்தில் அமரவும் அல்லது படுக்கவும்; குறைந்த பிறகு மெதுவாக எழுந்திருக்கவும். முடிந்தால் ஓய்வுக்குப் பிறகு இரத்த அழுத்தம் மற்றும் நாடியை அளவிடவும். மயக்கம் திடீரென தொடங்கியதா? மயக்கம் விழுதல், நெஞ்சுவலி, கடுமையான மூச்சுத்திணறல், உடலின் ஒரு பக்கம் பலவீனம் அல்லது குழப்பம் உள்ளதா? இவை உடனடி கவனம் தேவைப்படும் அறிகுறிகள்."
        )
        isSwellingQuestion(q) -> replyText(mode,
            "New or worsening swelling can matter in heart failure. Rest with your legs supported if comfortable, record today’s weight, and notice whether shoes or socks feel tighter than usual. Is the swelling in one leg or both, and has your weight risen over the last few days? Contact your healthcare team if it is new/worsening, especially with breathlessness or rapid weight gain.",
            "Bengkak baharu atau semakin teruk penting dalam kegagalan jantung. Rehat dengan kaki disokong jika selesa, rekod berat hari ini dan perhatikan sama ada kasut atau stoking terasa lebih ketat. Bengkak berlaku pada satu kaki atau kedua-duanya, dan adakah berat meningkat beberapa hari ini? Hubungi pasukan kesihatan jika bengkak baharu/bertambah, terutama bersama sesak nafas atau kenaikan berat cepat.",
            "对于心衰患者，新出现或加重的水肿值得关注。若舒服可抬高双腿休息，记录今天的体重，并留意鞋袜是否比平时更紧。是一只脚还是两只脚肿？近几天体重有上升吗？若水肿新出现或加重，尤其伴呼吸困难或体重快速增加，请联系医疗团队。",
            "இதய செயலிழப்பில் புதிய அல்லது மோசமடையும் வீக்கம் கவனிக்க வேண்டியது. வசதியாக இருந்தால் கால்களை ஆதரித்து ஓய்வெடுத்து, இன்றைய எடையை பதிவு செய்து, காலணி/சாக்ஸ் வழக்கத்தை விட இறுக்கமாக உள்ளதா பார்க்கவும். ஒரு காலிலா இரு கால்களிலா வீக்கம்? கடந்த சில நாட்களில் எடை உயர்ந்ததா? வீக்கம் புதிதாகவோ மோசமாகவோ இருந்தால், குறிப்பாக மூச்சுத்திணறல் அல்லது வேகமான எடை உயர்வுடன் இருந்தால் மருத்துவக் குழுவை தொடர்புகொள்ளவும்."
        )
        isCoughQuestion(q) -> replyText(mode,
            "A new or worsening cough is worth watching, especially if it is worse at night or when lying flat. Sit upright, rest, and notice whether you also have breathlessness, swelling, fever, or pink/frothy mucus. How long have you been coughing, and is it dry or producing mucus? Severe breathlessness or pink/frothy sputum needs urgent help.",
            "Batuk baharu atau semakin teruk perlu dipantau, terutama jika lebih teruk pada waktu malam atau ketika baring. Duduk tegak, berehat dan perhatikan sama ada ada sesak nafas, bengkak, demam atau kahak merah jambu/berbuih. Sudah berapa lama batuk, dan batuk kering atau berkahak? Sesak nafas teruk atau kahak merah jambu/berbuih memerlukan bantuan segera.",
            "新出现或加重的咳嗽需要留意，尤其是夜间或平躺时更明显。请坐直休息，并观察是否伴呼吸困难、水肿、发热或粉红色泡沫痰。咳嗽多久了？是干咳还是有痰？严重呼吸困难或粉红色泡沫痰需要紧急帮助。",
            "புதிய அல்லது மோசமடையும் இருமலை கவனிக்க வேண்டும், குறிப்பாக இரவில் அல்லது படுக்கும் போது அதிகமானால். நேராக அமர்ந்து ஓய்வெடுத்து, மூச்சுத்திணறல், வீக்கம், காய்ச்சல் அல்லது இளஞ்சிவப்பு நுரையுள்ள சளி உள்ளதா பார்க்கவும். எத்தனை நாட்களாக இருமல்? உலர் இருமலா அல்லது சளியுடன் உள்ளதா? கடுமையான மூச்சுத்திணறல் அல்லது இளஞ்சிவப்பு நுரையுள்ள சளி இருந்தால் அவசர உதவி தேவை."
        )
        isPalpitationQuestion(q) -> replyText(mode,
            "A racing or pounding heartbeat can feel frightening. Stop activity, sit down and rest, and check your pulse/heart rate if you can. When did it start, and is it regular or irregular? If it continues with chest pain, fainting, severe breathlessness or marked weakness, seek urgent medical help.",
            "Jantung berdebar atau berdegup laju boleh menakutkan. Hentikan aktiviti, duduk dan berehat, serta periksa nadi/denyutan jantung jika boleh. Bila ia bermula, dan degupan terasa teratur atau tidak teratur? Jika berterusan bersama sakit dada, pengsan, sesak nafas teruk atau sangat lemah, dapatkan bantuan perubatan segera.",
            "心跳很快或很重会让人害怕。请停止活动、坐下休息，并在可以时测量脉搏/心率。什么时候开始的？节律感觉规则还是不规则？若持续并伴胸痛、晕厥、严重呼吸困难或明显虚弱，请尽快就医。",
            "இதயம் வேகமாக அல்லது பலமாக துடிப்பது பயமளிக்கலாம். செயல்பாட்டை நிறுத்தி அமர்ந்து ஓய்வெடுத்து, முடிந்தால் நாடி/இதயத் துடிப்பை அளவிடவும். எப்போது தொடங்கியது? துடிப்பு ஒழுங்காக உள்ளதா அல்லது ஒழுங்கற்றதா? நெஞ்சுவலி, மயக்கம், கடுமையான மூச்சுத்திணறல் அல்லது அதிக பலவீனத்துடன் தொடர்ந்தால் அவசர மருத்துவ உதவி பெறவும்."
        )
        isFatigueQuestion(q) -> replyText(mode,
            "Feeling unusually tired or weak is worth paying attention to. Reduce activity for now, take breaks, and check whether you are also more breathless, dizzy, swollen, eating poorly, or sleeping badly. Is this tiredness new, and is it stopping you from doing your usual activities? If it is suddenly severe or comes with chest pain, fainting or severe breathlessness, seek urgent help.",
            "Rasa sangat penat atau lemah perlu diberi perhatian. Kurangkan aktiviti buat sementara, berehat dengan kerap dan perhatikan sama ada anda lebih sesak nafas, pening, bengkak, kurang makan atau kurang tidur. Adakah keletihan ini baharu, dan adakah ia menghalang aktiviti biasa? Jika tiba-tiba sangat teruk atau bersama sakit dada, pengsan atau sesak nafas teruk, dapatkan bantuan segera.",
            "异常疲倦或无力值得关注。先减少活动、分段休息，并留意是否同时更喘、头晕、水肿、食欲差或睡眠不好。这种疲倦是新出现的吗？是否影响平时活动？若突然非常严重或伴胸痛、晕厥、严重呼吸困难，请及时就医。",
            "வழக்கத்தை விட அதிக சோர்வு அல்லது பலவீனம் கவனிக்க வேண்டியது. இப்போது செயல்பாட்டை குறைத்து இடைவெளி எடுத்துக் கொள்ளவும்; அதிக மூச்சுத்திணறல், மயக்கம், வீக்கம், குறைந்த உணவு அல்லது மோசமான தூக்கம் உள்ளதா பார்க்கவும். இந்த சோர்வு புதிதா, வழக்கமான செயல்களை செய்ய முடியாமல் செய்கிறதா? திடீரென மிகவும் கடுமையாகவோ நெஞ்சுவலி, மயக்கம் அல்லது கடுமையான மூச்சுத்திணறலுடன் இருந்தாலோ உடனடி உதவி பெறவும்."
        )
        isHeadacheQuestion(q) -> replyText(mode,
            "I’m sorry your head hurts. Rest somewhere quiet, avoid strenuous activity, and if you can, check your blood pressure after sitting calmly for about 5 minutes. How strong is the headache from 0–10, and is it sudden or different from your usual headaches? A sudden severe headache with weakness on one side, confusion, fainting or vision/speech changes needs urgent medical help.",
            "Saya minta maaf kepala anda sakit. Berehat di tempat yang tenang, elakkan aktiviti berat dan jika boleh, periksa tekanan darah selepas duduk tenang kira-kira 5 minit. Tahap sakit 0–10 berapa, dan adakah ia tiba-tiba atau berbeza daripada sakit kepala biasa? Sakit kepala kuat secara tiba-tiba bersama lemah sebelah badan, keliru, pengsan atau perubahan penglihatan/pertuturan memerlukan bantuan segera.",
            "很抱歉您头痛。请在安静处休息、避免剧烈活动；如果可以，安静坐约 5 分钟后测量血压。头痛 0–10 分有多严重？是突然出现还是和平时不同？若突然剧烈头痛并伴单侧无力、意识混乱、晕厥或视力/说话改变，请立即就医。",
            "தலைவலி இருப்பது வருத்தமாக உள்ளது. அமைதியான இடத்தில் ஓய்வெடுத்து கடுமையான செயல்பாட்டை தவிர்க்கவும்; முடிந்தால் சுமார் 5 நிமிடம் அமைதியாக அமர்ந்தபின் இரத்த அழுத்தத்தை அளவிடவும். 0–10 இல் வலி எவ்வளவு? திடீரென வந்ததா அல்லது வழக்கமான தலைவலியிலிருந்து வேறுபடுகிறதா? திடீர் கடுமையான தலைவலியுடன் ஒரு பக்கம் பலவீனம், குழப்பம், மயக்கம் அல்லது பார்வை/பேச்சு மாற்றம் இருந்தால் உடனடி மருத்துவ உதவி பெறவும்."
        )
        isDiarrheaQuestion(q) -> replyText(mode,
            "Diarrhea can affect fluids and medicines. Rest, monitor how often it happens, and only replace fluids within the fluid plan your healthcare team has given you—don’t deliberately exceed a heart-failure fluid limit. Have you had repeated watery stools, dizziness, very low urine, fever, blood/black stool, or trouble keeping medicines down? If yes or it keeps happening, contact your healthcare team promptly.",
            "Cirit-birit boleh menjejaskan cecair dan ubat. Berehat, pantau berapa kerap ia berlaku dan gantikan cecair hanya dalam pelan cecair yang diberi oleh pasukan kesihatan—jangan sengaja melebihi had cecair kegagalan jantung. Adakah najis cair berulang, pening, air kencing sangat kurang, demam, darah/najis hitam atau sukar mengekalkan ubat? Jika ya atau berterusan, hubungi pasukan kesihatan dengan segera.",
            "腹泻可能影响液体平衡和药物。请休息并记录次数；补充液体时只能遵循医护团队给您的饮水计划，不要为了补水而自行超过心衰液体限制。是否反复水样便、头晕、尿量很少、发热、血便/黑便，或药物无法留住？若有这些情况或持续腹泻，请尽快联系医疗团队。",
            "வயிற்றுப்போக்கு திரவ சமநிலையையும் மருந்துகளையும் பாதிக்கலாம். ஓய்வெடுத்து எத்தனை முறை வருகிறது என்று கண்காணிக்கவும்; மருத்துவக் குழு கொடுத்த திரவ திட்டத்திற்குள் மட்டுமே திரவத்தை எடுத்துக்கொள்ளவும்—இதய செயலிழப்பு திரவ வரம்பை தானாக மீற வேண்டாம். மீண்டும் மீண்டும் தண்ணீர் மலம், மயக்கம், சிறுநீர் மிகவும் குறைவு, காய்ச்சல், இரத்த/கருப்பு மலம் அல்லது மருந்து தங்காமை உள்ளதா? இருந்தாலோ தொடர்ந்து நடந்தாலோ மருத்துவக் குழுவை விரைவாக தொடர்புகொள்ளவும்."
        )
        isConstipationQuestion(q) -> replyText(mode,
            "Constipation is uncomfortable. Gentle movement if you feel well and fibre-containing foods may help, but keep fluids within your prescribed heart-failure fluid plan rather than drinking extra on your own. How many days has it been since your last bowel movement, and do you have severe abdominal pain, vomiting, a very swollen abdomen or blood? Those symptoms need prompt medical advice.",
            "Sembelit memang tidak selesa. Pergerakan ringan jika anda rasa sihat dan makanan berserat mungkin membantu, tetapi kekalkan cecair dalam pelan cecair kegagalan jantung yang ditetapkan dan jangan minum berlebihan sendiri. Sudah berapa hari sejak buang air besar terakhir, dan adakah ada sakit perut teruk, muntah, perut sangat bengkak atau darah? Simptom ini memerlukan nasihat perubatan dengan segera.",
            "便秘会很不舒服。如果身体允许，轻微活动和含纤维食物可能有帮助，但饮水仍应遵循医生为心衰制定的液体计划，不要自行大量增加。距离上次排便多久了？是否有严重腹痛、呕吐、腹部明显胀大或出血？这些情况需要尽快咨询医护人员。",
            "மலச்சிக்கல் அசௌகரியமாக இருக்கும். உடல் நலம் அனுமதித்தால் மெதுவான இயக்கமும் நார்ச்சத்து உணவும் உதவலாம்; ஆனால் இதய செயலிழப்புக்காக வழங்கப்பட்ட திரவ திட்டத்திற்குள் மட்டுமே குடிக்கவும், தானாக அதிகப்படுத்த வேண்டாம். கடைசியாக மலம் கழித்தது எத்தனை நாட்களுக்கு முன்? கடுமையான வயிற்றுவலி, வாந்தி, மிகவும் வீங்கிய வயிறு அல்லது இரத்தம் உள்ளதா? இவை விரைவான மருத்துவ ஆலோசனை தேவைப்படும் அறிகுறிகள்."
        )
        isFeverQuestion(q) -> replyText(mode,
            "A fever can make you feel weak and may signal an infection. Rest, check your temperature if possible, and keep taking fluids only within your prescribed plan. What temperature did you measure, and do you also have cough, breathlessness, urinary symptoms, vomiting or marked weakness? Contact your healthcare team if the fever persists or you feel significantly unwell; severe breathlessness, fainting or confusion needs urgent help.",
            "Demam boleh menyebabkan badan lemah dan mungkin menandakan jangkitan. Berehat, periksa suhu jika boleh dan ambil cecair hanya dalam pelan yang ditetapkan. Berapa suhu yang diukur, dan adakah anda juga batuk, sesak nafas, masalah kencing, muntah atau sangat lemah? Hubungi pasukan kesihatan jika demam berterusan atau anda sangat tidak sihat; sesak nafas teruk, pengsan atau keliru memerlukan bantuan segera.",
            "发热会让人虚弱，也可能提示感染。请休息，如可以请测量体温，并只在医生规定的液体计划内饮水。测到多少度？是否同时有咳嗽、呼吸困难、排尿不适、呕吐或明显无力？若发热持续或整体状态明显不好，请联系医疗团队；严重呼吸困难、晕厥或意识混乱需要紧急帮助。",
            "காய்ச்சல் பலவீனத்தை ஏற்படுத்தலாம் மற்றும் தொற்றின் அறிகுறியாக இருக்கலாம். ஓய்வெடுத்து, முடிந்தால் வெப்பநிலையை அளந்து, பரிந்துரைக்கப்பட்ட திரவ திட்டத்திற்குள் மட்டுமே குடிக்கவும். வெப்பநிலை எவ்வளவு? இருமல், மூச்சுத்திணறல், சிறுநீர் பிரச்சனை, வாந்தி அல்லது அதிக பலவீனம் உள்ளதா? காய்ச்சல் நீடித்தாலோ மிகவும் உடல்நலம் குன்றியதாக உணர்ந்தாலோ மருத்துவக் குழுவை தொடர்புகொள்ளவும்; கடுமையான மூச்சுத்திணறல், மயக்கம் அல்லது குழப்பம் இருந்தால் அவசர உதவி பெறவும்."
        )
        isPoorAppetiteQuestion(q) -> replyText(mode,
            "Poor appetite can make it harder to maintain strength and take medicines comfortably. Try smaller, lighter, lower-salt meals rather than forcing a large meal, while following your prescribed fluid plan. How long has your appetite been poor, and are you also nauseous, vomiting, losing weight quickly or feeling much weaker? If you cannot eat or keep medicines down, contact your healthcare team.",
            "Kurang selera boleh menyukarkan anda mengekalkan tenaga dan mengambil ubat dengan selesa. Cuba hidangan lebih kecil, ringan dan rendah garam daripada memaksa makan banyak, sambil mengikut pelan cecair yang ditetapkan. Sudah berapa lama selera berkurang, dan adakah anda juga loya, muntah, turun berat dengan cepat atau semakin lemah? Jika tidak boleh makan atau ubat tidak dapat kekal, hubungi pasukan kesihatan.",
            "食欲差会影响体力，也可能让服药更困难。可以尝试少量、清淡、低盐的餐食，不必勉强吃一大餐，同时遵循医生给您的液体计划。食欲差多久了？是否同时恶心、呕吐、体重快速下降或明显更虚弱？如果无法进食或药物留不住，请联系医疗团队。",
            "பசி குறைவு உடல் வலிமையையும் மருந்துகளை எடுத்துக்கொள்வதையும் பாதிக்கலாம். பெரிய உணவை வற்புறுத்தாமல் சிறிய, லேசான, குறைந்த உப்பு உணவை முயற்சிக்கவும்; பரிந்துரைக்கப்பட்ட திரவ திட்டத்தை பின்பற்றவும். எத்தனை நாட்களாக பசி குறைவு? குமட்டல், வாந்தி, வேகமான எடை குறைவு அல்லது அதிக பலவீனம் உள்ளதா? சாப்பிட முடியாவிட்டாலோ மருந்து தங்காவிட்டாலோ மருத்துவக் குழுவை தொடர்புகொள்ளவும்."
        )
        isGeneralPainQuestion(q) -> replyText(mode,
            "I’m sorry you’re in pain. Tell me where the pain is, how strong it is from 0–10, when it started, and whether it is constant or comes and goes. Avoid taking an extra pain medicine unless it is already approved for you, because some common painkillers may not be suitable with heart-failure or kidney medicines. Severe/sudden pain, chest pain, fainting, confusion or severe breathlessness needs urgent medical help.",
            "Saya minta maaf anda sedang sakit. Beritahu saya sakit di bahagian mana, tahap 0–10, bila ia bermula dan sama ada berterusan atau datang dan pergi. Elakkan mengambil ubat tahan sakit tambahan melainkan ia memang telah diluluskan untuk anda kerana sesetengah ubat tahan sakit biasa mungkin tidak sesuai bersama ubat kegagalan jantung atau buah pinggang. Sakit sangat kuat/tiba-tiba, sakit dada, pengsan, keliru atau sesak nafas teruk memerlukan bantuan segera.",
            "很抱歉您正在疼痛。请告诉我疼痛在哪里、0–10 分有多严重、什么时候开始，以及是持续还是间歇出现。不要自行加用止痛药，除非医护人员已确认适合您，因为一些常见止痛药可能不适合与心衰或肾脏相关药物一起使用。若疼痛突然或非常严重，或出现胸痛、晕厥、意识混乱、严重呼吸困难，请立即就医。",
            "வலி இருப்பது வருத்தமாக உள்ளது. வலி எங்கு உள்ளது, 0–10 இல் எவ்வளவு, எப்போது தொடங்கியது, தொடர்ந்து உள்ளதா அல்லது வந்து போகிறதா என்று சொல்லுங்கள். மருத்துவர் ஏற்கனவே அனுமதிக்காத கூடுதல் வலி மருந்தை எடுத்துக்கொள்ள வேண்டாம்; சில பொதுவான வலி மருந்துகள் இதய செயலிழப்பு அல்லது சிறுநீரக மருந்துகளுடன் பொருந்தாமல் இருக்கலாம். திடீர்/கடுமையான வலி, நெஞ்சுவலி, மயக்கம், குழப்பம் அல்லது கடுமையான மூச்சுத்திணறல் இருந்தால் அவசர மருத்துவ உதவி பெறவும்."
        )
        else -> null
    }
}

private fun isOverallHealthQuestion(q: String): Boolean {
    val phrases = listOf(
        "how is my health", "how is my condition", "how am i", "am i okay",
        "am i healthy", "getting better", "am i improving", "my progress",
        "health overview", "health summary", "overall health", "overall",
        "all reading", "all readings", "status", "condition", "healthy",
        "health", "kesihatan saya", "keadaan kesihatan", "macam mana kesihatan",
        "bagaimana kesihatan", "saya okay tak", "saya sihat tak",
        "adakah saya sihat", "semakin baik", "bertambah baik", "keadaan",
        "bacaan saya", "健康状况", "今天怎么样", "我还好吗", "是否改善",
        "整体健康", "身体状况", "உடல்நிலை", "உடல்நலம்",
        "நான் நலமாக இருக்கிறேனா", "முன்னேற்றம்", "ஒட்டுமொத்த உடல்நலம்"
    )
    return phrases.any { it in q }
}

private fun isPersonalizedWarningQuestion(q: String): Boolean = listOf(
    "which warning signs should i watch", "warning signs should i watch",
    "tanda amaran yang perlu saya perhatikan", "警告症状", "எச்சரிக்கை அறிகுறிகளை"
).any { it in q }

private fun replyText(
    mode: AiReplyMode,
    en: String,
    ms: String,
    zh: String,
    ta: String
): String = when (mode) {
    AiReplyMode.MALAY -> ms
    AiReplyMode.MANDARIN -> zh
    AiReplyMode.TAMIL -> ta
    AiReplyMode.ROJAK -> en
    AiReplyMode.ENGLISH -> en
}

private fun weightTrendPhrase(trendKg: Double?, mode: AiReplyMode): String? {
    if (trendKg == null) return null
    val amount = "%.1f".format(Locale.ROOT, kotlin.math.abs(trendKg))

    return when {
        trendKg >= 2.0 -> when (mode) {
            AiReplyMode.MALAY -> "berat meningkat ${amount} kg baru-baru ini — ini mungkin pengumpulan cecair, bukan lemak"
            AiReplyMode.ROJAK -> "weight naik ${amount} kg recently — possible fluid retention, bukan lemak"
            AiReplyMode.MANDARIN -> "近期体重增加了 ${amount} kg——可能与液体潴留有关，不一定是脂肪增加"
            AiReplyMode.TAMIL -> "சமீபத்தில் எடை ${amount} kg அதிகரித்துள்ளது — இது கொழுப்பு அதிகரிப்பை விட திரவத் தேக்கம் காரணமாக இருக்கலாம்"
            AiReplyMode.ENGLISH -> "weight has risen ${amount} kg recently — possibly fluid retention rather than fat"
        }
        trendKg <= -2.0 -> when (mode) {
            AiReplyMode.MALAY -> "berat menurun ${amount} kg baru-baru ini"
            AiReplyMode.ROJAK -> "weight turun ${amount} kg recently"
            AiReplyMode.MANDARIN -> "近期体重下降了 ${amount} kg"
            AiReplyMode.TAMIL -> "சமீபத்தில் எடை ${amount} kg குறைந்துள்ளது"
            AiReplyMode.ENGLISH -> "weight has dropped ${amount} kg recently"
        }
        else -> null
    }
}

private fun symptomTrendPhrase(delta: Int?, mode: AiReplyMode): String? {
    if (delta == null) return null

    return when {
        delta >= 4 -> when (mode) {
            AiReplyMode.MALAY -> "skor simptom semakin teruk (+$delta berbanding beberapa rekod lalu)"
            AiReplyMode.ROJAK -> "symptom score makin teruk (+$delta compared with a few records ago)"
            AiReplyMode.MANDARIN -> "症状评分正在变差（比之前几次记录高 $delta 分）"
            AiReplyMode.TAMIL -> "அறிகுறி மதிப்பெண் மோசமடைந்துள்ளது (முந்தைய சில பதிவுகளை விட +$delta)"
            AiReplyMode.ENGLISH -> "symptom score is worsening (+$delta compared with a few records ago)"
        }
        delta <= -4 -> when (mode) {
            AiReplyMode.MALAY -> "skor simptom bertambah baik ($delta berbanding beberapa rekod lalu)"
            AiReplyMode.ROJAK -> "symptom score improving ($delta compared with a few records ago)"
            AiReplyMode.MANDARIN -> "症状评分正在改善（比之前几次记录低 ${kotlin.math.abs(delta)} 分）"
            AiReplyMode.TAMIL -> "அறிகுறி மதிப்பெண் மேம்பட்டுள்ளது (முந்தைய சில பதிவுகளை விட ${kotlin.math.abs(delta)} குறைவு)"
            AiReplyMode.ENGLISH -> "symptom score is improving ($delta compared with a few records ago)"
        }
        else -> null
    }
}

private fun buildOverallHealthReply(c: AiContextState, mode: AiReplyMode): String {
    val sys = c.bp?.split("/")?.firstOrNull()?.toIntOrNull()
    val dia = c.bp?.split("/")?.getOrNull(1)?.toIntOrNull()
    val heart = c.heartRate ?: c.pulse

    val concerns = buildList {
        when {
            sys != null && dia != null && (sys >= 180 || dia >= 120) -> add(replyText(mode,
                "blood pressure is critically high (${c.bp})",
                "tekanan darah sangat tinggi (${c.bp})",
                "血压非常高（${c.bp}）",
                "இரத்த அழுத்தம் மிகவும் அதிகமாக உள்ளது (${c.bp})"
            ))
            sys != null && dia != null && (sys >= 140 || dia >= 90) -> add(replyText(mode,
                "blood pressure is high (${c.bp})",
                "tekanan darah tinggi (${c.bp})",
                "血压偏高（${c.bp}）",
                "இரத்த அழுத்தம் அதிகமாக உள்ளது (${c.bp})"
            ))
            sys != null && dia != null && (sys < 80 || dia < 50) -> add(replyText(mode,
                "blood pressure is low (${c.bp})",
                "tekanan darah rendah (${c.bp})",
                "血压偏低（${c.bp}）",
                "இரத்த அழுத்தம் குறைவாக உள்ளது (${c.bp})"
            ))
        }
        when {
            c.spo2 != null && c.spo2 < 90 -> add(replyText(mode,
                "SpO₂ is critically low (${c.spo2}%)",
                "SpO₂ sangat rendah (${c.spo2}%)",
                "SpO₂ 严重偏低（${c.spo2}%）",
                "SpO₂ மிகவும் குறைவாக உள்ளது (${c.spo2}%)"
            ))
            c.spo2 != null && c.spo2 < 95 -> add(replyText(mode,
                "SpO₂ is below the usual monitoring range (${c.spo2}%)",
                "SpO₂ di bawah julat pemantauan biasa (${c.spo2}%)",
                "SpO₂ 低于一般监测范围（${c.spo2}%）",
                "SpO₂ வழக்கமான கண்காணிப்பு வரம்புக்கு கீழே உள்ளது (${c.spo2}%)"
            ))
        }
        when {
            heart != null && (heart < 50 || heart > 150) -> add(replyText(mode,
                "heart rate is in a critical range ($heart bpm)",
                "denyutan jantung berada dalam julat kritikal ($heart bpm)",
                "心率处于危险范围（$heart bpm）",
                "இதய துடிப்பு ஆபத்தான வரம்பில் உள்ளது ($heart bpm)"
            ))
            heart != null && (heart < 60 || heart > 100) -> add(replyText(mode,
                "heart rate is outside the usual resting range ($heart bpm)",
                "denyutan jantung di luar julat rehat biasa ($heart bpm)",
                "心率超出一般静息范围（$heart bpm）",
                "இதய துடிப்பு வழக்கமான ஓய்வு வரம்புக்கு வெளியே உள்ளது ($heart bpm)"
            ))
        }
        if (c.symptomScore != null && c.symptomScore >= 10) add(replyText(mode,
            "your symptom score is raised (${c.symptomScore}/25)",
            "skor simptom anda meningkat (${c.symptomScore}/25)",
            "您的症状评分偏高（${c.symptomScore}/25）",
            "உங்கள் அறிகுறி மதிப்பெண் உயர்ந்துள்ளது (${c.symptomScore}/25)"
        ))
        weightTrendPhrase(c.weightTrendKg, mode)?.let { add(it) }
        symptomTrendPhrase(c.symptomTrendDelta, mode)?.let { add(it) }
    }

    val missing = buildList {
        if (c.bp.isNullOrBlank()) add(replyText(mode, "BP", "BP", "血压", "இரத்த அழுத்தம்"))
        if (heart == null) add(replyText(mode, "heart rate", "denyutan jantung", "心率", "இதய துடிப்பு"))
        if (c.spo2 == null) add("SpO₂")
        if (c.weight == null) add(replyText(mode, "weight", "berat", "体重", "எடை"))
        if (c.steps == null) add(replyText(mode, "steps", "langkah", "步数", "நடைகள்"))
        if (c.symptomScore == null) add(replyText(mode, "symptoms", "simptom", "症状", "அறிகுறிகள்"))
    }

    val stepsText = c.steps?.let {
        replyText(mode, "$it/${c.targetSteps} steps", "$it/${c.targetSteps} langkah", "$it/${c.targetSteps} 步", "$it/${c.targetSteps} நடைகள்")
    } ?: replyText(mode, "no step data", "tiada data langkah", "没有步数数据", "நடைத் தரவு இல்லை")

    val readings = replyText(
        mode,
        "BP ${c.bp ?: "--"}, heart rate ${heart ?: "--"} bpm, SpO₂ ${c.spo2 ?: "--"}%, weight ${c.weight ?: "--"} kg, and $stepsText",
        "BP ${c.bp ?: "--"}, denyutan jantung ${heart ?: "--"} bpm, SpO₂ ${c.spo2 ?: "--"}%, berat ${c.weight ?: "--"} kg, dan $stepsText",
        "血压 ${c.bp ?: "--"}、心率 ${heart ?: "--"} bpm、SpO₂ ${c.spo2 ?: "--"}%、体重 ${c.weight ?: "--"} kg，以及 $stepsText",
        "இரத்த அழுத்தம் ${c.bp ?: "--"}, இதய துடிப்பு ${heart ?: "--"} bpm, SpO₂ ${c.spo2 ?: "--"}%, எடை ${c.weight ?: "--"} kg மற்றும் $stepsText"
    )

    if (mode == AiReplyMode.ROJAK) {
        val concernText = if (concerns.isEmpty()) "Setakat data yang ada, tak nampak critical reading." else "Yang perlu attention: ${concerns.joinToString("; ")}."
        val missingText = if (missing.isEmpty()) "" else " Missing data: ${missing.joinToString(", ")}."
        return "Based on latest record, bacaan anda: $readings. $concernText$missingText Tengok trend + symptom together, bukan satu reading sahaja. Kalau chest pain, severe breathlessness, pengsan, keliru atau bibir biru, terus dapatkan emergency help."
    }

    val concernText = if (concerns.isEmpty()) {
        replyText(mode,
            "No critical reading is identified from the available data.",
            "Tiada bacaan kritikal dikesan daripada data yang ada.",
            "从现有数据中未发现危急读数。",
            "கிடைக்கும் தரவில் ஆபத்தான அளவு எதுவும் கண்டறியப்படவில்லை."
        )
    } else {
        replyText(mode,
            "Points needing attention: ${concerns.joinToString("; ")}.",
            "Perkara yang perlu diberi perhatian: ${concerns.joinToString("; ")}.",
            "需要注意：${concerns.joinToString("；")}。",
            "கவனம் தேவைப்படும் விஷயங்கள்: ${concerns.joinToString("; ")}."
        )
    }
    val missingText = if (missing.isEmpty()) "" else replyText(mode,
        " Missing data: ${missing.joinToString(", ")}.",
        " Data yang belum lengkap: ${missing.joinToString(", ")}.",
        " 尚未完成的数据：${missing.joinToString("、")}。",
        " பதிவு செய்யாத தரவு: ${missing.joinToString(", ")}."
    )

    return replyText(mode,
        "Based on your latest records, your readings are $readings. $concernText$missingText Look at the trend together with your symptoms rather than relying on one reading. Seek emergency help for chest pain, severe breathlessness, fainting, confusion, or blue lips.",
        "Berdasarkan rekod terkini, bacaan anda ialah $readings. $concernText$missingText Pantau trend bersama simptom, bukan satu bacaan sahaja. Jika ada sakit dada, sesak nafas teruk, pengsan, keliru atau bibir kebiruan, dapatkan bantuan kecemasan segera.",
        "根据最新记录，您的读数为：$readings。$concernText$missingText 请结合趋势和症状判断，不要只依赖一次读数。若出现胸痛、严重呼吸困难、晕厥、意识混乱或嘴唇发蓝，请立即寻求紧急帮助。",
        "சமீபத்திய பதிவுகளின்படி உங்கள் அளவுகள்: $readings. $concernText$missingText ஒரே அளவை மட்டும் நம்பாமல் போக்கையும் அறிகுறிகளையும் சேர்த்து கவனிக்கவும். நெஞ்சுவலி, கடுமையான மூச்சுத்திணறல், மயக்கம், குழப்பம் அல்லது உதடு நீலமாகுதல் ஏற்பட்டால் உடனடி அவசர உதவி பெறவும்."
    )
}

private fun buildPersonalizedWarningReply(c: AiContextState, mode: AiReplyMode): String {
    val snapshot = buildOverallHealthReply(c, mode)
    return replyText(mode,
        "⚠️ Personalised check:\n$snapshot\n\nWatch for worsening breathlessness, new swelling, rapid weight gain, needing more pillows, chest pain, fainting, confusion, blue lips, or SpO₂ below 90%. Recheck an unusual reading after resting; seek emergency help for severe symptoms.",
        "⚠️ Semakan peribadi:\n$snapshot\n\nPantau sesak nafas yang semakin teruk, bengkak baharu, kenaikan berat mendadak, perlu lebih banyak bantal, sakit dada, pengsan, keliru, bibir kebiruan atau SpO₂ bawah 90%. Periksa semula bacaan luar biasa selepas berehat; dapatkan bantuan kecemasan untuk simptom teruk.",
        "⚠️ 个性化检查：\n$snapshot\n\n请留意呼吸困难加重、新出现的肿胀、体重快速增加、需要更多枕头、胸痛、晕厥、意识混乱、嘴唇发蓝或 SpO₂ 低于 90%。异常读数请休息后重新测量；严重症状应立即寻求紧急帮助。",
        "⚠️ தனிப்பட்ட சோதனை:\n$snapshot\n\nமோசமடையும் மூச்சுத்திணறல், புதிய வீக்கம், வேகமான எடை அதிகரிப்பு, அதிக தலையணைகள் தேவைப்படுதல், நெஞ்சுவலி, மயக்கம், குழப்பம், உதடு நீலமாகுதல் அல்லது SpO₂ 90%-க்கு கீழ் இருப்பதை கவனிக்கவும். அசாதாரண அளவை ஓய்வுக்குப் பிறகு மீண்டும் அளவிடவும்; கடுமையான அறிகுறிகளுக்கு அவசர உதவி பெறவும்."
    )
}

private fun findHeartFailureMattersTopic(q: String): HeartFailureKnowledgeTopic? {
    return heartFailureMattersKnowledge
        .map { topic -> topic to topic.keywords.count { it in q } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}

private fun generateContextReply(
    question: String,
    c: AiContextState,
    mode: AiReplyMode,
    history: List<ChatMessage> = emptyList()
): String {
    val q = normalizeAiQuestion(question)

    nauseaFollowUpReply(question, history, mode)?.let { return it }
    abdominalPainFollowUpReply(question, history, mode)?.let { return it }

    if (mode == AiReplyMode.ROJAK) {
        val sys = c.bp?.split("/")?.firstOrNull()?.toIntOrNull()
        val dia = c.bp?.split("/")?.getOrNull(1)?.toIntOrNull()
        val urgent = questionReportsEmergency(q) ||
            (sys != null && (sys >= 180 || sys < 80)) ||
            (dia != null && (dia >= 120 || dia < 50)) ||
            (c.pulse != null && (c.pulse < 50 || c.pulse > 150)) ||
            (c.heartRate != null && (c.heartRate < 50 || c.heartRate > 150)) ||
            (c.spo2 != null && c.spo2 < 90) ||
            (c.symptomScore != null && c.symptomScore >= 18)
        return generateRojakReply(q, c, urgent)
    }

    val hasPhysicalSymptom = hasRecognizedLocalSymptom(q)

    if (isGreetingQuestion(q) && !hasPhysicalSymptom) {
        return detailedAiGreetingText(mode)
    }

    if (isThanksQuestion(q) && !hasPhysicalSymptom) {
        return replyText(mode,
            "You’re welcome. Keep monitoring your readings and symptoms, and ask me whenever something changes.",
            "Sama-sama. Terus pantau bacaan dan simptom anda, dan tanya saya apabila ada perubahan.",
            "不客气。请继续监测读数和症状，有任何变化都可以问我。",
            "வரவேற்கிறேன். உங்கள் அளவுகளையும் அறிகுறிகளையும் தொடர்ந்து கண்காணிக்கவும்; மாற்றம் இருந்தால் என்னிடம் கேளுங்கள்."
        )
    }

    if (isNauseaOrVomitingQuestion(q)) {
        return nauseaFallbackReply(mode)
    }

    if (isEmotionalDistressQuestion(q) && !hasPhysicalSymptom) {
        return emotionalFallbackReply(mode)
    }

    if (isClearlyUnrelatedQuestion(q)) {
        return outOfScopeReply(mode)
    }

    val sys = c.bp?.split("/")?.firstOrNull()?.toIntOrNull()
    val dia = c.bp?.split("/")?.getOrNull(1)?.toIntOrNull()
    val heart = c.heartRate ?: c.pulse
    val urgent = questionReportsEmergency(q) ||
        (sys != null && (sys >= 180 || sys < 80)) ||
        (dia != null && (dia >= 120 || dia < 50)) ||
        (heart != null && (heart < 50 || heart > 150)) ||
        (c.spo2 != null && c.spo2 < 90) ||
        (c.symptomScore != null && c.symptomScore >= 18)

    val urgentReply = replyText(mode,
        "One or more readings need urgent attention. If you have chest pain, severe breathlessness, fainting, confusion, or blue lips, call Malaysia emergency services at 999 now. Rest and recheck only if it is safe.",
        "Satu atau lebih bacaan memerlukan perhatian segera. Jika ada sakit dada, sesak nafas teruk, pengsan, keliru atau bibir kebiruan, hubungi perkhidmatan kecemasan Malaysia di 999 sekarang. Rehat dan periksa semula hanya jika selamat.",
        "一项或多项读数需要立即关注。若出现胸痛、严重呼吸困难、晕厥、意识混乱或嘴唇发蓝，请立即拨打马来西亚紧急服务 999。只有在安全的情况下才休息并重新测量。",
        "ஒன்று அல்லது அதற்கு மேற்பட்ட அளவுகளுக்கு உடனடி கவனம் தேவை. நெஞ்சுவலி, கடுமையான மூச்சுத்திணறல், மயக்கம், குழப்பம் அல்லது உதடு நீலமாகுதல் இருந்தால் மலேசிய அவசர சேவை 999-ஐ உடனே அழைக்கவும். பாதுகாப்பாக இருந்தால் மட்டுமே ஓய்வெடுத்து மீண்டும் அளவிடவும்."
    )

    if (!urgent) {
        PcnaPatientAdvice.answer(q, mode.apiLanguageCode())?.let { return it }
        // Focused symptom replies already include the relevant red flags, so return them
        // directly instead of appending the same generic safety paragraph every time.
        focusedSymptomFallbackReply(q, mode)?.let { return it }
    }

    val answer = when {
        urgent -> urgentReply

        isOverallHealthQuestion(q) -> buildOverallHealthReply(c, mode)

        listOf("warning", "warning sign", "red flag", "amaran", "tanda amaran", "警告", "危险症状", "எச்சரிக்கை").any { it in q } -> replyText(mode,
            "Watch for worsening breathlessness, new or increasing leg swelling, rapid weight gain, needing more pillows to sleep, increased cough, chest pain, fainting, confusion, blue lips, or SpO₂ below 90%. Severe symptoms need emergency help.",
            "Pantau sesak nafas yang semakin teruk, bengkak kaki baharu atau bertambah, kenaikan berat mendadak, perlu lebih banyak bantal untuk tidur, batuk bertambah, sakit dada, pengsan, keliru, bibir kebiruan atau SpO₂ bawah 90%. Simptom teruk memerlukan bantuan kecemasan.",
            "请留意呼吸困难加重、腿部新出现或加重的肿胀、体重快速增加、睡觉需要更多枕头、咳嗽加重、胸痛、晕厥、意识混乱、嘴唇发蓝或 SpO₂ 低于 90%。严重症状需要紧急帮助。",
            "மோசமடையும் மூச்சுத்திணறல், புதிய அல்லது அதிகரிக்கும் கால் வீக்கம், வேகமான எடை அதிகரிப்பு, தூங்க அதிக தலையணைகள் தேவைப்படுதல், அதிக இருமல், நெஞ்சுவலி, மயக்கம், குழப்பம், உதடு நீலமாகுதல் அல்லது SpO₂ 90%-க்கு கீழ் இருப்பதை கவனிக்கவும். கடுமையான அறிகுறிகளுக்கு அவசர உதவி தேவை."
        )

        listOf("spo2", "oxygen", "oksigen", "血氧", "ஆக்சிஜன்").any { it in q } -> {
            val value = c.spo2
            val status = when {
                value == null -> replyText(mode, "No SpO₂ reading is available yet.", "Tiada bacaan SpO₂ lagi.", "目前没有 SpO₂ 读数。", "SpO₂ அளவு இன்னும் இல்லை.")
                value < 90 -> urgentReply
                value < 95 -> replyText(mode, "This is below the usual monitoring range. Rest, warm your finger, and recheck. Contact your healthcare team if it remains low.", "Ini di bawah julat pemantauan biasa. Rehat, hangatkan jari dan periksa semula. Hubungi pasukan kesihatan jika masih rendah.", "该读数低于一般监测范围。请休息、让手指保持温暖并重新测量；如果仍然偏低，请联系医疗团队。", "இது வழக்கமான கண்காணிப்பு வரம்புக்கு கீழே உள்ளது. ஓய்வெடுத்து விரலை சூடாக வைத்துப் மீண்டும் அளவிடவும்; தொடர்ந்து குறைவாக இருந்தால் மருத்துவக் குழுவை தொடர்புகொள்ளவும்.")
                else -> replyText(mode, "This is within the usual monitoring range.", "Ini dalam julat pemantauan biasa.", "该读数在一般监测范围内。", "இது வழக்கமான கண்காணிப்பு வரம்பில் உள்ளது.")
            }
            replyText(mode,
                "Your latest SpO₂ is ${value ?: "not available"}%. $status Keep your finger warm and still while measuring.",
                "SpO₂ terkini ialah ${value ?: "tiada data"}%. $status Pastikan jari hangat dan tidak bergerak semasa mengukur.",
                "您最新的 SpO₂ 为 ${value ?: "暂无数据"}%。$status 测量时请保持手指温暖且不要移动。",
                "உங்கள் சமீபத்திய SpO₂ ${value ?: "தரவு இல்லை"}%. $status அளவிடும்போது விரலை சூடாகவும் அசையாமலும் வைத்திருக்கவும்."
            )
        }

        listOf("bp", "blood pressure", "tekanan darah", "systolic", "diastolic", "血压", "இரத்த அழுத்தம்").any { it in q } -> {
            val status = when {
                sys == null || dia == null -> replyText(mode, "No complete BP reading is available yet.", "Tiada bacaan BP lengkap lagi.", "目前没有完整的血压读数。", "முழுமையான இரத்த அழுத்த அளவு இன்னும் இல்லை.")
                sys >= 180 || dia >= 120 -> urgentReply
                sys >= 140 || dia >= 90 -> replyText(mode, "This is high. Rest for 5 minutes and measure again. If it stays high, contact your healthcare team.", "Bacaan ini tinggi. Rehat 5 minit dan ukur semula. Jika masih tinggi, hubungi pasukan kesihatan.", "该血压偏高。请休息 5 分钟后重新测量；如果仍然偏高，请联系医疗团队。", "இந்த அளவு அதிகமாக உள்ளது. 5 நிமிடம் ஓய்வெடுத்து மீண்டும் அளவிடவும்; தொடர்ந்து அதிகமாக இருந்தால் மருத்துவக் குழுவை தொடர்புகொள்ளவும்.")
                sys < 80 || dia < 50 -> replyText(mode, "This is low. Sit or lie down, recheck, and seek help if you are dizzy, faint, or weak.", "Bacaan ini rendah. Duduk atau baring, periksa semula dan dapatkan bantuan jika pening, pengsan atau lemah.", "该血压偏低。请坐下或躺下并重新测量；若头晕、晕厥或虚弱，请寻求帮助。", "இந்த அளவு குறைவாக உள்ளது. அமரவும் அல்லது படுக்கவும், மீண்டும் அளவிடவும்; மயக்கம், உணர்வு இழப்பு அல்லது பலவீனம் இருந்தால் உதவி பெறவும்.")
                else -> replyText(mode, "This is not in the app’s critical BP range.", "Bacaan ini tidak berada dalam julat BP kritikal aplikasi.", "该读数不在应用设定的危急血压范围内。", "இந்த அளவு செயலியின் ஆபத்தான இரத்த அழுத்த வரம்பில் இல்லை.")
            }
            replyText(mode,
                "Your latest BP is ${c.bp ?: "not available"}. $status Measure after resting, with feet flat and your arm supported at heart level.",
                "BP terkini ialah ${c.bp ?: "tiada data"}. $status Ukur selepas berehat, dengan kaki rata dan lengan disokong pada paras jantung.",
                "您最新的血压为 ${c.bp ?: "暂无数据"}。$status 测量前请先休息，双脚平放，并让手臂支撑在心脏高度。",
                "உங்கள் சமீபத்திய இரத்த அழுத்தம் ${c.bp ?: "தரவு இல்லை"}. $status ஓய்வுக்குப் பிறகு, பாதங்கள் தரையில் சமமாகவும் கை இதய உயரத்தில் ஆதரவுடனும் அளவிடவும்."
            )
        }

        listOf("heart rate", "pulse", "nadi", "hr", "berdebar", "心率", "脉搏", "இதய துடிப்பு", "நாடித் துடிப்பு").any { it in q } -> {
            val status = when {
                heart == null -> replyText(mode, "No heart-rate reading is available yet.", "Tiada bacaan denyutan jantung lagi.", "目前没有心率读数。", "இதய துடிப்பு அளவு இன்னும் இல்லை.")
                heart < 50 || heart > 150 -> urgentReply
                heart < 60 || heart > 100 -> replyText(mode, "This is outside the usual resting range. Rest and recheck; contact your healthcare team if it persists or you have symptoms.", "Ini di luar julat rehat biasa. Rehat dan periksa semula; hubungi pasukan kesihatan jika berterusan atau ada simptom.", "该心率超出一般静息范围。请休息后重新测量；若持续异常或伴有症状，请联系医疗团队。", "இது வழக்கமான ஓய்வு வரம்புக்கு வெளியே உள்ளது. ஓய்வெடுத்து மீண்டும் அளவிடவும்; தொடர்ந்து இருந்தாலோ அறிகுறிகள் இருந்தாலோ மருத்துவக் குழுவை தொடர்புகொள்ளவும்.")
                else -> replyText(mode, "This is within the usual resting range.", "Ini dalam julat rehat biasa.", "该心率在一般静息范围内。", "இது வழக்கமான ஓய்வு வரம்பில் உள்ளது.")
            }
            replyText(mode,
                "Your latest heart rate is ${heart ?: "not available"} bpm. $status",
                "Denyutan jantung terkini ialah ${heart ?: "tiada data"} bpm. $status",
                "您最新的心率为 ${heart ?: "暂无数据"} bpm。$status",
                "உங்கள் சமீபத்திய இதய துடிப்பு ${heart ?: "தரவு இல்லை"} bpm. $status"
            )
        }

        listOf("weight", "weigh", "berat", "timbang", "reduce weight", "lose weight", "体重", "எடை").any { it in q } -> {
            val trend = weightTrendPhrase(c.weightTrendKg, mode)
            val trendText = trend?.let {
                replyText(mode, " Recent trend: $it.", " Trend terkini: $it.", " 最近趋势：$it。", " சமீபத்திய போக்கு: $it.")
            }.orEmpty()
            replyText(mode,
                "Your latest weight is ${c.weight ?: "not available"} kg.$trendText Weigh at a similar time each day. A sudden rise may be fluid retention rather than body fat, especially with swelling or breathlessness.",
                "Berat terkini ialah ${c.weight ?: "tiada data"} kg.$trendText Timbang pada waktu yang hampir sama setiap hari. Kenaikan mendadak mungkin disebabkan pengumpulan cecair, terutama bersama bengkak atau sesak nafas.",
                "您最新的体重为 ${c.weight ?: "暂无数据"} kg。$trendText 请每天在相近时间称重。体重突然增加可能是液体潴留，尤其同时出现肿胀或呼吸困难时。",
                "உங்கள் சமீபத்திய எடை ${c.weight ?: "தரவு இல்லை"} kg.$trendText தினமும் ஒரே நேரத்திற்கு அருகில் எடையை அளவிடவும். திடீர் அதிகரிப்பு உடல் கொழுப்பை விட திரவச் சேர்க்கையாக இருக்கலாம்; குறிப்பாக வீக்கம் அல்லது மூச்சுத்திணறலுடன் இருந்தால்."
            )
        }

        listOf("symptom", "simptom", "cough", "swelling", "bengkak", "breath", "sesak", "batuk", "fatigue", "tired", "penat", "症状", "咳嗽", "肿胀", "அறிகுறி", "இருமல்", "வீக்கம்").any { it in q } -> {
            val trend = symptomTrendPhrase(c.symptomTrendDelta, mode)
            val trendText = trend?.let {
                replyText(mode, " Recent trend: $it.", " Trend terkini: $it.", " 最近趋势：$it。", " சமீபத்திய போக்கு: $it.")
            }.orEmpty()
            replyText(mode,
                "Your latest symptom score is ${c.symptomScore ?: "not available"}/25.$trendText Watch for worsening breathlessness, swelling, needing more pillows, increased cough, chest pain, dizziness or fainting, and abdominal swelling. Contact your healthcare team if symptoms worsen.",
                "Skor simptom terkini ialah ${c.symptomScore ?: "tiada data"}/25.$trendText Pantau sesak nafas bertambah, bengkak, perlu lebih banyak bantal, batuk bertambah, sakit dada, pening atau pengsan dan perut membengkak. Hubungi pasukan kesihatan jika simptom semakin teruk.",
                "您最新的症状评分为 ${c.symptomScore ?: "暂无数据"}/25。$trendText 请留意呼吸困难加重、肿胀、需要更多枕头、咳嗽加重、胸痛、头晕或晕厥及腹部肿胀。症状加重时请联系医疗团队。",
                "உங்கள் சமீபத்திய அறிகுறி மதிப்பெண் ${c.symptomScore ?: "தரவு இல்லை"}/25.$trendText மோசமடையும் மூச்சுத்திணறல், வீக்கம், அதிக தலையணைகள் தேவை, அதிக இருமல், நெஞ்சுவலி, மயக்கம் மற்றும் வயிற்று வீக்கத்தை கவனிக்கவும். அறிகுறிகள் மோசமடைந்தால் மருத்துவக் குழுவை தொடர்புகொள்ளவும்."
            )
        }

        listOf("water", "fluid", "air", "minum", "thirst", "dahaga", "饮水", "液体", "நீர்", "திரவம்").any { it in q } -> replyText(mode,
            "Your recorded fluid intake is ${c.waterMl ?: "--"}/${c.waterLimitMl ?: "--"} ml. Follow the individual limit given by your doctor. Soup, ice, jelly, and porridge also count as fluids.",
            "Pengambilan cecair yang direkodkan ialah ${c.waterMl ?: "--"}/${c.waterLimitMl ?: "--"} ml. Ikut had individu yang diberi oleh doktor. Sup, ais, jeli dan bubur juga dikira sebagai cecair.",
            "您记录的液体摄入量为 ${c.waterMl ?: "--"}/${c.waterLimitMl ?: "--"} ml。请遵循医生为您设定的个人限制；汤、冰、果冻和粥也算液体。",
            "பதிவு செய்யப்பட்ட திரவ உட்கொள்ளல் ${c.waterMl ?: "--"}/${c.waterLimitMl ?: "--"} ml. மருத்துவர் வழங்கிய தனிப்பட்ட வரம்பை பின்பற்றவும். சூப், பனி, ஜெல்லி மற்றும் கஞ்சி ஆகியவையும் திரவமாக கணக்கிடப்படும்."
        )

        listOf("healthy plate", "plate method", "pinggan sihat", "suku suku separuh", "portion", "健康餐盘", "உணவுத் தட்டு").any { it in q } -> replyText(mode,
            "Use ¼ of the plate for carbohydrates, ¼ for lean protein, and ½ for vegetables and fruit. Reduce salty sauces, instant food, and processed food.",
            "Gunakan ¼ pinggan untuk karbohidrat, ¼ untuk protein kurang lemak dan ½ untuk sayur serta buah. Kurangkan sos masin, makanan segera dan makanan proses.",
            "餐盘的 ¼ 放碳水化合物，¼ 放低脂蛋白质，½ 放蔬菜和水果。减少咸味酱料、即食食品和加工食品。",
            "தட்டின் ¼ பகுதியை கார்போஹைட்ரேட்டுக்கு, ¼ பகுதியை குறைந்த கொழுப்பு புரதத்திற்கு, ½ பகுதியை காய்கறி மற்றும் பழத்திற்கு பயன்படுத்தவும். உப்பான சாஸ், உடனடி மற்றும் பதப்படுத்திய உணவை குறைக்கவும்."
        )

        listOf("salt", "sodium", "garam", "diet", "food", "meal", "makanan", "kicap", "fast food", "盐", "钠", "உப்பு").any { it in q } -> replyText(mode,
            "Your latest salt score is ${c.saltScore?.let { com.vitalink.app.util.SaltScore.display(it) } ?: "--"}/6. Reduce soy sauce, salty sauces, fast food, instant soup, and processed food. Choose fresh food and check sodium labels.",
            "Skor garam terkini ialah ${c.saltScore?.let { com.vitalink.app.util.SaltScore.display(it) } ?: "--"}/6. Kurangkan kicap, sos masin, makanan segera, sup segera dan makanan proses. Pilih makanan segar dan semak label sodium.",
            "您最新的盐分评分为 ${c.saltScore?.let { com.vitalink.app.util.SaltScore.display(it) } ?: "--"}/6。减少酱油、咸味酱料、快餐、即食汤和加工食品；选择新鲜食物并查看钠含量标签。",
            "உங்கள் சமீபத்திய உப்பு மதிப்பெண் ${c.saltScore?.let { com.vitalink.app.util.SaltScore.display(it) } ?: "--"}/6. சோயா சாஸ், உப்பான சாஸ், துரித உணவு, உடனடி சூப் மற்றும் பதப்படுத்திய உணவை குறைக்கவும். புதிய உணவைத் தேர்ந்தெடுத்து சோடியம் லேபிளைச் சரிபார்க்கவும்."
        )

        listOf("steps", "exercise", "walk", "senaman", "langkah", "activity", "target", "remaining", "步数", "运动", "நடைகள்", "உடற்பயிற்சி").any { it in q } -> {
            val steps = c.steps ?: 0L
            val remaining = (c.targetSteps - steps).coerceAtLeast(0L)
            replyText(mode,
                "You have $steps steps today out of a ${c.targetSteps}-step target, with $remaining remaining. Increase activity gradually and stop for chest pain, unusual breathlessness, dizziness, or strong palpitations.",
                "Anda mempunyai $steps langkah hari ini daripada sasaran ${c.targetSteps}, dengan baki $remaining langkah. Tambah aktiviti secara perlahan dan berhenti jika sakit dada, sesak nafas luar biasa, pening atau berdebar kuat.",
                "您今天已走 $steps 步，目标为 ${c.targetSteps} 步，还差 $remaining 步。请逐渐增加活动；若出现胸痛、异常呼吸困难、头晕或明显心悸，请停止活动。",
                "இன்று $steps நடைகள் நடந்துள்ளீர்கள்; இலக்கு ${c.targetSteps}, இன்னும் $remaining நடைகள் உள்ளன. செயல்பாட்டை மெதுவாக அதிகரிக்கவும்; நெஞ்சுவலி, அசாதாரண மூச்சுத்திணறல், மயக்கம் அல்லது பலமான இதயத் துடிப்பு இருந்தால் நிறுத்தவும்."
            )
        }

        listOf("medicine", "medication", "ubat", "dose", "dos", "tablet", "diuretic", "药", "மருந்து").any { it in q } -> replyText(mode,
            "Recorded medicines: ${c.medication?.takeIf { it.isNotBlank() } ?: "no record"}. Take them exactly as prescribed. Do not change or stop a dose without advice from your doctor or pharmacist. Tell them about side effects or missed doses.",
            "Ubat yang direkodkan: ${c.medication?.takeIf { it.isNotBlank() } ?: "tiada rekod"}. Ambil tepat mengikut arahan. Jangan ubah atau hentikan dos tanpa nasihat doktor atau ahli farmasi. Maklumkan jika ada kesan sampingan atau dos tertinggal.",
            "记录的药物：${c.medication?.takeIf { it.isNotBlank() } ?: "暂无记录"}。请严格按处方服用，不要在没有医生或药剂师建议的情况下更改或停药；若有副作用或漏服，请告知他们。",
            "பதிவு செய்யப்பட்ட மருந்துகள்: ${c.medication?.takeIf { it.isNotBlank() } ?: "பதிவு இல்லை"}. பரிந்துரைத்தபடி எடுத்துக்கொள்ளவும். மருத்துவர் அல்லது மருந்தாளர் ஆலோசனை இல்லாமல் அளவை மாற்றவோ நிறுத்தவோ வேண்டாம். பக்கவிளைவுகள் அல்லது தவறிய அளவுகளை தெரிவிக்கவும்."
        )

        listOf("appointment", "temujanji", "hospital", "clinic", "klinik", "follow up", "预约", "复诊", "சந்திப்பு").any { it in q } -> {
            if (!c.nextAppointmentDate.isNullOrBlank()) {
                val title = c.nextAppointmentTitle?.takeIf { it.isNotBlank() } ?: replyText(mode, "Hospital appointment", "Temujanji hospital", "医院预约", "மருத்துவமனை சந்திப்பு")
                val time = c.nextAppointmentTime?.takeIf { it.isNotBlank() }
                replyText(mode,
                    "Your next appointment is $title on ${c.nextAppointmentDate}${time?.let { " at $it" }.orEmpty()}. The app reminds you about 24 hours before the appointment, not 12 hours before. Android may deliver it a few minutes late because the background check runs periodically.",
                    "Temujanji seterusnya ialah $title pada ${c.nextAppointmentDate}${time?.let { " pukul $it" }.orEmpty()}. Aplikasi mengingatkan anda kira-kira 24 jam sebelum temujanji, bukan 12 jam. Android mungkin menghantarnya lewat beberapa minit kerana semakan latar berjalan secara berkala.",
                    "您的下一次预约是 $title，日期为 ${c.nextAppointmentDate}${time?.let { "，时间 $it" }.orEmpty()}。应用会在预约前约 24 小时提醒，而不是提前 12 小时。由于 Android 定期进行后台检查，通知可能延迟几分钟。",
                    "உங்கள் அடுத்த சந்திப்பு $title, தேதி ${c.nextAppointmentDate}${time?.let { ", நேரம் $it" }.orEmpty()}. செயலி சந்திப்புக்கு சுமார் 24 மணி நேரத்திற்கு முன் நினைவூட்டும்; 12 மணி நேரத்திற்கு முன் அல்ல. Android பின்னணி சரிபார்ப்பால் சில நிமிட தாமதம் ஏற்படலாம்."
                )
            } else {
                replyText(mode,
                    "No upcoming appointment is recorded. Add the date and time on My Appointment. The reminder is sent about 24 hours before the saved appointment time.",
                    "Tiada temujanji akan datang direkodkan. Tambah tarikh dan masa pada My Appointment. Peringatan dihantar kira-kira 24 jam sebelum masa temujanji yang disimpan.",
                    "目前没有即将到来的预约。请在“我的预约”中添加日期和时间；提醒会在已保存的预约时间前约 24 小时发送。",
                    "வரவிருக்கும் சந்திப்பு பதிவு செய்யப்படவில்லை. My Appointment-இல் தேதி மற்றும் நேரத்தைச் சேர்க்கவும். சேமித்த சந்திப்பு நேரத்திற்கு சுமார் 24 மணி நேரத்திற்கு முன் நினைவூட்டல் அனுப்பப்படும்."
                )
            }
        }

        else -> {
            val topic = findHeartFailureMattersTopic(q)
            if (topic != null) {
                val title = when (mode) {
                    AiReplyMode.MALAY -> topic.titleMs
                    AiReplyMode.MANDARIN -> com.vitalink.app.util.TranslationCatalog.translate(topic.titleEn, com.vitalink.app.util.AppLanguageCode.MANDARIN)
                    AiReplyMode.TAMIL -> com.vitalink.app.util.TranslationCatalog.translate(topic.titleEn, com.vitalink.app.util.AppLanguageCode.TAMIL)
                    else -> topic.titleEn
                }
                replyText(mode,
                    "$title: Keep following your care plan, monitor daily changes, take medicines as prescribed, and contact your healthcare team when symptoms worsen.",
                    "$title: Terus ikut pelan penjagaan, pantau perubahan harian, ambil ubat mengikut arahan dan hubungi pasukan kesihatan apabila simptom bertambah teruk.",
                    "$title：请继续遵循护理计划，监测每日变化，按处方服药，并在症状加重时联系医疗团队。",
                    "$title: பராமரிப்பு திட்டத்தை தொடர்ந்து பின்பற்றி, தினசரி மாற்றங்களை கண்காணித்து, பரிந்துரைப்படி மருந்துகளை எடுத்துக்கொண்டு, அறிகுறிகள் மோசமடைந்தால் மருத்துவக் குழுவை தொடர்புகொள்ளவும்."
                )
            } else {
                // V9: Unknown input should never become an unrelated health-data dump.
                // Invite the patient to describe the concern instead.
                replyText(mode,
                    "Tell me what you’re feeling or what heart-care question you have. I can help with symptoms, BP, SpO₂, heart rate, weight, medicines, water and salt, exercise, or appointments.",
                    "Beritahu saya apa yang anda rasa atau soalan penjagaan jantung anda. Saya boleh bantu tentang simptom, BP, SpO₂, denyutan jantung, berat, ubat, air dan garam, senaman atau temujanji.",
                    "请告诉我您现在有什么感觉，或您想问哪方面的心脏护理问题。我可以帮助您了解症状、血压、SpO₂、心率、体重、药物、饮水与盐分、运动或预约。",
                    "நீங்கள் என்ன உணர்கிறீர்கள் அல்லது எந்த இதய பராமரிப்பு கேள்வி உள்ளது என்று சொல்லுங்கள். அறிகுறிகள், இரத்த அழுத்தம், SpO₂, இதய துடிப்பு, எடை, மருந்துகள், நீர்/உப்பு, உடற்பயிற்சி அல்லது சந்திப்புகள் குறித்து உதவ முடியும்."
                )
            }
        }
    }

    val safety = replyText(mode,
        "\n\nThis guidance does not replace medical advice. Seek urgent medical help for an emergency.",
        "\n\nPanduan ini tidak menggantikan nasihat doktor. Untuk kecemasan, dapatkan bantuan perubatan segera.",
        "\n\n此内容不能取代医疗建议。如遇紧急情况，请立即寻求医疗帮助。",
        "\n\nஇந்த வழிகாட்டுதல் மருத்துவ ஆலோசனையை மாற்றாது. அவசரநிலையில் உடனடி மருத்துவ உதவி பெறவும்."
    )
    return if (answer.endsWith(safety.trim())) answer else answer + safety
}

private fun generateRojakReply(q: String, c: AiContextState, urgent: Boolean): String {
    val sys = c.bp?.split("/")?.firstOrNull()?.toIntOrNull()
    val dia = c.bp?.split("/")?.getOrNull(1)?.toIntOrNull()
    val hasPhysicalSymptom = hasRecognizedLocalSymptom(q)

    if (!urgent) PcnaPatientAdvice.answer(q, "ms")?.let { return it }
    if (!urgent && !isNauseaOrVomitingQuestion(q)) {
        focusedSymptomFallbackReply(q, AiReplyMode.ROJAK)?.let { return it }
    }

    val answer = when {
        isGreetingQuestion(q) && !hasPhysicalSymptom -> detailedAiGreetingText(AiReplyMode.ROJAK)

        isThanksQuestion(q) && !hasPhysicalSymptom -> "Sama-sama! Keep monitoring bacaan dan symptom anda, dan chat dengan saya bila ada apa-apa perubahan."

        isNauseaOrVomitingQuestion(q) -> nauseaFallbackReply(AiReplyMode.ROJAK)

        isEmotionalDistressQuestion(q) && !hasPhysicalSymptom -> emotionalFallbackReply(AiReplyMode.ROJAK)

        urgent -> {
            "Ada satu atau lebih bacaan yang nampak critical. Kalau ada chest pain, sesak nafas teruk, pengsan, keliru atau bibir kebiruan, call Malaysia emergency services 999 sekarang. Rehat dan check semula hanya kalau selamat."
        }

        isOverallHealthQuestion(q) -> buildOverallHealthReply(c, AiReplyMode.ROJAK)

        listOf("spo2", "oxygen", "oksigen").any { it in q } -> {
            "Latest SpO₂ anda ialah ${c.spo2 ?: "tak ada data"}%. Masa measure, pastikan jari warm dan jangan bergerak. Kalau bawah 95%, check semula; bawah 90% perlukan urgent attention."
        }

        listOf("bp", "blood pressure", "tekanan darah", "systolic", "diastolic", "tekanan tinggi", "tekanan rendah").any { it in q } -> {
            val bpText = c.bp ?: "tak ada data"
            val status = when {
                sys == null || dia == null -> "Saya belum boleh classify sebab data tak lengkap."
                sys >= 180 || dia >= 120 -> "Ini sangat tinggi dan perlukan urgent attention, especially kalau ada symptom."
                sys >= 140 || dia >= 90 -> "Ini agak tinggi. Rehat 5 minit dan check semula."
                sys < 80 || dia < 50 -> "Ini rendah. Sit or lie down dan dapatkan help kalau pening atau pengsan."
                else -> "Bacaan ini tidak berada dalam critical range."
            }
            "BP terkini anda $bpText. $status Pastikan kaki rata dan lengan at heart level masa ukur."
        }

        listOf("heart rate", "pulse", "nadi", "hr", "berdebar").any { it in q } -> {
            val hr = c.heartRate ?: c.pulse
            "Latest heart rate anda ialah ${hr ?: "tak ada data"} bpm. Rehat sekejap dan check again. Kalau bawah 50 atau lebih 150, especially dengan chest pain, pengsan atau sesak nafas, seek urgent help."
        }

        listOf("weight", "weigh", "berat", "timbang", "reduce weight", "lose weight", "kurangkan berat").any { it in q } -> {
            val trend = weightTrendPhrase(c.weightTrendKg, AiReplyMode.ROJAK)
            val trendText = trend?.let { " Trend: $it." }.orEmpty()
            "Latest weight anda ${c.weight ?: "tak ada data"} kg.$trendText Cuba timbang pada masa sama setiap hari. Sudden weight gain boleh jadi fluid retention, bukan semestinya lemak, so jangan ignore kalau naik cepat."
        }

        listOf(
            "symptom", "simptom", "cough", "swelling", "bengkak", "breath", "sesak",
            "batuk", "warning", "amaran", "fatigue", "tired", "penat", "chest pain",
            "sakit dada", "pening", "pengsan"
        ).any { it in q } -> {
            val trend = symptomTrendPhrase(c.symptomTrendDelta, AiReplyMode.ROJAK)
            val trendText = trend?.let { " Trend: $it." }.orEmpty()
            "Latest symptom score anda ${c.symptomScore ?: "tak ada data"}/25.$trendText Monitor kalau makin sesak, kaki bengkak, perlu tambah bantal, batuk bertambah, sakit dada, pening/pengsan atau perut membengkak. Kalau worsening, contact doctor."
        }

        listOf("water", "fluid", "air", "minum", "thirst", "dahaga").any { it in q } -> {
            "Water intake terkini ${c.waterMl ?: "--"}/${c.waterLimitMl ?: "--"} ml. Follow fluid limit yang doctor bagi sebab setiap patient lain. Soup, ice, jelly dan bubur pun kira sebagai fluid."
        }

        listOf("suku suku separuh", "healthy plate", "plate method", "pinggan sihat", "portion").any { it in q } -> {
            "Untuk suku-suku-separuh: ¼ carbs, ¼ protein dan ½ vegetables + fruit. Untuk heart care, pilih lean protein, lebihkan sayur dan kurangkan garam, sauce serta processed food."
        }

        listOf("salt", "sodium", "garam", "diet", "food", "meal", "makanan", "makan", "kicap", "fast food").any { it in q } -> {
            "Latest salt score anda ${c.saltScore?.let { com.vitalink.app.util.SaltScore.display(it) } ?: "--"}/6. Cuba kurangkan kicap, sauce, fast food, instant soup dan processed food. Guna herbs atau spices for flavour dan check sodium label."
        }

        listOf("steps", "exercise", "walk", "senaman", "langkah", "activity", "rehab", "target", "remaining", "sasaran", "baki").any { it in q } -> {
            val steps = c.steps ?: 0L
            val remaining = (c.targetSteps - steps).coerceAtLeast(0L)
            "Hari ini anda ada $steps steps daripada target ${c.targetSteps}; baki $remaining lagi. Add activity slowly, dan stop kalau ada chest pain, unusual breathlessness, pening atau strong palpitations."
        }

        listOf("medicine", "medication", "ubat", "dose", "dos", "tablet", "diuretic", "water tablet").any { it in q } -> {
            "Medicine yang direkodkan: ${c.medication?.takeIf { it.isNotBlank() } ?: "tak ada record"}. Ambil ikut prescription dan jangan change atau stop dose tanpa nasihat doctor."
        }

        listOf("appointment", "temujanji", "hospital", "clinic", "klinik", "follow up", "susulan").any { it in q } -> {
            if (!c.nextAppointmentDate.isNullOrBlank()) {
                val title = c.nextAppointmentTitle?.takeIf { it.isNotBlank() } ?: "Hospital appointment"
                val time = c.nextAppointmentTime?.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
                "Next appointment anda ialah $title on ${c.nextAppointmentDate}$time. App akan cuba send notification lebih kurang 24 jam before."
            } else {
                "Tak ada upcoming appointment dalam record. Save date dan time dekat My Appointment."
            }
        }

        else -> {
            val topic = findHeartFailureMattersTopic(q)
            if (topic != null) {
                val en = topic.adviceEn.firstOrNull().orEmpty()
                val ms = topic.adviceMs.getOrNull(1) ?: topic.adviceMs.firstOrNull().orEmpty()
                "$en\n\n$ms"
            } else {
                "Beritahu saya apa yang anda rasa atau heart-care question anda. Saya boleh bantu tentang symptom, BP, SpO₂, heart rate, weight, medicine, water/salt, exercise atau appointment."
            }
        }
    }

    return answer + "\n\nIni general guidance sahaja, bukan pengganti nasihat doctor. Untuk emergency, dapatkan bantuan perubatan segera."
}

private fun patientFacingAnswer(raw: String): String = raw.lines()
    .filterNot { it.trim().matches(Regex("(?i)^(sources?|references?|citations?|PCNA 2022|sumber|rujukan|参考来源|参考资料)[:： ].*")) }
    .joinToString("\n")
    .replace(Regex("\\[([^\\]]+)\\]\\(https?://[^)]+\\)"), "$1")
    .replace(Regex("https?://\\S+"), "")
    .trim()
