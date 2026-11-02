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
        val converter = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_RESTRICTIONS_CONVERTER)
        val emptyInstance = runCatching {
            val cl = converter?.declaringClass?.classLoader
                ?: DexKitResolver.get<List<Method>>(DexKitResolver.KEY_PLAYER_RESTRICTIONS_METHODS)?.firstOrNull()?.declaringClass?.classLoader
                ?: return@runCatching null
            val restrictionsClass = Class.forName(RESTRICTIONS_CLASS, false, cl)
            restrictionsClass.getField("EMPTY").get(null)
        }.getOrNull()

        if (converter != null && emptyInstance != null) {
            module.deoptimize(converter)
            module.hook(converter)
                .setId("player_restrictions_converter")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    emptyInstance
                }
            module.log(Log.INFO, TAG, "Hooked restrictions converter: ${converter.declaringClass.name}#${converter.name}")
        }

        val methods = DexKitResolver.get<List<Method>>(DexKitResolver.KEY_PLAYER_RESTRICTIONS_METHODS)
        if (methods.isNullOrEmpty()) {
            if (converter == null) {
                module.log(Log.WARN, TAG, "Player restrictions methods not resolved, skipping")
            }
            return
        }

        val emptyValues = runCatching {
            val cl = methods.first().declaringClass.classLoader
            val restrictionsClass = Class.forName(RESTRICTIONS_CLASS, false, cl)
            val empty = emptyInstance ?: restrictionsClass.getField("EMPTY").get(null)
            methods.associateWith { method ->
                restrictionsClass.getMethod(method.name).invoke(empty)
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

        val esRestrictionsMethods = runCatching {
            val cl = methods.first().declaringClass.classLoader
            val esClass = Class.forName("com.spotify.player.esperanto.proto.EsRestrictions\$Restrictions", false, cl)
            esClass.declaredMethods.filter {
                it.parameterCount == 0 && Map::class.java.isAssignableFrom(it.returnType)
            }
        }.getOrNull() ?: emptyList()

        for ((index, method) in esRestrictionsMethods.withIndex()) {
            module.deoptimize(method)
            module.hook(method)
                .setId("es_restrictions_map_$index")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    emptyMap<Any, Any>()
                }
        }
        if (esRestrictionsMethods.isNotEmpty()) {
            module.log(Log.INFO, TAG, "Hooked ${esRestrictionsMethods.size} EsRestrictions map getters")
        }
    }
}
