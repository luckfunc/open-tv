package app.opentv.launcher.setup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ConnectException

class SetupDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun preservesFailureTypeWithoutExportingExceptionMessageOrShellOutput() {
        val store = SetupDiagnostics(temp.newFolder(), mapOf("Model" to "Test TV"))
        val attempt = store.start()
        attempt.record(DiagnosticStage.Connection, DiagnosticEvent.Failed,
            failure = AdbFailure(AdbFailureReason.ConnectionRefused, ConnectException("192.168.1.12 private-key-value")))
        val report = attempt.report()
        assertTrue(report.contains("C01"))
        assertTrue(report.contains("AdbFailure -> ConnectException"))
        assertFalse(report.contains("192.168.1.12"))
        assertFalse(report.contains("private-key-value"))
        attempt.record(DiagnosticStage.Read, DiagnosticEvent.Completed)
        assertEquals(report, attempt.report())
        store.awaitWrites()
    }

    @Test fun storesOnlyFiveBoundedAttemptsAndReloadsLatestAfterRestart() {
        val dir = temp.newFolder()
        val store = SetupDiagnostics(dir, emptyMap())
        repeat(6) {
            val attempt = store.start()
            repeat(110) { attempt.record(DiagnosticStage.Handshake, DiagnosticEvent.Auth) }
            attempt.record(DiagnosticStage.Authorization, DiagnosticEvent.Failed, failure = AdbFailure(AdbFailureReason.AuthorizationTimeout))
        }
        store.awaitWrites()
        assertEquals(5, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.all { it.length() < 48 * 1024 })
        val reloaded = SetupDiagnostics(dir, emptyMap()).latestReport()!!
        assertTrue(reloaded.contains("A01"))
        assertTrue(reloaded.lines().count { it.startsWith("+") } <= 96)
    }

    @Test fun oldAttemptCannotWriteIntoNewAttempt() {
        val store = SetupDiagnostics(temp.newFolder(), emptyMap())
        val old = store.start()
        val fresh = store.start()
        old.record(DiagnosticStage.Authorization, DiagnosticEvent.Cancelled)
        assertFalse(fresh.report().contains("Cancelled"))
        assertNotEquals(old.id, fresh.id)
        assertSame(fresh, store.current)
        store.awaitWrites()
    }

    @Test fun unwritableStorageDoesNotInterruptDiagnosis() {
        val file = temp.newFile()
        val store = SetupDiagnostics(File(file, "impossible"), emptyMap())
        store.start().record(DiagnosticStage.Handshake, DiagnosticEvent.Failed, failure = AdbFailure(AdbFailureReason.TlsRequired))
        store.awaitWrites()
        assertTrue(store.latestReport()!!.contains("H02"))
    }
}
