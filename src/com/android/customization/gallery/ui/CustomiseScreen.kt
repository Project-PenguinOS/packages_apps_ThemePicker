package com.android.customization.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.android.customization.gallery.ClockStyle
import com.android.customization.gallery.CustomClocks
import com.android.customization.gallery.GalleryEffect
import com.android.customization.gallery.GalleryRenderer
import com.android.customization.gallery.GalleryWallpaper
import com.android.customization.gallery.GalleryWallpaper.Kind
import com.android.customization.gallery.GalleryWallpaper.Shuffle
import com.android.customization.gallery.GalleryWeather
import com.android.customization.gallery.HomeStyle
import com.android.customization.gallery.LockScreen
import com.android.customization.picker.quickaffordance.domain.interactor.KeyguardQuickAffordancePickerInteractor
import com.android.themepicker.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CLOCK_COLOURS = listOf(null, 0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(),
    0xFFFF9F0A.toInt(), 0xFFFF6482.toInt(), 0xFFBF5AF2.toInt(), 0xFF64D2FF.toInt(),
    0xFF30D158.toInt(), 0xFF8E8E93.toInt(), 0xFF1C1C1E.toInt())
private val ACCENT = Color(0xFF0A84FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomiseScreen(
    initial: LockScreen,
    isNew: Boolean,
    affordanceInteractor: KeyguardQuickAffordancePickerInteractor,
    onCancel: () -> Unit,
    onDone: (LockScreen, CustomClocks.Tuning) -> Unit,
) {
    val context = LocalContext.current
    var wallpaper by remember { mutableStateOf(initial.wallpaper) }
    var clock by remember { mutableStateOf(initial.clock) }
    var tuning by remember { mutableStateOf(CustomClocks.Tuning.load(context)) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var askHome by remember { mutableStateOf(false) }
    var weather by remember { mutableStateOf<GalleryWeather.Snapshot?>(null) }
    LaunchedEffect(Unit) {
        weather = withContext(Dispatchers.IO) { GalleryWeather.query(context) }
    }
    // The preview is already on screen from the switcher; only the controls fade in.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(320, delayMillis = 60)) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LiveWallpaper(wallpaper, Modifier.fillMaxSize(), weather)
        val effect = GalleryEffect.of(wallpaper.effect)
        val photo = wallpaper.photos.firstOrNull()
        if (wallpaper.kind == Kind.PHOTO && effect != GalleryEffect.NONE && photo != null) {
            EffectPreview(photo, effect, Modifier.fillMaxSize())
        }
        val onLight = rememberTopLight(wallpaper)
        if (clock.face > 0) {
            CustomClockLayer(clock, tuning = tuning)
            // The clock area stays tappable, as iOS lets you tap the clock to edit it.
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.3f).clickable { sheet = "clock" })
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            // Same proportions as the switcher's cards, so zooming in lines up.
            val screenHeight = maxHeight
            val dateSize = (maxWidth.value * 0.055f).sp
            Column(
                Modifier.fillMaxWidth().padding(top = maxHeight * 0.1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(dateString(), color = if (onLight) Color(0xFF1C1C1E) else Color.White,
                    fontSize = dateSize, fontWeight = FontWeight.Medium)
                // Framed, as iOS marks what can be edited.
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x66FFFFFF).copy(alpha = 0.4f * appear.value),
                            RoundedCornerShape(16.dp))
                        .background(Color(0x14FFFFFF).copy(alpha = 0.08f * appear.value))
                        .clickable { sheet = "clock" }
                        .padding(horizontal = 16.dp),
                ) {
                    ClockText(clock, screenHeight * if (clock.small) 0.06f else 0.24f,
                        fixedTime = false, onLight = onLight)
                }
            }
        }

        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 20.dp,
                vertical = 12.dp).alpha(appear.value),
        ) {
            Pill(stringResource(android.R.string.cancel), Color(0xCC2C2C2E), onCancel)
            Spacer(Modifier.weight(1f))
            Pill(stringResource(if (isNew) R.string.gallery_add else R.string.gallery_done),
                ACCENT) {
                // An effect plays from the lock screen into the home screen, so it's a pair.
                if (wallpaper.effect != 0) {
                    onDone(initial.copy(wallpaper = wallpaper, clock = clock,
                        home = HomeStyle.PAIR), tuning)
                } else askHome = true
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent,
                    Color(0xCC000000))))
                .navigationBarsPadding().padding(bottom = 16.dp, top = 40.dp)
                .alpha(appear.value),
        ) {
            KindControls(wallpaper) { wallpaper = it }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RoundAction(Icons.Rounded.Schedule, stringResource(R.string.gallery_clock)) {
                    sheet = "clock"
                }
                RoundAction(Icons.Rounded.Bolt, stringResource(R.string.gallery_shortcuts)) {
                    sheet = "shortcuts"
                }
            }
        }
    }

    if (sheet == "clock") {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF1C1C1E)) {
            ClockSheet(clock, tuning, { clock = it }, { tuning = it })
        }
    }
    if (sheet == "shortcuts") {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF1C1C1E)) {
            ShortcutsSheet(affordanceInteractor)
        }
    }
    if (askHome) {
        HomeDialog(wallpaper, onDismiss = { askHome = false }) { home ->
            askHome = false
            onDone(initial.copy(wallpaper = wallpaper, clock = clock, home = home), tuning)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(22.dp)).background(color).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x993A3A3C)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (on) Color.White else Color(0x993A3A3C))
                    .clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(label, color = if (on) Color.Black else Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Swatches(colors: List<Int?>, selected: Int?, onSelect: (Int?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        colors.forEach { c ->
            val on = c == selected
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color(0x55FFFFFF),
                        CircleShape)
                    .padding(if (on) 5.dp else 0.dp).clip(CircleShape)
                    .background(if (c == null) Brush.sweepGradient(listOf(Color.Red,
                        Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta,
                        Color.Red)) else SolidColor(Color(c)))
                    .clickable { onSelect(c) },
            )
        }
    }
}

@Composable
private fun KindControls(wallpaper: GalleryWallpaper, onChange: (GalleryWallpaper) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (wallpaper.kind) {
            Kind.PHOTO -> {
                Chips(listOf(stringResource(R.string.gallery_filter_natural),
                    stringResource(R.string.gallery_filter_mono),
                    stringResource(R.string.gallery_filter_duotone),
                    stringResource(R.string.gallery_filter_wash)), wallpaper.variant) {
                    onChange(wallpaper.copy(variant = it))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.gallery_depth), color = Color.White,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.gallery_depth_summary),
                            color = Color(0xB3FFFFFF), fontSize = 13.sp)
                    }
                    Switch(wallpaper.depth, { onChange(wallpaper.copy(depth = it)) },
                        colors = switchColors())
                }
                Text(stringResource(R.string.gallery_effect), color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp))
                Chips(GalleryEffect.entries.map { stringResource(it.label) }, wallpaper.effect) {
                    onChange(wallpaper.copy(effect = it))
                }
            }
            Kind.SHUFFLE -> Chips(listOf(stringResource(R.string.gallery_shuffle_tap),
                stringResource(R.string.gallery_shuffle_lock),
                stringResource(R.string.gallery_shuffle_hourly),
                stringResource(R.string.gallery_shuffle_daily)), wallpaper.shuffle.ordinal) {
                onChange(wallpaper.copy(shuffle = Shuffle.entries[it]))
            }
            Kind.COLOUR -> {
                Swatches(GalleryRenderer.COLOURS.toList(), wallpaper.colors.firstOrNull()) {
                    onChange(wallpaper.copy(colors = listOfNotNull(it)))
                }
                Chips(listOf(stringResource(R.string.gallery_colour_vibrant),
                    stringResource(R.string.gallery_colour_tone),
                    stringResource(R.string.gallery_colour_deep),
                    stringResource(R.string.gallery_colour_solid)), wallpaper.variant) {
                    onChange(wallpaper.copy(variant = it))
                }
            }
            Kind.EMOJI -> {
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)).background(Color(0x993A3A3C))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = wallpaper.emojis,
                        onValueChange = { text ->
                            val emojis = GalleryRenderer.splitEmoji(text).take(6)
                            onChange(wallpaper.copy(emojis = emojis.joinToString("")))
                        },
                        textStyle = TextStyle(fontSize = 26.sp, color = Color.White,
                            textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(Color.White),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Chips(listOf(stringResource(R.string.gallery_emoji_small),
                    stringResource(R.string.gallery_emoji_medium),
                    stringResource(R.string.gallery_emoji_large),
                    stringResource(R.string.gallery_emoji_rings),
                    stringResource(R.string.gallery_emoji_spiral)), wallpaper.variant) {
                    onChange(wallpaper.copy(variant = it))
                }
                Swatches(listOf(0xFFBFE3F2.toInt(), 0xFFF6D7E0.toInt(), 0xFFF3D36B.toInt(),
                    0xFFC8E6C9.toInt(), 0xFFE1D5F5.toInt(), 0xFF1D3B5A.toInt(),
                    0xFF2B2B2B.toInt(), 0xFFFFFFFF.toInt()), wallpaper.colors.firstOrNull()) {
                    onChange(wallpaper.copy(colors = listOfNotNull(it)))
                }
            }
            Kind.KALEIDOSCOPE -> Chips(listOf("6", "8", "12"), wallpaper.variant) {
                onChange(wallpaper.copy(variant = it))
            }
            Kind.ASTRONOMY -> Chips(listOf(stringResource(R.string.gallery_astro_earth),
                stringResource(R.string.gallery_astro_earth_detail),
                stringResource(R.string.gallery_astro_moon),
                stringResource(R.string.gallery_astro_moon_detail),
                stringResource(R.string.gallery_astro_solar),
                stringResource(R.string.gallery_astro_mars)), wallpaper.variant) {
                onChange(wallpaper.copy(variant = it))
            }
            Kind.BUBBLES, Kind.STRIPES, Kind.WAVES, Kind.PETALS -> {
                val count = when (wallpaper.kind) {
                    Kind.BUBBLES -> GalleryRenderer.BUBBLE_VARIANTS
                    Kind.STRIPES -> GalleryRenderer.STRIPE_VARIANTS
                    Kind.WAVES -> GalleryRenderer.WAVE_VARIANTS
                    else -> GalleryRenderer.PETAL_VARIANTS
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(count) { i ->
                        Box(
                            Modifier.padding(horizontal = 6.dp).width(44.dp).aspectRatio(0.5f)
                                .clip(RoundedCornerShape(10.dp))
                                .border(if (i == wallpaper.variant) 2.dp else 1.dp,
                                    if (i == wallpaper.variant) Color.White else Color(0x55FFFFFF),
                                    RoundedCornerShape(10.dp))
                                .clickable { onChange(wallpaper.copy(variant = i)) },
                        ) {
                            WallpaperImage(wallpaper.copy(variant = i), Modifier.fillMaxSize())
                        }
                    }
                }
            }
            Kind.WEATHER -> {}
            Kind.PAPER -> {
                // Choose among the wallpaper's own set: the Collections or the PenguinOS walls.
                val collection = GalleryRenderer.isCollectionPaper(wallpaper.variant)
                val variants =
                    if (collection) 0 until GalleryRenderer.PAPER_COLLECTIONS
                    else GalleryRenderer.PAPER_COLLECTIONS until GalleryRenderer.PAPERS.size
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    variants.forEach { i ->
                        Box(
                            Modifier.padding(horizontal = 6.dp).width(44.dp).aspectRatio(0.5f)
                                .clip(RoundedCornerShape(10.dp))
                                .border(if (i == wallpaper.variant) 2.dp else 1.dp,
                                    if (i == wallpaper.variant) Color.White else Color(0x55FFFFFF),
                                    RoundedCornerShape(10.dp))
                                .clickable { onChange(wallpaper.copy(variant = i)) },
                        ) {
                            WallpaperImage(wallpaper.copy(variant = i), Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockSheet(
    clock: ClockStyle,
    tuning: CustomClocks.Tuning,
    onChange: (ClockStyle) -> Unit,
    onTuning: (CustomClocks.Tuning) -> Unit,
) {
    val context = LocalContext.current
    val styles by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) { CustomClocks.names(context) }
    }
    // Kept to half the screen and scrolled inside, so the clock preview above stays in view.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
        .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (styles.size > 1) {
            Text(stringResource(R.string.gallery_clock_style), color = Color.White,
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(styles) { face, name ->
                    val on = clock.face == face
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(104.dp)
                            .clickable { onChange(clock.copy(face = face)) }) {
                        Box(
                            Modifier.fillMaxWidth().height(96.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) Color(0xFF48484A) else Color(0xFF2C2C2E))
                                .border(if (on) 2.dp else 0.dp,
                                    if (on) Color.White else Color.Transparent,
                                    RoundedCornerShape(14.dp))
                                .padding(6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            LockClock(clock.copy(face = face, small = face == 0),
                                if (face == 0) 30.dp else 84.dp, 92.dp, fixedTime = face == 0)
                        }
                        Text(name, color = Color.White, fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (clock.face == 0) {
            Text(stringResource(R.string.gallery_clock_font), color = Color.White,
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CLOCK_PRESETS.indices.forEach { i ->
                    val on = (clock.preset ?: 3) == i
                    Box(
                        Modifier.size(width = 64.dp, height = 72.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) Color(0xFF48484A) else Color(0xFF2C2C2E))
                            .border(if (on) 2.dp else 0.dp,
                                if (on) Color.White else Color.Transparent,
                                RoundedCornerShape(14.dp))
                            .clickable { onChange(clock.copy(preset = i)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        ClockText(ClockStyle(preset = i, rounded = clock.rounded, small = true),
                            30.dp, fixedTime = true)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gallery_clock_rounded), color = Color.White,
                    fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(clock.rounded, { onChange(clock.copy(rounded = it)) },
                    colors = switchColors())
            }
        }
        // Some custom styles keep their own colours on the lock screen.
        if (clock.face == 0 || CustomClocks.isColourable(clock.face)) {
            Text(stringResource(R.string.gallery_clock_colour), color = Color.White,
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            if (clock.face > 0) {
                val mode = when {
                    tuning.gradient -> 2
                    tuning.accent -> 1
                    else -> 0
                }
                Chips(listOf(stringResource(R.string.gallery_clock_colour_custom),
                    stringResource(R.string.gallery_clock_colour_accent),
                    stringResource(R.string.gallery_clock_colour_gradient)), mode) {
                    onTuning(tuning.copy(accent = it == 1, gradient = it == 2))
                }
            }
            if (clock.face == 0 || (!tuning.accent && !tuning.gradient)) {
                Swatches(CLOCK_COLOURS, clock.color) { onChange(clock.copy(color = it)) }
            }
            if (clock.face > 0 && tuning.gradient) {
                SheetLabel(stringResource(R.string.gallery_clock_gradient_start))
                Swatches(GRADIENT_COLOURS, tuning.gradientStart) {
                    onTuning(tuning.copy(gradientStart = it ?: tuning.gradientStart))
                }
                SheetLabel(stringResource(R.string.gallery_clock_gradient_end))
                Swatches(GRADIENT_COLOURS, tuning.gradientEnd) {
                    onTuning(tuning.copy(gradientEnd = it ?: tuning.gradientEnd))
                }
                TuningSlider(stringResource(R.string.gallery_clock_gradient_position),
                    tuning.gradientAnchorY, 0..100, "%") {
                    onTuning(tuning.copy(gradientAnchorY = it))
                }
                TuningSlider(stringResource(R.string.gallery_clock_gradient_spread),
                    tuning.gradientRadius, 25..200, "%") {
                    onTuning(tuning.copy(gradientRadius = it))
                }
            }
        }
        if (clock.face > 0) {
            Text(stringResource(R.string.gallery_clock_adjust), color = Color.White,
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            TuningSlider(stringResource(R.string.gallery_clock_size), tuning.scale, 50..150, "%") {
                onTuning(tuning.copy(scale = it))
            }
            TuningSlider(stringResource(R.string.gallery_clock_opacity), tuning.opacity, 0..100,
                "%") { onTuning(tuning.copy(opacity = it)) }
            TuningSlider(stringResource(R.string.gallery_clock_margin_top), tuning.marginTop,
                0..100, " dp") { onTuning(tuning.copy(marginTop = it)) }
            TuningSlider(stringResource(R.string.gallery_clock_margin_start), tuning.marginStart,
                -100..100, " dp") { onTuning(tuning.copy(marginStart = it)) }
            TuningSwitch(stringResource(R.string.gallery_clock_album_art),
                tuning.albumArtColour) { onTuning(tuning.copy(albumArtColour = it)) }
            TuningSwitch(stringResource(R.string.gallery_clock_aod_animation),
                tuning.aodAnimation) { onTuning(tuning.copy(aodAnimation = it)) }
            TuningSwitch(stringResource(R.string.gallery_clock_wobble), tuning.wobbleOnCharge) {
                onTuning(tuning.copy(wobbleOnCharge = it))
            }
            TuningSwitch(stringResource(R.string.gallery_clock_weather), tuning.weather) {
                onTuning(tuning.copy(weather = it))
            }
        }
        if (clock.face == 0) {
            Chips(listOf(stringResource(R.string.gallery_clock_large),
                stringResource(R.string.gallery_clock_small)), if (clock.small) 1 else 0) {
                onChange(clock.copy(small = it == 1))
            }
        }
    }
}

private val GRADIENT_COLOURS = listOf(0xFF00E5FF.toInt(), 0xFFFF2DAA.toInt(),
    0xFFFFD60A.toInt(), 0xFFFF9F0A.toInt(), 0xFF30D158.toInt(), 0xFFBF5AF2.toInt(),
    0xFF0A84FF.toInt(), 0xFFFFFFFF.toInt())

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = ACCENT,
    checkedBorderColor = ACCENT,
    uncheckedThumbColor = Color(0xFFAEAEB2),
    uncheckedTrackColor = Color(0xFF3A3A3C),
    uncheckedBorderColor = Color(0xFF3A3A3C),
)

@Composable
private fun SheetLabel(text: String) {
    Text(text, color = Color(0xB3FFFFFF), fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun TuningSlider(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("$value$unit", color = Color(0xB3FFFFFF), fontSize = 14.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Color.White,
                activeTrackColor = ACCENT, inactiveTrackColor = Color(0xFF3A3A3C)),
        )
    }
}

@Composable
private fun TuningSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange,
            colors = switchColors())
    }
}

@Composable
private fun ShortcutsSheet(interactor: KeyguardQuickAffordancePickerInteractor) {
    val scope = rememberCoroutineScope()
    val slots by interactor.slots.collectAsState(initial = emptyList())
    val affordances by interactor.affordances.collectAsState(initial = emptyList())
    val selections by interactor.selections.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.gallery_shortcuts), color = Color.White, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
        slots.forEachIndexed { index, slot ->
            Text(stringResource(if (index == 0) R.string.gallery_shortcut_left
                else R.string.gallery_shortcut_right),
                color = Color(0xB3FFFFFF), fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp))
            val chosen = selections.firstOrNull { it.slotId == slot.id }?.affordanceId
            val options = listOf(null to stringResource(R.string.gallery_shortcut_none)) +
                affordances.filter { it.isEnabled }.map { it.id to it.name }
            Chips(options.map { it.second }, options.indexOfFirst { it.first == chosen }
                .coerceAtLeast(0)) { i ->
                val id = options[i].first
                scope.launch {
                    if (id == null) interactor.unselectAllFromSlot(slot.id)
                    else interactor.select(slot.id, id)
                }
            }
        }
    }
}

@Composable
private fun HomeDialog(
    wallpaper: GalleryWallpaper,
    onDismiss: () -> Unit,
    onPick: (HomeStyle) -> Unit,
) {
    val context = LocalContext.current
    var dominant by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(wallpaper) {
        dominant = withContext(Dispatchers.Default) {
            GalleryRenderer.dominantColor(context, wallpaper)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF1C1C1E))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.gallery_home_title), color = Color.White,
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.gallery_home_summary), color = Color(0xB3FFFFFF),
                fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeStyle.entries.forEach { style ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(62.dp).clickable { onPick(style) }) {
                        val shape = RoundedCornerShape(12.dp)
                        val base = Color(dominant ?: 0xFF444444.toInt())
                        Box(Modifier.fillMaxWidth().aspectRatio(0.46f).clip(shape)
                            .border(1.dp, Color(0x33FFFFFF), shape)) {
                            when (style) {
                                HomeStyle.PAIR -> WallpaperImage(wallpaper, Modifier.fillMaxSize())
                                HomeStyle.BLUR -> WallpaperImage(wallpaper,
                                    Modifier.fillMaxSize().blur(8.dp))
                                HomeStyle.COLOUR -> Box(Modifier.fillMaxSize().background(base))
                                HomeStyle.GRADIENT -> Box(Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(listOf(
                                        Color(GalleryRenderer.blend(base.toArgb(),
                                            0xFFFFFFFF.toInt(), 0.3f)),
                                        Color(GalleryRenderer.blend(base.toArgb(),
                                            0xFF000000.toInt(), 0.45f))))))
                            }
                        }
                        Text(stringResource(when (style) {
                            HomeStyle.PAIR -> R.string.gallery_home_pair
                            HomeStyle.BLUR -> R.string.gallery_home_blur
                            HomeStyle.COLOUR -> R.string.gallery_colour
                            HomeStyle.GRADIENT -> R.string.gallery_home_gradient
                        }), color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
