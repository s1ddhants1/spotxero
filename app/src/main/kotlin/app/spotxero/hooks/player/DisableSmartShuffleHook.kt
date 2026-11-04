package app.spotxero.hooks.player

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object DisableSmartShuffleHook {

    fun install(module: XposedModule) {
        val methods = DexKitResolver.get<List<Method>>(DexKitResolver.KEY_SMART_SHUFFLE_FLAGS)
        if (methods.isNullOrEmpty()) {
            return
        }

        for ((index, method) in methods.withIndex()) {
            if (method.parameterCount != 0 || method.returnType != Boolean::class.javaPrimitiveType) {
                continue
            }
            module.deoptimize(method)
            module.hook(method)
                .setId("disable_smart_shuffle_$index")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        false
                    } else {
                        result
                    }
                }
        }
    }
}
