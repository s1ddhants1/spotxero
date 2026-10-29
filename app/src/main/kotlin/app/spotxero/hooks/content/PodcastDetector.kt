package app.spotxero.hooks.content

import android.util.Log
import app.spotxero.core.ProtoLists
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object PodcastDetector {

    private const val TAG = "SpotXero.Podcasts"
    private const val SECTION_TYPE_FIELD = "featureTypeCase_"
    private const val ITEM_IDENTIFIER_FIELD = "identifier_"
    private const val PODCAST_SECTION_CONSTANT = "PODCAST_HERO_CAROUSEL_FIELD_NUMBER"

    private val PODCAST_PREFIXES = arrayOf(
        "spotify:show:",
        "spotify:episode:",
        "spotify:podcast-chapter:",
        "spotify:clip:"
    )

    private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
    private val sectionTypes = ConcurrentHashMap<Class<*>, Int>()

    fun isPodcastUri(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return false
        return PODCAST_PREFIXES.any { uri.startsWith(it) }
    }

    fun isPodcastSection(section: Any?): Boolean {
        if (section == null) return false
        val expected = podcastSectionType(section.javaClass)
        val actual = getFieldValue(section, SECTION_TYPE_FIELD) as? Int ?: return false
        return expected >= 0 && actual == expected
    }

    fun filterPodcastSections(sections: List<*>?): List<*>? =
        ProtoLists.filter(sections) { section -> !isPodcastSection(section) }

    fun filterPodcastItems(items: List<*>?): List<*>? =
        ProtoLists.filter(items) { item ->
            !isPodcastUri(getFieldValue(item, ITEM_IDENTIFIER_FIELD) as? String)
        }

    private fun podcastSectionType(clazz: Class<*>): Int = sectionTypes.computeIfAbsent(clazz) { type ->
        runCatching { type.getField(PODCAST_SECTION_CONSTANT).getInt(null) }
            .onFailure { Log.w(TAG, "Podcast section type unresolved for ${type.name}: ${it.message}") }
            .getOrDefault(-1)
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
