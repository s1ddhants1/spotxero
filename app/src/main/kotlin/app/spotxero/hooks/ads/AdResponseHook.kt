package app.spotxero.hooks.ads

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object AdResponseHook {

    private const val ADS_SLOTS_SERVICE = "spotify.ads.esperanto.proto.Slots"
    private const val ADS_INSTREAM_SERVICE = "spotify.ads.esperanto.proto.InStream"
    private const val ADS_SETTINGS_SERVICE = "spotify.ads.esperanto.proto.Settings"
    private const val ADS_EVENTS_SERVICE = "spotify.ads.esperanto.proto.Events"
    private const val COMMON_CAPPING_SERVICE = "spotify.common_capping.impl.common_capping_proto.CommonCapping"

    private val BLOCKED_SLOTS_METHODS = setOf("CreateSlot", "FetchSlot", "TriggerSlot")
    private val BLOCKED_EVENTS_METHODS = setOf("postEvent", "postUnmanagedEvent")
    private val BLOCKED_CAPPING_METHODS = setOf("IsCapped", "GetQuotaStateAt", "ReportConsumption")
    private val BLOCKED_INSTREAM_STREAMS = setOf("SubInStream", "SubBreakChanged")

    fun install(module: XposedModule) {
        hookGetAdsWrapper(module)
        hookEsperantoSingles(module)
        hookEsperantoStreams(module)
    }

    private fun isBlockedSingle(service: String?, method: String?): Boolean = when (service) {
        ADS_SLOTS_SERVICE -> method in BLOCKED_SLOTS_METHODS
        ADS_SETTINGS_SERVICE -> method == "updateSlotEnabled"
        ADS_EVENTS_SERVICE -> method in BLOCKED_EVENTS_METHODS
        COMMON_CAPPING_SERVICE -> method in BLOCKED_CAPPING_METHODS
        else -> false
    }

    private fun isBlockedStream(service: String?, method: String?): Boolean = when (service) {
        ADS_INSTREAM_SERVICE -> method in BLOCKED_INSTREAM_STREAMS
        ADS_SLOTS_SERVICE -> method == "SubSlot"
        ADS_EVENTS_SERVICE -> method == "subEvent"
        COMMON_CAPPING_SERVICE -> method == "ObserveCapStateChanged"
        else -> false
    }

    private fun hookGetAdsWrapper(module: XposedModule) {
        val wrapper = DexKitResolver.get<Method>(DexKitResolver.KEY_GET_ADS_WRAPPER)
        val parser = DexKitResolver.get<Method>(DexKitResolver.KEY_EMPTY_ADS_RESPONSE)
        if (wrapper == null || parser == null) {
            return
        }

        val emptyResponse = runCatching { parser.invoke(null, ByteArray(0)) }.getOrNull() ?: return

        module.deoptimize(wrapper)

        module.hook(wrapper)
            .setId("empty_ad_response")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    emptyResponse
                } else {
                    chain.proceed()
                }
            }
    }

    private fun hookEsperantoSingles(module: XposedModule) {
        val coroutineSingle = DexKitResolver.get<Method>(DexKitResolver.KEY_COROUTINE_CLIENT_BASE_CALL_SINGLE)
        if (coroutineSingle != null) {
            module.deoptimize(coroutineSingle)
            module.hook(coroutineSingle)
                .setId("ads_coroutine_single")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        val service = chain.getArg(0) as? String
                        val methodName = chain.getArg(1) as? String
                        if (isBlockedSingle(service, methodName)) {
                            return@intercept ByteArray(0)
                        }
                    }
                    chain.proceed()
                }
        }

        val clientSingle = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)
        if (clientSingle != null) {
            val cl = clientSingle.declaringClass.classLoader
            val justMethod = cl?.let {
                runCatching {
                    Class.forName("io.reactivex.rxjava3.core.Single", false, it).getMethod("just", Any::class.java)
                }.getOrNull()
            }
            if (justMethod != null) {
                module.deoptimize(clientSingle)
                module.hook(clientSingle)
                    .setId("ads_client_single")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                            val service = chain.getArg(0) as? String
                            val methodName = chain.getArg(1) as? String
                            if (isBlockedSingle(service, methodName)) {
                                justMethod.invoke(null, ByteArray(0))?.let { return@intercept it }
                            }
                        }
                        chain.proceed()
                    }
            }
        }
    }

    private fun hookEsperantoStreams(module: XposedModule) {
        val clientStream = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_STREAM)
        if (clientStream != null) {
            val cl = clientStream.declaringClass.classLoader
            val neverMethod = cl?.let {
                runCatching {
                    Class.forName("io.reactivex.rxjava3.core.Observable", false, it).getMethod("never")
                }.getOrNull()
            }
            if (neverMethod != null) {
                module.deoptimize(clientStream)
                module.hook(clientStream)
                    .setId("ads_client_stream")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                            val service = chain.getArg(0) as? String
                            val methodName = chain.getArg(1) as? String
                            if (isBlockedStream(service, methodName)) {
                                neverMethod.invoke(null)?.let { return@intercept it }
                            }
                        }
                        chain.proceed()
                    }
            }
        }

        val coroutineStream = DexKitResolver.get<Method>(DexKitResolver.KEY_COROUTINE_CLIENT_BASE_CALL_STREAM)
        if (coroutineStream != null) {
            val flowInterface = coroutineStream.returnType
            val cl = coroutineStream.declaringClass.classLoader
            val unitInstance = cl?.let {
                runCatching {
                    Class.forName("kotlin.Unit", false, it).getField("INSTANCE").get(null)
                }.getOrNull()
            }
            if (flowInterface.isInterface && cl != null && unitInstance != null) {
                val emptyFlow = Proxy.newProxyInstance(cl, arrayOf(flowInterface)) { _, _, _ -> unitInstance }
                module.deoptimize(coroutineStream)
                module.hook(coroutineStream)
                    .setId("ads_coroutine_stream")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                            val service = chain.getArg(0) as? String
                            val methodName = chain.getArg(1) as? String
                            if (isBlockedStream(service, methodName)) {
                                return@intercept emptyFlow
                            }
                        }
                        chain.proceed()
                    }
            }
        }
    }
}
