package com.android.customization.gallery

import org.json.JSONArray
import org.json.JSONObject

/** Everything needed to draw one gallery wallpaper; kept as JSON in [GalleryStore]. */
data class GalleryWallpaper(
    val kind: Kind,
    /** Style within the kind: palette, planet, emoji layout, filter... */
    val variant: Int = 0,
    val colors: List<Int> = emptyList(),
    /** Photos copied into the app's files, so the wallpaper service can always read them. */
    val photos: List<String> = emptyList(),
    val emojis: String = "",
    val shuffle: Shuffle = Shuffle.ON_LOCK,
    /** Whether the photo's subject is lifted above the clock (SystemUI depth wallpaper). */
    val depth: Boolean = false,
    /** A [GalleryEffect] ordinal, for photos. */
    val effect: Int = 0,
) {
    enum class Kind(val live: Boolean) {
        COLOUR(false),
        BUBBLES(true),
        STRIPES(false),
        WAVES(true),
        PETALS(false),
        ASTRONOMY(true),
        WEATHER(true),
        EMOJI(false),
        KALEIDOSCOPE(false),
        PHOTO(false),
        SHUFFLE(true),
        /** Bundled images: [GalleryRenderer.PAPERS]. */
        PAPER(false),
    }

    enum class Shuffle { ON_TAP, ON_LOCK, HOURLY, DAILY }

    /** Moves from frame to frame, rather than changing now and then. */
    val isAnimated: Boolean
        get() = kind == Kind.BUBBLES || kind == Kind.WEATHER

    fun toJson(): JSONObject =
        JSONObject()
            .put("kind", kind.name)
            .put("variant", variant)
            .put("colors", JSONArray(colors))
            .put("photos", JSONArray(photos))
            .put("emojis", emojis)
            .put("shuffle", shuffle.name)
            .put("depth", depth)
            .put("effect", effect)

    companion object {
        const val ASTRO_EARTH = 0
        const val ASTRO_EARTH_DETAIL = 1
        const val ASTRO_MOON = 2
        const val ASTRO_MOON_DETAIL = 3
        const val ASTRO_SOLAR = 4
        const val ASTRO_MARS = 5

        const val COLOUR_VIBRANT = 0
        const val COLOUR_TONE = 1
        const val COLOUR_DEEP = 2
        const val COLOUR_SOLID = 3

        const val FILTER_NATURAL = 0
        const val FILTER_MONO = 1
        const val FILTER_DUOTONE = 2
        const val FILTER_WASH = 3

        const val EMOJI_SMALL = 0
        const val EMOJI_MEDIUM = 1
        const val EMOJI_LARGE = 2
        const val EMOJI_RINGS = 3
        const val EMOJI_SPIRAL = 4

        fun fromJson(json: JSONObject): GalleryWallpaper {
            val colors = json.optJSONArray("colors") ?: JSONArray()
            val photos = json.optJSONArray("photos") ?: JSONArray()
            return GalleryWallpaper(
                kind = Kind.valueOf(json.getString("kind")),
                variant = json.optInt("variant"),
                colors = List(colors.length()) { colors.getInt(it) },
                photos = List(photos.length()) { photos.getString(it) },
                emojis = json.optString("emojis"),
                shuffle =
                    runCatching { Shuffle.valueOf(json.optString("shuffle")) }
                        .getOrDefault(Shuffle.ON_LOCK),
                depth = json.optBoolean("depth"),
                effect = json.optInt("effect"),
            )
        }
    }
}

/** How the home screen relates to the lock screen, like iOS's wallpaper pairs. */
enum class HomeStyle { PAIR, BLUR, COLOUR, GRADIENT }

/** A saved lock screen: its wallpaper, the home screen that goes with it and its clock. */
data class LockScreen(
    val id: String,
    val wallpaper: GalleryWallpaper,
    val home: HomeStyle = HomeStyle.PAIR,
    val clock: ClockStyle = ClockStyle(),
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("wallpaper", wallpaper.toJson())
            .put("home", home.name)
            .put("clock", clock.toJson())

    companion object {
        fun fromJson(json: JSONObject) =
            LockScreen(
                id = json.getString("id"),
                wallpaper = GalleryWallpaper.fromJson(json.getJSONObject("wallpaper")),
                home =
                    runCatching { HomeStyle.valueOf(json.optString("home")) }
                        .getOrDefault(HomeStyle.PAIR),
                clock = json.optJSONObject("clock")?.let(ClockStyle::fromJson) ?: ClockStyle(),
            )
    }
}

/**
 * The lock screen clock as SystemUI's flex clock understands it. Null fields leave whatever the
 * device already has.
 */
data class ClockStyle(
    /** A custom clock style from Settings; 0 is SystemUI's own clock, styled below. */
    val face: Int = 0,
    /** Index into the flex clock's weight/width presets, thinnest last. */
    val preset: Int? = null,
    val rounded: Boolean = false,
    val color: Int? = null,
    val small: Boolean = false,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("face", face)
            .put("preset", preset ?: JSONObject.NULL)
            .put("rounded", rounded)
            .put("color", color ?: JSONObject.NULL)
            .put("small", small)

    companion object {
        fun fromJson(json: JSONObject) =
            ClockStyle(
                face = json.optInt("face"),
                preset = if (json.isNull("preset")) null else json.optInt("preset"),
                rounded = json.optBoolean("rounded"),
                color = if (json.isNull("color")) null else json.optInt("color"),
                small = json.optBoolean("small"),
            )
    }
}
