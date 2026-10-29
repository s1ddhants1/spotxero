package app.spotxero.hooks.ads

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object AdManagerHook {

    private const val TAG = "SpotXero.AdManager"

    fun install(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_AD_MANAGER_IS_ENABLED)
        if (method == null) {
            module.log(Log.WARN, TAG, "Ad manager isEnabled not resolved, skipping L2 ad blocking")
            return
        }

        module.deoptimize(method)

        module.hook(method)
            .setId("ad_manager_is_enabled")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) {
                    false
                } else {
                    chain.proceed()
                }
            }

        module.log(Log.INFO, TAG, "Hooked ad manager isEnabled: ${method.declaringClass.name}#${method.name}")
    }
}
