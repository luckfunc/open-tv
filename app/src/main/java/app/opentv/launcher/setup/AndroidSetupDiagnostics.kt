package app.opentv.launcher.setup

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.opentv.launcher.BuildConfig
import java.io.File

/** App-private history and an explicit, expiring export snapshot shared by TV and LAN UI. */
internal object AndroidSetupDiagnostics {
    private var instance: SetupDiagnostics? = null
    private var shared: String? = null
    private var expiresAt = 0L
    private var exportToken: String? = null

    @Synchronized fun get(context: Context): SetupDiagnostics = instance ?: SetupDiagnostics(
        File(context.noBackupFilesDir, "setup-diagnostics"),
        linkedMapOf("App" to BuildConfig.VERSION_NAME, "Variant" to BuildConfig.FLAVOR,
            "Manufacturer" to Build.MANUFACTURER, "Model" to Build.MODEL,
            "Android" to Build.VERSION.RELEASE, "SDK" to Build.VERSION.SDK_INT.toString(),
            "Firmware" to Build.DISPLAY, "Incremental" to Build.VERSION.INCREMENTAL),
    ).also { instance = it }

    @Synchronized fun share(report: String): String {
        shared = report
        expiresAt = Long.MAX_VALUE
        return java.util.UUID.randomUUID().toString().also { exportToken = it }
    }
    @Synchronized fun finishSharing() { expiresAt = SystemClock.elapsedRealtime() + 5 * 60_000 }
    @Synchronized fun sharedReport(token: String): String? = if (token != exportToken) null else if (SystemClock.elapsedRealtime() < expiresAt) shared else {
        shared = null
        null
    }
}
