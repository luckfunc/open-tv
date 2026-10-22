package app.opentv.launcher.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.opentv.launcher.MainActivity

/**
 * After a real reboot: restore Home Guard, and if the firmware still hands Home to its own
 * launcher, open OpenTV. Starting an activity from a receiver needs the "display over other apps"
 * permission on Android 10+, requested after confirmation in the setup wizard.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the boot broadcasts only; anything else could use it to pull OpenTV forward.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (GuardSetup.isPaused(context)) return
        GuardSetup.ensureEnabled(context)
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        // Also when OpenTV is the default Home: some firmwares start their own launcher at boot
        // regardless (Skyworth/Coolita 8R145, issue #43).
        // The guard takes Home over itself once it can see what is in front: by the time this
        // arrives, an HDMI-CEC source may already have switched the TV to its input.
        if (HomeGuardService.isRunning) return
        runCatching {
            context.startActivity(MainActivity.bringBackIntent(context).putExtra(MainActivity.EXTRA_RESTORE, true))
        }
    }
}
