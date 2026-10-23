package app.opentv.launcher.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import app.opentv.launcher.ui.theme.Palette
import app.opentv.launcher.ui.theme.Type
import app.opentv.launcher.wallpaper.PhoneServer
import app.opentv.launcher.wallpaper.PhoneSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SetupDiagnosticScreenProps(val onBack: () -> Unit)

@Composable
internal fun SetupDiagnosticScreen(props: SetupDiagnosticScreenProps) {
    val onBack = props.onBack
    val context = LocalContext.current
    val diagnostics = remember(context) { AndroidSetupDiagnostics.get(context) }
    val report by produceState<String?>(null, diagnostics) {
        value = withContext(Dispatchers.IO) { diagnostics.latestReport() }
    }
    val session = remember(context) { PhoneSession.get(context) }
    val baseUrl by session.url.collectAsState()
    var exportToken by remember { mutableStateOf<String?>(null) }
    DisposableEffect(report) {
        val text = report
        val lease = if (text != null) {
            exportToken = AndroidSetupDiagnostics.share(text)
            session.acquire()
        } else null
        onDispose {
            if (text != null) AndroidSetupDiagnostics.finishSharing()
            lease?.close()
        }
    }
    val url = if (report != null && exportToken != null) baseUrl?.let {
        it.removeSuffix("/").substringBeforeLast("/") + "/diagnostic-export/$exportToken/diagnostics"
    } else null
    val qr by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) {
        value = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it).asImageBitmap() } }
    }
    val owner = remember { FocusCoordinator<String, Unit> { Unit } }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { owner.request(if (focused) "back" else null) }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().testTag("setup-diagnostics"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(700.dp).height(420.dp).clip(RoundedCornerShape(24.dp))
            .background(Palette.Glass).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BasicText(stringResource(R.string.setup_diagnostics), style = Type.LargeTitle)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText(stringResource(R.string.setup_diagnostic_help), style = Type.Callout)
                    val fields = report?.lineSequence()?.filter { ": " in it }?.associate { it.substringBefore(": ") to it.substringAfter(": ") }.orEmpty()
                    val stage = DiagnosticStage.entries.firstOrNull { it.name == fields["Stage"] }
                    val stageLabel = stage?.let { stringResource(it.label) } ?: "—"
                    BasicText(if (report == null) stringResource(R.string.setup_diagnostic_empty) else
                        stringResource(R.string.setup_diagnostic_summary, fields["Attempt"].orEmpty(), stageLabel,
                            fields["Code"].orEmpty(), fields["App"].orEmpty(), fields["Model"].orEmpty()), style = Type.Body)
                    BasicText(stringResource(R.string.setup_diagnostic_privacy), style = Type.Callout)
                }
                Column(Modifier.width(190.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val bitmap = qr
                    if (bitmap != null) {
                        Box(Modifier.size(174.dp).background(androidx.compose.ui.graphics.Color.White).padding(10.dp)) {
                            Image(bitmap, stringResource(R.string.setup_diagnostic_scan), Modifier.fillMaxSize())
                        }
                        BasicText(stringResource(R.string.setup_diagnostic_scan), style = Type.Callout)
                    } else BasicText(stringResource(R.string.setup_diagnostic_network), style = Type.Callout)
                }
            }
            Pressable(onClick = onBack, modifier = Modifier.width(210.dp).height(44.dp).focusTarget(owner, "back") {}.semantics(mergeDescendants = true) {}) { interaction ->
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(if (interaction.focused) Palette.Focus else Palette.Fill),
                    contentAlignment = Alignment.Center) {
                    BasicText(stringResource(R.string.setup_diagnostic_back), style = Type.Body.copy(color = if (interaction.focused) Palette.OnFocus else Palette.Label))
                }
            }
        }
    }
}

private val DiagnosticStage.label: Int get() = when (this) {
    DiagnosticStage.Preparation -> R.string.setup_stage_preparation
    DiagnosticStage.Identity -> R.string.setup_stage_identity
    DiagnosticStage.Connection -> R.string.setup_stage_connection
    DiagnosticStage.Handshake -> R.string.setup_stage_handshake
    DiagnosticStage.Authorization -> R.string.setup_stage_authorization
    DiagnosticStage.Read -> R.string.setup_stage_read
    DiagnosticStage.Configure -> R.string.setup_stage_configure
    DiagnosticStage.Verify -> R.string.setup_stage_verify
}
