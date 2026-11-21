package app.spotxero.hooks.attestation

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

object ClientSpoofHook {

    private const val TAG = "SpotXero.ClientSpoof"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val GMS_SERVICE_MISSING = 1
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
            val out = java.io.ByteArrayOutputStream()
            while (n > 0x7f) {
                out.write(((n and 0x7f) or 0x80).toInt())
                n = n ushr 7
            }
            out.write((n and 0x7f).toInt())
            return out.toByteArray()
        }

        fun encodeField(fieldNumber: Int, wireType: Int, data: ByteArray): ByteArray {
            val tag = (fieldNumber shl 3) or wireType
            val out = java.io.ByteArrayOutputStream()
            out.write(encodeVarint(tag.toLong()))
            if (wireType == 2) {
                out.write(encodeVarint(data.size.toLong()))
            }
            out.write(data)
            return out.toByteArray()
        }

        val clientTokenStream = java.io.ByteArrayOutputStream()
        clientTokenStream.write(encodeField(1, 2, tokenBytes))
        clientTokenStream.write(encodeField(2, 0, encodeVarint(expiresAt)))
        clientTokenStream.write(encodeField(3, 0, encodeVarint(refreshAt)))
        clientTokenStream.write(encodeField(4, 2, hashBytes))

        val clientTokenBytes = clientTokenStream.toByteArray()

        val acquireResultStream = java.io.ByteArrayOutputStream()
        acquireResultStream.write(encodeField(1, 2, clientTokenBytes))
        acquireResultStream.toByteArray()
    }

    private val SYNTHETIC_ENCRYPTED_CLIENT_TOKEN: ByteArray by lazy {
        val tokenBytes = "spotxero_encrypted_token_active".toByteArray(Charsets.UTF_8)
        val tag = (1 shl 3) or 2
        val out = java.io.ByteArrayOutputStream()
        out.write(tag)
        out.write(tokenBytes.size)
        out.write(tokenBytes)
        out.toByteArray()
    }

    private fun enabled() = PreferencesManager.isEnabled(PrefKeys.KEY_CLIENT_SPOOF, true)

    fun install(module: XposedModule, targetClassLoader: ClassLoader? = null) {
        val cl = targetClassLoader
            ?: DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)?.declaringClass?.classLoader
            ?: DexKitResolver.get<List<Method>>(DexKitResolver.KEY_PLAY_INTEGRITY_OUTCOMES)?.firstOrNull()?.declaringClass?.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: return

        installOutcomeSuppression(module)
        installServiceBindingBlock(module, cl)
        installPackageManagerGmsHide(module, cl)
        installGoogleApiAvailability(module, cl)
        installPlayIntegrityUnavailability(module, cl)
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
                .setId("client_spoof_coroutine_single_token")
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
                .setId("client_spoof_client_single_token")
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
                .setId("client_spoof_client_stream_token")
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

    private fun installOutcomeSuppression(module: XposedModule) {
        val emitters = DexKitResolver
            .get<List<Method>>(DexKitResolver.KEY_PLAY_INTEGRITY_OUTCOMES)
            .orEmpty()
        if (emitters.isEmpty()) {
            Log.i(TAG, "No Play Integrity outcome emitters resolved")
            return
        }
        emitters.forEach { emitter ->
            module.deoptimize(emitter)
            module.hook(emitter)
                .setId("client_spoof_outcome_${emitter.name}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    Log.i(TAG, "Suppressed Play Integrity outcome report: ${emitter.name}")
                    null
                }
        }
        Log.i(TAG, "Suppressed ${emitters.size} Play Integrity outcome emitters")
    }

    private fun installServiceBindingBlock(module: XposedModule, classLoader: ClassLoader) {
        val contextWrapperClass = runCatching {
            Class.forName("android.content.ContextWrapper", false, classLoader)
        }.getOrNull() ?: ContextWrapper::class.java

        for (method in contextWrapperClass.methods) {
            if (method.name == "bindService" && method.parameterTypes.isNotEmpty() &&
                method.parameterTypes[0] == Intent::class.java
            ) {
                module.deoptimize(method)
                module.hook(method)
                    .setId("client_spoof_bind_service_${method.parameterCount}")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (!enabled()) return@intercept chain.proceed()
                        val intent = chain.getArg(0) as? Intent
                        if (isGmsIntent(intent)) {
                            Log.i(TAG, "Blocked GMS service binding (${intent?.action ?: intent?.`package`}) for non-GMS emulation")
                            return@intercept false
                        }
                        chain.proceed()
                    }
            }
        }
    }

    private fun isGmsIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val pkg = intent.`package`
        val componentPkg = intent.component?.packageName
        val action = intent.action
        return pkg == GMS_PACKAGE ||
            componentPkg == GMS_PACKAGE ||
            action == "com.google.android.play.core.expressintegrityservice.BIND_EXPRESS_INTEGRITY_SERVICE" ||
            action == "com.google.android.play.core.integrityservice.BIND_INTEGRITY_SERVICE" ||
            (action != null && action.startsWith("com.google.android.gms"))
    }

    private fun installPackageManagerGmsHide(module: XposedModule, classLoader: ClassLoader) {
        val apmClass = runCatching {
            Class.forName("android.app.ApplicationPackageManager", false, classLoader)
        }.getOrNull() ?: return

        for (method in apmClass.declaredMethods) {
            if (method.name in listOf("getPackageInfo", "getApplicationInfo") &&
                method.parameterTypes.isNotEmpty() &&
                method.parameterTypes[0] == String::class.java
            ) {
                module.deoptimize(method)
                module.hook(method)
                    .setId("client_spoof_pm_${method.name}_${method.parameterCount}")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (!enabled()) return@intercept chain.proceed()
                        val pkgName = chain.getArg(0) as? String
                        if (pkgName == GMS_PACKAGE) {
                            Log.i(TAG, "Simulated NameNotFoundException for $GMS_PACKAGE")
                            throw PackageManager.NameNotFoundException(GMS_PACKAGE)
                        }
                        chain.proceed()
                    }
            }
        }
    }

    private fun installGoogleApiAvailability(module: XposedModule, classLoader: ClassLoader) {
        val availabilityClasses = listOf(
            "com.google.android.gms.common.GoogleApiAvailability",
            "com.google.android.gms.common.GoogleApiAvailabilityLight",
            "com.google.android.gms.common.GooglePlayServicesUtil",
            "com.google.android.gms.common.GooglePlayServicesUtilLight"
        )

        for (className in availabilityClasses) {
            val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                if (method.name == "isGooglePlayServicesAvailable" &&
                    method.returnType == Int::class.javaPrimitiveType
                ) {
                    module.deoptimize(method)
                    module.hook(method)
                        .setId("client_spoof_gms_avail_${clazz.simpleName}_${method.parameterCount}")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (!enabled()) return@intercept chain.proceed()
                            Log.i(TAG, "isGooglePlayServicesAvailable intercepted: returning SERVICE_MISSING")
                            GMS_SERVICE_MISSING
                        }
                }
            }
        }
    }

    private fun installPlayIntegrityUnavailability(module: XposedModule, classLoader: ClassLoader) {
        val factoryClass = runCatching {
            Class.forName("com.google.android.play.core.integrity.IntegrityManagerFactory", false, classLoader)
        }.getOrNull()

        if (factoryClass != null) {
            for (method in factoryClass.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) continue
                if (method.name in listOf("createStandard", "create")) {
                    module.deoptimize(method)
                    module.hook(method)
                        .setId("client_spoof_factory_${method.name}")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (!enabled()) return@intercept chain.proceed()
                            val targetInterface = method.returnType
                            if (targetInterface.isInterface) {
                                createManagerProxy(targetInterface, classLoader)
                            } else {
                                chain.proceed()
                            }
                        }
                }
            }
        }

        val managerClasses = listOf(
            "com.google.android.play.core.integrity.StandardIntegrityManager",
            "com.google.android.play.core.integrity.IntegrityManager"
        )

        var hookedCount = 0
        for (className in managerClasses) {
            val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                if (Modifier.isAbstract(method.modifiers)) continue
                if (method.name in listOf("prepareIntegrityToken", "requestIntegrityToken", "request")) {
                    module.deoptimize(method)
                    module.hook(method)
                        .setId("client_spoof_integrity_${method.name}")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (!enabled()) return@intercept chain.proceed()
                            Log.i(TAG, "Intercepted Play Integrity ${method.name}, returning unavailable task (non-GMS fallback)")
                            val unavailableTask = createUnavailableTask(classLoader)
                            unavailableTask ?: chain.proceed()
                        }
                    hookedCount++
                }
            }
        }
        if (hookedCount > 0) {
            Log.i(TAG, "Play Integrity unavailability emulation installed on $hookedCount methods")
        }
    }

    private fun createManagerProxy(targetInterface: Class<*>, classLoader: ClassLoader): Any {
        return Proxy.newProxyInstance(
            classLoader,
            arrayOf(targetInterface)
        ) { _, method, args ->
            when (method.name) {
                "prepareIntegrityToken", "requestIntegrityToken", "request" -> {
                    createUnavailableTask(classLoader)
                }
                "equals" -> args?.getOrNull(0) === this
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "PlayIntegrityUnavailableProxy"
                else -> null
            }
        }
    }

    private fun createUnavailableTask(classLoader: ClassLoader): Any? {
        return runCatching {
            val tasksClass = Class.forName("com.google.android.gms.tasks.Tasks", false, classLoader)
            val forExceptionMethod = tasksClass.getMethod("forException", Exception::class.java)

            val exception = runCatching {
                val exceptionClass = Class.forName(
                    "com.google.android.play.core.integrity.StandardIntegrityException",
                    false,
                    classLoader
                )
                val ctor = exceptionClass.getDeclaredConstructor(
                    Int::class.javaPrimitiveType,
                    Throwable::class.java
                )
                ctor.isAccessible = true
                ctor.newInstance(-1, null) as Exception
            }.getOrNull() ?: runCatching {
                val exceptionClass = Class.forName(
                    "com.google.android.play.core.integrity.IntegrityServiceException",
                    false,
                    classLoader
                )
                val ctor = exceptionClass.getDeclaredConstructor(
                    Int::class.javaPrimitiveType,
                    Throwable::class.java
                )
                ctor.isAccessible = true
                ctor.newInstance(-1, null) as Exception
            }.getOrNull() ?: runCatching {
                val apiExceptionClass = Class.forName(
                    "com.google.android.gms.common.api.ApiException",
                    false,
                    classLoader
                )
                val statusClass = Class.forName(
                    "com.google.android.gms.common.api.Status",
                    false,
                    classLoader
                )
                val statusCtor = statusClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                statusCtor.isAccessible = true
                val status = statusCtor.newInstance(-1)
                val apiExceptionCtor = apiExceptionClass.getDeclaredConstructor(statusClass)
                apiExceptionCtor.isAccessible = true
                apiExceptionCtor.newInstance(status) as Exception
            }.getOrNull() ?: java.io.IOException("Google Play Services unavailable")

            forExceptionMethod.invoke(null, exception)
        }.getOrNull()
    }
}
