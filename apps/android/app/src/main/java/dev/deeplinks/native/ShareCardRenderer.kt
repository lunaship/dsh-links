package dev.deeplinks.native

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import dev.deeplinks.native.util.SHARE_EXPORT_PREFIX
import dev.deeplinks.native.util.ShareTurn
import dev.deeplinks.native.util.pruneShareExports
import java.io.File

/**
 * 会话分享图（对照 DeepSeek 官方 App 1.3.1）。
 * 卡片用 DSH 色，不冒充官方 App。
 */
object ShareCardRenderer {
    private const val WIDTH = 1080
    private const val PAD = 56
    private const val GAP = 28
    private const val RADIUS = 28f
    private const val BRAND_BAR = 8f

    fun render(title: String, turns: List<ShareTurn>, dark: Boolean, brandLabel: String): Bitmap {
        val colors = if (dark) DarkPalette else LightPalette
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.labelPrimary
            textSize = 40f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.labelTertiary
            textSize = 24f
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.labelPrimary
            textSize = 30f
        }
        val inner = WIDTH - PAD * 2
        val titleLayout = layout(title.ifBlank { "DSH" }, titlePaint, inner)
        val metaLayout = layout(brandLabel, metaPaint, inner)
        val bodyLayouts = turns.map { turn ->
            val maxW = if (turn.role == "user") (inner * 0.82f).toInt() else inner
            turn to layout(turn.text, bodyPaint, maxW)
        }
        var height = PAD + BRAND_BAR + GAP + titleLayout.height + 12 + metaLayout.height + GAP
        bodyLayouts.forEach { (_, layout) ->
            height += layout.height + 36 + GAP
        }
        height += PAD
        val bmp = createBitmap(WIDTH, height.toInt().coerceAtLeast(PAD * 4))
        val canvas = Canvas(bmp)
        canvas.drawColor(colors.bgBase)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.brand }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), BRAND_BAR, barPaint)
        var y = PAD + BRAND_BAR
        canvas.save()
        canvas.translate(PAD.toFloat(), y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + 12
        canvas.save()
        canvas.translate(PAD.toFloat(), y)
        metaLayout.draw(canvas)
        canvas.restore()
        y += metaLayout.height + GAP
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.bubble }
        bodyLayouts.forEach { (turn, layout) ->
            val bubbleW = layout.width + 40
            val bubbleH = layout.height + 32
            val left = if (turn.role == "user") (WIDTH - PAD - bubbleW).toFloat() else PAD.toFloat()
            canvas.drawRoundRect(
                RectF(left, y, left + bubbleW, y + bubbleH),
                RADIUS,
                RADIUS,
                if (turn.role == "user") bubblePaint else Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.assistantBg },
            )
            canvas.save()
            canvas.translate(left + 20f, y + 16f)
            layout.draw(canvas)
            canvas.restore()
            y += bubbleH + GAP
        }
        return bmp
    }

    fun sharePng(context: Context, bitmap: Bitmap, title: String, chooserTitle: String) {
        pruneShareExports(context.cacheDir, System.currentTimeMillis())
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "$SHARE_EXPORT_PREFIX${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newRawUri("image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    private data class Palette(
        val bgBase: Int,
        val labelPrimary: Int,
        val labelTertiary: Int,
        val brand: Int,
        val bubble: Int,
        val assistantBg: Int,
    )

    private val DarkPalette = Palette(
        bgBase = 0xFF151517.toInt(),
        labelPrimary = 0xFFF9FAFB.toInt(),
        labelTertiary = 0xFFADB2B8.toInt(),
        brand = 0xFF679EFE.toInt(),
        bubble = 0xFF353638.toInt(),
        assistantBg = 0xFF232324.toInt(),
    )

    private val LightPalette = Palette(
        bgBase = 0xFFFFFFFF.toInt(),
        labelPrimary = 0xFF0F1115.toInt(),
        labelTertiary = 0xFF70757A.toInt(),
        brand = 0xFF2563D8.toInt(),
        bubble = 0xFFF1F3F5.toInt(),
        assistantBg = 0xFFF9FAFB.toInt(),
    )
}
