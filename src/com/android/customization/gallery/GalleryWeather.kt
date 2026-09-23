package com.android.customization.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import java.util.Calendar
import kotlin.math.sin

/** The Weather wallpaper: the sky outside, from OmniJaws, animated. */
object GalleryWeather {

    private const val TAG = "GalleryWeather"
    private val WEATHER_URI = Uri.parse("content://org.omnirom.omnijaws.provider/weather")

    enum class Sky { CLEAR, CLOUDY, RAIN, SNOW, THUNDER, FOG }

    data class Snapshot(val sky: Sky, val night: Boolean, val temperature: String?,
            val city: String?)

    /** Null when OmniJaws is off or has nothing yet; the wallpaper then shows a clear sky. */
    fun query(context: Context): Snapshot? =
        try {
            context.contentResolver.query(WEATHER_URI,
                arrayOf("city", "condition_code", "temperature"), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val code = c.getInt(1)
                Snapshot(skyFor(code), isNight(code), c.getString(2), c.getString(0))
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "No weather", e)
            null
        }

    // Yahoo condition codes, which OmniJaws uses.
    private fun skyFor(code: Int) = when (code) {
        in 0..4, in 37..39, 45, 47 -> Sky.THUNDER
        5, 7, in 13..16, 18, in 41..43, 46 -> Sky.SNOW
        6, in 8..12, 35, 40 -> Sky.RAIN
        in 19..22 -> Sky.FOG
        in 26..30, 44 -> Sky.CLOUDY
        else -> Sky.CLEAR
    }

    private fun isNight(code: Int): Boolean {
        if (code == 27 || code == 29 || code == 31 || code == 33) return true
        if (code == 28 || code == 30 || code == 32 || code == 34) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 6 || hour >= 19
    }

    /** Returns true while it wants more frames. */
    fun draw(canvas: Canvas, weather: Snapshot?, w: Float, h: Float, timeMs: Long): Boolean {
        val sky = weather?.sky ?: Sky.CLEAR
        val night = weather?.night ?: isNight(-1)
        val colors = when (sky) {
            Sky.CLEAR -> if (night) intArrayOf(0xFF070C22.toInt(), 0xFF223868.toInt())
            else intArrayOf(0xFF2A74D6.toInt(), 0xFF8CC6F4.toInt())
            Sky.CLOUDY -> if (night) intArrayOf(0xFF151A26.toInt(), 0xFF3B4556.toInt())
            else intArrayOf(0xFF6B84A3.toInt(), 0xFFC3CEDB.toInt())
            Sky.RAIN -> intArrayOf(0xFF2E3846.toInt(), 0xFF6F7D8E.toInt())
            Sky.SNOW -> intArrayOf(0xFF8397AD.toInt(), 0xFFE2E9F0.toInt())
            Sky.THUNDER -> intArrayOf(0xFF151821.toInt(), 0xFF434A5A.toInt())
            Sky.FOG -> intArrayOf(0xFF8E98A3.toInt(), 0xFFD8DCE0.toInt())
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h, colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val t = timeMs / 1000.0

        if (sky == Sky.CLEAR) {
            if (night) {
                val random = java.util.Random(3)
                repeat(120) {
                    val twinkle = 0.6 + 0.4 * sin(t * (0.5 + random.nextDouble()) +
                        random.nextDouble() * 6)
                    paint.color = Color.argb((twinkle * 200).toInt(), 255, 255, 255)
                    canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h * 0.7f,
                        w / 600f * (1 + random.nextFloat()), paint)
                }
                paint.color = 0xFFF4F1E4.toInt()
                canvas.drawCircle(w * 0.78f, h * 0.2f, w * 0.06f, paint)
            } else {
                paint.shader = RadialGradient(w * 0.82f, h * 0.12f, w * 0.7f,
                    intArrayOf(0xCCFFF6D8.toInt(), 0x33FFE9A8, 0), floatArrayOf(0f, 0.3f, 1f),
                    Shader.TileMode.CLAMP)
                canvas.drawCircle(w * 0.82f, h * 0.12f, w * 0.7f, paint)
                paint.shader = null
            }
        }

        if (sky != Sky.CLEAR) {
            // Two layers of drifting cloud.
            val cloud = if (sky == Sky.SNOW || sky == Sky.FOG) 0x88FFFFFF.toInt()
            else if (night || sky == Sky.THUNDER) 0x663A4150 else 0x77E8EDF3
            for (layer in 0..1) {
                val speed = 6.0 + layer * 5
                val shift = ((t * speed) % (w * 1.6)).toFloat()
                for (i in 0 until 5) {
                    val cx = (i * w * 0.42f + shift) % (w * 1.6f) - w * 0.3f
                    val cy = h * (0.08f + 0.1f * layer + 0.05f * (i % 3))
                    val r = w * (0.28f + 0.06f * (i % 2))
                    paint.shader = RadialGradient(cx, cy, r, intArrayOf(cloud, 0), null,
                        Shader.TileMode.CLAMP)
                    canvas.drawCircle(cx, cy, r, paint)
                }
            }
            paint.shader = null
        }

        when (sky) {
            Sky.RAIN, Sky.THUNDER -> {
                paint.color = 0x55CFE0F2
                paint.strokeWidth = w / 450f
                val random = java.util.Random(11)
                repeat(140) {
                    val x = random.nextFloat() * w
                    val speed = 0.9f + random.nextFloat()
                    val y = ((random.nextFloat() * h + t * h * 0.9 * speed) % (h * 1.1)).toFloat() -
                        h * 0.05f
                    canvas.drawLine(x, y, x - w * 0.01f, y + h * 0.03f, paint)
                }
                if (sky == Sky.THUNDER && (t % 7.0) < 0.12) {
                    canvas.drawColor(0x55FFFFFF)
                }
            }
            Sky.SNOW -> {
                paint.color = 0xDDFFFFFF.toInt()
                val random = java.util.Random(5)
                repeat(110) {
                    val x0 = random.nextFloat() * w
                    val speed = 0.05f + random.nextFloat() * 0.08f
                    val y = ((random.nextFloat() * h + t * h * speed) % (h * 1.05)).toFloat()
                    val x = x0 + (w * 0.02f * sin(t + x0).toFloat())
                    canvas.drawCircle(x, y, w / 300f * (1 + random.nextFloat() * 1.5f), paint)
                }
            }
            Sky.FOG -> {
                for (i in 0 until 4) {
                    val y = h * (0.35f + 0.15f * i) + (h * 0.02f * sin(t / 4 + i)).toFloat()
                    paint.shader = LinearGradient(0f, y - h * 0.08f, 0f, y + h * 0.08f,
                        intArrayOf(0, 0x66FFFFFF, 0), null, Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, y - h * 0.08f, w, y + h * 0.08f, paint)
                }
                paint.shader = null
            }
            else -> {}
        }
        return sky != Sky.CLEAR || night
    }
}
