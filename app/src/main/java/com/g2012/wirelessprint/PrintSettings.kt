package com.g2012.wirelessprint

enum class PaperSize(val label: String, val gutenprintName: String, val widthInches: Float, val heightInches: Float) {
    A4("A4 (210 x 297 mm)", "A4", 8.27f, 11.69f),
    LETTER("Letter (8.5 x 11 in)", "Letter", 8.5f, 11.0f),
    LEGAL("Legal (8.5 x 14 in)", "Legal", 8.5f, 14.0f),
    A5("A5 (148 x 210 mm)", "A5", 5.83f, 8.27f),
    PHOTO_4X6("4x6 Photo", "w288h432", 4.0f, 6.0f)
}

enum class PrintQuality(val label: String, val dpi: Int) {
    DRAFT("Draft (Fast - 150 DPI)", 150),
    NORMAL("Normal (200 DPI)", 200),
    HIGH("High Quality (300 DPI)", 300)
}

enum class PageFilter(val label: String) {
    ALL("All Pages"),
    ODD("Odd Pages Only (1, 3, 5...)"),
    EVEN("Even Pages Only (2, 4, 6...)"),
    CUSTOM("Custom Range (e.g. 1-3, 5)")
}

enum class OrientationMode(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class ScaleMode(val label: String) {
    FIT_WITH_MARGINS("Fit to Page (With Margins)"),
    FILL_BORDERLESS("Fill / Minimal Margins")
}

data class PrintSettings(
    val isColor: Boolean = false,
    val paperSize: PaperSize = PaperSize.A4,
    val mediaType: String = "Plain",
    val quality: PrintQuality = PrintQuality.NORMAL,
    val orientation: OrientationMode = OrientationMode.PORTRAIT,
    val pagesPerSheet: Int = 1, // 1, 2, 4
    val scaleMode: ScaleMode = ScaleMode.FIT_WITH_MARGINS,
    val pageFilter: PageFilter = PageFilter.ALL,
    val customRange: String = "",
    val copies: Int = 1,
    val collate: Boolean = true,
    val directUsb: Boolean = false
) {
    fun resolvePageIndices(totalPages: Int): List<Int> {
        if (totalPages <= 0) return emptyList()
        return when (pageFilter) {
            PageFilter.ALL -> (0 until totalPages).toList()
            PageFilter.ODD -> (0 until totalPages).filter { (it + 1) % 2 == 1 }
            PageFilter.EVEN -> (0 until totalPages).filter { (it + 1) % 2 == 0 }
            PageFilter.CUSTOM -> parseCustomRange(customRange, totalPages)
        }
    }

    private fun parseCustomRange(input: String, totalPages: Int): List<Int> {
        val result = linkedSetOf<Int>()
        val parts = input.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.contains("-")) {
                val bounds = trimmed.split("-")
                val start = bounds.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                val end = bounds.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
                val lo = minOf(start, end).coerceIn(1, totalPages)
                val hi = maxOf(start, end).coerceIn(1, totalPages)
                for (p in lo..hi) result.add(p - 1)
            } else {
                val p = trimmed.toIntOrNull() ?: continue
                if (p in 1..totalPages) result.add(p - 1)
            }
        }
        return if (result.isEmpty()) (0 until totalPages).toList() else result.toList()
    }
}
