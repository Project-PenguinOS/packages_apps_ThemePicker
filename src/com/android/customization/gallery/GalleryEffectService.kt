package com.android.customization.gallery

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.animation.PathInterpolator

/**
 * The photo effects' live wallpaper: the locked look on the lock screen, animating into the
 * unlocked look as the device unlocks, as Atmo Engine does.
 */
class GalleryEffectService : WallpaperService() {

    override fun onCreateEngine(): Engine = EffectEngine()

    private inner class EffectEngine : Engine() {
        private val thread = HandlerThread("GalleryEffect").apply { start() }
        private val handler = Handler(thread.looper)
        private val renderer = GalleryEffectRenderer(this@GalleryEffectService)
        private val store = GalleryStore.get(this@GalleryEffectService)
        private val keyguard = getSystemService(KeyguardManager::class.java)!!
        private var effect = GalleryEffect.NONE
        private var progress = 0f
        private var animationStart = 0L
        private var animating = false
        private var visible = false
        private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

        private val onStoreChanged: () -> Unit = { handler.post { loadScene(); draw() } }

        // The depth wallpaper's subject arrives after we do, and changes with the photo.
        private val subjectObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                loadScene()
                draw()
            }
        }

        private val frame = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - animationStart).toFloat() /
                    effect.durationMs).coerceIn(0f, 1f)
                progress = effect.locked + (effect.unlocked - effect.locked) *
                    ease.getInterpolation(t)
                draw()
                if (t < 1f) handler.postDelayed(this, 8) else animating = false
            }
        }

        // Atmo polls rather than trusting broadcast order, which varies between devices.
        private val unlockPoll = object : Runnable {
            override fun run() {
                if (!visible) return
                if (keyguard.isKeyguardLocked) handler.postDelayed(this, POLL_MS) else unlock()
            }
        }

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handler.post {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> showLocked()
                        Intent.ACTION_USER_PRESENT -> unlock()
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            store.addListener(onStoreChanged)
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(GalleryApplier.DEPTH_SUBJECT), false, subjectObserver)
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, RECEIVER_EXPORTED)
        }

        override fun onDestroy() {
            store.removeListener(onStoreChanged)
            contentResolver.unregisterContentObserver(subjectObserver)
            unregisterReceiver(screenReceiver)
            handler.removeCallbacksAndMessages(null)
            handler.post {
                renderer.release()
                thread.quitSafely()
            }
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            handler.post {
                renderer.attach(holder.surface, w, h)
                loadScene()
                progress = if (keyguard.isKeyguardLocked && !isPreview) effect.locked
                else effect.unlocked
                draw()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(frame)
            handler.post { renderer.detachSurface() }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.post {
                if (!visible) return@post
                if (keyguard.isKeyguardLocked) {
                    showLocked()
                    handler.removeCallbacks(unlockPoll)
                    handler.postDelayed(unlockPoll, POLL_MS)
                } else if (!animating && progress != effect.unlocked) {
                    unlock()
                } else {
                    draw()
                }
            }
        }

        private fun loadScene() {
            val wallpaper = store.liveWallpaper(true) ?: return
            effect = GalleryEffect.of(wallpaper.effect)
            val subject = if (wallpaper.depth) {
                Settings.System.getString(contentResolver, GalleryApplier.DEPTH_SUBJECT)
            } else null
            GalleryRenderer.effectSource(this@GalleryEffectService, wallpaper)
                ?.let { renderer.load(it, effect, subject) }
        }

        private fun showLocked() {
            handler.removeCallbacks(frame)
            animating = false
            progress = effect.locked
            draw()
        }

        private fun unlock() {
            handler.removeCallbacks(unlockPoll)
            if (animating || progress == effect.unlocked) return
            animating = true
            animationStart = SystemClock.uptimeMillis()
            handler.post(frame)
        }

        private fun draw() = renderer.draw(progress)
    }

    companion object {
        private const val POLL_MS = 50L
    }
}
