package app.spotxero.hooks.attestation

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object ClientSpoofHook {

    private const val TAG = "SpotXero.ClientSpoof"

    private fun enabled() = PreferencesManager.isEnabled(PrefKeys.KEY_CLIENT_SPOOF, true)

    fun install(module: XposedModule) {
        installOutcomeSuppression(module)
        installPlayIntegrityUnavailability(module)
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

    private fun installPlayIntegrityUnavailability(module: XposedModule) {
        val cl = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)
            ?.declaringClass?.classLoader
            ?: DexKitResolver.get<List<Method>>(DexKitResolver.KEY_PLAY_INTEGRITY_OUTCOMES)
                ?.firstOrNull()?.declaringClass?.classLoader
            ?: return

        val managerClasses = listOf(
            "com.google.android.play.core.integrity.StandardIntegrityManager",
            "com.google.android.play.core.integrity.IntegrityManager"
        )

        var hookedCount = 0
        for (className in managerClasses) {
            val clazz = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                if (method.name in listOf("prepareIntegrityToken", "requestIntegrityToken")) {
                    module.deoptimize(method)
                    module.hook(method)
                        .setId("client_spoof_integrity_${method.name}")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (!enabled()) return@intercept chain.proceed()
                            Log.i(TAG, "Intercepted Play Integrity ${method.name}, returning unavailable task (non-GMS fallback)")
                            val unavailableTask = createUnavailableTask(cl)
                            if (unavailableTask != null) {
                                unavailableTask
                            } else {
                                chain.proceed()
                            }
                        }
                    hookedCount++
                }
            }
        }
        if (hookedCount > 0) {
            Log.i(TAG, "Play Integrity unavailability emulation installed on $hookedCount methods")
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
            }.getOrNull() ?: java.io.IOException("Google Play Services unavailable")

            forExceptionMethod.invoke(null, exception)
        }.getOrNull()
    }
}
