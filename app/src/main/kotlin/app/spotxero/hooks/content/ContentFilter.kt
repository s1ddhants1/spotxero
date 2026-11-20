package app.spotxero.hooks.content

import android.util.Log
import app.spotxero.core.ProtoLists
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

class ContentFilter private constructor(
    private val tag: String,
    private val keyword: String,
    private val uriPrefixes: Array<String>,
    private val browsePrefixes: Array<String>,
    private val chipIds: Set<String>,
    private val parcelMarker: String,
    private val sectionConstant: String?
) {

    private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
    private val sectionTypes = ConcurrentHashMap<Class<*>, Int>()

    fun filterItems(items: List<*>?): List<*>? {
        if (items.isNullOrEmpty()) return items
        val itemClass = items.first()?.javaClass ?: return items
        if (!hasField(itemClass, ITEM_IDENTIFIER_FIELD)) {
            Log.w(tag, "Item identifier unresolved for ${itemClass.name}")
            return items
        }
        return ProtoLists.filter(items) { item ->
            !isUri(getFieldValue(item, ITEM_IDENTIFIER_FIELD) as? String)
        }
    }

    fun filterHomeChips(chips: MutableList<*>) {
        runCatching {
            val iterator = chips.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next() ?: continue
                if (isHomeChip(item)) {
                    runCatching { iterator.remove() }
                } else {
                    for (f in item.javaClass.declaredFields) {
                        if (List::class.java.isAssignableFrom(f.type)) {
                            f.isAccessible = true
                            val sub = f.get(item) as? MutableList<*>
                            if (!sub.isNullOrEmpty()) {
                                filterHomeChips(sub)
                            }
                        }
                    }
                }
            }
        }
    }

    fun filterSearchChips(chips: MutableList<*>) {
        runCatching {
            val iterator = chips.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next() ?: continue
                if (isSearchChip(item)) {
                    runCatching { iterator.remove() }
                }
            }
        }
    }

    fun filterSearchEntities(entities: MutableList<*>) {
        runCatching {
            val iterator = entities.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next() ?: continue
                var uri: String? = null
                for (f in item.javaClass.declaredFields) {
                    if (f.type == String::class.java) {
                        f.isAccessible = true
                        uri = f.get(item) as? String
                        break
                    }
                }
                if (isUri(uri)) {
                    runCatching { iterator.remove() }
                }
            }
        }
    }

    fun filterBrowseItems(items: List<*>?): List<*>? {
        if (items.isNullOrEmpty()) return items
        return ProtoLists.filter(items) { item -> !isBrowseItem(item) }
    }

    fun filterBrowseSections(sections: List<*>?): List<*>? {
        if (sections.isNullOrEmpty()) return sections
        val filtered = ProtoLists.filter(sections) { section ->
            val sectionInfo = getFieldValue(section, "sectionInfo_")
            val sectionId = if (sectionInfo != null) (getFieldValue(sectionInfo, "sectionId_") as? String) ?: "" else ""
            !sectionId.contains(keyword, ignoreCase = true)
        }
        return if (filtered.isNullOrEmpty()) sections else filtered
    }

    fun filterSectionTypes(sections: List<*>?): List<*>? {
        if (sectionConstant == null) return sections
        if (sections.isNullOrEmpty()) return sections
        val sectionClass = sections.first()?.javaClass ?: return sections
        if (!hasField(sectionClass, SECTION_TYPE_FIELD)) {
            Log.w(tag, "Section type field unresolved for ${sectionClass.name}")
            return sections
        }
        val filtered = ProtoLists.filter(sections) { section -> !isSectionType(section) }
        return if (filtered.isNullOrEmpty()) sections else filtered
    }

    private fun isUri(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return false
        return uriPrefixes.any { uri.startsWith(it) }
    }

    private fun isHomeChip(chip: Any?): Boolean {
        if (chip == null) return false
        return runCatching {
            for (f in chip.javaClass.declaredFields) {
                f.isAccessible = true
                val v = f.get(chip) ?: continue
                if (v is String && chipIds.contains(v)) {
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun isSearchChip(chip: Any?): Boolean {
        if (chip == null) return false
        return runCatching {
            for (f in chip.javaClass.declaredFields) {
                f.isAccessible = true
                val v = f.get(chip) ?: continue
                if (v is String && v.contains(keyword, ignoreCase = true)) {
                    return@runCatching true
                }
                if (v is android.os.Parcelable) {
                    val parcel = android.os.Parcel.obtain()
                    try {
                        v.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        val str = parcel.readString()
                        if (str == parcelMarker) {
                            return@runCatching true
                        }
                    } catch (_: Throwable) {
                    } finally {
                        parcel.recycle()
                    }
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun isBrowseItem(item: Any?): Boolean {
        if (item == null) return false
        return runCatching {
            fun matches(s: String?): Boolean {
                if (s.isNullOrEmpty()) return false
                return s.contains(keyword, ignoreCase = true) || browsePrefixes.any { s.startsWith(it) }
            }
            for (m in item.javaClass.methods) {
                if (m.parameterCount == 0 && m.returnType == String::class.java) {
                    val str = runCatching { m.invoke(item) as? String }.getOrNull()
                    if (matches(str)) return@runCatching true
                }
            }
            for (f in item.javaClass.declaredFields) {
                f.isAccessible = true
                val v = f.get(item) ?: continue
                if (v is String && matches(v)) return@runCatching true
                if (v.javaClass.name.contains("ByteString")) {
                    val s = runCatching { v.toString() }.getOrNull()
                    if (matches(s)) return@runCatching true
                }
                if (!v.javaClass.isPrimitive && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("android.")) {
                    for (innerM in v.javaClass.methods) {
                        if (innerM.parameterCount == 0 && innerM.returnType == String::class.java) {
                            val str = runCatching { innerM.invoke(v) as? String }.getOrNull()
                            if (matches(str)) return@runCatching true
                        }
                    }
                    for (innerF in v.javaClass.declaredFields) {
                        innerF.isAccessible = true
                        val innerV = innerF.get(v) ?: continue
                        if (innerV is String && matches(innerV)) return@runCatching true
                    }
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun isSectionType(section: Any?): Boolean {
        if (section == null) return false
        val expected = sectionType(section.javaClass)
        if (expected < 0) return false
        val actual = getFieldValue(section, SECTION_TYPE_FIELD) as? Int ?: return false
        return actual == expected
    }

    private fun sectionType(clazz: Class<*>): Int = sectionTypes.computeIfAbsent(clazz) { type ->
        val value = runCatching { type.getField(sectionConstant!!).getInt(null) }.getOrNull()
        if (value == null) {
            Log.w(tag, "Section type unresolved for ${type.name}")
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

    companion object {
        private const val SECTION_TYPE_FIELD = "featureTypeCase_"
        private const val ITEM_IDENTIFIER_FIELD = "identifier_"

        val PODCASTS = ContentFilter(
            tag = "SpotXero.Podcasts",
            keyword = "podcast",
            uriPrefixes = arrayOf(
                "spotify:show:",
                "spotify:episode:",
                "spotify:podcast-chapter:",
                "spotify:clip:"
            ),
            browsePrefixes = arrayOf(
                "spotify:show:",
                "spotify:episode:",
                "spotify:podcast-chapter:"
            ),
            chipIds = setOf(
                "podcasts-chip",
                "podcasts-following-chip"
            ),
            parcelMarker = "PODCAST_AND_EPISODES",
            sectionConstant = "PODCAST_HERO_CAROUSEL_FIELD_NUMBER"
        )

        val AUDIOBOOKS = ContentFilter(
            tag = "SpotXero.Audiobooks",
            keyword = "audiobook",
            uriPrefixes = arrayOf(
                "spotify:audiobook:",
                "spotify:audiobook-chapter:",
                "spotify:author:"
            ),
            browsePrefixes = arrayOf(
                "spotify:audiobook:",
                "spotify:audiobook-chapter:",
                "spotify:author:"
            ),
            chipIds = setOf("audiobooks-chip"),
            parcelMarker = "AUDIOBOOK",
            sectionConstant = null
        )
    }
}
