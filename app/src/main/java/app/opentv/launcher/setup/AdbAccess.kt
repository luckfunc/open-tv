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
    CommandTimeout, IdentityUnavailable, ConnectionRefused, ConnectionTimeout, ConnectionDenied, HandshakeTimeout,
}

internal val AdbFailureReason.code: String get() = when (this) {
    AdbFailureReason.ConnectionRefused -> "C01"
    AdbFailureReason.ConnectionTimeout -> "C02"
    AdbFailureReason.ConnectionDenied -> "C03"
    AdbFailureReason.Unavailable -> "C04"
    AdbFailureReason.HandshakeTimeout -> "H01"
    AdbFailureReason.TlsRequired -> "H02"
    AdbFailureReason.ProtocolError -> "H03"
    AdbFailureReason.AuthorizationTimeout -> "A01"
    AdbFailureReason.Disconnected -> "C05"
    AdbFailureReason.CommandTimeout -> "R01"
    AdbFailureReason.IdentityUnavailable -> "I01"
}

internal class AdbFailure(val reason: AdbFailureReason, cause: Throwable? = null) : IOException(reason.name, cause)
