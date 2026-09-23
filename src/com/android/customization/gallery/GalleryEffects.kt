package com.android.customization.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import com.android.themepicker.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * The lock-to-home photo effects ported from Atmo Engine (saad-khan-rind/NOSAtmosphereEffect,
 * MIT): each is one of its shaders run between a locked and an unlocked progress.
 */
enum class GalleryEffect(
    val label: Int,
    val shader: Int,
    val locked: Float,
    val unlocked: Float,
    val durationMs: Long,
) {
    NONE(R.string.gallery_effect_none, 0, 0f, 0f, 0L),
    ATMOSPHERE(R.string.gallery_effect_atmosphere, R.raw.gallery_fx_atmosphere, 0f, 1f, 2_500L),
    ATMOSPHERE_REVERSE(R.string.gallery_effect_atmosphere_reverse,
        R.raw.gallery_fx_atmosphere_reverse, 1f, 0f, 1_500L),
    GLASS(R.string.gallery_effect_glass, R.raw.gallery_fx_glass, 0f, 1f, 1_200L),
    GLASS_REVERSE(R.string.gallery_effect_glass_reverse, R.raw.gallery_fx_glass, 1f, 0f, 1_200L),
    FROSTED(R.string.gallery_effect_frosted, R.raw.gallery_fx_frosted, 0f, 1f, 500L),
    FROSTED_REVERSE(R.string.gallery_effect_frosted_reverse, R.raw.gallery_fx_frosted, 1f, 0f,
        500L),
    COLOUR_FILL(R.string.gallery_effect_colour_fill, R.raw.gallery_fx_colorfill, 1f, 0f, 1_500L),
    COLOUR_FILL_REVERSE(R.string.gallery_effect_colour_fill_reverse,
        R.raw.gallery_fx_colorfill_reverse, 0f, 1f, 1_500L),
    HALFTONE(R.string.gallery_effect_halftone, R.raw.gallery_fx_halftone, 0f, 1f, 500L),
    HALFTONE_REVERSE(R.string.gallery_effect_halftone_reverse, R.raw.gallery_fx_halftone_reverse,
        0f, 1f, 500L);

    companion object {
        fun of(index: Int): GalleryEffect = entries.getOrElse(index) { NONE }
    }
}

/**
 * Draws a [GalleryEffect] over a photo into a [Surface] with GLES 3. Not thread safe: create,
 * use and release it on one thread.
 */
class GalleryEffectRenderer(private val context: Context) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var width = 0
    private var height = 0

    private var effect = GalleryEffect.NONE
    private var photo: String? = null
    private var program = 0
    private val textures = IntArray(4)
    private var hasTextures = false
    private val blobColors = FloatArray(MAX_BLOBS * 3)
    private val blobStart = FloatArray(MAX_BLOBS * 2)
    private val blobEnd = FloatArray(MAX_BLOBS * 2)
    private val blobBend = FloatArray(MAX_BLOBS * 2)
    private val blobSize = FloatArray(MAX_BLOBS)
    private val blobPositions = FloatArray(MAX_BLOBS * 2)
    private val blobSizes = FloatArray(MAX_BLOBS)
    private var blobCount = 0

    private val quad: FloatBuffer =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v; v is flipped so the bitmap's top row lands at the top.
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

    fun attach(surface: Surface, w: Int, h: Int): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
            config = configs[0] ?: return false
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        }
        detachSurface()
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface,
            intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
        resize(w, h)
        return true
    }

    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        // The photo is cropped to the surface, so a new size needs new textures.
        photo?.let { load(it, effect, force = true) }
    }

    /** Loads the photo and the effect's program; cheap when neither changed. */
    fun load(path: String, effect: GalleryEffect, force: Boolean = false) {
        if (eglSurface == EGL14.EGL_NO_SURFACE || width == 0) {
            photo = path
            this.effect = effect
            return
        }
        if (effect != this.effect || program == 0) {
            if (program != 0) GLES30.glDeleteProgram(program)
            program = if (effect == GalleryEffect.NONE) 0 else build(effect.shader)
            this.effect = effect
        }
        if (force || path != photo || !hasTextures) {
            photo = path
            uploadTextures(path)
        }
    }

    fun draw(progress: Float) {
        if (eglSurface == EGL14.EGL_NO_SURFACE || program == 0 || !hasTextures) return
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        updateBlobs(progress)
        for (i in 0 until 4) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
        }
        set1i("uTextureSharp", 0)
        set1i("uTexture", 0)
        set1i("uTextureBlur", 1)
        set1i("uSubjectMask", 2)
        set1i("uClockSubjectMask", 2)
        set1i("uClockTexture", 3)
        set1f("uBlurStrength", progress)
        set1f("uProgress", progress)
        set1f("uAspectRatio", width.toFloat() / height)
        set1f("uDimLevel", if (effect == GalleryEffect.ATMOSPHERE ||
            effect == GalleryEffect.ATMOSPHERE_REVERSE) 0.2f else 0.1f)
        set1f("uEnableNoise", 1f)
        set1f("uNoiseScale", 2_000f)
        set1f("uNoiseStrength", 0.06f)
        set1f("uSaturation", 1f)
        set1f("uContrast", 1f)
        set1f("uAtmosphereGlassEnabled", 0f)
        set1f("uGlassLineCount", 28f)
        set1f("uGlassLineThickness", 0.775f)
        set1f("uLineCount", 28f)
        set1f("uLineThickness", 0.775f)
        set1f("uTransitionStyle", 0f)
        set1f("uScrollOffsetX", 0.5f)
        set1f("uScrollWindowX", 1f)
        set1f("uBackgroundOnly", 0f)
        set1f("uHasSubject", 0f)
        set1f("uDrawerBlur", 0f)
        set1f("uDotSize", 12f)
        set1f("uGrayscale", 0f)
        // Colour fill spreads from the under-display fingerprint sensor.
        loc("uOrigin").takeIf { it >= 0 }?.let { GLES30.glUniform2f(it, 0.5f, 0.86f) }
        set1f("uClockEnabled", 0f)
        set1f("uClockOpacity", 0f)
        set1f("uClockDepth", 0f)
        set1f("uClockGlass", 0f)
        if (blobCount > 0) {
            loc("uBlobColors").takeIf { it >= 0 }?.let {
                GLES30.glUniform3fv(it, blobCount, blobColors, 0)
            }
            loc("uBlobPositions").takeIf { it >= 0 }?.let {
                GLES30.glUniform2fv(it, blobCount, blobPositions, 0)
            }
            loc("uBlobSizes").takeIf { it >= 0 }?.let {
                GLES30.glUniform1fv(it, blobCount, blobSizes, 0)
            }
        }
        loc("uBlobCount").takeIf { it >= 0 }?.let { GLES30.glUniform1i(it, blobCount) }

        val position = GLES30.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES30.glVertexAttribPointer(texCoord, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(texCoord)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun loc(name: String) = GLES30.glGetUniformLocation(program, name)
    private fun set1f(name: String, value: Float) {
        val l = loc(name)
        if (l >= 0) GLES30.glUniform1f(l, value)
    }
    private fun set1i(name: String, value: Int) {
        val l = loc(name)
        if (l >= 0) GLES30.glUniform1i(l, value)
    }

    fun detachSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            if (hasTextures) GLES30.glDeleteTextures(4, textures, 0)
            if (program != 0) GLES30.glDeleteProgram(program)
        }
        detachSurface()
        EGL14.eglDestroyContext(display, eglContext)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        program = 0
        hasTextures = false
        width = 0
        height = 0
    }

    // Program

    private fun build(shader: Int): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, raw(R.raw.gallery_fx_quad_vert))
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, raw(shader))
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Link failed: " + GLES30.glGetProgramInfoLog(id))
        }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source)
        GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Compile failed: " + GLES30.glGetShaderInfoLog(id))
        return id
    }

    private fun raw(id: Int) =
        context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    // Textures

    private fun uploadTextures(path: String) {
        val source = GalleryRenderer.loadPhoto(context, path, max(width, height)) ?: return
        // Cropped to the screen as the static wallpaper would be.
        val sharp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        GalleryRenderer.drawCentreCrop(Canvas(sharp), source, width.toFloat(), height.toFloat(),
            Paint(Paint.FILTER_BITMAP_FLAG))
        // Blurred at a quarter size; the shaders only ever sample it softly.
        val small = Bitmap.createScaledBitmap(sharp, max(1, width / 4), max(1, height / 4), true)
        val blur = GalleryApplier.blur(small, 24f)
        if (hasTextures) GLES30.glDeleteTextures(4, textures, 0)
        GLES30.glGenTextures(4, textures, 0)
        upload(textures[0], sharp)
        upload(textures[1], blur)
        // No subject isolation or clock: one transparent pixel for both.
        val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        upload(textures[2], empty)
        upload(textures[3], empty)
        hasTextures = true
        makeBlobs(blur)
    }

    private fun upload(id: Int, bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    // Atmosphere's drifting colour clouds, seeded from the photo's own colours.

    private fun makeBlobs(blurred: Bitmap) {
        val grid = 4
        val random = Random(blurred.getPixel(0, 0).toLong())
        blobCount = 0
        val cellW = blurred.width / grid
        val cellH = blurred.height / grid
        for (gy in 0 until grid) for (gx in 0 until grid) {
            if (blobCount >= MAX_BLOBS || cellW == 0 || cellH == 0) break
            val c = averageColor(blurred, Rect(gx * cellW, gy * cellH, (gx + 1) * cellW,
                (gy + 1) * cellH))
            val i = blobCount++
            blobColors[i * 3] = Color.red(c) / 255f
            blobColors[i * 3 + 1] = Color.green(c) / 255f
            blobColors[i * 3 + 2] = Color.blue(c) / 255f
            blobStart[i * 2] = (gx + 0.5f) / grid
            blobStart[i * 2 + 1] = (gy + 0.5f) / grid
            blobEnd[i * 2] = 0.05f + random.nextFloat() * 0.9f
            blobEnd[i * 2 + 1] = 0.05f + random.nextFloat() * 0.9f
            blobBend[i * 2] = (blobStart[i * 2] + blobEnd[i * 2]) / 2 +
                (random.nextFloat() - 0.5f) * 0.5f
            blobBend[i * 2 + 1] = (blobStart[i * 2 + 1] + blobEnd[i * 2 + 1]) / 2 +
                (random.nextFloat() - 0.5f) * 0.5f
            blobSize[i] = 0.12f + random.nextFloat() * 0.08f
        }
    }

    /** As Atmo does: blobs travel a curved path and grow once the transition gets going. */
    private fun updateBlobs(strength: Float) {
        val t = ((strength.coerceIn(0f, 1f) - 0.1f) / 0.9f).coerceIn(0f, 1f)
        val p = 1f - (1f - t) * (1f - t) * (1f - t)
        val u = 1f - p
        for (i in 0 until blobCount) {
            blobPositions[i * 2] = u * u * blobStart[i * 2] + 2 * u * p * blobBend[i * 2] +
                p * p * blobEnd[i * 2]
            blobPositions[i * 2 + 1] = u * u * blobStart[i * 2 + 1] +
                2 * u * p * blobBend[i * 2 + 1] + p * p * blobEnd[i * 2 + 1]
            blobSizes[i] = 0.05f + (blobSize[i] - 0.05f) * p
        }
    }

    private fun averageColor(bitmap: Bitmap, r: Rect): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var n = 0
        val step = max(1, min(r.width(), r.height()) / 6)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val c = bitmap.getPixel(x, y)
                red += Color.red(c)
                green += Color.green(c)
                blue += Color.blue(c)
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) Color.GRAY
        else Color.rgb((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
    }

    companion object {
        private const val TAG = "GalleryEffects"
        private const val MAX_BLOBS = 16
        private const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
