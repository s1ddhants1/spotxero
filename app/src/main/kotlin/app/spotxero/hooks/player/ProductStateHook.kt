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
        val methods = DexKitResolver.get<List<Method>>(DexKitResolver.KEY_PRODUCT_STATE_GETTERS)
        if (methods.isNullOrEmpty()) {
            module.log(Log.WARN, TAG, "Product state getters not resolved, skipping")
            return
        }

        for ((index, method) in methods.withIndex()) {
            module.deoptimize(method)
            module.hook(method)
                .setId("product_state_getter_$index")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    val key = chain.args.getOrNull(0) as? String ?: return@intercept result
                    val overridden = when (key) {
                        "type" -> "premium"
                        "catalogue" -> "premium"
                        "ads" -> "0"
                        "pick-and-shuffle" -> "true"
                        "horizon" -> "FULL"
                        "streaming-rules" -> ""
                        else -> null
                    }
                    overridden ?: result
                }
        }

        module.log(
            Log.INFO,
            TAG,
            "Hooked ${methods.size} product state getters: ${methods.joinToString { "${it.declaringClass.simpleName}#${it.name}" }}"
        )
    }
}
