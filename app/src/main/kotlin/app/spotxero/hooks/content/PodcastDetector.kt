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
        if (expected < 0) return false
        val actual = getFieldValue(section, SECTION_TYPE_FIELD) as? Int ?: return false
        return actual == expected
    }

    fun filterPodcastSections(sections: List<*>?): List<*>? {
        if (sections.isNullOrEmpty()) return sections
        val sectionClass = sections.first()?.javaClass ?: return sections
        if (!hasField(sectionClass, SECTION_TYPE_FIELD)) {
            Log.w(TAG, "Section type field unresolved for ${sectionClass.name}")
            return sections
        }
        return ProtoLists.filter(sections) { section -> !isPodcastSection(section) }
    }

    fun filterPodcastItems(items: List<*>?): List<*>? {
        if (items.isNullOrEmpty()) return items
        val itemClass = items.first()?.javaClass ?: return items
        if (!hasField(itemClass, ITEM_IDENTIFIER_FIELD)) {
            Log.w(TAG, "Item identifier unresolved for ${itemClass.name}")
            return items
        }
        return ProtoLists.filter(items) { item ->
            !isPodcastUri(getFieldValue(item, ITEM_IDENTIFIER_FIELD) as? String)
        }
    }

    private fun podcastSectionType(clazz: Class<*>): Int = sectionTypes.computeIfAbsent(clazz) { type ->
        val value = runCatching { type.getField(PODCAST_SECTION_CONSTANT).getInt(null) }.getOrNull()
        if (value == null) {
            Log.w(TAG, "Podcast section type unresolved for ${type.name}")
            -1
        } else {
            value
        }
    }

    private fun hasField(clazz: Class<*>, fieldName: String): Boolean =
        clazz.declaredFields.any { it.name == fieldName }

    private fun getFieldValue(target: Any, fieldName: String): Any? = runCatching {
        val field = fieldCache.computeIfAbsent(target.javaClass to fieldName) { (clazz, name) ->
            clazz.getDeclaredField(name).apply { isAccessible = true }
        }
        field.get(target)
    }.getOrNull()
}
