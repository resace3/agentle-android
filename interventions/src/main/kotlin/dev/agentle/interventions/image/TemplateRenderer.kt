package dev.agentle.interventions.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import dev.agentle.interventions.R
import dev.agentle.interventions.storage.AtomicFiles
import dev.agentle.jitai.dsl.model.JitaiCategory
import java.io.File
import java.io.IOException

/** The user's metric on a card, already formatted with the user's locale (for example "Steps today", "4,210"). */
data class CardMetric(val label: String, val value: String)

/** What a card shows. Text is drawn as given; the renderer never formats numbers or dates itself. */
data class CardContent(val title: String, val body: String, val category: JitaiCategory, val metric: CardMetric? = null)

/** Output sizes (R09 §6.3): 2:1 notification picture, 9:16 video slide, square share card. */
enum class CardSize(val width: Int, val height: Int) {
    NOTIFICATION(1024, 512),
    SLIDE(720, 1280),
    SHARE(1080, 1080),
}

/** Colour tokens (ARGB). Fixed per category in v1, so a delivered picture never depends on the theme at render time. */
data class CardTheme(val backgroundTop: Int, val backgroundBottom: Int, val onBackground: Int, val muted: Int, val accent: Int) {
    companion object {
        private const val ON = 0xFFFFFFFF.toInt()
        private const val MUTED = 0xD9FFFFFF.toInt()
        private const val ACCENT = 0x33FFFFFF

        fun forCategory(category: JitaiCategory): CardTheme = when (category) {
            JitaiCategory.PHYSICAL_ACTIVITY -> CardTheme(0xFF1B5E20.toInt(), 0xFF2E7D32.toInt(), ON, MUTED, ACCENT)
            JitaiCategory.SLEEP_WIND_DOWN -> CardTheme(0xFF1A237E.toInt(), 0xFF3949AB.toInt(), ON, MUTED, ACCENT)
            JitaiCategory.DIGITAL_WELLBEING -> CardTheme(0xFF004D40.toInt(), 0xFF00796B.toInt(), ON, MUTED, ACCENT)
            JitaiCategory.STRESS_BREAK -> CardTheme(0xFF4A148C.toInt(), 0xFF7B1FA2.toInt(), ON, MUTED, ACCENT)
            JitaiCategory.GENERAL -> CardTheme(0xFF263238.toInt(), 0xFF455A64.toInt(), ON, MUTED, ACCENT)
        }
    }
}

/**
 * How the text was laid out: final sizes, line counts, whether anything was cut with an ellipsis, and whether the
 * block fits the space left under the icon ([fits] is always true; the tests hold the renderer to it).
 */
data class CardLayout(
    val titleSizePx: Float,
    val bodySizePx: Float,
    val titleLines: Int,
    val bodyLines: Int,
    val ellipsized: Boolean,
    val textHeightPx: Int,
    val availableHeightPx: Int,
) {
    val fits: Boolean get() = textHeightPx <= availableHeightPx
}

/** A rendered card and its layout report. */
class RenderedCard(val bitmap: Bitmap, val layout: CardLayout)

/**
 * Offline, deterministic cards drawn with `android.graphics` (R09 §6.2): gradient background, category icon, title,
 * body and an optional metric. Text scales with the user's font scale (capped at 2.0), then shrinks and finally
 * ellipsizes until it fits, so it never overflows. Works in a worker: no window, no Compose.
 */
class TemplateRenderer(private val context: Context) {
    fun render(
        content: CardContent,
        size: CardSize,
        fontScale: Float = context.resources.configuration.fontScale,
        theme: CardTheme = CardTheme.forCategory(content.category),
    ): RenderedCard {
        val width = size.width
        val height = size.height
        val unit = minOf(width, height) / UNITS
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, width, height, theme, unit)
        val pad = unit * PAD_UNITS
        val iconSize = (unit * ICON_UNITS).toInt()
        drawIcon(canvas, content.category, pad.toInt(), iconSize, theme)
        var bottom = height - pad
        content.metric?.let { bottom = drawMetric(canvas, it, width, bottom, pad, unit, theme) }
        val top = pad + iconSize + unit * GAP_UNITS
        val scaledUnit = unit * fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        val layout = drawText(canvas, content, width - 2 * pad, top, pad, (bottom - top).toInt(), scaledUnit, theme)
        return RenderedCard(bitmap, layout)
    }

    /** PNG (lossless, no quality tuning), temp file, fsync and rename (R09 §6.2). Returns the file size. */
    fun writePng(bitmap: Bitmap, target: File): Long {
        AtomicFiles.write(target) { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) throw IOException("png encode")
        }
        return target.length()
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int, theme: CardTheme, unit: Float) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader =
                LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    theme.backgroundTop,
                    theme.backgroundBottom,
                    Shader.TileMode.CLAMP,
                )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        // One soft disc in the corner, so plain cards still look intentional.
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }
        canvas.drawCircle(width.toFloat(), 0f, unit * DISC_UNITS, disc)
    }

    private fun drawIcon(canvas: Canvas, category: JitaiCategory, pad: Int, size: Int, theme: CardTheme) {
        val icon = context.getDrawable(iconOf(category))?.mutate() ?: return
        icon.setTint(theme.onBackground)
        icon.setBounds(pad, pad, pad + size, pad + size)
        icon.draw(canvas)
    }

    /** Draws the metric at the bottom; returns the new bottom edge of the text area. */
    private fun drawMetric(
        canvas: Canvas,
        metric: CardMetric,
        width: Int,
        bottom: Float,
        pad: Float,
        unit: Float,
        theme: CardTheme,
    ): Float {
        val label = textPaint(theme.muted, unit * METRIC_LABEL_UNITS, bold = false)
        val value = textPaint(theme.onBackground, unit * METRIC_VALUE_UNITS, bold = true)
        val maxWidth = width - 2 * pad
        val labelText = TextUtils.ellipsize(metric.label, label, maxWidth, TextUtils.TruncateAt.END).toString()
        val valueText = TextUtils.ellipsize(metric.value, value, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(labelText, pad, bottom, label)
        val valueBaseline = bottom - label.textSize * LINE_GAP
        canvas.drawText(valueText, pad, valueBaseline, value)
        return valueBaseline - value.textSize - unit * GAP_UNITS
    }

    /**
     * Title (bold, at most 2 lines) and body (at most 6 lines). Sizes shrink by 10% steps down to [MIN_SHRINK] of the
     * scaled size; if the text still does not fit, body lines (then title lines) are dropped, ending with an ellipsis.
     */
    private fun drawText(
        canvas: Canvas,
        content: CardContent,
        maxWidth: Float,
        top: Float,
        left: Float,
        available: Int,
        scaledUnit: Float,
        theme: CardTheme,
    ): CardLayout {
        var shrink = 1f
        var titleMax = TITLE_MAX_LINES
        var bodyMax = BODY_MAX_LINES
        var pair = layouts(content, maxWidth.toInt(), scaledUnit * shrink, titleMax, bodyMax, theme)
        while (pair.height > available && shrink > MIN_SHRINK) {
            shrink -= SHRINK_STEP
            pair = layouts(content, maxWidth.toInt(), scaledUnit * shrink, titleMax, bodyMax, theme)
        }
        while (pair.height > available && (bodyMax > 1 || titleMax > 1)) {
            if (bodyMax > 1) bodyMax-- else titleMax--
            pair = layouts(content, maxWidth.toInt(), scaledUnit * shrink, titleMax, bodyMax, theme)
        }
        canvas.save()
        canvas.translate(left, top)
        pair.title.draw(canvas)
        canvas.translate(0f, pair.title.height + scaledUnit * shrink * GAP_UNITS)
        pair.body?.draw(canvas)
        canvas.restore()
        return CardLayout(
            titleSizePx = pair.title.paint.textSize,
            bodySizePx = pair.body?.paint?.textSize ?: 0f,
            titleLines = pair.title.lineCount,
            bodyLines = pair.body?.lineCount ?: 0,
            ellipsized = pair.ellipsized,
            textHeightPx = pair.height,
            availableHeightPx = available,
        )
    }

    private class TextBlock(val title: StaticLayout, val body: StaticLayout?, val gap: Int) {
        val height: Int get() = title.height + (body?.let { it.height + gap } ?: 0)
        val ellipsized: Boolean
            get() = (0 until title.lineCount).any { title.getEllipsisCount(it) > 0 } ||
                body?.let { b -> (0 until b.lineCount).any { b.getEllipsisCount(it) > 0 } } == true
    }

    private fun layouts(content: CardContent, width: Int, unit: Float, titleMax: Int, bodyMax: Int, theme: CardTheme): TextBlock {
        val title = staticLayout(content.title, textPaint(theme.onBackground, unit * TITLE_UNITS, bold = true), width, titleMax)
        val body = content.body.takeIf { it.isNotBlank() }?.let {
            staticLayout(it, textPaint(theme.muted, unit * BODY_UNITS, bold = false), width, bodyMax)
        }
        return TextBlock(title, body, (unit * GAP_UNITS).toInt())
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun textPaint(color: Int, size: Float, bold: Boolean): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    companion object {
        /** Layout unit: 1/100 of the shorter side, so all three sizes share one design. */
        private const val UNITS = 100f
        private const val PAD_UNITS = 7f
        private const val ICON_UNITS = 13f
        private const val GAP_UNITS = 3f
        private const val DISC_UNITS = 38f
        private const val TITLE_UNITS = 8f
        private const val BODY_UNITS = 5.5f
        private const val METRIC_VALUE_UNITS = 11f
        private const val METRIC_LABEL_UNITS = 4.5f
        private const val LINE_GAP = 1.3f
        private const val LINE_SPACING = 1.1f
        private const val TITLE_MAX_LINES = 2
        private const val BODY_MAX_LINES = 6
        private const val SHRINK_STEP = 0.1f
        private const val MIN_SHRINK = 0.5f
        private const val PNG_QUALITY = 100
        private const val MIN_FONT_SCALE = 0.85f
        const val MAX_FONT_SCALE: Float = 2f

        fun iconOf(category: JitaiCategory): Int = when (category) {
            JitaiCategory.PHYSICAL_ACTIVITY -> R.drawable.ic_card_physical_activity
            JitaiCategory.SLEEP_WIND_DOWN -> R.drawable.ic_card_sleep_wind_down
            JitaiCategory.DIGITAL_WELLBEING -> R.drawable.ic_card_digital_wellbeing
            JitaiCategory.STRESS_BREAK -> R.drawable.ic_card_stress_break
            JitaiCategory.GENERAL -> R.drawable.ic_card_general
        }
    }
}
