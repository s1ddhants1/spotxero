package app.spotxero.hooks.sharing

import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object CleanSharingHook {

    private const val TAG = "SpotXero.CleanSharing"

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
            module.log(Log.INFO, TAG, "Clean sharing disabled, skipping")
            return
        }

        hookShareUrlBuilder(module)
        hookShareResultConstructor(module)
    }

    private fun hookShareUrlBuilder(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_SHARE_URL_BUILDER)
        if (method == null) {
            module.log(Log.WARN, TAG, "Share URL builder not resolved, skipping")
            return
        }

        module.hook(method)
            .setId("clean_sharing_url_builder")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
                    return@intercept result
                }
                val url = result as? String ?: return@intercept result
                SharingLinksUtil.sanitizeUrl(url) ?: result
            }

        module.log(Log.INFO, TAG, "Hooked share URL builder: ${method.declaringClass.name}#${method.name}")
    }

    private fun hookShareResultConstructor(module: XposedModule) {
        val ctor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SHARE_RESULT_CTOR)
        if (ctor == null) {
            module.log(Log.WARN, TAG, "ShareResult constructor not resolved, skipping")
            return
        }

        module.hook(ctor)
            .setId("clean_sharing_result_ctor")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
                    val args = chain.args.toTypedArray()
                    val shareableUrl = args.getOrNull(0) as? String
                    val fullUrl = args.getOrNull(3) as? String
                    if (shareableUrl != null) args[0] = SharingLinksUtil.sanitizeUrl(shareableUrl)
                    if (fullUrl != null) args[3] = SharingLinksUtil.sanitizeUrl(fullUrl)
                    chain.proceed(args)
                } else {
                    chain.proceed()
                }
                null
            }

        module.log(Log.INFO, TAG, "Hooked ShareResult constructor: ${ctor.declaringClass.name}")
    }
}
