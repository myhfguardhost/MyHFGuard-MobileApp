package com.vitalink.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.MachineOcrScanner
import java.io.File

private fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 2000) decoder.setTargetSize((info.size.width * 2000f / longest).toInt().coerceAtLeast(1), (info.size.height * 2000f / longest).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}.getOrNull()

private fun createCameraUri(context: android.content.Context): Uri {
    val directory = File(context.cacheDir, "ocr_images").apply { mkdirs() }
    val file = File.createTempFile("myhfguard_ocr_", ".jpg", directory)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
private fun OcrQuickGuide(
    title: String,
    tips: List<String>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.TipsAndUpdates,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                tips.forEach { tip ->
                    Text(
                        text = "• $tip",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OcrImageButtons(
    onBitmap: (Bitmap) -> Unit,
    onMessage: (String) -> Unit,
    automatic: Boolean = false
) {
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var reviewImage by remember { mutableStateOf<Bitmap?>(null) }
    reviewImage?.let { image -> OcrImageReview(image, { reviewImage = null }) { corrected -> reviewImage = null; onBitmap(corrected) } }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            decodeBitmap(context, uri)?.let { if (automatic) onBitmap(it) else reviewImage = it }
                ?: onMessage(AppLanguage.text("Unable to open the captured image.", "Tidak dapat membuka gambar.", "无法打开拍摄的图片。", "படத்தைத் திறக்க முடியவில்லை."))
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createCameraUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            onMessage(AppLanguage.text("Camera permission is needed for scanning.", "Kebenaran kamera diperlukan untuk imbasan.", "扫描需要相机权限。", "ஸ்கேன் செய்ய கேமரா அனுமதி தேவை."))
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            decodeBitmap(context, uri)?.let { if (automatic) onBitmap(it) else reviewImage = it }
                ?: onMessage(AppLanguage.text("Unable to open the selected image.", "Tidak dapat membuka gambar yang dipilih.", "无法打开所选图片。", "தேர்ந்தெடுத்த படத்தைத் திறக்க முடியவில்லை."))
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    val uri = createCameraUri(context)
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Text(AppLanguage.text(" Camera OCR", " OCR Kamera", " 相机识别", " கேமரா OCR"))
        }
        OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Text(AppLanguage.text(" Gallery", " Galeri", " 相册", " படத்தொகுப்பு"))
        }
    }
}

@Composable
fun WeightMachineScanButtons(
    onWeightDetected: (Double) -> Unit,
    onMessage: (String) -> Unit,
    onSmartFallback: ((Bitmap) -> Unit)? = null,
    onLoadingChange: (Boolean) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OcrQuickGuide(
            title = AppLanguage.text(
                "Scan weight correctly",
                "Cara imbas berat",
                "正确扫描体重",
                "எடையை சரியாக ஸ்கேன் செய்ய"
            ),
            tips = listOf(
                AppLanguage.text(
                    "Wait until the reading stops changing.",
                    "Tunggu sehingga bacaan tidak berubah.",
                    "等待读数停止变化。",
                    "அளவு மாறாமல் நிலையாகும் வரை காத்திருக்கவும்."
                ),
                AppLanguage.text(
                    "Keep the full number clear and straight.",
                    "Pastikan nombor penuh jelas dan lurus.",
                    "保持完整数字清晰、画面端正。",
                    "முழு எண்ணும் தெளிவாகவும் நேராகவும் இருக்கட்டும்."
                ),
                AppLanguage.text(
                    "Avoid glare, then verify before saving.",
                    "Elakkan silau, kemudian semak sebelum simpan.",
                    "避免反光，保存前请核对。",
                    "ஒளி பிரதிபலிப்பை தவிர்த்து, சேமிக்கும் முன் சரிபார்க்கவும்."
                )
            )
        )
        OcrImageButtons(
            onBitmap = { bitmap ->
            onLoadingChange(true)
            MachineOcrScanner.scanWeight(
                bitmap,
                onSuccess = {
                    onWeightDetected(it.valueKg)
                    onLoadingChange(false)
                },
                onFailure = { localError ->
                    if (onSmartFallback != null) {
                        onSmartFallback(bitmap)
                        onLoadingChange(false)
                    } else {
                        onLoadingChange(false)
                        onMessage(localError)
                    }
                }
            )
            },
            onMessage = onMessage,
            automatic = true
        )
    }
}

@Composable
fun BloodPressureMachineScanButtons(
    onBloodPressureDetected: (Int, Int, Int) -> Unit,
    onMessage: (String) -> Unit,
    onSmartFallback: ((Bitmap) -> Unit)? = null,
    onLoadingChange: (Boolean) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OcrQuickGuide(
            title = AppLanguage.text(
                "Scan blood pressure correctly",
                "Cara imbas tekanan darah",
                "正确扫描血压",
                "இரத்த அழுத்தத்தை சரியாக ஸ்கேன் செய்ய"
            ),
            tips = listOf(
                AppLanguage.text(
                    "Wait until the monitor finishes.",
                    "Tunggu sehingga monitor selesai.",
                    "等待测量完成。",
                    "மானிட்டர் அளவீட்டை முடிக்கும் வரை காத்திருக்கவும்."
                ),
                AppLanguage.text(
                    "Show the complete screen: SYS at top, DIA in the middle and Pulse below.",
                    "Tunjukkan skrin penuh: SYS di atas, DIA di tengah dan Nadi di bawah.",
                    "显示完整屏幕：上方 SYS、中间 DIA、下方脉搏。",
                    "முழு திரையையும் காட்டவும்: மேலே SYS, நடுவில் DIA, கீழே நாடித்துடிப்பு."
                ),
                AppLanguage.text(
                    "Keep the monitor centred and straight, avoid glare, then verify all three values.",
                    "Pastikan monitor di tengah dan lurus, elakkan silau, kemudian semak ketiga-tiga bacaan.",
                    "保持血压计居中端正、避免反光，并核对三个数值。",
                    "மானிட்டரை நடுவில் நேராக வைத்து, ஒளி பிரதிபலிப்பை தவிர்த்து மூன்று மதிப்புகளையும் சரிபார்க்கவும்."
                )
            )
        )
        OcrImageButtons(
            onBitmap = { bitmap ->
            onLoadingChange(true)
            if (onSmartFallback != null) {
                onSmartFallback(bitmap)
                onLoadingChange(false)
            } else MachineOcrScanner.scanBloodPressure(
                bitmap,
                onSuccess = {
                    onBloodPressureDetected(it.systolic, it.diastolic, it.pulse)
                    onLoadingChange(false)
                },
                onFailure = { localError ->
                    if (onSmartFallback != null) {
                        onSmartFallback(bitmap)
                        onLoadingChange(false)
                    } else {
                        onLoadingChange(false)
                        onMessage(localError)
                    }
                }
            )
            },
            onMessage = onMessage,
            automatic = true
        )
    }
}
