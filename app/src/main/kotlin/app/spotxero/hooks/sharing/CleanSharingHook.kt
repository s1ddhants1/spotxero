package app.spotxero.hooks.sharing

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object CleanSharingHook {

    private const val TAG = "SpotXero.CleanSharing"

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
            module.log(Log.INFO, TAG, "Clean sharing disabled, skipping")
            return
        }

        val targets = buildList {
            DexKitResolver.get<Method>(DexKitResolver.KEY_SHARE_URL_BUILDER)
                ?.takeIf(::carriesUrl)
                ?.let(::add)
            DexKitResolver.get<List<Method>>(DexKitResolver.KEY_SHARE_RESPONSE_URLS)?.let(::addAll)
        }
        if (targets.isEmpty()) {
            module.log(Log.WARN, TAG, "No share URL targets resolved, skipping")
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

        module.log(
            Log.INFO,
            TAG,
            "Hooked ${targets.size} share URL targets: " +
                targets.joinToString { "${it.declaringClass.name}#${it.name}" }
        )
    }

    private fun carriesUrl(method: Method): Boolean =
        method.returnType == String::class.java ||
            method.returnType.declaredFields.any { it.type == Any::class.java }
}
