package app.spotxero.hooks.attestation

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

object ClientTokenHook {

    private const val TAG = "SpotXero.ClientToken"
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

    fun install(module: XposedModule, targetClassLoader: ClassLoader? = null) {
        val cl = targetClassLoader
            ?: DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)?.declaringClass?.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: return

        installOrbitConnectivity(module, cl)
        installClientTokenEsperanto(module)
    }

    private fun installOrbitConnectivity(module: XposedModule, classLoader: ClassLoader) {
        val configClass = runCatching {
            Class.forName("com.spotify.connectivity.ApplicationScopeConfiguration", false, classLoader)
        }.getOrNull()

        if (configClass != null) {
            val setEnableClientToken = runCatching {
                configClass.getMethod("setEnableClientToken", Boolean::class.javaPrimitiveType)
            }.getOrNull()
            if (setEnableClientToken != null) {
                module.deoptimize(setEnableClientToken)
                module.hook(setEnableClientToken)
                    .setId("orbit_set_enable_client_token")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (!enabled()) return@intercept chain.proceed()
                        chain.proceed(arrayOf(java.lang.Boolean.FALSE))
                    }
            }

            val getEnableClientToken = runCatching {
                configClass.getMethod("getEnableClientToken")
            }.getOrNull()
            if (getEnableClientToken != null) {
                module.deoptimize(getEnableClientToken)
                module.hook(getEnableClientToken)
                    .setId("orbit_get_enable_client_token")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (!enabled()) return@intercept chain.proceed()
                        false
                    }
            }
        }

        val nativeScopeClass = runCatching {
            Class.forName("com.spotify.connectivity.NativeApplicationScope", false, classLoader)
        }.getOrNull()

        val createMethod = nativeScopeClass?.declaredMethods?.firstOrNull { it.name == "create" }
        if (createMethod != null && configClass != null) {
            val clientTokenField = runCatching {
                configClass.getDeclaredField("enableClientToken").apply { isAccessible = true }
            }.getOrNull()
            val setClientTokenMethod = runCatching {
                configClass.getMethod("setEnableClientToken", Boolean::class.javaPrimitiveType)
            }.getOrNull()

            module.deoptimize(createMethod)
            module.hook(createMethod)
                .setId("orbit_native_app_scope_create")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (enabled()) {
                        for (arg in chain.args) {
                            if (arg != null && configClass.isInstance(arg)) {
                                runCatching { clientTokenField?.setBoolean(arg, false) }
                                runCatching { setClientTokenMethod?.invoke(arg, false) }
                            }
                        }
                    }
                    chain.proceed()
                }
        }
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
                        return@intercept SYNTHETIC_CLIENT_TOKEN_ACQUIRE_RESULT
                    }
                    chain.proceed()
                }
        }

        val clientSingle = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)
        if (clientSingle != null) {
            val cl = clientSingle.declaringClass.classLoader
            val singleClass = cl?.let { runCatching { Class.forName("io.reactivex.rxjava3.core.Single", false, it) }.getOrNull() }
            val justMethod = singleClass?.let { runCatching { it.getMethod("just", Any::class.java) }.getOrNull() }

            module.deoptimize(clientSingle)
            module.hook(clientSingle)
                .setId("client_token_client_single_token")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    val service = chain.getArg(0) as? String
                    val method = chain.getArg(1) as? String
                    if (service == CLIENT_TOKEN_SERVICE && method == METHOD_GET_TOKEN && justMethod != null) {
                        val syntheticSingle = justMethod.invoke(null, SYNTHETIC_CLIENT_TOKEN_ACQUIRE_RESULT)
                        if (syntheticSingle != null) {
                            return@intercept syntheticSingle
                        }
                    }
                    chain.proceed()
                }
        }

        val clientStream = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_STREAM)
        if (clientStream != null) {
            val cl = clientStream.declaringClass.classLoader
            val obsClass = cl?.let { runCatching { Class.forName("io.reactivex.rxjava3.core.Observable", false, it) }.getOrNull() }
            val justMethod = obsClass?.let { runCatching { it.getMethod("just", Any::class.java) }.getOrNull() }

            module.deoptimize(clientStream)
            module.hook(clientStream)
                .setId("client_token_client_stream_token")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    val service = chain.getArg(0) as? String
                    val method = chain.getArg(1) as? String
                    if (service == CLIENT_TOKEN_SERVICE && method == METHOD_GET_ENCRYPTED_TOKEN && justMethod != null) {
                        val synth = justMethod.invoke(null, SYNTHETIC_ENCRYPTED_CLIENT_TOKEN)
                        if (synth != null) {
                            return@intercept synth
                        }
                    }
                    chain.proceed()
                }
        }
    }
}
