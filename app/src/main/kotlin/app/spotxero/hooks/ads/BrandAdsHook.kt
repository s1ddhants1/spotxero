package app.spotxero.hooks.ads

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object BrandAdsHook {

    fun install(module: XposedModule) {
        hookSections(module, DexKitResolver.KEY_HOME_SECTION_LIST, "hide_home_brand_ads", AdSectionFilter::filterHome)
        hookSections(module, DexKitResolver.KEY_BROWSE_SECTION_LIST, "hide_browse_brand_ads", AdSectionFilter::filterBrowse)
        hookSections(module, DexKitResolver.KEY_PLAYER_SECTION_LIST, "hide_player_ad_sections", AdSectionFilter::filterPlayer)
    }

    private fun hookSections(
        module: XposedModule,
        resolverKey: String,
        hookId: String,
        filter: (List<*>?) -> List<*>?
    ) {
        val method = DexKitResolver.get<Method>(resolverKey) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId(hookId)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    return@intercept result
                }
                val list = result as? List<*> ?: return@intercept result
                filter(list)
            }
    }
}
