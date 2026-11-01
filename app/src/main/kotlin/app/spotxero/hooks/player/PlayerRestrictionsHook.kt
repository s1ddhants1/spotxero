package app.spotxero.hooks.player

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object PlayerRestrictionsHook {

    private const val TAG = "SpotXero.Restrictions"
    private const val RESTRICTIONS_CLASS = "com.spotify.player.model.Restrictions"

    fun install(module: XposedModule) {
        val methods = DexKitResolver.get<List<Method>>(DexKitResolver.KEY_PLAYER_RESTRICTIONS_METHODS)
        if (methods.isNullOrEmpty()) {
            module.log(Log.WARN, TAG, "Player restrictions methods not resolved, skipping")
            return
        }

        val emptyValues = runCatching {
            val cl = methods.first().declaringClass.classLoader
            val restrictionsClass = Class.forName(RESTRICTIONS_CLASS, false, cl)
            val emptyInstance = restrictionsClass.getField("EMPTY").get(null)
            methods.associateWith { method ->
                restrictionsClass.getMethod(method.name).invoke(emptyInstance)
            }
        }.getOrNull() ?: emptyMap()

        for ((index, method) in methods.withIndex()) {
            val emptyValue = emptyValues[method]
            module.deoptimize(method)
            module.hook(method)
                .setId("player_restrictions_$index")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    emptyValue ?: result
                }
        }

        module.log(
            Log.INFO,
            TAG,
            "Hooked ${methods.size} restrictions methods: ${methods.joinToString { "${it.declaringClass.simpleName}#${it.name}" }}"
        )
    }
}
