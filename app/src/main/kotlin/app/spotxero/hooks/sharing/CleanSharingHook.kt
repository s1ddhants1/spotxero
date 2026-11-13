package app.spotxero.hooks.sharing

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object CleanSharingHook {

    fun install(module: XposedModule) {
        val targets = buildList {
            DexKitResolver.get<Method>(DexKitResolver.KEY_SHARE_URL_BUILDER)
                ?.takeIf(::carriesUrl)
                ?.let(::add)
            DexKitResolver.get<List<Method>>(DexKitResolver.KEY_SHARE_RESPONSE_URLS)?.let(::addAll)
        }
        if (targets.isEmpty()) {
            return
        }

        for (method in targets) {
            val id = "clean_sharing_${method.declaringClass.simpleName}_${method.name}"
            module.deoptimize(method)
            module.hook(method)
                .setId(id)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
                        return@intercept result
                    }
                    SharingLinksUtil.sanitizeResult(result)
                }
        }
    }

    private fun carriesUrl(method: Method): Boolean =
        method.returnType == String::class.java ||
            method.returnType.declaredFields.any { it.type == Any::class.java }
}
