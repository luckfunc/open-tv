package app.opentv.launcher.ui.focus

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
internal fun <K : Any, G> Modifier.focusTarget(
    owner: FocusCoordinator<K, G>,
    target: K,
    onFocused: (K) -> Unit,
): Modifier {
    val observed by rememberUpdatedState(onFocused)
    val requester = remember(owner, target) { FocusRequester() }
    val binding = remember(owner, target) {
        owner.bind(target) {
            requester.requestFocus().also { if (!it) Log.w("OpenTVFocus", "Focus request rejected for $target") }
        }
    }
    DisposableEffect(binding) {
        binding.attach()
        onDispose { binding.detach() }
    }
    return focusRequester(requester)
        .focusProperties { canFocus = binding.canFocus }
        .onGloballyPositioned { binding.placed() }
        .onFocusChanged { if (binding.observed(it.isFocused)) observed(target) }
}
