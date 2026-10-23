package app.opentv.launcher.wallpaper

import androidx.test.core.app.ApplicationProvider
import app.opentv.launcher.setup.AndroidSetupDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.net.Socket
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticExportTest {
    @Test fun explicitExportHasSeparateReadOnlyTokenAndExpiresWithoutExposingPhoneSettings() {
        val server = PhoneServer(ApplicationProvider.getApplicationContext(), "wallpaper-only", { AutoCloseable {} }, port = 0)
        assertTrue(server.open())
        fun request(path: String, method: String = "GET"): String = Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
        try {
            assertTrue(request("/diagnostic-export/unknown/diagnostics").startsWith("HTTP/1.1 404"))
            val token = AndroidSetupDiagnostics.share("Report: </textarea><script>private()</script>")
            val path = "/diagnostic-export/$token"
            val html = request("$path/diagnostics")
            assertTrue(html.startsWith("HTTP/1.1 200"))
            assertTrue(html.contains("&lt;/textarea&gt;"))
            assertFalse(html.contains("<script>private()"))
            assertFalse(html.contains("wallpaper-only"))
            assertTrue(request("$path/diagnostics.txt").contains("attachment; filename=opentv-diagnostics.txt"))
            assertTrue(request("$path/api/state").startsWith("HTTP/1.1 404"))
            assertTrue(request("/$token/api/state").startsWith("HTTP/1.1 404"))
            assertTrue(request("$path/diagnostics", "POST").startsWith("HTTP/1.1 405"))
            AndroidSetupDiagnostics.finishSharing()
            ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
            assertTrue(request("$path/diagnostics").startsWith("HTTP/1.1 200"))
            ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
            assertTrue(request("$path/diagnostics.txt").startsWith("HTTP/1.1 404"))
        } finally { AndroidSetupDiagnostics.finishSharing(); server.close() }
    }
}
