package app.spotxero.hooks

import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

abstract class PremiumHookTemplate(
    private val module: XposedModule
) {
    fun install(targetMethod: Method) {
        module.hook(targetMethod).intercept { chain ->
            if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                interceptEnabled(chain)
            } else {
                chain.proceed()
            }
        }
    }

    protected abstract fun interceptEnabled(chain: XposedInterface.Chain): Any?
}
