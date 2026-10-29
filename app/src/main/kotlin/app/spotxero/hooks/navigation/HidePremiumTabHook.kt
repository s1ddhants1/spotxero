package app.spotxero.hooks.navigation

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object HidePremiumTabHook {

    private const val TAG = "SpotXero.HidePremiumTab"

    fun install(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_PREMIUM_TAB_FLAG)
        if (method == null) {
            module.log(Log.WARN, TAG, "Premium tab flag not resolved, skipping")
            return
        }

        module.deoptimize(method)

        module.hook(method)
            .setId("hide_premium_tab")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PREMIUM_TAB, false)) {
                    false
                } else {
                    result
                }
            }

        module.log(Log.INFO, TAG, "Hooked premium tab flag: ${method.declaringClass.name}#${method.name}")
    }
}
