package app.spotxero.hooks.player

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object ProductStateHook {

    private const val TAG = "SpotXero.ProductState"

    fun install(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_PRODUCT_STATE_MAP)
        if (method == null) {
            module.log(Log.WARN, TAG, "Product state map getter not resolved, skipping")
            return
        }

        module.deoptimize(method)
        module.hook(method)
            .setId("product_state_map")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val map = result as? Map<*, *> ?: return@intercept result
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept result
                }
                @Suppress("UNCHECKED_CAST")
                val modified = (map as Map<Any?, Any?>).toMutableMap()
                modified["type"] = "premium"
                modified["catalogue"] = "premium"
                modified["ads"] = "0"
                modified["pick-and-shuffle"] = "0"
                modified["horizon"] = "FULL"
                modified["streaming-rules"] = ""
                modified
            }

        module.log(
            Log.INFO,
            TAG,
            "Hooked product state map: ${method.declaringClass.simpleName}#${method.name}"
        )
    }
}
