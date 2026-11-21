package com.g2012.wirelessprint

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

sealed class PrintSource {
    data class Pdf(val uri: Uri, val displayName: String) : PrintSource()
    data class Images(val uris: List<Uri>) : PrintSource()
    data class PlainText(val text: String, val title: String) : PrintSource()
    object TestPage : PrintSource()
}

object PageComposer {

    fun getSourcePageCount(context: Context, source: PrintSource): Int {
        return when (source) {
            is PrintSource.Pdf -> {
                context.contentResolver.openFileDescriptor(source.uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { it.pageCount }
                } ?: 0
            }
            is PrintSource.Images -> source.uris.size
            is PrintSource.PlainText -> 1
            is PrintSource.TestPage -> 1
        }
    }

    fun getTotalSheetCount(context: Context, source: PrintSource, settings: PrintSettings): Int {
        val rawPages = getSourcePageCount(context, source)
        val selected = settings.resolvePageIndices(rawPages).size
        if (selected == 0) return 0
        val nUp = settings.pagesPerSheet.coerceIn(1, 4)
        return (selected + nUp - 1) / nUp
    }

    /**
     * Composes a single physical sheet (index 0..totalSheets-1) at the requested DPI.
     * For live preview, pass dpi = 90 (super fast); for printing, pass settings.quality.dpi.
     */
    fun composeSheet(
        context: Context,
        source: PrintSource,
        settings: PrintSettings,
        sheetIndex: Int,
        dpi: Int,
        applyBwPreviewFilter: Boolean = false
    ): Bitmap {
        // Physical paper is ALWAYS portrait in Canon G2012 feed; landscape rotates the content 90 deg onto portrait paper
        val sheetW = (settings.paperSize.widthInches * dpi).toInt().coerceAtLeast(300)
        val sheetH = (settings.paperSize.heightInches * dpi).toInt().coerceAtLeast(400)

        val isLandscape = settings.orientation == OrientationMode.LANDSCAPE
        val canvasW = if (isLandscape) sheetH else sheetW
        val canvasH = if (isLandscape) sheetW else sheetH

        val workBmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(workBmp)
        canvas.drawColor(Color.WHITE)

        val rawPages = getSourcePageCount(context, source)
        val selectedIndices = settings.resolvePageIndices(rawPages)
        val nUp = settings.pagesPerSheet
        val startPos = sheetIndex * nUp
        val pageIndicesForSheet = selectedIndices.drop(startPos).take(nUp)

        val margin = if (settings.scaleMode == ScaleMode.FIT_WITH_MARGINS) (dpi * 0.25f) else 0f
        val innerW = canvasW - margin * 2
        val innerH = canvasH - margin * 2

        val slots: List<RectF> = when (nUp) {
            2 -> {
                val gap = dpi * 0.15f
                if (isLandscape) {
                    val slotW = (innerW - gap) / 2f
                    listOf(
                        RectF(margin, margin, margin + slotW, margin + innerH),
                        RectF(margin + slotW + gap, margin, margin + innerW, margin + innerH)
                    )
                } else {
                    val slotH = (innerH - gap) / 2f
                    listOf(
                        RectF(margin, margin, margin + innerW, margin + slotH),
                        RectF(margin, margin + slotH + gap, margin + innerW, margin + innerH)
                    )
                }
            }
            4 -> {
                val gap = dpi * 0.15f
                val slotW = (innerW - gap) / 2f
                val slotH = (innerH - gap) / 2f
                listOf(
                    RectF(margin, margin, margin + slotW, margin + slotH),
                    RectF(margin + slotW + gap, margin, margin + innerW, margin + slotH),
                    RectF(margin, margin + slotH + gap, margin + slotW, margin + innerH),
                    RectF(margin + slotW + gap, margin + slotH + gap, margin + innerW, margin + innerH)
                )
            }
            else -> listOf(RectF(margin, margin, margin + innerW, margin + innerH))
        }

        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (applyBwPreviewFilter && !settings.isColor) {
            val cm = ColorMatrix().apply { setSaturation(0f) }
            drawPaint.colorFilter = ColorMatrixColorFilter(cm)
        }

        for ((slotIdx, pageIdx) in pageIndicesForSheet.withIndex()) {
            val slot = slots.getOrNull(slotIdx) ?: break
            val srcBmp = renderSingleSourcePage(
                context, source, pageIdx,
                slot.width().toInt().coerceAtLeast(100),
                slot.height().toInt().coerceAtLeast(100)
            ) ?: continue

            val scale = minOf(slot.width() / srcBmp.width, slot.height() / srcBmp.height)
            val dw = srcBmp.width * scale
            val dh = srcBmp.height * scale
            val left = slot.left + (slot.width() - dw) / 2f
            val top = slot.top + (slot.height() - dh) / 2f
            val dstRect = RectF(left, top, left + dw, top + dh)

            canvas.drawBitmap(srcBmp, null, dstRect, drawPaint)
            srcBmp.recycle()
        }

        // If printing (not preview) in Landscape, rotate 90 degrees so it feeds properly into Canon G2012 portrait tray
        if (isLandscape && !applyBwPreviewFilter) {
            val matrix = Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(workBmp, 0, 0, workBmp.width, workBmp.height, matrix, true)
            workBmp.recycle()
            return rotated
        }
        return workBmp
    }

    private fun renderSingleSourcePage(
        context: Context,
        source: PrintSource,
        pageIndex: Int,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        return when (source) {
            is PrintSource.Pdf -> {
                context.contentResolver.openFileDescriptor(source.uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return null
                        renderer.openPage(pageIndex).use { page ->
                            val scale = minOf(targetW.toFloat() / page.width, targetH.toFloat() / page.height)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            bmp
                        }
                    }
                }
            }
            is PrintSource.Images -> {
                val uri = source.uris.getOrNull(pageIndex) ?: return null
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
            is PrintSource.PlainText -> {
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = (targetW / 28f).coerceAtLeast(18f)
                }
                val pad = (targetW * 0.05f).toInt()
                val textWidth = (targetW - pad * 2).coerceAtLeast(100)
                val layout = StaticLayout.Builder.obtain(source.text, 0, source.text.length, tp, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(6f, 1.15f)
                    .build()
                c.save()
                c.translate(pad.toFloat(), pad.toFloat())
                layout.draw(c)
                c.restore()
                bmp
            }
            is PrintSource.TestPage -> {
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1565C0")
                    textSize = targetW * 0.065f
                    typeface = Typeface.DEFAULT_BOLD
                }
                c.drawText("Canon PIXMA G2012", targetW * 0.1f, targetH * 0.15f, p)
                p.color = Color.BLACK
                p.textSize = targetW * 0.04f
                p.typeface = Typeface.DEFAULT
                c.drawText("G2012 Print Pro - Wireless Test Page", targetW * 0.1f, targetH * 0.22f, p)
                c.drawText("Status: Driver & Wi-Fi Bridge Working!", targetW * 0.1f, targetH * 0.28f, p)

                // Color bars for testing ink nozzles
                val colors = listOf(Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.BLACK)
                val barW = (targetW * 0.8f) / 4f
                for ((i, col) in colors.withIndex()) {
                    p.color = col
                    c.drawRect(
                        targetW * 0.1f + i * barW,
                        targetH * 0.35f,
                        targetW * 0.1f + (i + 1) * barW - 10f,
                        targetH * 0.42f,
                        p
                    )
                }
                p.color = Color.BLACK
                p.style = Paint.Style.STROKE
                p.strokeWidth = 4f
                c.drawRect(targetW * 0.05f, targetH * 0.05f, targetW * 0.95f, targetH * 0.95f, p)
                bmp
            }
        }
    }
}
