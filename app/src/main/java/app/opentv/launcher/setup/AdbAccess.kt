package app.opentv.launcher.setup

import java.io.Closeable
import java.io.IOException

internal interface AdbConnector : Closeable {
    suspend fun connect(onAuthorizationRequested: () -> Unit): AdbSession
}

internal interface AdbSession : Closeable {
    suspend fun execute(command: String, timeoutMillis: Long = 15_000): AdbCommandResult
}

internal data class AdbCommandResult(val output: String, val exitCode: Int)

internal enum class AdbFailureReason {
    Unavailable, AuthorizationTimeout, TlsRequired, Disconnected, ProtocolError,
    CommandTimeout, IdentityUnavailable,
}

internal class AdbFailure(val reason: AdbFailureReason) : IOException(reason.name)
