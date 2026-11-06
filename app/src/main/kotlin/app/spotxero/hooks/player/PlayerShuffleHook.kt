package app.spotxero.hooks.player

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object PlayerShuffleHook {

    private const val STATION_PREFIX = "spotify:station:"
    private const val BASE_PREFIX = "spotify:"

    @Volatile
    private var userShuffleState: Boolean? = null

    private fun isShuffleEnabled(): Boolean {
        val cached = userShuffleState
        if (cached != null) return cached
        val stored = PreferencesManager.isEnabled(PrefKeys.KEY_SHUFFLE_STATE, false)
        userShuffleState = stored
        return stored
    }

    fun install(module: XposedModule) {
        hookCommandValue(module)
        hookPlayerOptions(module)
        hookPreparePlayOptions(module)
        hookOptionOverrides(module)
        hookProtoOptions(module)
        hookPlaybackSettings(module)
        hookShuffleContext(module)
        hookContextCapability(module)
        hookClientBase(module)
        hookCoroutineClientBase(module)
    }

    private fun hookCommandValue(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_SET_SHUFFLE_COMMAND_VALUE)
        if (method != null) {
            module.deoptimize(method)
            module.hook(method)
                .setId("set_shuffle_command_value")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val value = chain.getArg(0) as? Boolean
                    if (value != null) {
                        userShuffleState = value
                        PreferencesManager.setBoolean(null, PrefKeys.KEY_SHUFFLE_STATE, value)
                    }
                    chain.proceed()
                }
        }

        val createMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_SET_SHUFFLE_COMMAND_CREATE)
        if (createMethod != null) {
            module.deoptimize(createMethod)
            module.hook(createMethod)
                .setId("set_shuffle_command_create")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val value = chain.getArg(0) as? Boolean
                    if (value != null) {
                        userShuffleState = value
                        PreferencesManager.setBoolean(null, PrefKeys.KEY_SHUFFLE_STATE, value)
                    }
                    chain.proceed()
                }
        }

        val valueGetter = DexKitResolver.get<Method>(DexKitResolver.KEY_SET_SHUFFLE_COMMAND_VALUE_GETTER)
        if (valueGetter != null) {
            module.deoptimize(valueGetter)
            module.hook(valueGetter)
                .setId("set_shuffle_command_value_getter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    isShuffleEnabled()
                }
        }
    }

    private fun hookPlayerOptions(module: XposedModule) {
        val getter = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_OPTIONS_SHUFFLE_GETTER)
        if (getter != null) {
            module.deoptimize(getter)
            module.hook(getter)
                .setId("player_options_shuffle_getter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    isShuffleEnabled()
                }
        }

        val builderSetter = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_OPTIONS_BUILDER_SHUFFLE)
        if (builderSetter != null) {
            module.deoptimize(builderSetter)
            module.hook(builderSetter)
                .setId("player_options_builder_shuffle")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    chain.proceed(arrayOf(isShuffleEnabled()))
                }
        }
    }

    private fun hookPreparePlayOptions(module: XposedModule) {
        val buildMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_PREPARE_PLAY_OPTIONS_BUILDER_BUILD)
        if (buildMethod != null) {
            module.deoptimize(buildMethod)
            module.hook(buildMethod)
                .setId("prepare_play_options_build")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        val builderObj = chain.getThisObject()
                        val cl = builderObj?.javaClass?.classLoader
                        if (builderObj != null && cl != null) {
                            runCatching {
                                applyPreparePlayOverrides(builderObj, cl, isShuffleEnabled())
                            }
                        }
                    }
                    chain.proceed()
                }
        }

        val suppGetter = DexKitResolver.get<Method>(DexKitResolver.KEY_PREPARE_PLAY_OPTIONS_SUPPRESSIONS_GETTER)
        if (suppGetter != null) {
            module.deoptimize(suppGetter)
            module.hook(suppGetter)
                .setId("prepare_play_options_suppressions_getter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    if (!isShuffleEnabled()) {
                        val thisObj = chain.getThisObject() ?: return@intercept result
                        val cl = thisObj.javaClass.classLoader
                        buildSuppressionsOptional(cl, result) ?: result
                    } else {
                        result
                    }
                }
        }
    }

    private fun hookOptionOverrides(module: XposedModule) {
        val builderSetter = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_OPTION_OVERRIDES_BUILDER_SHUFFLE)
        if (builderSetter != null) {
            module.deoptimize(builderSetter)
            module.hook(builderSetter)
                .setId("player_option_overrides_builder_shuffle")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    if (!isShuffleEnabled()) {
                        chain.proceed(arrayOf(java.lang.Boolean.FALSE))
                    } else {
                        chain.proceed()
                    }
                }
        }

        val overrideGetter = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_OPTION_OVERRIDES_SHUFFLE_GETTER)
        if (overrideGetter != null) {
            module.deoptimize(overrideGetter)
            module.hook(overrideGetter)
                .setId("player_option_overrides_shuffle_getter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    val thisObj = chain.getThisObject() ?: return@intercept result
                    val cl = thisObj.javaClass.classLoader
                    buildBooleanOptional(cl, result, isShuffleEnabled()) ?: result
                }
        }

        val buildMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_OPTION_OVERRIDES_BUILDER_BUILD)
        if (builderSetter == null || buildMethod == null) {
            return
        }

        module.deoptimize(buildMethod)
        module.hook(buildMethod)
            .setId("player_option_overrides_builder_build")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) && !isShuffleEnabled()) {
                    chain.getThisObject()?.let { builder -> builderSetter.invoke(builder, java.lang.Boolean.FALSE) }
                }
                chain.proceed()
            }
    }

    private fun hookProtoOptions(module: XposedModule) {
        val protoGetter = DexKitResolver.get<Method>(DexKitResolver.KEY_ES_CONTEXT_PLAYER_OPTIONS_SHUFFLE_GETTER)
        if (protoGetter != null) {
            module.deoptimize(protoGetter)
            module.hook(protoGetter)
                .setId("es_context_player_options_shuffle_getter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    isShuffleEnabled()
                }
        }

        val protoSetter = DexKitResolver.get<Method>(DexKitResolver.KEY_ES_OPTION_OVERRIDES_SHUFFLE_SETTER)
        if (protoSetter != null) {
            module.deoptimize(protoSetter)
            module.hook(protoSetter)
                .setId("es_option_overrides_shuffle_setter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    if (isShuffleEnabled()) {
                        return@intercept chain.proceed()
                    }
                    val optBool = chain.getArg(0)
                    if (optBool != null) {
                        val modified = runCatching {
                            val builder = optBool.javaClass.getMethod("toBuilder").apply { isAccessible = true }.invoke(optBool)
                            val setter = builder.javaClass.declaredMethods.firstOrNull {
                                it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                            }?.apply { isAccessible = true }
                            setter?.invoke(builder, false)
                            builder.javaClass.getMethod("build").apply { isAccessible = true }.invoke(builder)
                        }.getOrNull()
                        if (modified != null) {
                            return@intercept chain.proceed(arrayOf(modified))
                        }
                    }
                    chain.proceed()
                }
        }
    }

    private fun applyPreparePlayOverrides(builderObj: Any, cl: ClassLoader, shuffleEnabled: Boolean) {
        val overridesClass = Class.forName("com.spotify.player.model.command.options.PlayerOptionOverrides", false, cl)
        val suppressionsClass = Class.forName("com.spotify.player.model.Suppressions", false, cl)

        val currentOpt = getFieldValue(builderObj, "playerOptionsOverride")
        val currentOverride = if (currentOpt != null) {
            val hasValueMethod = currentOpt.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            if (hasValueMethod?.invoke(currentOpt) == true) {
                val getMethod = currentOpt.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.returnType != Boolean::class.javaPrimitiveType && it.returnType != Void.TYPE
                }?.apply { isAccessible = true }
                getMethod?.invoke(currentOpt)
            } else null
        } else null

        val newOverrides = if (currentOverride != null) {
            val toBuilderMethod = currentOverride.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name == "toBuilder"
            }?.apply { isAccessible = true } ?: currentOverride.javaClass.getMethod("toBuilder").apply { isAccessible = true }
            val toBuilder = toBuilderMethod.invoke(currentOverride)
            val shuffleMethod = toBuilder.javaClass.methods.firstOrNull {
                it.name == "shufflingContext" && it.parameterCount == 1
            }?.apply { isAccessible = true }
            shuffleMethod?.invoke(toBuilder, shuffleEnabled)
            val buildMethod = toBuilder.javaClass.methods.firstOrNull {
                it.name == "build" && it.parameterCount == 0
            }?.apply { isAccessible = true }
            buildMethod?.invoke(toBuilder)
        } else {
            val builderMethod = overridesClass.getMethod("builder").apply { isAccessible = true }
            val builder = builderMethod.invoke(null)
            val modesMethod = builder.javaClass.methods.firstOrNull {
                it.name == "modes" && it.parameterCount == 1
            }?.apply { isAccessible = true }
            modesMethod?.invoke(builder, emptyMap<String, String>())
            val shuffleMethod = builder.javaClass.methods.firstOrNull {
                it.name == "shufflingContext" && it.parameterCount == 1
            }?.apply { isAccessible = true }
            shuffleMethod?.invoke(builder, shuffleEnabled)
            val buildMethod = builder.javaClass.methods.firstOrNull {
                it.name == "build" && it.parameterCount == 0
            }?.apply { isAccessible = true }
            buildMethod?.invoke(builder)
        }
        if (newOverrides != null) {
            val setOverrideMethod = builderObj.javaClass.methods.firstOrNull {
                it.name == "playerOptionsOverride" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(newOverrides.javaClass)
            }?.apply { isAccessible = true }
            setOverrideMethod?.invoke(builderObj, newOverrides)
        }

        val licenseMethod = builderObj.javaClass.methods.firstOrNull {
            it.name == "license" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }?.apply { isAccessible = true }
        licenseMethod?.invoke(builderObj, "premium")

        if (!shuffleEnabled) {
            val suppOpt = getFieldValue(builderObj, "suppressions")
            val currentSuppressions = if (suppOpt != null) {
                val hasValueMethod = suppOpt.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
                }?.apply { isAccessible = true }
                if (hasValueMethod?.invoke(suppOpt) == true) {
                    val getMethod = suppOpt.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && it.returnType != Boolean::class.javaPrimitiveType && it.returnType != Void.TYPE
                    }?.apply { isAccessible = true }
                    getMethod?.invoke(suppOpt)
                } else null
            } else null

            val providers = mutableSetOf(
                "mft",
                "mft/inject_random_tracks",
                "mft/inject_filler_tracks",
                "mft/inject_track_on_start_play",
                "mft/disallow_restart_play"
            )
            if (currentSuppressions != null) {
                val providersMethod = currentSuppressions.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && Set::class.java.isAssignableFrom(it.returnType)
                }?.apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val existing = providersMethod?.invoke(currentSuppressions) as? Set<String>
                if (existing != null) {
                    providers.addAll(existing)
                }
            }
            val createSuppressionsMethod = suppressionsClass.getMethod("create", Set::class.java).apply { isAccessible = true }
            val newSuppressions = createSuppressionsMethod.invoke(null, providers)
            val setSuppressionsMethod = builderObj.javaClass.methods.firstOrNull {
                it.name == "suppressions" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(newSuppressions.javaClass)
            }?.apply { isAccessible = true }
            setSuppressionsMethod?.invoke(builderObj, newSuppressions)
        }
    }

    private fun buildSuppressionsOptional(cl: ClassLoader?, result: Any?): Any? {
        if (result == null || cl == null) return null
        return runCatching {
            val suppressionsClass = Class.forName("com.spotify.player.model.Suppressions", false, cl)
            val providers = mutableSetOf(
                "mft",
                "mft/inject_random_tracks",
                "mft/inject_filler_tracks",
                "mft/inject_track_on_start_play",
                "mft/disallow_restart_play"
            )
            val currentOpt = result
            val hasValueMethod = currentOpt.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            if (hasValueMethod?.invoke(currentOpt) == true) {
                val getMethod = currentOpt.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 && it.returnType != Boolean::class.javaPrimitiveType && it.returnType != Void.TYPE
                }?.apply { isAccessible = true }
                val currentSupp = getMethod?.invoke(currentOpt)
                if (currentSupp != null) {
                    val providersMethod = currentSupp.javaClass.methods.firstOrNull {
                        it.parameterCount == 0 && Set::class.java.isAssignableFrom(it.returnType)
                    }?.apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val existing = providersMethod?.invoke(currentSupp) as? Set<String>
                    if (existing != null) {
                        providers.addAll(existing)
                    }
                }
            }
            val createMethod = suppressionsClass.getMethod("create", Set::class.java).apply { isAccessible = true }
            val newSuppressions = createMethod.invoke(null, providers)
            val optionalClass = result.javaClass.superclass?.takeIf { it != Any::class.java } ?: result.javaClass
            val ofMethod = optionalClass.methods.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Any::class.java &&
                    optionalClass.isAssignableFrom(it.returnType)
            }?.apply { isAccessible = true }
            ofMethod?.invoke(null, newSuppressions)
        }.getOrNull()
    }

    private fun buildBooleanOptional(cl: ClassLoader?, result: Any?, value: Boolean): Any? {
        if (result == null || cl == null) return null
        return runCatching {
            val optionalClass = result.javaClass.superclass?.takeIf { it != Any::class.java } ?: result.javaClass
            val ofMethod = optionalClass.methods.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Any::class.java &&
                    optionalClass.isAssignableFrom(it.returnType)
            }?.apply { isAccessible = true }
            ofMethod?.invoke(null, java.lang.Boolean.valueOf(value))
        }.getOrNull()
    }

    private fun getFieldValue(obj: Any, fieldName: String): Any? {
        var current: Class<*>? = obj.javaClass
        while (current != null && current != Any::class.java) {
            val field = runCatching { current.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(obj) }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun hookPlaybackSettings(module: XposedModule) {
        val setMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYBACK_SETTINGS_SET_SHUFFLE)
        if (setMethod != null) {
            module.deoptimize(setMethod)
            module.hook(setMethod)
                .setId("playback_settings_set_shuffle")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        chain.proceed(arrayOf(chain.getArg(0), isShuffleEnabled(), java.lang.Boolean.FALSE, chain.getArg(3)))
                    } else {
                        chain.proceed()
                    }
                }
        }

        val parseMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_SHUFFLE_STATE_PARSE)
        if (parseMethod != null) {
            module.deoptimize(parseMethod)
            module.hook(parseMethod)
                .setId("playback_settings_shuffle_state_parse")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    val payload = byteArrayOf(
                        0x08, (if (isShuffleEnabled()) 1 else 0).toByte(),
                        0x10, 0x00
                    )
                    chain.proceed(arrayOf(payload))
                }
        }
    }

    private fun hookShuffleContext(module: XposedModule) {
        val modesMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_SHUFFLE_CONTEXT_MODES_GETTER)
        if (modesMethod != null) {
            module.deoptimize(modesMethod)
            module.hook(modesMethod)
                .setId("shuffle_context_modes_getter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val original = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept original
                    }
                    val linear = DexKitResolver.get<Any>(DexKitResolver.KEY_SHUFFLE_ENUM_LINEAR)
                    val shuffle = DexKitResolver.get<Any>(DexKitResolver.KEY_SHUFFLE_ENUM_SHUFFLE)
                    val smartShuffle = DexKitResolver.get<Any>(DexKitResolver.KEY_SHUFFLE_ENUM_SMART_SHUFFLE)
                    if (linear != null && shuffle != null) {
                        if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) || smartShuffle == null) {
                            setOf(linear, shuffle)
                        } else {
                            setOf(linear, shuffle, smartShuffle)
                        }
                    } else {
                        original
                    }
                }
        }

        val defaultMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_SHUFFLE_CONTEXT_DEFAULT_GETTER)
        if (defaultMethod != null) {
            module.deoptimize(defaultMethod)
            module.hook(defaultMethod)
                .setId("shuffle_context_default_getter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val original = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept original
                    }
                    val linear = DexKitResolver.get<Any>(DexKitResolver.KEY_SHUFFLE_ENUM_LINEAR)
                    val shuffle = DexKitResolver.get<Any>(DexKitResolver.KEY_SHUFFLE_ENUM_SHUFFLE)
                    if (linear != null && shuffle != null) {
                        if (isShuffleEnabled()) shuffle else linear
                    } else {
                        original
                    }
                }
        }

        val enabledMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_SHUFFLE_CONTEXT_ENABLED_GETTER)
        if (enabledMethod != null) {
            module.deoptimize(enabledMethod)
            module.hook(enabledMethod)
                .setId("shuffle_context_enabled_getter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    true
                }
        }
    }

    private fun hookContextCapability(module: XposedModule) {
        val ctor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_CONTEXT_CAPABILITY_INIT)
        if (ctor != null) {
            module.deoptimize(ctor)
            module.hook(ctor)
                .setId("context_capability_init")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        val newArgs = arrayOf(
                            chain.getArg(0),
                            chain.getArg(1),
                            chain.getArg(2),
                            5,
                            chain.getArg(4),
                            chain.getArg(5)
                        )
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
                    null
                }
        }
    }

    @Volatile
    private var nativeTransport: Any? = null

    private fun hookNativeTransportCtors(module: XposedModule, cl: ClassLoader) {
        runCatching {
            val nativeCls = Class.forName("com.spotify.esperanto.esperantoimpl.NativeTransport", false, cl)
            val ctor = nativeCls.declaredConstructors.firstOrNull()
            if (ctor != null) {
                module.deoptimize(ctor)
                module.hook(ctor)
                    .setId("native_transport_init")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        chain.proceed()
                        val thisObj = chain.getThisObject()
                        if (thisObj != null) {
                            nativeTransport = thisObj
                        }
                        null
                    }
            }
        }

        runCatching {
            val schedCls = Class.forName("com.spotify.esperanto.esperantoimpl.SchedulingTransport", false, cl)
            val ctor = schedCls.declaredConstructors.firstOrNull()
            if (ctor != null) {
                module.deoptimize(ctor)
                module.hook(ctor)
                    .setId("scheduling_transport_init")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        chain.proceed()
                        val thisObj = chain.getThisObject()
                        if (thisObj != null) {
                            nativeTransport = thisObj
                        }
                        null
                    }
            }
        }
    }

    private fun hookClientBase(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE) ?: return
        val cl = method.declaringClass.classLoader
        if (cl != null) {
            hookNativeTransportCtors(module, cl)
        }
        module.deoptimize(method)
        module.hook(method)
            .setId("client_base_call_single")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val service = chain.getArg(0) as? String
                val methodName = chain.getArg(1) as? String
                val payload = chain.getArg(2)
                val client = chain.getThisObject()
                if (client != null) {
                    val t = getTransport(client)
                    if (t != null && !t.javaClass.name.contains("Cosmos")) {
                        nativeTransport = t
                    }
                }

                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    if (service == "spotify.playback_settings.esperanto.proto.PlaybackSettings" && methodName == "GetShuffleState") {
                        val shuffleOn = isShuffleEnabled()
                        val stateBytes = if (shuffleOn) byteArrayOf(0x08, 0x01, 0x10, 0x00) else byteArrayOf(0x08, 0x00, 0x10, 0x00)
                        val singleClass = method.declaringClass.classLoader?.loadClass("io.reactivex.rxjava3.core.Single")
                        val justMethod = singleClass?.getMethod("just", Any::class.java)
                        val singleObj = justMethod?.invoke(null, stateBytes)
                        if (singleObj != null) {
                            return@intercept singleObj
                        }
                    }
                    handleEsperantoPayload(chain, service, methodName, payload)
                }

                chain.proceed()
            }
    }

    private fun hookCoroutineClientBase(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_COROUTINE_CLIENT_BASE_CALL_SINGLE) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("coroutine_client_base_call_single")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val service = chain.getArg(0) as? String
                val methodName = chain.getArg(1) as? String
                val payload = chain.getArg(2)
                val client = chain.getThisObject()
                if (client != null) {
                    val t = getTransport(client)
                    if (t != null && !t.javaClass.name.contains("Cosmos")) {
                        nativeTransport = t
                    }
                }

                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    if (service == "spotify.playback_settings.esperanto.proto.PlaybackSettings" && methodName == "GetShuffleState") {
                        val shuffleOn = isShuffleEnabled()
                        val stateBytes = if (shuffleOn) byteArrayOf(0x08, 0x01, 0x10, 0x00) else byteArrayOf(0x08, 0x00, 0x10, 0x00)
                        return@intercept stateBytes
                    }
                    handleEsperantoPayload(chain, service, methodName, payload)
                }

                chain.proceed()
            }
    }



    private fun handleEsperantoPayload(
        chain: Chain,
        service: String?,
        methodName: String?,
        payload: Any?
    ) {
        if (payload == null) return
        if (service == "spotify.player.esperanto.proto.ContextPlayer") {
            when (methodName) {
                "Play", "PreparePlay" -> {
                    runCatching {
                        applyPlayOverrides(payload)
                    }
                    dispatchSetShufflingContext(chain.getThisObject(), isShuffleEnabled())
                }
                "SetOptions" -> {
                    runCatching {
                        applySetOptionsOverrides(chain, payload)
                    }
                }
                "SetShufflingContext" -> {
                    runCatching {
                        val shufflingField = payload.javaClass.getDeclaredField("shufflingContext_").apply { isAccessible = true }
                        if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                            val isShuffle = isShuffleEnabled()
                            shufflingField.setBoolean(payload, isShuffle)
                            resetMemoizedSize(payload)
                        }
                    }
                }
                "SkipNext", "SkipPrev" -> {
                    runCatching {
                        applySkipOverrides(payload)
                    }
                }
            }
        } else if (service == "spotify.playlist_esperanto.proto.PlaylistPlayService") {
            if (methodName == "Play") {
                runCatching {
                    applyPlayOverrides(payload)
                }
                dispatchSetShufflingContext(chain.getThisObject(), isShuffleEnabled())
            }
        } else if (service == "spotify.playback_settings.esperanto.proto.PlaybackSettings") {
            if (methodName == "SetShuffleState") {
                runCatching {
                    handlePlaybackSettingsSetShuffle(chain, payload)
                }
            }
        }
    }

    private fun deStationContext(payload: Any) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) return
        val (container, context) = locateContext(payload) ?: return
        var changed = false
        for (fieldName in listOf("uri_", "url_")) {
            runCatching {
                val field = context.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
                val current = field.get(context) as? String ?: return@runCatching
                deStation(current)?.let { destationed ->
                    field.set(context, destationed)
                    changed = true
                }
            }
        }
        if (changed) {
            resetMemoizedSize(context)
            if (container !== payload) {
                resetMemoizedSize(container)
            }
            resetMemoizedSize(payload)
        }
    }

    private fun locateContext(payload: Any): Pair<Any, Any>? {
        runCatching {
            val field = payload.javaClass.getDeclaredField("context_").apply { isAccessible = true }
            field.get(payload)?.let { return payload to it }
        }
        return runCatching {
            val parentField = payload.javaClass.getDeclaredField("preparePlayRequest_").apply { isAccessible = true }
            val parent = parentField.get(payload)
            parent?.javaClass
                ?.getDeclaredField("context_")
                ?.apply { isAccessible = true }
                ?.get(parent)
                ?.let { parent to it }
        }.getOrNull()
    }

    private fun deStation(uri: String): String? {
        if (!uri.startsWith(STATION_PREFIX)) return null
        var seed = uri
        while (seed.startsWith(STATION_PREFIX)) {
            seed = seed.substring(STATION_PREFIX.length)
        }
        val bare = seed.removePrefix(BASE_PREFIX)
        if (bare.startsWith("track:") || bare.startsWith("artist:") || bare.startsWith("genre:")) return null
        return if (seed.startsWith(BASE_PREFIX)) seed else BASE_PREFIX + seed
    }

    private fun applyPlayOverrides(payload: Any) {
        deStationContext(payload)
        val cl = payload.javaClass.classLoader ?: return
        val prepPlayClass = Class.forName("com.spotify.player.esperanto.proto.EsPreparePlay\$PreparePlayRequest", false, cl)
        val prepOptionsClass = Class.forName("com.spotify.player.esperanto.proto.EsPreparePlayOptions\$PreparePlayOptions", false, cl)
        val overridesClass = Class.forName("com.spotify.player.esperanto.proto.EsContextPlayerOptions\$ContextPlayerOptionOverrides", false, cl)
        val optBoolClass = Class.forName("com.spotify.player.esperanto.proto.EsOptional\$OptionalBoolean", false, cl)

        val isPlaylistPlay = payload.javaClass.name.endsWith("PlaylistPlayRequest")
        val prepReq = if (payload.javaClass.name.endsWith("EsPlay\$PlayRequest")) {
            val prepField = payload.javaClass.getDeclaredField("preparePlayRequest_").apply { isAccessible = true }
            var pr = prepField.get(payload)
            if (pr == null) {
                pr = prepPlayClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                prepField.set(payload, pr)
                val playBitField = payload.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
                playBitField.setInt(payload, playBitField.getInt(payload) or 1)
            }
            pr
        } else if (payload.javaClass.name.endsWith("EsPreparePlay\$PreparePlayRequest")) {
            payload
        } else if (isPlaylistPlay) {
            null
        } else {
            return
        }

        val options = if (isPlaylistPlay) {
            val prepOptionsField = payload.javaClass.getDeclaredField("preparePlayOptions_").apply { isAccessible = true }
            var po = prepOptionsField.get(payload)
            if (po == null) {
                po = prepOptionsClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                prepOptionsField.set(payload, po)
                val bitField = payload.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
                bitField.setInt(payload, bitField.getInt(payload) or 16)
            }
            po
        } else {
            val optField = prepPlayClass.getDeclaredField("options_").apply { isAccessible = true }
            var po = optField.get(prepReq)
            if (po == null) {
                po = prepOptionsClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                optField.set(prepReq, po)
                val prepBitField = prepPlayClass.getDeclaredField("bitField0_").apply { isAccessible = true }
                prepBitField.setInt(prepReq, prepBitField.getInt(prepReq) or 2)
            }
            po
        }

        val overrideField = prepOptionsClass.getDeclaredField("playerOptionsOverride_").apply { isAccessible = true }
        var overrides = overrideField.get(options)
        if (overrides == null) {
            overrides = overridesClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            overrideField.set(options, overrides)
        }
        val optBitField = prepOptionsClass.getDeclaredField("bitField0_").apply { isAccessible = true }
        optBitField.setInt(options, optBitField.getInt(options) or 4)

        val optBool = optBoolClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val valField = optBoolClass.getDeclaredField("value_").apply { isAccessible = true }
        val shuffleEnabled = isShuffleEnabled()
        valField.setBoolean(optBool, shuffleEnabled)

        val shuffField = overridesClass.getDeclaredField("shufflingContext_").apply { isAccessible = true }
        shuffField.set(overrides, optBool)
        val overBitField = overridesClass.getDeclaredField("bitField0_").apply { isAccessible = true }
        overBitField.setInt(overrides, overBitField.getInt(overrides) or 1)

        if (!shuffleEnabled) {
            val suppProviders = setOf(
                "mft",
                "mft/inject_random_tracks",
                "mft/inject_filler_tracks",
                "mft/inject_track_on_start_play",
                "mft/disallow_restart_play"
            )
            val addSuppMethod = prepOptionsClass.declaredMethods.firstOrNull {
                it.parameterCount == 1 && Iterable::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.apply { isAccessible = true }
            addSuppMethod?.invoke(options, suppProviders)
        }

        if (isPlaylistPlay) {
            val queryField = runCatching { payload.javaClass.getDeclaredField("playlistQuery_") }.getOrNull()?.apply { isAccessible = true }
            val query = queryField?.get(payload)
            if (query != null) {
                runCatching {
                    val recField = query.javaClass.getDeclaredField("loadRecommendations_").apply { isAccessible = true }
                    recField.setBoolean(query, false)
                    resetMemoizedSize(query)
                }
            }
        }

        val licenseField = runCatching { prepOptionsClass.getDeclaredField("license_").apply { isAccessible = true } }.getOrNull()
        licenseField?.set(options, "premium")

        resetMemoizedSize(optBool)
        resetMemoizedSize(overrides)
        resetMemoizedSize(options)
        if (prepReq != null) {
            resetMemoizedSize(prepReq)
        }
        resetMemoizedSize(payload)
    }

    private fun applySkipOverrides(payload: Any) {
        val cl = payload.javaClass.classLoader ?: return
        val optClass = Class.forName("com.spotify.player.esperanto.proto.EsCommandOptions\$CommandOptions", false, cl)
        val optField = runCatching { payload.javaClass.getDeclaredField("options_").apply { isAccessible = true } }.getOrNull() ?: return
        var opt = optField.get(payload)
        if (opt == null) {
            opt = optClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            optField.set(payload, opt)
            runCatching {
                val bitField = payload.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
                bitField.setInt(payload, bitField.getInt(payload) or 1)
            }
        }
        val overField = runCatching { optClass.getDeclaredField("overrideRestrictions_").apply { isAccessible = true } }.getOrNull()
        overField?.setBoolean(opt, true)
        resetMemoizedSize(opt)
        resetMemoizedSize(payload)
    }

    private fun applySetOptionsOverrides(chain: Chain, payload: Any) {
        val cl = payload.javaClass.classLoader ?: return
        val optBoolClass = Class.forName("com.spotify.player.esperanto.proto.EsOptional\$OptionalBoolean", false, cl)

        val shuffField = payload.javaClass.getDeclaredField("shufflingContext_").apply { isAccessible = true }
        val currentOpt = shuffField.get(payload)
        val bitField = payload.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
        val currentBits = bitField.getInt(payload)

        val shuffleEnabled = if ((currentBits and 4) != 0 && currentOpt != null) {
            val valField = optBoolClass.getDeclaredField("value_").apply { isAccessible = true }
            val uiShuffle = valField.getBoolean(currentOpt)
            userShuffleState = uiShuffle
            PreferencesManager.setBoolean(null, PrefKeys.KEY_SHUFFLE_STATE, uiShuffle)
            uiShuffle
        } else {
            isShuffleEnabled()
        }

        val optBool = optBoolClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val valField = optBoolClass.getDeclaredField("value_").apply { isAccessible = true }
        valField.setBoolean(optBool, shuffleEnabled)

        shuffField.set(payload, optBool)
        bitField.setInt(payload, currentBits or 4)

        resetMemoizedSize(optBool)
        resetMemoizedSize(payload)

        dispatchSetShufflingContext(chain.getThisObject(), shuffleEnabled)
    }

    private fun handlePlaybackSettingsSetShuffle(
        chain: Chain,
        payload: Any
    ) {
        val shuffleOnField = payload.javaClass.getDeclaredField("shuffleOn_").apply { isAccessible = true }
        if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
            val shuffleOn = isShuffleEnabled()
            shuffleOnField.setBoolean(payload, shuffleOn)
            val ceField = runCatching { payload.javaClass.getDeclaredField("contextEnhancement_") }.getOrNull()?.apply { isAccessible = true }
            ceField?.setInt(payload, 0)
            resetMemoizedSize(payload)
            dispatchSetShufflingContext(chain.getThisObject(), shuffleOn)
        }
    }

    private fun getTransport(client: Any): Any? {
        var cls: Class<*>? = client.javaClass
        while (cls != null && cls != Any::class.java) {
            val field = runCatching { cls.getDeclaredField("transport") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(client) }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    private fun dispatchSetShufflingContext(client: Any?, shuffleOn: Boolean) {
        runCatching {
            val transport = (if (client != null) getTransport(client) else null)
                ?.takeIf { !it.javaClass.name.contains("Cosmos") }
                ?: nativeTransport
            if (transport == null) return
            val syncMethod = transport.javaClass.methods.firstOrNull {
                it.name == "callSync" &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == ByteArray::class.java
            } ?: transport.javaClass.interfaces.firstNotNullOfOrNull { iface ->
                iface.methods.firstOrNull {
                    it.name == "callSync" &&
                        it.parameterCount == 3 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == String::class.java &&
                        it.parameterTypes[2] == ByteArray::class.java
                }
            }
            if (syncMethod != null) {
                syncMethod.isAccessible = true
                val scPayload = if (shuffleOn) byteArrayOf(0x08, 0x01) else byteArrayOf(0x08, 0x00)
                syncMethod.invoke(
                    transport,
                    "spotify.player.esperanto.proto.ContextPlayer",
                    "SetShufflingContext",
                    scPayload
                )
                val soPayload = if (shuffleOn) byteArrayOf(0x1a, 0x02, 0x08, 0x01) else byteArrayOf(0x1a, 0x02, 0x08, 0x00)
                syncMethod.invoke(
                    transport,
                    "spotify.player.esperanto.proto.ContextPlayer",
                    "SetOptions",
                    soPayload
                )
            }
        }
    }

    private fun resetMemoizedSize(obj: Any?) {
        if (obj == null) return
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            val field = runCatching { cls.getDeclaredField("memoizedSerializedSize") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                field.setInt(obj, -1)
                break
            }
            cls = cls.superclass
        }
    }
}
