package app.opentv.launcher.ui.components

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.ui.input.PressAction
import app.opentv.launcher.ui.input.PressInteraction
import app.opentv.launcher.ui.input.PressToken
import app.opentv.launcher.ui.theme.LocalReducedMotion
import app.opentv.launcher.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun KeyEvent.isSelect(): Boolean = when (nativeKeyEvent.keyCode) {
    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER,
    NativeKeyEvent.KEYCODE_BUTTON_A, NativeKeyEvent.KEYCODE_BUTTON_SELECT -> true
    else -> false
}

/** Observable feedback over the pure ownership machine; callers never assemble ownership flags. */
class PressState {
    var focused by mutableStateOf(false); internal set
    var pressed by mutableStateOf(false); private set
    internal var longPressJob: Job? = null
    private val interaction = PressInteraction()
    internal fun begin(token: PressToken, repeat: Boolean, longEnabled: Boolean): PressAction =
        interaction.begin(token, repeat, longEnabled).also { pressed = interaction.pressed }
    internal fun end(token: PressToken, canceled: Boolean): PressAction {
        if (interaction.owns(token)) longPressJob?.cancel()
        return interaction.end(token, canceled).also { pressed = interaction.pressed }
    }
    internal fun longPress(token: PressToken): PressAction = interaction.longPress(token).also { pressed = interaction.pressed }
    internal fun owns(token: PressToken) = interaction.owns(token)
    internal fun cancel() { longPressJob?.cancel(); interaction.cancel(); pressed = false }
}

fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }
fun Modifier.dismissOnTap(onDismiss: () -> Unit): Modifier = pointerInput(onDismiss) { detectTapGestures { onDismiss() } }

/**
 * One input boundary for buttons and cards. Short/long/menu activation all use the same press
 * identity. The default transform can be disabled with focusedScale=1 for custom card rendering.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    focusedScale: Float = 1.06f,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onLongClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    interactionState: PressState? = null,
    content: @Composable BoxScope.(PressState) -> Unit,
) {
    val ownState = remember { PressState() }
    val state = interactionState ?: ownState
    val ownRequester = remember { FocusRequester() }
    val requester = focusRequester ?: ownRequester
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val menu by rememberUpdatedState(onMenu)
    val keyHandler by rememberUpdatedState(onKey)
    val window = LocalWindowInfo.current
    val inputActive = enabled && window.isWindowFocused
    val scope = rememberCoroutineScope()
    DisposableEffect(state, inputActive) {
        if (!inputActive) state.cancel()
        onDispose { state.cancel() }
    }
    fun activate(action: PressAction, menuKey: Boolean = false) {
        when (action) {
            PressAction.Activate -> if (menuKey) menu?.invoke() else click()
            PressAction.LongActivate -> { state.longPressJob?.cancel(); longClick?.invoke() }
            PressAction.None -> Unit
        }
    }
    val reduced = LocalReducedMotion.current
    val instant = OpenTvSettings.instantNavigation
    val scale = if (instant || reduced || focusedScale == 1f) rememberUpdatedState(1f) else animateFloatAsState(
        when { state.pressed -> focusedScale - 0.05f; state.focused -> focusedScale; else -> 1f },
        if (state.pressed) tween(100, easing = Motion.EaseOut) else spring(Motion.FocusDamping, Motion.FocusStiffness),
        label = "pressable-scale",
    )
    val transform = if (focusedScale == 1f || instant || reduced) Modifier else
        Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
    Box(modifier.then(transform).focusRequester(requester)
        .onFocusChanged {
            state.focused = it.isFocused
            if (!it.isFocused) state.cancel()
        }
        .pointerInput(state, inputActive, onLongClick != null) {
            if (!inputActive) return@pointerInput
            detectTapGestures(
                onPress = {
                    state.begin(PressToken.Pointer, repeat = false, longEnabled = false)
                    var released = false
                    try { released = tryAwaitRelease() } finally {
                        val action = state.end(PressToken.Pointer, !released || !window.isWindowFocused)
                        if (action == PressAction.Activate) { requester.requestFocus(); activate(action) }
                    }
                },
                onLongPress = if (onLongClick != null) ({
                    val action = state.longPress(PressToken.Pointer)
                    if (action == PressAction.LongActivate) { requester.requestFocus(); activate(action) }
                }) else null,
            )
        }
        .onKeyEvent { event ->
            if (!enabled || !window.isWindowFocused) { state.cancel(); return@onKeyEvent false }
            if (keyHandler?.invoke(event) == true) return@onKeyEvent true
            val menuKey = event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MENU && menu != null
            if (!menuKey && !event.isSelect()) return@onKeyEvent false
            val token = PressToken.key(event.nativeKeyEvent.keyCode, event.nativeKeyEvent.downTime)
            when (event.type) {
                KeyEventType.KeyDown -> {
                    val wasOwned = state.owns(token)
                    val action = state.begin(token, event.nativeKeyEvent.repeatCount > 0, !menuKey && longClick != null)
                    activate(action, menuKey)
                    if (!wasOwned && state.owns(token) && !menuKey && longClick != null) {
                        state.longPressJob?.cancel()
                        state.longPressJob = scope.launch { delay(550); activate(state.longPress(token)) }
                    }
                }
                KeyEventType.KeyUp -> activate(state.end(token, event.nativeKeyEvent.isCanceled), menuKey)
            }
            true
        }.focusable(enabled), content = { content(state) })
}
