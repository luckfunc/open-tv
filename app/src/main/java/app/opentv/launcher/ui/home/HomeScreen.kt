package app.opentv.launcher.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.zIndex
import app.opentv.launcher.ui.components.GlassSurface
import app.opentv.launcher.ui.components.BackdropSceneHost
import app.opentv.launcher.ui.components.isSelect
import app.opentv.launcher.ui.input.PressAction
import app.opentv.launcher.ui.input.PressInteraction
import app.opentv.launcher.ui.input.PressToken
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import app.opentv.launcher.setup.SetupScreen
import app.opentv.launcher.setup.SetupState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import app.opentv.launcher.ui.focus.FocusCoordinator
import app.opentv.launcher.ui.focus.focusTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.opentv.launcher.CcPage
import app.opentv.launcher.HomeViewModel
import app.opentv.launcher.Overlay
import app.opentv.launcher.R
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.apps.AppEntry
import app.opentv.launcher.ui.control.ControlCenter
import app.opentv.launcher.ui.wallpaper.SettingsPanel
import app.opentv.launcher.wallpaper.Wallpapers
import app.opentv.launcher.ui.theme.LocalReducedMotion
import app.opentv.launcher.ui.theme.Motion
import app.opentv.launcher.ui.theme.Palette
import app.opentv.launcher.ui.theme.Type
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpen: (Intent) -> Unit,
) {
    val apps = vm.apps
    val setupState by vm.setup.state.collectAsState()
    val setupVisible = setupState != SetupState.Closed
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val instant = OpenTvSettings.instantNavigation
    val density = LocalDensity.current
    val now by rememberNow()
    val scope = rememberCoroutineScope()
    val appKeys = apps.map { it.key }
    var focusMemory by rememberSaveable { mutableStateOf<HomeFocusMemory>(HomeFocusMemory.AwaitingApps) }
    val memory = if (vm.loaded) focusMemory.reconcile(appKeys) else focusMemory
    val owner = remember { FocusCoordinator<HomeFocusTarget, Unit> { Unit } }
    val focusedKey = memory.app?.key
    val pillFocused = memory is HomeFocusMemory.Status
    var resetGeneration by remember { mutableStateOf(0) }
    val focusedIndex = apps.indexOfFirst { it.key == (moving ?: focusedKey) }
    val movePress = remember(moving) { PressInteraction() }
    val showHomeContent = overlay !is Overlay.ControlCenter && overlay != Overlay.Wallpaper
    val focusedRow = if (pillFocused || focusedIndex < 0) 0 else focusedIndex / COLUMNS
    val gridEnabled = overlay == Overlay.None && !setupVisible
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val homeActive = gridEnabled && windowFocused && vm.loaded
    val scroll = remember { Animatable(0f) }
    var dragged by remember { mutableStateOf(false) }
    val movingNow by rememberUpdatedState(moving)

    fun navigate(next: HomeFocusMemory) {
        focusMemory = next.reconcile(appKeys)
        owner.request(if (homeActive) focusMemory.target else null)
    }
    fun focusApp(index: Int) {
        val resolved = index.coerceIn(0, (apps.size - 1).coerceAtLeast(0))
        navigate(apps.getOrNull(resolved)?.let { HomeFocusMemory.Tile(it.key, resolved) } ?: HomeFocusMemory.Status(null))
    }
    val observed: (HomeFocusTarget) -> Unit = { focusMemory = focusMemory.observed(it, appKeys) }
    LaunchedEffect(appKeys, memory, moving, homeActive, vm.homeResetPending) {
        val reset = homeActive && vm.homeResetPending
        val resolved = if (reset) HomeFocusMemory.first(appKeys) else memory
        focusMemory = resolved
        val destination = moving?.takeIf { it in appKeys }?.let { HomeFocusTarget.App(it) } ?: resolved.target
        owner.request(if (homeActive) destination else null)
        if (!homeActive) movePress.cancel()
        if (reset) {
            dragged = false
            resetGeneration++
            vm.homeResetPending = false
            if (instant) scroll.snapTo(0f)
        }
    }

    BackHandler(enabled = moving != null) { vm.endMove(commit = false) }
    BackHandler(enabled = overlay == Overlay.None && moving == null && !setupVisible) {
        // Home is the final Back boundary. Touch scrolling also counts as being inside the grid.
        val scrolled = dragged && scroll.value > 1f
        dragged = false
        if (focusedRow > 0 || scrolled) focusApp(focusedIndex.coerceAtLeast(0) % COLUMNS)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // The remote takes the grid back from the finger: scroll follows focus again.
                // Back leaves it to BackHandler, which needs to know the grid was dragged
                // (gesture navigation sends Back as a key too).
                if (setupVisible) return@onPreviewKeyEvent false
                if (e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_BACK) dragged = false
                // Moving takes the remote here rather than on the moving tile: the tile does not
                // always get focus back when the menu closes, and focus then landed on the first
                // tile, which moved focus instead of the app (#58).
                if (moving != null) {
                    val code = e.nativeKeyEvent.keyCode
                    val confirm = e.isSelect()
                    if (confirm || code == NativeKeyEvent.KEYCODE_BACK) {
                        val token = PressToken.key(code, e.nativeKeyEvent.downTime)
                        if (e.type == KeyEventType.KeyDown) movePress.begin(token, e.nativeKeyEvent.repeatCount > 0)
                        else if (e.type == KeyEventType.KeyUp && movePress.end(token, e.nativeKeyEvent.isCanceled) == PressAction.Activate) {
                            vm.endMove(commit = confirm)
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent code != NativeKeyEvent.KEYCODE_HOME
                    val step = when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> GridDirection.Left
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> GridDirection.Right
                        NativeKeyEvent.KEYCODE_DPAD_UP -> GridDirection.Up
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> GridDirection.Down
                        else -> return@onPreviewKeyEvent false
                    }
                    NavigationPolicy.neighbor(focusedIndex, apps.size, step)?.let { vm.moveBy(it - focusedIndex) }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_MENU -> {
                        // On an app tile, Menu opens that app's menu (AppTile): remotes whose held
                        // OK never reads as held had no other way to it (issue #28). Control
                        // Center stays one press up, on the status pill, where Menu still opens it.
                        if (overlay == Overlay.None && !pillFocused && focusedIndex >= 0) return@onPreviewKeyEvent false
                        vm.overlay = if (overlay is Overlay.ControlCenter) Overlay.None else Overlay.ControlCenter()
                        true
                    }
                    else -> false
                }
            },
    ) {
        val metrics = remember(maxWidth, maxHeight, OpenTvSettings.oneRowHome) {
            GridMetrics(maxWidth, maxHeight, OpenTvSettings.oneRowHome)
        }
        val rows = (apps.size + COLUMNS - 1) / COLUMNS
        val collapsePx = with(density) { (metrics.topRowY - metrics.restY).toPx() }

        val target = with(density) { metrics.scrollFor(focusedRow, rows).toPx() }
        val maxScroll = with(density) { metrics.scrollFor(rows - 1, rows).toPx() }.coerceAtLeast(0f)
        LaunchedEffect(maxScroll) { scroll.updateBounds(0f, maxScroll) }
        // Cards and the shared ring inherit exactly the same content-space scroll transform.
        val visualScroll = { scroll.value }
        val decay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(target, dragged, instant, reduced, resetGeneration) {
            if (dragged) return@LaunchedEffect
            if (reduced || (instant && vm.homeResetPending)) scroll.snapTo(target)
            // The same Animatable retains velocity on rapid retargets; no input waits for it.
            else scroll.animateTo(target, spring(
                dampingRatio = Motion.FocusDamping, stiffness = Motion.ScrollStiffness,
            ))
        }
        val ambientApp = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()

        BackdropSceneHost(Wallpapers.prepared, ambientApp?.art?.backdrop, visualScroll, collapsePx) {
        val dragState = rememberDraggableState { delta ->
            dragged = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.snapTo(scroll.value - delta) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    enabled = gridEnabled && rows > 1 && moving == null,
                    onDragStopped = { velocity ->
                        // Touch keeps direct dragging and fling semantics in either mode.
                        if (!reduced) scroll.animateDecay(-velocity, decay)
                    },
                )
                // While moving an app, a tap on empty space ends the move, as OK does.
                .pointerInput(Unit) { detectTapGestures { if (movingNow != null) vm.endMove() } },
        ) {
            HomeBackground()

            Box(Modifier.fillMaxSize().offset { IntOffset(0, -visualScroll().roundToInt()) }
                .graphicsLayer { alpha = if (showHomeContent) 1f else 0f }) {
                // A bounded shelf surface, not another full-screen glass layer. It yields to the
                // grid as the first row scrolls away and leaves family artwork unobstructed.
                GlassSurface(RoundedCornerShape(34.dp), Modifier
                    .offset(x = metrics.dockX, y = metrics.dockTop)
                    .size(metrics.dockWidth, metrics.dockHeight)
                    .graphicsLayer { alpha = (1f - visualScroll() / collapsePx).coerceIn(0f, 1f) }) {}

                apps.forEachIndexed { index, app ->
                    key(app.key) {
                        val (x, y) = metrics.slot(index)
                        val slotPx = with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
                        val slot = if (reduced) rememberUpdatedState(slotPx) else animateIntOffsetAsState(
                            slotPx,
                            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                            label = "slot",
                        )
                        AppTile(
                            app = app,
                            width = metrics.tileW,
                            height = metrics.tileH,
                            enabled = gridEnabled,
                            moving = moving == app.key,
                            showLabel = index >= COLUMNS || OpenTvSettings.showAllNames,
                            editing = moving != null,
                            onNavigate = { direction ->
                                val step = when (direction) {
                                    FocusDirection.Left -> GridDirection.Left
                                    FocusDirection.Right -> GridDirection.Right
                                    FocusDirection.Up -> GridDirection.Up
                                    else -> GridDirection.Down
                                }
                                if (step == GridDirection.Up && index < COLUMNS) {
                                    navigate(HomeFocusMemory.Status(memory.app))
                                } else NavigationPolicy.neighbor(index, apps.size, step)?.let(::focusApp)
                                true
                            },
                            onClick = { bounds ->
                                val mover = moving
                                if (mover == null) onLaunch(app, bounds)
                                // While moving, tapping a tile puts the app there; tapping the app
                                // itself is done.
                                else if (mover == app.key) vm.endMove()
                                else {
                                    vm.moveBy(index - apps.indexOfFirst { it.key == mover })
                                    focusApp(apps.indexOfFirst { it.key == mover })
                                }
                            },
                            onLongPress = { if (moving == null) vm.startMove(app.key) },
                            onMenu = { bounds ->
                                if (moving == null) vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                            },
                            modifier = Modifier.focusTarget(owner, HomeFocusTarget.App(app.key), observed).offset { slot.value }
                                .zIndex(if (moving == app.key || focusedKey == app.key) 1f else 0f)
                                .graphicsLayer {
                                    val top = slot.value.y - visualScroll()
                                    val edgeFade = if (visualScroll() < 1f) 1f else
                                        ((top - 44.dp.toPx()) / 20.dp.toPx()).coerceIn(0f, 1f)
                                    alpha = if (metrics.hideNextRow && index >= COLUMNS && visualScroll() < 1f) 0f else edgeFade
                                },
                        )
                    }
                }
            }
        }

        StatusPill(
            now = now,
            enabled = gridEnabled && moving == null,
            onDown = { focusApp(focusedIndex.coerceAtLeast(0) % COLUMNS) },
            onClick = {
                focusMemory = HomeFocusMemory.Status(memory.app)
                vm.overlay = Overlay.ControlCenter()
            },
            modifier = Modifier.focusTarget(owner, HomeFocusTarget.Status, observed)
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 28.dp)
                .graphicsLayer { alpha = if (overlay is Overlay.ControlCenter) 0f else 1f },
        )

        AnimatedVisibility(
            visible = moving != null,
            enter = if (reduced) fadeIn(tween(0)) else fadeIn(tween(200)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = if (reduced) fadeOut(tween(0)) else fadeOut(tween(150)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            // Screens that can be touched also say how to move with a finger.
            val touch = LocalConfiguration.current.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
            BasicText(
                stringResource(if (touch) R.string.move_hint_touch else R.string.move_hint,
                    apps.firstOrNull { it.key == moving }?.label.orEmpty()),
                style = Type.Body,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Glass)
                    .widthIn(max = 840.dp)
                    .heightIn(min = 42.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            now = now,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = Overlay.None },
        )

        val menu = overlay as? Overlay.AppMenu
        AppMenu(
            app = menu?.let { m -> apps.firstOrNull { it.key == m.key } },
            anchor = menu?.anchor,
            onMove = { vm.startMove(it) },
            onHide = { key ->
                vm.overlay = Overlay.None
                vm.hide(key)
            },
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onUnpin = { key ->
                vm.overlay = Overlay.None
                apps.firstOrNull { it.key == key }?.source?.let { vm.setSourcePinned(it, false) }
            },
            onResetIcon = { key ->
                vm.overlay = Overlay.None
                vm.resetIcon(key)
            },
            onDismiss = { vm.overlay = Overlay.None },
        )

        HiddenAppsSheet(
            visible = overlay is Overlay.HiddenApps,
            apps = vm.hiddenApps,
            onUnhide = { key ->
                vm.unhide(key)
                if (vm.hiddenApps.isEmpty()) vm.overlay = Overlay.None
            },
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
        )

        SettingsPanel(
            visible = overlay is Overlay.Wallpaper && !setupVisible,
            onSetup = vm::openSetup,
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
        )

        SetupScreen(vm.setup, onDismiss = vm::dismissSetup, onOpen = onOpen)
        }
    }
}
