package app.opentv.launcher.ui.control

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import android.widget.Toast
import app.opentv.launcher.guard.HomeGuardService
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.launcher.BuildConfig
import app.opentv.launcher.CcPage
import app.opentv.launcher.HomeViewModel
import app.opentv.launcher.Overlay
import app.opentv.launcher.R
import app.opentv.launcher.system.SystemProbe
import app.opentv.launcher.ui.components.Pressable
import app.opentv.launcher.ui.components.GlassSurface
import app.opentv.launcher.ui.components.consumeTaps
import app.opentv.launcher.ui.components.dismissOnTap
import app.opentv.launcher.ui.home.timeText
import app.opentv.launcher.ui.theme.Glyph
import app.opentv.launcher.ui.theme.GlyphIcon
import app.opentv.launcher.ui.theme.LocalReducedMotion
import app.opentv.launcher.ui.theme.Motion
import app.opentv.launcher.ui.theme.Palette
import app.opentv.launcher.ui.theme.Type
import java.time.LocalDateTime

@Composable
fun ControlCenter(
    visible: Boolean,
    page: CcPage,
    vm: HomeViewModel,
    now: LocalDateTime,
    onOpen: (android.content.Intent) -> Unit,
    onClose: () -> Unit,
) = CcFocusSurface(visible, page, CcFocusInventory) {
    val reduced = LocalReducedMotion.current
    val scrolls = CcPage.entries.associateWith { destination ->
        androidx.compose.runtime.key(destination) { rememberScrollState() }
    }
    fun back() {
        val parent = page.parentPage()
        if (parent == null) onClose() else vm.overlay = Overlay.ControlCenter(parent)
    }
    BackHandler(enabled = visible) { back() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (reduced) 0 else 220)),
        exit = fadeOut(tween(if (reduced) 0 else 160)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap { if (visible) onClose() })
    }

    Box(Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp, bottom = 24.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = visible,
            enter = if (reduced) fadeIn(tween(0)) else fadeIn(tween(200)) +
                slideInHorizontally(tween(260, easing = Motion.Drawer)) { it / 3 },
            exit = if (reduced) fadeOut(tween(0)) else fadeOut(tween(160)) +
                slideOutHorizontally(tween(200, easing = Motion.EaseOut)) { it / 3 },
        ) {
            Box(
                Modifier.width(236.dp).heightIn(max = 492.dp).consumeTaps().padding(6.dp),
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState.depth > initialState.depth
                        if (reduced) fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                        else (fadeIn(tween(180)) + slideInHorizontally(tween(240, easing = Motion.EaseOut)) { if (forward) it / 5 else -it / 5 })
                            .togetherWith(fadeOut(tween(100)) + slideOutHorizontally(tween(180, easing = Motion.EaseOut)) { if (forward) -it / 5 else it / 5 })
                    },
                    label = "cc-page",
                ) { destination ->
                    CompositionLocalProvider(LocalCcPage provides destination) {
                        Column {
                            MainHeader(now)
                            if (destination != CcPage.Main) {
                                val title = stringResource(R.string.cc_about)
                                ControlGroup { ControlRow(Glyph.ChevronLeft, title, CcControl.Back, onClick = { back() }) }
                                Spacer(Modifier.height(12.dp))
                            }
                            Column(Modifier.weight(1f, fill = false).verticalScroll(scrolls.getValue(destination)).padding(3.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                when (destination) {
                                    CcPage.Main -> MainPage(vm, onOpen)
                                    CcPage.About -> AboutPage()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainHeader(now: LocalDateTime) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(stringResource(R.string.cc_title), style = Type.Headline)
        BasicText(timeText(now), style = Type.Callout.copy(color = Palette.Label))
    }
}

@Composable
private fun MainPage(vm: HomeViewModel, onOpen: (android.content.Intent) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickButton(Glyph.Wifi, stringResource(R.string.cc_network), CcControl.Network, Modifier.weight(1f)) {
            onOpen(SystemProbe.networkSettingsIntent(context))
        }
        QuickButton(Glyph.Paintbrush, stringResource(R.string.cc_personalize), CcControl.Personalize, Modifier.weight(1f)) { vm.overlay = Overlay.Wallpaper }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        RoundControl(Glyph.Gear, stringResource(R.string.cc_settings), CcControl.Settings) {
            onOpen(SystemProbe.settingsIntent(context))
        }
        RoundControl(Glyph.AppsDrawer, stringResource(R.string.cc_hidden_apps), CcControl.HiddenApps) { vm.overlay = Overlay.HiddenApps }
        RoundControl(Glyph.Exit, stringResource(R.string.cc_system_home), CcControl.SystemHome) {
            if (HomeGuardService.exitToSystemHome(context)) vm.overlay = Overlay.None
            else Toast.makeText(context, R.string.unavailable, Toast.LENGTH_SHORT).show()
        }
        RoundControl(Glyph.Info, stringResource(R.string.cc_about), CcControl.About) { vm.overlay = Overlay.ControlCenter(CcPage.About) }
    }
}

@Composable
private fun AboutPage() {
    GlassSurface(RoundedCornerShape(22.dp), Modifier.fillMaxWidth()) {
        BasicText("OpenTV ${BuildConfig.VERSION_NAME.substringBefore('-')}", style = Type.Headline,
            modifier = Modifier.padding(16.dp))
    }
}

@Composable
internal fun ControlGroup(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(RoundedCornerShape(20.dp), Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun QuickButton(glyph: Glyph, label: String, control: CcControl, modifier: Modifier, onClick: () -> Unit) {
    val active = ccPageActive
    Pressable(onClick = { if (active) onClick() }, enabled = active,
        focusedScale = 1.04f, modifier = modifier.controlCenterFocus(control)) { state ->
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        GlassSurface(RoundedCornerShape(23.dp), focused = state.focused,
            modifier = Modifier.fillMaxWidth().height(51.dp)) {
            Row(Modifier.align(Alignment.Center).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                GlyphIcon(glyph, fg, 18.dp)
                BasicText(label, style = Type.Caption.copy(color = fg), maxLines = 2)
            }
        }
    }
}

@Composable
internal fun ControlRow(glyph: Glyph?, text: String, control: CcControl, trailing: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    val active = ccPageActive
    Pressable(onClick = { if (active) onClick() }, enabled = active,
        focusedScale = 1f, modifier = Modifier.fillMaxWidth().controlCenterFocus(control)) { state ->
        val bg = if (state.focused) Palette.Focus else Color.Transparent
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).heightIn(min = 43.dp).padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (glyph != null) GlyphIcon(glyph, fg, 18.dp)
            BasicText(text, style = Type.Body.copy(color = fg), modifier = Modifier.weight(1f))
            if (trailing != null) BasicText(trailing, style = Type.Caption.copy(color = if (state.focused) fg else Palette.SecondaryLabel),
                modifier = Modifier.widthIn(max = 96.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (selected) GlyphIcon(Glyph.Check, fg, 16.dp)
        }
    }
}

@Composable
private fun RoundControl(glyph: Glyph, label: String, control: CcControl, onClick: () -> Unit) {
    val active = ccPageActive
    Pressable(onClick = onClick, enabled = active, focusedScale = 1.10f,
        modifier = Modifier.width(50.dp).controlCenterFocus(control).semantics { contentDescription = label }) { state ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            GlassSurface(CircleShape, Modifier.size(46.dp), focused = state.focused) {
                GlyphIcon(glyph, if (state.focused) Palette.OnFocus else Palette.Label, 23.dp, Modifier.align(Alignment.Center))
            }
        }
    }
}
