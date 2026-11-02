package app.spotxero.hooks.player

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

object ProductStateHook {

    val TYPED_SPOOFS: Map<String, Any> = mapOf(
        "ads" to false,
        "player-license" to "premium",
        "player-license-v2" to "premium",
        "shuffle" to false,
        "on-demand" to true,
        "streaming" to true,
        "pick-and-shuffle" to false,
        "streaming-rules" to "",
        "nft-disabled" to "1",
        "type" to "premium",
        "can_use_superbird" to true,
        "tablet-free" to false
    )

    val LEGACY_SPOOFS: Map<String, String> = TYPED_SPOOFS.mapValues { (_, value) ->
        (value as? Boolean)?.let { if (it) "1" else "0" } ?: value.toString()
    } + mapOf(
        "catalogue" to "premium",
        "horizon" to "FULL",
        "shuffle-restricted" to "0",
        "unlimited-skips" to "1"
    )

    fun install(module: XposedModule) {
        installMapSeam(module)
        installAttributeSeam(module)
    }

    private fun installMapSeam(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_PRODUCT_STATE_MAP) ?: return

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
                (map as Map<Any?, Any?>).toMutableMap().apply {
                    putAll(LEGACY_SPOOFS)
                }
            }
    }

    private fun installAttributeSeam(module: XposedModule) {
        val accessor = DexKitResolver.get<Method>(DexKitResolver.KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS)
        val valueField = DexKitResolver.get<Field>(DexKitResolver.KEY_PRODUCT_STATE_ATTRIBUTE_VALUE_FIELD)
        if (accessor == null || valueField == null) {
            return
        }

        module.deoptimize(accessor)
        module.hook(accessor)
            .setId("product_state_attribute_objects")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val map = result as? Map<*, *> ?: return@intercept result
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept result
                }
                map.forEach { (key, attribute) ->
                    val spoof = TYPED_SPOOFS[key as? String]
                    if (spoof != null && valueField.declaringClass.isInstance(attribute)) {
                        runCatching { valueField.set(attribute, spoof) }
                    }
                }
                result
            }
    }
}
