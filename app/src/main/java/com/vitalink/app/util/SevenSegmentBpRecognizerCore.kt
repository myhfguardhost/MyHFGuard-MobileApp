package com.vitalink.app.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight seven-segment BP display reader that does not depend on ML Kit.
 *
 * Many home BP monitors use LCD digits that general text OCR does not read
 * reliably. This recognizer looks directly at the segment geometry in the
 * expected SYS / DIA / Pulse areas and is used as a local fallback/first pass.
 * It intentionally returns null when the geometry is ambiguous rather than
 * silently placing a questionable health reading into the form.
 */
internal object SevenSegmentBpRecognizerCore {
    data class GreyImage(
        val width: Int,
        val height: Int,
        val pixels: IntArray
    ) {
        init {
            require(width > 0 && height > 0)
            require(pixels.size >= width * height)
        }

        fun at(x: Int, y: Int): Int = pixels[y * width + x]
    }

    data class Result(
        val systolic: Int,
        val diastolic: Int,
        val pulse: Int,
        val score: Int,
        val profileName: String
    )

    private enum class RowKind { SYS, DIA, PULSE }

    private data class NormalizedRect(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double
    )

    private data class LayoutProfile(
        val name: String,
        val sys: NormalizedRect,
        val dia: NormalizedRect,
        val pulse: NormalizedRect
    )

    private data class Box(
        var left: Int,
        var top: Int,
        var right: Int,
        var bottom: Int,
        var pixels: Int
    ) {
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    private data class DigitGuess(
        val digit: Int,
        val error: Int,
        val box: Box
    )

    private data class RowGuess(
        val value: Int,
        val score: Int,
        val digitCount: Int,
        val error: Int
    )

    // Profile A matches the common product-photo/camera framing where the whole
    // monitor is visible. B/C cover closer photos where the LCD occupies more of
    // the image. D is deliberately a little wider for monitors with offset digits.
    private val profiles = listOf(
        LayoutProfile(
            "centred-monitor",
            NormalizedRect(0.43, 0.245, 0.75, 0.415),
            NormalizedRect(0.45, 0.395, 0.75, 0.595),
            NormalizedRect(0.55, 0.565, 0.75, 0.725)
        ),
        LayoutProfile(
            "close-monitor",
            NormalizedRect(0.34, 0.18, 0.78, 0.41),
            NormalizedRect(0.35, 0.38, 0.78, 0.65),
            NormalizedRect(0.50, 0.60, 0.78, 0.83)
        ),
        LayoutProfile(
            "display-filled",
            NormalizedRect(0.18, 0.08, 0.78, 0.37),
            NormalizedRect(0.18, 0.33, 0.78, 0.65),
            NormalizedRect(0.42, 0.61, 0.78, 0.91)
        ),
        LayoutProfile(
            "wide-centre",
            NormalizedRect(0.37, 0.22, 0.77, 0.43),
            NormalizedRect(0.38, 0.39, 0.77, 0.63),
            NormalizedRect(0.50, 0.57, 0.77, 0.76)
        ),
        LayoutProfile(
            "omron-compact-lcd",
            NormalizedRect(0.35, 0.255, 0.70, 0.420),
            NormalizedRect(0.39, 0.385, 0.70, 0.565),
            NormalizedRect(0.46, 0.495, 0.70, 0.665)
        ),
        LayoutProfile(
            "omron-compact-lcd-tight",
            NormalizedRect(0.38, 0.270, 0.68, 0.405),
            NormalizedRect(0.43, 0.400, 0.68, 0.515),
            NormalizedRect(0.50, 0.515, 0.68, 0.625)
        ),
        LayoutProfile(
            "smaller-monitor",
            NormalizedRect(0.45, 0.285, 0.70, 0.435),
            NormalizedRect(0.48, 0.415, 0.70, 0.565),
            NormalizedRect(0.56, 0.555, 0.70, 0.665)
        )
    )

    // Segment order: top, upper-left, upper-right, middle,
    // lower-left, lower-right, bottom.
    private val digitPatterns = mapOf(
        0 to intArrayOf(1, 1, 1, 0, 1, 1, 1),
        1 to intArrayOf(0, 0, 1, 0, 0, 1, 0),
        2 to intArrayOf(1, 0, 1, 1, 1, 0, 1),
        3 to intArrayOf(1, 0, 1, 1, 0, 1, 1),
        4 to intArrayOf(0, 1, 1, 1, 0, 1, 0),
        5 to intArrayOf(1, 1, 0, 1, 0, 1, 1),
        6 to intArrayOf(1, 1, 0, 1, 1, 1, 1),
        7 to intArrayOf(1, 0, 1, 0, 0, 1, 0),
        8 to intArrayOf(1, 1, 1, 1, 1, 1, 1),
        9 to intArrayOf(1, 1, 1, 1, 0, 1, 1)
    )

    fun recognize(image: GreyImage): Result? {
        val images = buildList {
            add(image)
            // Gallery photos can still be stored sideways. Try both 90-degree
            // rotations, but keep the normal orientation strongly preferred.
            add(rotate90(image))
            add(rotate270(image))
        }

        val results = mutableListOf<Result>()
        images.forEachIndexed { orientationIndex, oriented ->
            profiles.forEach { profile ->
                recognizeProfile(oriented, profile)?.let { result ->
                    val orientationPenalty = if (orientationIndex == 0) 0 else 8
                    results += result.copy(score = result.score - orientationPenalty)
                }
            }
        }

        val ranked = results.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)

        // If two strong layouts disagree almost equally, do not guess.
        if (runnerUp != null &&
            best.systolic == runnerUp.systolic &&
            best.diastolic == runnerUp.diastolic &&
            best.pulse == runnerUp.pulse
        ) {
            return best.copy(score = best.score + 4)
        }
        if (runnerUp != null && best.score - runnerUp.score < 5) return null
        return if (best.score >= 45) best else null
    }

    private fun recognizeProfile(image: GreyImage, profile: LayoutProfile): Result? {
        val sys = decodeRow(crop(image, profile.sys) ?: return null, RowKind.SYS) ?: return null
        val dia = decodeRow(crop(image, profile.dia) ?: return null, RowKind.DIA) ?: return null
        val pulse = decodeRow(crop(image, profile.pulse) ?: return null, RowKind.PULSE) ?: return null

        if (!validBloodPressure(sys.value, dia.value, pulse.value)) return null

        var score = sys.score + dia.score + pulse.score
        if (sys.value in 90..180) score += 5
        if (dia.value in 50..110) score += 5
        if (pulse.value in 45..140) score += 4
        if (sys.error == 0 && dia.error == 0 && pulse.error == 0) score += 6

        return Result(sys.value, dia.value, pulse.value, score, profile.name)
    }

    private fun decodeRow(image: GreyImage, kind: RowKind): RowGuess? {
        val threshold = otsuThreshold(image).coerceIn(25, 200)
        val dark = BooleanArray(image.width * image.height) { index ->
            image.pixels[index] < threshold
        }
        val bright = BooleanArray(image.width * image.height) { index ->
            image.pixels[index] > threshold
        }

        val darkGuess = decodeBinaryRow(image.width, image.height, dark, kind)
        val brightGuess = decodeBinaryRow(image.width, image.height, bright, kind)

        return listOfNotNull(darkGuess, brightGuess)
            .maxByOrNull { it.score }
    }

    private fun decodeBinaryRow(
        width: Int,
        height: Int,
        mask: BooleanArray,
        kind: RowKind
    ): RowGuess? {
        if (width < 25 || height < 20) return null

        // Remove display-frame/bezel components before merging. A long LCD
        // border can overlap every digit horizontally and previously caused all
        // digits to be merged into one unusable component (common on OMRON
        // monitors and photos where the complete LCD rectangle is visible).
        val boxes = connectedBoxes(width, height, mask)
            .filter { box ->
                val widthRatio = box.width.toDouble() / width
                val heightRatio = box.height.toDouble() / height
                val nearLeftEdge = box.left <= width * 0.06
                val nearRightEdge = box.right >= width * 0.94
                val nearTopEdge = box.top <= height * 0.04
                val nearBottomEdge = box.bottom >= height * 0.96

                val horizontalFrame = widthRatio >= 0.72 && heightRatio >= 0.05
                val verticalFrame = heightRatio >= 0.82 && (nearLeftEdge || nearRightEdge)
                val edgeFrame = (nearTopEdge || nearBottomEdge) && widthRatio >= 0.62
                !(horizontalFrame || verticalFrame || edgeFrame)
            }
        if (boxes.isEmpty()) return null
        val groups = mergeBoxesByHorizontalOverlap(boxes, width)

        val digitGroups = groups
            .filter { group ->
                val heightRatio = group.height.toDouble() / height
                val widthRatio = group.width.toDouble() / width
                heightRatio >= 0.38 && widthRatio <= 0.48 && group.pixels >= 6
            }
            .sortedBy { it.left }

        if (digitGroups.size < 2) return null

        val decoded = digitGroups.mapNotNull { box -> decodeDigit(width, height, mask, box) }
        if (decoded.size < 2) return null

        val range = when (kind) {
            RowKind.SYS -> 70..260
            RowKind.DIA -> 35..160
            RowKind.PULSE -> 35..220
        }

        val candidates = mutableListOf<RowGuess>()
        for (start in decoded.indices) {
            for (count in 2..3) {
                if (start + count > decoded.size) continue
                val sequence = decoded.subList(start, start + count)
                if (sequence.first().digit == 0) continue
                val value = sequence.fold(0) { total, digit -> total * 10 + digit.digit }
                if (value !in range) continue

                val error = sequence.sumOf { it.error }
                if (error > 2) continue

                val tallest = sequence.maxOf { it.box.height }
                val shortest = sequence.minOf { it.box.height }
                val heightConsistency = if (tallest == 0) 0.0 else shortest.toDouble() / tallest
                if (heightConsistency < 0.45) continue

                var score = 24 - error * 6
                score += when (kind) {
                    RowKind.SYS -> if (value in 90..180) 8 else 3
                    RowKind.DIA -> if (value in 50..110) 8 else 3
                    RowKind.PULSE -> if (value in 45..140) 7 else 3
                }
                if (heightConsistency >= 0.78) score += 4
                if (sequence.all { it.error == 0 }) score += 5

                // Prefer the visually largest digit run; tiny clock/memory digits
                // should lose even if they accidentally make a plausible number.
                val averageHeightRatio = sequence.map { it.box.height.toDouble() / height }.average()
                score += (averageHeightRatio * 12.0).toInt().coerceIn(0, 12)

                candidates += RowGuess(value, score, count, error)
            }
        }

        val ranked = candidates.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && best.value != runnerUp.value && best.score - runnerUp.score < 3) return null
        return best
    }

    private fun connectedBoxes(width: Int, height: Int, mask: BooleanArray): List<Box> {
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val result = mutableListOf<Box>()
        val minPixels = max(3, (width * height * 0.00025).toInt())
        val minHeight = max(2, (height * 0.06).toInt())

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var stackSize = 0
            stack[stackSize++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var count = 0

            while (stackSize > 0) {
                val index = stack[--stackSize]
                val x = index % width
                val y = index / width
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + 1)
                bottom = max(bottom, y + 1)
                count++

                fun visit(nx: Int, ny: Int) {
                    if (nx !in 0 until width || ny !in 0 until height) return
                    val next = ny * width + nx
                    if (!mask[next] || visited[next]) return
                    visited[next] = true
                    stack[stackSize++] = next
                }

                visit(x - 1, y)
                visit(x + 1, y)
                visit(x, y - 1)
                visit(x, y + 1)
            }

            if (count >= minPixels && bottom - top >= minHeight) {
                result += Box(left, top, right, bottom, count)
            }
        }
        return result
    }

    private fun mergeBoxesByHorizontalOverlap(boxes: List<Box>, rowWidth: Int): List<Box> {
        val sorted = boxes.sortedBy { it.left }
        val groups = mutableListOf<Box>()
        val tolerance = max(1, (rowWidth * 0.008).toInt())

        sorted.forEach { box ->
            var merged = false
            for (group in groups) {
                val overlaps = min(group.right, box.right) - max(group.left, box.left) >= -tolerance
                if (overlaps) {
                    group.left = min(group.left, box.left)
                    group.top = min(group.top, box.top)
                    group.right = max(group.right, box.right)
                    group.bottom = max(group.bottom, box.bottom)
                    group.pixels += box.pixels
                    merged = true
                    break
                }
            }
            if (!merged) groups += box.copy()
        }

        // First-pass merges can create a bridge that overlaps a later group.
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in groups.indices) {
                for (j in i + 1 until groups.size) {
                    val a = groups[i]
                    val b = groups[j]
                    if (min(a.right, b.right) - max(a.left, b.left) >= -tolerance) {
                        a.left = min(a.left, b.left)
                        a.top = min(a.top, b.top)
                        a.right = max(a.right, b.right)
                        a.bottom = max(a.bottom, b.bottom)
                        a.pixels += b.pixels
                        groups.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }

        return groups
    }

    private fun decodeDigit(
        rowWidth: Int,
        rowHeight: Int,
        mask: BooleanArray,
        box: Box
    ): DigitGuess? {
        val aspect = box.width.toDouble() / box.height

        // Seven-segment 1 is much narrower than every other digit. Both right
        // vertical segments may be disconnected, but their x-ranges overlap and
        // are merged above.
        if (aspect < 0.34 && box.height >= rowHeight * 0.42) {
            return DigitGuess(1, 0, box)
        }

        if (aspect < 0.34 || aspect > 1.05) return null

        val zones = arrayOf(
            doubleArrayOf(0.18, 0.00, 0.82, 0.20),
            doubleArrayOf(0.00, 0.08, 0.28, 0.48),
            doubleArrayOf(0.72, 0.08, 1.00, 0.48),
            doubleArrayOf(0.18, 0.40, 0.82, 0.60),
            doubleArrayOf(0.00, 0.52, 0.28, 0.92),
            doubleArrayOf(0.72, 0.52, 1.00, 0.92),
            doubleArrayOf(0.18, 0.80, 0.82, 1.00)
        )

        val observed = IntArray(7)
        zones.forEachIndexed { index, zone ->
            val x1 = (box.left + box.width * zone[0]).toInt().coerceIn(box.left, box.right - 1)
            val y1 = (box.top + box.height * zone[1]).toInt().coerceIn(box.top, box.bottom - 1)
            val x2 = (box.left + box.width * zone[2]).toInt().coerceIn(x1 + 1, box.right)
            val y2 = (box.top + box.height * zone[3]).toInt().coerceIn(y1 + 1, box.bottom)
            var on = 0
            var area = 0
            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    area++
                    if (mask[y * rowWidth + x]) on++
                }
            }
            val occupancy = if (area == 0) 0.0 else on.toDouble() / area
            observed[index] = if (occupancy >= 0.18) 1 else 0
        }

        // A camera crop can make the top horizontal segment bleed slightly
        // into the upper-left sample zone and the lower-right segment bleed
        // into the bottom zone. This is especially common for the narrow "7"
        // used by OMRON LCDs. The defining features below are more reliable
        // than raw Hamming distance for that shape.
        if (observed[0] == 1 && observed[2] == 1 && observed[3] == 0 &&
            observed[4] == 0 && observed[5] == 1
        ) {
            return DigitGuess(7, 0, box)
        }

        var bestDigit = -1
        var bestError = Int.MAX_VALUE
        digitPatterns.forEach { (digit, pattern) ->
            var error = 0
            for (index in 0 until 7) {
                if (observed[index] != pattern[index]) error++
            }
            if (error < bestError) {
                bestError = error
                bestDigit = digit
            }
        }

        if (bestDigit < 0 || bestError > 1) return null
        return DigitGuess(bestDigit, bestError, box)
    }

    private fun crop(image: GreyImage, rect: NormalizedRect): GreyImage? {
        val left = (image.width * rect.left).toInt().coerceIn(0, image.width - 1)
        val top = (image.height * rect.top).toInt().coerceIn(0, image.height - 1)
        val right = (image.width * rect.right).toInt().coerceIn(left + 1, image.width)
        val bottom = (image.height * rect.bottom).toInt().coerceIn(top + 1, image.height)
        val width = right - left
        val height = bottom - top
        if (width < 20 || height < 18) return null

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val sourceOffset = (top + y) * image.width + left
            image.pixels.copyInto(pixels, y * width, sourceOffset, sourceOffset + width)
        }
        return GreyImage(width, height, pixels)
    }

    private fun otsuThreshold(image: GreyImage): Int {
        val histogram = IntArray(256)
        image.pixels.forEach { value -> histogram[value.coerceIn(0, 255)]++ }
        val total = image.pixels.size.coerceAtLeast(1)
        var sum = 0.0
        histogram.indices.forEach { value -> sum += value * histogram[value].toDouble() }

        var backgroundWeight = 0
        var backgroundSum = 0.0
        var maxVariance = -1.0
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
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = value
            }
        }
        return threshold
    }

    private fun rotate90(image: GreyImage): GreyImage {
        val width = image.height
        val height = image.width
        val out = IntArray(width * height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val nx = image.height - 1 - y
                val ny = x
                out[ny * width + nx] = image.at(x, y)
            }
        }
        return GreyImage(width, height, out)
    }

    private fun rotate270(image: GreyImage): GreyImage {
        val width = image.height
        val height = image.width
        val out = IntArray(width * height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val nx = y
                val ny = image.width - 1 - x
                out[ny * width + nx] = image.at(x, y)
            }
        }
        return GreyImage(width, height, out)
    }

    private fun validBloodPressure(sys: Int, dia: Int, pulse: Int): Boolean =
        sys in 70..260 && dia in 35..160 && pulse in 35..220 && sys > dia && abs(sys - dia) >= 15
}
