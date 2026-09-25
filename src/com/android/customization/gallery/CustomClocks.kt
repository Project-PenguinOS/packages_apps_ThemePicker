package com.android.customization.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import android.widget.TextView

/**
 * The custom lock screen clock styles behind Settings' "Custom clock style". Settings owns the
 * list; SystemUI owns the layouts that are actually shown, so previews are drawn from those,
 * placed, sized and coloured the way SystemUI's ClockStyle does it.
 */
object CustomClocks {

    private const val TAG = "CustomClocks"
    private const val SETTINGS = "com.android.settings"
    private const val SYSTEMUI = "com.android.systemui"
    private const val KEY_STYLE = "lock_screen_custom_clock_style"
    private const val KEY_COLOR_MODE = "lock_screen_custom_clock_color_mode"
    private const val KEY_CUSTOM_COLOR = "lock_screen_custom_clock_custom_color"
    private const val KEY_SIZE = "lock_screen_custom_clock_size_scale"
    private const val KEY_MARGIN_TOP = "lock_screen_custom_clock_margin_top"
    private const val KEY_OPACITY = "lock_screen_custom_clock_opacity"
    private const val KEY_HIDE_AOSP_CLOCK = "ls_clock_hide"
    private const val KEY_MARGIN_START = "lock_screen_custom_clock_margin_start"
    private const val KEY_GRADIENT = "lock_screen_custom_clock_gradient_enabled"
    private const val KEY_GRADIENT_START = "lock_screen_custom_clock_gradient_color_start"
    private const val KEY_GRADIENT_END = "lock_screen_custom_clock_gradient_color_end"
    private const val KEY_GRADIENT_ANCHOR_Y = "lock_screen_custom_clock_gradient_anchor_y"
    private const val KEY_GRADIENT_RADIUS = "lock_screen_custom_clock_gradient_radius"
    private const val KEY_ALBUM_ART_COLOR = "lock_screen_custom_clock_album_art_color"
    private const val KEY_AOD_ANIM = "lock_screen_custom_clock_aod_anim"
    private const val KEY_WOBBLE = "lock_screen_custom_clock_wobble_on_charge"
    private const val KEY_WEATHER = "custom_clock_weather"
    private const val COLOR_MODE_ACCENT = "accent"
    private const val PREVIEW_SHADOW = 0x59000000
    private const val ACTION_RESTART = "com.android.systemui.action.RESTART_FOR_CLOCK_STYLE"

    /** Styles whose colours SystemUI never changes. */
    private val NO_COLOR = setOf(1, 2, 25, 26, 45)

    private class Catalogue(
        val names: List<String>,
        /** Per style, a layout in [inflateContext]'s package. */
        val layouts: IntArray,
        val inflateContext: Context,
        val systemUi: Context?,
    )

    /** Where SystemUI puts the clock, in pixels of the real screen. */
    class Frame(val top: Int, val side: Int, val width: Int, val scale: Float, val alpha: Float)

    /** How SystemUI sizes, places and colours a custom style; Settings' clock options. */
    data class Tuning(
        val scale: Int = 100,
        val opacity: Int = 100,
        val marginTop: Int = 15,
        val marginStart: Int = 0,
        val accent: Boolean = false,
        val gradient: Boolean = false,
        val gradientStart: Int = 0xFF00E5FF.toInt(),
        val gradientEnd: Int = 0xFFFF2DAA.toInt(),
        val gradientAnchorY: Int = 50,
        val gradientRadius: Int = 100,
        val albumArtColour: Boolean = false,
        val aodAnimation: Boolean = true,
        val wobbleOnCharge: Boolean = true,
        val weather: Boolean = true,
    ) {
        companion object {
            fun load(context: Context): Tuning {
                val cr = context.contentResolver
                fun int(key: String, default: Int) = Settings.Secure.getInt(cr, key, default)
                val d = Tuning()
                return Tuning(
                    scale = int(KEY_SIZE, d.scale),
                    opacity = int(KEY_OPACITY, d.opacity),
                    marginTop = int(KEY_MARGIN_TOP, d.marginTop),
                    marginStart = int(KEY_MARGIN_START, d.marginStart),
                    accent = Settings.Secure.getString(cr, KEY_COLOR_MODE) == COLOR_MODE_ACCENT,
                    gradient = int(KEY_GRADIENT, 0) != 0,
                    gradientStart = int(KEY_GRADIENT_START, d.gradientStart),
                    gradientEnd = int(KEY_GRADIENT_END, d.gradientEnd),
                    gradientAnchorY = int(KEY_GRADIENT_ANCHOR_Y, d.gradientAnchorY),
                    gradientRadius = int(KEY_GRADIENT_RADIUS, d.gradientRadius),
                    albumArtColour = int(KEY_ALBUM_ART_COLOR, 0) != 0,
                    aodAnimation = int(KEY_AOD_ANIM, 1) != 0,
                    wobbleOnCharge = int(KEY_WOBBLE, 1) != 0,
                    weather = int(KEY_WEATHER, 1) != 0,
                )
            }
        }

        fun save(context: Context) {
            val cr = context.contentResolver
            fun put(key: String, value: Int) = Settings.Secure.putInt(cr, key, value)
            fun put(key: String, value: Boolean) = put(key, if (value) 1 else 0)
            put(KEY_SIZE, scale)
            put(KEY_OPACITY, opacity)
            put(KEY_MARGIN_TOP, marginTop)
            put(KEY_MARGIN_START, marginStart)
            put(KEY_GRADIENT, gradient)
            put(KEY_GRADIENT_START, gradientStart)
            put(KEY_GRADIENT_END, gradientEnd)
            put(KEY_GRADIENT_ANCHOR_Y, gradientAnchorY)
            put(KEY_GRADIENT_RADIUS, gradientRadius)
            put(KEY_ALBUM_ART_COLOR, albumArtColour)
            put(KEY_AOD_ANIM, aodAnimation)
            put(KEY_WOBBLE, wobbleOnCharge)
            put(KEY_WEATHER, weather)
        }
    }

    @Volatile private var catalogue: Catalogue? = null
    private val previews = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun load(context: Context): Catalogue? {
        catalogue?.let { return it }
        return try {
            val settings = context.createPackageContext(SETTINGS,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            val utils =
                settings.classLoader.loadClass("com.android.settings.lockscreen.ClockUtils")
            val instance = utils.getField("INSTANCE").get(null)
            val settingsLayouts = utils.getMethod("getCLOCK_LAYOUTS").invoke(instance) as IntArray
            @Suppress("UNCHECKED_CAST")
            val names =
                (utils.getMethod("getClockNames").invoke(instance) as Array<String>).toList()
            val systemUi = runCatching {
                context.createPackageContext(SYSTEMUI,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
            // SystemUI's copy of each layout, by name, is what the lock screen really shows.
            val layouts = settingsLayouts.map { id ->
                val name = settings.resources.getResourceEntryName(id)
                systemUi?.resources?.getIdentifier(name, "layout", SYSTEMUI) ?: 0
            }
            val useSystemUi = systemUi != null && layouts.drop(1).all { it != 0 }
            Catalogue(
                names,
                if (useSystemUi) layouts.toIntArray() else settingsLayouts,
                ContextThemeWrapper(if (useSystemUi) systemUi else settings,
                    android.R.style.Theme_DeviceDefault_NoActionBar),
                systemUi,
            ).also { catalogue = it }
        } catch (e: Exception) {
            Log.w(TAG, "Settings has no custom clock styles", e)
            null
        }
    }

    /** Style names, the first being the default clock. Empty when Settings lacks them. */
    fun names(context: Context): List<String> = load(context)?.names ?: emptyList()

    fun isColourable(style: Int) = style !in NO_COLOR

    fun frame(context: Context, tuning: Tuning = Tuning.load(context)): Frame {
        val density = context.resources.displayMetrics.density
        val screen = context.resources.displayMetrics.widthPixels
        val res = load(context)?.systemUi?.resources
        fun dimen(name: String, fallbackDp: Float): Int {
            val id = res?.getIdentifier(name, "dimen", SYSTEMUI) ?: 0
            return if (id != 0) res!!.getDimensionPixelSize(id) else (fallbackDp * density).toInt()
        }
        val statusBar = dimen("status_bar_height", 28f)
        val side = dimen("below_clock_padding_start", 32f)
        return Frame(
            // The lock screen lays the clock out right under the status bar, then the margin.
            top = (statusBar + tuning.marginTop * density).toInt(),
            side = side + (tuning.marginStart * density).toInt(),
            width = screen - side * 2,
            scale = tuning.scale.coerceIn(50, 150) / 100f,
            alpha = tuning.opacity.coerceIn(0, 100) / 100f,
        )
    }

    /**
     * The style drawn at [width] pixels with the current time, or null for the default clock.
     * [trim] crops the empty space around it, for small tiles.
     */
    fun preview(context: Context, style: Int, width: Int, color: Int?,
            trim: Boolean = false): Bitmap? {
        if (style <= 0) return null
        val minute = System.currentTimeMillis() / 60_000
        val key = "$style/$width/$color/$minute/$trim"
        previews.get(key)?.let { return it }
        val cat = load(context) ?: return null
        if (style >= cat.layouts.size) return null
        return try {
            val inflater = LayoutInflater.from(cat.inflateContext).cloneInContext(
                cat.inflateContext).apply { factory2 = SystemUiViews(cat.systemUi?.classLoader) }
            val view = inflater.inflate(cat.layouts[style], null)
            prepare(view, if (isColourable(style)) color ?: Color.WHITE else null)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            if (view.measuredHeight <= 0) return null
            Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
                .let {
                    view.draw(Canvas(it))
                    if (trim) trimmed(it) else it
                }
                ?.also { previews.put(key, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not draw clock style $style", e)
            null
        }
    }

    /**
     * SystemUI's clock views only need a context, so they're loaded from its code. Its other
     * views (weather and the like) need SystemUI itself; plain views keep the layout's shape.
     */
    private class SystemUiViews(private val loader: ClassLoader?) : LayoutInflater.Factory2 {
        override fun onCreateView(parent: View?, name: String, context: Context,
                attrs: AttributeSet): View? {
            if (!name.contains('.') || name.startsWith("android.")) return null
            if (loader != null && name.startsWith("$SYSTEMUI.clocks.")) {
                try {
                    return loader.loadClass(name).asSubclass(View::class.java)
                        .getConstructor(Context::class.java, AttributeSet::class.java)
                        .newInstance(context, attrs)
                } catch (e: ReflectiveOperationException) {
                    Log.w(TAG, "Could not build $name", e)
                } catch (e: LinkageError) {
                    Log.w(TAG, "Could not build $name", e)
                }
            }
            return if (name.endsWith("TextView")) TextView(context, attrs) else View(context, attrs)
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)
    }

    private fun prepare(view: View, color: Int?) {
        if (view is TextView) {
            // Detached views never tick, and single-line centred text is only scrolled into
            // place once attached, so both are done by hand.
            if (view is TextClock) view.refreshTime()
            if (view.isSingleLine) {
                view.setSingleLine(false)
                view.maxLines = 1
            }
            // As SystemUI does: only text that was white takes the chosen colour.
            if (color != null && (view.currentTextColor and 0xFFFFFF) == 0xFFFFFF) {
                view.setTextColor(color)
            }
            // The lock screen dims the wallpaper behind the clock and the previews don't, so
            // white clocks vanish on light wallpapers without this.
            view.setShadowLayer(view.textSize * 0.08f, 0f, view.textSize * 0.02f, PREVIEW_SHADOW)
        }
        if (view is ViewGroup) {
            view.clipChildren = false
            for (i in 0 until view.childCount) prepare(view.getChildAt(i), color)
        }
    }

    /** Crops away the empty space the layouts leave for the rest of the lock screen. */
    private fun trimmed(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (pixels[y * w + x] ushr 24 > 16) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * Applies [style] as Settings would. Switching between the default clock and a custom one
     * needs SystemUI to restart, as parts of the keyguard only read it at startup.
     */
    fun apply(context: Context, style: Int, color: Int?, tuning: Tuning? = null) {
        val resolver = context.contentResolver
        val before = Settings.Secure.getInt(resolver, KEY_STYLE, 0)
        Settings.Secure.putInt(resolver, KEY_STYLE, style)
        Settings.System.putInt(resolver, KEY_HIDE_AOSP_CLOCK, if (style != 0) 1 else 0)
        if (style != 0) {
            // Without new tuning, e.g. from the switcher, the current accent or gradient stays.
            val current = tuning ?: Tuning.load(context)
            // As Settings does: a gradient is drawn in the custom colour mode.
            val mode = when {
                current.gradient -> "custom"
                current.accent -> COLOR_MODE_ACCENT
                color != null -> "custom"
                else -> "default"
            }
            Settings.Secure.putString(resolver, KEY_COLOR_MODE, mode)
            if (color != null) Settings.Secure.putInt(resolver, KEY_CUSTOM_COLOR, color)
            tuning?.save(context)
        }
        if ((before == 0) != (style == 0)) {
            context.sendBroadcast(Intent(ACTION_RESTART).setPackage(SYSTEMUI))
        }
    }
}
