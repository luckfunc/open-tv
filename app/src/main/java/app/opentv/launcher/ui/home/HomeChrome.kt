package app.opentv.launcher.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.opentv.launcher.ui.components.Pressable
import app.opentv.launcher.ui.components.GlassSurface
import app.opentv.launcher.ui.components.LocalBackdropScene
import app.opentv.launcher.ui.components.SceneFrame
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import app.opentv.launcher.ui.theme.Glyph
import app.opentv.launcher.ui.theme.GlyphIcon
import app.opentv.launcher.ui.theme.Palette
import app.opentv.launcher.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The current time, ticking on the minute. */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(60_000L - System.currentTimeMillis() % 60_000L + 30L)
    }
}

@Composable
fun timeText(now: LocalDateTime): String {
    val context = LocalContext.current
    // 24-hour unless the owner chose 12-hour. With the setting unset, Android falls back to the
    // locale, and zh_CN means 12-hour, which TV clocks here never show (TCL 65S515D, issue #15).
    val twelve = runCatching { android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24) }.getOrNull() == "12"
    val pattern = if (twelve) "h:mm" else "H:mm"
    return now.format(DateTimeFormatter.ofPattern(pattern))
}

/** One full-screen draw; materials sample this exact frame through the same scene owner. */
@Composable
fun HomeBackground() {
    val scene = LocalBackdropScene.current
    Canvas(Modifier.fillMaxSize()) {
        when (val frame = scene.frame()) {
            SceneFrame.Empty -> drawRect(Color.Black)
            is SceneFrame.Ready -> drawImage(frame.base,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low,
                colorFilter = if (frame.dimming > 0f) ColorFilter.tint(Color.Black.copy(alpha = frame.dimming), BlendMode.SrcAtop) else null)
        }
    }
}

/** Top-right time and Control Center entry, reached with Up from the top row. */
@Composable
fun StatusPill(
    now: LocalDateTime,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDown: () -> Unit = {},
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusedScale = 1.08f,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (enabled && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.type == KeyEventType.KeyDown) onDown()
                true
            } else false
        },
    ) { state ->
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        GlassSurface(CircleShape, focused = state.focused) {
            Row(Modifier.height(32.dp).padding(start = 15.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(timeText(now), style = Type.Callout.copy(color = fg))
                Box(Modifier.size(24.dp).clip(CircleShape).background(fg.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Switches, fg, 15.dp)
                }
            }
        }
    }
}
