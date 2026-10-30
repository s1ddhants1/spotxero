package app.spotxero.hooks.ads

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object AdResponseHook {

    private const val TAG = "SpotXero.Ads"

    fun install(module: XposedModule) {
        val wrapper = DexKitResolver.get<Method>(DexKitResolver.KEY_GET_ADS_WRAPPER)
        val parser = DexKitResolver.get<Method>(DexKitResolver.KEY_EMPTY_ADS_RESPONSE)
        if (wrapper == null || parser == null) {
            module.log(Log.WARN, TAG, "GetAds targets not resolved, skipping ad response blocking")
            return
        }

        val emptyResponse = runCatching { parser.invoke(null, ByteArray(0)) }.getOrNull()
        if (emptyResponse == null) {
            module.log(Log.WARN, TAG, "Empty ad response could not be built, skipping")
            return
        }

        module.deoptimize(wrapper)

        module.hook(wrapper)
            .setId("empty_ad_response")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) {
                    emptyResponse
                } else {
                    chain.proceed()
                }
            }

        module.log(Log.INFO, TAG, "Hooked GetAds: ${wrapper.declaringClass.name}#${wrapper.name}")
    }
}
