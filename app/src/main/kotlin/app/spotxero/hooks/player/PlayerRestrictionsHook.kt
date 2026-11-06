package app.spotxero.hooks.player

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object PlayerRestrictionsHook {

    private const val RESTRICTIONS_CLASS = "com.spotify.player.model.Restrictions"

    fun install(module: XposedModule) {
        installConverterHook(module)
        installRestrictionMethodsHook(module)
        installMftDisallowHandlerHook(module)
        installEsRestrictionsHook(module)
    }

    private fun installConverterHook(module: XposedModule) {
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
        }
    }

    private fun installRestrictionMethodsHook(module: XposedModule) {
        val methods = DexKitResolver.get<List<Method>>(DexKitResolver.KEY_PLAYER_RESTRICTIONS_METHODS)
        if (methods.isNullOrEmpty()) {
            return
        }

        val emptyInstance = runCatching {
            val cl = methods.first().declaringClass.classLoader
            val restrictionsClass = Class.forName(RESTRICTIONS_CLASS, false, cl)
            restrictionsClass.getField("EMPTY").get(null)
        }.getOrNull()

        val emptyValues = runCatching {
            val cl = methods.first().declaringClass.classLoader
            val restrictionsClass = Class.forName(RESTRICTIONS_CLASS, false, cl)
            val empty = emptyInstance ?: restrictionsClass.getField("EMPTY").get(null)
            methods.associateWith { method ->
                runCatching { restrictionsClass.getMethod(method.name).invoke(empty) }.getOrNull() ?: emptySet<Any>()
            }
        }.getOrNull() ?: emptyMap()

        for ((index, method) in methods.withIndex()) {
            val emptyValue = emptyValues[method] ?: emptySet<Any>()
            module.deoptimize(method)
            module.hook(method)
                .setId("player_restrictions_$index")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    emptyValue
                }
        }
    }

    private fun installMftDisallowHandlerHook(module: XposedModule) {
        val handler = DexKitResolver.get<Method>(DexKitResolver.KEY_MFT_DISALLOW_RESTRICTION_HANDLER) ?: return
        val absentInstance = DexKitResolver.get<Any>(DexKitResolver.KEY_OPTIONAL_ABSENT_INSTANCE)

        module.deoptimize(handler)
        module.hook(handler)
            .setId("mft_disallow_restriction_handler")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept chain.proceed()
                }
                var hadMftDisallow = false
                for (arg in chain.args) {
                    if (arg == null) continue
                    var currentClass: Class<*>? = arg.javaClass
                    while (currentClass != null && currentClass != Any::class.java) {
                        for (field in currentClass.declaredFields) {
                            if (List::class.java.isAssignableFrom(field.type)) {
                                runCatching {
                                    field.isAccessible = true
                                    val list = field.get(arg) as? List<*>
                                    if (list != null && list.contains("mft_disallow")) {
                                        hadMftDisallow = true
                                        val filtered = list.filter { it != "mft_disallow" }
                                        field.set(arg, filtered)
                                    }
                                }
                            }
                        }
                        currentClass = currentClass.superclass
                    }
                }
                val result = chain.proceed()
                if (absentInstance != null) {
                    val containsValue = runCatching {
                        val isPresentMethod = result?.javaClass?.methods?.firstOrNull {
                            it.name == "c" && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
                        } ?: result?.javaClass?.methods?.firstOrNull {
                            it.name == "isPresent" && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
                        }
                        isPresentMethod?.invoke(result) as? Boolean
                    }.getOrNull()
                    if (containsValue == true) {
                        return@intercept absentInstance
                    }
                }
                result
            }
    }

    private fun installEsRestrictionsHook(module: XposedModule) {
        val methods = DexKitResolver.get<List<Method>>(DexKitResolver.KEY_PLAYER_RESTRICTIONS_METHODS)
        val cl = methods?.firstOrNull()?.declaringClass?.classLoader
            ?: DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_RESTRICTIONS_CONVERTER)?.declaringClass?.classLoader
            ?: return

        val esRestrictionsMethods = runCatching {
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
    }
}
