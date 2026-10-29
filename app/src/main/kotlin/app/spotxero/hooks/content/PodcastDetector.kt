package app.spotxero.hooks.content

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object PodcastDetector {

    private val PODCAST_PREFIXES = arrayOf(
        "spotify:show:",
        "spotify:episode:",
        "spotify:podcast-chapter:",
        "spotify:clip:"
    )

    private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()

    fun isPodcastUri(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return false
        return PODCAST_PREFIXES.any { uri.startsWith(it) }
    }

    fun isPodcastSection(section: Any?): Boolean {
        if (section == null) return false
        return runCatching {
            val featureTypeCase = getFieldValue(section, "featureTypeCase_") as? Int ?: return false
            if (featureTypeCase == 34) return true
            val items = getFieldValue(section, "items_") as? List<*>
            if (!items.isNullOrEmpty()) {
                items.all { item ->
                    val uri = item?.let { getFieldValue(it, "identifier_") as? String }
                    isPodcastUri(uri)
                }
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun filterPodcastItems(items: List<*>?): List<*>? {
        if (items.isNullOrEmpty()) return items
        val hasPodcasts = items.any { item ->
            val uri = item?.let { getFieldValue(it, "identifier_") as? String }
            isPodcastUri(uri)
        }
        if (!hasPodcasts) return items
        return items.filter { item ->
            val uri = item?.let { getFieldValue(it, "identifier_") as? String }
            !isPodcastUri(uri)
        }
    }

    private fun getFieldValue(target: Any, fieldName: String): Any? {
        return runCatching {
            val key = target.javaClass to fieldName
            val field = fieldCache.computeIfAbsent(key) { (clazz, name) ->
                clazz.getDeclaredField(name).apply { isAccessible = true }
            }
            field.get(target)
        }.getOrNull()
    }
}
