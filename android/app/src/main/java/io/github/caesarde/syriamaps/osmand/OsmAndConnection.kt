package io.github.caesarde.syriamaps.osmand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.osmand.aidlapi.IOsmAndAidlInterface

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val packageName: String) : ConnectionState

    /** OsmAnd was stopped or crashed; we try to rebind on the next action. */
    data object Disconnected : ConnectionState

    /** bindService refused: the installed OsmAnd has no AIDL V2 service (too old) or blocks binding. */
    data class Unsupported(val packageName: String) : ConnectionState
}

/** Outcome of one AIDL call. */
sealed interface AidlResult<out T> {
    data class Ok<T>(val value: T) : AidlResult<T>
    data object NotConnected : AidlResult<Nothing>

    /** RemoteException: OsmAnd died, or the method does not exist in this OsmAnd version. */
    data class Remote(val method: String, val message: String?) : AidlResult<Nothing>
    data class Security(val method: String, val message: String?) : AidlResult<Nothing>
}

/** Binds to OsmAnd's AIDL V2 service and serialises calls to it. */
class OsmAndConnection(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile private var service: IOsmAndAidlInterface? = null
    @Volatile private var boundPackage: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IOsmAndAidlInterface.Stub.asInterface(binder)
            _state.value = ConnectionState.Connected(name.packageName)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _state.value = ConnectionState.Disconnected
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            _state.value = ConnectionState.Disconnected
            unbindQuietly()
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            _state.value = ConnectionState.Unsupported(name.packageName)
            unbindQuietly()
        }
    }

    fun connect(packageName: String) {
        val current = _state.value
        if (boundPackage == packageName && (current is ConnectionState.Connected || current is ConnectionState.Connecting)) return
        disconnect()
        _state.value = ConnectionState.Connecting
        val intent = Intent(OsmAndApps.AIDL_SERVICE_ACTION).setPackage(packageName)
        val ok = try {
            appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        if (ok) {
            boundPackage = packageName
        } else {
            unbindQuietly()
            _state.value = ConnectionState.Unsupported(packageName)
        }
    }

    fun disconnect() {
        unbindQuietly()
        service = null
        _state.value = ConnectionState.Idle
    }

    private fun unbindQuietly() {
        if (boundPackage != null) {
            try {
                appContext.unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // not bound
            }
        }
        boundPackage = null
    }

    /** Runs [block] against the bound service on a background thread, one call at a time. */
    suspend fun <T> call(method: String, block: (IOsmAndAidlInterface) -> T): AidlResult<T> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val api = service ?: return@withLock AidlResult.NotConnected
                try {
                    AidlResult.Ok(block(api))
                } catch (e: SecurityException) {
                    AidlResult.Security(method, e.message)
                } catch (e: RemoteException) {
                    AidlResult.Remote(method, e.message)
                } catch (e: RuntimeException) {
                    // e.g. TransactionTooLargeException surfaces as RemoteException; others mean an API mismatch.
                    AidlResult.Remote(method, e.message)
                }
            }
        }
}
