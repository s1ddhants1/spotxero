package app.spotxero.hooks.navigation

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object HidePremiumTabHook {

    fun install(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_PREMIUM_TAB_FLAG)
        if (method == null ||
            method.parameterCount != 0 ||
            method.returnType != Boolean::class.javaPrimitiveType
        ) {
            return
        }

        module.deoptimize(method)

        module.hook(method)
            .setId("hide_premium_tab")
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
