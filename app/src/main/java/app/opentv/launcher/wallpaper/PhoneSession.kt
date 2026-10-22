package app.opentv.launcher.wallpaper

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns the LAN server independently of the screens that advertise its address. */
class PhoneSession internal constructor(
    private val scope: CoroutineScope,
    private val graceMillis: Long = 5 * 60 * 1000L,
    private val createServer: (() -> AutoCloseable?) -> Server,
) {
    internal interface Server {
        fun open(): Boolean
        fun address(): String?
        fun close()
    }

    private class Session {
        var server: Server? = null
        val requests = mutableSetOf<Any>()
    }

    private class Grace {
        var shutdown: Job? = null
        var expired = false
    }

    private val lock = Any()
    private val owners = mutableSetOf<Any>()
    private var current: Session? = null
    private var grace: Grace? = null
    private val mutableUrl = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = mutableUrl.asStateFlow()

    fun acquire(): AutoCloseable = synchronized(lock) {
        val owner = Any()
        owners.add(owner)
        grace?.shutdown?.cancel()
        grace = null
        val session = current ?: Session().also { current = it }
        scope.launch {
            synchronized(lock) {
                if (current !== session) return@synchronized
                if (session.server == null) {
                    val server = createServer { beginRequest(session) }
                    if (!server.open()) {
                        server.close()
                        current = null
                        mutableUrl.value = null
                        return@synchronized
                    }
                    session.server = server
                }
                mutableUrl.value = session.server?.address()
            }
        }
        AutoCloseable { release(owner) }
    }

    private fun release(owner: Any) = synchronized(lock) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return@synchronized
        val pending = Grace()
        grace = pending
        pending.shutdown = scope.launch {
            delay(graceMillis)
            synchronized(lock) {
                if (grace !== pending) return@synchronized
                pending.expired = true
                stopIfIdle()
            }
        }
    }

    private fun beginRequest(session: Session): AutoCloseable? = synchronized(lock) {
        if (current !== session) return@synchronized null
        val request = Any()
        session.requests.add(request)
        AutoCloseable {
            synchronized(lock) {
                if (current === session && session.requests.remove(request)) stopIfIdle()
            }
        }
    }

    private fun stopIfIdle() {
        val session = current ?: return
        if (owners.isNotEmpty() || grace?.expired != true || session.requests.isNotEmpty()) return
        current = null
        grace = null
        mutableUrl.value = null
        session.server?.close()
    }

    companion object {
        private var applicationSession: PhoneSession? = null

        @Synchronized
        fun get(context: Context): PhoneSession = applicationSession ?: run {
            val app = context.applicationContext
            PhoneSession(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { request ->
                PhoneServer.create(app, request)
            }.also { applicationSession = it }
        }
    }
}
