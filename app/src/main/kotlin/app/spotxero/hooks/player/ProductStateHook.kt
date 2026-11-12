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
        "streaming" to true,
        "streaming-rules" to "",
        "previous-streaming-rules" to "",
        "nft-disabled" to "1",
        "can_use_superbird" to true,
        "unrestricted" to true,
        "shuffle-eligible" to true,
        "high-bitrate" to true,
        "tablet-free" to false
    )

    val PLAYER_LOCAL_SPOOFS: Map<String, Any> = mapOf(
        "type" to "premium",
        "catalogue" to "premium",
        "player-license" to "premium",
        "player-license-v2" to "premium",
        "financial-product" to "pr:premium,tc:0"
    )

    val KEYS_TO_STRIP: Set<String> = setOf(
        "payment-state",
        "last-premium-activation-date",
        "on-demand-trial",
        "on-demand-trial-in-progress",
        "smart-shuffle",
        "at-signal",
        "strider-key",
        "is-eligible-for-trial",
        "is-eligible-for-upsell"
    )

    val MFT_FLAGS_TO_REMOVE: Set<String> = setOf(
        "is_remove_from_queue_enabled_for_mft_plus",
        "is_reordering_for_mft_plus_allowed",
        "enable_common_capping",
        "enable_pns_common_capping",
        "enable_pick_and_shuffle_common_capping",
        "enable_pick_and_shuffle_dynamic_cap",
        "enable_pick_and_shuffle_remote_cap_parameters",
        "pick_and_shuffle_timecap",
        "enable_repeat_track_limit",
        "enable_audiobooks_common_capping",
        "enable_free_on_demand_experiment",
        "enable_free_on_demand_context_menu_experiment",
        "enable_playback_timeout_service",
        "enable_playback_timeout_error_ui",
        "playback_timeout_action",
        "ad_clips_per_free_tier_ad_break",
        "ad_clips_per_podcast_segment"
    )

    private val RCS_SCOPED_OVERRIDES: Map<String, Any> = mapOf(
        "core-common-capping:enable_common_capping" to false,
        "android-libs-lyrics:enable_common_capping" to false,
        "core-player:enable_pick_and_shuffle_dynamic_cap" to false,
        "core-player:enable_pick_and_shuffle_remote_cap_parameters" to false,
        "core-player:enable_pick_and_shuffle_common_capping" to "Disabled",
        "core-player:pick_and_shuffle_timecap" to 1440,
        "android-feature-freeondemand:enable_free_on_demand_experiment" to false,
        "core-restrictions:enable_pns_common_capping" to "Disabled",
        "core-restrictions:enable_repeat_track_limit" to "Disabled",
        "core-restrictions:enable_audiobooks_common_capping" to "Disabled",
        "cpp-player-rendering-renderer:playback_timeout_action" to "Nothing",
        "core-list-player:ad_clips_per_free_tier_ad_break" to 0,
        "core-list-player:ad_clips_per_podcast_segment" to 0
    )

    private val RCS_INT_BY_NAME: Map<String, Int> = mapOf(
        "pick_and_shuffle_timecap" to 1440,
        "ad_clips_per_free_tier_ad_break" to 0,
        "ad_clips_per_podcast_segment" to 0
    )

    private val RCS_ENUM_BY_NAME: Map<String, String> = mapOf(
        "playback_timeout_action" to "Nothing"
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
        installRcsMapSeam(module)
        installRcsLookupSeam(module)
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
                    PLAYER_LOCAL_SPOOFS.forEach { (k, v) -> put(k, v.toString()) }
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

        val allSpoofs = TYPED_SPOOFS + PLAYER_LOCAL_SPOOFS

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
                            val spoof = allSpoofs[key as? String]
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

    private fun buildRcsValue(
        ctor: java.lang.reflect.Constructor<*>,
        name: String,
        scope: String,
        value: Any
    ): Any? = runCatching {
        ctor.newInstance(
            name,
            scope,
            value as? Boolean,
            value as? Int,
            value as? String,
            0L,
            0L
        )
    }.getOrNull()

    private fun resolveRcsValueConstructor(): java.lang.reflect.Constructor<*>? {
        val lookup = DexKitResolver.get<Method>(DexKitResolver.KEY_RCS_PROPERTY_LOOKUP) ?: return null
        return lookup.returnType.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size == 7 &&
                p[0] == String::class.java &&
                p[1] == String::class.java &&
                p[2] == Boolean::class.javaObjectType &&
                p[3] == Int::class.javaObjectType &&
                p[4] == String::class.java &&
                p[5] == Long::class.javaPrimitiveType &&
                p[6] == Long::class.javaPrimitiveType
        }?.apply { isAccessible = true }
    }

    private fun installRcsMapSeam(module: XposedModule) {
        val loader = DexKitResolver.get<Method>(DexKitResolver.KEY_RCS_MAP_LOADER) ?: return
        val r27Ctor = resolveRcsValueConstructor() ?: return
        val r27Class = r27Ctor.declaringClass
        val boolField = r27Class.declaredFields.firstOrNull { it.type == Boolean::class.javaObjectType }?.apply { isAccessible = true }
        val intField = r27Class.declaredFields.firstOrNull { it.type == Int::class.javaObjectType }?.apply { isAccessible = true }
        val stringFields = r27Class.declaredFields.filter { it.type == String::class.java }.onEach { it.isAccessible = true }

        val snapshotClass = loader.returnType
        val snapshotCtor = snapshotClass.declaredConstructors.firstOrNull { c ->
            c.parameterCount == 2 && !c.parameterTypes[0].isPrimitive && Map::class.java.isAssignableFrom(c.parameterTypes[1])
        }?.apply { isAccessible = true } ?: return
        val headerField = snapshotClass.declaredFields.firstOrNull { it.type == snapshotCtor.parameterTypes[0] }?.apply { isAccessible = true } ?: return
        val mapField = snapshotClass.declaredFields.firstOrNull { Map::class.java.isAssignableFrom(it.type) }?.apply { isAccessible = true } ?: return

        module.deoptimize(loader)
        module.hook(loader)
            .setId("rcs_map_loader")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val snapshot = chain.proceed() ?: return@intercept null
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept snapshot
                }
                val origHeader = headerField.get(snapshot) ?: return@intercept snapshot
                @Suppress("UNCHECKED_CAST")
                val origMap = mapField.get(snapshot) as? Map<String, Any?> ?: return@intercept snapshot
                val patched = HashMap<String, Any?>(origMap.size + RCS_SCOPED_OVERRIDES.size)
                patched.putAll(origMap)

                for ((fullKey, existing) in origMap) {
                    val sep = fullKey.indexOf(':')
                    if (sep <= 0 || existing == null) continue
                    val scope = fullKey.substring(0, sep)
                    val name = fullKey.substring(sep + 1)
                    if (!MFT_FLAGS_TO_REMOVE.contains(name)) continue
                    val overrideVal: Any? = when {
                        boolField?.get(existing) != null -> false
                        intField?.get(existing) != null -> RCS_INT_BY_NAME[name]
                        stringFields.count { it.get(existing) != null } >= 3 -> RCS_ENUM_BY_NAME[name] ?: "Disabled"
                        else -> null
                    }
                    if (overrideVal != null) {
                        buildRcsValue(r27Ctor, name, scope, overrideVal)?.let { patched[fullKey] = it }
                    }
                }

                for ((fullKey, value) in RCS_SCOPED_OVERRIDES) {
                    val sep = fullKey.indexOf(':')
                    val scope = fullKey.substring(0, sep)
                    val name = fullKey.substring(sep + 1)
                    buildRcsValue(r27Ctor, name, scope, value)?.let { patched[fullKey] = it }
                }

                snapshotCtor.newInstance(origHeader, java.util.Collections.unmodifiableMap(patched))
            }
    }

    private fun installRcsLookupSeam(module: XposedModule) {
        val lookup = DexKitResolver.get<Method>(DexKitResolver.KEY_RCS_PROPERTY_LOOKUP) ?: return
        val r27Ctor = resolveRcsValueConstructor() ?: return

        module.deoptimize(lookup)
        module.hook(lookup)
            .setId("rcs_property_lookup")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept result
                }
                val scope = chain.getArg(0) as? String ?: return@intercept result
                val name = chain.getArg(1) as? String ?: return@intercept result
                val overrideVal = RCS_SCOPED_OVERRIDES["$scope:$name"] ?: return@intercept result
                buildRcsValue(r27Ctor, name, scope, overrideVal) ?: result
            }
    }
}
