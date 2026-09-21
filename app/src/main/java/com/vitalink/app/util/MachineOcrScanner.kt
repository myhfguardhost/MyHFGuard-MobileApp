package com.vitalink.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

object MachineOcrScanner {
    data class WeightResult(val valueKg: Double, val rawText: String)
    data class BloodPressureResult(val systolic: Int, val diastolic: Int, val pulse: Int, val rawText: String)

    fun scanWeight(
        bitmap: Bitmap,
        onSuccess: (WeightResult) -> Unit,
        onFailure: (String) -> Unit
    ) {
        recognizeVariants(bitmap, includeBloodPressureRegions = false) { pages, _ ->
            if (pages.isEmpty()) {
                onFailure(AppLanguage.text("The scale display could not be read. Keep it straight, close and free from glare, then try again.", "Paparan penimbang tidak dapat dibaca. Pastikan ia lurus, dekat dan tanpa silau, kemudian cuba lagi.", "无法读取体重秤显示屏，请正对屏幕、靠近拍摄并避免反光后重试。", "எடை அளவியின் திரையைப் படிக்க முடியவில்லை. நேராகவும் அருகிலும் ஒளிப்பிரதிபலிப்பு இல்லாமலும் வைத்து மீண்டும் முயலவும்."))
                return@recognizeVariants
            }
            val parsed = MachineOcrParser.parseWeight(pages)
            if (parsed == null) {
                onFailure("The weight could not be read confidently. Fill most of the photo with the display, keep KG visible, avoid glare, and try again.")
            } else {
                onSuccess(WeightResult(parsed.valueKg, parsed.rawText))
            }
        }
    }

    fun scanBloodPressure(
        bitmap: Bitmap,
        onSuccess: (BloodPressureResult) -> Unit,
        onFailure: (String) -> Unit
    ) {
        recognizeVariants(bitmap, includeBloodPressureRegions = true) { pages, _ ->
            if (pages.isEmpty()) {
                onFailure(AppLanguage.text("The blood pressure display could not be read. Keep it straight, close and free from glare, then try again.", "Paparan tekanan darah tidak dapat dibaca. Pastikan ia lurus, dekat dan tanpa silau, kemudian cuba lagi.", "无法读取血压计显示屏，请正对屏幕、靠近拍摄并避免反光后重试。", "இரத்த அழுத்த அளவியின் திரையைப் படிக்க முடியவில்லை. நேராகவும் அருகிலும் ஒளிப்பிரதிபலிப்பு இல்லாமலும் வைத்து மீண்டும் முயலவும்."))
                return@recognizeVariants
            }
            val parsed = MachineOcrParser.parseBloodPressure(pages)
            if (parsed == null) {
                onFailure("SYS, DIA and Pulse could not be read confidently. Fill most of the photo with the complete monitor display, avoid glare, and try again.")
            } else {
                onSuccess(BloodPressureResult(parsed.systolic, parsed.diastolic, parsed.pulse, parsed.rawText))
            }
        }
    }

    private data class OcrVariant(
        val bitmap: Bitmap,
        val region: OcrRegion
    )

    private fun recognizeVariants(
        original: Bitmap,
        includeBloodPressureRegions: Boolean,
        onComplete: (List<OcrPageData>, String?) -> Unit
    ) {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        worker.execute {
            try {
                val variants = buildVariants(original, includeBloodPressureRegions)
                main.post {
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val pages = mutableListOf<OcrPageData>()
                    var lastError: String? = null
                    fun next(index: Int) {
                        if (index == variants.size) {
                            recognizer.close()
                            variants.map { it.bitmap }.distinct().filter { it !== original }.forEach { if (!it.isRecycled) it.recycle() }
                            worker.shutdown()
                            onComplete(pages, lastError)
                            return
                        }
                        val variant = variants[index]
                        recognizer.process(InputImage.fromBitmap(variant.bitmap, 0))
                            .addOnSuccessListener { result ->
                                if (result.text.isNotBlank()) {
                                    val lines = result.textBlocks.flatMap { it.lines }.mapIndexed { i, line ->
                                        val box = line.boundingBox
                                        OcrLineData(line.text, box?.left ?: 0, box?.top ?: i*10, box?.right ?: 0, box?.bottom ?: i*10+8)
                                    }.sortedWith(compareBy<OcrLineData> { it.top }.thenBy { it.left })
                                    pages += OcrPageData(result.text, lines, variant.region)
                                }
                            }
                            .addOnFailureListener { lastError = it.message }
                            .addOnCompleteListener { next(index + 1) }
                    }
                    next(0)
                }
            } catch (error: Exception) {
                worker.shutdown()
                main.post { onComplete(emptyList(), error.message) }
            }
        }
    }

    private fun buildVariants(
        bitmap: Bitmap,
        includeBloodPressureRegions: Boolean
    ): List<OcrVariant> {
        val safe = resizeForOcr(bitmap, 1400)
        val centre = crop(safe, 0.03f, 0.05f, 0.97f, 0.95f)
        val display = crop(safe, 0.09f, 0.08f, 0.91f, 0.88f)
        val lcd = if (includeBloodPressureRegions) findLcdDisplay(safe) else null
        val contrastedSafe = enhanceContrast(safe)
        val contrastedCentre = enhanceContrast(centre ?: safe)
        val contrastedDisplay = enhanceContrast(display ?: centre ?: safe)
        val darkerDisplay = adjustGamma(contrastedDisplay, 0.72)
        val lighterDisplay = adjustGamma(contrastedDisplay, 1.38)

        return buildList {
            add(OcrVariant(safe, OcrRegion.FULL))
            centre?.let { add(OcrVariant(it, OcrRegion.DISPLAY)) }
            display?.let { add(OcrVariant(it, OcrRegion.DISPLAY)) }
            add(OcrVariant(contrastedSafe, OcrRegion.FULL))
            add(OcrVariant(contrastedCentre, OcrRegion.DISPLAY))
            add(OcrVariant(darkerDisplay, OcrRegion.DISPLAY))
            add(OcrVariant(lighterDisplay, OcrRegion.DISPLAY))
            add(OcrVariant(toBinary(contrastedDisplay, invert = false), OcrRegion.DISPLAY))
            add(OcrVariant(toBinary(contrastedDisplay, invert = true), OcrRegion.DISPLAY))
            lcd?.let {
                val contrastedLcd = enhanceContrast(it)
                add(OcrVariant(it, OcrRegion.DISPLAY))
                add(OcrVariant(contrastedLcd, OcrRegion.DISPLAY))
                add(OcrVariant(toBinary(contrastedLcd, invert = false), OcrRegion.DISPLAY))
                add(OcrVariant(toBinary(contrastedLcd, invert = true), OcrRegion.DISPLAY))
            }

            if (includeBloodPressureRegions) {
                // The review screen rectifies the monitor LCD into one image.  OCR of
                // the whole display often sees three seven-segment rows as unrelated
                // text, so read each row independently as well.  The wider fallback
                // also covers a user who leaves a little monitor bezel in the frame.
                lcd?.let { addBloodPressureRows(this, it) }
                addBloodPressureRows(this, safe)
                centre?.let { addBloodPressureRows(this, it) }
            }

            // Full-display rotations avoid assuming SYS/DIA occupy fixed photo coordinates.
            for (angle in listOf(90f, 180f, 270f)) {
                val rotated = Bitmap.createBitmap(safe, 0, 0, safe.width, safe.height,
                    android.graphics.Matrix().apply { postRotate(angle) }, true)
                add(OcrVariant(rotated, OcrRegion.FULL))
                add(OcrVariant(enhanceContrast(rotated), OcrRegion.FULL))
            }
        }
    }

    /** Finds the monitor's large dark neutral/green-grey LCD before OCR. */
    private fun findLcdDisplay(source: Bitmap): Bitmap? {
        val probe = if (max(source.width, source.height) > 520) resizeForOcr(source, 520) else source
        val width = probe.width; val height = probe.height
        if (width < 80 || height < 80) return null
        val pixels = IntArray(width * height)
        probe.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = BooleanArray(pixels.size) { index ->
            val pixel = pixels[index]; val red = Color.red(pixel); val green = Color.green(pixel); val blue = Color.blue(pixel)
            val luminance = (red * 30 + green * 59 + blue * 11) / 100
            luminance in 18..165 && max(red, max(green, blue)) - min(red, min(green, blue)) <= 92
        }
        val visited = BooleanArray(mask.size); val queue = IntArray(mask.size)
        var best: IntArray? = null
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0; var tail = 0; queue[tail++] = start; visited[start] = true
            var left = start % width; var right = left; var top = start / width; var bottom = top
            while (head < tail) {
                val point = queue[head++]; val x = point % width; val y = point / width
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
                fun add(next: Int) { if (mask[next] && !visited[next]) { visited[next] = true; queue[tail++] = next } }
                if (x > 0) add(point - 1); if (x < width - 1) add(point + 1)
                if (y > 0) add(point - width); if (y < height - 1) add(point + width)
            }
            val componentWidth = right - left + 1; val componentHeight = bottom - top + 1
            if (componentWidth < width * .16 || componentHeight < height * .12 || tail < width * height * .018) continue
            val distance = kotlin.math.abs((left + right) / 2f / width - .5f) + kotlin.math.abs((top + bottom) / 2f / height - .48f)
            val score = tail - (distance * width * height * .25f).toInt()
            if (best == null || score > best!![4]) best = intArrayOf(left, top, right, bottom, score)
        }
        val candidate = best ?: return null
        val scaleX = source.width.toFloat() / width; val scaleY = source.height.toFloat() / height
        val padX = ((candidate[2] - candidate[0] + 1) * .035f).toInt(); val padY = ((candidate[3] - candidate[1] + 1) * .035f).toInt()
        val left = ((candidate[0] - padX).coerceAtLeast(0) * scaleX).toInt(); val top = ((candidate[1] - padY).coerceAtLeast(0) * scaleY).toInt()
        val right = ((candidate[2] + padX + 1).coerceAtMost(width) * scaleX).toInt(); val bottom = ((candidate[3] + padY + 1).coerceAtMost(height) * scaleY).toInt()
        if (right - left < 100 || bottom - top < 100) return null
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun addBloodPressureRows(target: MutableList<OcrVariant>, display: Bitmap) {
        // BP monitors present SYS, DIA and pulse in this vertical order.  The two
        // profiles overlap deliberately: different Omron and wrist monitors leave
        // different amounts of label/bezel space above and below the digits.
        val profiles = listOf(
            floatArrayOf(0.05f, 0.03f, 0.97f, 0.35f, 0.05f, 0.31f, 0.97f, 0.66f, 0.05f, 0.62f, 0.97f, 0.98f),
            floatArrayOf(0.12f, 0.12f, 0.95f, 0.42f, 0.12f, 0.36f, 0.95f, 0.70f, 0.12f, 0.64f, 0.95f, 0.96f)
        )
        profiles.forEachIndexed { profileIndex, p ->
            target.addRegionVariants(display, p[0], p[1], p[2], p[3], OcrRegion.SYSTOLIC, if (profileIndex == 0) 1 else 2)
            target.addRegionVariants(display, p[4], p[5], p[6], p[7], OcrRegion.DIASTOLIC, if (profileIndex == 0) 1 else 2)
            target.addRegionVariants(display, p[8], p[9], p[10], p[11], OcrRegion.PULSE, if (profileIndex == 0) 1 else 2)
        }
    }

    private fun MutableList<OcrVariant>.addRegionVariants(
        source: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        region: OcrRegion,
        profile: Int
    ) {
        val cropped = crop(source, left, top, right, bottom) ?: return
        val enlarged = resizeForOcr(cropped, 1100)
        val contrasted = enhanceContrast(enlarged)

        // Keep the regional fallback accurate without launching too many large
        // OCR bitmaps at once. Profile 1 is the main monitor layout; profile 2
        // is a wider crop used when the screen occupies a different area.
        if (profile == 1) {
            add(OcrVariant(enlarged, region))
            add(OcrVariant(contrasted, region))
            add(OcrVariant(toBinary(contrasted, invert = false), region))
        } else {
            add(OcrVariant(contrasted, region))
            add(OcrVariant(adjustGamma(contrasted, 0.72), region))
        }
    }

    private fun resizeForOcr(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = when {
            longest > maxDimension -> maxDimension.toFloat() / longest
            longest < 900 -> 900f / longest
            else -> 1f
        }
        if (scale == 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true
        )
    }

    private fun crop(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap? {
        val x = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val y = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val width = (bitmap.width * (right - left)).toInt().coerceAtMost(bitmap.width - x)
        val height = (bitmap.height * (bottom - top)).toInt().coerceAtMost(bitmap.height - y)
        if (width < 100 || height < 100) return null
        return Bitmap.createBitmap(bitmap, x, y, width, height)
    }

    /** Contrast stretching works better than a fixed multiplier on pale LCD screens. */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val source = resizeForOcr(bitmap, 1400)
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)

        val histogram = IntArray(256)
        pixels.forEach { pixel -> histogram[grey(pixel)]++ }
        val total = pixels.size.coerceAtLeast(1)
        val lowTarget = (total * 0.02).toInt()
        val highTarget = (total * 0.98).toInt()
        var cumulative = 0
        var low = 0
        for (value in 0..255) {
            cumulative += histogram[value]
            if (cumulative >= lowTarget) {
                low = value
                break
            }
        }
        cumulative = 0
        var high = 255
        for (value in 0..255) {
            cumulative += histogram[value]
            if (cumulative >= highTarget) {
                high = value
                break
            }
        }
        if (high <= low + 12) {
            low = 40
            high = 215
        }

        for (index in pixels.indices) {
            val stretched = ((grey(pixels[index]) - low) * 255 / (high - low).coerceAtLeast(1)).coerceIn(0, 255)
            pixels[index] = Color.rgb(stretched, stretched, stretched)
        }
        output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return output
    }

    /**
     * LCD segments can be either much darker or much lighter than their background.
     * Gamma variants help ML Kit separate the segments without committing to one
     * fixed threshold that only works for a particular monitor or room lighting.
     */
    private fun adjustGamma(bitmap: Bitmap, gamma: Double): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val lookup = IntArray(256) { value ->
            (255.0 * Math.pow(value / 255.0, gamma)).toInt().coerceIn(0, 255)
        }
        for (index in pixels.indices) {
            val grey = lookup[grey(pixels[index])]
            pixels[index] = Color.rgb(grey, grey, grey)
        }
        output.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return output
    }

    private fun toBinary(bitmap: Bitmap, invert: Boolean): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val histogram = IntArray(256)
        pixels.forEach { pixel -> histogram[grey(pixel)]++ }
        val threshold = otsuThreshold(histogram, pixels.size)

        for (index in pixels.indices) {
            val dark = grey(pixels[index]) < threshold
            val value = if (dark.xor(invert)) 0 else 255
            pixels[index] = Color.rgb(value, value, value)
        }
        output.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return output
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sum = 0.0
        histogram.indices.forEach { value -> sum += value * histogram[value].toDouble() }
        var backgroundWeight = 0
        var backgroundSum = 0.0
        var maximumVariance = -1.0
        var threshold = 128

        histogram.indices.forEach { value ->
            backgroundWeight += histogram[value]
            if (backgroundWeight == 0) return@forEach
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) return threshold
            backgroundSum += value * histogram[value].toDouble()
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (sum - backgroundSum) / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > maximumVariance) {
                maximumVariance = variance
                threshold = value
            }
        }
        return threshold.coerceIn(55, 210)
    }

    private fun grey(pixel: Int): Int =
        (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
}
