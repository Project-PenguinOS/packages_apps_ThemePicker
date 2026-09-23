package com.android.customization.gallery.ui

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.customization.gallery.ClockStyle
import com.android.customization.gallery.GalleryRenderer
import com.android.customization.gallery.GalleryStore
import com.android.customization.gallery.GalleryWallpaper
import com.android.customization.gallery.GalleryWallpaper.Kind
import com.android.customization.gallery.GalleryWeather
import com.android.themepicker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TILE_CLOCK = ClockStyle(preset = 5)

private data class Tile(val wallpaper: GalleryWallpaper?, val label: String? = null,
        val onClick: (() -> Unit)? = null, val emoji: String? = null)

/** The full-screen wallpaper gallery, in the order and style of iOS's. */
@Composable
fun GalleryScreen(
    store: GalleryStore,
    version: Int,
    featured: List<Pair<String, Long>>,
    hasPhotoAccess: Boolean,
    onRequestPhotoAccess: () -> Unit,
    onCancel: () -> Unit,
    onPick: (GalleryWallpaper) -> Unit,
    onPickPhoto: () -> Unit,
    onPickShuffle: () -> Unit,
    onPickKaleidoscope: () -> Unit,
    onPacksChanged: () -> Unit,
) {
    val context = LocalContext.current
    var weather by remember { mutableStateOf<GalleryWeather.Snapshot?>(null) }
    LaunchedEffect(Unit) {
        weather = withContext(Dispatchers.IO) { GalleryWeather.query(context) }
    }
    val featuredPhotos = featured.map { GalleryWallpaper(Kind.PHOTO, photos = listOf(it.first)) }
    val featuredShuffle = featured.takeIf { it.size >= 2 }?.let {
        GalleryWallpaper(Kind.SHUFFLE, photos = it.map { p -> p.first })
    }
    fun month(ms: Long) = DateFormat.format("MMMM yyyy", ms).toString()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp,
                bottom = 48.dp),
        ) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp)) {
                    item {
                        SourceButton(stringResource(R.string.gallery_photos),
                            featuredPhotos.firstOrNull(), if (featured.isEmpty()) "🖼️" else null,
                            onPickPhoto)
                    }
                    item {
                        SourceButton(stringResource(R.string.gallery_photo_shuffle), null, "🔀",
                            onPickShuffle)
                    }
                    item {
                        SourceButton(stringResource(R.string.gallery_emoji),
                            GalleryWallpaper(Kind.COLOUR, 1, listOf(0xFFF3D36B.toInt())), "😀") {
                            onPick(EMOJI_PRESETS[0])
                        }
                    }
                    item {
                        SourceButton(stringResource(R.string.gallery_weather),
                            GalleryWallpaper(Kind.WEATHER), null) {
                            onPick(GalleryWallpaper(Kind.WEATHER))
                        }
                    }
                    item {
                        SourceButton(stringResource(R.string.gallery_astronomy),
                            GalleryWallpaper(Kind.ASTRONOMY, GalleryWallpaper.ASTRO_EARTH),
                            null) {
                            onPick(GalleryWallpaper(Kind.ASTRONOMY, GalleryWallpaper.ASTRO_EARTH))
                        }
                    }
                    item {
                        SourceButton(stringResource(R.string.gallery_colour),
                            GalleryWallpaper(Kind.COLOUR, 0, listOf(GalleryRenderer.COLOURS[1])),
                            null) {
                            onPick(GalleryWallpaper(Kind.COLOUR, 0,
                                listOf(GalleryRenderer.COLOURS[1])))
                        }
                    }
                    if (store.isAdded(GalleryStore.PACK_KALEIDOSCOPE)) {
                        item {
                            SourceButton(stringResource(R.string.gallery_kaleidoscope), null,
                                "❄️", onPickKaleidoscope)
                        }
                    }
                    item {
                        SourceButton(stringResource(R.string.gallery_live), null, "✨") {
                            runCatching {
                                context.startActivity(android.content.Intent(
                                    android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                            }
                        }
                    }
                }
            }

            item {
                val tiles = mutableListOf(Tile(GalleryWallpaper(Kind.BUBBLES),
                    stringResource(R.string.gallery_collections)))
                featured.forEachIndexed { i, (_, taken) ->
                    tiles += Tile(featuredPhotos[i], month(taken))
                }
                featuredShuffle?.let {
                    tiles.add(1, Tile(it, stringResource(R.string.gallery_featured) + " ⤮"))
                }
                if (!hasPhotoAccess) {
                    tiles += Tile(null, stringResource(R.string.gallery_allow_photos),
                        onRequestPhotoAccess, "🔒")
                }
                Section(stringResource(R.string.gallery_featured), null, false, tiles,
                    weather, onPick)
            }

            item {
                val tiles = mutableListOf(Tile(null, stringResource(R.string.gallery_choose_photos),
                    onPickShuffle, "➕"))
                featuredShuffle?.let { tiles += Tile(it, stringResource(R.string.gallery_featured)) }
                Section(stringResource(R.string.gallery_photo_shuffle),
                    stringResource(R.string.gallery_photo_shuffle_summary), false, tiles, weather,
                    onPick)
            }

            item {
                Section(stringResource(R.string.gallery_weather),
                    stringResource(R.string.gallery_weather_summary), true,
                    listOf(Tile(GalleryWallpaper(Kind.WEATHER))), weather, onPick)
            }

            item {
                Section(stringResource(R.string.gallery_astronomy),
                    stringResource(R.string.gallery_astronomy_summary), true,
                    listOf(GalleryWallpaper.ASTRO_EARTH, GalleryWallpaper.ASTRO_EARTH_DETAIL,
                        GalleryWallpaper.ASTRO_MOON, GalleryWallpaper.ASTRO_MOON_DETAIL,
                        GalleryWallpaper.ASTRO_SOLAR, GalleryWallpaper.ASTRO_MARS).map {
                        Tile(GalleryWallpaper(Kind.ASTRONOMY, it))
                    }, weather, onPick)
            }

            item {
                val tiles = (0 until GalleryRenderer.BUBBLE_VARIANTS).map {
                    Tile(GalleryWallpaper(Kind.BUBBLES, it))
                } + (0 until GalleryRenderer.STRIPE_VARIANTS).map {
                    Tile(GalleryWallpaper(Kind.STRIPES, it))
                } + (0 until GalleryRenderer.WAVE_VARIANTS).map {
                    Tile(GalleryWallpaper(Kind.WAVES, it))
                }
                Section(stringResource(R.string.gallery_collections), null, true, tiles, weather,
                    onPick)
            }

            item {
                val tiles = listOf(
                    GalleryWallpaper(Kind.COLOUR, GalleryWallpaper.COLOUR_TONE,
                        listOf(GalleryRenderer.COLOURS[8])),
                    GalleryWallpaper(Kind.COLOUR, GalleryWallpaper.COLOUR_VIBRANT,
                        listOf(GalleryRenderer.COLOURS[0])),
                    GalleryWallpaper(Kind.COLOUR, GalleryWallpaper.COLOUR_DEEP,
                        listOf(GalleryRenderer.COLOURS[1])),
                    GalleryWallpaper(Kind.COLOUR, GalleryWallpaper.COLOUR_VIBRANT,
                        listOf(GalleryRenderer.COLOURS[2])),
                    GalleryWallpaper(Kind.COLOUR, GalleryWallpaper.COLOUR_TONE,
                        listOf(GalleryRenderer.COLOURS[4])),
                    GalleryWallpaper(Kind.COLOUR, GalleryWallpaper.COLOUR_DEEP,
                        listOf(GalleryRenderer.COLOURS[6])),
                ).map { Tile(it) }
                Section(stringResource(R.string.gallery_colour), null, true, tiles, weather,
                    onPick)
            }

            item {
                val device = remember { deviceName(context) }
                Section(device, stringResource(R.string.gallery_device_summary, device),
                    false, (0 until GalleryRenderer.PETAL_VARIANTS).map {
                        Tile(GalleryWallpaper(Kind.PETALS, it))
                    }, weather, onPick)
            }

            if (store.isAdded(GalleryStore.PACK_EMOJI)) {
                item {
                    Section(stringResource(R.string.gallery_emoji),
                        stringResource(R.string.gallery_emoji_summary), true,
                        EMOJI_PRESETS.map { Tile(it) }, weather, onPick)
                }
            }
            if (store.isAdded(GalleryStore.PACK_KALEIDOSCOPE)) {
                item {
                    val tiles = mutableListOf(Tile(null,
                        stringResource(R.string.gallery_choose_photo), onPickKaleidoscope, "➕"))
                    featured.take(4).forEachIndexed { i, (uri, _) ->
                        tiles += Tile(GalleryWallpaper(Kind.KALEIDOSCOPE, i % 3,
                            photos = listOf(uri)))
                    }
                    Section(stringResource(R.string.gallery_kaleidoscope),
                        stringResource(R.string.gallery_kaleidoscope_summary), true, tiles,
                        weather, onPick)
                }
            }
            PACKS.filterNot { store.isAdded(it.id) }.forEach { pack ->
                item(key = "pack-${pack.id}-$version") {
                    PackCard(pack) {
                        store.setAdded(pack.id, true)
                        onPacksChanged()
                    }
                }
            }
        }

        Box(
            Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp)
                .clip(RoundedCornerShape(22.dp)).background(Color(0xCC2C2C2E))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
                .clickable(onClick = onCancel).padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(stringResource(android.R.string.cancel), color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun deviceName(context: android.content.Context): String =
    android.provider.Settings.Global.getString(context.contentResolver,
        android.provider.Settings.Global.DEVICE_NAME) ?: Build.MODEL

val EMOJI_PRESETS = listOf(
    GalleryWallpaper(Kind.EMOJI, GalleryWallpaper.EMOJI_SMALL, listOf(0xFFBFE3F2.toInt()),
        emojis = "🐝☁️"),
    GalleryWallpaper(Kind.EMOJI, GalleryWallpaper.EMOJI_MEDIUM, listOf(0xFFF6D7E0.toInt()),
        emojis = "🌸🌷"),
    GalleryWallpaper(Kind.EMOJI, GalleryWallpaper.EMOJI_RINGS, listOf(0xFF1D3B5A.toInt()),
        emojis = "🐠🐙🫧"),
    GalleryWallpaper(Kind.EMOJI, GalleryWallpaper.EMOJI_LARGE, listOf(0xFFF3D36B.toInt()),
        emojis = "😀🥳😎"),
    GalleryWallpaper(Kind.EMOJI, GalleryWallpaper.EMOJI_SPIRAL, listOf(0xFF2B2B2B.toInt()),
        emojis = "🍕🍩🍟"),
)

private data class Pack(val id: String, val title: Int, val summary: Int,
        val previews: List<GalleryWallpaper>)

private val PACKS = listOf(
    Pack(GalleryStore.PACK_EMOJI, R.string.gallery_emoji, R.string.gallery_emoji_summary,
        EMOJI_PRESETS.take(3)),
    Pack(GalleryStore.PACK_KALEIDOSCOPE, R.string.gallery_kaleidoscope,
        R.string.gallery_kaleidoscope_summary,
        listOf(GalleryWallpaper(Kind.KALEIDOSCOPE, 0), GalleryWallpaper(Kind.KALEIDOSCOPE, 2))),
)

@Composable
private fun Section(
    title: String,
    summary: String?,
    check: Boolean,
    tiles: List<Tile>,
    weather: GalleryWeather.Snapshot?,
    onPick: (GalleryWallpaper) -> Unit,
) {
    Column(Modifier.padding(top = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            if (check) {
                Box(Modifier.size(30.dp).border(2.dp, Color(0x99FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("✓", color = Color(0xCCFFFFFF), fontSize = 16.sp)
                }
            }
        }
        if (summary != null) {
            Text(summary, color = Color(0xCCFFFFFF), fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            items(tiles) { tile ->
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(120.dp)) {
                    if (tile.wallpaper != null) {
                        LockScreenCard(
                            wallpaper = tile.wallpaper,
                            clock = TILE_CLOCK,
                            weather = weather,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.46f),
                            onClick = { tile.onClick?.invoke() ?: onPick(tile.wallpaper) },
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.46f)
                                .clip(RoundedCornerShape(24.dp)).background(Color(0xFF1C1C1E))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                                .clickable { tile.onClick?.invoke() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(tile.emoji ?: "", fontSize = 30.sp)
                        }
                    }
                    if (tile.label != null) {
                        Text(tile.label, color = Color.White, fontSize = 14.sp,
                            textAlign = TextAlign.Center, maxLines = 2,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PackCard(pack: Pack, onGet: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 32.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(128.dp).height(200.dp)) {
            pack.previews.reversed().forEachIndexed { i, wallpaper ->
                val back = pack.previews.size - 1 - i
                WallpaperImage(
                    wallpaper,
                    Modifier.offset(x = (back * 12).dp).width(100.dp).height(200.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp)),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(pack.title), color = Color.White, fontSize = 26.sp,
                fontWeight = FontWeight.Bold)
            Text(stringResource(pack.summary), color = Color(0xB3FFFFFF), fontSize = 16.sp,
                modifier = Modifier.padding(top = 6.dp))
            Box(
                Modifier.padding(top = 14.dp).clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF3A3A3C)).clickable(onClick = onGet)
                    .padding(horizontal = 28.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.gallery_get), color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
