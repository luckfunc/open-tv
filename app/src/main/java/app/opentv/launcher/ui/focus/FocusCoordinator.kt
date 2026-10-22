package app.opentv.launcher.ui.focus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Owns a single focus surface. Group policy and destination resolution belong to its caller. */
internal class FocusCoordinator<K : Any, G>(private val groupOf: (K) -> G) {
    private sealed interface State<out K> {
        data object Inactive : State<Nothing>
        data class Waiting<K>(val target: K) : State<K>
        data class Active<K>(val target: K) : State<K>
    }

    private var state by mutableStateOf<State<K>>(State.Inactive)
    private val bindings = mutableStateMapOf<K, Binding>()
    private var delivering = false

    fun request(target: K?) {
        if (target == null) {
            state = State.Inactive
            return
        }
        val current = state
        if (current is State.Active && current.target == target && bindings[target]?.hasFocus == true) return
        state = State.Waiting(target)
        deliver()
    }

    fun isEligible(target: K): Boolean = when (val current = state) {
        State.Inactive -> false
        is State.Waiting -> target == current.target
        is State.Active -> groupOf(target) == groupOf(current.target)
    }

    fun bind(target: K, requestFocus: () -> Boolean): Binding = Binding(target, requestFocus)

    internal inner class Binding internal constructor(private val target: K, internal val requestFocus: () -> Boolean) {
        private var placed by mutableStateOf(false)
        internal var hasFocus = false; private set
        val canFocus get() = bindings[target] === this && placed && isEligible(target)

        fun attach() {
            val replacement = bindings.put(target, this)
            val current = state
            if (replacement !== this && current is State.Active && current.target == target) state = State.Waiting(target)
            deliver()
        }

        fun placed() {
            if (placed) return
            placed = true
            if (bindings[target] === this) deliver()
        }

        fun observed(focused: Boolean): Boolean {
            hasFocus = focused
            if (!focused || !canFocus) return false
            state = State.Active(target)
            return true
        }

        fun detach() {
            if (bindings[target] !== this) return
            bindings.remove(target)
            val current = state
            if (current is State.Active && current.target == target) state = State.Waiting(target)
        }
    }

    private fun deliver() {
        if (delivering) return
        delivering = true
        try {
            var attempted: Binding? = null
            while (true) {
                val current = state as? State.Waiting ?: break
                val binding = bindings[current.target]?.takeIf { it.canFocus } ?: break
                if (binding === attempted) break
                attempted = binding
                if (binding.hasFocus) binding.observed(true) else binding.requestFocus()
            }
        } finally {
            delivering = false
        }
    }
}
