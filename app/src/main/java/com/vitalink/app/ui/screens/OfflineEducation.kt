package com.vitalink.app.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vitalink.app.util.AppLanguage
import kotlinx.coroutines.delay
import org.json.JSONArray

fun educationCatalogue(context: Context): List<EducationVideoUi> {
    val rows = JSONArray(context.assets.open("education/catalog.json").bufferedReader().use { it.readText() })
    return (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        val resource = row.getString("resource")
        EducationVideoUi(row.getString("key"), row.optJSONObject("titles")?.optString(AppLanguage.current.storageValue)?.takeIf { it.isNotBlank() } ?: row.getString("title"),
            AppLanguage.text("Offline video • Watch to earn 10 coins", "Video luar talian • Tonton untuk mendapat 10 syiling", "离线视频 • 观看可获得10枚金币", "இணையமில்லா காணொளி • பார்த்து 10 நாணயங்கள் பெறுங்கள்"),
            row.getString("url"), context.resources.getIdentifier(resource, "raw", context.packageName), resource)
    }
}

private data class Caption(val start: Int, val end: Int, val text: String)

@Composable
fun OfflineEducationPlayer(video: EducationVideoUi, watchKey: String, onDismiss: () -> Unit, onCompleted: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val language = AppLanguage.current.storageValue
    val prefs = remember { context.getSharedPreferences("education_watch_coverage", Context.MODE_PRIVATE) }
    val coverage = remember(watchKey) { WatchCoverage(prefs.getStringSet(watchKey, emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet()) }
    val captions = remember(video.key, language) {
        fun read(lang: String): List<Caption> = runCatching {
            val data = JSONArray(context.assets.open("education/${video.captionResource}_$lang.json").bufferedReader().use { it.readText() })
            (0 until data.length()).map { i -> data.getJSONObject(i).let { Caption(it.getInt("start"), it.getInt("end"), it.getString("text")) } }
        }.getOrDefault(emptyList())
        val chosen = read(language)
        if (chosen.isNotEmpty()) language to chosen else "en" to read("en")
    }
    val narrationId = remember(video.key, language) {
        context.resources.getIdentifier("${video.captionResource}_$language", "raw", context.packageName)
    }
    val narration = remember(video.key, language) {
        if (language != "en" && narrationId != 0) android.media.MediaPlayer.create(context, narrationId) else null
    }
    var ttsReady by remember(video.key, language) { mutableStateOf(false) }
    var speech by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    DisposableEffect(video.key, language) {
        val engine = android.speech.tts.TextToSpeech(context) { result ->
            if (result == android.speech.tts.TextToSpeech.SUCCESS) {
                val locale = java.util.Locale.forLanguageTag(when (language) { "zh" -> "zh-CN"; "ta" -> "ta-IN"; "ms" -> "ms-MY"; else -> "en-US" })
                ttsReady = (speech?.setLanguage(locale) ?: android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) >= 0
            }
        }
        speech = engine
        onDispose { engine.stop(); engine.shutdown(); speech = null }
    }
    var translatedAudio by remember(video.key, language) { mutableStateOf(language != "en") }
    val useSpeech = translatedAudio && narration == null && ttsReady && captions.first == language
    var isPlaying by remember { mutableStateOf(false) }
    var lastSpoken by remember(video.key, language) { mutableStateOf("") }
    var videoAudio by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    DisposableEffect(narration) { onDispose { narration?.release() } }
    LaunchedEffect(videoAudio, translatedAudio, narration, useSpeech) {
        val volume = if (translatedAudio && (narration != null || useSpeech)) 0f else 1f
        runCatching { videoAudio?.setVolume(volume, volume) }
        if (!translatedAudio) runCatching { narration?.pause() }
    }
    var player by remember { mutableStateOf<VideoView?>(null) }
    var caption by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var awarded by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    val completed by rememberUpdatedState(onCompleted)
    fun save() { prefs.edit().putStringSet(watchKey, coverage.seconds.map(Int::toString).toSet()).apply() }
    DisposableEffect(owner, video.key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { foreground = false; player?.pause(); save() }
            if (event == Lifecycle.Event.ON_START) foreground = true
        }
        owner.lifecycle.addObserver(observer)
        onDispose { save(); owner.lifecycle.removeObserver(observer); player?.stopPlayback(); player = null }
    }
    LaunchedEffect(player, foreground, narration, translatedAudio, useSpeech) {
        while (true) {
            val view = player
            if (view != null) {
                val position = view.currentPosition
                val playing = foreground && view.isPlaying
                isPlaying = playing
                coverage.sample(position, playing)
                runCatching {
                    narration?.let { audio ->
                        if (translatedAudio && playing) {
                            if (kotlin.math.abs(audio.currentPosition - position) > 650) audio.seekTo(position)
                            if (!audio.isPlaying) audio.start()
                        } else if (audio.isPlaying) audio.pause()
                    }
                }
                caption = captions.second.lastOrNull { position >= it.start && position < it.end }?.text.orEmpty()
                if (useSpeech && playing && caption.isNotBlank() && caption != lastSpoken) {
                    speech?.speak(caption, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "video-caption")
                    lastSpoken = caption
                } else if (!playing || !useSpeech) {
                    speech?.stop()
                    lastSpoken = ""
                }
            }
            delay(400)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("🎥 ${video.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = AppLanguage.text("Close", "Tutup", "关闭", "மூடு"))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                        VideoView(ctx).also { view ->
                            player = view
                            view.setMediaController(MediaController(ctx).apply { setAnchorView(view) })
                            view.setVideoURI(Uri.parse("android.resource://${ctx.packageName}/${video.localRawResId}"))
                            view.setOnPreparedListener { videoAudio = it; it.isLooping = false; if (translatedAudio && narration != null) it.setVolume(0f, 0f); view.start() }
                            view.setOnCompletionListener {
                                coverage.sample(view.duration, true)
                                save()
                                if (coverage.complete(view.duration)) {
                                    if (!awarded) { awarded = true; completed() }
                                    status = AppLanguage.text("Completed — collect your coins after closing the video.", "Selesai — kutip syiling selepas menutup video.", "已完成，请关闭视频领取金币。", "முடிந்தது — காணொளியை மூடி நாணயங்களைப் பெறுங்கள்.")
                                } else {
                                    status = AppLanguage.text("Please watch the skipped parts to unlock coins.", "Sila tonton bahagian yang dilangkau untuk membuka syiling.", "请观看跳过的部分以解锁金币。", "நாணயங்களைப் பெற தவிர்த்த பகுதிகளைப் பாருங்கள்.")
                                }
                            }
                            view.setOnErrorListener { _, _, _ ->
                                status = AppLanguage.text("This video could not be played. Please reopen it.", "Video ini tidak dapat dimainkan. Sila buka semula.", "无法播放此视频，请重新打开。", "காணொளியை இயக்க முடியவில்லை. மீண்டும் திறக்கவும்."); true
                            }
                        }
                    })
                }
                Button(onClick = {
                    if (player?.isPlaying == true) { player?.pause(); narration?.pause(); speech?.stop(); isPlaying = false }
                    else { player?.start(); isPlaying = true }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isPlaying) AppLanguage.text("Pause", "Jeda", "暂停", "இடைநிறுத்து") else AppLanguage.text("Play", "Main", "播放", "இயக்கு"))
                }
                if (narration != null || (ttsReady && captions.first == language)) {
                    TextButton(onClick = { translatedAudio = !translatedAudio }) {
                        Text(if (translatedAudio) AppLanguage.text("🔊 Translated audio • Switch to original", "🔊 Audio terjemahan • Tukar kepada asal", "🔊 译配音频 · 切换原声", "🔊 ஒலி: மொழிபெயர்ப்பு • அசல் ஒலிக்கு மாற்று")
                        else AppLanguage.text("🔊 Original audio • Switch to translation", "🔊 Audio asal • Tukar kepada terjemahan", "🔊 原声音频 · 切换译配", "🔊 ஒலி: அசல் • மொழிபெயர்ப்புக்கு மாற்று"))
                    }
                }
                if (translatedAudio && narration == null && !ttsReady) {
                    Text(AppLanguage.text("Translated audio needs the selected language voice installed on this device.", "Audio terjemahan memerlukan suara bahasa yang dipilih dipasang pada peranti.", "译配音频需要在设备上安装所选语言的语音包。", "மொழிபெயர்ப்பு ஒலிக்கு தேர்ந்தெடுத்த மொழியின் குரலை சாதனத்தில் நிறுவ வேண்டும்."), style = MaterialTheme.typography.bodySmall)
                }
                if (captions.second.isNotEmpty()) {
                    Text(caption.ifBlank { " " }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
                    Text(AppLanguage.text("Captions", "Sari kata", "字幕", "வசன வரிகள்") + ": " + when(captions.first) { "ms" -> "Bahasa Melayu"; "zh" -> "中文"; "ta" -> "தமிழ்"; else -> "English" }, style = MaterialTheme.typography.labelSmall)
                }
                if (status.isNotEmpty()) Text(status)
            }
        }
    }
}
