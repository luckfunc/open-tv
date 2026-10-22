package app.opentv.launcher.ui.wallpaper

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.opentv.launcher.R
import app.opentv.launcher.ui.components.Pressable
import app.opentv.launcher.ui.components.consumeTaps
import app.opentv.launcher.ui.components.dismissOnTap
import app.opentv.launcher.ui.focus.FocusCoordinator
import app.opentv.launcher.ui.focus.focusTarget
import app.opentv.launcher.ui.theme.Glyph
import app.opentv.launcher.ui.theme.GlyphIcon
import app.opentv.launcher.ui.theme.Palette
import app.opentv.launcher.ui.theme.Type
import app.opentv.launcher.wallpaper.PhoneServer
import app.opentv.launcher.wallpaper.PhoneSession
import app.opentv.launcher.wallpaper.Wallpaper
import app.opentv.launcher.wallpaper.WallpaperKind
import app.opentv.launcher.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsPanel(visible: Boolean, onDismiss: () -> Unit, onSetup: () -> Unit = {}) {
    val context = LocalContext.current
    val session = remember(context.applicationContext) { PhoneSession.get(context) }
    val phoneUrl = rememberPhoneUrl(session, visible)
    val renderedQr by produceState<Pair<String, ImageBitmap>?>(null, phoneUrl) {
        value = phoneUrl?.let { url ->
            url to withContext(Dispatchers.Default) { PhoneServer.qr(url).asImageBitmap() }
        }
    }
    val qr = renderedQr?.takeIf { it.first == phoneUrl }?.second
    SettingsContent(visible, onDismiss, phoneUrl, qr, onSetup)
}

@Composable
internal fun rememberPhoneUrl(session: PhoneSession, visible: Boolean): String? {
    DisposableEffect(session, visible) {
        val lease = if (visible) session.acquire() else null
        onDispose { lease?.close() }
    }
    val url by session.url.collectAsState()
    return if (visible) url else null
}

@Composable
internal fun SettingsContent(
    visible: Boolean,
    onDismiss: () -> Unit,
    phoneUrl: String? = null,
    qr: ImageBitmap? = null,
    onSetup: () -> Unit = {},
) {
    var navigation by rememberSaveable(stateSaver = SettingsNavigationSaver) { mutableStateOf(SettingsNavigation()) }
    val owner = remember { FocusCoordinator<SettingsFocus, SettingsFocusScope> { it.scope } }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val sidebarScroll = rememberScrollState()
    val detailScrolls = SettingsCategory.entries.associateWith { category -> key(category) { rememberScrollState() } }
    val choiceScrolls = SettingsSetting.entries.associateWith { setting -> key(setting) { rememberScrollState() } }
    val wallpaperScrolls = WallpaperGroup.entries.associateWith { group -> key(group) { rememberLazyListState() } }
    val rows = wallpaperRows()
    val inventory = SettingsInventory(
        SettingsCategory.entries,
        rows.flatMap { row -> row.items.map { SettingsFocus.Wallpaper(row.group, it.id) } },
        SettingsSetting.entries.filter { it != SettingsSetting.Filter || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
            .associateWith(SettingsChoices::options),
    )
    val state = navigation.reconcile(inventory)
    val active = visible && windowFocused
    LaunchedEffect(state, active) {
        navigation = state
        owner.request(if (active) state.focus else null)
    }
    fun navigate(next: SettingsNavigation) {
        navigation = next.reconcile(inventory)
        owner.request(if (active) navigation.focus else null)
    }
    val observed: (SettingsFocus) -> Unit = { navigation = navigation.observed(it) }
    fun back() {
        val parent = navigation.back()
        if (parent == null) onDismiss() else navigate(parent)
    }
    val wallpaperTarget = state.focus as? SettingsFocus.Wallpaper
    LaunchedEffect(wallpaperTarget, rows, visible) {
        if (!visible || wallpaperTarget == null) return@LaunchedEffect
        val row = rows.firstOrNull { it.group == wallpaperTarget.group } ?: return@LaunchedEffect
        val index = row.items.indexOfFirst { it.id == wallpaperTarget.id }
        val scroll = wallpaperScrolls.getValue(row.group)
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.scrollToItem(index)
    }
    BackHandler(enabled = visible) { back() }
    if (!visible) return

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Palette.Background).dismissOnTap(onDismiss))
        Row(Modifier.align(Alignment.Center).fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp).consumeTaps()) {
            Column(Modifier.testTag("settings-sidebar").width(238.dp).fillMaxHeight().padding(horizontal = 18.dp, vertical = 24.dp)) {
                BasicText(stringResource(R.string.redesign_settings), style = Type.LargeTitle, modifier = Modifier.padding(start = 12.dp, bottom = 20.dp))
                Column(Modifier.weight(1f).verticalScroll(sidebarScroll), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    inventory.categories.forEach { item ->
                        key(item) {
                            val enter = { navigate(navigation.enter(item, inventory)) }
                            SettingsRow(
                                label = stringResource(item.label), selected = state.route.category == item,
                                modifier = Modifier.focusTarget(owner, SettingsFocus.Category(item), observed)
                                    .focusProperties { left = FocusRequester.Cancel },
                                onRight = enter, onClick = enter,
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.Border))
            Column(Modifier.testTag("settings-detail").weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 24.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_DPAD_LEFT) false
                    else when (val route = navigation.route) {
                        is SettingsRoute.Sidebar -> false
                        is SettingsRoute.Choices -> { back(); true }
                        is SettingsRoute.Detail -> {
                            val wallpaper = route.focus as? SettingsFocus.Wallpaper
                            val atBoundary = wallpaper == null || rows.firstOrNull { it.group == wallpaper.group }?.items?.firstOrNull()?.id == wallpaper.id
                            if (atBoundary) back()
                            atBoundary
                        }
                    }
                }) {
                val page = state.route as? SettingsRoute.Choices
                BasicText(stringResource(page?.let { SettingsChoices.title(it.option.setting) } ?: state.route.category.label),
                    style = Type.LargeTitle, modifier = Modifier.padding(bottom = 16.dp))
                if (page != null) {
                    val setting = page.option.setting
                    val options = inventory.options.getValue(setting)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(choiceScrolls.getValue(setting)).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsChoices.hint(setting)?.let { SettingsNote(stringResource(it)) }
                        options.forEachIndexed { index, option ->
                            key(option) {
                                SettingsRow(SettingsChoices.label(option), selected = option == SettingsChoices.current(setting),
                                    modifier = Modifier.focusTarget(owner, SettingsFocus.Option(option), observed)
                                        .focusProperties { right = FocusRequester.Cancel },
                                    onKey = { event ->
                                        val delta = when (event.nativeKeyEvent.keyCode) {
                                            NativeKeyEvent.KEYCODE_DPAD_UP -> -1
                                            NativeKeyEvent.KEYCODE_DPAD_DOWN -> 1
                                            else -> 0
                                        }
                                        if (delta == 0) false else {
                                            if (event.type == KeyEventType.KeyDown) options.getOrNull(index + delta)?.let {
                                                navigate(navigation.observed(SettingsFocus.Option(it)))
                                            }
                                            true
                                        }
                                    }, onClick = {
                                        navigation.select(option, inventory)?.let { next ->
                                            navigate(next)
                                            SettingsChoices.apply(option)
                                        }
                                    })
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(detailScrolls.getValue(state.route.category)).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (state.route.category) {
                            SettingsCategory.Wallpaper -> WallpaperRows(rows, wallpaperScrolls, owner, observed)
                            SettingsCategory.Launcher -> {
                                inventory.options.keys.filter { it.category == state.route.category }.forEach { setting ->
                                    key(setting) {
                                        val current = SettingsChoices.current(setting)
                                        SettingsRow(stringResource(SettingsChoices.title(setting)), SettingsChoices.label(current),
                                            modifier = Modifier.focusTarget(owner, SettingsFocus.Setting(setting), observed),
                                            onClick = { navigate(navigation.openChoices(SettingsChoices.current(setting), inventory)) })
                                        SettingsChoices.hint(setting)?.let { SettingsNote(stringResource(it)) }
                                    }
                                }
                                SettingsRow(stringResource(R.string.setup_title),
                                    modifier = Modifier.focusTarget(owner, SettingsFocus.Action(SettingsAction.Setup), observed),
                                    onClick = onSetup)
                            }
                            SettingsCategory.Phone -> {
                                SettingsRow(stringResource(R.string.redesign_categories),
                                    modifier = Modifier.focusTarget(owner, SettingsFocus.Action(SettingsAction.Categories), observed), onClick = { back() })
                                PhoneCard(phoneUrl, qr)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onRight: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, focusedScale = 1.015f,
        onKey = { event ->
            if (onKey?.invoke(event) == true) true
            else if (onRight != null && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_RIGHT) {
                onRight(); true
            } else false
        }) { state ->
        val foreground = if (state.focused) Palette.OnFocus else Palette.Label
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(when { state.focused -> Palette.Focus; selected -> Palette.FillStrong; else -> Palette.Fill })
            .heightIn(min = 46.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(label, style = Type.Body.copy(color = foreground), modifier = Modifier.weight(1f))
            if (value != null) BasicText(value, style = Type.Callout.copy(color = if (state.focused) foreground else Palette.SecondaryLabel), modifier = Modifier.widthIn(max = 190.dp))
            if (selected) GlyphIcon(Glyph.Check, foreground, 14.dp)
        }
    }
}

@Composable
private fun SettingsNote(text: String) {
    BasicText(text, style = Type.Callout, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
}

private data class WallpaperRow(val group: WallpaperGroup, val title: Int, val items: List<Wallpaper>)

@Composable
private fun wallpaperRows(): List<WallpaperRow> {
    val items = Wallpapers.items
    val daily = items.filter { it.kind == WallpaperKind.Daily }
    return buildList {
        if (daily.isNotEmpty()) add(WallpaperRow(WallpaperGroup.Daily, R.string.wp_daily,
            listOf(Wallpaper(Wallpapers.LATEST_DAILY, WallpaperKind.Daily, stringResource(R.string.wp_daily_latest))) + daily))
        items.filter { it.kind == WallpaperKind.Photo }.takeIf { it.isNotEmpty() }?.let { photos ->
            val rotate = if (photos.size > 1) listOf(Wallpaper(Wallpapers.ROTATE_PHOTOS, WallpaperKind.Photo, stringResource(R.string.wp_photos_rotate))) else emptyList()
            add(WallpaperRow(WallpaperGroup.Photos, R.string.wp_photos, rotate + photos))
        }
        add(WallpaperRow(WallpaperGroup.Builtin, R.string.wp_builtin, items.filter { it.kind == WallpaperKind.Builtin || it.kind == WallpaperKind.Auto }))
    }
}

@Composable
private fun WallpaperRows(
    rows: List<WallpaperRow>,
    scrolls: Map<WallpaperGroup, LazyListState>,
    owner: FocusCoordinator<SettingsFocus, SettingsFocusScope>,
    onFocused: (SettingsFocus) -> Unit,
) {
    rows.forEach { row ->
        key(row.group) {
            BasicText(stringResource(row.title), style = Type.Headline)
            LazyRow(state = scrolls.getValue(row.group), horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                items(row.items, key = { it.id }) { wp ->
                    WallpaperCard(wp, Modifier.focusTarget(owner, SettingsFocus.Wallpaper(row.group, wp.id), onFocused))
                }
            }
        }
    }
}

@Composable
private fun WallpaperCard(wp: Wallpaper, modifier: Modifier) {
    val selected = Wallpapers.selectedId == wp.id
    val thumb by produceState<ImageBitmap?>(null, wp.id, Wallpapers.items) {
        value = withContext(Dispatchers.Default) { Wallpapers.thumbnail(wp.id)?.asImageBitmap() }
    }
    Pressable(onClick = { Wallpapers.select(wp.id) }, modifier = modifier, focusedScale = 1.04f) { state ->
        Column(Modifier.width(168.dp)) {
            Box(Modifier.size(168.dp, 94.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Fill)) {
                thumb?.let { image ->
                    Canvas(Modifier.fillMaxSize()) { drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                }
                Box(Modifier.fillMaxSize().border(if (state.focused || selected) 3.dp else 1.dp,
                    if (state.focused || selected) Palette.Focus else Palette.Border, RoundedCornerShape(12.dp)))
                if (selected) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape).background(Palette.Focus), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Check, Palette.OnFocus, 13.dp)
                }
            }
            BasicText(wp.title, style = Type.Callout.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PhoneCard(url: String?, qr: ImageBitmap?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(176.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp), contentAlignment = Alignment.Center) {
            if (qr != null) Image(qr, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            else GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 40.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(stringResource(R.string.wp_phone_title), style = Type.Title)
            BasicText(stringResource(if (url != null) R.string.wp_phone_body else R.string.wp_phone_offline), style = Type.Body.copy(color = Palette.SecondaryLabel))
            if (url != null) BasicText(url.removePrefix("http://").substringBefore('/'), style = Type.Callout)
        }
    }
}
