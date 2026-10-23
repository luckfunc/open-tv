package app.opentv.launcher.setup

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.opentv.launcher.R
import app.opentv.launcher.ui.components.Pressable
import app.opentv.launcher.ui.focus.FocusCoordinator
import app.opentv.launcher.ui.focus.focusTarget
import app.opentv.launcher.ui.theme.Glyph
import app.opentv.launcher.ui.theme.GlyphIcon
import app.opentv.launcher.ui.theme.Palette
import app.opentv.launcher.ui.theme.Type
import kotlinx.coroutines.launch

@Composable
internal fun SetupScreen(controller: SetupController, onDismiss: () -> Unit, onOpen: (Intent) -> Unit) {
    val state by controller.state.collectAsState()
    if (state == SetupState.Closed) return
    var showDiagnostics by remember { mutableStateOf(false) }
    if (showDiagnostics) {
        SetupDiagnosticScreen(SetupDiagnosticScreenProps(onBack = { showDiagnostics = false }))
        return
    }
    SetupContent(state, onCheck = controller::check, onConfirm = controller::confirm,
        onCancel = controller::cancel, onDismiss = onDismiss, onOpen = onOpen,
        onDiagnostics = { showDiagnostics = true })
}

@Composable
internal fun SetupContent(
    state: SetupState,
    onCheck: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (Intent) -> Unit,
    onDiagnostics: (() -> Unit)? = null,
) {
    if (state == SetupState.Closed) return
    val context = LocalContext.current
    val settings = remember(context) { setupDebuggingIntent(context) }
    val owner = remember { FocusCoordinator<String, Unit> { Unit } }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val page = when (state) {
        is SetupState.Intro -> "intro"
        is SetupState.Checking -> "checking"
        is SetupState.Review -> "review"
        is SetupState.Applying -> "applying"
        is SetupState.Result -> "result"
        is SetupState.Blocked -> "blocked"
        SetupState.Closed -> "closed"
    }
    val report = (state as? SetupState.Result)?.report
    val actual = report?.snapshot
    val core = SetupOperation.entries.filterNot { it.optional }.toSet() + actual?.optionalOperations.orEmpty()
    val verified = report != null && actual != null && actual.configured.containsAll(core) && actual.guardBound && actual.listenerBound && !report.cancelled &&
        report.outcomes.values.none { it == SetupOutcome.Failed || it == SetupOutcome.Uncertain }
    val busy = state is SetupState.Checking || state is SetupState.Applying
    BackHandler { if (busy) onCancel() else onDismiss() }
    val stage = when {
        verified -> 2
        state is SetupState.Review || state is SetupState.Applying || state is SetupState.Result -> 1
        else -> 0
    }
    val primary = when {
        verified -> "done"
        busy -> "cancel"
        state is SetupState.Review -> "apply"
        else -> "check"
    }
    LaunchedEffect(page, verified, windowFocused) { owner.request(if (windowFocused) "$page-$primary" else null) }
    val scroll = key(page) { rememberScrollState() }
    val scope = rememberCoroutineScope()
    key(page) {
    Box(Modifier.fillMaxSize().testTag("setup-$page"), contentAlignment = Alignment.Center) {
        Row(Modifier.width(700.dp).height(400.dp).heightIn(max = 476.dp).testTag("setup-card")
            .clip(RoundedCornerShape(24.dp)).background(Palette.Glass)
            .border(1.dp, Palette.Border, RoundedCornerShape(24.dp)).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                BasicText(stringResource(R.string.setup_wordmark), style = Type.Headline)
                listOf(R.string.setup_step_connect, R.string.setup_step_enable, R.string.setup_step_finish).forEachIndexed { index, label ->
                    val completed = verified || index < stage
                    val color = if (completed || index == stage) Palette.Label else Palette.TertiaryLabel
                    Row(Modifier.fillMaxWidth().testTag("setup-step-$index")
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (index == stage && !verified) Palette.Fill else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (completed) GlyphIcon(Glyph.Check, color, 16.dp)
                        else Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
                        }
                        BasicText(stringResource(label), style = Type.Body.copy(color = color))
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Hairline))
            Column(Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp, if (verified) Alignment.CenterVertically else Alignment.Top),
                horizontalAlignment = if (verified) Alignment.CenterHorizontally else Alignment.Start) {
            if (verified) GlyphIcon(Glyph.Check, Palette.Label, 48.dp)
            BasicText(stringResource(when (stage) {
                2 -> R.string.setup_complete_title
                1 -> R.string.setup_step_enable
                else -> R.string.setup_step_connect
            }), style = Type.LargeTitle)
            Column((if (verified) Modifier else Modifier.weight(1f)).fillMaxWidth()
                .testTag("setup-body")
                .focusTarget(owner, "$page-body") {}
                .onPreviewKeyEvent { event ->
                    val delta = when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> 100
                        KeyEvent.KEYCODE_DPAD_UP -> -100
                        else -> 0
                    }
                    if (delta == 0 || (delta > 0 && scroll.value >= scroll.maxValue) || (delta < 0 && scroll.value == 0)) false
                    else {
                        if (event.type == KeyEventType.KeyDown) scope.launch { scroll.scrollTo((scroll.value + delta).coerceIn(0, scroll.maxValue)) }
                        true
                    }
                }.focusable(enabled = !verified).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = if (verified) Alignment.CenterHorizontally else Alignment.Start) {
                when (state) {
                    is SetupState.Intro -> {
                        if (state.nativeHome) Note(R.string.setup_native_home)
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Fill).padding(12.dp)) {
                            Note(R.string.setup_debug_instructions)
                        }
                        Note(R.string.setup_intro)
                    }
                    is SetupState.Checking -> Note(if (state.authorizing) R.string.setup_authorizing else R.string.setup_checking)
                    is SetupState.Review -> {
                        listOf(SetupOperation.HomeGuard, SetupOperation.AutoStart, SetupOperation.Notification).forEach { operation ->
                            val status = when {
                                operation in state.snapshot.configured -> R.string.setup_already
                                operation.optional && operation !in state.snapshot.optionalOperations -> R.string.setup_unsupported
                                else -> R.string.setup_will_apply
                            }
                            BasicText(stringResource(R.string.setup_status_row, stringResource(when (operation) {
                                SetupOperation.HomeGuard -> R.string.setup_purpose_home
                                SetupOperation.AutoStart -> R.string.setup_purpose_boot
                                else -> R.string.setup_purpose_recovery
                            }), stringResource(status)), style = Type.Body)
                        }
                        Note(R.string.setup_security_disclosure)
                        if (state.legacyBehaviors) Note(R.string.setup_legacy_disclosure)
                    }
                    is SetupState.Applying -> {
                        Note(R.string.setup_applying)
                        BasicText(stringResource(state.operation.label), style = Type.Title)
                        Note(R.string.setup_cancel_warning)
                    }
                    is SetupState.Result -> {
                        Note(if (verified) R.string.setup_verified else R.string.setup_partial)
                        if (!verified) {
                            state.report.outcomes.filterValues { it == SetupOutcome.Failed || it == SetupOutcome.Uncertain }.forEach { (operation, outcome) ->
                                BasicText(stringResource(R.string.setup_status_row, stringResource(operation.label), stringResource(outcome.label)), style = Type.Body)
                            }
                            if (actual != null) {
                                if (!actual.guardBound) BasicText(stringResource(R.string.setup_status_row, stringResource(R.string.setup_guard_binding), stringResource(R.string.setup_not_bound)), style = Type.Body)
                                if (!actual.listenerBound) BasicText(stringResource(R.string.setup_status_row, stringResource(R.string.setup_listener_binding), stringResource(R.string.setup_not_bound)), style = Type.Body)
                            }
                        }
                    }
                    is SetupState.Blocked -> {
                        Note(if (state.connected) R.string.setup_read_failed else when (state.reason) {
                            AdbFailureReason.TlsRequired -> R.string.setup_tls
                            AdbFailureReason.AuthorizationTimeout -> R.string.setup_auth_timeout
                            AdbFailureReason.HandshakeTimeout -> R.string.setup_handshake_timeout
                            AdbFailureReason.ConnectionRefused -> R.string.setup_connection_refused
                            AdbFailureReason.ConnectionTimeout -> R.string.setup_connection_timeout
                            AdbFailureReason.ConnectionDenied -> R.string.setup_connection_denied
                            else -> R.string.setup_blocked
                        })
                        state.reason?.let { BasicText(stringResource(R.string.setup_error_code, it.code), style = Type.Body) }
                    }
                    SetupState.Closed -> Unit
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = if (verified) Alignment.CenterHorizontally else Alignment.Start) {
                @Composable fun Action(id: String, label: Int, action: () -> Unit) {
                    Pressable(onClick = action, modifier = Modifier.width(210.dp).height(44.dp).focusTarget(owner, "$page-$id") {}.semantics(mergeDescendants = true) {}) { interaction ->
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                            .background(if (interaction.focused) Palette.Focus else if (id == primary) Palette.Fill else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            BasicText(stringResource(label), style = Type.Body.copy(color = if (interaction.focused) Palette.OnFocus else Palette.Label))
                        }
                    }
                }
                if (state is SetupState.Intro && onDiagnostics != null) Action("diagnostics", R.string.setup_diagnostics, onDiagnostics)
                if (busy) Action("cancel", R.string.setup_cancel, onCancel)
                else if (verified) Action("done", R.string.setup_finish, onDismiss)
                else {
                    when (state) {
                        is SetupState.Review -> Action("apply", R.string.setup_apply, onConfirm)
                        else -> Action("check", if (state is SetupState.Intro) R.string.setup_check else R.string.setup_retry, onCheck)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (onDiagnostics != null && (state is SetupState.Blocked || state is SetupState.Result)) {
                            Action("diagnostics", R.string.setup_diagnostics, onDiagnostics)
                        } else if (settings != null && state !is SetupState.Review) Action("settings", R.string.setup_open_settings) { onOpen(settings) }
                        Action("close", if (state is SetupState.Intro) R.string.setup_skip else R.string.setup_done, onDismiss)
                    }
                }
            }
        }
        }
    }
    }
}

@Composable private fun Note(resource: Int) { BasicText(stringResource(resource), style = Type.Callout) }
internal val SetupOperation.label: Int get() = when (this) {
    SetupOperation.ServiceAccess -> R.string.setup_service_access
    SetupOperation.SecureSettings -> R.string.setup_secure_settings
    SetupOperation.Overlay -> R.string.setup_overlay
    SetupOperation.Notification -> R.string.setup_notification
    SetupOperation.HomeGuard -> R.string.setup_guard
    SetupOperation.AutoStart -> R.string.setup_autostart
}
private val SetupOutcome.label: Int get() = when (this) {
    SetupOutcome.AlreadySet -> R.string.setup_already
    SetupOutcome.Verified -> R.string.setup_confirmed
    SetupOutcome.Failed -> R.string.setup_failed
    SetupOutcome.Unsupported -> R.string.setup_unsupported
    SetupOutcome.Uncertain -> R.string.setup_uncertain
}
