package app.spotxero.hooks.player

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object OnDemandInFreeHook {

    private const val TAG = "SpotXero.OnDemandInFree"

    fun install(module: XposedModule) {
        val methods = DexKitResolver.get<List<Method>>(DexKitResolver.KEY_ON_DEMAND_IN_FREE_GETTERS)
        if (methods.isNullOrEmpty()) {
            module.log(Log.WARN, TAG, "On demand in free getters not resolved, skipping")
            return
        }

        for ((index, method) in methods.withIndex()) {
            module.deoptimize(method)
            module.hook(method)
                .setId("on_demand_in_free_$index")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    if (method.returnType == java.lang.Boolean.TYPE) {
                        true
                    } else {
                        java.lang.Boolean.TRUE
                    }
                }
        }

        module.log(
            Log.INFO,
            TAG,
            "Hooked ${methods.size} on demand in free getters: ${methods.joinToString { "${it.declaringClass.simpleName}#${it.name}" }}"
        )
    }
}
