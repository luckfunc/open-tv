package app.opentv.launcher.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupControllerTest {
    private class Environment : SetupEnvironment {
        var acknowledged = false
        var configured = emptySet<SetupOperation>()
        var optionalOperations = emptySet<SetupOperation>()
        var guardBound = true
        var listenerBound = true
        var applySucceeds = true
        var readbackSucceeds = true
        var waitDuringWrite = false
        var snapshots = 0
        var snapshotFailure: AdbFailure? = null
        val writes = mutableListOf<SetupOperation>()
        val journals = mutableListOf<Map<SetupOperation, SetupOutcome>>()
        override fun acknowledged() = acknowledged
        override fun acknowledge() { acknowledged = true }
        override fun nativeHome() = false
        override fun legacyBehaviors() = false
        override suspend fun snapshot(session: AdbSession): SetupSnapshot {
            snapshots++
            snapshotFailure?.let { throw it }
            return SetupSnapshot(configured, optionalOperations, mapOf("accessibility" to "vendor/.Assistant"), guardBound, listenerBound)
        }
        override suspend fun apply(operation: SetupOperation, session: AdbSession): Boolean {
            check(journals.isNotEmpty())
            writes += operation
            if (waitDuringWrite) awaitCancellation()
            if (readbackSucceeds && applySucceeds) configured += operation
            return applySucceeds
        }
        override fun journal(before: SetupSnapshot, outcomes: Map<SetupOperation, SetupOutcome>) { journals += outcomes }
    }
    private class Connector : AdbConnector {
        var closed = false
        var sessionClosed = false
        var identityExit = 0
        val commands = mutableListOf<String>()
        override suspend fun connect(onAuthorizationRequested: () -> Unit): AdbSession {
            onAuthorizationRequested()
            return object : AdbSession {
                override suspend fun execute(command: String, timeoutMillis: Long): AdbCommandResult {
                    commands += command
                    return AdbCommandResult("2000\n", identityExit)
                }
                override fun close() { sessionClosed = true }
            }
        }
        override fun close() { closed = true }
    }

    @Test fun firstOpenSkipAndSettingsReopenHaveExplicitDestinations() = runTest {
        val environment = Environment()
        val controller = SetupController(this, { Connector() }, environment)
        assertTrue(controller.state.value is SetupState.Intro)
        controller.dismiss()
        assertTrue(environment.acknowledged)
        assertEquals(SetupState.Closed, controller.state.value)
        controller.open(SetupReturn.Personalization)
        assertEquals(SetupReturn.Personalization, controller.destination)
        assertTrue(controller.state.value is SetupState.Intro)
        controller.close()
        assertEquals(SetupState.Closed, SetupController(this, { Connector() }, environment).state.value)
    }

    @Test fun snapshotFailureAfterConnectKeepsConnectionEstablishedInBlockedState() = runTest {
        val environment = Environment().apply { snapshotFailure = AdbFailure(AdbFailureReason.ProtocolError) }
        val connector = Connector()
        val controller = SetupController(this, { connector }, environment)
        controller.check()
        runCurrent()
        assertEquals(SetupState.Blocked(AdbFailureReason.ProtocolError, connected = true), controller.state.value)
        assertTrue(connector.sessionClosed)
        assertTrue(connector.closed)
        assertTrue(environment.writes.isEmpty())
    }

    @Test fun connectingAndReviewNeverWriteAndConfirmIsStateGated() = runTest {
        val environment = Environment()
        val connector = Connector()
        val controller = SetupController(this, { connector }, environment)
        controller.confirm()
        controller.check()
        runCurrent()
        assertTrue(controller.state.value is SetupState.Review)
        assertEquals(listOf("id -u"), connector.commands)
        assertTrue(environment.writes.isEmpty())
        assertTrue(environment.journals.isEmpty())
        controller.confirm()
        runCurrent()
        assertEquals(listOf(SetupOperation.SecureSettings, SetupOperation.Overlay, SetupOperation.Notification, SetupOperation.HomeGuard), environment.writes)
        assertTrue(controller.state.value is SetupState.Result)
        assertTrue(connector.closed)
        assertTrue(connector.sessionClosed)
        controller.confirm()
        assertEquals(4, environment.writes.size)
    }

    @Test fun autoStartIsOneConsentedFeatureAndUnconfirmedSupportNeverWrites() = runTest {
        for (supported in listOf(false, true)) {
            val environment = Environment().apply {
                if (supported) optionalOperations = setOf(SetupOperation.AutoStart)
            }
            val controller = SetupController(this, { Connector() }, environment)
            controller.check(); runCurrent()
            assertTrue(environment.writes.isEmpty())
            controller.confirm(); runCurrent()
            val outcomes = (controller.state.value as SetupState.Result).report.outcomes
            assertEquals(if (supported) SetupOutcome.Verified else SetupOutcome.Unsupported, outcomes[SetupOperation.AutoStart])
            assertEquals(if (supported) 1 else 0, environment.writes.count { it == SetupOperation.AutoStart })
            controller.close()
        }
    }

    @Test fun serviceAccessRequiresReviewedSupportAndRunsBeforeSecureSettings() = runTest {
        for (supported in listOf(false, true)) {
            val environment = Environment().apply {
                if (supported) optionalOperations = setOf(SetupOperation.ServiceAccess)
            }
            val controller = SetupController(this, { Connector() }, environment)
            controller.check(); runCurrent()
            assertTrue(environment.writes.isEmpty())
            if (!supported) environment.optionalOperations = setOf(SetupOperation.ServiceAccess)
            controller.confirm(); runCurrent()
            val report = (controller.state.value as SetupState.Result).report
            assertEquals(if (supported) SetupOutcome.Verified else SetupOutcome.Unsupported, report.outcomes[SetupOperation.ServiceAccess])
            assertEquals(supported, SetupOperation.ServiceAccess in environment.writes)
            if (supported) assertEquals(SetupOperation.ServiceAccess, environment.writes.first())
        }
    }

    @Test fun configuredButUnboundGuardIsRecoveredAndBindingWaitIsBounded() = runTest {
        val environment = Environment().apply {
            configured = SetupOperation.entries.filterNot { it.optional }.toSet()
            guardBound = false
        }
        val controller = SetupController(this, { Connector() }, environment)
        controller.check(); runCurrent(); controller.confirm(); runCurrent()
        assertEquals(listOf(SetupOperation.HomeGuard), environment.writes)
        assertTrue(controller.state.value is SetupState.Applying)
        advanceUntilIdle()
        assertEquals(3000L, testScheduler.currentTime)
        assertFalse((controller.state.value as SetupState.Result).report.snapshot!!.guardBound)
    }

    @Test fun nonzeroIdentityExitCannotReachReviewOrWrites() = runTest {
        val environment = Environment()
        val connector = Connector().apply { identityExit = 1 }
        val controller = SetupController(this, { connector }, environment)
        controller.check()
        runCurrent()
        assertTrue(controller.state.value is SetupState.Blocked)
        controller.confirm()
        assertTrue(environment.writes.isEmpty())
        assertTrue(connector.closed)
    }

    @Test fun successfulCommandWithoutReadbackIsFailureAndStopsDependentGrants() = runTest {
        val environment = Environment().apply { readbackSucceeds = false }
        val controller = SetupController(this, { Connector() }, environment)
        controller.check(); runCurrent(); controller.confirm(); runCurrent()
        val result = controller.state.value as SetupState.Result
        assertEquals(SetupOutcome.Failed, result.report.outcomes[SetupOperation.SecureSettings])
        assertEquals(listOf(SetupOperation.SecureSettings), environment.writes)
    }

    @Test fun failedCommandCannotBecomeVerifiedEvenWhenReadbackWasPreviouslySet() = runTest {
        val environment = Environment().apply { applySucceeds = false }
        val controller = SetupController(this, { Connector() }, environment)
        controller.check(); runCurrent(); controller.confirm(); runCurrent()
        assertEquals(SetupOutcome.Failed, (controller.state.value as SetupState.Result).report.outcomes[SetupOperation.SecureSettings])
    }

    @Test fun cancelClosesSessionRetainsUncertainResultAndRetryRequiresNewReview() = runTest {
        val environment = Environment().apply { waitDuringWrite = true }
        val connections = mutableListOf<Connector>()
        val controller = SetupController(this, { Connector().also(connections::add) }, environment)
        controller.check(); runCurrent(); controller.confirm(); runCurrent()
        assertTrue(controller.state.value is SetupState.Applying)
        controller.cancel(); runCurrent()
        val result = controller.state.value as SetupState.Result
        assertTrue(result.report.cancelled)
        assertEquals(SetupOutcome.Uncertain, result.report.outcomes[SetupOperation.SecureSettings])
        assertEquals(1, environment.writes.size)
        assertTrue(connections.first().closed)
        assertTrue(connections.first().sessionClosed)
        controller.check(); runCurrent()
        assertTrue(controller.state.value is SetupState.Review)
        assertEquals(1, environment.writes.size)
        controller.close()
    }

    @Test fun retryReadsCurrentStateAndDoesNotRepeatAlreadyAppliedWrites() = runTest {
        val environment = Environment()
        val controller = SetupController(this, { Connector() }, environment)
        controller.check(); runCurrent(); controller.confirm(); runCurrent()
        val writes = environment.writes.toList()
        controller.check(); runCurrent()
        assertEquals(writes, environment.writes)
        controller.confirm(); runCurrent()
        assertEquals(writes, environment.writes)
    }
}
