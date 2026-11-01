package app.spotxero.hooks.ads

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object AdSkipHook {

    private const val TAG = "SpotXero.AdSkip"
    private const val CONTEXT_TRACK = "com.spotify.player.model.ContextTrack"

    fun install(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_IS_ADVERTISEMENT_CHECK)
        if (method == null ||
            method.parameterCount != 1 ||
            method.parameterTypes[0].name != CONTEXT_TRACK ||
            method.returnType != Boolean::class.javaPrimitiveType
        ) {
            module.log(Log.WARN, TAG, "is_advertisement check not resolved, skipping ad blocking")
            return
        }

        module.deoptimize(method)

        module.hook(method)
            .setId("ad_skip_boolean")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    false
                } else {
                    chain.proceed()
                }
            }

        module.log(Log.INFO, TAG, "Hooked ad detection: ${method.declaringClass.name}#${method.name}")
    }
}
