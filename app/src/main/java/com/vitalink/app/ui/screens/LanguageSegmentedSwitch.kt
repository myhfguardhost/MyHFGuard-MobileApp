package com.vitalink.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.AppLanguageCode

@Composable
fun LanguageSegmentedSwitch(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val current = AppLanguage.current
    val label = when (current) {
        AppLanguageCode.ENGLISH -> "EN"
        AppLanguageCode.MALAY -> "BM"
        AppLanguageCode.MANDARIN -> "中文"
        AppLanguageCode.TAMIL -> "தமிழ்"
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.width(100.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            tonalElevation = 2.dp
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                AppLanguageCode.ENGLISH to "English",
                AppLanguageCode.MALAY to "Bahasa Melayu",
                AppLanguageCode.MANDARIN to "中文",
                AppLanguageCode.TAMIL to "தமிழ்"
            ).forEach { (language, text) ->
                DropdownMenuItem(
                    text = { Text(text, fontWeight = if (language == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        AppLanguage.setLanguage(context, language)
                        expanded = false
                    }
                )
            }
        }
    }
}
