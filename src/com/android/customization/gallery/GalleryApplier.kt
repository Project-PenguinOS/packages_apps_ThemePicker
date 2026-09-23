package com.android.customization.gallery

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/** Puts a [LockScreen]'s wallpapers on the device. The clock is applied by the caller. */
object GalleryApplier {

    private const val TAG = "GalleryApplier"
    private const val DEPTH_ENABLED = "depth_wallpaper_enabled"
    private const val ACTION_EXTRACT_DEPTH = "com.android.settings.action.EXTRACT_DEPTH_SUBJECT_NOW"

    fun screenSize(context: Context): Pair<Int, Int> {
        val bounds = context.getSystemService(WindowManager::class.java)!!
            .maximumWindowMetrics.bounds
        return min(bounds.width(), bounds.height()) to max(bounds.width(), bounds.height())
    }

    /** Blocking; call off the main thread. */
    fun apply(context: Context, lockScreen: LockScreen) {
        val wm = WallpaperManager.getInstance(context)
        val store = GalleryStore.get(context)
        val (w, h) = screenSize(context)
        val wallpaper = lockScreen.wallpaper
        val live = ComponentName(context, GalleryWallpaperService::class.java)
        try {
            if (lockScreen.home == HomeStyle.PAIR) {
                store.setLiveWallpaper(lock = false, wallpaper = null)
                if (wallpaper.kind.live) {
                    store.setLiveWallpaper(lock = true, wallpaper = wallpaper)
                    wm.setWallpaperComponentWithFlags(live,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                } else {
                    store.setLiveWallpaper(lock = true, wallpaper = null)
                    wm.setBitmap(GalleryRenderer.toBitmap(context, wallpaper, w, h), null, true,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                }
            } else {
                // Home first, so the old shared wallpaper doesn't linger on the lock screen.
                store.setLiveWallpaper(lock = false, wallpaper = null)
                wm.setBitmap(homeBitmap(context, lockScreen, w, h), null, true,
                    WallpaperManager.FLAG_SYSTEM)
                if (wallpaper.kind.live) {
                    store.setLiveWallpaper(lock = true, wallpaper = wallpaper)
                    wm.setWallpaperComponentWithFlags(live, WallpaperManager.FLAG_LOCK)
                } else {
                    store.setLiveWallpaper(lock = true, wallpaper = null)
                    wm.setBitmap(GalleryRenderer.toBitmap(context, wallpaper, w, h), null, true,
                        WallpaperManager.FLAG_LOCK)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not set the wallpaper", e)
            return
        }
        applyDepth(context, wallpaper.kind == GalleryWallpaper.Kind.PHOTO && wallpaper.depth)
        store.setCurrent(lockScreen.id)
        store.pruneImportedPhotos()
    }

    /** SystemUI's depth wallpaper lifts the photo's subject over the clock, like iOS's 3D. */
    private fun applyDepth(context: Context, depth: Boolean) {
        val resolver = context.contentResolver
        val enabled = Settings.System.getInt(resolver, DEPTH_ENABLED, 0) != 0
        if (!depth && !enabled) return
        try {
            Settings.System.putInt(resolver, DEPTH_ENABLED, if (depth) 1 else 0)
            if (depth) {
                context.startService(Intent(ACTION_EXTRACT_DEPTH).setPackage("com.android.settings"))
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not change the depth effect", e)
        }
    }

    fun homeBitmap(context: Context, lockScreen: LockScreen, w: Int, h: Int): Bitmap =
        when (lockScreen.home) {
            HomeStyle.BLUR -> blur(GalleryRenderer.toBitmap(context, lockScreen.wallpaper, w, h),
                context.resources.displayMetrics.density * 40)
            HomeStyle.COLOUR, HomeStyle.GRADIENT -> {
                val color = GalleryRenderer.dominantColor(context, lockScreen.wallpaper)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val canvas = Canvas(bitmap)
                    if (lockScreen.home == HomeStyle.COLOUR) {
                        canvas.drawColor(color)
                    } else {
                        val paint = Paint()
                        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                            GalleryRenderer.blend(color, Color.WHITE, 0.3f),
                            GalleryRenderer.blend(color, Color.BLACK, 0.45f), Shader.TileMode.CLAMP)
                        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                    }
                }
            }
            HomeStyle.PAIR -> GalleryRenderer.toBitmap(context, lockScreen.wallpaper, w, h)
        }

    fun blur(source: Bitmap, radius: Float): Bitmap {
        val w = source.width
        val h = source.height
        val content = RenderNode("GalleryBlur").apply {
            setPosition(0, 0, w, h)
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        content.beginRecording().apply {
            drawBitmap(source, 0f, 0f, null)
            drawColor(0x33000000)
        }
        content.endRecording()
        val root = RenderNode("GalleryBlurRoot").apply { setPosition(0, 0, w, h) }
        root.beginRecording().drawRenderNode(content)
        root.endRecording()
        return HardwareRenderer.createHardwareBitmap(root, w, h)
            ?.copy(Bitmap.Config.ARGB_8888, false) ?: source
    }
}
