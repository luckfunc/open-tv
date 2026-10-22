package app.opentv.launcher.apps

/** Verified utility identities, not label guesses; these retain their complete native artwork. */
internal object UtilityIcons {
    private val verifiedUtilities = setOf(
        // Existing Xiaomi identities verified in the corresponding device notes.
        "com.xiaomi.mitv.settings",
        "com.mitv.gallery",
        "com.xiaomi.mitv.calendar",
        "com.xiaomi.tweather",
        "com.xiaomi.mitv.smartshare",
        "com.xiaomi.mitv.tvmanager",
        "com.xiaomi.mitv.appstore",
        "com.xiaomi.mitv.mediaexplorer",
        // Checked on FFALCON Smart TV Pro / T966D5, Android 14.
        // Updated vendor utilities may live on /data without FLAG_SYSTEM.
        "com.tcl.settings",
        "com.tcl.tvsmartalbum",
        "com.tcl.common.weather",
        "com.tcl.MultiScreenInteraction_TV",
        "com.tcl.guard",
        "com.tcl.appmarket2",
        "com.tcl.ui_mediaCenter",
        "com.tcl.browser",
        "com.tcl.appreciate.art",
        "com.tcl.c2dm.client",
        "com.tcl.playskill",
        "com.tcl.t_solo",
        "com.tcl.tliveplay",
        "com.tcl.usercenter",
        "com.tcl.walleve",
        "com.tcl.wholenetsearch",
        "com.tcl.ffeducation",
        "com.kookong.tvplus",
    )

    fun isVerifiedUtility(packageName: String): Boolean = packageName in verifiedUtilities
}
