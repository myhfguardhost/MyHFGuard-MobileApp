package com.vitalink.app.util

fun main() {
    val positionPages = listOf(
        OcrPageData("128 SYS mmHg", listOf(OcrLineData("128", 20, 20, 180, 110)), OcrRegion.SYSTOLIC),
        OcrPageData("1 28", listOf(OcrLineData("1 28", 20, 20, 180, 110)), OcrRegion.SYSTOLIC),
        OcrPageData("82 DIA mmHg", listOf(OcrLineData("82", 20, 20, 180, 110)), OcrRegion.DIASTOLIC),
        OcrPageData("8 2", listOf(OcrLineData("8 2", 20, 20, 180, 110)), OcrRegion.DIASTOLIC),
        OcrPageData("80 PUL /min", listOf(OcrLineData("80", 20, 20, 180, 110)), OcrRegion.PULSE),
        OcrPageData("8 0", listOf(OcrLineData("8 0", 20, 20, 180, 110)), OcrRegion.PULSE)
    )
    val parsed = checkNotNull(MachineOcrParser.parseBloodPressure(positionPages)) { "Position parser returned null" }
    check(parsed.systolic == 128) { "Expected SYS 128 but got $parsed" }
    check(parsed.diastolic == 82) { "Expected DIA 82 but got $parsed" }
    check(parsed.pulse == 80) { "Expected Pulse 80 but got $parsed" }

    val labelled = checkNotNull(MachineOcrParser.parseBloodPressure(
        listOf(
            OcrPageData(
                "128 SYS mmHg\n82 DIA mmHg\n80 PUL /min",
                listOf(
                    OcrLineData("128", 20, 20, 180, 105),
                    OcrLineData("SYS mmHg", 200, 35, 300, 80),
                    OcrLineData("82", 20, 130, 180, 215),
                    OcrLineData("DIA mmHg", 200, 145, 300, 190),
                    OcrLineData("80", 100, 235, 180, 290),
                    OcrLineData("PUL /min", 200, 240, 300, 280)
                ),
                OcrRegion.DISPLAY
            ),
            OcrPageData(
                "128 SYS mmHg\n82 DIA mmHg\n80 PUL /min",
                listOf(
                    OcrLineData("128", 20, 20, 180, 105),
                    OcrLineData("SYS mmHg", 200, 35, 300, 80),
                    OcrLineData("82", 20, 130, 180, 215),
                    OcrLineData("DIA mmHg", 200, 145, 300, 190),
                    OcrLineData("80", 100, 235, 180, 290),
                    OcrLineData("PUL /min", 200, 240, 300, 280)
                ),
                OcrRegion.DISPLAY
            )
        )
    )) { "Label parser returned null" }
    check(labelled.systolic == 128 && labelled.diastolic == 82 && labelled.pulse == 80) {
        "Expected labelled 128/82 pulse 80 but got $labelled"
    }
    println("PASS: OCR parser maps sample monitor to SYS 128, DIA 82, Pulse 80")
}
