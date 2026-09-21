package com.vitalink.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vitalink.app.util.AppLanguage
import kotlinx.coroutines.delay

/**
 * Loading dialog for network sync/save operations.
 *
 * A cancel-and-return action appears when an operation takes unusually long.
 * Screens can provide [onCancel] to cancel the active coroutine. When no callback
 * is supplied, the user can still close the overlay and return to the form.
 */
@Composable
fun BlockingLoadingScreen(
    visible: Boolean,
    title: String,
    detail: String = AppLanguage.text(
        "Please wait a moment.",
        "Sila tunggu sebentar.",
        "请稍候。",
        "சிறிது நேரம் காத்திருக்கவும்."
    ),
    onCancel: (() -> Unit)? = null,
    cancelAfterMillis: Long = 8_000L
) {
    var showCancel by remember { mutableStateOf(false) }
    var locallyDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (!visible) {
            showCancel = false
            locallyDismissed = false
        } else {
            showCancel = false
            delay(cancelAfterMillis)
            showCancel = true
        }
    }

    if (!visible || locallyDismissed) return

    val cancelAndReturn = {
        onCancel?.invoke()
        locallyDismissed = true
    }

    Dialog(
        onDismissRequest = {
            if (showCancel) cancelAndReturn()
        },
        properties = DialogProperties(
            dismissOnBackPress = showCancel,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.widthIn(max = 340.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (showCancel) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = AppLanguage.text(
                                "Taking too long? You can return to the form.",
                                "Terlalu lama? Anda boleh kembali ke borang.",
                                "等待太久？您可以返回输入页面。",
                                "அதிக நேரமாகிறதா? உள்ளீட்டு படிவத்திற்குத் திரும்பலாம்."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = cancelAndReturn,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                AppLanguage.text(
                                    "Cancel and return",
                                    "Batal dan kembali",
                                    "取消并返回",
                                    "ரத்துசெய்து திரும்பவும்"
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
