package app.opentv.launcher.setup

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext

internal enum class SetupReturn { Home, Personalization }
internal enum class SetupOperation(val optional: Boolean = false) {
    ServiceAccess(true), SecureSettings, Overlay, Notification, HomeGuard, AutoStart(true)
}
internal enum class SetupOutcome { AlreadySet, Verified, Failed, Unsupported, Uncertain }
internal data class SetupSnapshot(
    val configured: Set<SetupOperation>,
    val optionalOperations: Set<SetupOperation>,
    val before: Map<String, String>,
    val guardBound: Boolean,
    val listenerBound: Boolean,
)
internal data class SetupReport(
    val outcomes: Map<SetupOperation, SetupOutcome>,
    val snapshot: SetupSnapshot? = null,
    val cancelled: Boolean = false,
)
internal sealed interface SetupState {
    data object Closed : SetupState
    data class Intro(val nativeHome: Boolean) : SetupState
    data class Checking(val authorizing: Boolean = false) : SetupState
    data class Review(val snapshot: SetupSnapshot, val legacyBehaviors: Boolean) : SetupState
    data class Applying(val operation: SetupOperation, val outcomes: Map<SetupOperation, SetupOutcome>) : SetupState
    data class Result(val report: SetupReport) : SetupState
    data class Blocked(val reason: AdbFailureReason?, val connected: Boolean = false) : SetupState
}
internal interface SetupEnvironment {
    fun trace(event: String, error: Throwable? = null) = Unit
    fun acknowledged(): Boolean
    fun acknowledge()
    fun nativeHome(): Boolean
    fun legacyBehaviors(): Boolean
    suspend fun snapshot(session: AdbSession): SetupSnapshot
    suspend fun apply(operation: SetupOperation, session: AdbSession): Boolean
    fun journal(before: SetupSnapshot, outcomes: Map<SetupOperation, SetupOutcome>)
}

internal class SetupController(
    private val scope: CoroutineScope,
    private val connectorFactory: () -> AdbConnector,
    private val environment: SetupEnvironment,
) : Closeable {
    private val mutableState = MutableStateFlow<SetupState>(
        if (environment.acknowledged()) SetupState.Closed else SetupState.Intro(environment.nativeHome()),
    )
    val state = mutableState.asStateFlow()
    var destination = SetupReturn.Home
        private set
    private var job: Job? = null
    private var connector: AdbConnector? = null
    private var session: AdbSession? = null
    private var generation = 0
    private var before: SetupSnapshot? = null
    private val outcomes = linkedMapOf<SetupOperation, SetupOutcome>()

    fun open(destination: SetupReturn) {
        stop()
        this.destination = destination
        mutableState.value = SetupState.Intro(environment.nativeHome())
    }

    fun returnHome() {
        destination = SetupReturn.Home
        dismiss()
    }

    fun dismiss() {
        if (mutableState.value is SetupState.Applying) {
            cancel()
            return
        }
        stop()
        environment.acknowledge()
        mutableState.value = SetupState.Closed
    }

    fun check() {
        environment.trace("check start")
        if (mutableState.value is SetupState.Applying) return
        stop()
        outcomes.clear()
        before = null
        mutableState.value = SetupState.Checking()
        val token = generation
        job = scope.launch {
            var connectionEstablished = false
            try {
                val connection = connectorFactory()
                connector = connection
                environment.trace("connect start")
                val connected = connection.connect {
                    environment.trace("connect authorization waiting")
                    scope.launch {
                        if (token == generation && mutableState.value is SetupState.Checking) {
                            mutableState.value = SetupState.Checking(authorizing = true)
                        }
                    }
                }
                try {
                    coroutineContext.ensureActive()
                } catch (cancelled: CancellationException) {
                    connected.close()
                    throw cancelled
                }
                environment.trace("connect established")
                connectionEstablished = true
                session = connected
                val identity = connected.execute("id -u")
                if (identity.exitCode != 0 || identity.output.trim() != "2000") throw AdbFailure(AdbFailureReason.ProtocolError)
                val snapshot = environment.snapshot(connected)
                coroutineContext.ensureActive()
                if (token == generation) {
                    before = snapshot
                    environment.trace("Review configured=${snapshot.configured} guardBound=${snapshot.guardBound} listenerBound=${snapshot.listenerBound}")
                    mutableState.value = SetupState.Review(snapshot, environment.legacyBehaviors())
                }
            } catch (cancelled: CancellationException) {
                environment.trace("check cancelled", cancelled)
                throw cancelled
            } catch (error: Exception) {
                environment.trace("check failed", error)
                if (token == generation) {
                    release()
                    mutableState.value = SetupState.Blocked((error as? AdbFailure)?.reason, connected = connectionEstablished)
                }
            }
        }
    }

    fun confirm() {
        val review = mutableState.value as? SetupState.Review ?: return
        val connected = session ?: return
        val token = generation
        environment.trace("confirm start")
        mutableState.value = SetupState.Applying(SetupOperation.SecureSettings, emptyMap())
        job = scope.launch {
            try {
                val identity = connected.execute("id -u")
                if (identity.exitCode != 0 || identity.output.trim() != "2000") throw AdbFailure(AdbFailureReason.ProtocolError)
                val initial = environment.snapshot(connected)
                coroutineContext.ensureActive()
                before = initial
                environment.journal(initial, outcomes.toMap())
                for (operation in SetupOperation.entries) {
                    environment.trace("operation=$operation start")
                    coroutineContext.ensureActive()
                    if (operation.optional &&
                        (operation !in review.snapshot.optionalOperations || operation !in initial.optionalOperations)) {
                        outcomes[operation] = SetupOutcome.Unsupported
                    } else if (operation in initial.configured &&
                        !(operation == SetupOperation.HomeGuard && !initial.guardBound)) {
                        outcomes[operation] = SetupOutcome.AlreadySet
                    } else {
                        outcomes[operation] = SetupOutcome.Uncertain
                        environment.journal(initial, outcomes.toMap())
                        mutableState.value = SetupState.Applying(operation, outcomes.toMap())
                        val success = environment.apply(operation, connected)
                        coroutineContext.ensureActive()
                        val actual = environment.snapshot(connected)
                        outcomes[operation] = when {
                            success && operation == SetupOperation.HomeGuard -> SetupOutcome.Uncertain
                            success && operation in actual.configured -> SetupOutcome.Verified
                            else -> SetupOutcome.Failed
                        }
                    }
                    environment.trace("operation=$operation outcome=${outcomes[operation]}")
                    environment.journal(initial, outcomes.toMap())
                    if (operation == SetupOperation.SecureSettings && outcomes[operation] == SetupOutcome.Failed) break
                }
                var actual = environment.snapshot(connected)
                val required = SetupOperation.entries.filterNot { it.optional }.toSet() +
                    actual.optionalOperations
                val awaitingGuard = outcomes[SetupOperation.HomeGuard] == SetupOutcome.Uncertain
                if (actual.configured.containsAll(if (awaitingGuard) required - SetupOperation.HomeGuard else required)) {
                    var attempts = 0
                    while ((!actual.guardBound || !actual.listenerBound) && attempts < 15) {
                        delay(200)
                        coroutineContext.ensureActive()
                        actual = environment.snapshot(connected)
                        attempts++
                    }
                }
                coroutineContext.ensureActive()
                if (awaitingGuard) {
                    outcomes[SetupOperation.HomeGuard] = if (SetupOperation.HomeGuard in actual.configured && actual.guardBound)
                        SetupOutcome.Verified else SetupOutcome.Failed
                    environment.journal(initial, outcomes.toMap())
                }
                environment.trace("final outcomes=$outcomes configured=${actual.configured} guardBound=${actual.guardBound} listenerBound=${actual.listenerBound}")
                if (token == generation) mutableState.value = SetupState.Result(SetupReport(outcomes.toMap(), actual))
            } catch (cancelled: CancellationException) {
                environment.trace("confirm cancelled operation=${(mutableState.value as? SetupState.Applying)?.operation}", cancelled)
                throw cancelled
            } catch (error: Exception) {
                environment.trace("confirm failed operation=${(mutableState.value as? SetupState.Applying)?.operation}", error)
                if (token == generation) mutableState.value = SetupState.Result(SetupReport(outcomes.toMap()))
            } finally {
                if (token == generation) release()
            }
        }
    }

    fun cancel() {
        environment.trace("cancel state=${mutableState.value.javaClass.simpleName}")
        val applying = mutableState.value is SetupState.Applying
        stop()
        if (applying) {
            before?.let {
                runCatching { environment.journal(it, outcomes.toMap()) }
                    .onFailure { error -> environment.trace("cancel journal failed", error) }
            }
            mutableState.value = SetupState.Result(SetupReport(outcomes.toMap(), cancelled = true))
        } else mutableState.value = SetupState.Intro(environment.nativeHome())
    }

    private fun release() {
        runCatching { session?.close() }.onFailure { environment.trace("session close failed", it) }
        runCatching { connector?.close() }.onFailure { environment.trace("connector close failed", it) }
        session = null
        connector = null
    }

    private fun stop() {
        generation++
        job?.cancel()
        job = null
        release()
    }

    override fun close() = stop()
}
