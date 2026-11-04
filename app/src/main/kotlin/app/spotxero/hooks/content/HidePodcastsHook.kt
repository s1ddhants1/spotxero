package app.spotxero.hooks.content

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object HidePodcastsHook {

    fun install(module: XposedModule) {
        hookProvidedItems(module)
        hookHomeSections(module)
    }

    private fun hookProvidedItems(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_PROVIDED_ITEMS_LIST) ?: return
        module.hook(method)
            .setId("hide_provided_podcasts")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PODCASTS, false)) {
                    return@intercept result
                }
                val list = result as? List<*> ?: return@intercept result
                PodcastDetector.filterPodcastItems(list)
            }
    }

    private fun hookHomeSections(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_HOME_SECTION_LIST) ?: return
        module.hook(method)
            .setId("hide_home_podcast_sections")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PODCASTS, false)) {
                    return@intercept result
                }
                val list = result as? List<*> ?: return@intercept result
                PodcastDetector.filterPodcastSections(list)
            }
    }
}
