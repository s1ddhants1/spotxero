package app.spotxero.hooks.ads

import android.util.Log
import app.spotxero.core.ProtoLists
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object AdSectionFilter {

    private const val TAG = "SpotXero.BrandAds"
    private const val HOME_SECTION = "com.spotify.casita.v1.resolved.Section"
    private const val BROWSE_SECTION = "com.spotify.browsita.v1.resolved.Section"
    private const val PLAYER_SECTION = "com.spotify.scrollsita.v1.Section"
    private const val HOME_TYPE_FIELD = "featureTypeCase_"
    private const val BROWSE_TYPE_FIELD = "sectionTypeCase_"
    private const val PLAYER_TYPE_FIELD = "sectionTypeCase_"

    private val HOME_AD_FIELDS = listOf("IMAGE_BRAND_AD_FIELD_NUMBER", "VIDEO_BRAND_AD_FIELD_NUMBER")
    private val BROWSE_AD_FIELDS = listOf("BRAND_ADS_FIELD_NUMBER")
    private val PLAYER_AD_FIELDS = listOf("IMAGE_BRAND_AD_FIELD_NUMBER", "LIVE_UPSELL_FIELD_NUMBER", "LEAVEBEHINDS_FIELD_NUMBER")

    private val typeFields = ConcurrentHashMap<Class<*>, Field>()
    private val adTypes = ConcurrentHashMap<Class<*>, Set<Int>>()
    private val defaultInstances = ConcurrentHashMap<Class<*>, Any>()

    fun filterHome(sections: List<*>?): List<*>? =
        filter(sections, HOME_SECTION, HOME_TYPE_FIELD, HOME_AD_FIELDS, inspectEmbeddedAds = true)

    fun filterBrowse(sections: List<*>?): List<*>? =
        filter(sections, BROWSE_SECTION, BROWSE_TYPE_FIELD, BROWSE_AD_FIELDS, inspectEmbeddedAds = true)

    fun filterPlayer(sections: List<*>?): List<*>? =
        filter(sections, PLAYER_SECTION, PLAYER_TYPE_FIELD, PLAYER_AD_FIELDS, inspectEmbeddedAds = false)

    private fun filter(
        sections: List<*>?,
        className: String,
        typeField: String,
        adFields: List<String>,
        inspectEmbeddedAds: Boolean
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
            val kind = runCatching { field.getInt(section) }.getOrDefault(-1)
            if (kind in kinds) return@filter false
            !inspectEmbeddedAds || !hasEmbeddedAdOrSponsorship(section)
        }
    }

    private fun hasEmbeddedAdOrSponsorship(section: Any): Boolean {
        for (f in section.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val t = f.type
            if (t != Any::class.java && !t.name.startsWith("com.spotify.")) continue
            val child = runCatching {
                f.isAccessible = true
                f.get(section)
            }.getOrNull() ?: continue
            if (hasAdMarker(child)) return true
        }
        return false
    }

    private fun hasAdMarker(node: Any): Boolean {
        val cls = node.javaClass
        if (cls.name.endsWith(".SponsoredHeading")) return true
        for (f in cls.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val t = f.type
            val name = t.name
            if (name.endsWith(".EmbeddedAdMetadata")) {
                val meta = runCatching {
                    f.isAccessible = true
                    f.get(node)
                }.getOrNull() ?: continue
                if (!isDefaultInstance(meta)) return true
            } else if (name.endsWith(".Heading") || t == Any::class.java) {
                val sub = runCatching {
                    f.isAccessible = true
                    f.get(node)
                }.getOrNull() ?: continue
                if (sub.javaClass.name.endsWith(".SponsoredHeading")) return true
                if (name.endsWith(".Heading") && hasAdMarker(sub)) return true
            }
        }
        return false
    }

    private fun isDefaultInstance(msg: Any): Boolean {
        val def = defaultInstances[msg.javaClass] ?: runCatching {
            msg.javaClass.getMethod("getDefaultInstanceForType").invoke(msg)
        }.getOrNull()?.also { defaultInstances[msg.javaClass] = it } ?: return true
        return msg == def
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
