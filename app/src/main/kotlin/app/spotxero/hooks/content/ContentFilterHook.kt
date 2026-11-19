package app.spotxero.hooks.content

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object ContentFilterHook {

    private val FILTERS = listOf(
        PrefKeys.KEY_HIDE_PODCASTS to ContentFilter.PODCASTS,
        PrefKeys.KEY_HIDE_AUDIOBOOKS to ContentFilter.AUDIOBOOKS
    )

    fun install(module: XposedModule) {
        hookProvidedItems(module)
        hookHomeSections(module)
        hookHomeChips(module)
        hookSearchChips(module)
        hookSearchResponse(module)
        hookBrowseSections(module)
        hookBrowseItems(module)
    }

    private fun hookProvidedItems(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_PROVIDED_ITEMS_LIST) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("hide_provided_items")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                applyListFilters(chain.proceed()) { filter, list -> filter.filterItems(list) }
            }
    }

    private fun hookHomeSections(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_HOME_SECTION_LIST) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("hide_home_sections")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                applyListFilters(chain.proceed()) { filter, list -> filter.filterSectionTypes(list) }
            }
    }

    private fun hookHomeChips(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_HOME_CHIPS_MAPPER) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("hide_home_chips")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                filterHomeChips(chain.args[0])
                val result = chain.proceed()
                filterHomeChips(result)
                result
            }
    }

    private fun hookSearchChips(module: XposedModule) {
        val ctor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SEARCH_CHIPS_CTOR) ?: return
        module.hook(ctor)
            .setId("hide_search_chips")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val chips = chain.args[0] as? MutableList<*>
                if (chips != null) {
                    activeFilters().forEach { it.filterSearchChips(chips) }
                }
                chain.proceed()
            }
    }

    private fun hookSearchResponse(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_SEARCH_RESPONSE_MAPPER) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("hide_search_response")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed() ?: return@intercept null
                val active = activeFilters()
                if (active.isEmpty()) return@intercept result
                for (f in result.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(result) ?: continue
                    if (v is MutableList<*>) {
                        active.forEach { it.filterSearchEntities(v) }
                    } else if (v !is String && !v.javaClass.isPrimitive) {
                        for (innerF in v.javaClass.declaredFields) {
                            if (List::class.java.isAssignableFrom(innerF.type)) {
                                innerF.isAccessible = true
                                val list = innerF.get(v) as? MutableList<*>
                                if (!list.isNullOrEmpty()) {
                                    active.forEach { it.filterSearchChips(list) }
                                }
                            }
                        }
                    }
                }
                result
            }
    }

    private fun hookBrowseSections(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_BROWSE_SECTION_LIST) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("hide_browse_sections")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                applyListFilters(chain.proceed()) { filter, list -> filter.filterBrowseSections(list) }
            }
    }

    private fun hookBrowseItems(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_CONTENT_CATEGORY_ITEMS_LIST) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("hide_browse_items")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                applyListFilters(chain.proceed()) { filter, list -> filter.filterBrowseItems(list) }
            }
    }

    private fun applyListFilters(
        result: Any?,
        transform: (ContentFilter, List<*>?) -> List<*>?
    ): Any? {
        var list: List<*>? = result as? List<*> ?: return result
        for ((prefKey, filter) in FILTERS) {
            if (PreferencesManager.isEnabled(prefKey, false)) {
                list = transform(filter, list)
            }
        }
        return list
    }

    private fun filterHomeChips(arg: Any?) {
        val chips = arg as? MutableList<*> ?: return
        activeFilters().forEach { it.filterHomeChips(chips) }
    }

    private fun activeFilters(): List<ContentFilter> = FILTERS.mapNotNull { (prefKey, filter) ->
        if (PreferencesManager.isEnabled(prefKey, false)) filter else null
    }
}
