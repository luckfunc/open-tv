package app.opentv.launcher.guard

/** Android 11 and older need filtering to avoid the system's background-start delay. */
internal object HomeKeyFilterPolicy {
    fun shouldFilter(sdk: Int, temporary: Boolean, persistent: Boolean): Boolean =
        sdk <= 30 || temporary || persistent
}
