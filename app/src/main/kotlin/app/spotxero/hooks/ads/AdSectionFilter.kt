package app.spotxero.hooks.ads

import android.util.Log
import app.spotxero.core.ProtoLists
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object AdSectionFilter {

    private const val TAG = "SpotXero.BrandAds"
    private const val HOME_SECTION = "com.spotify.casita.v1.resolved.Section"
    private const val BROWSE_SECTION = "com.spotify.browsita.v1.resolved.Section"
    private const val HOME_TYPE_FIELD = "featureTypeCase_"
    private const val BROWSE_TYPE_FIELD = "sectionTypeCase_"

    private val HOME_AD_FIELDS = listOf("IMAGE_BRAND_AD_FIELD_NUMBER", "VIDEO_BRAND_AD_FIELD_NUMBER")
    private val BROWSE_AD_FIELDS = listOf("BRAND_ADS_FIELD_NUMBER")

    private val typeFields = ConcurrentHashMap<Class<*>, Field>()
    private val adTypes = ConcurrentHashMap<Class<*>, Set<Int>>()

    fun filterHome(sections: List<*>?): List<*>? =
        filter(sections, HOME_SECTION, HOME_TYPE_FIELD, HOME_AD_FIELDS)

    fun filterBrowse(sections: List<*>?): List<*>? =
        filter(sections, BROWSE_SECTION, BROWSE_TYPE_FIELD, BROWSE_AD_FIELDS)

    private fun filter(
        sections: List<*>?,
        className: String,
        typeField: String,
        adFields: List<String>
    ): List<*>? {
        if (sections.isNullOrEmpty()) return sections
        val sectionClass = sections.first()?.javaClass ?: return sections
        if (sectionClass.name != className) return sections

        val kinds = adTypes.computeIfAbsent(sectionClass) { resolveAdTypes(it, adFields) }
        if (kinds.isEmpty()) return sections

        val field = typeFields[sectionClass] ?: runCatching {
            sectionClass.getDeclaredField(typeField).apply { isAccessible = true }
        }.getOrNull()?.also { typeFields[sectionClass] = it } ?: return sections

        return ProtoLists.filter(sections) { section ->
            runCatching { field.getInt(section) }.getOrDefault(-1) !in kinds
        }
    }

    private fun resolveAdTypes(sectionClass: Class<*>, adFields: List<String>): Set<Int> {
        val values = adFields.mapNotNull { name ->
            runCatching { sectionClass.getField(name).getInt(null) }.getOrNull()
        }
        if (values.size != adFields.size) {
            Log.w(TAG, "Ad section types unresolved for ${sectionClass.name}")
            return emptySet()
        }
        return values.toSet()
    }
}
