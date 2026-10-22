package app.opentv.launcher.wallpaper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSessionTest {
    private class FakeServer(val request: () -> AutoCloseable?, val id: Int) : PhoneSession.Server {
        var opens = 0
        var closes = 0
        var available = true
        var starts = true
        override fun open(): Boolean { opens++; return starts }
        override fun address(): String? = if (available) "http://phone/$id/" else null
        override fun close() { closes++ }
    }

    private class Fixture(scope: CoroutineScope) {
        val servers = mutableListOf<FakeServer>()
        var configure: (FakeServer) -> Unit = {}
        val session = PhoneSession(scope) { request ->
            FakeServer(request, servers.size).also { configure(it); servers.add(it) }
        }
        val server get() = servers.last()
    }

    @Test fun onlyTheLastOwnerStartsTheFiveMinuteGrace() = runTest {
        val f = Fixture(this)
        val first = f.session.acquire()
        val second = f.session.acquire()
        runCurrent()
        assertEquals("http://phone/0/", f.session.url.value)
        first.close()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(0, f.server.closes)
        second.close()
        runCurrent()
        advanceTimeBy(299_999)
        runCurrent()
        assertEquals(0, f.server.closes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, f.server.closes)
        assertNull(f.session.url.value)
    }

    @Test fun immediateReacquireCancelsGraceAndOldReleaseIsIdempotent() = runTest {
        val f = Fixture(this)
        val old = f.session.acquire()
        runCurrent()
        old.close()
        val replacement = f.session.acquire()
        old.close()
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, f.servers.size)
        assertEquals(1, f.server.opens)
        assertEquals(0, f.server.closes)
        replacement.close()
    }

    @Test fun anObsoleteQueuedDeadlineCannotStopTheReopenedSession() = runTest {
        val f = Fixture(this)
        val old = f.session.acquire()
        runCurrent()
        old.close()
        runCurrent()
        advanceTimeBy(300_000)
        val replacement = f.session.acquire()
        replacement.close()
        runCurrent()
        assertEquals(0, f.server.closes)
        advanceTimeBy(299_999)
        runCurrent()
        assertEquals(0, f.server.closes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, f.server.closes)
    }

    @Test fun simultaneousAcquiresStartOneService() = runTest {
        val f = Fixture(this)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val leases = executor.invokeAll(List(32) { Callable { f.session.acquire() } }).map { it.get() }
            runCurrent()
            assertEquals(1, f.servers.size)
            assertEquals(1, f.server.opens)
            leases.forEach { it.close() }
            runCurrent()
            advanceTimeBy(300_000)
            runCurrent()
            assertEquals(1, f.server.closes)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun requestsKeepTheServiceAndUrlAlivePastTheDeadline() = runTest {
        val f = Fixture(this)
        val owner = f.session.acquire()
        runCurrent()
        val first = f.server.request()!!
        val second = f.server.request()!!
        owner.close()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(0, f.server.closes)
        assertNotNull(f.session.url.value)
        first.close()
        first.close()
        assertEquals(0, f.server.closes)
        second.close()
        assertEquals(1, f.server.closes)
        assertNull(f.session.url.value)
    }

    @Test fun oldRequestsAndReleasesCannotAffectANewerServer() = runTest {
        val f = Fixture(this)
        val oldOwner = f.session.acquire()
        runCurrent()
        val oldServer = f.server
        val oldRequest = oldServer.request()!!
        oldRequest.close()
        oldOwner.close()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        val newOwner = f.session.acquire()
        runCurrent()
        assertEquals("http://phone/1/", f.session.url.value)
        assertNull(oldServer.request())
        oldOwner.close()
        oldRequest.close()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(0, f.server.closes)
        newOwner.close()
    }

    @Test fun reopeningWhileARequestOutlivesGraceResetsTheDeadline() = runTest {
        val f = Fixture(this)
        val owner = f.session.acquire()
        runCurrent()
        val request = f.server.request()!!
        owner.close()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        val reopened = f.session.acquire()
        request.close()
        runCurrent()
        assertEquals(0, f.server.closes)
        reopened.close()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, f.server.closes)
    }

    @Test fun releaseBeforeStartupStillCleansUpWithoutAScreenScope() = runTest {
        val f = Fixture(this)
        f.session.acquire().close()
        runCurrent()
        assertEquals(1, f.server.opens)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, f.server.closes)
        assertNull(f.session.url.value)
    }

    @Test fun failedStartupPublishesNoUrlAndANewAcquireRetries() = runTest {
        val f = Fixture(this)
        f.configure = { it.starts = false }
        val first = f.session.acquire()
        runCurrent()
        assertNull(f.session.url.value)
        assertEquals(1, f.server.closes)
        f.configure = {}
        val retry = f.session.acquire()
        runCurrent()
        assertEquals("http://phone/1/", f.session.url.value)
        first.close()
        retry.close()
    }

    @Test fun offlineAddressIsNotAdvertisedAndReacquireRefreshesTheAddress() = runTest {
        val f = Fixture(this)
        f.configure = { it.available = false }
        val first = f.session.acquire()
        runCurrent()
        assertNull(f.session.url.value)
        f.server.available = true
        val second = f.session.acquire()
        runCurrent()
        assertEquals("http://phone/0/", f.session.url.value)
        f.server.available = false
        val third = f.session.acquire()
        runCurrent()
        assertNull(f.session.url.value)
        first.close()
        second.close()
        third.close()
    }
}
