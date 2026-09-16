package com.vitalink.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vitalink.app.util.AppLanguage
import kotlin.math.*

/** Rectify a tilted display without requiring another photograph. No values are saved here. */
@Composable
internal fun OcrImageReview(original: Bitmap, onDismiss: () -> Unit, onScan: (Bitmap) -> Unit) {
    var bitmap by remember(original) { mutableStateOf(original) }
    var corners by remember(bitmap) { mutableStateOf(listOf(Offset(.03f,.03f), Offset(.97f,.03f), Offset(.97f,.97f), Offset(.03f,.97f))) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var error by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(AppLanguage.text("Adjust the display frame", "Laraskan bingkai paparan", "调整屏幕边框", "திரைச் சட்டத்தைச் சரிசெய்யவும்"), style = MaterialTheme.typography.titleLarge)
                Text(AppLanguage.text("Drag the four corners around the display. Keep all numbers and units inside.", "Seret empat penjuru mengelilingi paparan. Pastikan semua nombor dan unit di dalam.", "拖动四个角框住显示屏，保留所有数字和单位。", "நான்கு மூலைகளையும் திரையைச் சுற்றி இழுக்கவும். எல்லா எண்களும் அலகுகளும் உள்ளே இருக்கட்டும்."))
                Surface(
                    Modifier.fillMaxWidth().height(260.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Black
                ) {
                    Box(Modifier.fillMaxSize().onSizeChanged { bounds = it }) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize().pointerInput(bitmap, bounds) {
                        var active = -1
                        detectDragGestures(onDragStart = { point ->
                            active = corners.indices.minByOrNull { i -> (Offset(corners[i].x*bounds.width,corners[i].y*bounds.height)-point).getDistance() } ?: -1
                        }, onDragEnd = { active = -1 }, onDragCancel = { active = -1 }) { change, drag ->
                            if (active >= 0 && bounds.width > 0 && bounds.height > 0) {
                                change.consume()
                                val next = corners[active] + Offset(drag.x/bounds.width,drag.y/bounds.height)
                                corners = corners.toMutableList().also { it[active] = Offset(next.x.coerceIn(0f,1f),next.y.coerceIn(0f,1f)) }
                            }
                        }
                    }) {
                        val points = corners.map { Offset(it.x*size.width,it.y*size.height) }
                        for (cell in 1..2) {
                            val f = cell / 3f
                            drawLine(Color.Red, Offset(points[0].x+(points[1].x-points[0].x)*f, points[0].y+(points[1].y-points[0].y)*f), Offset(points[3].x+(points[2].x-points[3].x)*f, points[3].y+(points[2].y-points[3].y)*f), 2.dp.toPx())
                            drawLine(Color.Red, Offset(points[0].x+(points[3].x-points[0].x)*f, points[0].y+(points[3].y-points[0].y)*f), Offset(points[1].x+(points[2].x-points[1].x)*f, points[1].y+(points[2].y-points[1].y)*f), 2.dp.toPx())
                        }
                        points.indices.forEach { i ->
                            drawLine(Color.Red,points[i],points[(i+1)%4],3.dp.toPx())
                            drawCircle(Color.White,11.dp.toPx(),points[i])
                            drawCircle(Color(0xFF1746A2),8.dp.toPx(),points[i])
                        }
                    }
                    }
                }
                if(error) Text(AppLanguage.text("Keep the corners in order around the display.", "Pastikan penjuru mengikut turutan.", "请按顺序框住显示屏，边线不要交叉。", "திரையைச் சுற்றி மூலைகளை வரிசையாக வைத்திருங்கள்."), color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { bitmap = Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply { postRotate(90f) },true) },
                        modifier = Modifier.weight(1f)
                    ) { Text(AppLanguage.text("Rotate", "Putar", "旋转", "சுழற்று")) }
                    OutlinedButton(
                        onClick = { corners = listOf(Offset.Zero,Offset(1f,0f),Offset(1f,1f),Offset(0f,1f));error=false },
                        modifier = Modifier.weight(1f)
                    ) { Text(AppLanguage.text("Full image", "Imej penuh", "完整图片", "முழுப் படம்")) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(AppLanguage.text("Cancel", "Batal", "取消", "ரத்து")) }
                    Button(onClick = {
                        val points = corners.map { Offset(it.x*bitmap.width,it.y*bitmap.height) }
                        val crosses = points.indices.map { i -> val a=points[(i+1)%4]-points[i];val b=points[(i+2)%4]-points[(i+1)%4]; a.x*b.y-a.y*b.x }
                        if(crosses.any { it <= 0f }) { error=true; return@Button }
                        val width=max((points[1]-points[0]).getDistance(),(points[2]-points[3]).getDistance()).toInt()
                        val height=max((points[3]-points[0]).getDistance(),(points[2]-points[1]).getDistance()).toInt()
                        if(width<60 || height<40) { error=true; return@Button }
                        val scale=min(1f,1800f/max(width,height))
                        val w=(width*scale).toInt().coerceAtLeast(1);val h=(height*scale).toInt().coerceAtLeast(1)
                        val matrix=Matrix()
                        val source=points.flatMap { listOf(it.x,it.y) }.toFloatArray()
                        if(!matrix.setPolyToPoly(source,0,floatArrayOf(0f,0f,w.toFloat(),0f,w.toFloat(),h.toFloat(),0f,h.toFloat()),0,4)) { error=true; return@Button }
                        val result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(result).apply { drawColor(android.graphics.Color.WHITE);drawBitmap(bitmap,matrix,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)) }
                        onScan(result)
                    }) { Text(AppLanguage.text("Scan", "Imbas", "识别", "ஸ்கேன்")) }
                }
            }
        }
    }
}
