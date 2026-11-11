package dev.lumen.launcher

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.RectF
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Performance notes, because this runs on a budget quad-core:
 *
 *  - Every animation writes only to a graphics layer (translate / scale / rotate / alpha)
 *    or to a draw block. Nothing is re-measured or re-laid-out while you navigate.
 *  - Animated values are read inside layer and draw lambdas, never during composition,
 *    so moving focus recomposes two cards (the one losing focus and the one gaining it).
 *  - There is no live blur anywhere. The backdrop is a 48x27 pre-blurred bitmap stretched
 *    by the GPU, and the focus shadow is one shared pre-rendered bitmap.
 */

private const val AMBIENT_ALPHA = 0.62f
private val Placeholder = Color(0xFF1D1E24)
private val SheenColors = listOf(Color.Transparent, Color.White.copy(alpha = 0.26f), Color.Transparent)
private val SoftShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 10f)

@Composable
fun HomeScreen(state: LauncherState) {
    val m = state.metrics
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Backdrop(state)
        Content(state, m)
        TopFade(state, m)
        Clock(m)
        MoveHint(state, m)
        MenuOverlay(state, m)
    }
}

// -------------------------------------------------------------------- backdrop

/** Wallpaper: a dark base washed with the colours of whichever app is selected. */
@Composable
private fun Backdrop(state: LauncherState) {
    val selected = state.selectedId
    var shownId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selected) {
        // Wait for fast D-pad travel to settle before repainting the whole screen.
        delay(140)
        shownId = selected
    }
    val target = state.entry(shownId)?.ambient

    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target != null && target !== current) {
            previous = current
            current = target
            fade.snapTo(0f)
            fade.animateTo(1f, tween(durationMillis = 520, easing = LinearOutSlowInEasing))
            previous = null
        }
    }

    val base = remember { Brush.verticalGradient(listOf(Color(0xFF15161D), Color(0xFF07080B))) }
    val scrim = remember {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.30f),
            0.5f to Color.Black.copy(alpha = 0.46f),
            1f to Color.Black.copy(alpha = 0.82f),
        )
    }
    Spacer(
        Modifier.fillMaxSize().drawBehind {
            drawRect(base)
            val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val f = fade.value
            val prev = previous
            val cur = current
            if (prev != null && f < 1f) {
                drawImage(image = prev, dstSize = dst, alpha = (1f - f) * AMBIENT_ALPHA)
            }
            if (cur != null) {
                drawImage(image = cur, dstSize = dst, alpha = f * AMBIENT_ALPHA)
            }
            drawRect(scrim)
        },
    )
}

// -------------------------------------------------------------------- scrolling content

@Composable
private fun Content(state: LauncherState, m: Metrics) {
    val scrollTarget by remember(state) {
        derivedStateOf { state.scrollFor(state.locate(state.selectedId)) }
    }
    val scroll = animateFloatAsState(
        targetValue = scrollTarget,
        animationSpec = spring(dampingRatio = 1f, stiffness = 380f),
        label = "scroll",
    )
    val intro = remember { Animatable(0f) }
    val loaded = state.loaded
    LaunchedEffect(loaded) {
        if (loaded) intro.animateTo(1f, tween(durationMillis = 520, easing = LinearOutSlowInEasing))
    }
    val shadow = remember(m) { makeShadow(m) }

    Box(
        Modifier.fillMaxSize().graphicsLayer {
            translationY = -scroll.value + (1f - intro.value) * m.px(16f)
            alpha = intro.value
        },
    ) {
        Hero(state, m, scroll)
        DockGlass(state, m)
        Cards(state, m, shadow)
        FocusTitle(state, m)
    }
}

/** Large name of the selected app above the dock. Scrolls away with the content. */
@Composable
private fun Hero(state: LauncherState, m: Metrics, scroll: State<Float>) {
    val entry = state.entry(state.selectedId) ?: return
    val date by produceState(initialValue = formatDate()) {
        while (true) {
            value = formatDate()
            delay(60_000L)
        }
    }
    Column(
        Modifier.graphicsLayer {
            translationX = m.side
            translationY = m.px(146f)
            alpha = (1f - scroll.value / m.px(120f)).coerceIn(0f, 1f)
        },
    ) {
        BasicText(
            text = date,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.58f),
                fontSize = m.sp(11f),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = m.sp(1.6f),
                shadow = SoftShadow,
            ),
        )
        Spacer(Modifier.height(m.dp(4f)))
        key(entry.id) {
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                appear.animateTo(1f, tween(durationMillis = 340, easing = LinearOutSlowInEasing))
            }
            BasicText(
                text = entry.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White,
                    fontSize = m.sp(42f),
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = m.sp(-0.5f),
                    shadow = SoftShadow,
                ),
                modifier = Modifier.width(m.dp(640f)).graphicsLayer {
                    alpha = appear.value
                    translationX = (appear.value - 1f) * m.px(12f)
                },
            )
        }
        Spacer(Modifier.height(m.dp(4f)))
        BasicText(
            text = "Hold OK for options",
            style = TextStyle(
                color = Color.White.copy(alpha = 0.42f),
                fontSize = m.sp(11.5f),
                fontWeight = FontWeight.Medium,
                shadow = SoftShadow,
            ),
        )
    }
}

/** The translucent shelf the dock row sits on. */
@Composable
private fun DockGlass(state: LauncherState, m: Metrics) {
    if (state.dock.isEmpty()) return
    val shape = remember(m) { RoundedCornerShape(m.dp(22f)) }
    Box(
        Modifier
            .graphicsLayer {
                translationX = m.side - m.glassPad
                translationY = m.dockTop - m.glassPad
            }
            .size(
                m.toDp(m.screenW - 2f * (m.side - m.glassPad)),
                m.toDp(m.cardH + 2f * m.glassPad),
            )
            .background(Color.White.copy(alpha = 0.075f), shape)
            .border(m.toDp(max(1f, m.px(0.6f))), Color.White.copy(alpha = 0.10f), shape),
    )
}

// -------------------------------------------------------------------- cards

private class Slot(val entry: AppEntry, val x: Float, val y: Float)

@Composable
private fun Cards(state: LauncherState, m: Metrics, shadow: ShadowSpec) {
    val dock = state.dock
    val grid = state.grid
    // One list and one loop, so a card keeps its identity (and animates) when it moves
    // between the dock and the grid.
    val slots = remember(dock, grid, m) {
        val list = ArrayList<Slot>(dock.size + grid.size)
        dock.forEachIndexed { i, e -> list.add(Slot(e, m.slotX(i), m.dockTop)) }
        grid.forEachIndexed { i, e ->
            list.add(Slot(e, m.slotX(i % Metrics.COLS), m.gridY(i / Metrics.COLS)))
        }
        list
    }
    val gloss = remember {
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.17f), 0.6f to Color.Transparent)
    }
    for (slot in slots) {
        key(slot.entry.id) {
            AppCard(slot.entry, slot.x, slot.y, state, m, shadow, gloss)
        }
    }
}

@Composable
private fun AppCard(
    entry: AppEntry,
    x: Float,
    y: Float,
    state: LauncherState,
    m: Metrics,
    shadow: ShadowSpec,
    gloss: Brush,
) {
    val focused by remember(entry, state) {
        derivedStateOf { state.selectedId == entry.id }
    }
    val moving by remember(entry, state) {
        derivedStateOf { state.mode == Mode.Move && state.selectedId == entry.id }
    }
    val dimmed by remember(entry, state) {
        derivedStateOf { state.mode == Mode.Move && state.selectedId != entry.id }
    }
    val pressed by remember(entry, state) {
        derivedStateOf { state.pressed && state.selectedId == entry.id }
    }

    val slide = remember { spring<Float>(dampingRatio = 0.82f, stiffness = 340f) }
    val posX = animateFloatAsState(x, slide, label = "x")
    val posY = animateFloatAsState(y, slide, label = "y")
    val focus = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.66f, stiffness = 600f),
        label = "focus",
    )
    val press = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 900f),
        label = "press",
    )
    val lift = animateFloatAsState(
        targetValue = if (moving) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "lift",
    )
    val dim = animateFloatAsState(
        targetValue = if (dimmed) 0.5f else if (focused) 0f else 0.12f,
        animationSpec = tween(durationMillis = 180),
        label = "dim",
    )

    // Parallax: the card is nudged in the direction focus arrived from, then springs level.
    val tiltY = remember { Animatable(0f) }
    val tiltX = remember { Animatable(0f) }
    // Sheen: one band of light sweeps across the card as it takes focus.
    val sheen = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            val dir = state.lastDir
            launch {
                tiltY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 170f),
                    initialVelocity = dir.first * 110f,
                )
            }
            launch {
                tiltX.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 170f),
                    initialVelocity = -dir.second * 90f,
                )
            }
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(durationMillis = 560, delayMillis = 70, easing = FastOutSlowInEasing))
        } else {
            sheen.snapTo(1f)
            if (tiltY.value != 0f || tiltX.value != 0f) {
                launch { tiltY.animateTo(0f, tween(durationMillis = 140)) }
                launch { tiltX.animateTo(0f, tween(durationMillis = 140)) }
            }
        }
    }

    val jiggle: State<Float>? = if (moving) {
        rememberInfiniteTransition(label = "jiggle").animateFloat(
            initialValue = -1.1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 130, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "jiggle",
        )
    } else {
        null
    }

    val icon = entry.icon
    val appear = animateFloatAsState(
        targetValue = if (icon != null) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "appear",
    )
    val shape = remember(m) { RoundedCornerShape(m.toDp(m.corner)) }
    val stroke = max(1f, m.px(0.7f))

    Box(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .size(m.toDp(m.cardW), m.toDp(m.cardH))
            .graphicsLayer {
                translationX = posX.value
                translationY = posY.value
                val s = 1f + (Metrics.FOCUS_SCALE - 1f) * focus.value -
                    0.07f * press.value + 0.05f * lift.value
                scaleX = s
                scaleY = s
                rotationY = tiltY.value
                rotationX = tiltX.value
                rotationZ = jiggle?.value ?: 0f
                cameraDistance = 12f * density
            }
            .drawBehind {
                val a = focus.value.coerceIn(0f, 1f)
                if (a > 0.02f) {
                    drawImage(
                        image = shadow.image,
                        dstOffset = IntOffset(-shadow.pad, -shadow.pad + shadow.dy),
                        dstSize = IntSize(shadow.w, shadow.h),
                        alpha = a,
                    )
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Placeholder)
                .drawWithContent {
                    drawContent()
                    val d = dim.value.coerceIn(0f, 1f)
                    if (d > 0.004f) drawRect(Color.Black, alpha = d)
                    val f = focus.value.coerceIn(0f, 1f)
                    if (f > 0.02f) {
                        drawRect(gloss, alpha = f)
                        val p = sheen.value
                        if (p < 0.999f) {
                            val band = size.width * 0.55f
                            val x0 = -band + (size.width + band) * p
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = SheenColors,
                                    start = Offset(x0, 0f),
                                    end = Offset(x0 + band, size.height * 0.8f),
                                ),
                                alpha = f,
                            )
                        }
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(m.corner - stroke / 2f),
                            style = Stroke(width = stroke),
                            alpha = 0.28f * f,
                        )
                    }
                },
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = entry.label,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = appear.value },
                )
            }
        }
    }
}

/** Name under the focused card. One shared label instead of one per card. */
@Composable
private fun FocusTitle(state: LauncherState, m: Metrics) {
    if (state.mode == Mode.Move) return
    val id = state.selectedId ?: return
    val entry = state.entry(id) ?: return
    val loc = state.locate(id) ?: return
    val slot = state.slotOf(loc)
    val extra = m.gap
    // Dock names sit just under the glass shelf; grid names sit just under the grown card.
    val below = if (loc.inDock) {
        m.cardH + m.glassPad + m.px(6f)
    } else {
        m.cardH * (1f + (Metrics.FOCUS_SCALE - 1f) / 2f) + m.px(8f)
    }
    key(id) {
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, tween(durationMillis = 240, delayMillis = 60, easing = LinearOutSlowInEasing))
        }
        BasicText(
            text = entry.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = Color.White,
                fontSize = m.sp(12.5f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                shadow = SoftShadow,
            ),
            modifier = Modifier
                .graphicsLayer {
                    translationX = slot.first - extra / 2f
                    translationY = slot.second + below - (1f - appear.value) * m.px(5f)
                    alpha = appear.value
                }
                .width(m.toDp(m.cardW + extra)),
        )
    }
}

// -------------------------------------------------------------------- chrome

/** Fades out rows that have scrolled up underneath the clock. */
@Composable
private fun TopFade(state: LauncherState, m: Metrics) {
    // Only from the second grid row down: before that nothing has scrolled under the clock,
    // and the fade would just dim the top of the dock.
    val inGrid by remember(state) {
        derivedStateOf {
            val loc = state.locate(state.selectedId)
            loc != null && !loc.inDock && loc.index / Metrics.COLS >= 1
        }
    }
    val strength = animateFloatAsState(
        targetValue = if (inGrid) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "topFade",
    )
    val brush = remember {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.92f),
            0.5f to Color.Black.copy(alpha = 0.6f),
            1f to Color.Transparent,
        )
    }
    Spacer(
        Modifier.fillMaxWidth().height(m.dp(84f)).drawBehind {
            val a = strength.value.coerceIn(0f, 1f)
            if (a > 0.01f) drawRect(brush, alpha = a)
        },
    )
}

@Composable
private fun Clock(m: Metrics) {
    val context = LocalContext.current
    val time by produceState(initialValue = formatTime(context)) {
        while (true) {
            value = formatTime(context)
            // Wake just after each minute turns over.
            delay(60_000L - System.currentTimeMillis() % 60_000L + 40L)
        }
    }
    Box(Modifier.fillMaxSize()) {
        BasicText(
            text = time,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.9f),
                fontSize = m.sp(15f),
                fontWeight = FontWeight.Medium,
                shadow = SoftShadow,
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = m.dp(22f), end = m.toDp(m.side)),
        )
    }
}

@Composable
private fun MoveHint(state: LauncherState, m: Metrics) {
    val show = state.mode == Mode.Move
    val alphaAnim = animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "hint",
    )
    Box(Modifier.fillMaxSize()) {
        BasicText(
            text = "Move with the D-pad   ·   Press OK when done",
            style = TextStyle(
                color = Color.White.copy(alpha = 0.92f),
                fontSize = m.sp(12.5f),
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = m.dp(26f))
                .graphicsLayer { alpha = alphaAnim.value }
                .background(Color(0xE6202024), RoundedCornerShape(50))
                .padding(horizontal = m.dp(18f), vertical = m.dp(9f)),
        )
    }
}

// -------------------------------------------------------------------- options menu

@Composable
private fun MenuOverlay(state: LauncherState, m: Metrics) {
    val open = state.mode == Mode.Menu
    val t = animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
        label = "menu",
    )
    val visible by remember { derivedStateOf { t.value > 0.01f } }
    if (!open && !visible) return

    val target = state.menuTarget
    val items = state.menuItems
    val panel = remember(m) { RoundedCornerShape(m.dp(22f)) }
    val thumb = remember(m) { RoundedCornerShape(m.dp(8f)) }

    Box(
        modifier = Modifier.fillMaxSize().drawBehind {
            drawRect(Color.Black, alpha = 0.58f * t.value.coerceIn(0f, 1f))
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    val v = t.value
                    alpha = v.coerceIn(0f, 1f)
                    val s = 0.92f + 0.08f * v
                    scaleX = s
                    scaleY = s
                }
                .width(m.dp(290f))
                .background(Color(0xF4202024), panel)
                .border(m.toDp(max(1f, m.px(0.6f))), Color.White.copy(alpha = 0.09f), panel)
                .padding(m.dp(16f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val icon = target?.icon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.size(m.dp(128f), m.dp(72f)).clip(thumb),
                )
                Spacer(Modifier.height(m.dp(12f)))
            }
            BasicText(
                text = target?.label ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White,
                    fontSize = m.sp(17f),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(m.dp(12f)))
            items.forEachIndexed { index, item ->
                MenuRow(item, index, state, m)
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, index: Int, state: LauncherState, m: Metrics) {
    val selected by remember(index, state) { derivedStateOf { state.menuIndex == index } }
    val s by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "row",
    )
    val fraction = s.coerceIn(0f, 1f)
    val resting = if (item.destructive) Color(0xFFFF453A) else Color.White.copy(alpha = 0.92f)
    val shape = remember(m) { RoundedCornerShape(m.dp(10f)) }
    BasicText(
        text = item.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = lerp(resting, Color(0xFF101013), fraction),
            fontSize = m.sp(14f),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = m.dp(2f))
            .background(Color.White.copy(alpha = fraction), shape)
            .padding(vertical = m.dp(10f), horizontal = m.dp(12f)),
    )
}

// -------------------------------------------------------------------- helpers

/** Pre-rendered soft shadow shared by every card. */
private class ShadowSpec(
    val image: ImageBitmap,
    val pad: Int,
    val dy: Int,
    val w: Int,
    val h: Int,
)

private fun makeShadow(m: Metrics): ShadowSpec {
    val cardW = m.cardW.roundToInt()
    val cardH = m.cardH.roundToInt()
    val pad = (m.cardH * 0.42f).roundToInt().coerceAtLeast(6)
    val w = cardW + pad * 2
    val h = cardH + pad * 2
    // Half resolution is plenty for something this blurry.
    val scale = 0.5f
    val bw = (w * scale).roundToInt().coerceAtLeast(4)
    val bh = (h * scale).roundToInt().coerceAtLeast(4)
    val bitmap = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.argb(165, 0, 0, 0)
    paint.maskFilter = BlurMaskFilter(max(1f, pad * scale * 0.5f), BlurMaskFilter.Blur.NORMAL)
    val inset = pad * scale
    val radius = m.corner * scale
    canvas.drawRoundRect(RectF(inset, inset, bw - inset, bh - inset), radius, radius, paint)
    return ShadowSpec(bitmap.asImageBitmap(), pad, (m.cardH * 0.16f).roundToInt(), w, h)
}

private fun formatTime(context: Context): String = try {
    DateFormat.getTimeFormat(context).format(Date())
} catch (e: Throwable) {
    ""
}

private fun formatDate(): String = try {
    SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
} catch (e: Throwable) {
    ""
}
