package app.spotxero

import android.app.Application
import android.util.Log
import app.spotxero.core.PreferencesManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class SpotXeroApplication : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "SpotXero.App"

        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceConnectionListener>()

        fun addConnectionListener(listener: ServiceConnectionListener, notifyImmediately: Boolean = true) {
            listeners.add(listener)
            if (notifyImmediately) {
                listener.onServiceConnected(service)
            }
        }

        fun removeConnectionListener(listener: ServiceConnectionListener) {
            listeners.remove(listener)
        }
    }

    interface ServiceConnectionListener {
        fun onServiceConnected(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.initLocal(this)

        try {
            XposedServiceHelper.registerListener(this)
            Log.i(TAG, "Registered XposedServiceHelper listener successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed registering XposedServiceHelper: ${t.message}", t)
        }
    }

    override fun onServiceBind(service: XposedService) {
        Log.i(TAG, "XposedService bound: ${service.frameworkName} v${service.frameworkVersion} (API: ${service.apiVersion})")
        Companion.service = service
        PreferencesManager.bindServiceRemote(this)
        listeners.forEach {
            runCatching { it.onServiceConnected(service) }
        }
    }

    override fun onServiceDied(service: XposedService) {
        Log.w(TAG, "XposedService connection lost")
        if (Companion.service == service) {
            Companion.service = null
            listeners.forEach {
                runCatching { it.onServiceConnected(null) }
            }
        }
    }
}
