package com.vitalink.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vitalink.app.util.AppLanguage
import kotlinx.coroutines.delay

/**
 * Shared success overlay shown after a patient-entered record is saved or updated.
 * It intentionally uses one consistent confirmation across all data-entry modules.
 */
@Composable
fun DataCollectedSuccessScreen(
    visible: Boolean,
    onDismiss: () -> Unit,
    autoDismissMillis: Long = 1_800L
) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(autoDismissMillis)
            onDismiss()
        }
    }

    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
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
                modifier = Modifier.widthIn(max = 350.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(82.dp)
                            .background(Color(0xFF2E7D32), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓",
                            color = Color.White,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.size(20.dp))
                    Text(
                        text = AppLanguage.text(
                            "Data successfully collected!",
                            "Data berjaya dikumpulkan!",
                            "数据已成功收集！",
                            "தரவு வெற்றிகரமாக சேகரிக்கப்பட்டது!"
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = AppLanguage.text(
                            "Your information has been saved successfully.",
                            "Maklumat anda telah berjaya disimpan.",
                            "您的资料已成功保存。",
                            "உங்கள் தகவல் வெற்றிகரமாக சேமிக்கப்பட்டது."
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** A clear result screen for an unsuccessful OCR scan. Manual fields remain visible behind it. */
@Composable
fun OcrFailureScreen(
    message: String?,
    onEnterManually: () -> Unit
) {
    if (message == null) return

    Dialog(
        onDismissRequest = onEnterManually,
        properties = DialogProperties(
            dismissOnBackPress = true,
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
                modifier = Modifier.widthIn(max = 350.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(82.dp)
                            .background(Color(0xFFC62828), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("!", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(20.dp))
                    Text(
                        AppLanguage.text("Scan was not successful", "Imbasan tidak berjaya", "扫描未成功", "ஸ்கேன் வெற்றியடையவில்லை"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color(0xFFC62828)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(20.dp))
                    Button(onClick = onEnterManually) {
                        Text(AppLanguage.text("Enter numbers manually", "Masukkan nombor secara manual", "手动输入数字", "எண்களை கைமுறையாக உள்ளிடவும்"))
                    }
                }
            }
        }
    }
}
