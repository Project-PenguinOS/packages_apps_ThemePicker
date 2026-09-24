package com.android.customization.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.customization.gallery.ClockStyle
import com.android.customization.gallery.CustomClocks
import com.android.customization.gallery.GalleryRenderer
import com.android.customization.gallery.GalleryWallpaper
import com.android.customization.gallery.GalleryWeather
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The flex clock's weight/width presets, heaviest and narrowest first, as SystemUI has them. */
val CLOCK_PRESETS = listOf(800f to 30f, 700f to 55f, 600f to 80f, 500f to 100f, 400f to 108f,
    300f to 116f, 200f to 120f)

fun clockVariation(style: ClockStyle): String {
    val (weight, width) = CLOCK_PRESETS[(style.preset ?: 3).coerceIn(0, CLOCK_PRESETS.size - 1)]
    return "'wght' $weight, 'wdth' $width, 'ROND' ${if (style.rounded) 100 else 0}"
}

private val clockTypeface: Typeface by lazy { Typeface.create("google-sans-flex-clock",
    Typeface.NORMAL) }

/** A still of a wallpaper, drawn off the main thread at the size it is shown. */
@Composable
fun WallpaperImage(
    wallpaper: GalleryWallpaper,
    modifier: Modifier = Modifier,
    weather: GalleryWeather.Snapshot? = null,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val context = LocalContext.current
        val w = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val h = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val bitmap by produceState<Bitmap?>(null, wallpaper, w, h, weather) {
            value = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    GalleryRenderer.draw(context, android.graphics.Canvas(it), wallpaper, w, h,
                        weather = weather)
                }
            }
        }
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Box(Modifier.fillMaxSize().background(Color(0xFF1C1C1E)))
    }
}

/** A wallpaper drawn every frame, for kinds that move. */
@Composable
fun LiveWallpaper(
    wallpaper: GalleryWallpaper,
    modifier: Modifier = Modifier,
    weather: GalleryWeather.Snapshot? = null,
    shuffleIndex: Int = 0,
) {
    if (!wallpaper.kind.live || wallpaper.kind == GalleryWallpaper.Kind.SHUFFLE ||
            wallpaper.kind == GalleryWallpaper.Kind.ASTRONOMY) {
        val shown = if (wallpaper.kind == GalleryWallpaper.Kind.SHUFFLE) {
            wallpaper.copy(kind = GalleryWallpaper.Kind.PHOTO, variant = 0,
                photos = listOfNotNull(wallpaper.photos.getOrNull(
                    Math.floorMod(shuffleIndex, wallpaper.photos.size.coerceAtLeast(1)))))
        } else wallpaper
        WallpaperImage(shown, modifier, weather)
        return
    }
    val context = LocalContext.current
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(wallpaper) {
        while (true) withFrameMillis { time = System.currentTimeMillis() }
    }
    androidx.compose.foundation.Canvas(modifier) {
        drawIntoCanvas {
            GalleryRenderer.draw(context, it.nativeCanvas, wallpaper, size.width.toInt(),
                size.height.toInt(), time, weather = weather)
        }
    }
}

/** A lightweight stand-in for SystemUI's clock, for tiles, the switcher and customising. */
@Composable
fun ClockText(
    style: ClockStyle,
    heightDp: Dp,
    modifier: Modifier = Modifier,
    fixedTime: Boolean = false,
    onLight: Boolean = false,
) {
    val context = LocalContext.current
    val text = clockString(context, style.small, fixedTime)
    val color = style.color ?: if (onLight) 0xFF1C1C1E.toInt() else android.graphics.Color.WHITE
    val lines = if (style.small) 1 else 2
    val family = remember(style.preset, style.rounded) { clockFontFamily(clockVariation(style)) }
    val fontScale = LocalDensity.current.fontScale
    val size = (heightDp.value / lines * 0.95f / fontScale).sp
    Text(
        text,
        color = Color(color),
        fontFamily = family,
        fontSize = size,
        lineHeight = size * 0.9f,
        textAlign = TextAlign.Center,
        maxLines = lines,
        softWrap = false,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier,
    )
}

private val clockFamilies = HashMap<String, FontFamily>()

/** The system clock font with the preset's axes baked in, which Compose can't set itself. */
private fun clockFontFamily(variation: String): FontFamily =
    clockFamilies.getOrPut(variation) {
        val typeface = CLOCK_FONT_FILES.map(::File).firstOrNull(File::exists)?.let {
            Typeface.Builder(it).setFontVariationSettings(variation).build()
        } ?: clockTypeface
        FontFamily(typeface)
    }

private val CLOCK_FONT_FILES = listOf("/system/fonts/GoogleSansFlexClock-Regular.ttf",
    "/product/fonts/GoogleSansFlexClock-Regular.ttf")

/** A clock thumbnail: SystemUI's own clock, or a custom style cropped to fit. */
@Composable
fun LockClock(
    style: ClockStyle,
    heightDp: Dp,
    widthDp: Dp,
    modifier: Modifier = Modifier,
    fixedTime: Boolean = false,
    onLight: Boolean = false,
) {
    if (style.face <= 0) {
        ClockText(style, heightDp, modifier, fixedTime, onLight)
        return
    }
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(null, style.face, style.color) {
        value = withContext(Dispatchers.Default) {
            CustomClocks.preview(context, style.face, CustomClocks.frame(context).width,
                style.color, trim = true)
        }
    }
    preview?.let {
        Image(it.asImageBitmap(), null, modifier.width(widthDp).height(heightDp),
            contentScale = ContentScale.Fit)
    }
}

/**
 * A custom clock style laid over a full lock screen preview exactly where SystemUI puts it,
 * shrunk with the preview, so cards, customising and the lock screen all match.
 */
@Composable
fun CustomClockLayer(
    style: ClockStyle,
    modifier: Modifier = Modifier,
    tuning: CustomClocks.Tuning? = null,
) {
    val context = LocalContext.current
    val frame = remember(tuning) {
        if (tuning != null) CustomClocks.frame(context, tuning) else CustomClocks.frame(context)
    }
    // Accent and gradient clocks are previewed in their accent and first gradient colour.
    val color = when {
        tuning?.gradient == true -> tuning.gradientStart
        tuning?.accent == true -> context.getColor(android.R.color.system_accent1_100)
        else -> style.color
    }
    val preview by produceState<Bitmap?>(null, style.face, color, frame.width) {
        value = withContext(Dispatchers.Default) {
            CustomClocks.preview(context, style.face, frame.width, color)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bitmap = preview ?: return@BoxWithConstraints
        val k = constraints.maxWidth / context.resources.displayMetrics.widthPixels.toFloat()
        val density = LocalDensity.current
        Image(
            bitmap.asImageBitmap(), null,
            Modifier
                .offset { IntOffset((frame.side * k).toInt(), (frame.top * k).toInt()) }
                .size(with(density) { (frame.width * k).toDp() },
                    with(density) { (bitmap.height * k).toDp() })
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = frame.scale
                    scaleY = frame.scale
                    alpha = frame.alpha
                },
            contentScale = ContentScale.FillBounds,
        )
    }
}

fun clockString(context: Context, small: Boolean, fixedTime: Boolean): String {
    val now = Calendar.getInstance()
    val hour = if (fixedTime) 9 else if (DateFormat.is24HourFormat(context))
        now.get(Calendar.HOUR_OF_DAY) else (now.get(Calendar.HOUR) + 11) % 12 + 1
    val minute = if (fixedTime) 41 else now.get(Calendar.MINUTE)
    return if (small) "%d:%02d".format(hour, minute) else "%02d\n%02d".format(hour, minute)
}

/** A phone-shaped preview of a lock screen, as the gallery and switcher show them. */
@Composable
fun LockScreenCard(
    wallpaper: GalleryWallpaper,
    clock: ClockStyle,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    fixedTime: Boolean = true,
    corner: Dp = 24.dp,
    weather: GalleryWeather.Snapshot? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(corner.coerceAtLeast(0.dp))
    val onLight = rememberTopLight(wallpaper)
    BoxWithConstraints(
        modifier
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        // The still stays underneath, so turning live never flashes an empty card.
        WallpaperImage(wallpaper, Modifier.fillMaxSize(), weather)
        if (live && wallpaper.isAnimated) LiveWallpaper(wallpaper, Modifier.fillMaxSize(), weather)
        if (clock.face > 0) {
            CustomClockLayer(clock)
            return@BoxWithConstraints
        }
        val clockHeight = maxHeight * if (clock.small) 0.06f else 0.24f
        val dateSize = (maxWidth.value * 0.055f).sp
        Column(
            Modifier.fillMaxWidth().padding(top = maxHeight * 0.1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                dateString(),
                color = if (onLight) Color(0xFF1C1C1E) else Color.White,
                fontSize = dateSize,
                fontWeight = FontWeight.Medium,
            )
            ClockText(clock, clockHeight, fixedTime = fixedTime, onLight = onLight)
        }
    }
}

/** The clock turns dark over light wallpapers, as the real one does. */
@Composable
fun rememberTopLight(wallpaper: GalleryWallpaper): Boolean {
    val context = LocalContext.current
    val light by produceState(false, wallpaper) {
        value = withContext(Dispatchers.Default) { GalleryRenderer.isTopLight(context, wallpaper) }
    }
    return light
}

fun dateString(): String =
    DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(),
        "EEEdMMM"), System.currentTimeMillis()).toString()

/** A round source button at the top of the gallery, drawn from the wallpaper it opens. */
@Composable
fun SourceButton(label: String, preview: GalleryWallpaper?, icon: ImageVector?,
        onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 6.dp).clickable(onClick = onClick),
    ) {
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(Color(0xFF2C2C2E))
                .border(1.dp, Color(0x22FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) WallpaperImage(preview, Modifier.fillMaxSize())
            if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        Text(label, color = Color.White, fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp))
    }
}
