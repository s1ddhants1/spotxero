package app.spotxero.hooks.player

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.core.ProtoLists
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

object ProductStateHook {

    private const val TAG = "SpotXero.ProductState"

    private val BASE_TYPED_SPOOFS: Map<String, Any> = mapOf(
        "ads" to false,
        "shuffle" to false,
        "on-demand" to true,
        "pick-and-shuffle" to false,
        "streaming" to true,
        "streaming-rules" to "",
        "previous-streaming-rules" to "",
        "can_use_superbird" to true,
        "unrestricted" to true,
        "shuffle-eligible" to true,
        "high-bitrate" to true,
        "tablet-free" to false,
        "autoplay" to true,
        "player.autoplay" to true,
        "enable_has_lyrics_check_bypass" to true,
        "has_lyrics" to true,
        "android-integrity:validation_enabled" to false,
        "android-integrity:employee_validation_enabled" to false,
        "android-integrity:time_tracking_enabled" to false
    )

    private fun typedSpoofs(): Map<String, Any> {
        val hideTab = PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PREMIUM_TAB, true)
        return BASE_TYPED_SPOOFS + ("nft-disabled" to (if (hideTab) "1" else "0"))
    }

    private val PLAYER_LOCAL_SPOOFS: Map<String, Any> = mapOf(
        "type" to "premium",
        "catalogue" to "premium",
        "player-license" to "premium",
        "player-license-v2" to "premium",
        "financial-product" to "pr:premium,tc:0"
    )

    private val KEYS_TO_STRIP: Set<String> = setOf(
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

    private val MFT_FLAGS_TO_REMOVE: Set<String> = setOf(
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
        "core-list-player:ad_clips_per_podcast_segment" to 0,
        "core-player:autoplay" to true,
        "player:autoplay" to true,
        "core-player:player.autoplay" to true,
        "player:player.autoplay" to true,
        "android-feature-lyrics-repository:enable_has_lyrics_check_bypass" to true,
        "android-feature-lyrics:enable_has_lyrics_check_bypass" to true,
        "lyrics:enable_has_lyrics_check_bypass" to true,
        "enable_has_lyrics_check_bypass" to true,
        "android-feature-lyrics-npvelements:lyrics_entry_point_enabled" to true,
        "android-feature-lyrics-npvelements:lyrics_entry_point_button_v2_enabled" to true,
        "android-feature-lyrics-npvelements:shared_npv_lyrics_loading_enabled" to true
    )

    private val RCS_INTEGRITY_OVERRIDES: Map<String, Any> = mapOf(
        "android-integrity:validation_enabled" to false,
        "android-integrity:employee_validation_enabled" to false,
        "android-integrity:time_tracking_enabled" to false
    )

    private val RCS_ALL_OVERRIDES: Map<String, Any> = RCS_SCOPED_OVERRIDES + RCS_INTEGRITY_OVERRIDES

    private fun isPremiumActive(): Boolean =
        PreferencesManager.isUserLoggedIn() && PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)

    private val RCS_INT_BY_NAME: Map<String, Int> = mapOf(
        "pick_and_shuffle_timecap" to 1440,
        "ad_clips_per_free_tier_ad_break" to 0,
        "ad_clips_per_podcast_segment" to 0
    )

    private val RCS_ENUM_BY_NAME: Map<String, String> = mapOf(
        "playback_timeout_action" to "Nothing"
    )

    private val EXTRA_LEGACY_SPOOFS: Map<String, String> = mapOf(
        "horizon" to "FULL",
        "shuffle-restricted" to "0",
        "unlimited-skips" to "1"
    )

    private fun legacySpoofs(): Map<String, String> =
        typedSpoofs().mapValues { (_, value) ->
            (value as? Boolean)?.let { if (it) "1" else "0" } ?: value.toString()
        } + EXTRA_LEGACY_SPOOFS

    fun install(module: XposedModule) {
        installMapSeam(module)
        installAttributeSeam(module)
        installUcsAttributeSeam(module)
        installRcsConfigurationSeam(module)
        installRcsMapSeam(module)
        installRcsLookupSeam(module)
    }

    private fun installMapSeam(module: XposedModule) {
        val method = DexKitResolver.require<Method>(DexKitResolver.KEY_PRODUCT_STATE_MAP)

        module.deoptimize(method)
        module.hook(method)
            .setId("product_state_map")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val map = result as? Map<*, *> ?: return@intercept result
                if (!isPremiumActive() || map.isEmpty()) {
                    return@intercept result
                }
                @Suppress("UNCHECKED_CAST")
                (map as Map<Any?, Any?>).toMutableMap().apply {
                    KEYS_TO_STRIP.forEach { remove(it) }
                    putAll(legacySpoofs())
                    PLAYER_LOCAL_SPOOFS.forEach { (k, v) -> put(k, v.toString()) }
                }
            }
    }

    private fun installAttributeSeam(module: XposedModule) {
        val accessor = DexKitResolver.require<Method>(DexKitResolver.KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS)
        val valueField = DexKitResolver.require<Field>(DexKitResolver.KEY_PRODUCT_STATE_ATTRIBUTE_VALUE_FIELD)

        val accessors = attributeAccessorsOf(valueField.declaringClass, valueField)

        module.deoptimize(accessor)
        module.hook(accessor)
            .setId("product_state_attribute_objects")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val map = result as? Map<*, *> ?: return@intercept result
                if (!isPremiumActive() || map.isEmpty()) {
                    return@intercept result
                }
                val allSpoofs = typedSpoofs() + PLAYER_LOCAL_SPOOFS
                map.forEach { (key, attribute) ->
                    if (valueField.declaringClass.isInstance(attribute)) {
                        if (KEYS_TO_STRIP.contains(key)) {
                            runCatching {
                                accessors.valueCaseField?.set(attribute, 0)
                                valueField.set(attribute, null)
                            }
                        } else {
                            val spoof = allSpoofs[key as? String]
                            if (spoof != null) {
                                runCatching {
                                    writeAttributeValue(attribute, spoof, accessors)
                                }
                            }
                        }
                    }
                }
                result
            }
    }

    private fun installUcsAttributeSeam(module: XposedModule) {
        val getter = DexKitResolver.require<Method>(DexKitResolver.KEY_UCS_ACCOUNT_ATTRIBUTES_GETTER)
        module.deoptimize(getter)
        module.hook(getter)
            .setId("ucs_account_attributes_getter")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val originalMap = result as? Map<*, *> ?: return@intercept result
                if (!isPremiumActive() || originalMap.isEmpty()) {
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
                    val accessors = attributeAccessorsOf(attributeClass)
                    val allSpoofs = typedSpoofs() + PLAYER_LOCAL_SPOOFS
                    for ((key, value) in allSpoofs) {
                        val existing = mutableMap[key]
                        val target = existing ?: constructor?.newInstance() ?: continue
                        runCatching {
                            writeAttributeValue(target, value, accessors)
                            if (existing == null) {
                                mutableMap[key] = target
                            }
                        }
                    }
                }
                java.util.Collections.unmodifiableMap(mutableMap)
            }
    }

    private data class AttributeAccessors(
        val boolSetter: Method?,
        val stringSetter: Method?,
        val valueCaseField: Field?,
        val valueField: Field?
    )

    private fun attributeAccessorsOf(clazz: Class<*>, valueField: Field? = null): AttributeAccessors {
        val resolvedValueField = valueField ?: clazz.declaredFields.firstOrNull {
            it.type == Any::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
        return AttributeAccessors(
            clazz.declaredMethods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }?.apply { isAccessible = true },
            clazz.declaredMethods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }?.apply { isAccessible = true },
            clazz.declaredFields.firstOrNull {
                it.type == java.lang.Integer.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }?.apply { isAccessible = true },
            resolvedValueField
        )
    }

    private fun writeAttributeValue(target: Any?, spoof: Any, accessors: AttributeAccessors) {
        when (spoof) {
            is Boolean -> accessors.boolSetter?.invoke(target, spoof) ?: run {
                accessors.valueCaseField?.set(target, 2)
                accessors.valueField?.set(target, spoof)
            }
            is String -> accessors.stringSetter?.invoke(target, spoof) ?: run {
                accessors.valueCaseField?.set(target, 4)
                accessors.valueField?.set(target, spoof)
            }
            else -> accessors.stringSetter?.invoke(target, spoof.toString()) ?: run {
                accessors.valueCaseField?.set(target, 4)
                accessors.valueField?.set(target, spoof.toString())
            }
        }
    }

    private fun installRcsConfigurationSeam(module: XposedModule) {
        val getter = DexKitResolver.require<Method>(DexKitResolver.KEY_RCS_CONFIGURATION_ASSIGNED_VALUES)
        module.deoptimize(getter)
        module.hook(getter)
            .setId("rcs_configuration_assigned_values")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val list = result as? List<*> ?: return@intercept result
                if (!isPremiumActive()) {
                    return@intercept result
                }
                val filtered = ProtoLists.filter(list) { assignedValue ->
                    val propId = assignedValue.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.returnType.name == "com.spotify.rcs.admin.grpc.v0.PropertyDefinition\$Identifier"
                    }?.invoke(assignedValue)
                    if (propId == null) {
                        true
                    } else {
                        val name = propId.javaClass.getMethod("getName").invoke(propId) as? String
                        name == null || !MFT_FLAGS_TO_REMOVE.contains(name)
                    }
                }
                filtered ?: result
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

    private fun resolveRcsValueConstructor(): java.lang.reflect.Constructor<*> {
        val lookup = DexKitResolver.require<Method>(DexKitResolver.KEY_RCS_PROPERTY_LOOKUP)
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
        }?.apply { isAccessible = true } ?: error("Missing RCS value constructor")
    }

    private fun installRcsMapSeam(module: XposedModule) {
        val loader = DexKitResolver.require<Method>(DexKitResolver.KEY_RCS_MAP_LOADER)
        val rcsValueCtor = resolveRcsValueConstructor()
        val rcsValueClass = rcsValueCtor.declaringClass
        val boolField = rcsValueClass.declaredFields.firstOrNull { it.type == Boolean::class.javaObjectType }?.apply { isAccessible = true }
        val intField = rcsValueClass.declaredFields.firstOrNull { it.type == Int::class.javaObjectType }?.apply { isAccessible = true }
        val stringFields = rcsValueClass.declaredFields.filter { it.type == String::class.java }.onEach { it.isAccessible = true }

        val snapshotClass = loader.returnType
        val snapshotCtor = snapshotClass.declaredConstructors.firstOrNull { c ->
            c.parameterCount == 2 && !c.parameterTypes[0].isPrimitive && Map::class.java.isAssignableFrom(c.parameterTypes[1])
        }?.apply { isAccessible = true } ?: error("Missing RCS snapshot constructor")
        val headerField = snapshotClass.declaredFields.firstOrNull { it.type == snapshotCtor.parameterTypes[0] }?.apply { isAccessible = true }
            ?: error("Missing RCS snapshot header field")
        val mapField = snapshotClass.declaredFields.firstOrNull { Map::class.java.isAssignableFrom(it.type) }?.apply { isAccessible = true }
            ?: error("Missing RCS snapshot map field")

        module.deoptimize(loader)
        module.hook(loader)
            .setId("rcs_map_loader")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val snapshot = chain.proceed() ?: return@intercept null
                val origHeader = headerField.get(snapshot) ?: return@intercept snapshot
                @Suppress("UNCHECKED_CAST")
                val origMap = mapField.get(snapshot) as? Map<String, Any?> ?: return@intercept snapshot
                val applyPremium = isPremiumActive()
                val overrides = if (applyPremium) RCS_ALL_OVERRIDES else RCS_INTEGRITY_OVERRIDES
                val patched = HashMap<String, Any?>(origMap.size + overrides.size)
                patched.putAll(origMap)

                if (applyPremium) {
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
                            buildRcsValue(rcsValueCtor, name, scope, overrideVal)?.let { patched[fullKey] = it }
                        }
                    }
                }

                for ((fullKey, value) in overrides) {
                    val sep = fullKey.indexOf(':')
                    if (sep <= 0) continue
                    val scope = fullKey.substring(0, sep)
                    val name = fullKey.substring(sep + 1)
                    buildRcsValue(rcsValueCtor, name, scope, value)?.let { patched[fullKey] = it }
                }

                snapshotCtor.newInstance(origHeader, java.util.Collections.unmodifiableMap(patched))
            }
    }

    private fun installRcsLookupSeam(module: XposedModule) {
        val lookup = DexKitResolver.require<Method>(DexKitResolver.KEY_RCS_PROPERTY_LOOKUP)
        val rcsValueCtor = resolveRcsValueConstructor()

        module.deoptimize(lookup)
        module.hook(lookup)
            .setId("rcs_property_lookup")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val scope = chain.getArg(0) as? String ?: return@intercept result
                val name = chain.getArg(1) as? String ?: return@intercept result
                val scopedKey = "$scope:$name"
                val overrideVal = RCS_INTEGRITY_OVERRIDES[scopedKey]
                    ?: RCS_INTEGRITY_OVERRIDES[name]
                    ?: if (isPremiumActive()) {
                        RCS_SCOPED_OVERRIDES[scopedKey] ?: RCS_SCOPED_OVERRIDES[name]
                    } else {
                        null
                    }
                    ?: return@intercept result
                buildRcsValue(rcsValueCtor, name, scope, overrideVal) ?: result
            }
    }
}
