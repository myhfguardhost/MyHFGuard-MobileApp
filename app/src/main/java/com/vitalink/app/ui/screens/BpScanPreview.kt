package com.vitalink.app.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vitalink.app.util.AppLanguage

@Composable
internal fun BpScanPreview(encoded: String, onDismiss: () -> Unit) {
    val bitmap = remember(encoded) { runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppLanguage.text("Check scanned readings", "Semak bacaan imbasan", "核对识别读数", "ஸ்கேன் மதிப்புகளைச் சரிபார்க்கவும்")) },
        text = { bitmap?.let { Image(it.asImageBitmap(), "Detected BP rows", Modifier.fillMaxWidth().heightIn(max = 400.dp), contentScale = ContentScale.Fit) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(AppLanguage.text("Review values", "Semak nilai", "核对数值", "மதிப்புகளைச் சரிபார்")) } }
    )
}
