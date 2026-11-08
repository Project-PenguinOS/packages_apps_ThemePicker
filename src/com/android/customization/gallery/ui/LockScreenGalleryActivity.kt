package com.android.customization.gallery.ui

import android.Manifest
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.ViewTreeObserver
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.android.customization.gallery.ClockStyle
import com.android.customization.gallery.CustomClocks
import com.android.customization.gallery.GalleryApplier
import com.android.customization.gallery.GalleryStore
import com.android.customization.gallery.GalleryWallpaper
import com.android.customization.gallery.GalleryWallpaper.Kind
import com.android.customization.gallery.HomeStyle
import com.android.customization.gallery.LockScreen
import com.android.customization.gallery.SystemClocks
import com.android.customization.picker.clock.domain.interactor.ClockPickerInteractor
import com.android.customization.picker.clock.shared.ClockSize
import com.android.customization.picker.clock.shared.model.ClockMetadataModel
import com.android.customization.picker.quickaffordance.domain.interactor.KeyguardQuickAffordancePickerInteractor
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS-style lock screens: a switcher of saved lock screens, a gallery to add new ones and a
 * customise screen. Opened by long-pressing the lock screen.
 */
@AndroidEntryPoint(ComponentActivity::class)
class LockScreenGalleryActivity : Hilt_LockScreenGalleryActivity() {

    @Inject lateinit var clockInteractor: ClockPickerInteractor
    @Inject lateinit var affordanceInteractor: KeyguardQuickAffordancePickerInteractor

    private var overLockScreen = false
    private val screenOff =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = finish()
        }

    private sealed interface Screen {
        /** [zoomIn]: arrive as a full lock screen shrinking into its card. */
        data class Switcher(val zoomIn: Boolean) : Screen
        data object Gallery : Screen
        data class Customise(
            val lockScreen: LockScreen,
            val isNew: Boolean,
            val openClock: Boolean = false,
        ) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The switcher animates itself out of the lock screen, so no window animation.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in,
            android.R.anim.fade_out)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen(intent)
        lockOnly = intent.getBooleanExtra(EXTRA_LOCK_ONLY, false)
        val store = GalleryStore.get(this)
        val startInGallery = intent.getBooleanExtra(EXTRA_GALLERY, false)
        val startOnClock = intent.getBooleanExtra(EXTRA_CLOCK, false)
        lifecycleScope.launch(Dispatchers.IO) { adoptCurrentWallpaper(store) }
        lifecycleScope.launch {
            clockInteractor.allClocks.collect { clocks ->
                SystemClocks.faces =
                    clocks.map { SystemClocks.Face(it.clockId, it.description, it.thumbnail) }
            }
        }
        lifecycleScope.launch {
            clockInteractor.selectedClockId.collect { SystemClocks.selectedId = it }
        }
        if (!startInGallery && !startOnClock) holdFirstFrame()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var screen by remember {
                    // Wallpaper & style sends its clock entry here, straight to the clock.
                    val current =
                        store.lockScreens.let { all ->
                            all.firstOrNull { it.id == store.currentId } ?: all.firstOrNull()
                        }
                    mutableStateOf<Screen>(
                        when {
                            startInGallery -> Screen.Gallery
                            startOnClock && current != null ->
                                Screen.Customise(current, isNew = false, openClock = true)
                            else -> Screen.Switcher(zoomIn = true)
                        })
                }
                var version by remember { mutableStateOf(0) }
                val scope = rememberCoroutineScope()

                val pickPhoto = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    scope.launch {
                        val path = withContext(Dispatchers.IO) { store.importPhoto(uri) }
                            ?: return@launch
                        screen = Screen.Customise(newLockScreen(store, GalleryWallpaper(
                            Kind.PHOTO, photos = listOf(path))), isNew = true)
                    }
                }
                val pickKaleidoscope = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    scope.launch {
                        val path = withContext(Dispatchers.IO) { store.importPhoto(uri) }
                            ?: return@launch
                        screen = Screen.Customise(newLockScreen(store, GalleryWallpaper(
                            Kind.KALEIDOSCOPE, photos = listOf(path))), isNew = true)
                    }
                }
                val pickShuffle = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(MAX_SHUFFLE)) { uris ->
                    if (uris.isEmpty()) return@rememberLauncherForActivityResult
                    scope.launch {
                        val paths = withContext(Dispatchers.IO) {
                            uris.mapNotNull { store.importPhoto(it) }
                        }
                        if (paths.isNotEmpty()) {
                            screen = Screen.Customise(newLockScreen(store, GalleryWallpaper(
                                Kind.SHUFFLE, photos = paths)), isNew = true)
                        }
                    }
                }
                val requestPhotos = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()) { version++ }

                BackHandler {
                    when (screen) {
                        is Screen.Switcher -> finish()
                        Screen.Gallery ->
                            if (startInGallery) finish() else screen = Screen.Switcher(false)
                        is Screen.Customise -> screen = Screen.Switcher(zoomIn = true)
                    }
                }

                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val from = initialState
                        val to = targetState
                        when {
                            // The switcher zooms its card to and from full screen itself.
                            from is Screen.Switcher && to is Screen.Customise ||
                                from is Screen.Customise && to is Screen.Switcher ->
                                EnterTransition.None togetherWith ExitTransition.None
                            to == Screen.Gallery ->
                                (slideInVertically(tween(420)) { it / 3 } +
                                    fadeIn(tween(300))) togetherWith fadeOut(tween(250))
                            from == Screen.Gallery && to is Screen.Switcher ->
                                fadeIn(tween(250)) togetherWith
                                    (slideOutVertically(tween(380)) { it / 3 } +
                                        fadeOut(tween(250)))
                            else -> (fadeIn(tween(300)) + scaleIn(tween(350),
                                initialScale = 0.94f)) togetherWith fadeOut(tween(200))
                        }
                    },
                    label = "screen",
                ) { current ->
                    when (current) {
                        is Screen.Switcher -> SwitcherScreen(
                            store = store,
                            version = version,
                            zoomIn = current.zoomIn,
                            onReady = ::releaseFirstFrame,
                            onApply = { lockScreen ->
                                apply(if (lockOnly) lockScreen.copy(home = HomeStyle.KEEP)
                                else lockScreen)
                            },
                            onHome = if (lockOnly) null else { lockScreen, home, photo ->
                                val paired = lockScreen.copy(home = home,
                                    homePhoto = photo ?: lockScreen.homePhoto)
                                store.save(paired)
                                version++
                                apply(paired)
                            },
                            onCustomise = { screen = Screen.Customise(it, isNew = false) },
                            onAdd = { screen = Screen.Gallery },
                            onDelete = {
                                store.delete(it.id)
                                version++
                            },
                        )
                        Screen.Gallery -> GalleryScreen(
                            featured = remember(version) { featuredPhotos() },
                            hasPhotoAccess = hasPhotoAccess(),
                            onRequestPhotoAccess = {
                                leavingLockScreen {
                                    requestPhotos.launch(Manifest.permission.READ_MEDIA_IMAGES)
                                }
                            },
                            onCancel = {
                                if (startInGallery) finish() else screen = Screen.Switcher(false)
                            },
                            onPick = { wallpaper ->
                                scope.launch {
                                    // Featured photos come from MediaStore; keep our own copy.
                                    val imported = withContext(Dispatchers.IO) {
                                        wallpaper.copy(photos = wallpaper.photos.map {
                                            if (it.startsWith("content:"))
                                                store.importPhoto(android.net.Uri.parse(it)) ?: it
                                            else it
                                        })
                                    }
                                    screen = Screen.Customise(newLockScreen(store, imported),
                                        isNew = true)
                                }
                            },
                            onPickPhoto = {
                                leavingLockScreen {
                                    pickPhoto.launch(PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            },
                            onPickShuffle = {
                                leavingLockScreen {
                                    pickShuffle.launch(PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            },
                            onPickKaleidoscope = {
                                leavingLockScreen {
                                    pickKaleidoscope.launch(PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            },
                        )
                        is Screen.Customise -> CustomiseScreen(
                            initial = current.lockScreen,
                            isNew = current.isNew,
                            affordanceInteractor = affordanceInteractor,
                            initialSheet = if (current.openClock) "clock" else null,
                            lockOnly = lockOnly,
                            onCancel = {
                                screen = if (current.isNew) Screen.Gallery
                                else Screen.Switcher(zoomIn = true)
                            },
                            onDone = { lockScreen, tuning ->
                                store.save(lockScreen)
                                version++
                                apply(lockScreen, tuning)
                            },
                        )
                    }
                }
            }
        }
    }

    private var lockOnly by mutableStateOf(false)

    private fun newLockScreen(store: GalleryStore, wallpaper: GalleryWallpaper) =
        LockScreen(store.newId(), wallpaper, clock = ClockStyle(preset = 5))

    private fun hasPhotoAccess() =
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED

    /** Recent portrait camera photos, as content URIs, newest first. */
    private fun featuredPhotos(): List<Pair<String, Long>> {
        if (!hasPhotoAccess()) return emptyList()
        val photos = mutableListOf<Pair<String, Long>>()
        runCatching {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.ORIENTATION),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("DCIM/%"),
                "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext() && photos.size < FEATURED) {
                    val rotated = c.getInt(4) % 180 != 0
                    val w = if (rotated) c.getInt(3) else c.getInt(2)
                    val h = if (rotated) c.getInt(2) else c.getInt(3)
                    if (h <= w) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    photos += uri.toString() to c.getLong(1)
                }
            }
        }
        return photos
    }

    private var firstFrameHeld = false
    private val holdFirstFrame =
        ViewTreeObserver.OnPreDrawListener { !firstFrameHeld }

    /** Keeps the window off screen until the lock screen it opens from is drawn full size. */
    private fun holdFirstFrame() {
        firstFrameHeld = true
        window.decorView.viewTreeObserver.addOnPreDrawListener(holdFirstFrame)
    }

    private fun releaseFirstFrame() {
        if (!firstFrameHeld) return
        firstFrameHeld = false
        window.decorView.viewTreeObserver.removeOnPreDrawListener(holdFirstFrame)
        window.decorView.invalidate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lockOnly = intent.getBooleanExtra(EXTRA_LOCK_ONLY, false)
        showOverLockScreen(intent)
    }

    override fun onDestroy() {
        if (overLockScreen) unregisterReceiver(screenOff)
        super.onDestroy()
    }

    /**
     * Opened from an unlocked lock screen, the gallery shows over it, so choosing a lock screen
     * lands back on it. Locking the phone closes it there.
     */
    private fun showOverLockScreen(intent: Intent) {
        val over = intent.getBooleanExtra(EXTRA_OVER_LOCK_SCREEN, false)
        setShowWhenLocked(over)
        // Over the lock screen it closes as the full-size lock screen, which is what is beneath.
        if (over) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        if (over && !overLockScreen) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } else if (!over && overLockScreen) {
            unregisterReceiver(screenOff)
        }
        overLockScreen = over
    }

    /** Runs [action], which opens another app's screen, once the lock screen is out of the way. */
    fun leavingLockScreen(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!overLockScreen || !keyguard.isKeyguardLocked) {
            action()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }

    private fun apply(lockScreen: LockScreen, tuning: CustomClocks.Tuning? = null) {
        lifecycleScope.launch {
            // The clock first: an effect with depth waits for its subject before the wallpaper
            // goes on, and the clock should not wait with it.
            applyClock(lockScreen.clock, tuning)
            val app = applicationContext
            // Out of the activity's scope, so leaving the gallery does not cut the wallpaper,
            // its effect or its depth off halfway.
            ApplyScope.launch { GalleryApplier.apply(app, lockScreen) }
            finish()
        }
    }

    private suspend fun applyClock(style: ClockStyle, tuning: CustomClocks.Tuning?) {
        if (style.face == 0) applyStockClock(style)
        withContext(Dispatchers.IO) {
            runCatching { CustomClocks.apply(this@LockScreenGalleryActivity, style.face,
                style.color, tuning) }
        }
    }

    private suspend fun applyStockClock(style: ClockStyle) {
        // The weight and width presets are the default flex clock's own axes.
        val axes = style.preset?.takeIf { style.isDefaultClock }?.let {
            val (weight, width) = CLOCK_PRESETS[it.coerceIn(0, CLOCK_PRESETS.size - 1)]
            ClockAxisStyle(mapOf("wght" to weight, "wdth" to width,
                "ROND" to if (style.rounded) 100f else 0f, "slnt" to 0f))
        }
        runCatching {
            clockInteractor.applyClock(
                clockId = style.clockId ?: ClockStyle.DEFAULT_CLOCK_ID,
                size = if (style.small) ClockSize.SMALL else ClockSize.DYNAMIC,
                selectedColorId = null,
                colorToneProgress = ClockMetadataModel.DEFAULT_COLOR_TONE_PROGRESS,
                seedColor = style.color,
                axisSettings = axes,
            )
        }
    }

    /**
     * The wallpaper on the lock screen becomes a saved lock screen the first time, and again
     * whenever it was changed outside the gallery (Wallpaper and style, another app), so the
     * gallery never shows or re-applies an old one.
     */
    private fun adoptCurrentWallpaper(store: GalleryStore) {
        val id = GalleryApplier.lockWallpaperId(this)
        if (store.lockScreens.isNotEmpty() && id == store.lockWallpaperId) return
        val wm = WallpaperManager.getInstance(this)
        val sharesHome = wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0
        val info =
            if (sharesHome) wm.wallpaperInfo else wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)
        // A live wallpaper is not kept as a photo; the gallery's own ones are saved already.
        if (store.lockScreens.isNotEmpty() && info != null) {
            store.lockWallpaperId = id
            return
        }
        val drawable = runCatching { wm.getDrawable(WallpaperManager.FLAG_LOCK) }.getOrNull()
            ?: runCatching { wm.builtInDrawable }.getOrNull() ?: return
        val (w, h) = GalleryApplier.screenSize(this)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bitmap))
        val file = File(File(filesDir, "gallery").apply { mkdirs() }, "${store.newId()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val lockScreen = LockScreen(store.newId(),
            GalleryWallpaper(Kind.PHOTO, photos = listOf(file.absolutePath)))
        store.save(lockScreen)
        store.setCurrent(lockScreen.id)
        store.lockWallpaperId = id
    }

    companion object {
        private val ApplyScope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            )
        const val EXTRA_GALLERY = "gallery"
        const val EXTRA_CLOCK = "clock"
        /** From "Lock screen" in Wallpaper and style: the home screen is left as it is. */
        const val EXTRA_LOCK_ONLY = "lock_only"
        const val EXTRA_OVER_LOCK_SCREEN = "over_lock_screen"
        private const val MAX_SHUFFLE = 30
        private const val FEATURED = 6
    }
}
