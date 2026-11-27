package app.spotxero.hooks.attestation

import android.net.Uri
import app.spotxero.core.DexKitResolver
import app.spotxero.hooks.network.OkHttpBridge
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object LoginClientTokenHook {

    private const val NATIVE_HTTP_CONNECTION = "com.spotify.core.http.NativeHttpConnection"
    private const val HTTP_REQUEST = "com.spotify.core.http.HttpRequest"
    private const val HTTP_OPTIONS = "com.spotify.core.http.HttpOptions"
    private const val NATIVE_HELPERS = "com.spotify.jvm.jni.NativeHelpers"
    private const val LOGIN5_HOST = "login5.spotify.com"
    private const val CLIENT_TOKEN_HEADER = "client-token"

    fun install(module: XposedModule) {
        hookNativeHttpConnection(module)
        hookClientTokenInterceptor(module)
    }

    private fun isLogin5Url(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(rawUrl).host }.getOrNull() ?: return false
        return host.equals(LOGIN5_HOST, ignoreCase = true)
    }

    private fun hookNativeHttpConnection(module: XposedModule) {
        val cl = DexKitResolver.require<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE).declaringClass.classLoader
        val connClass = Class.forName(NATIVE_HTTP_CONNECTION, false, cl)
        val reqClass = Class.forName(HTTP_REQUEST, false, cl)
        val optClass = Class.forName(HTTP_OPTIONS, false, cl)
        val helpersClass = Class.forName(NATIVE_HELPERS, false, cl)

        val sendMethod = connClass.getDeclaredMethod("send", reqClass, optClass)
        val getUrlMethod = reqClass.getMethod("getUrl")
        val getMethodMethod = reqClass.getMethod("getMethod")
        val getHeadersMethod = reqClass.getMethod("getHeaders")
        val getBodyMethod = reqClass.getMethod("getBody")
        val reqCtor = reqClass.getConstructor(
            String::class.java,
            String::class.java,
            ByteArray::class.java,
            ByteArray::class.java
        )
        val byteArrayToMapMethod = helpersClass.getMethod("byteArrayToMap", ByteArray::class.java)
        val mapToByteArrayMethod = helpersClass.getMethod("mapToByteArray", Map::class.java)

        module.deoptimize(sendMethod)
        module.hook(sendMethod)
            .setId("login5_native_client_token")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val req = chain.getArg(0) ?: return@intercept chain.proceed()
                val url = getUrlMethod.invoke(req) as? String
                if (isLogin5Url(url)) {
                    val rawHeaders = getHeadersMethod.invoke(req) as? ByteArray
                    @Suppress("UNCHECKED_CAST")
                    val headers = byteArrayToMapMethod.invoke(null, rawHeaders) as? Map<String, String>
                    if (headers != null && headers.keys.any { it.equals(CLIENT_TOKEN_HEADER, ignoreCase = true) }) {
                        val filtered = LinkedHashMap<String, String>(headers.size)
                        for ((k, v) in headers) {
                            if (!k.equals(CLIENT_TOKEN_HEADER, ignoreCase = true)) {
                                filtered[k] = v
                            }
                        }
                        val strippedBytes = mapToByteArrayMethod.invoke(null, filtered) as ByteArray
                        val sanitizedReq = reqCtor.newInstance(
                            url,
                            getMethodMethod.invoke(req),
                            strippedBytes,
                            getBodyMethod.invoke(req)
                        )
                        val modifiedArgs = chain.args.toTypedArray()
                        modifiedArgs[0] = sanitizedReq
                        return@intercept chain.proceed(modifiedArgs)
                    }
                }
                chain.proceed()
            }
    }

    private fun hookClientTokenInterceptor(module: XposedModule) {
        val interceptMethod = DexKitResolver.require<Method>(DexKitResolver.KEY_CLIENT_TOKEN_INTERCEPTOR)
        val reqToString = DexKitResolver.require<Method>(DexKitResolver.KEY_OKHTTP_REQUEST_TO_STRING)

        val requestClass = reqToString.declaringClass
        val responseClass = interceptMethod.returnType
        val chainClass = interceptMethod.parameterTypes[0]

        val chainRequestMethod = chainClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == requestClass
        }?.apply { isAccessible = true } ?: error("Missing OkHttp chain request method")
        val chainProceedMethod = chainClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == requestClass &&
                it.returnType == responseClass
        }?.apply { isAccessible = true } ?: error("Missing OkHttp chain proceed method")

        val urlField = OkHttpBridge.findRequestUrlField(requestClass)
            ?: error("Missing OkHttp request URL field")

        val headersField = requestClass.declaredFields.singleOrNull { f ->
            !Modifier.isStatic(f.modifiers) &&
                f.type.declaredFields.any { !Modifier.isStatic(it.modifiers) && it.type == Array<String>::class.java }
        }?.apply { isAccessible = true } ?: error("Missing OkHttp request headers field")
        val headersValuesField = headersField.type.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == Array<String>::class.java
        }?.apply { isAccessible = true } ?: error("Missing OkHttp headers array field")

        val reqCtor = requestClass.declaredConstructors.singleOrNull {
            it.parameterCount == 1 && !it.parameterTypes[0].isPrimitive
        }?.apply { isAccessible = true } ?: error("Missing OkHttp request builder constructor")
        val builderClass = reqCtor.parameterTypes[0]
        val newBuilderMethod = requestClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == builderClass
        }?.apply { isAccessible = true } ?: error("Missing OkHttp request newBuilder method")
        val builderObjectFields = builderClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

        module.deoptimize(interceptMethod)
        module.hook(interceptMethod)
            .setId("login5_okhttp_client_token")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val chainObj = chain.getArg(0) ?: return@intercept chain.proceed()
                val request = chainRequestMethod.invoke(chainObj) ?: return@intercept chain.proceed()
                val urlStr = urlField.get(request)?.toString()
                if (isLogin5Url(urlStr)) {
                    val values = headersField.get(request)?.let { headersValuesField.get(it) as? Array<*> }
                    val targetRequest = if (hasClientTokenHeader(values)) {
                        stripClientTokenFromRequest(
                            request,
                            newBuilderMethod,
                            reqCtor,
                            builderObjectFields
                        )
                    } else {
                        request
                    }
                    return@intercept chainProceedMethod.invoke(chainObj, targetRequest)
                }
                chain.proceed()
            }
    }

    private fun hasClientTokenHeader(values: Array<*>?): Boolean {
        if (values == null) return false
        var i = 0
        while (i + 1 < values.size) {
            val key = values[i] as? String
            if (key != null && key.equals(CLIENT_TOKEN_HEADER, ignoreCase = true)) {
                return true
            }
            i += 2
        }
        return false
    }

    private fun stripClientTokenFromRequest(
        request: Any,
        newBuilderMethod: Method,
        reqCtor: java.lang.reflect.Constructor<*>,
        builderFields: List<Field>
    ): Any {
        val builder = newBuilderMethod.invoke(request) ?: return request
        for (field in builderFields) {
            val candidate = field.get(builder) ?: continue
            val innerFields = candidate.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            if (innerFields.size == 1 && ArrayList::class.java.isAssignableFrom(innerFields[0].type)) {
                val listField = innerFields[0].apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val list = listField.get(candidate) as? MutableList<Any?> ?: continue
                var i = 0
                while (i + 1 < list.size) {
                    val key = list[i] as? String
                    if (key != null && key.equals(CLIENT_TOKEN_HEADER, ignoreCase = true)) {
                        list.removeAt(i)
                        list.removeAt(i)
                    } else {
                        i += 2
                    }
                }
                return reqCtor.newInstance(builder)
            }
        }
        return request
    }
}
