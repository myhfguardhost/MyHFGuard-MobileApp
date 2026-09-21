@file:Suppress("SpellCheckingInspection", "GrazieInspection")

package com.vitalink.app.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalink.app.data.model.ChatMessage
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.AppLanguageCode
import androidx.compose.foundation.gestures.detectDragGestures
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Small voice-first AI nurse shown above the bottom navigation on every
 * authenticated MyHFGuard screen.
 *
 * Voice path:
 *   microphone -> Android speech recognition -> existing AiChatViewModel /
 *   existing Gemini-backed MyHFGuard API -> Android TextToSpeech speaker.
 *
 * It intentionally has no free-text box. The nurse is a quick voice companion,
 * while the full My Chat page remains available for typed conversations.
 */
@Composable
fun FloatingAiNurse(
    vm: AiChatViewModel,
    modifier: Modifier = Modifier,
    bottomReservedSpace: Dp = 20.dp
) {
    val context = LocalContext.current
    val patientId by vm.patientId.collectAsState()
    val appLanguage = AppLanguage.current

    var expanded by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var replying by remember { mutableStateOf(false) }
    var latestUserText by remember { mutableStateOf("") }
    // Key the greeting and speaker language to the app language so changing
    // EN / BM / 中文 / தமிழ் immediately updates the floating nurse.
    var latestReply by remember(appLanguage) { mutableStateOf(initialNurseGreeting()) }
    var latestReplyMode by remember(appLanguage) { mutableStateOf(replyModeForAppLanguage()) }
    var pendingSpeech by remember { mutableStateOf<Pair<String, AiReplyMode>?>(null) }
    val conversation = remember { mutableStateListOf<ChatMessage>() }

    // Draggable AI Nurse bubble. The complete circular icon is always kept
    // inside the visible app area, so it can never become clipped off-screen.
    // Its X/Y position is remembered when navigating between app screens.
    val bubblePrefs = remember(context) {
        context.getSharedPreferences(AI_NURSE_BUBBLE_PREFS, android.content.Context.MODE_PRIVATE)
    }
    var bubbleX by remember { mutableFloatStateOf(Float.NaN) }
    var bubbleY by remember { mutableFloatStateOf(Float.NaN) }
    var bubbleWasDragged by remember {
        mutableStateOf(
            bubblePrefs.contains(AI_NURSE_BUBBLE_X_FRACTION) ||
                bubblePrefs.contains(AI_NURSE_BUBBLE_Y_FRACTION)
        )
    }

    var ttsReady by remember { mutableStateOf(false) }
    var ttsInstallPromptedFor by remember { mutableStateOf<AppLanguageCode?>(null) }

    // Samsung phones commonly have both Samsung TTS and Google's Speech
    // Recognition & Synthesis installed. If Android is allowed to choose an
    // engine for INSTALL_TTS_DATA, it shows a system "Complete action using"
    // chooser. Prefer Google TTS for MyHFGuard when it is installed because it
    // has broader coverage for EN / BM / 中文 / தமிழ். Otherwise, keep the
    // phone's normal default TTS engine.
    val preferredTtsEngine = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .firstOrNull { it.serviceInfo?.packageName == GOOGLE_TTS_ENGINE_PACKAGE }
            ?.serviceInfo
            ?.packageName
    }

    val textToSpeech = remember(context, preferredTtsEngine) {
        val listener = TextToSpeech.OnInitListener { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
        if (preferredTtsEngine != null) {
            TextToSpeech(context.applicationContext, listener, preferredTtsEngine)
        } else {
            TextToSpeech(context.applicationContext, listener)
        }
    }

    DisposableEffect(textToSpeech) {
        onDispose {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    LaunchedEffect(patientId, appLanguage) {
        // Do not let one patient's nurse conversation leak into another login,
        // and do not keep old-language turns after the user changes app language.
        conversation.clear()
        latestUserText = ""
        latestReply = initialNurseGreeting()
        replying = false
        listening = false
        pendingSpeech = null
        ttsInstallPromptedFor = null
    }

    fun openTtsVoiceInstaller() {
        // Bind the installer to the TTS engine used by MyHFGuard. This avoids
        // Samsung's "Complete action using" chooser when more than one TTS
        // engine is installed on the phone.
        val enginePackage = preferredTtsEngine ?: textToSpeech.defaultEngine
        val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            if (!enginePackage.isNullOrBlank()) {
                setPackage(enginePackage)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(installIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                AppLanguage.text(
                    "This phone cannot open the speech-voice installer. Please install/update Speech Services by Google in the Play Store.",
                    "Telefon ini tidak dapat membuka pemasang suara. Sila pasang atau kemas kini Speech Services by Google di Play Store.",
                    "此手机无法打开语音安装程序。请在 Play 商店安装或更新 Speech Services by Google。",
                    "இந்த தொலைபேசியில் குரல் நிறுவியைத் திறக்க முடியவில்லை. Play Store-ல் Speech Services by Google-ஐ நிறுவவும் அல்லது புதுப்பிக்கவும்."
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun configureTtsLanguage(mode: AiReplyMode): Boolean {
        for (locale in ttsLocaleCandidates(mode)) {
            val result = textToSpeech.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return true
            }
        }
        return false
    }

    fun promptForMissingTtsVoice() {
        // Prompt only once for the currently selected app language. The AI text
        // remains visible even when the device has no matching speech voice.
        if (ttsInstallPromptedFor == appLanguage) return
        ttsInstallPromptedFor = appLanguage
        Toast.makeText(
            context,
            AppLanguage.text(
                "The voice for the selected language is not installed. Please install the speech voice, then tap the speaker again.",
                "Suara untuk bahasa yang dipilih belum dipasang. Sila pasang suara pertuturan, kemudian tekan ikon pembesar suara sekali lagi.",
                "尚未安装所选语言的语音。请先安装语音数据，然后再次点击扬声器。",
                "தேர்ந்தெடுத்த மொழிக்கான குரல் நிறுவப்படவில்லை. குரல் தரவை நிறுவி, பின்னர் ஸ்பீக்கரை மீண்டும் தட்டவும்."
            ),
            Toast.LENGTH_LONG
        ).show()
        openTtsVoiceInstaller()
    }

    fun speak(text: String, mode: AiReplyMode) {
        if (text.isBlank()) return
        if (!ttsReady) {
            pendingSpeech = text to mode
            return
        }
        pendingSpeech = null
        if (!configureTtsLanguage(mode)) {
            // Do not read Mandarin/Tamil/Malay with an English fallback voice: it
            // sounds garbled. Keep the Gemini text on screen and guide the user
            // to install the correct Android TTS voice instead.
            promptForMissingTtsVoice()
            return
        }
        textToSpeech.setSpeechRate(0.95f)
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "myhfguard_ai_nurse")
    }

    LaunchedEffect(ttsReady, appLanguage) {
        if (ttsReady) {
            // Do NOT open a TTS installer merely because the Dashboard/Profile
            // was opened. Only deal with missing voice data when the user
            // actually requests speech. This removes the unexpected chooser on
            // Samsung devices during normal app navigation.
            pendingSpeech?.let { (text, pendingMode) -> speak(text, pendingMode) }
        }
    }

    val adviceRequest by com.vitalink.app.util.NurseAdviceRequests.request.collectAsState()
    LaunchedEffect(adviceRequest, ttsReady) {
        adviceRequest?.let { (_, text) ->
            expanded = true
            latestReply = text
            latestReplyMode = replyModeForAppLanguage()
            if (ttsReady) {
                speak(text, latestReplyMode)
                com.vitalink.app.util.NurseAdviceRequests.consumed()
            }
        }
    }

    fun submitVoiceText(spokenText: String) {
        val q = spokenText.trim()
        if (q.isBlank() || replying) return

        val history = conversation.takeLast(20)
        latestUserText = q
        conversation.add(ChatMessage(role = "user", content = q))
        vm.clearRiskNotice()
        replying = true

        // The floating nurse always follows the language selected in MyHFGuard.
        // Do not auto-detect from the spoken sentence: a patient may say an English
        // medical term while the app itself is set to BM / 中文 / தமிழ்.
        val mode = replyModeForAppLanguage()
        latestReplyMode = mode
        val memoryNotes = loadAiMemoryNotes(context, patientId)

        vm.answerNurse(q, mode, history, memoryNotes) { reply, memoryUpdates ->
            latestReply = reply
            conversation.add(ChatMessage(role = "assistant", content = reply))
            saveAiMemoryNotes(context, patientId, memoryNotes, memoryUpdates)
            replying = false
            speak(reply, mode)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        listening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                expanded = true
                submitVoiceText(spoken)
            }
        }
    }

    fun startListening() {
        if (replying) return
        if (patientId.isBlank()) {
            Toast.makeText(
                context,
                AppLanguage.text(
                    "AI Nurse is loading your profile. Please try again in a moment.",
                    "Jururawat AI sedang memuatkan profil anda. Cuba lagi sebentar lagi.",
                    "AI 护士正在加载您的资料，请稍后再试。",
                    "AI செவிலியர் உங்கள் சுயவிவரத்தை ஏற்றுகிறார். சிறிது நேரத்தில் மீண்டும் முயற்சிக்கவும்."
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        textToSpeech.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLocaleTag(appLanguage))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLocaleTag(appLanguage))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                AppLanguage.text(
                    "Tell your AI nurse how you feel",
                    "Beritahu jururawat AI keadaan anda",
                    "告诉 AI 护士你的情况",
                    "உங்கள் நிலையை AI செவிலியரிடம் சொல்லுங்கள்"
                )
            )
        }

        try {
            listening = true
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            listening = false
            Toast.makeText(
                context,
                AppLanguage.text(
                    "Speech recognition is not available on this phone.",
                    "Pengecaman suara tidak tersedia pada telefon ini.",
                    "此手机无法使用语音识别。",
                    "இந்த தொலைபேசியில் குரல் அடையாளம் கிடைக்கவில்லை."
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // This overlay is intentionally transparent; only the nurse card/bubble
        // consumes touch events, so the rest of the screen remains interactive.
        val bubbleSize = 66.dp
        val bubbleSizePx = with(density) { bubbleSize.toPx() }
        val horizontalMarginPx = with(density) { 10.dp.toPx() }
        val topMarginPx = with(density) { 12.dp.toPx() }
        val bottomReservedPx = with(density) { bottomReservedSpace.toPx() }
        val screenWidthPx = this.constraints.maxWidth.toFloat()
        val screenHeightPx = this.constraints.maxHeight.toFloat()
        val minVisibleX = horizontalMarginPx
        val maxVisibleX = (screenWidthPx - bubbleSizePx - horizontalMarginPx)
            .coerceAtLeast(minVisibleX)
        val minVisibleY = topMarginPx
        val maxVisibleY = (screenHeightPx - bottomReservedPx - bubbleSizePx)
            .coerceAtLeast(minVisibleY)

        LaunchedEffect(screenWidthPx, screenHeightPx, bottomReservedPx) {
            if (bubbleX.isNaN() || bubbleY.isNaN()) {
                val savedXFraction = bubblePrefs
                    .getFloat(AI_NURSE_BUBBLE_X_FRACTION, 1f)
                    .coerceIn(0f, 1f)
                val savedYFraction = bubblePrefs
                    .getFloat(AI_NURSE_BUBBLE_Y_FRACTION, 1f)
                    .coerceIn(0f, 1f)

                val horizontalTravel = (maxVisibleX - minVisibleX).coerceAtLeast(0f)
                val verticalTravel = (maxVisibleY - minVisibleY).coerceAtLeast(0f)
                bubbleX = (minVisibleX + savedXFraction * horizontalTravel)
                    .coerceIn(minVisibleX, maxVisibleX)
                bubbleY = (minVisibleY + savedYFraction * verticalTravel)
                    .coerceIn(minVisibleY, maxVisibleY)

                // Migrate away from the previous half-hidden/tucked behaviour.
                bubblePrefs.edit().putBoolean(AI_NURSE_BUBBLE_TUCKED, false).apply()
            } else {
                bubbleX = bubbleX.coerceIn(minVisibleX, maxVisibleX)
                bubbleY = bubbleY.coerceIn(minVisibleY, maxVisibleY)
            }
        }

        // The conversation card opens above the navigation area. Keeping the card
        // independent of the bubble prevents it from being clipped when the user
        // moves the chat head close to a corner.
        if (expanded) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = bottomReservedSpace + 82.dp
                    )
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 326.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FCFF)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NurseAvatar(size = 42.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                AppLanguage.text(
                                    "AI Nurse",
                                    "Jururawat AI",
                                    "AI 护士",
                                    "AI செவிலியர்"
                                ),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFF17667A)
                            )
                            Text(
                                AppLanguage.text(
                                    "Voice heart-care assistant",
                                    "Pembantu penjagaan jantung bersuara",
                                    "语音心脏护理助手",
                                    "குரல் இதய பராமரிப்பு உதவியாளர்"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF5D7278)
                            )
                        }
                        IconButton(onClick = {
                            expanded = false
                            textToSpeech.stop()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    if (latestUserText.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFEAF7FB)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(
                                    AppLanguage.text("You said", "Anda berkata", "你说", "நீங்கள் சொன்னது"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2A7182)
                                )
                                Text(
                                    latestUserText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 210.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White
                    ) {
                        Box(Modifier.padding(11.dp)) {
                            if (replying) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        AppLanguage.text(
                                            "AI Nurse is thinking…",
                                            "Jururawat AI sedang berfikir…",
                                            "AI 护士正在思考…",
                                            "AI செவிலியர் யோசிக்கிறார்…"
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Text(
                                    latestReply,
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF263238)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            AppLanguage.text(
                                "Heart-care topics only",
                                "Topik penjagaan jantung sahaja",
                                "仅限心脏护理主题",
                                "இதய பராமரிப்பு தலைப்புகள் மட்டும்"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71858B)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                enabled = !replying && latestReply.isNotBlank(),
                                onClick = { speak(latestReply, latestReplyMode) }
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = AppLanguage.text("Replay", "Main semula", "重播", "மீண்டும் கேள்"),
                                    tint = Color(0xFF267A8E)
                                )
                            }
                            FilledIconButton(
                                enabled = !replying,
                                onClick = {
                                    if (listening) {
                                        listening = false
                                    } else {
                                        startListening()
                                    }
                                }
                            ) {
                                Icon(
                                    if (listening) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = AppLanguage.text("Speak", "Cakap", "说话", "பேசுங்கள்")
                                )
                            }
                        }
                    }
                }
            }
        }

        // Show the old greeting hint until the patient starts interacting with or
        // moving the chat head. It then becomes a clean Messenger-style bubble.
        if (!expanded && !bubbleWasDragged && !bubbleX.isNaN()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 84.dp, bottom = bottomReservedSpace + 18.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFCDEEF7),
                shadowElevation = 4.dp
            ) {
                Text(
                    AppLanguage.text(
                        "Hi 👋 Drag or tap AI Nurse",
                        "Hai 👋 Seret atau tekan Jururawat AI",
                        "你好 👋 拖动或点击 AI 护士",
                        "வணக்கம் 👋 AI செவிலியரை இழுக்கவும் அல்லது தட்டவும்"
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF17667A)
                )
            }
        }

        if (!bubbleX.isNaN() && !bubbleY.isNaN()) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(bubbleX.roundToInt(), bubbleY.roundToInt()) }
                    .size(bubbleSize)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable {
                        if (expanded) {
                            startListening()
                        } else {
                            expanded = true
                        }
                    }
                    .pointerInput(
                        screenWidthPx,
                        screenHeightPx,
                        bottomReservedPx
                    ) {
                        detectDragGestures(
                            onDragStart = {
                                bubbleWasDragged = true
                                expanded = false
                                textToSpeech.stop()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                // Clamp both coordinates on every drag frame. This
                                // guarantees the entire 66dp icon stays on-screen.
                                bubbleX = (bubbleX + dragAmount.x)
                                    .coerceIn(minVisibleX, maxVisibleX)
                                bubbleY = (bubbleY + dragAmount.y)
                                    .coerceIn(minVisibleY, maxVisibleY)
                            },
                            onDragEnd = {
                                val horizontalTravel = (maxVisibleX - minVisibleX)
                                    .coerceAtLeast(0f)
                                val verticalTravel = (maxVisibleY - minVisibleY)
                                    .coerceAtLeast(0f)
                                val xFraction = if (horizontalTravel > 0f) {
                                    ((bubbleX - minVisibleX) / horizontalTravel)
                                        .coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                                val yFraction = if (verticalTravel > 0f) {
                                    ((bubbleY - minVisibleY) / verticalTravel)
                                        .coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                                bubblePrefs.edit()
                                    .putFloat(AI_NURSE_BUBBLE_X_FRACTION, xFraction)
                                    .putFloat(AI_NURSE_BUBBLE_Y_FRACTION, yFraction)
                                    .putBoolean(AI_NURSE_BUBBLE_TUCKED, false)
                                    .apply()
                            },
                            onDragCancel = {
                                bubbleX = bubbleX.coerceIn(minVisibleX, maxVisibleX)
                                bubbleY = bubbleY.coerceIn(minVisibleY, maxVisibleY)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                NurseAvatar(size = 58.dp)
            }
        }
    }
}


@Composable
private fun NurseAvatar(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFFE8F8FC)),
        contentAlignment = Alignment.Center
    ) {
        // Emoji keeps the project self-contained while still giving the nurse a
        // friendly face like the sample floating assistant supplied by the user.
        Text("👩‍⚕️", fontSize = (size.value * 0.53f).sp)
    }
}

private fun initialNurseGreeting(): String = AppLanguage.text(
    "Hi 👋 I’m your AI Nurse. Tap the microphone and tell me how you feel or ask about your heart care.",
    "Hai 👋 Saya Jururawat AI anda. Tekan mikrofon dan beritahu keadaan anda atau tanya tentang penjagaan jantung.",
    "你好 👋 我是你的 AI 护士。点击麦克风，告诉我你的感受或询问心脏护理问题。",
    "வணக்கம் 👋 நான் உங்கள் AI செவிலியர். மைக்ரோஃபோனை தட்டி உங்கள் உணர்வை சொல்லுங்கள் அல்லது இதய பராமரிப்பு பற்றி கேளுங்கள்."
)

private fun replyModeForAppLanguage(): AiReplyMode = when (AppLanguage.current) {
    AppLanguageCode.ENGLISH -> AiReplyMode.ENGLISH
    AppLanguageCode.MALAY -> AiReplyMode.MALAY
    AppLanguageCode.MANDARIN -> AiReplyMode.MANDARIN
    AppLanguageCode.TAMIL -> AiReplyMode.TAMIL
}

private fun ttsLocaleCandidates(mode: AiReplyMode): List<Locale> = when (mode) {
    // Try the Malaysian locale first where it exists, then a language-only or
    // widely supported regional voice. This avoids unnecessary English fallback.
    AiReplyMode.MALAY, AiReplyMode.ROJAK -> listOf(
        Locale("ms", "MY"),
        Locale("ms")
    )
    AiReplyMode.MANDARIN -> listOf(
        Locale.SIMPLIFIED_CHINESE,
        Locale.CHINESE,
        Locale("zh", "CN")
    ).distinct()
    AiReplyMode.TAMIL -> listOf(
        Locale("ta", "MY"),
        Locale("ta", "IN"),
        Locale("ta")
    )
    AiReplyMode.ENGLISH -> listOf(
        Locale("en", "MY"),
        Locale.US,
        Locale.ENGLISH
    )
}

private const val AI_NURSE_BUBBLE_PREFS = "myhfguard_ai_nurse_bubble"
private const val AI_NURSE_BUBBLE_X_FRACTION = "x_fraction"
private const val AI_NURSE_BUBBLE_Y_FRACTION = "y_fraction"
private const val AI_NURSE_BUBBLE_TUCKED = "tucked"

private const val GOOGLE_TTS_ENGINE_PACKAGE = "com.google.android.tts"

private fun speechLocaleTag(language: AppLanguageCode): String = when (language) {
    AppLanguageCode.ENGLISH -> "en-MY"
    AppLanguageCode.MALAY -> "ms-MY"
    AppLanguageCode.MANDARIN -> "zh-CN"
    // ta-IN has wider SpeechRecognizer support than ta-MY on many Android phones.
    // Gemini/TTS output is still locked to Tamil for the MyHFGuard UI language.
    AppLanguageCode.TAMIL -> "ta-IN"
}

