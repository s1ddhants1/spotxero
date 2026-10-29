package app.spotxero.hooks

import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

abstract class SharedFeatureHookTemplate(
    private val module: XposedModule,
    private val preferenceKey: String,
    private val defaultEnabled: Boolean
) {
    fun install(targetMethod: Method) {
        module.hook(targetMethod).intercept { chain ->
            if (PreferencesManager.isEnabled(preferenceKey, defaultEnabled)) {
                interceptEnabled(chain)
            } else {
                chain.proceed()
            }
        }
    }

    protected abstract fun interceptEnabled(chain: XposedInterface.Chain): Any?
}

abstract class CleanSharingHookTemplate(module: XposedModule) : SharedFeatureHookTemplate(
    module = module,
    preferenceKey = PrefKeys.KEY_CLEAN_SHARING,
    defaultEnabled = true
)

abstract class SpotifyAmoledHookTemplate(module: XposedModule) : SharedFeatureHookTemplate(
    module = module,
    preferenceKey = PrefKeys.KEY_SPOTIFY_AMOLED,
    defaultEnabled = false
)
