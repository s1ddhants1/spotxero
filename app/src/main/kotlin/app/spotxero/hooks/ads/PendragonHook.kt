package app.spotxero.hooks.ads

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object PendragonHook {

    private const val PENDRAGON_SERVICE_SUBSTRING = "ClientMessageService"
    private const val FETCH_MESSAGE_RESPONSE = "com.spotify.pendragon.v1.proto.FetchMessageResponse"
    private const val FETCH_MESSAGE_LIST_RESPONSE = "com.spotify.pendragon.v1.proto.FetchMessageListResponse"
    private const val RX_SINGLE = "io.reactivex.rxjava3.core.Single"

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_PENDRAGON, true)) {
            return
        }

        val retrofitCreate = DexKitResolver.get<Method>(DexKitResolver.KEY_RETROFIT_CREATE) ?: return

        module.deoptimize(retrofitCreate)
        module.hook(retrofitCreate)
            .setId("pendragon_service_interceptor")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_PENDRAGON, true)) {
                    return@intercept result
                }

                val serviceClass = chain.getArg(0) as? Class<*> ?: return@intercept result
                if (!isPendragonService(serviceClass)) {
                    return@intercept result
                }

                wrapPendragonService(serviceClass, result)
            }
    }

    private fun isPendragonService(cls: Class<*>): Boolean {
        if (cls.name.contains(PENDRAGON_SERVICE_SUBSTRING, ignoreCase = true)) return true
        for (m in cls.declaredMethods) {
            if (m.name.contains("FetchMessage", ignoreCase = true)) return true
            for (ann in m.declaredAnnotations) {
                if (ann.toString().contains(PENDRAGON_SERVICE_SUBSTRING, ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun wrapPendragonService(serviceClass: Class<*>, realInstance: Any): Any {
        val cl = serviceClass.classLoader ?: return realInstance
        val singleClass = runCatching { Class.forName(RX_SINGLE, false, cl) }.getOrNull() ?: return realInstance
        val singleJustMethod = runCatching { singleClass.getMethod("just", Any::class.java) }.getOrNull() ?: return realInstance

        val fetchMsgRespClass = runCatching { Class.forName(FETCH_MESSAGE_RESPONSE, false, cl) }.getOrNull()
        val defaultMsgInstance = fetchMsgRespClass?.let { getDefaultInstance(it) }

        val fetchListRespClass = runCatching { Class.forName(FETCH_MESSAGE_LIST_RESPONSE, false, cl) }.getOrNull()
        val defaultListInstance = fetchListRespClass?.let { getDefaultInstance(it) }

        val handler = InvocationHandler { _, method, args ->
            val returnType = method.returnType
            if (singleClass.isAssignableFrom(returnType)) {
                val methodName = method.name
                val annotationsStr = method.declaredAnnotations.joinToString { it.toString() }

                if (methodName.contains("FetchMessageList", ignoreCase = true) ||
                    annotationsStr.contains("FetchMessageList", ignoreCase = true)
                ) {
                    if (defaultListInstance != null) {
                        return@InvocationHandler singleJustMethod.invoke(null, defaultListInstance)
                    }
                }

                if (methodName.contains("FetchMessage", ignoreCase = true) ||
                    annotationsStr.contains("FetchMessage", ignoreCase = true)
                ) {
                    if (defaultMsgInstance != null) {
                        return@InvocationHandler singleJustMethod.invoke(null, defaultMsgInstance)
                    }
                }
            }

            if (args != null) {
                method.invoke(realInstance, *args)
            } else {
                method.invoke(realInstance)
            }
        }

        return Proxy.newProxyInstance(cl, arrayOf(serviceClass), handler)
    }

    private fun getDefaultInstance(clazz: Class<*>): Any? {
        return runCatching {
            clazz.getDeclaredMethod("getDefaultInstance").invoke(null)
        }.getOrNull() ?: runCatching {
            clazz.getDeclaredField("DEFAULT_INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull()
    }
}
