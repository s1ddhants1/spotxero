package app.spotxero

import android.content.SharedPreferences
import android.util.Log
import app.spotxero.core.HookRegistry
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.utils.core.CommonUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class SpotXeroModule : XposedModule() {

    companion object {
        const val TAG = "SpotXero"
    }

    private val forceStopListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PrefKeys.KEY_FORCE_STOP) {
            log(Log.INFO, TAG, "Force-stop signal received, terminating process")
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)
        }
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "SpotXero loaded into process: ${param.processName}")
        log(Log.INFO, TAG, "Framework: $frameworkName v$frameworkVersion (API: $apiVersion)")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!CommonUtils.SUPPORTED_PACKAGES.contains(param.packageName) || !param.isFirstPackage) {
            return
        }

        runCatching {
            val remotePrefs = getRemotePreferences(PrefKeys.PREFS_NAME)
            PreferencesManager.bindRemote(remotePrefs)
            remotePrefs.registerOnSharedPreferenceChangeListener(forceStopListener)
        }.onFailure {
            log(Log.ERROR, TAG, "Failed initializing remote preferences: ${it.message}", it)
        }

        runCatching {
            val appClass = param.classLoader.loadClass("android.app.Application")
            val attachMethod = appClass.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
            deoptimize(attachMethod)
            hook(attachMethod)
                .setId("app_attach_base_context_target_prefs")
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val ctx = chain.getArg(0) as? android.content.Context
                    if (ctx != null) {
                        PreferencesManager.initTarget(ctx)
                    }
                    chain.proceed()
                }

            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            deoptimize(onCreateMethod)
            hook(onCreateMethod)
                .setId("app_on_create_target_prefs")
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val app = chain.getThisObject() as? android.content.Context
                    if (app != null) {
                        PreferencesManager.initTarget(app)
                    }
                    chain.proceed()
                }
        }

        runCatching {
            System.loadLibrary("spotxero_native")
            log(Log.INFO, TAG, "Loaded native patch library")
        }.onFailure {
            log(Log.WARN, TAG, "Failed loading native patch library: ${it.message}", it)
        }

        runCatching {
            HookRegistry.resolveAndInstall(
                module = this,
                appInfo = param.applicationInfo,
                classLoader = param.classLoader
            )
        }.onFailure {
            log(Log.ERROR, TAG, "Failed installing hooks: ${it.message}", it)
        }
    }
}
