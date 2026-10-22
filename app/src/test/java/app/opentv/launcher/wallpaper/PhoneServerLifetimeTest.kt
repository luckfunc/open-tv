package app.opentv.launcher.wallpaper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneServerLifetimeTest {
    @Test fun aRealUploadConnectionSurvivesGraceUntilItsResponseIsSent() = runTest {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var server: PhoneServer
        val session = PhoneSession(this) { request ->
            PhoneServer(context, "upload-test", {
                request()?.let { lease ->
                    entered.countDown()
                    AutoCloseable {
                        try { lease.close() } finally { finished.countDown() }
                    }
                }
            }, port = 0).also { server = it }
        }
        val owner = session.acquire()
        runCurrent()
        try {
            Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.soTimeout = 5_000
                val output = socket.getOutputStream()
                output.write(("POST /upload-test/api/upload HTTP/1.1\r\n" +
                    "Host: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: 8\r\nConnection: close\r\n\r\nphoto=").toByteArray())
                output.flush()
                assertTrue("connection handler entered", entered.await(5, TimeUnit.SECONDS))
                owner.close()
                runCurrent()
                advanceTimeBy(300_000)
                runCurrent()
                assertTrue(server.isAlive)
                assertEquals(1L, finished.count)
                output.write("xx".toByteArray())
                output.flush()
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(response.contains("\"added\":0"))
                assertTrue("connection released", finished.await(5, TimeUnit.SECONDS))
                assertFalse(server.isAlive)
                assertNull(session.url.value)
                assertNull(server.address())
            }
        } finally {
            owner.close()
            server.close()
        }
    }

    @Test fun aDisconnectedUploadReleasesItsLease() = runTest {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        lateinit var server: PhoneServer
        val session = PhoneSession(this) { request ->
            PhoneServer(ApplicationProvider.getApplicationContext(), "disconnect-test", {
                request()?.let { lease ->
                    entered.countDown()
                    AutoCloseable {
                        try { lease.close() } finally { finished.countDown() }
                    }
                }
            }, port = 0).also { server = it }
        }
        val owner = session.acquire()
        runCurrent()
        try {
            Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.getOutputStream().write(("POST /disconnect-test/api/upload HTTP/1.1\r\n" +
                    "Host: localhost\r\nContent-Length: 8000\r\n\r\nx").toByteArray())
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                owner.close()
                runCurrent()
                advanceTimeBy(300_000)
                runCurrent()
                assertTrue(server.isAlive)
            }
            assertTrue("disconnection released", finished.await(5, TimeUnit.SECONDS))
            assertFalse(server.isAlive)
            assertNull(session.url.value)
        } finally {
            owner.close()
            server.close()
        }
    }
}
