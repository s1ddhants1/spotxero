package app.spotxero.hooks.ads

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object BrandAdsHook {

    fun install(module: XposedModule) {
        hookHomeSections(module)
        hookBrowseSections(module)
    }

    private fun hookHomeSections(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_HOME_SECTION_LIST) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("hide_home_brand_ads")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept result
                }
                val list = result as? List<*> ?: return@intercept result
                AdSectionFilter.filterHome(list)
            }
    }

    private fun hookBrowseSections(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_BROWSE_SECTION_LIST) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("hide_browse_brand_ads")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept result
                }
                val list = result as? List<*> ?: return@intercept result
                AdSectionFilter.filterBrowse(list)
            }
    }
}
