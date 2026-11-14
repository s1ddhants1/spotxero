package app.spotxero.hooks.lyrics

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object LyricsHook {

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_UNCAPPED_LYRICS, true)) {
            return
        }

        val capStatusGetter = DexKitResolver.get<Method>(DexKitResolver.KEY_LYRICS_CAP_STATUS_GETTER) ?: return
        val enumType = capStatusGetter.returnType
        val uncappedConstant = enumType.enumConstants?.firstOrNull {
            (it as? Enum<*>)?.name == "UNCAPPED"
        } ?: return

        module.deoptimize(capStatusGetter)
        module.hook(capStatusGetter)
            .setId("lyrics_uncapped_status")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_UNCAPPED_LYRICS, true)) {
                    return@intercept uncappedConstant
                }
                chain.proceed()
            }
    }
}
