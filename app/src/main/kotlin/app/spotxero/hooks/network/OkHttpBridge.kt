package app.spotxero.hooks.network

import app.spotxero.core.DexKitResolver
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

object OkHttpBridge {

    class ResponseFactory(
        val callRequestField: Field,
        val urlField: Field,
        val requestClass: Class<*>,
        val responseClass: Class<*>,
        val builderClass: Class<*>,
        private val builderCtor: Constructor<*>,
        private val respBuild: Method,
        private val bReqField: Field,
        private val bCodeField: Field,
        private val bMsgField: Field,
        private val bProtoField: Field,
        private val http11: Any,
        private val bSentField: Field?,
        private val bReceivedField: Field?,
        val bHeadersField: Field?,
        val headerAddMethod: Method?,
        val responseBodyClass: Class<*>?,
        private val bBodyField: Field?
    ) {
        fun extractRequestAndUrl(callObj: Any?): Pair<Any, String>? {
            val req = callObj?.let { runCatching { callRequestField.get(it) }.getOrNull() } ?: return null
            val url = runCatching { urlField.get(req)?.toString() }.getOrNull() ?: return null
            return req to url
        }

        fun buildResponse(
            request: Any,
            code: Int,
            message: String,
            body: Any? = null,
            contentType: String? = null
        ): Any? = runCatching {
            val builder = builderCtor.newInstance()
            bReqField.set(builder, request)
            bProtoField.set(builder, http11)
            bCodeField.setInt(builder, code)
            bMsgField.set(builder, message)
            if (body != null && bBodyField != null) {
                bBodyField.set(builder, body)
                if (contentType != null && bHeadersField != null && headerAddMethod != null) {
                    bHeadersField.get(builder)?.let { headerAddMethod.invoke(it, "content-type", contentType) }
                }
            } else {
                val now = System.currentTimeMillis()
                runCatching {
                    bSentField?.setLong(builder, now)
                    bReceivedField?.setLong(builder, now)
                }
            }
            respBuild.invoke(builder)
        }.getOrNull()
    }

    private data class RegisteredInterceptor(
        val id: String,
        val handler: (request: Any, url: String, factory: ResponseFactory, proceed: () -> Any?) -> Pair<Boolean, Any?>
    )

    @Volatile
    private var cachedFactory: ResponseFactory? = null

    @Volatile
    private var realCallHookInstalled = false

    private val interceptors = CopyOnWriteArrayList<RegisteredInterceptor>()

    fun reset() {
        realCallHookInstalled = false
        cachedFactory = null
        interceptors.clear()
    }

    fun findRequestUrlField(requestClass: Class<*>): Field? =
        requestClass.declaredFields.firstOrNull { f ->
            !f.type.isPrimitive && f.type.declaredMethods.any { m -> m.returnType == URI::class.java }
        }?.apply { isAccessible = true }

    fun getFactory(): ResponseFactory {
        cachedFactory?.let { return it }
        val realCallChain = DexKitResolver.require<Method>(DexKitResolver.KEY_OKHTTP_REAL_CALL_CHAIN)
        val reqToString = DexKitResolver.require<Method>(DexKitResolver.KEY_OKHTTP_REQUEST_TO_STRING)
        val respBuild = DexKitResolver.require<Method>(DexKitResolver.KEY_OKHTTP_RESPONSE_BUILDER_BUILD)

        val requestClass = reqToString.declaringClass
        val responseClass = respBuild.returnType
        val callRequestField = realCallChain.declaringClass.declaredFields.firstOrNull {
            it.type == requestClass
        }?.apply { isAccessible = true } ?: error("Missing OkHttp call request field")
        val urlField = findRequestUrlField(requestClass) ?: error("Missing OkHttp request URL field")

        val builderClass = respBuild.declaringClass
        val builderCtor = builderClass.getDeclaredConstructor().apply { isAccessible = true }
        val bReqField = builderClass.declaredFields.firstOrNull { it.type == requestClass }?.apply { isAccessible = true }
            ?: error("Missing OkHttp builder request field")
        val bCodeField = builderClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }?.apply { isAccessible = true }
            ?: error("Missing OkHttp builder code field")
        val bMsgField = builderClass.declaredFields.firstOrNull { it.type == String::class.java }?.apply { isAccessible = true }
            ?: error("Missing OkHttp builder message field")
        val bProtoField = builderClass.declaredFields.firstOrNull {
            it.type.isEnum && it.type.enumConstants?.any { c -> c.toString() == "http/1.1" } == true
        }?.apply { isAccessible = true } ?: error("Missing OkHttp builder protocol field")
        val http11 = bProtoField.type.enumConstants?.firstOrNull { it.toString() == "http/1.1" }
            ?: error("Missing http/1.1 enum constant")

        val longFields = builderClass.declaredFields.filter { it.type == Long::class.javaPrimitiveType }.onEach { it.isAccessible = true }
        val bSentField = longFields.firstOrNull { it.name.contains("sent", ignoreCase = true) } ?: longFields.getOrNull(0)
        val bReceivedField = longFields.firstOrNull { it.name.contains("received", ignoreCase = true) }
            ?: longFields.firstOrNull { it != bSentField }

        val responseBodyClass = runCatching { Class.forName("okhttp3.ResponseBody", false, builderClass.classLoader) }.getOrNull()
            ?: builderClass.declaredFields.firstOrNull { f ->
                java.io.Closeable::class.java.isAssignableFrom(f.type) &&
                    Modifier.isAbstract(f.type.modifiers) &&
                    f.type != requestClass &&
                    f.type != responseClass
            }?.type
            ?: responseClass.declaredMethods.firstOrNull { m ->
                !Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 &&
                    java.io.Closeable::class.java.isAssignableFrom(m.returnType) &&
                    Modifier.isAbstract(m.returnType.modifiers) &&
                    m.returnType != responseClass
            }?.returnType
        val bBodyField = responseBodyClass?.let { bodyCls ->
            builderClass.declaredFields.firstOrNull { it.type == bodyCls }?.apply { isAccessible = true }
        }
        val bHeadersField = builderClass.declaredFields.firstOrNull { f ->
            !f.type.isPrimitive && !f.type.name.startsWith("java.") &&
                f.type != requestClass && f.type != responseClass && f.type != responseBodyClass &&
                runCatching {
                    f.type.declaredMethods.any { m ->
                        m.parameterCount == 2 && m.parameterTypes[0] == String::class.java && m.parameterTypes[1] == String::class.java
                    }
                }.getOrDefault(false)
        }?.apply { isAccessible = true }
        val headerAddMethod = bHeadersField?.type?.declaredMethods?.firstOrNull {
            it.parameterCount == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java
        }?.apply { isAccessible = true }

        respBuild.isAccessible = true
        return ResponseFactory(
            callRequestField = callRequestField,
            urlField = urlField,
            requestClass = requestClass,
            responseClass = responseClass,
            builderClass = builderClass,
            builderCtor = builderCtor,
            respBuild = respBuild,
            bReqField = bReqField,
            bCodeField = bCodeField,
            bMsgField = bMsgField,
            bProtoField = bProtoField,
            http11 = http11,
            bSentField = bSentField,
            bReceivedField = bReceivedField,
            bHeadersField = bHeadersField,
            headerAddMethod = headerAddMethod,
            responseBodyClass = responseBodyClass,
            bBodyField = bBodyField
        ).also { cachedFactory = it }
    }

    fun registerRealCallInterceptor(
        module: XposedModule,
        id: String,
        handler: (request: Any, url: String, factory: ResponseFactory, proceed: () -> Any?) -> Pair<Boolean, Any?>
    ) {
        val factory = getFactory()
        interceptors.removeAll { it.id == id }
        interceptors.add(RegisteredInterceptor(id, handler))
        if (realCallHookInstalled) return
        realCallHookInstalled = true

        val realCallChain = DexKitResolver.require<Method>(DexKitResolver.KEY_OKHTTP_REAL_CALL_CHAIN)
        module.deoptimize(realCallChain)
        module.hook(realCallChain)
            .setId("okhttp_real_call_pipeline")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val pair = factory.extractRequestAndUrl(chain.getThisObject())
                    ?: return@intercept chain.proceed()
                var proceeded = false
                var originalResponse: Any? = null
                val proceedOnce = {
                    if (!proceeded) {
                        proceeded = true
                        originalResponse = chain.proceed()
                    }
                    originalResponse
                }
                for (entry in interceptors) {
                    val (handled, response) = entry.handler(pair.first, pair.second, factory, proceedOnce)
                    if (handled) {
                        return@intercept response
                    }
                }
                proceedOnce()
            }
    }
}
