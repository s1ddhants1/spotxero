package app.spotxero.hooks.ads

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object AdResponseHook {

    fun install(module: XposedModule) {
        val wrapper = DexKitResolver.get<Method>(DexKitResolver.KEY_GET_ADS_WRAPPER)
        val parser = DexKitResolver.get<Method>(DexKitResolver.KEY_EMPTY_ADS_RESPONSE)
        if (wrapper == null || parser == null) {
            return
        }

        val emptyResponse = runCatching { parser.invoke(null, ByteArray(0)) }.getOrNull() ?: return

        module.deoptimize(wrapper)

        module.hook(wrapper)
            .setId("empty_ad_response")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    emptyResponse
                } else {
                    chain.proceed()
                }
            }
    }
}
