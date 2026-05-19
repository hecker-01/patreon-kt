package com.patreonkt.server

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.patreonkt.db.PatreonDatabase

/**
 * Android [Service] that hosts the [BrowseServer] for its full lifecycle.
 *
 * Host apps bind to this service to start and stop the embedded HTTP server without worrying
 * about thread management or coroutine scope.
 *
 * ### Usage from an Activity
 * ```kotlin
 * val connection = object : ServiceConnection {
 *     override fun onServiceConnected(name: ComponentName, service: IBinder) {
 *         val binder = service as BrowseServerService.LocalBinder
 *         binder.startServer(8765)
 *     }
 *     override fun onServiceDisconnected(name: ComponentName) {}
 * }
 * bindService(Intent(this, BrowseServerService::class.java), connection, BIND_AUTO_CREATE)
 * ```
 *
 * The preferred entry point is [com.patreonkt.PatreonArchiver.startBrowseServer] which handles
 * binding automatically.
 */
class BrowseServerService : Service() {

    private var server: BrowseServer? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        /**
         * Starts the browse server on [port].
         * @return The actual port the server is listening on (useful when [port] is 0).
         */
        fun startServer(port: Int): Int {
            val db = PatreonDatabase.getInstance(applicationContext)
            val prefs = applicationContext.getSharedPreferences(
                "patreonkt_server_settings", MODE_PRIVATE
            )
            val srv = BrowseServer(port, db, prefs)
            srv.start(NanoHTTPD_SOCKET_READ_TIMEOUT, false)
            server = srv
            return srv.listeningPort
        }

        /** Stops the browse server if it is running. */
        fun stopServer() {
            server?.stop()
            server = null
        }

        /** Returns true if the server is currently running. */
        val isRunning: Boolean get() = server?.isAlive == true
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    companion object {
        private const val NanoHTTPD_SOCKET_READ_TIMEOUT = 5000
    }
}
