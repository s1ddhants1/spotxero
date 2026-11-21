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
