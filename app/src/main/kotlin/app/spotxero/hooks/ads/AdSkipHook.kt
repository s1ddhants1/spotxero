package app.spotxero.hooks.ads

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object AdSkipHook {

    private const val TAG = "SpotXero.AdSkip"

    fun install(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_IS_ADVERTISEMENT_CHECK)
        if (method == null) {
            module.log(Log.WARN, TAG, "is_advertisement check not resolved, skipping L4 ad blocking")
            return
        }

        module.deoptimize(method)

        if (method.returnType == Boolean::class.javaPrimitiveType ||
            method.returnType == Boolean::class.javaObjectType
        ) {
            module.hook(method)
                .setId("ad_skip_boolean")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) {
                        false
                    } else {
                        chain.proceed()
                    }
                }
            module.log(Log.INFO, TAG, "Hooked ad detection (boolean): ${method.declaringClass.name}#${method.name}")
        } else {
            module.hook(method)
                .setId("ad_skip_general")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) {
                        return@intercept result
                    }
                    if (result is Map<*, *>) {
                        val mutable = result.toMutableMap()
                        mutable.remove("is_advertisement")
                        mutable
                    } else {
                        result
                    }
                }
            module.log(Log.INFO, TAG, "Hooked ad detection (general): ${method.declaringClass.name}#${method.name}")
        }
    }
}
