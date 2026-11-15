package app.spotxero.hooks.ads

import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object ContextMenuAdBlockHook {

    private val BLOCKED_EXACT_IDS = setOf(
        "context_menu_remove_ads",
        "playlist_entity_reinventfree_adsfree_context_menu_item",
        "premium_destination_play_full_song"
    )

    private val BLOCKED_SUBSTRINGS = listOf(
        "remove_ads",
        "adsfree",
        "premium_destination"
    )

    private val itemStringFieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_FILTER_CONTEXT_MENU, true)) {
            return
        }

        val ctor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_CONTEXT_MENU_VIEW_MODEL_CTOR) ?: return

        module.deoptimize(ctor)
        module.hook(ctor)
            .setId("context_menu_ad_blocker")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_FILTER_CONTEXT_MENU, true)) {
                    val rawItems = chain.getArg(1) as? List<*>
                    if (!rawItems.isNullOrEmpty()) {
                        val filteredItems = rawItems.filterNot { item ->
                            isAdContextMenuItem(item)
                        }
                        if (filteredItems.size != rawItems.size) {
                            val newArgs = arrayOf(
                                chain.getArg(0),
                                filteredItems,
                                chain.getArg(2)
                            )
                            return@intercept chain.proceed(newArgs)
                        }
                    }
                }
                chain.proceed()
            }
    }

    private fun isAdContextMenuItem(item: Any?): Boolean {
        if (item == null) return false
        val fields = itemStringFieldsCache.computeIfAbsent(item.javaClass) { clazz ->
            clazz.declaredFields
                .filter { it.type == String::class.java }
                .onEach { it.isAccessible = true }
        }

        for (field in fields) {
            val value = runCatching { field.get(item) as? String }.getOrNull() ?: continue
            val lower = value.lowercase()
            if (value in BLOCKED_EXACT_IDS) return true
            if (BLOCKED_SUBSTRINGS.any { lower.contains(it) }) return true
        }

        val stringified = runCatching { item.toString() }.getOrNull() ?: ""
        if (stringified.contains("isPremiumUpsell=true")) return true
        return false
    }
}
