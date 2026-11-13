package app.spotxero.hooks.ads

import app.spotxero.core.ProtoLists
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object AdSectionFilter {

    private const val HOME_SECTION = "com.spotify.casita.v1.resolved.Section"
    private const val BROWSE_SECTION = "com.spotify.browsita.v1.resolved.Section"
    private const val PLAYER_SECTION = "com.spotify.scrollsita.v1.Section"
    private const val HOME_TYPE_FIELD = "featureTypeCase_"
    private const val BROWSE_TYPE_FIELD = "sectionTypeCase_"
    private const val PLAYER_TYPE_FIELD = "sectionTypeCase_"

    private val HOME_AD_FIELDS = listOf(
        "IMAGE_BRAND_AD_FIELD_NUMBER",
        "VIDEO_BRAND_AD_FIELD_NUMBER",
        "SHOWCASE_FIELD_NUMBER",
        "PROMOTION_V1_FIELD_NUMBER",
        "PROMOTION_V3_FIELD_NUMBER",
        "AUDIO_PROMO_FIELD_NUMBER",
        "VIDEO_PROMO_FIELD_NUMBER",
        "PLAY_LINK_PROMO_FIELD_NUMBER",
        "CAMPAIGN_LINK_CAROUSEL_FIELD_NUMBER",
        "PREVIEW_PROMOTION_CAROUSEL_FIELD_NUMBER",
        "CONCERT_CAMPAIGN_FIELD_NUMBER",
        "BANNER_FIELD_NUMBER",
        "BANNER_WITH_ANIMATIONS_FIELD_NUMBER",
        "FULL_BLEED_BANNER_FIELD_NUMBER",
        "INLINE_CARD_FIELD_NUMBER",
        "AUDIO_SPOTLIGHT_FIELD_NUMBER"
    )

    private val BROWSE_AD_FIELDS = listOf(
        "BRAND_ADS_FIELD_NUMBER",
        "SHOWCASE_FIELD_NUMBER",
        "PROMOTION_V1_FIELD_NUMBER",
        "PROMOTION_V3_FIELD_NUMBER",
        "AUDIO_PROMO_FIELD_NUMBER",
        "VIDEO_PROMO_FIELD_NUMBER",
        "PLAY_LINK_PROMO_FIELD_NUMBER",
        "PREVIEW_PROMOTION_CAROUSEL_FIELD_NUMBER",
        "AUDIO_SPOTLIGHT_FIELD_NUMBER"
    )

    private val PLAYER_AD_FIELDS = listOf(
        "IMAGE_BRAND_AD_FIELD_NUMBER",
        "LIVE_UPSELL_FIELD_NUMBER",
        "LEAVEBEHINDS_FIELD_NUMBER",
        "CONCERT_CAMPAIGN_FIELD_NUMBER"
    )

    private val AD_NAME_MARKERS = listOf("AdMetadata", "CreativeAd", "AdTrackingUrls", "adMetadata", "ad_metadata")
    private val AD_TEXT_MARKERS = listOf("Реклама", "Advertisement", "Sponsored", "Anzeige", "Publicité", "Publicidad", "spotify:ad:")
    private val DEFAULT_SENTINEL = Any()

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
            val childName = child.javaClass.name
            if (childName.contains("Showcase") ||
                childName.contains("BrandAd") ||
                childName.contains("Sponsored") ||
                hasAdMarker(child)
            ) return true
        }
        return false
    }

    private fun hasAdMarker(node: Any): Boolean {
        val clsName = node.javaClass.name
        if (clsName.endsWith(".SponsoredHeading") ||
            clsName.contains("Showcase") ||
            clsName.contains("BrandAd") ||
            clsName.contains("Sponsored") ||
            clsName.contains("AdMetadata")
        ) return true
        for (f in node.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val t = f.type
            val typeName = t.name
            val fieldName = f.name
            if (AD_NAME_MARKERS.any { typeName.contains(it) || fieldName.contains(it) }) {
                val meta = runCatching {
                    f.isAccessible = true
                    f.get(node)
                }.getOrNull() ?: continue
                if (!isDefaultInstance(meta)) return true
            } else if (typeName.endsWith(".Heading") || typeName.endsWith("Heading") || t == Any::class.java) {
                val sub = runCatching {
                    f.isAccessible = true
                    f.get(node)
                }.getOrNull() ?: continue
                val subClsName = sub.javaClass.name
                if (subClsName.contains("Sponsored")) return true
                if (hasAdMarker(sub)) return true
            } else if (t == String::class.java) {
                val str = runCatching {
                    f.isAccessible = true
                    f.get(node) as? String
                }.getOrNull()
                if (str != null && AD_TEXT_MARKERS.any { str.contains(it, ignoreCase = true) }) return true
            }
        }
        return false
    }

    private fun isDefaultInstance(msg: Any): Boolean {
        val def = defaultInstances.computeIfAbsent(msg.javaClass) { cls ->
            runCatching {
                cls.getDeclaredField("DEFAULT_INSTANCE").apply { isAccessible = true }.get(null)
            }.getOrNull() ?: DEFAULT_SENTINEL
        }
        return msg === def || (def !== DEFAULT_SENTINEL && msg == def)
    }

    private fun resolveAdTypes(sectionClass: Class<*>, adFields: List<String>): Set<Int> =
        adFields.mapNotNull { name ->
            runCatching { sectionClass.getField(name).getInt(null) }.getOrNull()
        }.toSet()
}
