package app.spotxero.hooks.ads

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object ProductStateOverrideHook {

    private const val TAG = "SpotXero.ProductState"

    private val OVERRIDES = mapOf(
        "ads" to "0",
        "catalogue" to "premium",
        "product" to "premium",
        "type" to "premium"
    )

    fun install(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_PRODUCT_STATE_GET)
        if (method == null) {
            module.log(Log.WARN, TAG, "Product state getter not resolved, skipping L1 ad blocking")
            return
        }

        module.hook(method)
            .setId("product_state_override")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) {
                    return@intercept result
                }
                val key = chain.getArg(0) as? String ?: return@intercept result
                OVERRIDES[key] ?: result
            }

        module.log(Log.INFO, TAG, "Hooked product state getter: ${method.declaringClass.name}#${method.name}")
    }
}
