package com.android.customization.gallery.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.customization.gallery.GalleryStore
import com.android.customization.gallery.GalleryWallpaper.Kind
import com.android.customization.gallery.LockScreen
import com.android.themepicker.R
import kotlin.math.abs
import kotlin.math.min

fun kindLabel(kind: Kind): Int = when (kind) {
    Kind.COLOUR -> R.string.gallery_colour
    Kind.BUBBLES, Kind.STRIPES, Kind.WAVES, Kind.PETALS -> R.string.gallery_collections
    Kind.ASTRONOMY -> R.string.gallery_astronomy
    Kind.WEATHER -> R.string.gallery_weather
    Kind.EMOJI -> R.string.gallery_emoji
    Kind.KALEIDOSCOPE -> R.string.gallery_kaleidoscope
    Kind.PHOTO -> R.string.gallery_photo
    Kind.SHUFFLE -> R.string.gallery_photo_shuffle
}

/**
 * Long-press on the lock screen lands here: swipe through lock screens, tap one to use it. As on
 * iOS, the lock screen shrinks into its card on the way in and grows back out of it on the way
 * to applying or customising.
 */
@Composable
fun SwitcherScreen(
    store: GalleryStore,
    version: Int,
    zoomIn: Boolean,
    onApply: (LockScreen) -> Unit,
    onCustomise: (LockScreen) -> Unit,
    onAdd: () -> Unit,
    onDelete: (LockScreen) -> Unit,
) {
    var changes by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val listener: () -> Unit = { changes++ }
        store.addListener(listener)
        onDispose { store.removeListener(listener) }
    }
    val lockScreens = remember(version, changes) { store.lockScreens }
    val current = remember(version, changes) {
        lockScreens.indexOfFirst { it.id == store.currentId }.coerceAtLeast(0)
    }
    val pager = rememberPagerState(initialPage = current) { lockScreens.size }
    val scope = rememberCoroutineScope()
    // 1 is the lock screen filling the display, 0 is it sitting in its card.
    val zoom = remember { Animatable(if (zoomIn) 1f else 0f) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    var zooming by remember { mutableStateOf<LockScreen?>(null) }
    val spring = spring<Float>(dampingRatio = 0.86f, stiffness = 320f)

    LaunchedEffect(cardBounds != null) {
        if (cardBounds != null && zoom.value > 0f && zooming == null) zoom.animateTo(0f, spring)
    }

    fun zoomOut(lockScreen: LockScreen, then: (LockScreen) -> Unit) {
        if (zooming != null) return
        zooming = lockScreen
        scope.launch {
            zoom.animateTo(1f, spring)
            then(lockScreen)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val fullWidth = constraints.maxWidth.toFloat()
        val fullHeight = constraints.maxHeight.toFloat()
        val chrome = 1f - zoom.value
        Column(
            Modifier.fillMaxSize().systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val shown = lockScreens.getOrNull(pager.currentPage)
            AnimatedContent(
                targetState = shown?.let { stringResource(kindLabel(it.wallpaper.kind)) } ?: "",
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "kind",
                modifier = Modifier.padding(top = 48.dp, bottom = 24.dp).alpha(chrome),
            ) { label ->
                Text(label.uppercase(), color = Color.White, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 64.dp),
                pageSpacing = 20.dp,
                userScrollEnabled = zoom.value == 0f,
                modifier = Modifier.weight(1f),
            ) { page ->
                val lockScreen = lockScreens[page]
                val isCurrent = page == pager.currentPage
                var drag by remember(lockScreen.id) { mutableFloatStateOf(0f) }
                val lift by animateFloatAsState(drag, label = "lift")
                Box(contentAlignment = Alignment.BottomCenter) {
                    if (drag < 0) {
                        Box(
                            Modifier.padding(bottom = 24.dp).size(56.dp).clip(CircleShape)
                                .background(Color(0xFFFF453A))
                                .clickable { onDelete(lockScreen) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🗑", fontSize = 22.sp)
                        }
                    }
                    val pageOffset =
                        abs(pager.currentPage - page + pager.currentPageOffsetFraction)
                    LockScreenCard(
                        wallpaper = lockScreen.wallpaper,
                        clock = lockScreen.clock,
                        live = isCurrent,
                        fixedTime = false,
                        corner = 36.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.46f)
                            .onGloballyPositioned {
                                if (isCurrent && drag == 0f) cardBounds = it.boundsInRoot()
                            }
                            .graphicsLayer {
                                // Shrinks toward its top edge so the pager doesn't clip it.
                                transformOrigin = TransformOrigin(0.5f, 0f)
                                val scale =
                                    (1f - 0.08f * min(1f, pageOffset)) * (1f + lift / 1800f)
                                scaleX = scale
                                scaleY = scale
                                // The zooming copy stands in for the current card.
                                alpha = if (isCurrent && zoom.value > 0f) 0f
                                else if (isCurrent) 1f else chrome
                            }
                            .pointerInput(lockScreen.id) {
                                // Swipe a lock screen up to reveal delete, as on iOS.
                                detectVerticalDragGestures(
                                    onDragEnd = { drag = if (drag < -120f) -330f else 0f },
                                ) { _, dy -> drag = (drag + dy).coerceIn(-380f, 0f) }
                            },
                        onClick = {
                            if (drag < 0) drag = 0f else zoomOut(lockScreen, onApply)
                        },
                    )
                }
            }
            Row(Modifier.padding(vertical = 20.dp).alpha(chrome),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(lockScreens.size) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(
                        if (it == pager.currentPage) Color.White else Color(0x66FFFFFF)))
                }
            }
            Row(
                Modifier.padding(bottom = 32.dp).alpha(chrome),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(72.dp))
                Box(
                    Modifier.width(200.dp).height(56.dp).clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF1C1C1E)).border(1.dp, Color(0x33FFFFFF),
                            RoundedCornerShape(28.dp))
                        .clickable(enabled = shown != null) {
                            shown?.let { zoomOut(it, onCustomise) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.gallery_customise), color = Color.White,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(16.dp))
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF1C1C1E))
                        .border(1.dp, Color(0x33FFFFFF), CircleShape).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Color.White, fontSize = 30.sp,
                        fontWeight = FontWeight.Light)
                }
            }
        }

        // The lock screen on its way between the display and its card.
        // Until the card has been laid out, the lock screen simply fills the display.
        val card = cardBounds ?: Rect(0f, 0f, fullWidth, fullHeight)
        val moving = zooming ?: lockScreens.getOrNull(pager.currentPage)
        if (moving != null && zoom.value > 0f) {
            val t = zoom.value
            val left = card.left * (1 - t)
            val top = card.top * (1 - t)
            val width = card.width + (fullWidth - card.width) * t
            val height = card.height + (fullHeight - card.height) * t
            val density = LocalDensity.current
            LockScreenCard(
                wallpaper = moving.wallpaper,
                clock = moving.clock,
                live = true,
                fixedTime = false,
                corner = 36.dp * (1 - t),
                modifier = Modifier
                    .offset { IntOffset(left.toInt(), top.toInt()) }
                    .size(with(density) { width.toDp() }, with(density) { height.toDp() }),
            )
        }
    }
}
