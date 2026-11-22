package app.spotxero.hooks.attestation

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object ClientTokenHook {

    private const val CLIENT_TOKEN_SERVICE = "spotify.connectivity.auth.client_token.esperanto.proto.ClientTokenProvider"
    private const val METHOD_GET_TOKEN = "getToken"
    private const val METHOD_GET_ENCRYPTED_TOKEN = "getEncryptedToken"

    private val SYNTHETIC_CLIENT_TOKEN_ACQUIRE_RESULT: ByteArray by lazy {
        val tokenBytes = "spotxero_client_token_active".toByteArray(Charsets.UTF_8)
        val hashBytes = "d41d8cd98f00b204e9800998ecf8427e".toByteArray(Charsets.UTF_8)
        val expiresAt = 4102444800L
        val refreshAt = 4102444800L

        fun encodeVarint(v: Long): ByteArray {
            var n = v
            val out = ByteArrayOutputStream()
            while (n > 0x7f) {
                out.write(((n and 0x7f) or 0x80).toInt())
                n = n ushr 7
            }
            out.write((n and 0x7f).toInt())
            return out.toByteArray()
        }

        fun encodeField(fieldNumber: Int, wireType: Int, data: ByteArray): ByteArray {
            val tag = (fieldNumber shl 3) or wireType
            val out = ByteArrayOutputStream()
            out.write(encodeVarint(tag.toLong()))
            if (wireType == 2) {
                out.write(encodeVarint(data.size.toLong()))
            }
            out.write(data)
            return out.toByteArray()
        }

        val clientTokenStream = ByteArrayOutputStream()
        clientTokenStream.write(encodeField(1, 2, tokenBytes))
        clientTokenStream.write(encodeField(2, 0, encodeVarint(expiresAt)))
        clientTokenStream.write(encodeField(3, 0, encodeVarint(refreshAt)))
        clientTokenStream.write(encodeField(4, 2, hashBytes))

        val clientTokenBytes = clientTokenStream.toByteArray()

        val acquireResultStream = ByteArrayOutputStream()
        acquireResultStream.write(encodeField(1, 2, clientTokenBytes))
        acquireResultStream.toByteArray()
    }

    private val SYNTHETIC_ENCRYPTED_CLIENT_TOKEN: ByteArray by lazy {
        val tokenBytes = "spotxero_encrypted_token_active".toByteArray(Charsets.UTF_8)
        val tag = (1 shl 3) or 2
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(tokenBytes.size)
        out.write(tokenBytes)
        out.toByteArray()
    }

    private fun enabled() = PreferencesManager.isEnabled(PrefKeys.KEY_CLIENT_TOKEN, true)

    private fun isValidClientToken(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 16) return false
        return (bytes[0].toInt() and 0xFF) == 0x0A
    }

    fun install(module: XposedModule, targetClassLoader: ClassLoader? = null) {
        installClientTokenEsperanto(module)
    }

    private fun installClientTokenEsperanto(module: XposedModule) {
        val coroutineSingle = DexKitResolver.get<Method>(DexKitResolver.KEY_COROUTINE_CLIENT_BASE_CALL_SINGLE)
        if (coroutineSingle != null) {
            module.deoptimize(coroutineSingle)
            module.hook(coroutineSingle)
                .setId("client_token_coroutine_single_token")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    val service = chain.getArg(0) as? String
                    val method = chain.getArg(1) as? String
                    if (service == CLIENT_TOKEN_SERVICE && method == METHOD_GET_TOKEN) {
                        val result = runCatching { chain.proceed() }.getOrNull()
                        val bytes = result as? ByteArray
                        if (isValidClientToken(bytes)) {
                            return@intercept bytes
                        }
                        return@intercept SYNTHETIC_CLIENT_TOKEN_ACQUIRE_RESULT
                    }
                    chain.proceed()
                }
        }

        val clientSingle = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)
        if (clientSingle != null) {
            val cl = clientSingle.declaringClass.classLoader
            if (cl != null) {
                module.deoptimize(clientSingle)
                module.hook(clientSingle)
                    .setId("client_token_client_single_token")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (!enabled()) return@intercept result
                        val service = chain.getArg(0) as? String
                        val method = chain.getArg(1) as? String
                        if (service == CLIENT_TOKEN_SERVICE && method == METHOD_GET_TOKEN && result != null) {
                            return@intercept attachSingleFallback(result, cl, SYNTHETIC_CLIENT_TOKEN_ACQUIRE_RESULT)
                        }
                        result
                    }
            }
        }

        val clientStream = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_STREAM)
        if (clientStream != null) {
            val cl = clientStream.declaringClass.classLoader
            if (cl != null) {
                module.deoptimize(clientStream)
                module.hook(clientStream)
                    .setId("client_token_client_stream_token")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (!enabled()) return@intercept result
                        val service = chain.getArg(0) as? String
                        val method = chain.getArg(1) as? String
                        if (service == CLIENT_TOKEN_SERVICE && method == METHOD_GET_ENCRYPTED_TOKEN && result != null) {
                            return@intercept attachObservableFallback(result, cl, SYNTHETIC_ENCRYPTED_CLIENT_TOKEN)
                        }
                        result
                    }
            }
        }
    }

    private fun attachSingleFallback(single: Any, cl: ClassLoader, fallbackBytes: ByteArray): Any {
        val singleClass = single.javaClass
        val onErrorReturnItem = runCatching {
            singleClass.getMethod("onErrorReturnItem", Any::class.java)
        }.getOrNull()
        val withError = onErrorReturnItem?.invoke(single, fallbackBytes) ?: single

        val functionClass = runCatching {
            Class.forName("io.reactivex.rxjava3.functions.Function", false, cl)
        }.getOrNull() ?: return withError

        val mapMethod = runCatching {
            singleClass.getMethod("map", functionClass)
        }.getOrNull() ?: return withError

        val mapper = Proxy.newProxyInstance(
            cl,
            arrayOf(functionClass)
        ) { _, method, args ->
            if (method.name == "apply" && args != null && args.isNotEmpty()) {
                val item = args[0] as? ByteArray
                if (isValidClientToken(item)) item else fallbackBytes
            } else {
                null
            }
        }
        return mapMethod.invoke(withError, mapper) ?: withError
    }

    private fun attachObservableFallback(observable: Any, cl: ClassLoader, fallbackBytes: ByteArray): Any {
        val obsClass = observable.javaClass
        val onErrorReturnItem = runCatching {
            obsClass.getMethod("onErrorReturnItem", Any::class.java)
        }.getOrNull()
        val withError = onErrorReturnItem?.invoke(observable, fallbackBytes) ?: observable

        val functionClass = runCatching {
            Class.forName("io.reactivex.rxjava3.functions.Function", false, cl)
        }.getOrNull() ?: return withError

        val mapMethod = runCatching {
            obsClass.getMethod("map", functionClass)
        }.getOrNull() ?: return withError

        val mapper = Proxy.newProxyInstance(
            cl,
            arrayOf(functionClass)
        ) { _, method, args ->
            if (method.name == "apply" && args != null && args.isNotEmpty()) {
                val item = args[0] as? ByteArray
                if (isValidClientToken(item)) item else fallbackBytes
            } else {
                null
            }
        }
        return mapMethod.invoke(withError, mapper) ?: withError
    }
}
