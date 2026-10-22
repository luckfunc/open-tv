package app.opentv.launcher.ui.home

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import app.opentv.launcher.ui.focus.FocusCoordinator
import app.opentv.launcher.ui.focus.focusTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.opentv.launcher.R
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.system.SystemProbe
import app.opentv.launcher.apps.AppEntry
import app.opentv.launcher.apps.CustomIcons
import app.opentv.launcher.ui.components.Pressable
import app.opentv.launcher.ui.components.consumeTaps
import app.opentv.launcher.ui.components.dismissOnTap
import app.opentv.launcher.ui.theme.Glyph
import app.opentv.launcher.ui.theme.GlyphIcon
import app.opentv.launcher.ui.theme.LocalReducedMotion
import app.opentv.launcher.ui.theme.Motion
import app.opentv.launcher.ui.theme.Palette
import app.opentv.launcher.ui.theme.Type
import kotlin.math.roundToInt

/** Keeps the last non-null value, so an overlay still has its content while it animates out. */
@Composable
fun <T : Any> rememberLatest(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}

/** A menu action with a near-white focus plate. */
@Composable
fun MenuRow(
    glyph: Glyph,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    trailing: String? = null,
    enabled: Boolean = true,
) {
    val reduced = LocalReducedMotion.current
    Pressable(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        onKey = { !enabled },
        focusedScale = 1.02f,
        modifier = modifier.fillMaxWidth(),
    ) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent, if (reduced || OpenTvSettings.instantNavigation) snap() else tween(140, easing = Motion.EaseOut), label = "row-bg",
        )
        val fg = when {
            state.focused -> if (destructive) Color(0xFFD70015) else Palette.OnFocus
            destructive -> Palette.Red
            else -> Palette.Label
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .heightIn(min = 46.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphIcon(glyph, fg, 18.dp)
            BasicText(text, style = Type.Body.copy(color = fg), modifier = Modifier.weight(1f))
            if (trailing != null) {
                BasicText(trailing, style = Type.Callout.copy(color = if (state.focused) Palette.OnFocus.copy(alpha = 0.6f) else Palette.SecondaryLabel))
            }
        }
    }
}

/** Long-press menu, opening out of the tile it belongs to. */
@Composable
fun AppMenu(
    app: AppEntry?,
    anchor: Rect?,
    onMove: (String) -> Unit,
    onHide: (String) -> Unit,
    onOpen: (Intent) -> Unit,
    onUnpin: (String) -> Unit,
    onResetIcon: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = app != null
    val shownApp = rememberLatest(app) ?: return
    val shownAnchor = rememberLatest(anchor) ?: Rect.Zero
    val context = LocalContext.current
    val infoIntent = remember(context, shownApp, visible) {
        if (shownApp.source == null) SystemProbe.appInfoIntent(context, shownApp.packageName) else null
    }
    val uninstallIntent = remember(context, shownApp, visible) {
        if (shownApp.source == null && shownApp.removable) SystemProbe.uninstallIntent(context, shownApp.packageName) else null
    }
    val owner = remember { FocusCoordinator<AppMenuTarget, String> { it.appKey } }
    val reduced = LocalReducedMotion.current
    val active = visible && LocalWindowInfo.current.isWindowFocused
    var focusedAction by remember(shownApp.key, visible) { mutableStateOf(AppMenuAction.Move) }
    val actions = AppMenuAction.entries.filter { action ->
        when (action) {
            AppMenuAction.Move -> true
            AppMenuAction.Unpin -> shownApp.source != null
            AppMenuAction.Info -> infoIntent != null
            AppMenuAction.Uninstall -> uninstallIntent != null
            AppMenuAction.ResetIcon -> shownApp.source == null && CustomIcons.has(shownApp.key)
            else -> shownApp.source == null
        }
    }
    val action = focusedAction.takeIf { it in actions } ?: AppMenuAction.Move
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(active, shownApp.key, action) {
        focusedAction = action
        owner.request(if (active) AppMenuTarget(shownApp.key, action) else null)
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(if (visible) Modifier.dismissOnTap(onDismiss) else Modifier)
            .onPreviewKeyEvent { !visible },
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = if (reduced) fadeIn(tween(80)) else fadeIn(tween(160)) + scaleIn(tween(200, easing = Motion.EaseOut), initialScale = 0.96f, transformOrigin = TransformOrigin(0.2f, 0.5f)),
            exit = if (reduced) fadeOut(tween(80)) else fadeOut(tween(120)) + scaleOut(tween(120, easing = Motion.EaseOut), targetScale = 0.98f, transformOrigin = TransformOrigin(0.2f, 0.5f)),
            modifier = Modifier.layout { measurable, constraints ->
                val safe = 24.dp.roundToPx()
                val panel = measurable.measure(Constraints(
                    maxWidth = (constraints.maxWidth - safe * 2).coerceAtLeast(0),
                    maxHeight = (constraints.maxHeight - safe * 2).coerceAtLeast(0),
                ))
                val placement = placeAnchoredMenu(
                    constraints.maxWidth, constraints.maxHeight, panel.width, panel.height,
                    shownAnchor.left.roundToInt(), shownAnchor.top.roundToInt(), shownAnchor.bottom.roundToInt(),
                    safe, 14.dp.roundToPx(),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { panel.place(placement.x, placement.y) }
            },
        ) {
            Column(
                Modifier
                    .width(250.dp)
                    .consumeTaps()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.Surface)
                    .border(1.dp, Palette.Border, RoundedCornerShape(20.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                BasicText(
                    shownApp.label, style = Type.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
                )
                actions.forEach { item ->
                    val (glyph, label) = when (item) {
                        AppMenuAction.Move -> Glyph.Move to R.string.menu_move
                        AppMenuAction.Unpin -> Glyph.EyeSlash to R.string.menu_unpin_source
                        AppMenuAction.Hide -> Glyph.EyeSlash to R.string.menu_hide
                        AppMenuAction.ResetIcon -> Glyph.Photo to R.string.menu_reset_icon
                        AppMenuAction.Info -> Glyph.Info to R.string.menu_info
                        AppMenuAction.Uninstall -> Glyph.Trash to R.string.menu_uninstall
                    }
                    MenuRow(glyph, stringResource(label), onClick = {
                        when (item) {
                            AppMenuAction.Move -> onMove(shownApp.key)
                            AppMenuAction.Unpin -> onUnpin(shownApp.key)
                            AppMenuAction.Hide -> onHide(shownApp.key)
                            AppMenuAction.ResetIcon -> onResetIcon(shownApp.key)
                            AppMenuAction.Info -> infoIntent?.let(onOpen)
                            AppMenuAction.Uninstall -> uninstallIntent?.let(onOpen)
                        }
                    }, modifier = Modifier.focusTarget(owner, AppMenuTarget(shownApp.key, item)) { focusedAction = it.action },
                        destructive = item == AppMenuAction.Uninstall, enabled = visible)
                }
            }
        }
    }
}

/** A small, flat copy of a tile for lists. */
@Composable
fun MiniTile(app: AppEntry, focused: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) Palette.Focus else Palette.Hairline, RoundedCornerShape(10.dp)),
    ) {
        drawImage(app.art.back, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
        app.art.fore?.let {
            val at = app.art.foreAt
            drawImage(
                it,
                dstOffset = IntOffset((at.left * size.width).roundToInt(), (at.top * size.height).roundToInt()),
                dstSize = IntSize((at.width * size.width).roundToInt(), (at.height * size.height).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** Hidden apps, to put back on Home. Reached from Control Center. */
@Composable
fun HiddenAppsSheet(
    visible: Boolean,
    apps: List<AppEntry>,
    onUnhide: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val owner = remember { FocusCoordinator<HiddenAppsTarget, Unit> { Unit } }
    var focus by remember { mutableStateOf<HiddenAppsFocus>(HiddenAppsFocus.Initial) }
    val appKeys = apps.map { it.key }
    val resolved = if (visible) focus.reconcile(appKeys) else focus
    val reduced = LocalReducedMotion.current
    val active = visible && LocalWindowInfo.current.isWindowFocused
    val observed: (HiddenAppsTarget) -> Unit = { focus = focus.observed(it, appKeys) }
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(active, resolved) {
        focus = resolved
        owner.request(if (active) resolved.target else null)
    }
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(tween(80)) else fadeIn(tween(180)) + scaleIn(tween(240, easing = Motion.Drawer), initialScale = 0.97f),
        exit = if (reduced) fadeOut(tween(80)) else fadeOut(tween(140)) + scaleOut(tween(140, easing = Motion.EaseOut), targetScale = 0.98f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier.fillMaxSize()
                .then(if (visible) Modifier.dismissOnTap(onDismiss) else Modifier.consumeTaps())
                .onPreviewKeyEvent { !visible }
                .background(Palette.Scrim),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(688.dp)
                    .consumeTaps()
                    .heightIn(max = 480.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Palette.Surface)
                    .border(1.dp, Palette.Border, RoundedCornerShape(28.dp))
                    .padding(28.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(stringResource(R.string.hidden_title), style = Type.LargeTitle, modifier = Modifier.weight(1f))
                    MenuRow(
                        Glyph.ChevronLeft, stringResource(R.string.cc_back), onDismiss,
                        modifier = Modifier.width(104.dp).focusTarget(owner, HiddenAppsTarget.Back, observed), enabled = visible,
                    )
                }
                BasicText(
                    stringResource(if (apps.isEmpty()) R.string.hidden_empty else R.string.hidden_hint),
                    style = Type.Callout, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    apps.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                            row.forEach { app ->
                                key(app.key) {
                                    Pressable(
                                        onClick = { if (visible) onUnhide(app.key) },
                                        enabled = visible,
                                        onKey = { !visible },
                                        focusedScale = 1.04f,
                                        modifier = Modifier.focusTarget(owner, HiddenAppsTarget.App(app.key), observed),
                                    ) { state ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            MiniTile(app, state.focused, Modifier.size(132.dp, 74.dp))
                                            Spacer(Modifier.height(8.dp))
                                            BasicText(
                                                app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
                                                modifier = Modifier.width(132.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
