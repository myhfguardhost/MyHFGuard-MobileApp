package com.vitalink.app.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure Kotlin parser used by [MachineOcrScanner]. Keeping the interpretation
 * separate from ML Kit makes the rules testable and prevents a weak OCR pass
 * from silently filling the health form with an implausible value.
 */
internal data class OcrLineData(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

internal enum class OcrRegion {
    FULL,
    DISPLAY,
    SYSTOLIC,
    DIASTOLIC,
    PULSE,
    WEIGHT
}

internal data class OcrPageData(
    val text: String,
    val lines: List<OcrLineData>,
    val region: OcrRegion = OcrRegion.FULL
)

internal object MachineOcrParser {
    data class ParsedWeight(val valueKg: Double, val rawText: String)
    data class ParsedBloodPressure(val systolic: Int, val diastolic: Int, val pulse: Int, val rawText: String)

    private data class WeightCandidate(
        val value: Double,
        var score: Int = 0,
        var unitVotes: Int = 0,
        val pages: MutableSet<Int> = mutableSetOf(),
        var raw: String = ""
    )

    private data class BpCandidate(
        val systolic: Int,
        val diastolic: Int,
        val pulse: Int,
        var score: Int = 0,
        var labelledVotes: Int = 0,
        val pages: MutableSet<Int> = mutableSetOf(),
        var raw: String = ""
    )

    private data class RegionalValueVote(
        var score: Int = 0,
        val pages: MutableSet<Int> = mutableSetOf(),
        var raw: String = ""
    )

    fun parseWeight(pages: List<OcrPageData>): ParsedWeight? {
        val candidates = mutableMapOf<Int, WeightCandidate>()

        pages.forEachIndexed { pageIndex, page ->
            val lines = page.lines.ifEmpty {
                page.text.lines().filter { it.isNotBlank() }.mapIndexed { index, text ->
                    OcrLineData(text = text, top = index * 10, bottom = index * 10 + 8)
                }
            }
            val tallest = lines.maxOfOrNull { it.height }?.coerceAtLeast(1) ?: 1
            val pageBest = mutableMapOf<Int, Triple<Double, Int, Boolean>>()

            lines.forEachIndexed { lineIndex, line ->
                val previous = lines.getOrNull(lineIndex - 1)?.text.orEmpty()
                val next = lines.getOrNull(lineIndex + 1)?.text.orEmpty()
                val nearbyText = "$previous ${line.text} $next"
                val sameLineKg = hasKg(line.text)
                val nearbyKg = sameLineKg || hasKg(previous) || hasKg(next)
                val hasPounds = hasPounds(line.text) || (!sameLineKg && hasPounds(nearbyText))
                val metadata = isWeightMetadata(line.text)
                val largeTextBonus = when {
                    line.height >= tallest * 0.75 -> 4
                    line.height >= tallest * 0.5 -> 2
                    else -> 0
                }

                weightValues(line.text, allowSplitDecimal = nearbyKg).forEach { (value, hasDecimal) ->
                    if (hasPounds && !sameLineKg) return@forEach
                    var localScore = 2 + largeTextBonus
                    if (sameLineKg) localScore += 14
                    else if (nearbyKg) localScore += 6
                    if (hasDecimal) localScore += 4
                    if (value in 30.0..200.0) localScore += 2
                    if (metadata && !sameLineKg) localScore -= 14
                    if (line.text.contains('%') && !sameLineKg) localScore -= 12

                    val key = weightKey(value)
                    val previousBest = pageBest[key]
                    if (previousBest == null || localScore > previousBest.second) {
                        pageBest[key] = Triple(value, localScore, sameLineKg || nearbyKg)
                    }
                }
            }

            // Some LCD displays are returned as one block without useful line boxes.
            // Give those values a small vote, but never let this weak pass overpower
            // a candidate that is repeatedly seen beside KG.
            weightValues(page.text, allowSplitDecimal = hasKg(page.text)).forEach { (value, hasDecimal) ->
                val key = weightKey(value)
                val weakScore = 1 + (if (hasKg(page.text)) 4 else 0) + (if (hasDecimal) 2 else 0)
                val previousBest = pageBest[key]
                if (previousBest == null || weakScore > previousBest.second) {
                    pageBest[key] = Triple(value, weakScore, hasKg(page.text))
                }
            }

            pageBest.forEach { (key, local) ->
                val candidate = candidates.getOrPut(key) { WeightCandidate(local.first) }
                candidate.score += local.second
                candidate.pages += pageIndex
                if (local.third) candidate.unitVotes += 1
                if (candidate.raw.isBlank() || local.second >= 10) candidate.raw = page.text
            }
        }

        val ranked = candidates.values
            .onEach { candidate ->
                candidate.score += candidate.pages.size * 5
                candidate.score += candidate.unitVotes * 3
            }
            .sortedByDescending { it.score }

        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        val confident = when {
            best.unitVotes >= 1 && best.score >= 18 -> true
            best.pages.size >= 3 && best.score >= 22 -> true
            best.pages.size >= 2 && best.score >= 20 && (runnerUp == null || best.score - runnerUp.score >= 4) -> true
            else -> false
        }
        if (!confident) return null
        if (runnerUp != null && abs(best.score - runnerUp.score) < maxOf(4, best.score / 10)) return null

        return ParsedWeight(best.value, best.raw)
    }

    fun parseBloodPressure(pages: List<OcrPageData>): ParsedBloodPressure? {
        val candidates = mutableMapOf<String, BpCandidate>()
        val regionalVotes = mutableMapOf<OcrRegion, MutableMap<Int, RegionalValueVote>>()

        pages.forEachIndexed { pageIndex, page ->
            collectRegionalVotes(pageIndex, page, regionalVotes)
            val lines = page.lines.ifEmpty {
                page.text.lines().filter { it.isNotBlank() }.mapIndexed { index, text ->
                    OcrLineData(text = text, top = index * 10, bottom = index * 10 + 8)
                }
            }.sortedWith(compareBy<OcrLineData> { it.top }.thenBy { it.left })

            val local = mutableMapOf<String, Pair<BpCandidate, Int>>()

            fun add(sys: Int, dia: Int, pulse: Int, score: Int, labelled: Boolean) {
                if (!validBloodPressure(sys, dia, pulse)) return
                val key = "$sys/$dia/$pulse"
                val existing = local[key]
                val candidate = BpCandidate(sys, dia, pulse, raw = page.text)
                candidate.labelledVotes = if (labelled) 1 else 0
                if (existing == null || score > existing.second) local[key] = candidate to score
            }

            val labelledSys = findLabelledValue(lines, LabelKind.SYSTOLIC, 40..260)
            val labelledDia = findLabelledValue(lines, LabelKind.DIASTOLIC, 25..160)
            val labelledPulse = findLabelledValue(lines, LabelKind.PULSE, 30..220)
            if (labelledSys != null && labelledDia != null && labelledPulse != null) {
                add(labelledSys, labelledDia, labelledPulse, 42, labelled = true)
            }

            // Common textual form: 120/80 and a pulse value nearby.
            slashCandidates(page.text).forEach { triple ->
                add(triple.first, triple.second, triple.third, 28, labelled = false)
            }

            // BP monitors normally show SYS, DIA and pulse vertically in that order.
            val lineValues = lines.mapIndexedNotNull { index, line ->
                val values = integerValues(line.text).filter { it in 25..260 }
                val selected = values.maxByOrNull { value ->
                    when {
                        value in 40..260 && hasSysLabel(line.text) -> 5
                        value in 25..160 && hasDiaLabel(line.text) -> 5
                        value in 30..220 && hasPulseLabel(line.text) -> 5
                        else -> 1
                    }
                }
                selected?.let { IndexedValue(index, it) }
            }

            for (i in 0 until lineValues.size) {
                for (j in i + 1 until lineValues.size) {
                    for (k in j + 1 until lineValues.size) {
                        val sys = lineValues[i].value
                        val dia = lineValues[j].value
                        val pulse = lineValues[k].value
                        if (!validBloodPressure(sys, dia, pulse)) continue
                        var score = 14
                        if (sys in 90..180) score += 3
                        if (dia in 50..110) score += 3
                        if (pulse in 45..140) score += 2
                        if (hasSysLabel(lines[lineValues[i].index].text)) score += 5
                        if (hasDiaLabel(lines[lineValues[j].index].text)) score += 5
                        if (hasPulseLabel(lines[lineValues[k].index].text)) score += 5
                        add(sys, dia, pulse, score, labelled = score >= 29)
                    }
                }
            }

            // Fallback for OCR engines that flatten all values into one line. Only
            // evaluate consecutive triples; trying every combination causes dates,
            // clocks and model numbers to be mistaken for BP readings.
            val flattened = integerValues(page.text).filter { it in 25..260 }
            for (index in 0..(flattened.size - 3).coerceAtLeast(-1)) {
                if (index < 0 || index + 2 >= flattened.size) break
                val sys = flattened[index]
                val dia = flattened[index + 1]
                val pulse = flattened[index + 2]
                var score = 7
                if (sys in 90..180) score += 2
                if (dia in 50..110) score += 2
                if (pulse in 45..140) score += 1
                add(sys, dia, pulse, score, labelled = false)
            }

            local.forEach { (key, pair) ->
                val localCandidate = pair.first
                val localScore = pair.second
                val candidate = candidates.getOrPut(key) {
                    BpCandidate(localCandidate.systolic, localCandidate.diastolic, localCandidate.pulse)
                }
                candidate.score += localScore
                candidate.pages += pageIndex
                candidate.labelledVotes += localCandidate.labelledVotes
                if (candidate.raw.isBlank() || localScore >= 28) candidate.raw = page.text
            }
        }

        addPositionAwareBloodPressureCandidates(candidates, regionalVotes)

        val ranked = candidates.values
            .onEach { candidate ->
                candidate.score += candidate.pages.size * 6
                candidate.score += candidate.labelledVotes * 8
            }
            .sortedByDescending { it.score }

        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        val confident = when {
            // A complete three-row seven-segment monitor can be correctly read
            // from one sharp pass. Requiring several variant passes caused clear
            // photos to be thrown away before the patient could verify them.
            best.labelledVotes >= 1 && best.score >= 22 -> true
            best.pages.size >= 1 && best.score >= 20 && (runnerUp == null || best.score - runnerUp.score >= 3) -> true
            best.pages.size >= 2 && best.score >= 34 && (runnerUp == null || best.score - runnerUp.score >= 5) -> true
            best.pages.size >= 3 && best.score >= 40 -> true
            else -> false
        }
        if (!confident) return null
        if (runnerUp != null && best.score >= 30 && best.score - runnerUp.score < maxOf(4, best.score / 12)) return null

        return ParsedBloodPressure(best.systolic, best.diastolic, best.pulse, best.raw)
    }


    private fun collectRegionalVotes(
        pageIndex: Int,
        page: OcrPageData,
        votes: MutableMap<OcrRegion, MutableMap<Int, RegionalValueVote>>
    ) {
        val range = when (page.region) {
            OcrRegion.SYSTOLIC -> 40..260
            OcrRegion.DIASTOLIC -> 25..160
            OcrRegion.PULSE -> 30..220
            else -> return
        }

        val pageCandidates = regionalNumericCandidates(page.text, range)
        if (pageCandidates.isEmpty()) return

        val pageMap = votes.getOrPut(page.region) { mutableMapOf() }
        pageCandidates.forEachIndexed { index, value ->
            var score = 8 - index.coerceAtMost(4)
            score += when (page.region) {
                OcrRegion.SYSTOLIC -> when (value) {
                    in 90..180 -> 5
                    in 70..220 -> 2
                    else -> 0
                }
                OcrRegion.DIASTOLIC -> when (value) {
                    in 50..110 -> 5
                    in 40..140 -> 2
                    else -> 0
                }
                OcrRegion.PULSE -> when (value) {
                    in 45..140 -> 4
                    in 35..180 -> 2
                    else -> 0
                }
                else -> 0
            }
            if (value >= 100 && page.region == OcrRegion.SYSTOLIC) score += 2
            if (value < 100 && page.region != OcrRegion.SYSTOLIC) score += 1

            val vote = pageMap.getOrPut(value) { RegionalValueVote() }
            vote.score += score
            vote.pages += pageIndex
            if (vote.raw.isBlank()) vote.raw = page.text
        }
    }

    /**
     * Reconstructs LCD values that ML Kit may split into separate groups, such
     * as "1 28" for 128 or "8 2" for 82.
     */
    private fun regionalNumericCandidates(text: String, range: IntRange): List<Int> {
        val normalized = numericText(text)
        val groups = Regex("\\d{1,3}")
            .findAll(normalized)
            .map { it.value }
            .toList()

        val ranked = linkedSetOf<Int>()
        groups.forEach { group ->
            group.toIntOrNull()?.takeIf { it in range }?.let(ranked::add)
        }
        for (index in groups.indices) {
            val first = groups[index]
            for (nextIndex in index + 1..minOf(index + 2, groups.lastIndex)) {
                val combined = first + groups[nextIndex]
                if (combined.length <= 3) {
                    combined.toIntOrNull()?.takeIf { it in range }?.let(ranked::add)
                }
            }
        }
        return ranked.toList()
    }

    private fun addPositionAwareBloodPressureCandidates(
        candidates: MutableMap<String, BpCandidate>,
        votes: Map<OcrRegion, Map<Int, RegionalValueVote>>
    ) {
        val sysVotes = votes[OcrRegion.SYSTOLIC].orEmpty()
            .entries.sortedByDescending { it.value.score }.take(4)
        val diaVotes = votes[OcrRegion.DIASTOLIC].orEmpty()
            .entries.sortedByDescending { it.value.score }.take(4)
        val pulseVotes = votes[OcrRegion.PULSE].orEmpty()
            .entries.sortedByDescending { it.value.score }.take(4)

        if (sysVotes.isEmpty() || diaVotes.isEmpty() || pulseVotes.isEmpty()) return

        sysVotes.forEach sysLoop@{ sys ->
            diaVotes.forEach diaLoop@{ dia ->
                pulseVotes.forEach pulseLoop@{ pulse ->
                    if (!validBloodPressure(sys.key, dia.key, pulse.key)) return@pulseLoop

                    val key = "${sys.key}/${dia.key}/${pulse.key}"
                    val candidate = candidates.getOrPut(key) {
                        BpCandidate(sys.key, dia.key, pulse.key)
                    }
                    candidate.score += 32 + sys.value.score + dia.value.score + pulse.value.score
                    candidate.pages += sys.value.pages
                    candidate.pages += dia.value.pages
                    candidate.pages += pulse.value.pages
                    candidate.labelledVotes += 1
                    if (candidate.raw.isBlank()) {
                        candidate.raw = listOf(
                            "SYS ${sys.value.raw}",
                            "DIA ${dia.value.raw}",
                            "PULSE ${pulse.value.raw}"
                        ).joinToString("\n")
                    }
                }
            }
        }
    }

    private enum class LabelKind { SYSTOLIC, DIASTOLIC, PULSE }

    private fun findLabelledValue(lines: List<OcrLineData>, kind: LabelKind, range: IntRange): Int? {
        fun matches(text: String): Boolean = when (kind) {
            LabelKind.SYSTOLIC -> hasSysLabel(text)
            LabelKind.DIASTOLIC -> hasDiaLabel(text)
            LabelKind.PULSE -> hasPulseLabel(text)
        }

        lines.forEachIndexed { index, labelLine ->
            if (!matches(labelLine.text)) return@forEachIndexed

            integerValues(labelLine.text).firstOrNull { it in range }?.let { return it }

            val labelCenterY = (labelLine.top + labelLine.bottom) / 2
            val spatialCandidate = lines
                .asSequence()
                .filterIndexed { candidateIndex, _ -> candidateIndex != index }
                .flatMap { candidate ->
                    integerValues(candidate.text)
                        .filter { it in range }
                        .asSequence()
                        .map { value ->
                            val candidateCenterY = (candidate.top + candidate.bottom) / 2
                            val verticalDistance = abs(candidateCenterY - labelCenterY)
                            val horizontalPenalty = when {
                                candidate.right <= labelLine.left -> 0
                                candidate.left < labelLine.right -> 35
                                else -> 120
                            }
                            Triple(value, verticalDistance + horizontalPenalty, candidate.height)
                        }
                }
                .minWithOrNull(
                    compareBy<Triple<Int, Int, Int>> { it.second }
                        .thenByDescending { it.third }
                )

            if (spatialCandidate != null && spatialCandidate.second <= 180) {
                return spatialCandidate.first
            }

            // Bounding boxes are occasionally absent. Keep a small text-order
            // fallback for those OCR results.
            listOfNotNull(
                lines.getOrNull(index - 1),
                lines.getOrNull(index + 1),
                lines.getOrNull(index - 2),
                lines.getOrNull(index + 2)
            ).forEach { neighbour ->
                integerValues(neighbour.text).firstOrNull { it in range }?.let { return it }
            }
        }
        return null
    }

    private fun slashCandidates(text: String): List<Triple<Int, Int, Int>> {
        val normalized = numericText(text)
        val regex = Regex("(?<!\\d)(\\d{2,3})\\s*/\\s*(\\d{2,3})(?:\\D{1,8}(\\d{2,3}))?")
        val allValues = integerValues(text)
        return regex.findAll(normalized).mapNotNull { match ->
            val sys = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val dia = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val pulse = match.groupValues.getOrNull(3)?.toIntOrNull()
                ?: allValues.firstOrNull { it in 30..220 && it != sys && it != dia }
                ?: return@mapNotNull null
            if (validBloodPressure(sys, dia, pulse)) Triple(sys, dia, pulse) else null
        }.toList()
    }

    private fun validBloodPressure(sys: Int, dia: Int, pulse: Int): Boolean =
        sys in 40..260 && dia in 25..160 && pulse in 30..220 && sys > dia && (sys - dia) >= 15

    private fun weightValues(text: String, allowSplitDecimal: Boolean): List<Pair<Double, Boolean>> {
        val normalized = numericText(text)
        val values = mutableListOf<Pair<Double, Boolean>>()
        Regex("(?<!\\d)(\\d{1,4}(?:\\.\\d{1,2})?)(?!\\d)").findAll(normalized).forEach { match ->
            val raw = match.groupValues[1]
            val parsed = raw.toDoubleOrNull() ?: return@forEach
            val candidates = when {
                parsed in 20.0..300.0 -> listOf(parsed)
                '.' !in raw && parsed / 10.0 in 20.0..300.0 -> listOf(parsed / 10.0)
                '.' !in raw && parsed / 100.0 in 20.0..300.0 -> listOf(parsed / 100.0)
                else -> emptyList()
            }
            candidates.forEach { value -> values += value to ('.' in raw || parsed > 300.0) }
        }

        if (allowSplitDecimal) {
            Regex("(?<!\\d)(\\d{2,3})\\s+(\\d)(?!\\d)").findAll(normalized).forEach { match ->
                val value = "${match.groupValues[1]}.${match.groupValues[2]}".toDoubleOrNull()
                if (value != null && value in 20.0..300.0) values += value to true
            }
        }
        return values.distinctBy { weightKey(it.first) }
    }

    private fun integerValues(text: String): List<Int> =
        Regex("(?<!\\d)(\\d{1,3})(?!\\d)")
            .findAll(numericText(text))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()

    /** Converts only OCR-like numeric glyphs, without changing label text. */
    private fun numericText(text: String): String = buildString(text.length) {
        text.forEach { char ->
            append(
                when (char) {
                    in '0'..'9' -> char
                    'O', 'o', 'Q', 'q', 'D', 'd' -> '0'
                    'I', 'i', 'L', 'l', '|', '!', 'ı' -> '1'
                    'Z', 'z' -> '2'
                    'S', 's' -> '5'
                    'G', 'g' -> '6'
                    'B', 'b' -> '8'
                    ',', '.' -> '.'
                    '/', ':', '-', ' ', '\n', '\t' -> char
                    else -> ' '
                }
            )
        }
    }

    private fun hasKg(text: String): Boolean {
        val upper = text.uppercase()
        return Regex("K\\s*[G689]").containsMatchIn(upper) || "KGS" in upper || "KILOGRAM" in upper
    }

    private fun hasPounds(text: String): Boolean {
        val upper = text.uppercase()
        return Regex("\\bL\\s*B(S)?\\b").containsMatchIn(upper) || "POUND" in upper
    }

    private fun isWeightMetadata(text: String): Boolean {
        val upper = text.uppercase()
        return listOf("BMI", "BODY FAT", "FAT", "WATER", "MUSCLE", "BONE", "TEMP", "°C", "KCAL").any { it in upper }
    }

    private fun hasSysLabel(text: String): Boolean {
        val upper = text.uppercase().replace(" ", "")
        return "SYS" in upper || "SYST" in upper || "5YS" in upper || "5Y5" in upper || "SY5" in upper
    }

    private fun hasDiaLabel(text: String): Boolean {
        val upper = text.uppercase().replace(" ", "")
        return "DIA" in upper || "D1A" in upper || "D|A" in upper || "DIAST" in upper
    }

    private fun hasPulseLabel(text: String): Boolean {
        val upper = text.uppercase().replace(" ", "")
        return "PULSE" in upper || "PUL" in upper || "PU1" in upper || "BPM" in upper ||
            Regex("(^|[^A-Z])PR([^A-Z]|$)").containsMatchIn(text.uppercase())
    }

    private fun weightKey(value: Double): Int = (value * 10.0).roundToInt()
}
