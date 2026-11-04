package app.spotxero

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.spotxero.core.HookRegistry
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.utils.core.CommonUtils
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.Collections
import java.util.WeakHashMap
import kotlin.system.exitProcess

class SpotXeroModule : XposedModule() {

    companion object {
        const val TAG = "SpotXero"
        const val ACTION_FORCE_STOP = "app.spotxero.FORCE_STOP"
    }

    @Volatile
    private var appContext: Context? = null

    private val activeActivities: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())
    private val activeServices: MutableSet<Service> = Collections.newSetFromMap(WeakHashMap())

    private fun terminateProcess(context: Context?) {
        val ctx = context ?: appContext ?: runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null) as? Context
            if (currentApp != null) currentApp else {
                val at = atClass.getMethod("currentActivityThread").invoke(null)
                atClass.getMethod("getApplication").invoke(at) as? Context
            }
        }.getOrNull()

        for (activity in activeActivities.toList()) {
            runCatching {
                activity.finishAffinity()
                activity.finishAndRemoveTask()
            }
        }
        for (service in activeServices.toList()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    service.stopForeground(true)
                }
                service.stopSelf()
            }
        }
        runCatching {
            val nm = ctx?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        }
        runCatching {
            val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            @Suppress("DEPRECATION")
            am?.abandonAudioFocus(null)
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private val forceStopListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PrefKeys.KEY_FORCE_STOP) {
            terminateProcess(appContext)
        }
    }

    private val forceStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FORCE_STOP) {
                setResultCode(Activity.RESULT_OK)
                val targetCtx = context ?: appContext
                Thread {
                    SystemClock.sleep(50)
                    terminateProcess(targetCtx)
                }.start()
            }
        }
    }

    private fun registerReceiverSafely(context: Context) {
        val targetCtx = context.applicationContext ?: context
        appContext = targetCtx
        runCatching {
            val filter = IntentFilter(ACTION_FORCE_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetCtx.registerReceiver(forceStopReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                targetCtx.registerReceiver(forceStopReceiver, filter)
            }
        }
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!CommonUtils.SUPPORTED_PACKAGES.contains(param.packageName) || !param.isFirstPackage) {
            return
        }

        runCatching {
            val remotePrefs = getRemotePreferences(PrefKeys.PREFS_NAME)
            PreferencesManager.bindRemote(remotePrefs)
            remotePrefs.registerOnSharedPreferenceChangeListener(forceStopListener)
        }

        runCatching {
            val appClassName = param.applicationInfo.className ?: "android.app.Application"
            val appClass = runCatching { param.classLoader.loadClass(appClassName) }.getOrElse {
                param.classLoader.loadClass("android.app.Application")
            }
            val attachMethod = runCatching {
                appClass.getDeclaredMethod("attachBaseContext", Context::class.java)
            }.getOrElse {
                param.classLoader.loadClass("android.app.Application")
                    .getDeclaredMethod("attachBaseContext", Context::class.java)
            }
            deoptimize(attachMethod)
            hook(attachMethod)
                .setId("app_attach_base_context_target_prefs")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val ctx = chain.getArg(0) as? Context
                    if (ctx != null) {
                        registerReceiverSafely(ctx)
                        PreferencesManager.initTarget(ctx)
                    }
                    chain.proceed()
                }

            val onCreateMethod = runCatching {
                appClass.getDeclaredMethod("onCreate")
            }.getOrElse {
                param.classLoader.loadClass("android.app.Application")
                    .getDeclaredMethod("onCreate")
            }
            deoptimize(onCreateMethod)
            hook(onCreateMethod)
                .setId("app_on_create_target_prefs")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val app = chain.getThisObject() as? Context
                    if (app != null) {
                        registerReceiverSafely(app)
                        PreferencesManager.initTarget(app)
                    }
                    val application = chain.getThisObject() as? Application
                    application?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                            activeActivities.add(activity)
                        }
                        override fun onActivityStarted(activity: Activity) {
                            activeActivities.add(activity)
                        }
                        override fun onActivityResumed(activity: Activity) {
                            activeActivities.add(activity)
                        }
                        override fun onActivityPaused(activity: Activity) {}
                        override fun onActivityStopped(activity: Activity) {}
                        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                        override fun onActivityDestroyed(activity: Activity) {
                            activeActivities.remove(activity)
                        }
                    })
                    chain.proceed()
                }
        }

        runCatching {
            val serviceClass = param.classLoader.loadClass("android.app.Service")
            val serviceOnCreate = serviceClass.getDeclaredMethod("onCreate")
            deoptimize(serviceOnCreate)
            hook(serviceOnCreate)
                .setId("service_on_create_tracker")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val service = chain.getThisObject() as? Service
                    if (service != null) {
                        activeServices.add(service)
                        registerReceiverSafely(service)
                    }
                    chain.proceed()
                }

            val serviceOnDestroy = serviceClass.getDeclaredMethod("onDestroy")
            deoptimize(serviceOnDestroy)
            hook(serviceOnDestroy)
                .setId("service_on_destroy_tracker")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val service = chain.getThisObject() as? Service
                    if (service != null) {
                        activeServices.remove(service)
                    }
                    chain.proceed()
                }
        }

        runCatching {
            System.loadLibrary("spotxero_native")
        }

        runCatching {
            HookRegistry.resolveAndInstall(
                module = this,
                appInfo = param.applicationInfo,
                classLoader = param.classLoader
            )
            log(Log.INFO, TAG, "Hooks injected")
        }
    }
}
