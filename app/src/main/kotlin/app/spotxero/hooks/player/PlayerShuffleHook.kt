package app.spotxero.hooks.player

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object PlayerShuffleHook {

    private val SUPPRESSIONS = listOf("ad", "podcast_ad", "mft/force_shuffle")

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
            return
        }
        hookEsperantoCall(module, DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE, "client_base_call_single")
        hookEsperantoCall(module, DexKitResolver.KEY_COROUTINE_CLIENT_BASE_CALL_SINGLE, "coroutine_client_base_call_single")
    }

    private fun hookEsperantoCall(module: XposedModule, resolverKey: String, hookId: String) {
        val method = DexKitResolver.require<Method>(resolverKey)
        module.deoptimize(method)
        module.hook(method)
            .setId(hookId)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    val service = chain.getArg(0) as? String
                    val methodName = chain.getArg(1) as? String
                    handleEsperantoPayload(service, methodName, chain.getArg(2))
                }
                chain.proceed()
            }
    }

    private fun handleEsperantoPayload(service: String?, methodName: String?, payload: Any?) {
        if (payload == null) return
        when (service) {
            "spotify.player.esperanto.proto.ContextPlayer" -> when (methodName) {
                "Play", "PreparePlay" -> runCatching { sanitizePlayPayload(payload) }
                "SkipNext", "SkipPrev", "PlayPrepared" -> runCatching { applySkipOverrides(payload) }
            }
            "spotify.playlist_esperanto.proto.PlaylistPlayService" -> if (methodName == "Play") {
                runCatching { sanitizePlayPayload(payload) }
            }
        }
    }

    private fun sanitizeContext(payload: Any) {
        val (container, context) = locateContext(payload) ?: return
        val restrField = context.javaClass.getDeclaredField("restrictions_").apply { isAccessible = true }
        if (restrField.get(context) == null) return

        restrField.set(context, null)
        val bitField = context.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
        bitField.setInt(context, bitField.getInt(context) and 1.inv())

        resetMemoizedSize(context)
        if (container !== payload) {
            resetMemoizedSize(container)
        }
        resetMemoizedSize(payload)
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

    private fun sanitizePlayPayload(payload: Any) {
        sanitizeContext(payload)
        val cl = payload.javaClass.classLoader ?: return
        val prepPlayClass = Class.forName("com.spotify.player.esperanto.proto.EsPreparePlay\$PreparePlayRequest", false, cl)
        val prepOptionsClass = Class.forName("com.spotify.player.esperanto.proto.EsPreparePlayOptions\$PreparePlayOptions", false, cl)

        val isPlaylistPlay = payload.javaClass.name.endsWith("PlaylistPlayRequest")
        val prepReq = when {
            payload.javaClass.name.endsWith("EsPlay\$PlayRequest") -> {
                val prepField = payload.javaClass.getDeclaredField("preparePlayRequest_").apply { isAccessible = true }
                prepField.get(payload) ?: prepPlayClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
                    prepField.set(payload, it)
                    val playBitField = payload.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
                    playBitField.setInt(payload, playBitField.getInt(payload) or 1)
                }
            }
            payload.javaClass.name.endsWith("EsPreparePlay\$PreparePlayRequest") -> payload
            isPlaylistPlay -> null
            else -> return
        }

        val options = if (isPlaylistPlay) {
            val prepOptionsField = payload.javaClass.getDeclaredField("preparePlayOptions_").apply { isAccessible = true }
            prepOptionsField.get(payload) ?: prepOptionsClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
                prepOptionsField.set(payload, it)
                val bitField = payload.javaClass.getDeclaredField("bitField0_").apply { isAccessible = true }
                bitField.setInt(payload, bitField.getInt(payload) or 16)
            }
        } else {
            val optField = prepPlayClass.getDeclaredField("options_").apply { isAccessible = true }
            optField.get(prepReq) ?: prepOptionsClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
                optField.set(prepReq, it)
                val prepBitField = prepPlayClass.getDeclaredField("bitField0_").apply { isAccessible = true }
                prepBitField.setInt(prepReq, prepBitField.getInt(prepReq) or 2)
            }
        }

        val existing = runCatching {
            prepOptionsClass.getDeclaredField("suppressions_").apply { isAccessible = true }.get(options) as? List<*>
        }.getOrNull().orEmpty()
        val missing = SUPPRESSIONS.filterNot { it in existing }
        if (missing.isNotEmpty()) {
            val addSuppMethod = prepOptionsClass.declaredMethods.firstOrNull {
                it.parameterCount == 1 && Iterable::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.apply { isAccessible = true }
            addSuppMethod?.invoke(options, missing)
        }

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
