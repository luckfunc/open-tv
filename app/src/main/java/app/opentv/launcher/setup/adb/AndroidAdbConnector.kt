package app.opentv.launcher.setup.adb

import android.content.Context
import app.opentv.launcher.setup.AdbCommandResult
import app.opentv.launcher.setup.AdbConnector
import app.opentv.launcher.setup.AdbFailure
import app.opentv.launcher.setup.AdbFailureReason
import app.opentv.launcher.setup.AdbSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class AndroidAdbConnector internal constructor(
    private val identity: () -> AdbIdentity,
    private val port: Int = 5555,
    private val authorizationTimeoutMillis: Long = 60_000,
    private val socketFactory: () -> Socket = { Socket() },
) : AdbConnector {
    constructor(context: Context) : this({ AdbIdentity.load(context.applicationContext) })

    private val lock = Any()
    private var closed = false
    private var ownedSocket: Socket? = null

    override suspend fun connect(onAuthorizationRequested: () -> Unit): AdbSession {
        try {
            return connectOnIo(onAuthorizationRequested)
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }

    private suspend fun connectOnIo(onAuthorizationRequested: () -> Unit): AdbSession = withContext(Dispatchers.IO) {
        val key = identity()
        coroutineContext.ensureActive()
        val socket = socketFactory()
        synchronized(lock) {
            if (closed || ownedSocket != null) {
                socket.close()
                throw AdbFailure(AdbFailureReason.Disconnected)
            }
            ownedSocket = socket
        }
        var connected = false
        val deadline = deadlineAfter(authorizationTimeoutMillis)
        val reason = AdbFailureReason.AuthorizationTimeout
        val guard = AdbIoDeadline(socket, deadline, coroutineContext)
        try {
            socket.soTimeout = 100
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 250)
            connected = true
            val wire = AdbWire(socket)
            wire.send(AdbPacket(AdbProtocol.CNXN, 0x01000000, AdbProtocol.MAX_PAYLOAD, "host::\u0000".toByteArray()), deadline, coroutineContext, reason)
            var auth = 0
            while (true) {
                val packet = wire.receive(deadline, coroutineContext, reason)
                when (packet.command) {
                    AdbProtocol.CNXN -> {
                        if (packet.arg0 < 0x01000000 || packet.arg1 <= 0) protocolError()
                        return@withContext SocketAdbSession(socket, wire) { release(socket) }
                    }
                    AdbProtocol.AUTH -> {
                        if (packet.arg0 != 1 || packet.arg1 != 0 || packet.payload.size != 20) protocolError()
                        when (auth++) {
                            0 -> wire.send(AdbPacket(AdbProtocol.AUTH, 2, 0, key.sign(packet.payload)), deadline, coroutineContext, reason)
                            1 -> {
                                wire.send(AdbPacket(AdbProtocol.AUTH, 3, 0, key.publicPayload()), deadline, coroutineContext, reason)
                                onAuthorizationRequested()
                            }
                            else -> protocolError()
                        }
                    }
                    else -> protocolError()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (failure: Throwable) {
            release(socket)
            checkDeadline(deadline, coroutineContext, reason)
            when (failure) {
                is CancellationException -> throw failure
                is AdbFailure -> throw failure
                else -> throw AdbFailure(if (connected) AdbFailureReason.Disconnected else AdbFailureReason.Unavailable)
            }
        } finally {
            guard.close()
        }
    }

    private fun release(socket: Socket) {
        synchronized(lock) { if (ownedSocket === socket) ownedSocket = null }
        try { socket.close() } catch (_: IOException) { }
    }

    override fun close() {
        val socket = synchronized(lock) {
            closed = true
            ownedSocket.also { ownedSocket = null }
        }
        try { socket?.close() } catch (_: IOException) { }
    }
}

private class SocketAdbSession(
    private val socket: Socket,
    private val wire: AdbWire,
    private val release: () -> Unit,
) : AdbSession {
    private val commands = Mutex()
    private var nextId = 1
    private data class ClosedStream(val local: Int, val remote: Int)
    private val closedStreams = ArrayDeque<ClosedStream>()

    override suspend fun execute(command: String, timeoutMillis: Long): AdbCommandResult = withContext(Dispatchers.IO) {
        val deadline = deadlineAfter(timeoutMillis)
        val owner = Any()
        try {
            if (withTimeoutOrNull(timeoutMillis.coerceIn(1, 120_000)) { commands.lock(owner); true } != true) {
                throw AdbFailure(AdbFailureReason.CommandTimeout)
            }
            require(command.isNotBlank() && '\u0000' !in command && command.length <= 2048)
            val reason = AdbFailureReason.CommandTimeout
            val context = coroutineContext
            val local = nextId++
            val marker = "__OPENTV_${UUID.randomUUID().toString().replace("-", "") }__"
            val shell = "shell:($command); __opentv_status=$?; printf '\\n$marker:%s\\n' \"\${__opentv_status}\"\u0000"
            val output = ByteArrayOutputStream()
            val guard = AdbIoDeadline(socket, deadline, context)
            try {
                if (socket.isClosed || local <= 0) throw AdbFailure(AdbFailureReason.Disconnected)
                wire.send(AdbPacket(AdbProtocol.OPEN, local, 0, shell.toByteArray(Charsets.UTF_8)), deadline, context, reason)
                val opened = receiveStreamPacket(deadline, context, reason)
                if (opened.command == AdbProtocol.CLSE && opened.arg0 == 0 && opened.arg1 == local) {
                    throw AdbFailure(AdbFailureReason.Disconnected)
                }
                if (opened.command != AdbProtocol.OKAY || opened.arg0 <= 0 || opened.arg1 != local || opened.payload.isNotEmpty()) protocolError()
                val remote = opened.arg0
                while (true) {
                    val packet = receiveStreamPacket(deadline, context, reason)
                    if (packet.arg0 != remote || packet.arg1 != local) protocolError()
                    when (packet.command) {
                        AdbProtocol.WRTE -> {
                            if (output.size() + packet.payload.size > 256 * 1024) protocolError()
                            output.write(packet.payload)
                            wire.send(AdbPacket(AdbProtocol.OKAY, local, remote), deadline, context, reason)
                        }
                        AdbProtocol.CLSE -> {
                            if (packet.payload.isNotEmpty()) protocolError()
                            closedStreams.addLast(ClosedStream(local, remote))
                            if (closedStreams.size > 64) closedStreams.removeFirst()
                            wire.send(AdbPacket(AdbProtocol.CLSE, local, remote), deadline, context, reason)
                            return@withContext parseResult(output.toByteArray(), marker)
                        }
                        else -> protocolError()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } catch (failure: Throwable) {
                close()
                checkDeadline(deadline, context, reason)
                when (failure) {
                    is CancellationException -> throw failure
                    is AdbFailure -> throw failure
                    else -> throw AdbFailure(AdbFailureReason.Disconnected)
                }
            } finally {
                guard.close()
            }
        } finally {
            if (commands.holdsLock(owner)) commands.unlock(owner)
        }
    }

    private suspend fun receiveStreamPacket(deadline: Long, context: CoroutineContext, reason: AdbFailureReason): AdbPacket {
        while (true) {
            val packet = wire.receive(deadline, context, reason)
            if (packet.command == AdbProtocol.CLSE && packet.payload.isEmpty() &&
                ClosedStream(packet.arg1, packet.arg0) in closedStreams) continue
            return packet
        }
    }

    private fun parseResult(bytes: ByteArray, marker: String): AdbCommandResult {
        val text = bytes.toString(Charsets.UTF_8)
        val match = Regex("\\r?\\n${marker}:([0-9]{1,3})\\r?\\n$").find(text) ?: protocolError()
        val exit = match.groupValues[1].toInt()
        if (exit !in 0..255) protocolError()
        return AdbCommandResult(text.substring(0, match.range.first), exit)
    }

    override fun close() = release()
}
