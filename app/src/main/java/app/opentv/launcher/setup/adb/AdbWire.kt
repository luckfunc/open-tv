package app.opentv.launcher.setup.adb

import app.opentv.launcher.setup.AdbFailure
import app.opentv.launcher.setup.AdbFailureReason
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

internal data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = byteArrayOf())

internal object AdbProtocol {
    val CNXN = command("CNXN")
    val AUTH = command("AUTH")
    val STLS = command("STLS")
    val OPEN = command("OPEN")
    val OKAY = command("OKAY")
    val WRTE = command("WRTE")
    val CLSE = command("CLSE")
    const val MAX_PAYLOAD = 4096

    private fun command(value: String): Int = ByteBuffer.wrap(value.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
    fun checksum(bytes: ByteArray): Int = bytes.sumOf { it.toInt() and 255 }
    fun encode(packet: AdbPacket): ByteArray = ByteBuffer.allocate(24 + packet.payload.size).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(packet.command).putInt(packet.arg0).putInt(packet.arg1).putInt(packet.payload.size)
        .putInt(checksum(packet.payload)).putInt(packet.command.inv()).put(packet.payload).array()
}

internal class AdbWire(private val socket: Socket) {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    fun send(packet: AdbPacket, deadline: Long, context: CoroutineContext, timeout: AdbFailureReason) {
        checkDeadline(deadline, context, timeout)
        require(packet.payload.size <= AdbProtocol.MAX_PAYLOAD)
        output.write(AdbProtocol.encode(packet))
        output.flush()
        checkDeadline(deadline, context, timeout)
    }

    fun receive(deadline: Long, context: CoroutineContext, timeout: AdbFailureReason): AdbPacket {
        val header = ByteBuffer.wrap(readExact(24, deadline, context, timeout)).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val length = header.int
        val checksum = header.int
        val magic = header.int
        if (length !in 0..AdbProtocol.MAX_PAYLOAD || magic != command.inv()) protocolError()
        val payload = readExact(length, deadline, context, timeout)
        if (AdbProtocol.checksum(payload) != checksum) protocolError()
        if (command == AdbProtocol.STLS) throw AdbFailure(AdbFailureReason.TlsRequired)
        return AdbPacket(command, arg0, arg1, payload)
    }

    private fun readExact(size: Int, deadline: Long, context: CoroutineContext, timeout: AdbFailureReason): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            checkDeadline(deadline, context, timeout)
            try {
                val count = input.read(bytes, offset, size - offset)
                if (count < 0) throw EOFException()
                offset += count
            } catch (_: SocketTimeoutException) {
                checkDeadline(deadline, context, timeout)
            }
        }
        checkDeadline(deadline, context, timeout)
        return bytes
    }
}

internal class AdbIoDeadline(socket: Socket, deadline: Long, context: CoroutineContext) : java.io.Closeable {
    private val task = watchdog.scheduleAtFixedRate({
        if (!context.isActive || System.nanoTime() >= deadline) {
            try { socket.close() } catch (_: java.io.IOException) { }
        }
    }, 0, 50, java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun close() { task.cancel(false) }

    companion object {
        private val watchdog = java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "OpenTV-adb-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

internal fun deadlineAfter(millis: Long): Long = System.nanoTime() + millis.coerceIn(1, 120_000) * 1_000_000
internal fun checkDeadline(deadline: Long, context: CoroutineContext, timeout: AdbFailureReason) {
    context.ensureActive()
    if (System.nanoTime() >= deadline) throw AdbFailure(timeout)
}
internal fun protocolError(): Nothing = throw AdbFailure(AdbFailureReason.ProtocolError)
