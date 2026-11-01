package app.spotxero.hooks.ads

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object PlayerAdCardsHook {

    private const val TAG = "SpotXero.PlayerAdCards"
    private const val PLAYER_SECTION = "com.spotify.scrollsita.v1.Section"

    fun install(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_IMAGE_AD_PREDICATE)
        if (method == null ||
            method.declaringClass.name != PLAYER_SECTION ||
            method.parameterCount != 0 ||
            method.returnType != Boolean::class.javaPrimitiveType
        ) {
            module.log(Log.WARN, TAG, "Image brand ad predicate not resolved, skipping")
            return
        }

        module.deoptimize(method)

        module.hook(method)
            .setId("hide_player_image_ad")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    false
                } else {
                    chain.proceed()
                }
            }

        module.log(Log.INFO, TAG, "Hooked player image ad predicate: ${method.declaringClass.name}#${method.name}")
    }
}
