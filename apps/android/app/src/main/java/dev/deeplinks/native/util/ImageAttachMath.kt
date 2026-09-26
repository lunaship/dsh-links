package dev.deeplinks.native.util

/** 图片附件几何：压缩边长、采样、取景框映射（对照 DeepSeek 1.6 裁剪 / 2.3 上传）。 */
const val IMAGE_MAX_EDGE = 2048
const val IMAGE_MAX_BYTES = 8 * 1024 * 1024
const val IMAGE_TARGET_BYTES = 1_500_000

fun scaledSize(width: Int, height: Int, maxEdge: Int = IMAGE_MAX_EDGE): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val longEdge = maxOf(width, height)
    if (longEdge <= maxEdge) return width to height
    val k = maxEdge.toFloat() / longEdge
    return (width * k).toInt().coerceAtLeast(1) to (height * k).toInt().coerceAtLeast(1)
}

fun inSampleSize(width: Int, height: Int, maxEdge: Int = IMAGE_MAX_EDGE): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / sample > maxEdge || height / sample > maxEdge) {
        sample *= 2
    }
    return sample
}

/**
 * ContentScale.Fit + graphicsLayer(scale, translation) 下，视图像素 → 位图像素。
 * 变换原点为视口中心，与 Compose `graphicsLayer` 默认一致。
 */
fun mapViewToBitmap(
    viewX: Float,
    viewY: Float,
    viewW: Float,
    viewH: Float,
    bmpW: Int,
    bmpH: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): Pair<Float, Float> {
    val fit = minOf(viewW / bmpW, viewH / bmpH)
    val drawnW = bmpW * fit
    val drawnH = bmpH * fit
    val originX = (viewW - drawnW) / 2f
    val originY = (viewH - drawnH) / 2f
    val cx = viewW / 2f
    val cy = viewH / 2f
    val srcX = cx + (viewX - cx - offsetX) / scale
    val srcY = cy + (viewY - cy - offsetY) / scale
    return (srcX - originX) / fit to (srcY - originY) / fit
}

data class BitmapCropRect(val left: Int, val top: Int, val width: Int, val height: Int)

fun viewfinderCropOnBitmap(
    viewW: Float,
    viewH: Float,
    bmpW: Int,
    bmpH: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): BitmapCropRect {
    val (x0, y0) = mapViewToBitmap(0f, 0f, viewW, viewH, bmpW, bmpH, scale, offsetX, offsetY)
    val (x1, y1) = mapViewToBitmap(viewW, viewH, viewW, viewH, bmpW, bmpH, scale, offsetX, offsetY)
    val left = minOf(x0, x1).toInt().coerceIn(0, bmpW - 1)
    val top = minOf(y0, y1).toInt().coerceIn(0, bmpH - 1)
    val right = maxOf(x0, x1).toInt().coerceIn(left + 1, bmpW)
    val bottom = maxOf(y0, y1).toInt().coerceIn(top + 1, bmpH)
    return BitmapCropRect(left, top, right - left, bottom - top)
}

fun tableToTsv(rows: List<List<String>>): String =
    rows.joinToString("\n") { row -> row.joinToString("\t") }

fun tableToCsv(rows: List<List<String>>): String =
    rows.joinToString("\n") { row -> row.joinToString(",") { csvCell(it) } }

internal fun csvCell(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

fun tableToMarkdown(rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    val cols = rows.maxOf { it.size }
    fun pad(row: List<String>) = (0 until cols).map { row.getOrElse(it) { "" } }
    val header = pad(rows.first())
    val sep = List(cols) { "---" }
    val body = rows.drop(1).map { pad(it).joinToString(" | ", "| ", " |") }
    return buildString {
        append(header.joinToString(" | ", "| ", " |"))
        append('\n')
        append(sep.joinToString(" | ", "| ", " |"))
        if (body.isNotEmpty()) {
            append('\n')
            append(body.joinToString("\n"))
        }
    }
}
