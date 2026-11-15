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

    private val BASE_SUPPRESSIONS = setOf("ad", "podcast_ad")
    private val SHUFFLE_OFF_SUPPRESSIONS = setOf("mft/force_shuffle")

    private fun suppressionProviders(shuffleEnabled: Boolean): Set<String> =
        if (shuffleEnabled) BASE_SUPPRESSIONS else BASE_SUPPRESSIONS + SHUFFLE_OFF_SUPPRESSIONS

    @Volatile
    private var userShuffleState: Boolean? = null

    @Volatile
    private var userSmartShuffleInModes: Boolean? = null

    private fun isShuffleEnabled(): Boolean {
        val cached = userShuffleState
        if (cached != null) return cached
        val stored = PreferencesManager.isEnabled(PrefKeys.KEY_SHUFFLE_STATE, false)
        userShuffleState = stored
        return stored
    }

    private fun isSmartShuffleInModesEnabled(): Boolean {
        val cached = userSmartShuffleInModes
        if (cached != null) return cached
        val stored = PreferencesManager.isEnabled(PrefKeys.KEY_SMART_SHUFFLE_IN_MODES, true)
        userSmartShuffleInModes = stored
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
                    val thisObj = chain.getThisObject() ?: return@intercept result
                    val cl = thisObj.javaClass.classLoader
                    buildSuppressionsOptional(cl, result, isShuffleEnabled()) ?: result
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
                    if (isShuffleEnabled()) {
                        chain.proceed()
                    } else {
                        null
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
                    if (isShuffleEnabled()) {
                        val thisObj = chain.getThisObject() ?: return@intercept result
                        val cl = thisObj.javaClass.classLoader
                        buildBooleanOptional(cl, result, true) ?: result
                    } else {
                        result
                    }
                }
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
    }

    private fun applyPreparePlayOverrides(builderObj: Any, cl: ClassLoader, shuffleEnabled: Boolean) {
        val overridesClass = Class.forName("com.spotify.player.model.command.options.PlayerOptionOverrides", false, cl)
        val suppressionsClass = Class.forName("com.spotify.player.model.Suppressions", false, cl)

        if (shuffleEnabled) {
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
                shuffleMethod?.invoke(toBuilder, true)
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
                shuffleMethod?.invoke(builder, true)
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
        } else {
            val overrideField = runCatching { builderObj.javaClass.getDeclaredField("playerOptionsOverride").apply { isAccessible = true } }.getOrNull()
            if (overrideField != null) {
                val absent = runCatching {
                    Class.forName("p.j5", false, cl).getDeclaredField("a").apply { isAccessible = true }.get(null)
                }.getOrNull()
                if (absent != null) {
                    overrideField.set(builderObj, absent)
                }
            }
        }



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

        val providers = suppressionProviders(shuffleEnabled).toMutableSet()
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

    private fun buildSuppressionsOptional(cl: ClassLoader?, result: Any?, shuffleEnabled: Boolean): Any? {
        if (result == null || cl == null) return null
        return runCatching {
            val suppressionsClass = Class.forName("com.spotify.player.model.Suppressions", false, cl)
            val providers = suppressionProviders(shuffleEnabled).toMutableSet()
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
                        val smartShuffle = chain.getArg(2) as? Boolean
                        if (smartShuffle != null) {
                            userSmartShuffleInModes = smartShuffle
                            PreferencesManager.setBoolean(null, PrefKeys.KEY_SMART_SHUFFLE_IN_MODES, smartShuffle)
                        }
                        chain.proceed(arrayOf(chain.getArg(0), isShuffleEnabled(), smartShuffle ?: isSmartShuffleInModesEnabled(), chain.getArg(3)))
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
                    val smartShuffle = isSmartShuffleInModesEnabled()
                    val payload = byteArrayOf(
                        0x08, (if (isShuffleEnabled()) 1 else 0).toByte(),
                        0x10, (if (smartShuffle) 1 else 0).toByte()
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
                        if (isSmartShuffleInModesEnabled() && smartShuffle != null) {
                            setOf(linear, shuffle, smartShuffle)
                        } else {
                            setOf(linear, shuffle)
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

    private fun hookClientBase(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("client_base_call_single")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val service = chain.getArg(0) as? String
                val methodName = chain.getArg(1) as? String
                val payload = chain.getArg(2)

                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    if (service == "spotify.playback_settings.esperanto.proto.PlaybackSettings" && methodName == "GetShuffleState") {
                        val shuffleOn = isShuffleEnabled()
                        val smartShuffle = isSmartShuffleInModesEnabled()
                        val stateBytes = byteArrayOf(
                            0x08, (if (shuffleOn) 1 else 0).toByte(),
                            0x10, (if (smartShuffle) 1 else 0).toByte()
                        )
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

                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    if (service == "spotify.playback_settings.esperanto.proto.PlaybackSettings" && methodName == "GetShuffleState") {
                        val shuffleOn = isShuffleEnabled()
                        val smartShuffle = isSmartShuffleInModesEnabled()
                        val stateBytes = byteArrayOf(
                            0x08, (if (shuffleOn) 1 else 0).toByte(),
                            0x10, (if (smartShuffle) 1 else 0).toByte()
                        )
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
                }
                "SetOptions" -> {
                    runCatching {
                        applySetOptionsOverrides(payload)
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
                "SkipNext", "SkipPrev", "PlayPrepared" -> {
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
            }
        } else if (service == "spotify.playback_settings.esperanto.proto.PlaybackSettings") {
            if (methodName == "SetShuffleState") {
                runCatching {
                    handlePlaybackSettingsSetShuffle(payload)
                }
            }
        }
    }

    private fun sanitizeContext(payload: Any) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) return
        val (container, context) = locateContext(payload) ?: return
        var changed = false

        runCatching {
            val restrField = context.javaClass.getDeclaredField("restrictions_").apply { isAccessible = true }
            if (restrField.get(context) != null) {
                restrField.set(context, null)
                val bitField = context.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
                bitField.setInt(context, bitField.getInt(context) and 1.inv())
                changed = true
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

    private fun applyPlayOverrides(payload: Any) {
        sanitizeContext(payload)
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
        val optBitField = prepOptionsClass.getDeclaredField("bitField0_").apply { isAccessible = true }
        val shuffleEnabled = isShuffleEnabled()

        if (shuffleEnabled) {
            var overrides = overrideField.get(options)
            if (overrides == null) {
                overrides = overridesClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                overrideField.set(options, overrides)
            }
            optBitField.setInt(options, optBitField.getInt(options) or 4)

            val optBool = optBoolClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val valField = optBoolClass.getDeclaredField("value_").apply { isAccessible = true }
            valField.setBoolean(optBool, true)

            val shuffField = overridesClass.getDeclaredField("shufflingContext_").apply { isAccessible = true }
            shuffField.set(overrides, optBool)
            val overBitField = overridesClass.getDeclaredField("bitField0_").apply { isAccessible = true }
            overBitField.setInt(overrides, overBitField.getInt(overrides) or 1)

            resetMemoizedSize(optBool)
            resetMemoizedSize(overrides)
        } else {
            overrideField.set(options, null)
            optBitField.setInt(options, optBitField.getInt(options) and 4.inv())
        }

        val addSuppMethod = prepOptionsClass.declaredMethods.firstOrNull {
            it.parameterCount == 1 && Iterable::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.apply { isAccessible = true }
        addSuppMethod?.invoke(options, suppressionProviders(shuffleEnabled))

        if (isPlaylistPlay) {
            val queryField = runCatching { payload.javaClass.getDeclaredField("playlistQuery_") }.getOrNull()?.apply { isAccessible = true }
            val query = queryField?.get(payload)
            if (query != null) {
                runCatching {
                    val incPremField = query.javaClass.getDeclaredField("includePremiumContent_").apply { isAccessible = true }
                    incPremField.setBoolean(query, true)
                }
                resetMemoizedSize(query)
            }
        }



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
                val bitMask = if (payload.javaClass.name.endsWith("PlayPreparedRequest")) 2 else 1
                bitField.setInt(payload, bitField.getInt(payload) or bitMask)
            }
        }
        val overField = runCatching { optClass.getDeclaredField("overrideRestrictions_").apply { isAccessible = true } }.getOrNull()
        overField?.setBoolean(opt, true)
        resetMemoizedSize(opt)
        resetMemoizedSize(payload)
    }

    private fun applySetOptionsOverrides(payload: Any) {
        val cl = payload.javaClass.classLoader ?: return
        val optBoolClass = Class.forName("com.spotify.player.esperanto.proto.EsOptional\$OptionalBoolean", false, cl)

        val shuffField = payload.javaClass.getDeclaredField("shufflingContext_").apply { isAccessible = true }
        val currentOpt = shuffField.get(payload)
        val bitField = payload.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
        val currentBits = bitField.getInt(payload)

        if ((currentBits and 4) == 0 || currentOpt == null) {
            return
        }

        val valField = optBoolClass.getDeclaredField("value_").apply { isAccessible = true }
        val uiShuffle = valField.getBoolean(currentOpt)
        userShuffleState = uiShuffle
        PreferencesManager.setBoolean(null, PrefKeys.KEY_SHUFFLE_STATE, uiShuffle)

        if (uiShuffle) {
            val optBool = optBoolClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            valField.setBoolean(optBool, true)
            shuffField.set(payload, optBool)
            bitField.setInt(payload, currentBits or 4)
            resetMemoizedSize(optBool)
        } else {
            shuffField.set(payload, null)
            bitField.setInt(payload, currentBits and 4.inv())
        }
        resetMemoizedSize(payload)
    }

    private fun handlePlaybackSettingsSetShuffle(payload: Any) {
        val shuffleOnField = payload.javaClass.getDeclaredField("shuffleOn_").apply { isAccessible = true }
        if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
            val shuffleOn = isShuffleEnabled()
            shuffleOnField.setBoolean(payload, shuffleOn)
            val ceField = runCatching { payload.javaClass.getDeclaredField("contextEnhancement_") }.getOrNull()?.apply { isAccessible = true }
            if (ceField != null) {
                val currentCe = ceField.getInt(payload)
                val smartShuffle = currentCe != 0
                userSmartShuffleInModes = smartShuffle
                PreferencesManager.setBoolean(null, PrefKeys.KEY_SMART_SHUFFLE_IN_MODES, smartShuffle)
            }
            resetMemoizedSize(payload)
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
