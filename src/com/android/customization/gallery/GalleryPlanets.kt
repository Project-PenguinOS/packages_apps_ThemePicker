package com.android.customization.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import com.android.themepicker.R
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The Astronomy wallpapers: planets lit as they are right now. */
object GalleryPlanets {

    // The terminator moves about a degree in five minutes.
    private const val REDRAW_MS = 5 * 60_000L
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var land: IntArray? = null
    private var landWidth = 0
    private var landHeight = 0

    fun draw(context: Context, canvas: Canvas, variant: Int, w: Float, h: Float, timeMs: Long) {
        canvas.drawColor(Color.BLACK)
        stars(canvas, w, h)
        if (variant == GalleryWallpaper.ASTRO_SOLAR) {
            solarSystem(canvas, w, h, timeMs)
            return
        }
        val detail = variant == GalleryWallpaper.ASTRO_EARTH_DETAIL ||
            variant == GalleryWallpaper.ASTRO_MOON_DETAIL
        val radius = if (detail) w * 1.1f else w * 0.43f
        val cx = w / 2
        val cy = if (detail) h * 1.02f else h * 0.55f
        val size = min(radius * 2, 1400f).toInt().coerceAtLeast(16)
        val minute = timeMs / REDRAW_MS
        val key = "$variant/$size/$minute"
        val sphere = cache.get(key) ?: renderSphere(context, variant, size, timeMs).also {
            cache.put(key, it)
        }
        if (variant != GalleryWallpaper.ASTRO_MOON && variant != GalleryWallpaper.ASTRO_MOON_DETAIL) {
            // A thin atmosphere around Earth and Mars.
            val glow = Paint(Paint.ANTI_ALIAS_FLAG)
            val tint = if (variant == GalleryWallpaper.ASTRO_MARS) 0x66E07A4A else 0x664F9BFF
            glow.shader = RadialGradient(cx, cy, radius * 1.08f,
                intArrayOf(tint, tint, 0), floatArrayOf(0f, 0.92f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius * 1.08f, glow)
        }
        val dst = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawBitmap(sphere, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun stars(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val random = java.util.Random(7)
        repeat(160) {
            paint.color = Color.argb(60 + random.nextInt(140), 255, 255, 255)
            canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h,
                max(0.6f, w / 900f) * (0.5f + random.nextFloat()), paint)
        }
    }

    // Ephemeris, good to a fraction of a degree for decades around J2000.

    private fun daysSinceJ2000(timeMs: Long) = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0

    /** Latitude and longitude, in radians, where the sun is overhead. */
    fun subsolarPoint(timeMs: Long): DoubleArray {
        val n = daysSinceJ2000(timeMs)
        val l = Math.toRadians(280.460 + 0.9856474 * n)
        val g = Math.toRadians(357.528 + 0.9856003 * n)
        val lambda = l + Math.toRadians(1.915) * sin(g) + Math.toRadians(0.020) * sin(2 * g)
        val epsilon = Math.toRadians(23.439 - 0.0000004 * n)
        val declination = asin(sin(epsilon) * sin(lambda))
        val ra = atan2(cos(epsilon) * sin(lambda), cos(lambda))
        val gmst = Math.toRadians((280.46061837 + 360.98564736629 * n) % 360)
        return doubleArrayOf(declination, wrap(ra - gmst))
    }

    /** 0 at new moon, 0.5 at full. */
    fun moonPhase(timeMs: Long): Double {
        val days = daysSinceJ2000(timeMs) + 2451545.0 - 2451550.1
        val p = days / 29.530588853
        return p - floor(p)
    }

    private fun wrap(a: Double): Double {
        var x = (a + PI) % (2 * PI)
        if (x < 0) x += 2 * PI
        return x - PI
    }

    // Spheres

    private fun renderSphere(context: Context, variant: Int, size: Int, timeMs: Long): Bitmap {
        val out = IntArray(size * size)
        val moon = variant == GalleryWallpaper.ASTRO_MOON ||
            variant == GalleryWallpaper.ASTRO_MOON_DETAIL
        val mars = variant == GalleryWallpaper.ASTRO_MARS
        if (!moon && !mars) loadLand(context)

        // Look at the Earth from above the user's time zone, as iOS does.
        val lat0 = if (moon) 0.0 else Math.toRadians(if (mars) 20.0 else 18.0)
        val lon0 = if (moon) 0.0
        else Math.toRadians(TimeZone.getDefault().getOffset(timeMs) / 3_600_000.0 * 15)
        val fx = cos(lat0) * cos(lon0)
        val fy = cos(lat0) * sin(lon0)
        val fz = sin(lat0)
        val ex = -sin(lon0)
        val ey = cos(lon0)
        val nx = -sin(lat0) * cos(lon0)
        val ny = -sin(lat0) * sin(lon0)
        val nz = cos(lat0)

        // Light, in the same frame as the surface points.
        val light: DoubleArray = if (moon) {
            val theta = 2 * PI * moonPhase(timeMs)
            // Expressed in view space: +x right, +z toward the viewer.
            doubleArrayOf(sin(theta), 0.0, -cos(theta))
        } else {
            val sun = subsolarPoint(timeMs + if (mars) 5 * 3_600_000L else 0)
            doubleArrayOf(cos(sun[0]) * cos(sun[1]), cos(sun[0]) * sin(sun[1]), sin(sun[0]))
        }

        val r = size / 2.0
        for (py in 0 until size) {
            val y = (py + 0.5 - r) / r
            for (px in 0 until size) {
                val x = (px + 0.5 - r) / r
                val d2 = x * x + y * y
                if (d2 > 1) continue
                val z = sqrt(1 - d2)
                val wx = z * fx + x * ex - y * nx
                val wy = z * fy + x * ey - y * ny
                val wz = z * fz - y * nz
                val lat = asin(wz.coerceIn(-1.0, 1.0))
                val lon = atan2(wy, wx)
                val lit = if (moon) x * light[0] + z * light[2]
                else wx * light[0] + wy * light[1] + wz * light[2]
                val base = when {
                    moon -> moonColor(lat, lon)
                    mars -> marsColor(lat, lon)
                    else -> earthColor(lat, lon, lit)
                }
                // Soft terminator, darker limb.
                val day = smooth(-0.06, 0.12, lit)
                val limb = 0.55 + 0.45 * z
                val night = if (moon || mars) 0.03 else 0.06
                val k = (night + (1 - night) * day) * limb
                var c = scale(base, k)
                if (!moon && !mars && day < 0.5 && isLand(lat, lon) &&
                        hash(lat * 400, lon * 400) > 0.93) {
                    c = add(c, 0xFFE8A04A.toInt(), (0.5 - day) * 1.2)
                }
                out[py * size + px] = c
            }
        }
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun loadLand(context: Context) {
        if (land != null) return
        val bitmap = BitmapFactory.decodeResource(context.resources, R.raw.gallery_earth_land,
            BitmapFactory.Options().apply { inScaled = false })
        landWidth = bitmap.width
        landHeight = bitmap.height
        land = IntArray(landWidth * landHeight).also {
            bitmap.getPixels(it, 0, landWidth, 0, 0, landWidth, landHeight)
        }
    }

    private fun isLand(lat: Double, lon: Double): Boolean {
        val pixels = land ?: return false
        val u = ((lon + PI) / (2 * PI) * landWidth).toInt().coerceIn(0, landWidth - 1)
        val v = ((PI / 2 - lat) / PI * landHeight).toInt().coerceIn(0, landHeight - 1)
        return (pixels[v * landWidth + u] and 0xFF) > 127
    }

    private fun earthColor(lat: Double, lon: Double, lit: Double): Int {
        val latDeg = Math.toDegrees(lat)
        val n = noise(lat * 6, lon * 6)
        var c = if (isLand(lat, lon)) {
            when {
                kotlin.math.abs(latDeg) > 66 -> 0xFFE9EEF2.toInt()
                else -> {
                    // Deserts around the tropics, green elsewhere.
                    val dry = (1 - kotlin.math.abs(kotlin.math.abs(latDeg) - 24) / 14)
                        .coerceIn(0.0, 1.0) * 0.8 + (n - 0.5) * 0.6
                    GalleryRenderer.blend(0xFF3E6B2F.toInt(), 0xFFC2A36A.toInt(),
                        dry.toFloat().coerceIn(0f, 1f))
                }
            }
        } else {
            GalleryRenderer.blend(0xFF0A2A5E.toInt(), 0xFF14508F.toInt(), n.toFloat())
        }
        val clouds = smooth(0.55, 0.8, noise(lat * 3 + 11, lon * 3 + 5) * 0.7 +
            noise(lat * 9, lon * 9) * 0.3)
        c = add(c, 0xFFFFFFFF.toInt(), clouds * 0.85)
        return c
    }

    private val MARIA = arrayOf(
        doubleArrayOf(33.0, -16.0, 17.0), doubleArrayOf(28.0, 17.0, 10.0),
        doubleArrayOf(8.0, 31.0, 12.0), doubleArrayOf(17.0, 59.0, 8.0),
        doubleArrayOf(-8.0, 51.0, 9.0), doubleArrayOf(-21.0, -17.0, 10.0),
        doubleArrayOf(18.0, -57.0, 24.0), doubleArrayOf(56.0, 0.0, 7.0),
        doubleArrayOf(-15.0, 35.0, 5.0), doubleArrayOf(-24.0, -39.0, 6.0),
        doubleArrayOf(-2.0, -12.0, 7.0),
    )

    private fun moonColor(lat: Double, lon: Double): Int {
        val la = Math.toDegrees(lat)
        val lo = Math.toDegrees(lon)
        var dark = 0.0
        for (m in MARIA) {
            val d = sqrt((la - m[0]) * (la - m[0]) + (lo - m[1]) * (lo - m[1]) * cos(lat) * cos(lat))
            dark = max(dark, smooth(m[2] * 1.15, m[2] * 0.7, d))
        }
        dark *= 0.75 + 0.25 * noise(lat * 20, lon * 20)
        var v = 0.72 - 0.34 * dark + (noise(lat * 40, lon * 40) - 0.5) * 0.12
        // Small craters.
        val crater = hash(floor(la / 3), floor(lo / 3))
        if (crater > 0.8) v += 0.06 * (crater - 0.8) * 5
        // Tycho and its bright surroundings.
        val tycho = sqrt((la + 43) * (la + 43) + (lo + 11) * (lo + 11))
        v += 0.18 * smooth(6.0, 1.0, tycho)
        val g = (v.coerceIn(0.0, 1.0) * 255).toInt()
        return Color.rgb(g, g, (g * 0.98).toInt())
    }

    private fun marsColor(lat: Double, lon: Double): Int {
        val n = noise(lat * 5, lon * 5) * 0.6 + noise(lat * 15, lon * 15) * 0.4
        var c = GalleryRenderer.blend(0xFFC2623A.toInt(), 0xFF6B2F1E.toInt(),
            smooth(0.5, 0.75, n).toFloat())
        if (kotlin.math.abs(Math.toDegrees(lat)) > 76 - 6 * n) c = 0xFFF2EDE8.toInt()
        return c
    }

    // Solar system

    private val PLANETS = arrayOf(
        // mean longitude at J2000 (deg), rate (deg/day), colour, size
        doubleArrayOf(252.25, 4.09233, 0xFFB0A9A2.toDouble(), 0.010),
        doubleArrayOf(181.98, 1.60213, 0xFFE6C98E.toDouble(), 0.016),
        doubleArrayOf(100.46, 0.98561, 0xFF4E8FE0.toDouble(), 0.017),
        doubleArrayOf(355.43, 0.52403, 0xFFD2643D.toDouble(), 0.013),
        doubleArrayOf(34.35, 0.08309, 0xFFD9B38C.toDouble(), 0.034),
        doubleArrayOf(50.08, 0.03346, 0xFFE8D29C.toDouble(), 0.029),
        doubleArrayOf(314.06, 0.01173, 0xFF9AD8E3.toDouble(), 0.022),
        doubleArrayOf(304.35, 0.00598, 0xFF4F6FD6.toDouble(), 0.021),
    )

    private fun solarSystem(canvas: Canvas, w: Float, h: Float, timeMs: Long) {
        val cx = w / 2
        val cy = h * 0.58f
        val n = daysSinceJ2000(timeMs)
        val orbit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, w / 500f)
            color = 0x33FFFFFF
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        body.shader = RadialGradient(cx, cy, w * 0.07f,
            intArrayOf(0xFFFFF3C4.toInt(), 0xFFFFB02E.toInt(), 0x00FF8A00), floatArrayOf(0f,
                0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, w * 0.07f, body)
        body.shader = null
        PLANETS.forEachIndexed { i, p ->
            // Evenly spaced rings read better than true distances.
            val radius = w * (0.1f + 0.05f * i)
            canvas.drawCircle(cx, cy, radius, orbit)
            val angle = Math.toRadians(p[0] + p[1] * n)
            val x = cx + radius * cos(angle).toFloat()
            val y = cy - radius * sin(angle).toFloat()
            body.color = p[2].toLong().toInt()
            canvas.drawCircle(x, y, w * p[3].toFloat(), body)
            if (i == 5) {
                orbit.color = 0x88E8D29C.toInt()
                canvas.drawOval(x - w * 0.05f, y - w * 0.012f, x + w * 0.05f, y + w * 0.012f,
                    orbit)
                orbit.color = 0x33FFFFFF
            }
        }
    }

    // Helpers

    private fun smooth(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun hash(x: Double, y: Double): Double {
        val s = sin(x * 127.1 + y * 311.7) * 43758.5453
        return s - floor(s)
    }

    /** Smooth value noise in 0..1. */
    private fun noise(x: Double, y: Double): Double {
        val ix = floor(x)
        val iy = floor(y)
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * (3 - 2 * fx)
        val uy = fy * fy * (3 - 2 * fy)
        val a = hash(ix, iy)
        val b = hash(ix + 1, iy)
        val c = hash(ix, iy + 1)
        val d = hash(ix + 1, iy + 1)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    private fun scale(c: Int, k: Double): Int =
        Color.rgb((Color.red(c) * k).toInt().coerceIn(0, 255),
            (Color.green(c) * k).toInt().coerceIn(0, 255),
            (Color.blue(c) * k).toInt().coerceIn(0, 255))

    private fun add(c: Int, over: Int, t: Double) = GalleryRenderer.blend(c, over,
        t.toFloat().coerceIn(0f, 1f))
}
