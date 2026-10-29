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

    fun install(module: XposedModule) {
        hookImageAdFlag(module)
        hookEmbeddedAdPredicate(module)
    }

    private fun hookImageAdFlag(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_IMAGE_AD_FLAG) ?: return
        module.hook(method)
            .setId("hide_player_image_ad")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PLAYER_ADS, true)) {
                    false
                } else {
                    chain.proceed()
                }
            }
        module.log(Log.INFO, TAG, "Hooked player image ad flag: ${method.declaringClass.name}#${method.name}")
    }

    private fun hookEmbeddedAdPredicate(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_EMBEDDED_AD_PREDICATE) ?: return
        module.hook(method)
            .setId("hide_player_embedded_ad")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PLAYER_ADS, true)) {
                    false
                } else {
                    chain.proceed()
                }
            }
        module.log(Log.INFO, TAG, "Hooked embedded ad predicate: ${method.declaringClass.name}#${method.name}")
    }
}
