package app.spotxero.core

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

object DexKitResolver {

    private const val TAG = "SpotXero.Resolver"

    const val KEY_SHARE_URL_BUILDER = "share_url_builder"
    const val KEY_SHARE_RESPONSE_URLS = "share_response_urls"
    const val KEY_GET_ADS_WRAPPER = "get_ads_wrapper"
    const val KEY_EMPTY_ADS_RESPONSE = "empty_ads_response"
    const val KEY_PREMIUM_TAB_FLAG = "premium_tab_flag"
    const val KEY_IS_ADVERTISEMENT_CHECK = "is_advertisement_check"
    const val KEY_HOME_SECTION_LIST = "home_section_list_getter"
    const val KEY_BROWSE_SECTION_LIST = "browse_section_list_getter"
    const val KEY_IMAGE_AD_PREDICATE = "scrollsita_image_ad_predicate"
    const val KEY_PROVIDED_ITEMS_LIST = "provided_items_list_getter"
    const val KEY_SMART_SHUFFLE_FLAGS = "smart_shuffle_flags"
    const val KEY_PRODUCT_STATE_GETTERS = "product_state_getters"
    const val KEY_ON_DEMAND_IN_FREE_GETTERS = "on_demand_in_free_getters"

    private const val GENERATE_URL_RESPONSE = "com.spotify.share.linkgeneration.api.proto.GenerateUrlResponse"
    private const val GET_ADS_REQUEST = "com.spotify.ads.esperanto.proto.GetAdsRequest"
    private const val GET_ADS_RESPONSE = "com.spotify.ads.esperanto.proto.GetAdsResponse"
    private val GET_ADS_CALL = listOf("spotify.ads.esperanto.proto.Ads", "GetAds")
    private const val HOME_STRUCTURE = "com.spotify.casita.v1.resolved.HomeStructure"
    private const val BROWSE_STRUCTURE = "com.spotify.browsita.v1.resolved.BrowseStructure"
    private const val PROVIDED = "com.spotify.casita.v1.resolved.Provided"
    private const val PLAYER_SECTION = "com.spotify.scrollsita.v1.Section"
    private const val CONTEXT_TRACK = "com.spotify.player.model.ContextTrack"

    private const val IMAGE_BRAND_AD_FIELD = "IMAGE_BRAND_AD_FIELD_NUMBER"
    private const val SHARE_RESPONSE_URL_COUNT = 4
    private val PREMIUM_KEYS = listOf(
        "premium_tab_enabled",
        "premium_tab_shows_on_tablets",
        "premium_tab_title"
    )

    @Volatile
    private var resolved: Map<String, Any> = emptyMap()

    @Synchronized
    fun resolve(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cachedSignatures: Map<String, String>?
    ): Map<String, String> {
        val signatures = mutableMapOf<String, String>()
        val members = mutableMapOf<String, Any>()

        resolveShareUrlBuilder(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveShareResponseUrls(classLoader, cachedSignatures, signatures, members)
        resolveGetAds(dexKit, classLoader, cachedSignatures, signatures, members)
        resolvePremiumTabFlag(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveIsAdvertisementCheck(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveSectionList(KEY_HOME_SECTION_LIST, HOME_STRUCTURE, classLoader, cachedSignatures, signatures, members)
        resolveSectionList(KEY_BROWSE_SECTION_LIST, BROWSE_STRUCTURE, classLoader, cachedSignatures, signatures, members)
        resolveSectionList(KEY_PROVIDED_ITEMS_LIST, PROVIDED, classLoader, cachedSignatures, signatures, members)
        resolveImageAdPredicate(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveSmartShuffleFlags(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveProductStateGetters(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveOnDemandInFreeGetters(dexKit, classLoader, cachedSignatures, signatures, members)

        resolved = members
        return signatures
    }

    fun <T> get(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return resolved[key] as? T
    }

    private fun resolveShareUrlBuilder(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_SHARE_URL_BUILDER, classLoader, ::isShareUrlBuilder)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("si", "utm_source", "Invalid uri "), StringMatchType.Equals)
                        paramCount(4)
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isShareUrlBuilder)
        store(KEY_SHARE_URL_BUILDER, method, sigs, members)
    }

    private fun resolveShareResponseUrls(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val methods = cached?.get(KEY_SHARE_RESPONSE_URLS)
            ?.split('|')
            ?.mapNotNull { cachedMethod(classLoader, it, ::isShareResponseUrl) }
            ?.takeIf { it.size == SHARE_RESPONSE_URL_COUNT }
            ?: runCatching {
                Class.forName(GENERATE_URL_RESPONSE, false, classLoader)
                    .declaredMethods
                    .filter { isShareResponseUrl(it) }
            }.getOrNull()?.takeIf { it.size == SHARE_RESPONSE_URL_COUNT }
        if (methods.isNullOrEmpty()) {
            Log.w(TAG, "$KEY_SHARE_RESPONSE_URLS: no validated match")
            return
        }
        members[KEY_SHARE_RESPONSE_URLS] = methods
        sigs[KEY_SHARE_RESPONSE_URLS] = methods.joinToString("|") { signatureOf(it) }
    }

    private fun resolveGetAds(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val wrapper = cachedMethod(cached, KEY_GET_ADS_WRAPPER, classLoader, ::isGetAdsWrapper)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(GET_ADS_CALL, StringMatchType.Equals)
                        paramCount(2)
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isGetAdsWrapper)
        store(KEY_GET_ADS_WRAPPER, wrapper, sigs, members)

        val emptyResponse = cachedMethod(cached, KEY_EMPTY_ADS_RESPONSE, classLoader, ::isEmptyAdsResponse)
            ?: runCatching {
                Class.forName(GET_ADS_RESPONSE, false, classLoader)
                    .declaredMethods
                    .single { isEmptyAdsResponse(it) }
            }.getOrNull()?.takeIf(::isEmptyAdsResponse)
        store(KEY_EMPTY_ADS_RESPONSE, emptyResponse, sigs, members)
    }

    private fun resolvePremiumTabFlag(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_PREMIUM_TAB_FLAG, classLoader, ::isPremiumTabFlag)
            ?: runCatching { findPremiumTabFlag(dexKit, classLoader) }.getOrNull()?.takeIf(::isPremiumTabFlag)
        store(KEY_PREMIUM_TAB_FLAG, method, sigs, members)
    }

    private fun resolveIsAdvertisementCheck(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_IS_ADVERTISEMENT_CHECK, classLoader, ::isAdvertisementCheck)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("is_advertisement"), StringMatchType.Equals)
                        paramTypes(CONTEXT_TRACK)
                        returnType("boolean")
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isAdvertisementCheck)
        store(KEY_IS_ADVERTISEMENT_CHECK, method, sigs, members)
    }

    private fun resolveSectionList(
        key: String,
        className: String,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, key, classLoader) { isListGetter(it, className) }
            ?: runCatching {
                Class.forName(className, false, classLoader)
                    .declaredMethods
                    .filter { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
                    .singleOrNull()
            }.getOrNull()?.takeIf { isListGetter(it, className) }
        store(key, method, sigs, members)
    }

    private fun resolveImageAdPredicate(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_IMAGE_AD_PREDICATE, classLoader, ::isImageAdPredicate)
            ?: runCatching {
                val sectionClass = Class.forName(PLAYER_SECTION, false, classLoader)
                val fieldNumber = sectionClass.getField(IMAGE_BRAND_AD_FIELD).getInt(null)
                dexKit.findMethod {
                    matcher {
                        declaredClass(PLAYER_SECTION)
                        paramCount(0)
                        returnType("boolean")
                        usingNumbers(fieldNumber)
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isImageAdPredicate)
        store(KEY_IMAGE_AD_PREDICATE, method, sigs, members)
    }

    private fun resolveSmartShuffleFlags(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val methods = cached?.get(KEY_SMART_SHUFFLE_FLAGS)
            ?.split('|')
            ?.mapNotNull { cachedMethod(classLoader, it, ::isSmartShuffleFlag) }
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                val matchClass = dexKit.findClass {
                    matcher {
                        usingStrings(listOf("smart_shuffle_as_default_for_liked_songs"), StringMatchType.Contains)
                    }
                }.firstOrNull()
                val clazz = matchClass?.getInstance(classLoader)
                clazz?.declaredMethods?.filter { isSmartShuffleFlag(it) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }

        if (methods.isNullOrEmpty()) {
            Log.w(TAG, "$KEY_SMART_SHUFFLE_FLAGS: no validated match")
            return
        }
        members[KEY_SMART_SHUFFLE_FLAGS] = methods
        sigs[KEY_SMART_SHUFFLE_FLAGS] = methods.joinToString("|") { signatureOf(it) }
    }

    private fun isSmartShuffleFlag(method: Method): Boolean =
        method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveProductStateGetters(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val methods = cached?.get(KEY_PRODUCT_STATE_GETTERS)
            ?.split('|')
            ?.mapNotNull { cachedMethod(classLoader, it, ::isProductStateGetter) }
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                val matchClass = dexKit.findClass {
                    matcher {
                        usingStrings(listOf("catalogue"), StringMatchType.Contains)
                    }
                }.firstOrNull()
                val clazz = matchClass?.getInstance(classLoader)
                clazz?.declaredMethods?.filter { isProductStateGetter(it) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }

        if (methods.isNullOrEmpty()) {
            Log.w(TAG, "$KEY_PRODUCT_STATE_GETTERS: no validated match")
            return
        }
        members[KEY_PRODUCT_STATE_GETTERS] = methods
        sigs[KEY_PRODUCT_STATE_GETTERS] = methods.joinToString("|") { signatureOf(it) }
    }

    private fun isProductStateGetter(method: Method): Boolean =
        method.parameterCount == 1 &&
            method.parameterTypes[0] == String::class.java &&
            method.returnType == String::class.java &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveOnDemandInFreeGetters(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val methods = cached?.get(KEY_ON_DEMAND_IN_FREE_GETTERS)
            ?.split('|')
            ?.mapNotNull { cachedMethod(classLoader, it, ::isOnDemandInFreeGetter) }
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                val declaredMethods = mutableListOf<Method>()
                runCatching {
                    Class.forName("com.spotify.playlist.proto.PlaylistRequest\$Response", false, classLoader)
                        .declaredMethods
                        .filter { isOnDemandInFreeGetter(it) }
                        .let { declaredMethods.addAll(it) }
                }
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("isOnDemandInFree"), StringMatchType.Contains)
                        paramCount(0)
                    }
                }.mapNotNull { it.getMethodInstance(classLoader) }
                    .filter { isOnDemandInFreeGetter(it) }
                    .let { declaredMethods.addAll(it) }

                declaredMethods.distinctBy { signatureOf(it) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }

        if (methods.isNullOrEmpty()) {
            Log.w(TAG, "$KEY_ON_DEMAND_IN_FREE_GETTERS: no validated match")
            return
        }
        members[KEY_ON_DEMAND_IN_FREE_GETTERS] = methods
        sigs[KEY_ON_DEMAND_IN_FREE_GETTERS] = methods.joinToString("|") { signatureOf(it) }
    }

    private fun isOnDemandInFreeGetter(method: Method): Boolean {
        if (method.parameterCount != 0) return false
        val name = method.returnType.name
        return (name == "boolean" || name == "java.lang.Boolean") &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)
    }

    private fun findPremiumTabFlag(dexKit: DexKitBridge, classLoader: ClassLoader): Method? {
        val record = dexKit.findMethod {
            matcher {
                usingStrings(PREMIUM_KEYS, StringMatchType.Equals)
                paramCount(0)
                returnType("java.util.List")
            }
        }.singleOrNull() ?: return null
        val recordClass = record.declaredClass?.getInstance(classLoader) ?: return null
        if (recordClass.declaredMethods.count { isBooleanGetter(it) } != 3) return null
        val invoked = record.invokes.mapNotNull { data ->
            runCatching { data.getMethodInstance(classLoader) }.getOrNull()
        }.filter { it.declaringClass == recordClass && isBooleanGetter(it) }
            .distinctBy { it.name }
        return invoked.takeIf { it.size == 3 }?.get(1)
    }

    private fun isShareResponseUrl(method: Method): Boolean =
        method.declaringClass.name == GENERATE_URL_RESPONSE &&
            method.parameterCount == 0 &&
            method.returnType == String::class.java

    private fun isGetAdsWrapper(method: Method): Boolean =
        method.parameterCount == 2 &&
            method.parameterTypes[0].name == GET_ADS_REQUEST &&
            method.returnType == Any::class.java

    private fun isEmptyAdsResponse(method: Method): Boolean =
        method.declaringClass.name == GET_ADS_RESPONSE &&
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == ByteArray::class.java &&
            method.returnType.name == GET_ADS_RESPONSE

    private fun isShareUrlBuilder(method: Method): Boolean {
        if (method.parameterCount != 4) return false
        if (method.parameterTypes[0] != String::class.java) return false
        if (method.parameterTypes[1] != String::class.java) return false
        if (!Map::class.java.isAssignableFrom(method.parameterTypes[3])) return false
        val returnType = method.returnType
        if (returnType == String::class.java) return true
        val pairFields = returnType.declaredFields.count { it.type == Any::class.java }
        val pairConstructor = returnType.declaredConstructors.any { constructor ->
            constructor.parameterCount == 2 && constructor.parameterTypes.all { it == Any::class.java }
        }
        return pairFields == 2 && pairConstructor
    }

    private fun isPremiumTabFlag(method: Method): Boolean {
        if (!isBooleanGetter(method)) return false
        val recordClass = method.declaringClass
        if (recordClass.declaredMethods.count { isBooleanGetter(it) } != 3) return false
        if (recordClass.declaredMethods.none { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }) {
            return false
        }
        return recordClass.declaredConstructors.any { constructor ->
            constructor.parameterCount >= 4 &&
                constructor.parameterTypes.take(3).all { it == Boolean::class.javaPrimitiveType }
        }
    }

    private fun isAdvertisementCheck(method: Method): Boolean =
        method.parameterCount == 1 &&
            method.parameterTypes[0].name == CONTEXT_TRACK &&
            method.returnType == Boolean::class.javaPrimitiveType

    private fun isImageAdPredicate(method: Method): Boolean =
        method.declaringClass.name == PLAYER_SECTION && isBooleanGetter(method)

    private fun isBooleanGetter(method: Method): Boolean =
        method.parameterCount == 0 && method.returnType == Boolean::class.javaPrimitiveType

    private fun isListGetter(method: Method, className: String): Boolean =
        method.declaringClass.name == className &&
            method.parameterCount == 0 &&
            List::class.java.isAssignableFrom(method.returnType)

    private fun store(
        key: String,
        method: Method?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        if (method == null) {
            Log.w(TAG, "$key: no validated match")
            return
        }
        members[key] = method
        sigs[key] = signatureOf(method)
    }

    private fun cachedMethod(
        cached: Map<String, String>?,
        key: String,
        classLoader: ClassLoader,
        validate: (Method) -> Boolean
    ): Method? {
        val entry = cached?.get(key) ?: return null
        val parts = entry.split("#")
        if (parts.size != 3) return null
        return runCatching {
            Class.forName(parts[0], false, classLoader)
                .declaredMethods
                .firstOrNull { it.name == parts[1] && descriptorOf(it) == parts[2] }
        }.getOrNull()?.takeIf(validate)
    }

    private fun cachedMethod(classLoader: ClassLoader, entry: String, validate: (Method) -> Boolean): Method? {
        val parts = entry.split("#")
        if (parts.size != 3) return null
        return runCatching {
            Class.forName(parts[0], false, classLoader)
                .declaredMethods
                .firstOrNull { it.name == parts[1] && descriptorOf(it) == parts[2] }
        }.getOrNull()?.takeIf(validate)
    }

    private fun signatureOf(method: Method): String =
        "${method.declaringClass.name}#${method.name}#${descriptorOf(method)}"

    private fun descriptorOf(method: Method): String = buildString {
        append('(')
        method.parameterTypes.forEach { append(descriptorOf(it)) }
        append(')')
        append(descriptorOf(method.returnType))
    }

    private fun descriptorOf(type: Class<*>): String = when (type) {
        Boolean::class.javaPrimitiveType -> "Z"
        Byte::class.javaPrimitiveType -> "B"
        Char::class.javaPrimitiveType -> "C"
        Short::class.javaPrimitiveType -> "S"
        Int::class.javaPrimitiveType -> "I"
        Long::class.javaPrimitiveType -> "J"
        Float::class.javaPrimitiveType -> "F"
        Double::class.javaPrimitiveType -> "D"
        Void.TYPE -> "V"
        else -> if (type.isArray) {
            "[" + descriptorOf(type.componentType ?: Any::class.java)
        } else {
            "L" + type.name.replace('.', '/') + ";"
        }
    }
}
