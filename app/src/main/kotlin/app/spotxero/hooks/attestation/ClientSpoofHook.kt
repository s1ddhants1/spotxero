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
        if (!enabled()) {
            Log.i(TAG, "ClientSpoofHook disabled by preferences")
            return
        }
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
}
