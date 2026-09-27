/*
 * Copyright (C) 2026 The PenguinOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.customization.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * SystemUI's own clock faces, as the clock picker lists them, so the lock screen customiser can
 * offer and preview them rather than leaving them to Wallpaper & style.
 */
object SystemClocks {
    data class Face(val id: String, val name: String, val thumbnail: Drawable)

    var faces by mutableStateOf<List<Face>>(emptyList())

    /** The face SystemUI shows now, for lock screens saved before faces could be chosen here. */
    var selectedId by mutableStateOf<String?>(null)

    fun thumbnail(id: String?): Drawable? = id?.let { faces.firstOrNull { f -> f.id == it } }?.thumbnail

    private val toned = HashMap<Pair<String, Int>, Bitmap>()

    /**
     * The thumbnail in [color]'s tones. The thumbnails are drawn in three greys, which SystemUI
     * shows as a pale tint, the colour itself and a deeper shade of it.
     */
    fun toned(id: String, color: Int): Bitmap? = toned.getOrPut(id to color) {
        val drawable = thumbnail(id) ?: return null
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = TONED_WIDTH.toFloat() / w
        val bitmap = Bitmap.createBitmap(TONED_WIDTH, (h * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        drawable.copyBounds().let { old ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            drawable.bounds = old
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            // Unpremultiplied grey, 0 to 255.
            val grey = (Color.red(p) * 0.3f + Color.green(p) * 0.59f + Color.blue(p) * 0.11f)
            fun tone(c: Int) = if (grey <= MID) {
                c * (DARK_SHADE + (1 - DARK_SHADE) * ((grey - DARK) / (MID - DARK)).coerceIn(0f, 1f))
            } else {
                c + (255 - c) * PALE_TINT * ((grey - MID) / (LIGHT - MID)).coerceIn(0f, 1f)
            }
            pixels[i] = Color.argb(a, tone(r).toInt(), tone(g).toInt(), tone(b).toInt())
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap
    }

    private const val TONED_WIDTH = 480
    private const val LIGHT = 227f
    private const val MID = 158f
    private const val DARK = 106f
    private const val PALE_TINT = 0.75f
    private const val DARK_SHADE = 0.76f
}
