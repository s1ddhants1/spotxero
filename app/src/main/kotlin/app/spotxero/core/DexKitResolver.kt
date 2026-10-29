package app.spotxero.core

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object DexKitResolver {

    private const val TAG = "SpotXero.Resolver"

    const val KEY_SHARE_URL_BUILDER = "share_url_builder"
    const val KEY_SHARE_RESULT_CTOR = "share_result_ctor"
    const val KEY_PREMIUM_TAB_FLAG = "premium_tab_flag"
    const val KEY_PRODUCT_STATE_GET = "product_state_get_value"
    const val KEY_AD_MANAGER_IS_ENABLED = "ad_manager_is_enabled"
    const val KEY_IS_ADVERTISEMENT_CHECK = "is_advertisement_check"
    const val KEY_HOME_SECTION_LIST = "home_section_list_getter"
    const val KEY_BROWSE_SECTION_LIST = "browse_section_list_getter"
    const val KEY_IMAGE_AD_FLAG = "scrollsita_section_is_ad"
    const val KEY_EMBEDDED_AD_PREDICATE = "embedded_ad_predicate"
    const val KEY_PROVIDED_ITEMS_LIST = "provided_items_list_getter"

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
        resolveShareResultCtor(dexKit, classLoader, cachedSignatures, signatures, members)
        resolvePremiumTabFlag(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveProductStateGet(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveAdManagerIsEnabled(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveIsAdvertisementCheck(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveHomeSectionList(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveBrowseSectionList(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveImageAdFlag(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveEmbeddedAdPredicate(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveProvidedItemsList(dexKit, classLoader, cachedSignatures, signatures, members)

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
        try {
            val cachedSig = cached?.get(KEY_SHARE_URL_BUILDER)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName }
                if (method != null) {
                    members[KEY_SHARE_URL_BUILDER] = method
                    sigs[KEY_SHARE_URL_BUILDER] = cachedSig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    usingStrings("si", "utm_source", "Invalid uri ")
                }
            }
            val methodData = results.singleOrNull() ?: run {
                Log.w(TAG, "$KEY_SHARE_URL_BUILDER: found ${results.size} matches, expected 1")
                return
            }
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_SHARE_URL_BUILDER] = method
            sigs[KEY_SHARE_URL_BUILDER] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_SHARE_URL_BUILDER: ${t.message}")
        }
    }

    private fun resolveShareResultCtor(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_SHARE_RESULT_CTOR)
            if (cachedSig != null) {
                val clazz = Class.forName(cachedSig, false, classLoader)
                val ctor = clazz.declaredConstructors.firstOrNull {
                    it.parameterTypes.size == 4 &&
                        it.parameterTypes.all { p -> p == String::class.java }
                }
                if (ctor != null) {
                    members[KEY_SHARE_RESULT_CTOR] = ctor
                    sigs[KEY_SHARE_RESULT_CTOR] = cachedSig
                    return
                }
            }

            val responseClassName = "com.spotify.share.linkgeneration.api.proto.GenerateUrlResponse"
            runCatching { Class.forName(responseClassName, false, classLoader) }.getOrNull() ?: run {
                Log.w(TAG, "$KEY_SHARE_RESULT_CTOR: GenerateUrlResponse not found")
                return
            }

            val results = dexKit.findMethod {
                matcher {
                    name("<init>")
                    paramTypes(
                        "java.lang.String",
                        "java.lang.String",
                        "java.lang.String",
                        "java.lang.String"
                    )
                }
                searchInClass(
                    dexKit.findClass {
                        matcher {
                            usingStrings("shareableUrl_", "fullUrl_")
                        }
                    }
                )
            }
            val methodData = results.firstOrNull() ?: run {
                Log.w(TAG, "$KEY_SHARE_RESULT_CTOR: no matching constructor found")
                return
            }
            val ctor = methodData.getConstructorInstance(classLoader)
            members[KEY_SHARE_RESULT_CTOR] = ctor
            sigs[KEY_SHARE_RESULT_CTOR] = ctor.declaringClass.name
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_SHARE_RESULT_CTOR: ${t.message}")
        }
    }

    private fun resolvePremiumTabFlag(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_PREMIUM_TAB_FLAG)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull {
                    it.name == methodName &&
                        it.parameterTypes.isEmpty() &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }
                if (method != null) {
                    members[KEY_PREMIUM_TAB_FLAG] = method
                    sigs[KEY_PREMIUM_TAB_FLAG] = cachedSig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    usingStrings("premium_tab", "premium")
                    returnType("boolean")
                    paramCount(0)
                }
            }
            if (results.isEmpty()) {
                val altResults = dexKit.findMethod {
                    matcher {
                        usingStrings("showPremiumTab", "isPremiumTabEnabled")
                        returnType("boolean")
                    }
                }
                val methodData = altResults.firstOrNull() ?: run {
                    Log.w(TAG, "$KEY_PREMIUM_TAB_FLAG: no matches found")
                    return
                }
                val method = methodData.getMethodInstance(classLoader)
                val sig = "${method.declaringClass.name}#${method.name}"
                members[KEY_PREMIUM_TAB_FLAG] = method
                sigs[KEY_PREMIUM_TAB_FLAG] = sig
                return
            }
            val methodData = results.first()
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_PREMIUM_TAB_FLAG] = method
            sigs[KEY_PREMIUM_TAB_FLAG] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_PREMIUM_TAB_FLAG: ${t.message}")
        }
    }

    private fun resolveProductStateGet(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_PRODUCT_STATE_GET)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull {
                    it.name == methodName &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType == String::class.java
                }
                if (method != null) {
                    members[KEY_PRODUCT_STATE_GET] = method
                    sigs[KEY_PRODUCT_STATE_GET] = cachedSig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    usingStrings("ads", "catalogue", "product")
                    paramTypes("java.lang.String")
                    returnType("java.lang.String")
                }
            }
            val methodData = results.firstOrNull() ?: run {
                val altResults = dexKit.findMethod {
                    matcher {
                        usingStrings("product_state")
                        paramTypes("java.lang.String")
                        returnType("java.lang.String")
                    }
                }
                altResults.firstOrNull() ?: run {
                    Log.w(TAG, "$KEY_PRODUCT_STATE_GET: no matches found")
                    return
                }
            }
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_PRODUCT_STATE_GET] = method
            sigs[KEY_PRODUCT_STATE_GET] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_PRODUCT_STATE_GET: ${t.message}")
        }
    }

    private fun resolveAdManagerIsEnabled(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_AD_MANAGER_IS_ENABLED)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull {
                    it.name == methodName &&
                        it.parameterTypes.isEmpty() &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }
                if (method != null) {
                    members[KEY_AD_MANAGER_IS_ENABLED] = method
                    sigs[KEY_AD_MANAGER_IS_ENABLED] = cachedSig
                    return
                }
            }

            val adManagerClasses = dexKit.findClass {
                matcher {
                    usingStrings("spotify.ads.esperanto.slots.proto.Slots")
                }
            }
            if (adManagerClasses.isEmpty()) {
                Log.w(TAG, "$KEY_AD_MANAGER_IS_ENABLED: ad manager container not found")
                return
            }

            val enabledMethods = mutableListOf<Method>()
            for (classData in adManagerClasses) {
                val clazz = classData.getInstance(classLoader)
                for (m in clazz.declaredMethods) {
                    if (m.parameterTypes.isEmpty() &&
                        m.returnType == Boolean::class.javaPrimitiveType &&
                        (m.name.contains("isEnabled", ignoreCase = true) ||
                            m.name.contains("enabled", ignoreCase = true) ||
                            m.name.length <= 2)
                    ) {
                        enabledMethods.add(m)
                    }
                }
            }

            val method = enabledMethods.firstOrNull() ?: run {
                Log.w(TAG, "$KEY_AD_MANAGER_IS_ENABLED: no isEnabled-like method found")
                return
            }
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_AD_MANAGER_IS_ENABLED] = method
            sigs[KEY_AD_MANAGER_IS_ENABLED] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_AD_MANAGER_IS_ENABLED: ${t.message}")
        }
    }

    private fun resolveIsAdvertisementCheck(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_IS_ADVERTISEMENT_CHECK)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName }
                if (method != null) {
                    members[KEY_IS_ADVERTISEMENT_CHECK] = method
                    sigs[KEY_IS_ADVERTISEMENT_CHECK] = cachedSig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    usingStrings("is_advertisement", "true")
                    returnType("boolean")
                }
            }
            val methodData = results.firstOrNull() ?: run {
                val altResults = dexKit.findMethod {
                    matcher {
                        usingStrings("is_advertisement")
                    }
                }
                altResults.firstOrNull() ?: run {
                    Log.w(TAG, "$KEY_IS_ADVERTISEMENT_CHECK: no matches found")
                    return
                }
            }
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_IS_ADVERTISEMENT_CHECK] = method
            sigs[KEY_IS_ADVERTISEMENT_CHECK] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_IS_ADVERTISEMENT_CHECK: ${t.message}")
        }
    }

    private fun resolveHomeSectionList(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_HOME_SECTION_LIST)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName }
                if (method != null) {
                    members[KEY_HOME_SECTION_LIST] = method
                    sigs[KEY_HOME_SECTION_LIST] = cachedSig
                    return
                }
            }

            val homeStructureClass = runCatching {
                Class.forName("com.spotify.casita.v1.resolved.HomeStructure", false, classLoader)
            }.getOrNull()
            if (homeStructureClass != null) {
                val method = homeStructureClass.declaredMethods.firstOrNull {
                    it.parameterTypes.isEmpty() && List::class.java.isAssignableFrom(it.returnType)
                }
                if (method != null) {
                    val sig = "${method.declaringClass.name}#${method.name}"
                    members[KEY_HOME_SECTION_LIST] = method
                    sigs[KEY_HOME_SECTION_LIST] = sig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    declaredClass("com.spotify.casita.v1.resolved.HomeStructure")
                    paramCount(0)
                    returnType("java.util.List")
                }
            }
            val methodData = results.firstOrNull() ?: return
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_HOME_SECTION_LIST] = method
            sigs[KEY_HOME_SECTION_LIST] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_HOME_SECTION_LIST: ${t.message}")
        }
    }

    private fun resolveBrowseSectionList(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_BROWSE_SECTION_LIST)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName }
                if (method != null) {
                    members[KEY_BROWSE_SECTION_LIST] = method
                    sigs[KEY_BROWSE_SECTION_LIST] = cachedSig
                    return
                }
            }

            val browseStructureClass = runCatching {
                Class.forName("com.spotify.browsita.v1.resolved.BrowseStructure", false, classLoader)
            }.getOrNull()
            if (browseStructureClass != null) {
                val method = browseStructureClass.declaredMethods.firstOrNull {
                    it.parameterTypes.isEmpty() && List::class.java.isAssignableFrom(it.returnType)
                }
                if (method != null) {
                    val sig = "${method.declaringClass.name}#${method.name}"
                    members[KEY_BROWSE_SECTION_LIST] = method
                    sigs[KEY_BROWSE_SECTION_LIST] = sig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    declaredClass("com.spotify.browsita.v1.resolved.BrowseStructure")
                    paramCount(0)
                    returnType("java.util.List")
                }
            }
            val methodData = results.firstOrNull() ?: return
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_BROWSE_SECTION_LIST] = method
            sigs[KEY_BROWSE_SECTION_LIST] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_BROWSE_SECTION_LIST: ${t.message}")
        }
    }

    private fun resolveImageAdFlag(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_IMAGE_AD_FLAG)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName }
                if (method != null) {
                    members[KEY_IMAGE_AD_FLAG] = method
                    sigs[KEY_IMAGE_AD_FLAG] = cachedSig
                    return
                }
            }

            val sectionClass = runCatching {
                Class.forName("com.spotify.scrollsita.v1.Section", false, classLoader)
            }.getOrNull()
            if (sectionClass != null) {
                val method = sectionClass.declaredMethods.firstOrNull {
                    it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType
                }
                if (method != null) {
                    val sig = "${method.declaringClass.name}#${method.name}"
                    members[KEY_IMAGE_AD_FLAG] = method
                    sigs[KEY_IMAGE_AD_FLAG] = sig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    declaredClass("com.spotify.scrollsita.v1.Section")
                    paramCount(0)
                    returnType("boolean")
                }
            }
            val methodData = results.firstOrNull() ?: return
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_IMAGE_AD_FLAG] = method
            sigs[KEY_IMAGE_AD_FLAG] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_IMAGE_AD_FLAG: ${t.message}")
        }
    }

    private fun resolveEmbeddedAdPredicate(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_EMBEDDED_AD_PREDICATE)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName }
                if (method != null) {
                    members[KEY_EMBEDDED_AD_PREDICATE] = method
                    sigs[KEY_EMBEDDED_AD_PREDICATE] = cachedSig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    paramTypes("com.spotify.player.model.ContextTrack")
                    returnType("boolean")
                }
            }
            val methodData = results.firstOrNull() ?: return
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_EMBEDDED_AD_PREDICATE] = method
            sigs[KEY_EMBEDDED_AD_PREDICATE] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_EMBEDDED_AD_PREDICATE: ${t.message}")
        }
    }

    private fun resolveProvidedItemsList(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        try {
            val cachedSig = cached?.get(KEY_PROVIDED_ITEMS_LIST)
            if (cachedSig != null) {
                val (className, methodName) = cachedSig.split("#")
                val clazz = Class.forName(className, false, classLoader)
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName }
                if (method != null) {
                    members[KEY_PROVIDED_ITEMS_LIST] = method
                    sigs[KEY_PROVIDED_ITEMS_LIST] = cachedSig
                    return
                }
            }

            val providedClass = runCatching {
                Class.forName("com.spotify.casita.v1.resolved.Provided", false, classLoader)
            }.getOrNull()
            if (providedClass != null) {
                val method = providedClass.declaredMethods.firstOrNull {
                    it.parameterTypes.isEmpty() && List::class.java.isAssignableFrom(it.returnType)
                }
                if (method != null) {
                    val sig = "${method.declaringClass.name}#${method.name}"
                    members[KEY_PROVIDED_ITEMS_LIST] = method
                    sigs[KEY_PROVIDED_ITEMS_LIST] = sig
                    return
                }
            }

            val results = dexKit.findMethod {
                matcher {
                    declaredClass("com.spotify.casita.v1.resolved.Provided")
                    paramCount(0)
                    returnType("java.util.List")
                }
            }
            val methodData = results.firstOrNull() ?: return
            val method = methodData.getMethodInstance(classLoader)
            val sig = "${method.declaringClass.name}#${method.name}"
            members[KEY_PROVIDED_ITEMS_LIST] = method
            sigs[KEY_PROVIDED_ITEMS_LIST] = sig
        } catch (t: Throwable) {
            Log.w(TAG, "Failed resolving $KEY_PROVIDED_ITEMS_LIST: ${t.message}")
        }
    }
}
