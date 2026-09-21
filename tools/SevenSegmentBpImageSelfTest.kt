package com.vitalink.app.util

import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val imageFile = File(args.firstOrNull() ?: "app/src/test/resources/vital_ocr_128_82_80.png")
    check(imageFile.exists()) { "Missing sample image: ${imageFile.path}" }
    val image = ImageIO.read(imageFile)
    val grey = IntArray(image.width * image.height)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            val r = (rgb shr 16) and 0xff
            val g = (rgb shr 8) and 0xff
            val b = rgb and 0xff
            grey[y * image.width + x] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        }
    }

    val result = checkNotNull(
        SevenSegmentBpRecognizerCore.recognize(
            SevenSegmentBpRecognizerCore.GreyImage(image.width, image.height, grey)
        )
    ) { "Seven-segment recognizer returned null" }

    check(result.systolic == 128) { "Expected SYS 128, got $result" }
    check(result.diastolic == 82) { "Expected DIA 82, got $result" }
    check(result.pulse == 80) { "Expected Pulse 80, got $result" }
    println("PASS: seven-segment image OCR = ${result.systolic}/${result.diastolic}, pulse ${result.pulse}, profile=${result.profileName}, score=${result.score}")
}
