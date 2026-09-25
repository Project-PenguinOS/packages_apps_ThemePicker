package com.android.customization.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.util.LruCache
import com.android.customization.gallery.GalleryWallpaper.Kind
import com.android.themepicker.R
import java.text.BreakIterator
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Draws any [GalleryWallpaper] at any size, for previews, the live service and bitmaps. */
object GalleryRenderer {

    /** Returns true while the wallpaper wants another frame soon. */
    fun draw(
        context: Context,
        canvas: Canvas,
        wallpaper: GalleryWallpaper,
        width: Int,
        height: Int,
        timeMs: Long = System.currentTimeMillis(),
        shuffleIndex: Int = 0,
        weather: GalleryWeather.Snapshot? = null,
    ): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (wallpaper.kind) {
            Kind.COLOUR -> colour(canvas, wallpaper, w, h).let { false }
            Kind.BUBBLES -> bubbles(canvas, wallpaper.variant, w, h, timeMs).let { true }
            Kind.STRIPES -> stripes(canvas, wallpaper.variant, w, h).let { false }
            Kind.WAVES -> waves(canvas, wallpaper.variant, w, h, timeMs).let { false }
            Kind.PETALS -> petals(canvas, wallpaper.variant, w, h).let { false }
            Kind.EMOJI -> emoji(canvas, wallpaper, w, h).let { false }
            Kind.KALEIDOSCOPE -> kaleidoscope(context, canvas, wallpaper, w, h).let { false }
            Kind.PHOTO -> photo(context, canvas, wallpaper.photos.firstOrNull(), wallpaper.variant,
                w, h).let { false }
            Kind.PAPER -> paper(context, canvas, wallpaper.variant, w, h).let { false }
            Kind.SHUFFLE -> {
                val photos = wallpaper.photos
                photo(context, canvas, photos.getOrNull(Math.floorMod(shuffleIndex,
                    max(1, photos.size))), GalleryWallpaper.FILTER_NATURAL, w, h)
                false
            }
            Kind.ASTRONOMY ->
                GalleryPlanets.draw(context, canvas, wallpaper.variant, w, h, timeMs).let { false }
            Kind.WEATHER -> GalleryWeather.draw(canvas, weather, w, h, timeMs)
        }
    }

    /**
     * The image an unlock effect or the depth cutout works from: the photo itself, or any other
     * still wallpaper drawn once to a file, so every wall can take an effect, not just photos.
     * Blocking; call off the main thread. Null for moving wallpapers.
     */
    fun effectSource(context: Context, wallpaper: GalleryWallpaper): String? {
        if (wallpaper.kind == GalleryWallpaper.Kind.PHOTO) return wallpaper.photos.firstOrNull()
        if (wallpaper.kind.live) return null
        // Keyed on what is drawn, not on the effect or depth chosen for it.
        val key = wallpaper.copy(effect = 0, depth = false).hashCode().toUInt().toString(16)
        val dir = java.io.File(context.filesDir, "gallery/effect").apply { mkdirs() }
        val file = java.io.File(dir, "$key.jpg")
        if (!file.exists()) {
            val (w, h) = GalleryApplier.screenSize(context)
            val bitmap = toBitmap(context, wallpaper, w, h)
            java.io.FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)
            }
            dir.listFiles()?.filter { it != file }?.sortedBy { it.lastModified() }
                ?.dropLast(EFFECT_SOURCES_KEPT)?.forEach { it.delete() }
        }
        return file.absolutePath
    }

    fun toBitmap(context: Context, wallpaper: GalleryWallpaper, width: Int, height: Int,
            shuffleIndex: Int = 0): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(context, Canvas(bitmap), wallpaper, width, height, shuffleIndex = shuffleIndex,
            weather = GalleryWeather.query(context))
        return bitmap
    }

    private const val EFFECT_SOURCES_KEPT = 8

    // Colour

    val COLOURS = intArrayOf(0xFF3A7BD5.toInt(), 0xFF8E5CF7.toInt(), 0xFFE0569A.toInt(),
        0xFFF05A4F.toInt(), 0xFFF5A623.toInt(), 0xFFE9D34B.toInt(), 0xFF4CC38A.toInt(),
        0xFF2DB8C5.toInt(), 0xFF5BC8F5.toInt(), 0xFF7A8594.toInt(), 0xFFB08D6E.toInt(),
        0xFF1C1C1E.toInt())

    fun colourStops(base: Int, style: Int): IntArray {
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        fun hsv(hue: Float, sat: Float, value: Float) =
            Color.HSVToColor(floatArrayOf((hue + 360) % 360, sat.coerceIn(0f, 1f),
                value.coerceIn(0f, 1f)))
        return when (style) {
            GalleryWallpaper.COLOUR_TONE -> intArrayOf(hsv(hsv[0], hsv[1] * 0.45f, 0.95f),
                hsv(hsv[0], hsv[1], hsv[2] * 0.75f))
            GalleryWallpaper.COLOUR_DEEP -> intArrayOf(hsv(hsv[0], hsv[1], 0.08f),
                hsv(hsv[0], hsv[1], hsv[2] * 0.8f))
            GalleryWallpaper.COLOUR_SOLID -> intArrayOf(base, base)
            else -> intArrayOf(hsv(hsv[0] - 18, hsv[1] * 0.55f, 1f), base,
                hsv(hsv[0] + 22, min(1f, hsv[1] * 1.2f), hsv[2] * 0.55f))
        }
    }

    private fun colour(canvas: Canvas, wallpaper: GalleryWallpaper, w: Float, h: Float) {
        val stops = colourStops(wallpaper.colors.firstOrNull() ?: COLOURS[0], wallpaper.variant)
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, 0f, h, stops, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    // Collections

    private val BUBBLE_PALETTES = arrayOf(
        intArrayOf(0xFF000000.toInt(), 0xFFF2D544.toInt(), 0xFFA63FB8.toInt(), 0xFF4A9AF0.toInt()),
        intArrayOf(0xFFF4F1EA.toInt(), 0xFFFF7A59.toInt(), 0xFF20B2AA.toInt(), 0xFF22306B.toInt()),
        intArrayOf(0xFF0E1230.toInt(), 0xFFFF6FB5.toInt(), 0xFFFFA14A.toInt(), 0xFF3DDCFF.toInt()),
    )
    const val BUBBLE_VARIANTS = 3

    private fun bubbles(canvas: Canvas, variant: Int, w: Float, h: Float, timeMs: Long) {
        val colors = BUBBLE_PALETTES[Math.floorMod(variant, BUBBLE_PALETTES.size)]
        canvas.drawColor(colors[0])
        val t = (timeMs % 60_000L) / 60_000.0 * 2 * PI
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun bubble(color: Int, cx: Float, cy: Float, r: Float) {
            paint.shader = RadialGradient(cx, cy, r, intArrayOf(color, color,
                color and 0x00FFFFFF), floatArrayOf(0f, 0.9f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
        bubble(colors[1], w * (0.92f + 0.02f * sin(t).toFloat()), h * 0.02f, w * 0.62f)
        bubble(colors[2], w * (0.95f + 0.03f * cos(t * 2).toFloat()),
            h * (0.33f + 0.01f * sin(t * 2).toFloat()), w * 0.18f)
        bubble(colors[3], w * (0.02f + 0.02f * cos(t).toFloat()), h * 0.62f, w * 0.44f)
    }

    const val STRIPE_VARIANTS = 3

    private fun stripes(canvas: Canvas, variant: Int, w: Float, h: Float) {
        canvas.drawColor(
            when (Math.floorMod(variant, STRIPE_VARIANTS)) {
                1 -> 0xFF101010.toInt()
                2 -> 0xFFF4F1EA.toInt()
                else -> 0xFF2B3927.toInt()
            }
        )
        val bands = intArrayOf(0xFF6DB84A.toInt(), 0xFFF5B83D.toInt(), 0xFFEF8A31.toInt(),
            0xFFE3453A.toInt(), 0xFF963E97.toInt(), 0xFF3F90D0.toInt())
        val band = h * 0.028f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.rotate(-26f, w / 2, h * 0.52f)
        bands.forEachIndexed { i, color ->
            paint.color = color
            val top = h * 0.42f + i * band
            canvas.drawRect(-w, top, w * 2, top + band + 1, paint)
        }
        canvas.restore()
    }

    private val WAVE_TINTS = arrayOf(
        intArrayOf(0xFF101120.toInt(), 0xFF2E3152.toInt(), 0xFF8C90C8.toInt()),
        intArrayOf(0xFF221A20.toInt(), 0xFF5C4450.toInt(), 0xFFE0AFC2.toInt()),
        intArrayOf(0xFF2C2C29.toInt(), 0xFF6B6B64.toInt(), 0xFFEDEDE6.toInt()),
    )
    const val WAVE_VARIANTS = 3

    /** Folded fabric that brightens by day and dims at night. */
    private fun waves(canvas: Canvas, variant: Int, w: Float, h: Float, timeMs: Long) {
        val tint = WAVE_TINTS[Math.floorMod(variant, WAVE_TINTS.size)]
        canvas.drawColor(tint[0])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, w / 400f)
        }
        for (k in 0 until 5) {
            val y0 = h * (0.02f + 0.19f * k)
            val path = Path().apply {
                moveTo(w * 1.05f, y0)
                cubicTo(w * 0.62f, y0 - h * 0.06f, w * 0.52f, y0 + h * 0.42f, -w * 0.05f,
                    y0 + h * 0.3f)
                lineTo(-w * 0.05f, h * 1.1f)
                lineTo(w * 1.05f, h * 1.1f)
                close()
            }
            val shade = blend(tint[0], tint[1], 0.35f + 0.13f * k)
            paint.shader = LinearGradient(w, y0, 0f, y0 + h * 0.45f,
                intArrayOf(blend(shade, tint[2], 0.25f), shade, blend(shade, tint[0], 0.5f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
            edge.shader = LinearGradient(w, y0, 0f, y0 + h * 0.3f,
                intArrayOf(tint[2], tint[2] and 0x33FFFFFF), null, Shader.TileMode.CLAMP)
            val line = Path().apply {
                moveTo(w * 1.05f, y0)
                cubicTo(w * 0.62f, y0 - h * 0.06f, w * 0.52f, y0 + h * 0.42f, -w * 0.05f,
                    y0 + h * 0.3f)
            }
            canvas.drawPath(line, edge)
        }
        val hour = Calendar.getInstance().apply { timeInMillis = timeMs }
            .let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f }
        val daylight = ((cos((hour - 13f) / 24f * 2 * PI) + 1) / 2).toFloat()
        canvas.drawColor(Color.argb(((1 - daylight) * 90).toInt(), 0, 0, 0))
    }

    private val PETAL_TINTS = arrayOf(
        intArrayOf(0xFFF3F5F9.toInt(), 0xFF7C8FB3.toInt()),
        intArrayOf(0xFFF6F2F8.toInt(), 0xFFA08BC6.toInt()),
        intArrayOf(0xFFF2F5F1.toInt(), 0xFF7FA38A.toInt()),
    )
    const val PETAL_VARIANTS = 3

    private fun petals(canvas: Canvas, variant: Int, w: Float, h: Float) {
        val tint = PETAL_TINTS[Math.floorMod(variant, PETAL_TINTS.size)]
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.WHITE, tint[0]), null,
            Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bg)
        val cx = w / 2
        val cy = h * 0.58f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.MULTIPLY }
        fun ring(count: Int, length: Float, width: Float, offset: Float, alpha: Int) {
            for (i in 0 until count) {
                val angle = offset + 360f / count * i
                canvas.save()
                canvas.rotate(angle, cx, cy)
                val path = Path().apply {
                    moveTo(cx, cy)
                    quadTo(cx - width, cy - length * 0.55f, cx, cy - length)
                    quadTo(cx + width, cy - length * 0.55f, cx, cy)
                }
                paint.shader = LinearGradient(cx, cy, cx, cy - length,
                    intArrayOf(Color.argb(alpha, Color.red(tint[1]), Color.green(tint[1]),
                        Color.blue(tint[1])), Color.argb(alpha / 4, 255, 255, 255)), null,
                    Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }
        ring(12, w * 0.7f, w * 0.1f, 0f, 150)
        ring(12, w * 0.46f, w * 0.08f, 15f, 170)
        ring(8, w * 0.22f, w * 0.06f, 22.5f, 200)
    }

    // Emoji

    fun splitEmoji(text: String): List<String> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).takeIf { s -> s.isNotBlank() }?.let(out::add)
            start = end
            end = it.next()
        }
        return out
    }

    private fun emoji(canvas: Canvas, wallpaper: GalleryWallpaper, w: Float, h: Float) {
        canvas.drawColor(wallpaper.colors.firstOrNull() ?: 0xFFBFE3F2.toInt())
        val emojis = splitEmoji(wallpaper.emojis).ifEmpty { listOf("😀") }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        var n = 0
        fun put(x: Float, y: Float, size: Float) {
            paint.textSize = size
            canvas.drawText(emojis[n++ % emojis.size], x, y + size * 0.35f, paint)
        }
        when (wallpaper.variant) {
            GalleryWallpaper.EMOJI_RINGS -> {
                val cx = w / 2
                val cy = h / 2
                var r = w * 0.12f
                put(cx, cy, w * 0.14f)
                while (r < hypot(w, h) / 2) {
                    val count = (2 * PI * r / (w * 0.13f)).toInt()
                    for (i in 0 until count) {
                        val a = 2 * PI * i / count
                        put(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), w * 0.09f)
                    }
                    r += w * 0.14f
                }
            }
            GalleryWallpaper.EMOJI_SPIRAL -> {
                val cx = w / 2
                val cy = h / 2
                for (i in 1..420) {
                    val r = w * 0.045f * sqrt(i.toFloat())
                    if (r > hypot(w, h) / 2) break
                    val a = i * 2.39996
                    put(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(),
                        w * (0.05f + 0.00012f * i))
                }
            }
            else -> {
                val columns = when (wallpaper.variant) {
                    GalleryWallpaper.EMOJI_LARGE -> 4
                    GalleryWallpaper.EMOJI_MEDIUM -> 6
                    else -> 9
                }
                val step = w / columns
                var row = 0
                var y = step / 2
                while (y < h + step) {
                    val shift = if (row % 2 == 1) step / 2 else 0f
                    var x = step / 2 + shift - step
                    while (x < w + step) {
                        put(x, y, step * 0.62f)
                        x += step
                    }
                    y += step * 0.9f
                    row++
                }
            }
        }
    }

    // Photos

    private val photoCache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** The bundled wallpapers: the PenguinOS Collections, then the PenguinOS walls. */
    val PAPERS = intArrayOf(
        R.drawable.gallery_paper_0, R.drawable.gallery_paper_1, R.drawable.gallery_paper_2,
        R.drawable.gallery_paper_3, R.drawable.gallery_paper_4, R.drawable.gallery_paper_5,
        R.drawable.gallery_paper_6,
    )
    const val PAPER_COLLECTIONS = 3

    /** Whether a [Kind.PAPER] variant is one of the Collections, rather than a PenguinOS wall. */
    fun isCollectionPaper(variant: Int) = variant < PAPER_COLLECTIONS

    private fun paper(context: Context, canvas: Canvas, variant: Int, w: Float, h: Float) {
        val bitmap = loadPaper(context, Math.floorMod(variant, PAPERS.size), max(w, h).toInt())
        if (bitmap == null) {
            canvas.drawColor(0xFF202124.toInt())
            return
        }
        drawCentreCrop(canvas, bitmap, w, h, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun loadPaper(context: Context, index: Int, maxSide: Int): Bitmap? {
        val key = "paper:$index@$maxSide"
        photoCache.get(key)?.let { return it }
        val res = context.resources
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(res, PAPERS[index], bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeResource(res, PAPERS[index],
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        photoCache.put(key, bitmap)
        return bitmap
    }

    /** [path] is a file of ours, or a content URI for photos not imported yet. */
    fun loadPhoto(context: Context, path: String, maxSide: Int): Bitmap? {
        val key = "$path@$maxSide"
        photoCache.get(key)?.let { return it }
        if (path.startsWith("content:")) {
            return runCatching {
                context.contentResolver.loadThumbnail(android.net.Uri.parse(path),
                    android.util.Size(maxSide, maxSide), null)
            }.getOrNull()?.also { photoCache.put(key, it) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        photoCache.put(key, bitmap)
        return bitmap
    }

    fun filter(variant: Int): ColorMatrixColorFilter? {
        val matrix = ColorMatrix()
        when (variant) {
            GalleryWallpaper.FILTER_MONO -> matrix.setSaturation(0f)
            GalleryWallpaper.FILTER_DUOTONE -> {
                // Luminance mapped from deep blue to warm yellow.
                val dark = floatArrayOf(28f, 48f, 110f)
                val light = floatArrayOf(250f, 205f, 120f)
                val l = floatArrayOf(0.299f, 0.587f, 0.114f)
                matrix.set(FloatArray(20).also { m ->
                    for (c in 0..2) {
                        val scale = (light[c] - dark[c]) / 255f
                        m[c * 5] = l[0] * scale
                        m[c * 5 + 1] = l[1] * scale
                        m[c * 5 + 2] = l[2] * scale
                        m[c * 5 + 4] = dark[c]
                    }
                    m[18] = 1f
                })
            }
            GalleryWallpaper.FILTER_WASH -> {
                matrix.setSaturation(0.15f)
                matrix.postConcat(ColorMatrix().apply { setScale(0.85f, 0.95f, 1.1f, 1f) })
            }
            else -> return null
        }
        return ColorMatrixColorFilter(matrix)
    }

    fun drawCentreCrop(canvas: Canvas, bitmap: Bitmap, w: Float, h: Float, paint: Paint) {
        val scale = max(w / bitmap.width, h / bitmap.height)
        val sw = w / scale
        val sh = h / scale
        val left = ((bitmap.width - sw) / 2).toInt()
        val top = ((bitmap.height - sh) / 2).toInt()
        canvas.drawBitmap(bitmap, Rect(left, top, left + sw.toInt(), top + sh.toInt()),
            RectF(0f, 0f, w, h), paint)
    }

    private fun photo(context: Context, canvas: Canvas, path: String?, filter: Int, w: Float,
            h: Float) {
        val bitmap = path?.let { loadPhoto(context, it, max(w, h).toInt()) }
        if (bitmap == null) {
            canvas.drawColor(0xFF202124.toInt())
            return
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter(filter) }
        drawCentreCrop(canvas, bitmap, w, h, paint)
    }

    private fun kaleidoscope(context: Context, canvas: Canvas, wallpaper: GalleryWallpaper,
            w: Float, h: Float) {
        canvas.drawColor(Color.BLACK)
        val bitmap = wallpaper.photos.firstOrNull()?.let { loadPhoto(context, it, 1024) }
            ?: kaleidoscopeSource(context)
        val segments = intArrayOf(6, 8, 12)[Math.floorMod(wallpaper.variant, 3)]
        val sweep = 360f / segments
        val cx = w / 2
        val cy = h / 2
        val r = hypot(w, h) / 2
        // Each wedge shows the same slice of the photo, every other one mirrored.
        val source = min(bitmap.width, bitmap.height).toFloat()
        val scale = r / (source / 2)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val wedge = Path().apply {
            moveTo(cx, cy)
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f - sweep / 2, sweep)
            close()
        }
        for (i in 0 until segments) {
            canvas.save()
            canvas.rotate(i * sweep, cx, cy)
            canvas.clipPath(wedge)
            val m = Matrix()
            m.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
            if (i % 2 == 1) m.postScale(-1f, 1f)
            m.postScale(scale, scale)
            m.postTranslate(cx, cy - r * 0.35f)
            canvas.drawBitmap(bitmap, m, paint)
            canvas.restore()
        }
    }

    /** What a kaleidoscope reflects before a photo is chosen. */
    private fun kaleidoscopeSource(context: Context): Bitmap =
        photoCache.get("kaleidoscope-source") ?: Bitmap.createBitmap(512, 1024,
            Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            bubbles(canvas, 2, 512f, 1024f, 0)
            photoCache.put("kaleidoscope-source", it)
        }

    fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
        )
    }

    /** Whether the top of the wallpaper, where the clock sits, is light. */
    fun isTopLight(context: Context, wallpaper: GalleryWallpaper): Boolean {
        val small = toBitmap(context, wallpaper, 24, 48)
        var sum = 0.0
        for (y in 4 until 20) for (x in 0 until 24) {
            val c = small.getPixel(x, y)
            sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        }
        return sum / (16 * 24) > 165
    }

    /** The colour the home screen takes when it is set to Colour or Gradient. */
    fun dominantColor(context: Context, wallpaper: GalleryWallpaper): Int {
        val small = toBitmap(context, wallpaper, 24, 48)
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
        }
        val n = small.width * small.height
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
