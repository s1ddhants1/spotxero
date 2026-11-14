package app.spotxero.hooks.attestation

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Neutralizes Google Play Integrity API attestation and telemetry sinks.
 *
 * Spotify on Android requests client tokens via its native connectivity engine.
 * Neutralizing Play Integrity checks prevents attestation failures from blocking
 * token issuance or flagging the client session, while leaving native HTTP/AP
 * connection streams intact.
 */
object ClientSpoofHook {

    private const val TAG = "SpotXero.ClientSpoof"

    private fun enabled() = PreferencesManager.isEnabled(PrefKeys.KEY_CLIENT_SPOOF, true)

    fun install(module: XposedModule) {
        if (!enabled()) {
            Log.i(TAG, "ClientSpoofHook disabled by preferences")
            return
        }
        Log.i(TAG, "Installing ClientSpoofHook (Play Integrity attestation neutralization)...")
        hookPlayIntegrity(module)
    }

    private fun hookPlayIntegrity(module: XposedModule) {
        val piInit = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAY_INTEGRITY_INIT)
        if (piInit != null) {
            Log.i(TAG, "Hooking Play Integrity init: $piInit")
            module.deoptimize(piInit)
            module.hook(piInit)
                .setId("client_spoof_play_integrity_init")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    Log.i(TAG, "Neutralized Play Integrity init call: ${piInit.name}")
                    null
                }
        }

        val pitSink = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAY_INTEGRITY_RESULT_SINK)
        if (pitSink != null) {
            Log.i(TAG, "Hooking Play Integrity result sink: $pitSink")
            module.deoptimize(pitSink)
            module.hook(pitSink)
                .setId("client_spoof_pit_sink")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    Log.i(TAG, "Neutralized PitResult telemetry event: ${pitSink.name}")
                    null
                }
        }
    }
}
