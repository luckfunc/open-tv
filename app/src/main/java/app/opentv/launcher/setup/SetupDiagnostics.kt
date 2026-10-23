package app.opentv.launcher.setup

import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

internal enum class DiagnosticStage { Preparation, Identity, Connection, Handshake, Authorization, Read, Configure, Verify }
internal enum class DiagnosticEvent { Started, Connected, Cnxn, Auth, Stls, KeySent, Completed, Failed, Cancelled, Interrupted, Review, Result }
internal enum class DiagnosticCheck { ShellIdentity, RestrictedSettings, Overlay, AutoStart, SecureSettings, Notification, HomeGuard, Snapshot, Other }
internal enum class DiagnosticHint { PermissionDenied, Unsupported, MissingCommand, Other }

/** Only typed events and explicitly selected device metadata enter the shareable report. */
internal class SetupDiagnostics(private val directory: File, private val metadata: Map<String, String>) {
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "OpenTV-diagnostics").apply { isDaemon = true } }
    @Volatile var current: Attempt? = null
        private set

    private var lastTimestamp = 0L
    @Synchronized fun start(): Attempt = Attempt(maxOf(System.currentTimeMillis(), lastTimestamp + 1).also { lastTimestamp = it }).also { current = it; it.record(DiagnosticStage.Preparation, DiagnosticEvent.Started) }

    inner class Attempt internal constructor(private val timestamp: Long) {
        val id: String = UUID.randomUUID().toString().take(8)
        private val started = System.nanoTime()
        private val fileName = "$timestamp-$id.txt"
        private val entries = ArrayDeque<String>()
        private var terminal = false
        private var status = DiagnosticEvent.Started
        var stage = DiagnosticStage.Preparation
            private set
        var reason: AdbFailureReason? = null
            private set

        @Synchronized fun record(
            stage: DiagnosticStage, event: DiagnosticEvent, check: DiagnosticCheck? = null,
            failure: Throwable? = null, exitCode: Int? = null, hint: DiagnosticHint? = null,
            guardBound: Boolean? = null, listenerBound: Boolean? = null,
            outcome: SetupOutcome? = null, configured: Set<SetupOperation>? = null,
        ) {
            if (terminal) return
            this.stage = stage
            status = event
            if (failure is AdbFailure) reason = failure.reason
            val elapsed = (System.nanoTime() - started) / 1_000_000
            // Exception messages, command text, shell output and packet payloads are deliberately excluded.
            val exception = failure?.let { original ->
                generateSequence(original) { it.cause }.take(4).joinToString(" -> ") {
                    it.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(64)
                }
            }
            entries.addLast(buildString {
                append("+${elapsed}ms ${stage.name} ${event.name}")
                check?.let { append(" check=${it.name}") }
                reason?.let { append(" code=${it.code} reason=${it.name}") }
                exception?.let { append(" exception=$it") }
                exitCode?.let { append(" exit=$it") }
                hint?.let { append(" hint=${it.name}") }
                guardBound?.let { append(" guardBound=$it") }
                listenerBound?.let { append(" listenerBound=$it") }
                outcome?.let { append(" outcome=${it.name}") }
                configured?.let { append(" configured=${it.sortedBy { operation -> operation.name }.joinToString(",") { operation -> operation.name }}") }
            })
            while (entries.size > 96) entries.removeFirst()
            terminal = event in setOf(DiagnosticEvent.Failed, DiagnosticEvent.Cancelled, DiagnosticEvent.Interrupted, DiagnosticEvent.Result)
            val snapshot = report()
            writer.execute { persist(fileName, snapshot) }
        }

        @Synchronized fun report(): String = buildString {
            appendLine("OpenTV setup diagnostics v1")
            appendLine("Attempt: $id")
            appendLine("Started epoch ms: $timestamp")
            appendLine("Status: ${status.name}")
            appendLine("Stage: ${stage.name}")
            appendLine("Code: ${reason?.code ?: "-"}")
            appendLine("Connection target: device loopback / TCP 5555")
            metadata.forEach { (key, value) -> appendLine("$key: ${value.replace(Regex("[\\r\\n\\p{Cntrl}]"), " ").take(180)}") }
            appendLine("Data: selected device metadata and typed events only; no keys, addresses or shell output.")
            appendLine()
            entries.forEach { appendLine(it) }
        }
    }

    fun latestReport(): String? = current?.report() ?: runCatching {
        directory.listFiles { f -> f.name.endsWith(".txt") }?.maxByOrNull { it.name }
            ?.takeIf { it.length() <= 48 * 1024 }?.readText()
    }.getOrNull()

    private fun persist(name: String, report: String) {
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "$name.tmp")
            temp.writeText(report)
            if (!temp.renameTo(File(directory, name))) temp.delete()
            directory.listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        }
    }

    internal fun awaitWrites() { writer.submit {}.get() }

    companion object {
        fun check(command: String): DiagnosticCheck = when {
            command == "id -u" -> DiagnosticCheck.ShellIdentity
            "ACCESS_RESTRICTED_SETTINGS" in command -> DiagnosticCheck.RestrictedSettings
            "SYSTEM_ALERT_WINDOW" in command -> DiagnosticCheck.Overlay
            "WRITE_SECURE_SETTINGS" in command -> DiagnosticCheck.SecureSettings
            "notification" in command -> DiagnosticCheck.Notification
            "AUTO_START" in command || "ASSOC_START" in command -> DiagnosticCheck.AutoStart
            else -> DiagnosticCheck.Other
        }
        fun hint(output: String): DiagnosticHint = when {
            output.contains("permission", true) || output.contains("SecurityException") -> DiagnosticHint.PermissionDenied
            output.contains("unknown", true) || output.contains("unsupported", true) -> DiagnosticHint.Unsupported
            output.contains("not found", true) -> DiagnosticHint.MissingCommand
            else -> DiagnosticHint.Other
        }
    }
}
