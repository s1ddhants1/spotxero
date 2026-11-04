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
        "shuffle" to false,
        "on-demand" to true,
        "pick-and-shuffle" to false,
        "streaming-rules" to "",
        "previous-streaming-rules" to "",
        "nft-disabled" to "1",
        "can_use_superbird" to true,
        "unrestricted" to true,
        "shuffle-eligible" to true,
        "high-bitrate" to true,
        "tablet-free" to true
    )

    val KEYS_TO_STRIP: Set<String> = setOf(
        "payment-state",
        "last-premium-activation-date",
        "on-demand-trial",
        "on-demand-trial-in-progress",
        "smart-shuffle",
        "at-signal",
        "feature-set-id-masked",
        "strider-key",
        "is-eligible-for-trial",
        "is-eligible-for-upsell"
    )

    val MFT_FLAGS_TO_REMOVE: Set<String> = setOf(
        "enable_mft_plus_queue",
        "enable_mft_plus_extended_queue",
        "is_remove_from_queue_enabled_for_mft_plus",
        "is_reordering_for_mft_plus_allowed",
        "enable_common_capping",
        "enable_pns_common_capping",
        "enable_pick_and_shuffle_common_capping",
        "enable_pick_and_shuffle_dynamic_cap",
        "pick_and_shuffle_timecap",
        "enable_free_on_demand_experiment",
        "enable_free_on_demand_context_menu_experiment",
        "enable_playback_timeout_service",
        "enable_playback_timeout_error_ui"
    )

    val LEGACY_SPOOFS: Map<String, String> = TYPED_SPOOFS.mapValues { (_, value) ->
        (value as? Boolean)?.let { if (it) "1" else "0" } ?: value.toString()
    } + mapOf(
        "horizon" to "FULL",
        "shuffle-restricted" to "0",
        "unlimited-skips" to "1"
    )

    fun install(module: XposedModule) {
        installMapSeam(module)
        installAttributeSeam(module)
        installUcsAttributeSeam(module)
        installRcsConfigurationSeam(module)
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
                    KEYS_TO_STRIP.forEach { remove(it) }
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

        val attributeClass = valueField.declaringClass
        val boolSetter = attributeClass.declaredMethods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
        val stringSetter = attributeClass.declaredMethods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
        val valueCaseField = attributeClass.declaredFields.firstOrNull {
            it.type == java.lang.Integer.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }

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
                    if (attributeClass.isInstance(attribute)) {
                        if (KEYS_TO_STRIP.contains(key)) {
                            runCatching {
                                valueCaseField?.set(attribute, 0)
                                valueField.set(attribute, null)
                            }
                        } else {
                            val spoof = TYPED_SPOOFS[key as? String]
                            if (spoof != null) {
                                runCatching {
                                    when (spoof) {
                                        is Boolean -> boolSetter?.invoke(attribute, spoof) ?: run {
                                            valueCaseField?.set(attribute, 2)
                                            valueField.set(attribute, spoof)
                                        }
                                        is String -> stringSetter?.invoke(attribute, spoof) ?: run {
                                            valueCaseField?.set(attribute, 4)
                                            valueField.set(attribute, spoof)
                                        }
                                        else -> stringSetter?.invoke(attribute, spoof.toString()) ?: run {
                                            valueCaseField?.set(attribute, 4)
                                            valueField.set(attribute, spoof.toString())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                result
            }
    }

    private fun installUcsAttributeSeam(module: XposedModule) {
        val getter = DexKitResolver.get<Method>(DexKitResolver.KEY_UCS_ACCOUNT_ATTRIBUTES_GETTER) ?: return
        module.deoptimize(getter)
        module.hook(getter)
            .setId("ucs_account_attributes_getter")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val originalMap = result as? Map<*, *> ?: return@intercept result
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept result
                }
                val mutableMap = originalMap.toMutableMap()
                KEYS_TO_STRIP.forEach { mutableMap.remove(it) }
                val cl = getter.declaringClass.classLoader
                val attributeClass = runCatching {
                    Class.forName("com.spotify.useraccount.v1.AccountAttribute", false, cl)
                }.getOrNull()
                if (attributeClass != null) {
                    val constructor = runCatching {
                        attributeClass.getDeclaredConstructor().apply { isAccessible = true }
                    }.getOrNull()
                    val boolSetter = attributeClass.declaredMethods.firstOrNull {
                        it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
                    }?.apply { isAccessible = true }
                    val stringSetter = attributeClass.declaredMethods.firstOrNull {
                        it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
                    }?.apply { isAccessible = true }
                    val valueCaseField = attributeClass.declaredFields.firstOrNull {
                        it.type == java.lang.Integer.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
                    }?.apply { isAccessible = true }
                    val valueField = attributeClass.declaredFields.firstOrNull {
                        it.type == Any::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
                    }?.apply { isAccessible = true }

                    for ((key, value) in TYPED_SPOOFS) {
                        val existing = mutableMap[key]
                        val target = existing ?: constructor?.newInstance() ?: continue
                        runCatching {
                            when (value) {
                                is Boolean -> boolSetter?.invoke(target, value) ?: run {
                                    valueCaseField?.set(target, 2)
                                    valueField?.set(target, value)
                                }
                                is String -> stringSetter?.invoke(target, value) ?: run {
                                    valueCaseField?.set(target, 4)
                                    valueField?.set(target, value)
                                }
                                else -> stringSetter?.invoke(target, value.toString()) ?: run {
                                    valueCaseField?.set(target, 4)
                                    valueField?.set(target, value.toString())
                                }
                            }
                            if (existing == null) {
                                mutableMap[key] = target
                            }
                        }
                    }
                }
                mutableMap
            }
    }

    private fun installRcsConfigurationSeam(module: XposedModule) {
        val getter = DexKitResolver.get<Method>(DexKitResolver.KEY_RCS_CONFIGURATION_ASSIGNED_VALUES) ?: return
        module.deoptimize(getter)
        module.hook(getter)
            .setId("rcs_configuration_assigned_values")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val list = result as? List<*> ?: return@intercept result
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept result
                }
                list.filterNot { assignedValue ->
                    if (assignedValue == null) return@filterNot false
                    val propId = assignedValue.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.returnType.name == "com.spotify.rcs.admin.grpc.v0.PropertyDefinition\$Identifier"
                    }?.invoke(assignedValue) ?: return@filterNot false
                    val name = propId.javaClass.getMethod("getName").invoke(propId) as? String
                    name != null && MFT_FLAGS_TO_REMOVE.contains(name)
                }
            }
    }
}
