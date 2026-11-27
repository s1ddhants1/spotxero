package app.spotxero

import android.app.Application
import android.content.Context
import android.util.Log
import app.spotxero.core.LSPatchHelper
import app.spotxero.core.PreferencesManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class SpotXeroApplication : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "SpotXero.App"
        private val serviceLock = Any()

        @Volatile
        private var rootService: XposedService? = null

        @Volatile
        private var lspatchService: XposedService? = null

        @Volatile
        private var preferLSPatch = false

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

        fun updatePreferLSPatch(context: Context, prefer: Boolean) {
            preferLSPatch = prefer
            selectActiveService(context)
        }

        private fun selectActiveService(context: Context) {
            val changed: Boolean
            val active = synchronized(serviceLock) {
                val selected = if (preferLSPatch) {
                    lspatchService ?: rootService
                } else {
                    rootService ?: lspatchService
                }
                changed = selected !== service
                if (changed) {
                    service = selected
                }
                selected
            }
            if (!changed) {
                return
            }
            if (active != null) {
                PreferencesManager.bindServiceRemote(context)
            }
            listeners.forEach {
                runCatching { it.onServiceConnected(active) }
            }
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
        LSPatchHelper.requestServicePush(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.i(TAG, "XposedService bound: ${service.frameworkName} v${service.frameworkVersion} (API: ${service.apiVersion})")
        val isLSPatch = service.frameworkName.contains("LSPatch", ignoreCase = true)
        synchronized(serviceLock) {
            if (isLSPatch) {
                lspatchService = service
            } else {
                rootService = service
            }
        }
        preferLSPatch = LSPatchHelper.hasManagerPatchedTarget(this)
        selectActiveService(this)
    }

    override fun onServiceDied(service: XposedService) {
        Log.w(TAG, "XposedService connection lost: ${service.frameworkName}")
        synchronized(serviceLock) {
            if (lspatchService === service) {
                lspatchService = null
            }
            if (rootService === service) {
                rootService = null
            }
        }
        selectActiveService(this)
    }
}
