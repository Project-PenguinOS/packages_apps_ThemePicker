package com.android.customization.gallery

import android.app.WallpaperManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.android.customization.gallery.GalleryWallpaper.Kind
import com.android.customization.gallery.GalleryWallpaper.Shuffle
import java.util.Calendar

/** Draws the gallery's live wallpapers: animated, time-based and photo shuffle. */
class GalleryWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GalleryEngine()

    private inner class GalleryEngine : Engine() {
        private val thread = HandlerThread("GalleryWallpaper").apply { start() }
        private val handler = Handler(thread.looper)
        private val store = GalleryStore.get(this@GalleryWallpaperService)
        private val drawFrame = Runnable { draw() }
        private val onStoreChanged: () -> Unit = { handler.post(drawFrame) }
        private val weatherObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refreshWeather()
        }
        private var visible = false
        private var weather: GalleryWeather.Snapshot? = null
        private var width = 0
        private var height = 0

        /** The lock screen's wallpaper also serves the home screen when they are a pair. */
        private val forLock: Boolean
            get() = (wallpaperFlags and WallpaperManager.FLAG_LOCK) != 0

        private val wallpaper: GalleryWallpaper?
            get() = store.liveWallpaper(forLock) ?: store.liveWallpaper(!forLock)

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            store.addListener(onStoreChanged)
            contentResolver.registerContentObserver(WEATHER_URI, true, weatherObserver)
            refreshWeather()
        }

        override fun onDestroy() {
            store.removeListener(onStoreChanged)
            contentResolver.unregisterContentObserver(weatherObserver)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            handler.post(drawFrame)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                maybeAdvanceShuffle(onLock = false)
                handler.post(drawFrame)
            } else {
                handler.removeCallbacks(drawFrame)
                // "On lock": the next photo is waiting the next time the screen comes on.
                maybeAdvanceShuffle(onLock = true)
            }
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?,
                resultRequested: Boolean): Bundle? {
            val shuffle = wallpaper
            if (action == WallpaperManager.COMMAND_TAP && shuffle?.kind == Kind.SHUFFLE &&
                    shuffle.shuffle == Shuffle.ON_TAP) {
                advanceShuffle()
                handler.post(drawFrame)
            }
            return null
        }

        private fun refreshWeather() {
            handler.post {
                weather = GalleryWeather.query(this@GalleryWallpaperService)
                draw()
            }
        }

        private fun maybeAdvanceShuffle(onLock: Boolean) {
            val shuffle = wallpaper?.takeIf { it.kind == Kind.SHUFFLE } ?: return
            val now = System.currentTimeMillis()
            val due = when (shuffle.shuffle) {
                Shuffle.ON_LOCK -> onLock
                Shuffle.HOURLY -> !onLock && now - store.shuffleChangedAt >= 3_600_000L
                Shuffle.DAILY -> !onLock && dayOf(now) != dayOf(store.shuffleChangedAt)
                Shuffle.ON_TAP -> false
            }
            if (due) advanceShuffle()
        }

        private fun advanceShuffle() {
            store.shuffleIndex = store.shuffleIndex + 1
            store.shuffleChangedAt = System.currentTimeMillis()
        }

        private fun dayOf(ms: Long) =
            Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_YEAR)

        private fun draw() {
            handler.removeCallbacks(drawFrame)
            val spec = wallpaper ?: return
            if (width == 0 || height == 0) return
            if (visible) maybeAdvanceShuffle(onLock = false)
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Surface not ready", e)
                null
            } ?: return
            val animate: Boolean
            try {
                animate = GalleryRenderer.draw(this@GalleryWallpaperService, canvas, spec, width,
                    height, shuffleIndex = store.shuffleIndex, weather = weather)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            if (!visible) return
            val delay = when {
                // The bubbles drift slowly enough for 15 fps.
                animate && spec.kind == Kind.BUBBLES -> 66L
                animate -> 33L
                spec.kind == Kind.ASTRONOMY || spec.kind == Kind.WAVES -> 60_000L
                spec.kind == Kind.WEATHER -> 15 * 60_000L
                spec.kind == Kind.SHUFFLE && spec.shuffle == Shuffle.HOURLY -> 60_000L
                else -> -1L
            }
            if (delay > 0) handler.postDelayed(drawFrame, delay)
        }
    }

    companion object {
        private const val TAG = "GalleryWallpaper"
        private val WEATHER_URI = Uri.parse("content://org.omnirom.omnijaws.provider/weather")
    }
}
