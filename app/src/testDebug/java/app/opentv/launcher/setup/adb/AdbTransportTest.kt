package app.opentv.launcher.setup.adb

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import app.opentv.launcher.setup.AdbFailure
import app.opentv.launcher.setup.AdbCommandResult
import app.opentv.launcher.setup.AdbFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AdbTransportTest {
    private val identity = AdbIdentity(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private)

    @Test fun rawDigestSignatureAndAndroidPublicKeyAreCompatible() {
        val bytes = identity.publicWire()
        assertEquals(524, bytes.size)
        val wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, wire.int)
        val n0 = wire.int.toLong() and 0xffffffffL
        val modulus = ByteArray(256).also { wire.get(it) }.reversedArray().let { BigInteger(1, it) }
        val rr = ByteArray(256).also { wire.get(it) }.reversedArray().let { BigInteger(1, it) }
        assertEquals(65537, wire.int)
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), rr)
        assertEquals(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE), modulus.multiply(BigInteger.valueOf(n0)).mod(BigInteger.ONE.shiftLeft(32)))
        val public = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))
        val message = "OpenTV authentication verification".toByteArray()
        val token = java.security.MessageDigest.getInstance("SHA-1").digest(message)
        assertTrue(Signature.getInstance("SHA1withRSA").run { initVerify(public); update(message); verify(identity.sign(token)) })
        val payload = identity.publicPayload().toString(Charsets.US_ASCII)
        assertTrue(payload.endsWith(" OpenTV\u0000"))
        assertArrayEquals(bytes, Base64.decode(payload.substringBefore(' '), Base64.DEFAULT))
    }

    @Test fun identityPersistsAndCorruptionDoesNotRotateIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.noBackupFilesDir, "opentv-adb-identity.pk8")
        file.delete()
        java.io.File(file.path + ".bak").delete()
        java.io.File(file.path + ".new").delete()
        try {
            val first = AdbIdentity.load(context).publicWire()
            assertArrayEquals(first, AdbIdentity.load(context).publicWire())
            file.writeBytes(byteArrayOf(1, 2, 3))
            expectFailure(AdbFailureReason.IdentityUnavailable) { AdbIdentity.load(context) }
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally { file.delete() }
    }

    @Test fun fragmentedPublicKeyAuthorizationAndNonzeroShellExit() = runBlocking {
        var callbacks = 0
        FakeAdbd { socket ->
            assertEquals(AdbProtocol.CNXN, read(socket).command)
            send(socket, AdbPacket(AdbProtocol.AUTH, 1, 0, ByteArray(20)), true)
            assertEquals(2, read(socket).arg0)
            send(socket, AdbPacket(AdbProtocol.AUTH, 1, 0, ByteArray(20) { 1 }), true)
            val public = read(socket)
            assertEquals(3, public.arg0)
            assertArrayEquals(identity.publicPayload(), public.payload)
            send(socket, connected(), true)
            val open = read(socket)
            assertEquals(AdbProtocol.OPEN, open.command)
            val marker = Regex("__OPENTV_[a-f0-9]+__").find(open.payload.toString(Charsets.UTF_8))!!.value
            send(socket, AdbPacket(AdbProtocol.OKAY, 19, open.arg0), true)
            for (text in listOf("exact output\n", "\n$marker:7\n")) {
                send(socket, AdbPacket(AdbProtocol.WRTE, 19, open.arg0, text.toByteArray()), true)
                val ack = read(socket)
                assertEquals(AdbProtocol.OKAY, ack.command)
                assertEquals(19, ack.arg1)
            }
            send(socket, AdbPacket(AdbProtocol.CLSE, 19, open.arg0), true)
            assertEquals(AdbProtocol.CLSE, read(socket).command)
        }.use { server ->
            connector(server).use { connector ->
                connector.connect { callbacks++ }.use { session ->
                    val result = session.execute("false")
                    assertEquals(7, result.exitCode)
                    assertEquals("exact output\n", result.output)
                }
            }
        }
        assertEquals(1, callbacks)
    }

    @Test fun legacyTerminalLineEndingsPreserveOutputAndExitStatus() = runBlocking {
        FakeAdbd { socket ->
            read(socket)
            send(socket, connected())
            val open = read(socket)
            val marker = Regex("__OPENTV_[a-f0-9]+__").find(open.payload.toString(Charsets.UTF_8))!!.value
            send(socket, AdbPacket(AdbProtocol.OKAY, 19, open.arg0))
            send(socket, AdbPacket(AdbProtocol.WRTE, 19, open.arg0, "2000\r\n\r\n$marker:0\r\n".toByteArray()), true)
            assertEquals(AdbProtocol.OKAY, read(socket).command)
            send(socket, AdbPacket(AdbProtocol.CLSE, 19, open.arg0))
            assertEquals(AdbProtocol.CLSE, read(socket).command)
        }.use { server ->
            connector(server).use { connector ->
                connector.connect {}.use { session ->
                    assertEquals(AdbCommandResult("2000\r\n", 0), session.execute("id -u"))
                }
            }
        }
    }

    @Test fun duplicateClosedStreamFramesDoNotInterruptLaterCommands() = runBlocking {
        FakeAdbd { socket ->
            read(socket)
            send(socket, connected())
            var previous: AdbPacket? = null
            repeat(3) { index ->
                val open = read(socket)
                assertEquals(AdbProtocol.OPEN, open.command)
                val marker = Regex("__OPENTV_[a-f0-9]+__").find(open.payload.toString(Charsets.UTF_8))!!.value
                previous?.let { send(socket, it) }
                val remote = 61 + index
                send(socket, AdbPacket(AdbProtocol.OKAY, remote, open.arg0))
                previous?.let { send(socket, it) }
                send(socket, AdbPacket(AdbProtocol.WRTE, remote, open.arg0, "result$index\n$marker:$index\n".toByteArray()))
                val ack = read(socket)
                assertEquals(AdbProtocol.OKAY, ack.command)
                assertEquals(open.arg0, ack.arg0)
                assertEquals(remote, ack.arg1)
                previous?.let { send(socket, it) }
                val close = AdbPacket(AdbProtocol.CLSE, remote, open.arg0)
                send(socket, close)
                val closed = read(socket)
                assertEquals(AdbProtocol.CLSE, closed.command)
                assertEquals(open.arg0, closed.arg0)
                assertEquals(remote, closed.arg1)
                previous = close
            }
        }.use { server ->
            connector(server).use { connector ->
                connector.connect {}.use { session ->
                    repeat(3) { index ->
                        assertEquals(AdbCommandResult("result$index", index), session.execute("echo result$index"))
                    }
                }
            }
        }
    }

    @Test fun unknownCloseStillRejectsTheCommand() = runBlocking {
        FakeAdbd { socket ->
            read(socket)
            send(socket, connected())
            val open = read(socket)
            send(socket, AdbPacket(AdbProtocol.CLSE, 61, open.arg0 + 1))
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            connector(server).use { connector ->
                connector.connect {}.use { session ->
                    expectSuspendFailure(AdbFailureReason.ProtocolError) { session.execute("true") }
                }
            }
        }
    }

    @Test fun tlsIsTypedAndSocketIsClosed() = runBlocking {
        FakeAdbd { socket ->
            read(socket)
            send(socket, AdbPacket(AdbProtocol.STLS, 0x01000000, 0))
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            connector(server).use { connector -> expectSuspendFailure(AdbFailureReason.TlsRequired) { connector.connect {} } }
        }
    }

    @Test fun badLengthChecksumAndMagicAreRejected() = runBlocking {
        for (field in listOf(12, 16, 20)) {
            FakeAdbd { socket ->
                read(socket)
                val packet = AdbProtocol.encode(connected())
                ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(field, if (field == 12) Int.MAX_VALUE else 123)
                socket.getOutputStream().write(packet)
                try {
                    assertEquals(-1, socket.getInputStream().read())
                } catch (_: java.net.SocketException) {
                    // Rejecting a header before its payload is drained can reset TCP.
                }
            }.use { server ->
                connector(server).use { connector -> expectSuspendFailure(AdbFailureReason.ProtocolError) { connector.connect {} } }
            }
        }
    }

    @Test fun wrongStreamIdAndMissingExitMarkerCannotSucceed() = runBlocking {
        for (wrongId in listOf(true, false)) {
            FakeAdbd { socket ->
                read(socket)
                send(socket, connected())
                val open = read(socket)
                send(socket, AdbPacket(AdbProtocol.OKAY, 19, open.arg0))
                if (wrongId) {
                    send(socket, AdbPacket(AdbProtocol.WRTE, 20, open.arg0, "bad".toByteArray()))
                    assertEquals(-1, socket.getInputStream().read())
                } else {
                    send(socket, AdbPacket(AdbProtocol.CLSE, 19, open.arg0))
                    assertEquals(AdbProtocol.CLSE, read(socket).command)
                }
            }.use { server ->
                connector(server).use { connector ->
                    connector.connect {}.use { session -> expectSuspendFailure(AdbFailureReason.ProtocolError) { session.execute("true") } }
                }
            }
        }
    }

    @Test fun abruptEofAndExcessOutputAreFailures() = runBlocking {
        for (overflow in listOf(false, true)) {
            FakeAdbd { socket ->
                read(socket)
                send(socket, connected())
                val open = read(socket)
                send(socket, AdbPacket(AdbProtocol.OKAY, 19, open.arg0))
                if (overflow) {
                    repeat(64) {
                        send(socket, AdbPacket(AdbProtocol.WRTE, 19, open.arg0, ByteArray(4096)))
                        assertEquals(AdbProtocol.OKAY, read(socket).command)
                    }
                    send(socket, AdbPacket(AdbProtocol.WRTE, 19, open.arg0, byteArrayOf(1)))
                    assertEquals(-1, socket.getInputStream().read())
                }
            }.use { server ->
                connector(server).use { connector ->
                    connector.connect {}.use { session ->
                        expectSuspendFailure(if (overflow) AdbFailureReason.ProtocolError else AdbFailureReason.Disconnected) { session.execute("true") }
                    }
                }
            }
        }
    }

    @Test fun authorizationTimeoutClosesSocket() = runBlocking {
        FakeAdbd { socket ->
            read(socket)
            send(socket, AdbPacket(AdbProtocol.AUTH, 1, 0, ByteArray(20)))
            read(socket)
            send(socket, AdbPacket(AdbProtocol.AUTH, 1, 0, ByteArray(20)))
            read(socket)
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            connector(server, 200).use { connector -> expectSuspendFailure(AdbFailureReason.AuthorizationTimeout) { connector.connect {} } }
        }
    }

    @Test fun commandTimeoutAndCancellationCloseBlockedRead() = runBlocking {
        for (cancel in listOf(false, true)) {
            val opened = CompletableDeferred<Unit>()
            FakeAdbd { socket ->
                read(socket)
                send(socket, connected())
                val open = read(socket)
                send(socket, AdbPacket(AdbProtocol.OKAY, 19, open.arg0))
                opened.complete(Unit)
                assertEquals(-1, socket.getInputStream().read())
            }.use { server ->
                connector(server).use { connector ->
                    connector.connect {}.use { session ->
                        if (cancel) {
                            val task = async { session.execute("true", 5000) }
                            opened.await()
                            task.cancelAndJoin()
                            assertTrue(task.isCancelled)
                        } else expectSuspendFailure(AdbFailureReason.CommandTimeout) { session.execute("true", 200) }
                    }
                }
            }
        }
    }

    @Test fun closingConnectorInterruptsAuthorization() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        FakeAdbd { socket ->
            read(socket)
            waiting.complete(Unit)
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            val connector = connector(server)
            val task = async { expectSuspendFailure(AdbFailureReason.Disconnected) { connector.connect {} } }
            waiting.await()
            connector.close()
            task.await()
            connector.close()
        }
    }

    @Test fun silentHandshakeIsNotReportedAsMissingAuthorization() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("opentv-handshake-test").toFile()
        val store = app.opentv.launcher.setup.SetupDiagnostics(dir, emptyMap())
        val attempt = store.start()
        var requested = false
        FakeAdbd { socket ->
            read(socket)
            assertEquals(-1, socket.getInputStream().read())
        }.use { server ->
            AndroidAdbConnector({ identity }, server.port, 200, diagnostic = attempt).use { connector ->
                expectSuspendFailure(AdbFailureReason.HandshakeTimeout) { connector.connect { requested = true } }
            }
        }
        assertFalse(requested)
        assertFalse(attempt.report().contains("KeySent"))
        store.awaitWrites()
        dir.deleteRecursively()
        Unit
    }

    @Test fun tlsRequirementProducesProtocolEvidenceWithoutPayload() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("opentv-tls-test").toFile()
        val store = app.opentv.launcher.setup.SetupDiagnostics(dir, emptyMap())
        val attempt = store.start()
        FakeAdbd { socket ->
            read(socket)
            send(socket, AdbPacket(AdbProtocol.STLS, 1, 0))
        }.use { server ->
            AndroidAdbConnector({ identity }, server.port, diagnostic = attempt).use { connector ->
                expectSuspendFailure(AdbFailureReason.TlsRequired) { connector.connect {} }
            }
        }
        assertTrue(attempt.report().contains("Stls"))
        store.awaitWrites()
        dir.deleteRecursively()
        Unit
    }

    @Test fun refusedConnectionPreservesItsCause() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        AndroidAdbConnector({ identity }, port).use { connector ->
            try {
                connector.connect {}
                fail("Expected connection refusal")
            } catch (e: AdbFailure) {
                assertEquals(AdbFailureReason.ConnectionRefused, e.reason)
                assertTrue(e.cause is java.net.ConnectException)
            }
        }
    }

    private fun connector(server: FakeAdbd, timeout: Long = 3000) = AndroidAdbConnector({ identity }, server.port, timeout)
    private fun connected() = AdbPacket(AdbProtocol.CNXN, 0x01000000, 4096, "device::\u0000".toByteArray())
    private fun expectFailure(reason: AdbFailureReason, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (failure: AdbFailure) { assertEquals(reason, failure.reason) }
    }
    private suspend fun expectSuspendFailure(reason: AdbFailureReason, action: suspend () -> Unit) {
        try { action(); fail("Expected $reason") } catch (failure: AdbFailure) { assertEquals(reason, failure.reason) }
    }

    private class FakeAdbd(action: (Socket) -> Unit) : java.io.Closeable {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        private val executor = Executors.newSingleThreadExecutor()
        private val work = executor.submit { server.accept().use { socket -> socket.soTimeout = 5000; action(socket) } }
        override fun close() {
            try { work.get(7, TimeUnit.SECONDS) } finally { server.close(); executor.shutdownNow() }
        }
    }

    companion object {
        private fun read(socket: Socket): AdbPacket {
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(24).also { input.readFully(it) }
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val length = buffer.int
            assertTrue(length in 0..4096)
            val checksum = buffer.int
            assertEquals(command.inv(), buffer.int)
            val bytes = ByteArray(length).also { input.readFully(it) }
            assertEquals(bytes.sumOf { it.toInt() and 255 }, checksum)
            return AdbPacket(command, arg0, arg1, bytes)
        }
        private fun send(socket: Socket, packet: AdbPacket, split: Boolean = false) {
            val bytes = AdbProtocol.encode(packet)
            if (split) for (byte in bytes) { socket.getOutputStream().write(byte.toInt() and 255); socket.getOutputStream().flush() }
            else { socket.getOutputStream().write(bytes); socket.getOutputStream().flush() }
        }
    }
}
