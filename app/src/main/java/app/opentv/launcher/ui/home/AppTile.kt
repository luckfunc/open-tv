package app.opentv.launcher.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.opentv.launcher.apps.AppEntry
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.ui.components.Pressable
import app.opentv.launcher.ui.components.PressState
import app.opentv.launcher.ui.theme.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Card presentation only. All short/long/menu/pointer ownership belongs to Pressable. */
@Composable
fun AppTile(
    app: AppEntry,
    width: Dp,
    height: Dp,
    enabled: Boolean,
    moving: Boolean,
    onClick: (Rect?) -> Unit,
    onLongPress: (Rect?) -> Unit,
    onMenu: (Rect?) -> Unit = onLongPress,
    showLabel: Boolean = true,
    editing: Boolean = false,
    modifier: Modifier = Modifier,
    onNavigate: (FocusDirection) -> Boolean = { false },
) {
    val instant = OpenTvSettings.instantNavigation
    val reduced = LocalReducedMotion.current
    val focusManager = LocalFocusManager.current
    val interaction = remember { PressState() }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val active = enabled && (interaction.focused || moving)
    val scale by animateFloatAsState(
        NavigationPolicy.scale(instant, reduced, interaction.pressed && enabled, active),
        if (instant || reduced) snap() else spring(Motion.FocusDamping, Motion.FocusStiffness), label = "card-scale",
    )
    val lift by animateFloatAsState(if (active) 1f else 0f,
        if (instant || reduced) snap() else spring(Motion.FocusDamping, Motion.FocusStiffness), label = "card-emphasis")

    Box(modifier.size(width, height)) {
        Pressable(
            onClick = { onClick(bounds) }, onLongClick = { onLongPress(bounds) }, onMenu = { onMenu(bounds) },
            enabled = enabled, interactionState = interaction, focusedScale = 1f,
            modifier = Modifier.size(width, height)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    shadowElevation = if (reduced) 0f else 20.dp.toPx() * lift
                    alpha = if (editing && !moving) 0.60f else 1f
                    shape = RoundedCornerShape(ShapeTokens.Card); clip = true
                },
            onKey = { event ->
                val direction = when (event.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                    NativeKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                    else -> null
                }
                if (direction == null) false else {
                    if (event.type == KeyEventType.KeyDown && !onNavigate(direction)) focusManager.moveFocus(direction)
                    true
                }
            },
        ) { state ->
            Canvas(Modifier.size(width, height)) {
                val art = app.art
                val cover = max(size.width / art.back.width, size.height / art.back.height)
                val drawnW = art.back.width * cover; val drawnH = art.back.height * cover
                val left = (size.width - drawnW) / 2f; val top = (size.height - drawnH) / 2f
                drawImage(art.back, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(drawnW.roundToInt(), drawnH.roundToInt()), filterQuality = FilterQuality.Medium)
                art.fore?.let { image ->
                    val at = art.foreAt
                    drawImage(image,
                        dstOffset = IntOffset((left + at.left * drawnW).roundToInt(), (top + at.top * drawnH).roundToInt()),
                        dstSize = IntSize((at.width * drawnW).roundToInt(), (at.height * drawnH).roundToInt()), filterQuality = FilterQuality.Medium)
                }
                if (lift > 0.01f) {
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f * lift), Color.Transparent)))
                    if (state.pressed) drawRect(Color.Black.copy(alpha = 0.12f))
                }
                drawRoundRect(Color.White.copy(alpha = 0.10f + 0.12f * lift),
                    cornerRadius = CornerRadius(ShapeTokens.Card.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
        if (moving) {
            Canvas(Modifier.size(width, height).graphicsLayer { scaleX = scale; scaleY = scale; alpha = lift }) {
                val stroke = 2.dp.toPx(); val outset = 3.dp.toPx() + stroke / 2f
                val origin = Offset(-outset, -outset)
                val extent = Size(size.width + outset * 2, size.height + outset * 2)
                val radius = CornerRadius(ShapeTokens.Card.toPx() + outset)
                drawRoundRect(Color.Black.copy(alpha = 0.3f), origin, extent, radius, style = Stroke(stroke + 1.dp.toPx()))
                drawRoundRect(Color.White, origin, extent, radius, style = Stroke(stroke))
            }
        }
        if (moving) Box(Modifier.align(Alignment.TopEnd).offset(x = 13.dp, y = (-13).dp)
            .size(28.dp).clip(CircleShape).background(Palette.Focus), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Move, Palette.OnFocus, 16.dp)
        }
        if (showLabel && !editing) BasicText(app.label,
            style = Type.Headline.copy(textAlign = TextAlign.Center, shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 8f)),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = height + 14.dp)
                .requiredSize(width + 20.dp, 26.dp).graphicsLayer {
                    alpha = if (OpenTvSettings.showAllNames) 0.7f + 0.3f * lift else lift
                    translationY = height.toPx() * (scale - 1f) / 2f
                },
        )
    }
}
