package app.qcoldthrone.mobile.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import app.qcoldthrone.mobile.Action
import app.qcoldthrone.mobile.Key
import app.qcoldthrone.mobile.aidl.ISagerNetService
import app.qcoldthrone.mobile.aidl.ISagerNetServiceCallback
import app.qcoldthrone.mobile.aidl.SpeedDisplayData
import app.qcoldthrone.mobile.aidl.TrafficDataBatch
import app.qcoldthrone.mobile.bg.autoselector.AutoSelectorClient
import app.qcoldthrone.mobile.database.DataStore
import app.qcoldthrone.mobile.ktx.runOnDefaultDispatcher
import app.qcoldthrone.mobile.ktx.runOnMainDispatcher

class SagerConnection(
    private var connectionId: Int,
    private var listenForDeath: Boolean = false
) : ServiceConnection, IBinder.DeathRecipient {

    companion object {
        val serviceClass
            get() = when (DataStore.serviceMode) {
                Key.MODE_PROXY -> ProxyService::class
                Key.MODE_VPN -> VpnService::class
                else -> throw UnknownError()
            }.java

        const val CONNECTION_ID_SHORTCUT = 0
        const val CONNECTION_ID_TILE = 1
        const val CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND = 2
        const val CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND = 3
        const val CONNECTION_ID_RESTART_BG = 4
        const val CONNECTION_ID_AUTO_SELECTOR = 5
        const val CONNECTION_ID_OFFSITE = 6

        var restartingApp = false
    }

    interface Callback {
        // smaller ISagerNetServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        suspend fun cbTrafficUpdate(data: TrafficDataBatch) {}
        fun cbSelectorUpdate(id: Long) {}

        /** The auto-selector status changed; [AutoSelectorClient] already holds it. */
        fun cbAutoSelectorUpdate(json: String) {}

        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)

        fun onServiceConnected(service: ISagerNetService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    private var connectionActive = false
    private var callbackRegistered = false
    private var callback: Callback? = null
    private val serviceCallback = object : ISagerNetServiceCallback.Stub() {

        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            if (state < 0) return // skip private
            val s = BaseService.State.values()[state]
            DataStore.serviceState = s
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.stateChanged(s, profileName, msg)
            }
        }

        override fun cbSpeedUpdate(stats: SpeedDisplayData) {
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.cbSpeedUpdate(stats)
            }
        }

        override fun cbTrafficUpdate(stats: TrafficDataBatch) {
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.cbTrafficUpdate(stats)
            }
        }

        override fun cbSelectorUpdate(id: Long) {
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.cbSelectorUpdate(id)
            }
        }

        override fun cbAutoSelectorUpdate(json: String?) {
            AutoSelectorClient.update(json)
            val callback = callback ?: return
            runOnMainDispatcher {
                callback.cbAutoSelectorUpdate(json.orEmpty())
            }
        }

    }

    private var binder: IBinder? = null

    var service: ISagerNetService? = null

    fun updateConnectionId(id: Int) {
        connectionId = id
        try {
            service?.registerCallback(serviceCallback, id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        this.binder = binder
        val service = ISagerNetService.Stub.asInterface(binder)!!
        this.service = service
        try {
            if (listenForDeath) binder.linkToDeath(this, 0)
            check(!callbackRegistered)
            service.registerCallback(serviceCallback, connectionId)
            callbackRegistered = true
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        runOnDefaultDispatcher {
            runCatching { service.autoSelectorStatus(false) }.onSuccess(AutoSelectorClient::update)
        }
        callback?.onServiceConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        unregisterCallback()
        AutoSelectorClient.reset()
        callback?.onServiceDisconnected()
        service = null
        binder = null
    }

    override fun binderDied() {
        service = null
        callbackRegistered = false
        AutoSelectorClient.reset()
        if (!restartingApp) {
            callback?.also { runOnMainDispatcher { it.onBinderDied() } }
        }
    }

    private fun unregisterCallback() {
        val service = service
        if (service != null && callbackRegistered) try {
            service.unregisterCallback(serviceCallback)
        } catch (_: RemoteException) {
        }
        callbackRegistered = false
    }

    fun connect(context: Context, callback: Callback?) {
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        this.callback = callback
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        if (connectionActive) try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }   // ignore
        connectionActive = false
        if (listenForDeath) try {
            binder?.unlinkToDeath(this, 0)
        } catch (_: NoSuchElementException) {
        }
        binder = null
        service = null
        callback = null
    }
}
