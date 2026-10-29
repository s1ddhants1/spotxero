package app.spotxero.hooks.ads

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object AdSectionFilter {

    private val fieldCache = ConcurrentHashMap<Class<*>, Field>()

    fun filterHome(sections: List<*>?): List<*>? {
        if (sections.isNullOrEmpty()) return sections
        return filter(sections, "com.spotify.casita.v1.resolved.Section", "featureTypeCase_", setOf(20, 21))
    }

    fun filterBrowse(sections: List<*>?): List<*>? {
        if (sections.isNullOrEmpty()) return sections
        return filter(sections, "com.spotify.browsita.v1.resolved.Section", "sectionTypeCase_", setOf(6))
    }

    private fun filter(sections: List<*>, expectedClassName: String, fieldName: String, adKinds: Set<Int>): List<*> {
        val first = sections.firstOrNull() ?: return sections
        if (first.javaClass.name != expectedClassName) return sections

        val field = fieldCache.computeIfAbsent(first.javaClass) { clazz ->
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }

        val hasAds = sections.any { item ->
            item != null && runCatching { field.getInt(item) in adKinds }.getOrDefault(false)
        }
        if (!hasAds) return sections

        return sections.filter { item ->
            item != null && runCatching { field.getInt(item) !in adKinds }.getOrDefault(true)
        }
    }
}
