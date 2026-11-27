package app.spotxero.core

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object DexKitResolver {

    private const val TAG = "SpotXero.Resolver"

    const val KEY_SHARE_URL_BUILDER = "share_url_builder"
    const val KEY_SHARE_RESPONSE_URLS = "share_response_urls"
    const val KEY_GET_ADS_WRAPPER = "get_ads_wrapper"
    const val KEY_EMPTY_ADS_RESPONSE = "empty_ads_response"
    const val KEY_PREMIUM_TAB_FLAG = "premium_tab_flag"
    const val KEY_HOME_SECTION_LIST = "home_section_list_getter"
    const val KEY_BROWSE_SECTION_LIST = "browse_section_list_getter"
    const val KEY_CONTENT_CATEGORY_ITEMS_LIST = "content_category_items_list"
    const val KEY_IMAGE_AD_PREDICATE = "scrollsita_image_ad_predicate"
    const val KEY_PROVIDED_ITEMS_LIST = "provided_items_list_getter"
    const val KEY_SMART_SHUFFLE_FLAGS = "smart_shuffle_flags"
    const val KEY_PRODUCT_STATE_MAP = "product_state_map"
    const val KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS = "product_state_attribute_objects"
    const val KEY_PRODUCT_STATE_ATTRIBUTE_VALUE_FIELD = "product_state_attribute_value_field"
    const val KEY_PLAYER_RESTRICTIONS_METHODS = "player_restrictions_methods"
    const val KEY_PLAYER_RESTRICTIONS_CONVERTER = "player_restrictions_converter"
    const val KEY_ON_DEMAND_IN_FREE_GETTERS = "on_demand_in_free_getters"
    const val KEY_SET_SHUFFLE_COMMAND_VALUE = "set_shuffle_command_value"
    const val KEY_PLAYER_OPTIONS_SHUFFLE_GETTER = "player_options_shuffle_getter"
    const val KEY_PLAYER_OPTIONS_BUILDER_SHUFFLE = "player_options_builder_shuffle"
    const val KEY_PREPARE_PLAY_OPTIONS_BUILDER_BUILD = "prepare_play_options_builder_build"
    const val KEY_PLAYER_OPTION_OVERRIDES_BUILDER_SHUFFLE = "player_option_overrides_builder_shuffle"
    const val KEY_ES_CONTEXT_PLAYER_OPTIONS_SHUFFLE_GETTER = "es_context_player_options_shuffle_getter"
    const val KEY_PLAYER_OPTION_OVERRIDES_SHUFFLE_GETTER = "player_option_overrides_shuffle_getter"
    const val KEY_PREPARE_PLAY_OPTIONS_SUPPRESSIONS_GETTER = "prepare_play_options_suppressions_getter"
    const val KEY_SET_SHUFFLE_COMMAND_CREATE = "set_shuffle_command_create"
    const val KEY_SET_SHUFFLE_COMMAND_VALUE_GETTER = "set_shuffle_command_value_getter"
    const val KEY_PLAYBACK_SETTINGS_SET_SHUFFLE = "playback_settings_set_shuffle"
    const val KEY_SHUFFLE_STATE_PARSE = "shuffle_state_parse"
    const val KEY_SHUFFLE_ENUM_LINEAR = "shuffle_enum_linear"
    const val KEY_SHUFFLE_ENUM_SHUFFLE = "shuffle_enum_shuffle"
    const val KEY_SHUFFLE_ENUM_SMART_SHUFFLE = "shuffle_enum_smart_shuffle"
    const val KEY_SHUFFLE_CONTEXT_MODES_GETTER = "shuffle_context_modes_getter"
    const val KEY_SHUFFLE_CONTEXT_DEFAULT_GETTER = "shuffle_context_default_getter"
    const val KEY_SHUFFLE_CONTEXT_ENABLED_GETTER = "shuffle_context_enabled_getter"
    const val KEY_CONTEXT_CAPABILITY_INIT = "context_capability_init"
    const val KEY_CLIENT_BASE_CALL_SINGLE = "client_base_call_single"
    const val KEY_COROUTINE_CLIENT_BASE_CALL_SINGLE = "coroutine_client_base_call_single"
    const val KEY_CLIENT_BASE_CALL_STREAM = "client_base_call_stream"
    const val KEY_COROUTINE_CLIENT_BASE_CALL_STREAM = "coroutine_client_base_call_stream"
    const val KEY_KOTLIN_UNIT_CLASS = "kotlin_unit_class"
    const val KEY_MFT_DISALLOW_RESTRICTION_HANDLER = "mft_disallow_restriction_handler"
    const val KEY_OPTIONAL_ABSENT_INSTANCE = "optional_absent_instance"
    const val KEY_UCS_ACCOUNT_ATTRIBUTES_GETTER = "ucs_account_attributes_getter"
    const val KEY_RCS_CONFIGURATION_ASSIGNED_VALUES = "rcs_configuration_assigned_values"
    const val KEY_RCS_MAP_LOADER = "rcs_map_loader"
    const val KEY_RCS_PROPERTY_LOOKUP = "rcs_property_lookup"
    const val KEY_PLAYER_SECTION_LIST = "player_section_list_getter"
    const val KEY_OKHTTP_REQUEST_TO_STRING = "okhttp_request_to_string"
    const val KEY_OKHTTP_RESPONSE_BUILDER_BUILD = "okhttp_response_builder_build"
    const val KEY_OKHTTP_REAL_CALL_CHAIN = "okhttp_real_call_chain"
    const val KEY_CLIENT_TOKEN_INTERCEPTOR = "client_token_interceptor"
    const val KEY_RETROFIT_CREATE = "retrofit_create"
    const val KEY_CONTEXT_MENU_VIEW_MODEL_CTOR = "context_menu_view_model_ctor"
    const val KEY_LYRICS_CAP_STATUS_GETTER = "lyrics_cap_status_getter"
    const val KEY_LYRICS_CONCRETE_BODY_CLASS = "lyrics_concrete_body_class"
    const val KEY_HOME_CHIPS_MAPPER = "home_chips_mapper"
    const val KEY_SEARCH_CHIPS_CTOR = "search_chips_ctor"
    const val KEY_SEARCH_RESPONSE_MAPPER = "search_response_mapper"
    const val KEY_ACTIVITY_VOLUME_KEY_HANDLER = "activity_volume_key_handler"
    const val KEY_VOLUME_KEY_HANDLER = "volume_key_handler"
    const val KEY_SETTINGS_ROOT_FACTORY = "settings_root_factory"
    const val KEY_SETTINGS_MODEL_CTOR = "settings_model_ctor"
    const val KEY_SETTINGS_SECTION_CTOR = "settings_section_ctor"
    const val KEY_SETTINGS_PERSISTENT_LIST_FACTORY = "settings_persistent_list_factory"
    const val KEY_SETTINGS_TOGGLE_ITEM_CTOR = "settings_toggle_item_ctor"
    const val KEY_SETTINGS_SUBTITLE_TEXT_CTOR = "settings_subtitle_text_ctor"
    const val KEY_SETTINGS_TOGGLE_CONTROL_CTOR = "settings_toggle_control_ctor"
    const val KEY_SETTINGS_TOGGLE_ID_CTOR = "settings_toggle_id_ctor"
    const val KEY_SETTINGS_TOGGLE_ID_ARG = "settings_toggle_id_arg"
    const val KEY_SETTINGS_REACTIVE_TOGGLE_CTOR = "settings_reactive_toggle_ctor"
    const val KEY_SETTINGS_SUCCESS_RESULT_CTOR = "settings_success_result_ctor"
    const val KEY_SETTINGS_STATE_FLOW_CTOR = "settings_state_flow_ctor"
    const val KEY_SETTINGS_STATE_FLOW_EMIT = "settings_state_flow_emit"
    const val KEY_SETTINGS_ACTION_URI_CTOR = "settings_action_uri_ctor"
    const val KEY_SETTINGS_ACTION_CONTROL_CTOR = "settings_action_control_ctor"
    const val KEY_SETTINGS_EXTERNAL_URL_CTOR = "settings_external_url_ctor"
    const val KEY_SETTINGS_ACTION_ID_CLASS = "settings_action_id_class"
    const val KEY_SETTINGS_ROUTER_NAVIGATE = "settings_router_navigate"
    const val KEY_SETTINGS_PAGE_HOST_CTOR = "settings_page_host_ctor"
    const val KEY_SETTINGS_STRING_TITLE_CTOR = "settings_string_title_ctor"
    const val KEY_SETTINGS_HEADER_PROP_CTOR = "settings_header_prop_ctor"
    const val KEY_SETTINGS_TITLE_RESOLVER = "settings_title_resolver"
    const val KEY_SETTINGS_ICON_LOGO_CLASS = "settings_icon_logo_class"
    const val KEY_SETTINGS_VECTOR_BUILDER_CLASS = "settings_vector_builder_class"
    const val KEY_SETTINGS_VECTOR_PATH_CTOR = "settings_vector_path_ctor"
    const val KEY_SETTINGS_PATH_MOVETO_CTOR = "settings_path_moveto_ctor"
    const val KEY_SETTINGS_PATH_CURVETO_CTOR = "settings_path_curveto_ctor"
    const val KEY_SETTINGS_PATH_CLOSE_CLASS = "settings_path_close_class"
    const val KEY_SETTINGS_ROW_PARCEL_CLASS = "settings_row_parcel_class"
    const val KEY_APP_SIGNATURES_METHOD = "app_signatures_method"
    const val KEY_INSTALLER_PACKAGE_METHOD = "installer_package_method"
    const val KEY_INSTALLER_ALLOWLIST_METHOD = "installer_allowlist_method"

    private const val CLIENT_BASE = "com.spotify.esperanto.esperanto.ClientBase"
    private const val COROUTINE_CLIENT_BASE = "com.spotify.esperanto.esperanto.CoroutineClientBase"

    private const val GENERATE_URL_RESPONSE = "com.spotify.share.linkgeneration.api.proto.GenerateUrlResponse"
    private const val GET_ADS_REQUEST = "com.spotify.ads.esperanto.proto.GetAdsRequest"
    private const val GET_ADS_RESPONSE = "com.spotify.ads.esperanto.proto.GetAdsResponse"
    private val GET_ADS_CALL = listOf("spotify.ads.esperanto.proto.Ads", "GetAds")
    private const val HOME_STRUCTURE = "com.spotify.casita.v1.resolved.HomeStructure"
    private const val BROWSE_STRUCTURE = "com.spotify.browsita.v1.resolved.BrowseStructure"
    private const val CONTENT_CATEGORY = "com.spotify.browsita.v1.resolved.ContentCategory"
    private const val PLAYER_STRUCTURE = "com.spotify.scrollsita.v1.NpvScrollStructure"
    private const val PROVIDED = "com.spotify.casita.v1.resolved.Provided"
    private const val PLAYER_SECTION = "com.spotify.scrollsita.v1.Section"

    private const val IMAGE_BRAND_AD_FIELD = "IMAGE_BRAND_AD_FIELD_NUMBER"
    private const val SHARE_RESPONSE_URL_COUNT = 4
    private const val AUTO_VALUE_RESTRICTIONS = "com.spotify.player.model.AutoValue_Restrictions"
    private const val ES_RESTRICTIONS = "com.spotify.player.esperanto.proto.EsRestrictions\$Restrictions"
    private const val RESTRICTIONS = "com.spotify.player.model.Restrictions"
    private const val UCS_ACCOUNT_ATTRIBUTES_RESPONSE = "com.spotify.ucs.proto.v0.UcsResponseWrapper\$AccountAttributesResponse"
    private const val RCS_CONFIGURATION = "com.spotify.rcs.resolver.grpc.v0.Configuration"
    private const val GET_VALUES_RESPONSE = "com.spotify.connectivity.product_state.esperanto.proto.GetValuesResponse"
    private const val PRODUCT_STATE_PROTO = "com.spotify.remoteconfig.internal.ProductStateProto"
    private const val ACCOUNT_ATTRIBUTE = "com.spotify.remoteconfig.internal.AccountAttribute"
    private const val ATTRIBUTE_VALUE_FIELD = "value_"
    private const val PLAYLIST_REQUEST_RESPONSE = "com.spotify.playlist.proto.PlaylistRequest\$Response"
    private const val IS_ON_DEMAND_IN_FREE_FIELD = "isOnDemandInFree_"
    private const val AUTO_VALUE_SET_SHUFFLE_COMMAND_BUILDER = "com.spotify.player.model.command.AutoValue_SetShufflingContextCommand\$Builder"
    private const val AUTO_VALUE_PLAYER_OPTIONS = "com.spotify.player.model.AutoValue_PlayerOptions"
    private const val AUTO_VALUE_PLAYER_OPTIONS_BUILDER = "com.spotify.player.model.AutoValue_PlayerOptions\$Builder"
    private const val AUTO_VALUE_PREPARE_PLAY_OPTIONS_BUILDER = "com.spotify.player.model.command.options.AutoValue_PreparePlayOptions\$Builder"
    private const val AUTO_VALUE_PLAYER_OPTION_OVERRIDES_BUILDER = "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder"
    private const val AUTO_VALUE_PLAYER_OPTION_OVERRIDES = "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides"
    private const val AUTO_VALUE_PREPARE_PLAY_OPTIONS = "com.spotify.player.model.command.options.AutoValue_PreparePlayOptions"
    private const val SET_SHUFFLE_COMMAND = "com.spotify.player.model.command.SetShufflingContextCommand"
    private const val AUTO_VALUE_SET_SHUFFLE_COMMAND = "com.spotify.player.model.command.AutoValue_SetShufflingContextCommand"
    private const val ES_CONTEXT_PLAYER_OPTIONS = "com.spotify.player.esperanto.proto.EsContextPlayerOptions\$ContextPlayerOptions"
    private const val SHUFFLING_CONTEXT_FIELD = "shufflingContext_"
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
        cachedSignatures: Map<String, String>?,
        includeInAppSettings: Boolean,
        settingsOnly: Boolean = false
    ): Map<String, String> {
        val signatures = mutableMapOf<String, String>()
        val members = mutableMapOf<String, Any>()

        if (settingsOnly) {
            if (includeInAppSettings) {
                resolveSettingsRootFactory(dexKit, classLoader, cachedSignatures, signatures, members)
                resolveNativeSettingsContracts(dexKit, classLoader, cachedSignatures, signatures, members)
            }
            resolved = members
            return signatures
        }

        resolveShareUrlBuilder(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveShareResponseUrls(classLoader, cachedSignatures, signatures, members)
        resolveGetAds(dexKit, classLoader, cachedSignatures, signatures, members)
        resolvePremiumTabFlag(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveSectionList(KEY_HOME_SECTION_LIST, HOME_STRUCTURE, classLoader, cachedSignatures, signatures, members)
        resolveSectionList(KEY_BROWSE_SECTION_LIST, BROWSE_STRUCTURE, classLoader, cachedSignatures, signatures, members)
        resolveSectionList(KEY_CONTENT_CATEGORY_ITEMS_LIST, CONTENT_CATEGORY, classLoader, cachedSignatures, signatures, members)
        resolveSectionList(KEY_PLAYER_SECTION_LIST, PLAYER_STRUCTURE, classLoader, cachedSignatures, signatures, members)
        resolveSectionList(KEY_PROVIDED_ITEMS_LIST, PROVIDED, classLoader, cachedSignatures, signatures, members)
        resolveImageAdPredicate(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveSmartShuffleFlags(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveProductStateMap(classLoader, cachedSignatures, signatures, members)
        resolveProductStateAttributeSeam(classLoader, cachedSignatures, signatures, members)
        resolveUcsAccountAttributesGetter(classLoader, cachedSignatures, signatures, members)
        resolveRcsConfigurationAssignedValues(classLoader, cachedSignatures, signatures, members)
        resolveRcsMapLoader(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveRcsPropertyLookup(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveOkHttpNetwork(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveClientTokenInterceptor(dexKit, classLoader, cachedSignatures, signatures, members)
        resolvePlayerRestrictions(classLoader, cachedSignatures, signatures, members)
        resolvePlayerRestrictionsConverter(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveMftDisallowRestrictionHandler(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveOptionalAbsentInstance(dexKit, classLoader, members)
        resolveOnDemandInFreeGetters(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveSetShuffleCommandValue(classLoader, cachedSignatures, signatures, members)
        resolvePlayerOptionsShuffle(classLoader, cachedSignatures, signatures, members)
        resolvePreparePlayOptionsBuilderBuild(classLoader, cachedSignatures, signatures, members)
        resolvePlayerOptionOverridesBuilderShuffle(classLoader, cachedSignatures, signatures, members)
        resolveEsContextPlayerOptionsShuffle(dexKit, classLoader, cachedSignatures, signatures, members)
        resolvePlayerOptionOverridesShuffleGetter(classLoader, cachedSignatures, signatures, members)
        resolvePreparePlayOptionsSuppressionsGetter(classLoader, cachedSignatures, signatures, members)
        resolveSetShuffleCommandCreate(classLoader, cachedSignatures, signatures, members)
        resolveSetShuffleCommandValueGetter(classLoader, cachedSignatures, signatures, members)
        resolvePlaybackSettingsSetShuffle(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveShuffleStateParse(classLoader, cachedSignatures, signatures, members)
        resolveShuffleContextModes(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveContextCapabilityInit(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveClientBaseCallSingle(classLoader, cachedSignatures, signatures, members)
        resolveCoroutineClientBaseCallSingle(classLoader, cachedSignatures, signatures, members)
        resolveEsperantoCall(CLIENT_BASE, "callStream", 3, KEY_CLIENT_BASE_CALL_STREAM, classLoader, cachedSignatures, signatures, members)
        resolveEsperantoCall(COROUTINE_CLIENT_BASE, "callStream", 3, KEY_COROUTINE_CLIENT_BASE_CALL_STREAM, classLoader, cachedSignatures, signatures, members)
        resolveKotlinUnitClass(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveRetrofitCreate(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveContextMenuViewModelCtor(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveLyricsCapStatus(classLoader, cachedSignatures, signatures, members)
        resolveLyricsConcreteBodyClass(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveHomeChipsMapper(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveSearchChips(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveVolumeKeyHandlers(dexKit, classLoader, cachedSignatures, signatures, members)
        if (includeInAppSettings) {
            resolveSettingsRootFactory(dexKit, classLoader, cachedSignatures, signatures, members)
            resolveNativeSettingsContracts(dexKit, classLoader, cachedSignatures, signatures, members)
        }
        resolveAppSignaturesMethod(dexKit, classLoader, cachedSignatures, signatures, members)
        resolveInstallerPackageMethod(dexKit, classLoader, cachedSignatures, signatures, members)

        resolved = members
        return signatures
    }

    fun <T> get(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return resolved[key] as? T
    }

    fun <T> require(key: String): T =
        get<T>(key) ?: error("Missing resolved symbol: $key")

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
            ?.takeIf { it.size == 2 }
            ?: runCatching {
                val modelsMethod = dexKit.findMethod {
                    matcher {
                        usingStrings(
                            listOf("smart_shuffle_as_default_for_liked_songs", "smart_shuffle_as_default_for_playlist"),
                            StringMatchType.Equals
                        )
                        paramCount(0)
                        returnType("java.util.List")
                    }
                }.singleOrNull() ?: return@runCatching null

                val declaringClassName = modelsMethod.className
                val propertyNames = modelsMethod.usingStrings.filter { it != "android-system-smart-shuffle" }
                val likedIndex = propertyNames.indexOf("smart_shuffle_as_default_for_liked_songs")
                val playlistIndex = propertyNames.indexOf("smart_shuffle_as_default_for_playlist")
                if (likedIndex == -1 || playlistIndex == -1) return@runCatching null

                val getters = modelsMethod.invokes
                    .filter { it.className == declaringClassName && !it.isConstructor }
                    .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }

                val likedMethod = getters.getOrNull(likedIndex)?.takeIf(::isSmartShuffleFlag)
                val playlistMethod = getters.getOrNull(playlistIndex)?.takeIf(::isSmartShuffleFlag)

                if (likedMethod != null && playlistMethod != null) {
                    listOf(likedMethod, playlistMethod)
                } else null
            }.getOrNull()?.takeIf { it.size == 2 }

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

    private fun resolveProductStateMap(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_PRODUCT_STATE_MAP, classLoader, ::isProductStateMapGetter)
            ?: runCatching {
                Class.forName(GET_VALUES_RESPONSE, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isProductStateMapGetter(it) }
            }.getOrNull()?.takeIf(::isProductStateMapGetter)
        store(KEY_PRODUCT_STATE_MAP, method, sigs, members)
    }

    private fun isProductStateMapGetter(method: Method): Boolean =
        method.declaringClass.name == GET_VALUES_RESPONSE &&
            method.parameterCount == 0 &&
            Map::class.java.isAssignableFrom(method.returnType) &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveProductStateAttributeSeam(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val container = runCatching { Class.forName(PRODUCT_STATE_PROTO, false, classLoader) }.getOrNull()
        val attributeClass = runCatching { Class.forName(ACCOUNT_ATTRIBUTE, false, classLoader) }.getOrNull()
        if (container == null || attributeClass == null) {
            Log.w(TAG, "$KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS: no validated match")
            return
        }
        val valueField = runCatching { attributeClass.getDeclaredField(ATTRIBUTE_VALUE_FIELD) }
            .getOrNull()
            ?.takeIf {
                it.type == Any::class.java &&
                    !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.declaringClass == attributeClass
            }
        if (valueField == null) {
            Log.w(TAG, "$KEY_PRODUCT_STATE_ATTRIBUTE_VALUE_FIELD: no validated match")
            return
        }
        val accessor = cachedMethod(cached, KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS, classLoader, ::isProductStateAttributeAccessor)
            ?: container.declaredMethods
                .filter(::isProductStateAttributeAccessor)
                .distinctBy { it.name }
                .singleOrNull()
        if (accessor == null) {
            Log.w(TAG, "$KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS: no validated match")
            return
        }
        valueField.isAccessible = true
        members[KEY_PRODUCT_STATE_ATTRIBUTE_VALUE_FIELD] = valueField
        members[KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS] = accessor
        sigs[KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS] = signatureOf(accessor)
    }

    private fun isProductStateAttributeAccessor(method: Method): Boolean =
        method.declaringClass.name == PRODUCT_STATE_PROTO &&
            method.parameterCount == 0 &&
            method.returnType == Map::class.java &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveUcsAccountAttributesGetter(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_UCS_ACCOUNT_ATTRIBUTES_GETTER, classLoader, ::isUcsAccountAttributesGetter)
            ?: runCatching {
                Class.forName(UCS_ACCOUNT_ATTRIBUTES_RESPONSE, false, classLoader)
                    .declaredMethods
                    .firstOrNull(::isUcsAccountAttributesGetter)
            }.getOrNull()
        store(KEY_UCS_ACCOUNT_ATTRIBUTES_GETTER, method, sigs, members)
    }

    private fun isUcsAccountAttributesGetter(method: Method): Boolean =
        method.declaringClass.name == UCS_ACCOUNT_ATTRIBUTES_RESPONSE &&
            method.parameterCount == 0 &&
            Map::class.java.isAssignableFrom(method.returnType) &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveRcsConfigurationAssignedValues(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_RCS_CONFIGURATION_ASSIGNED_VALUES, classLoader, ::isRcsConfigurationAssignedValuesGetter)
            ?: runCatching {
                Class.forName(RCS_CONFIGURATION, false, classLoader)
                    .declaredMethods
                    .firstOrNull(::isRcsConfigurationAssignedValuesGetter)
            }.getOrNull()
        store(KEY_RCS_CONFIGURATION_ASSIGNED_VALUES, method, sigs, members)
    }

    private fun isRcsConfigurationAssignedValuesGetter(method: Method): Boolean =
        method.declaringClass.name == RCS_CONFIGURATION &&
            method.parameterCount == 0 &&
            List::class.java.isAssignableFrom(method.returnType) &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolvePlayerRestrictions(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val methods = cached?.get(KEY_PLAYER_RESTRICTIONS_METHODS)
            ?.split('|')
            ?.mapNotNull { cachedMethod(classLoader, it, ::isRestrictionsMethod) }
            ?.ifEmpty { null }
            ?: runCatching {
                val clazz = Class.forName(AUTO_VALUE_RESTRICTIONS, false, classLoader)
                clazz.declaredMethods.filter(::isRestrictionsMethod)
            }.getOrNull()?.ifEmpty { null }

        if (methods.isNullOrEmpty()) {
            Log.w(TAG, "$KEY_PLAYER_RESTRICTIONS_METHODS: no validated match")
            return
        }
        members[KEY_PLAYER_RESTRICTIONS_METHODS] = methods
        sigs[KEY_PLAYER_RESTRICTIONS_METHODS] = methods.joinToString("|") { signatureOf(it) }
    }

    private fun isRestrictionsMethod(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_RESTRICTIONS &&
            method.parameterCount == 0 &&
            (Set::class.java.isAssignableFrom(method.returnType) || Collection::class.java.isAssignableFrom(method.returnType)) &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolvePlayerRestrictionsConverter(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_PLAYER_RESTRICTIONS_CONVERTER, classLoader, ::isRestrictionsConverter)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        paramTypes(ES_RESTRICTIONS)
                        returnType(RESTRICTIONS)
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isRestrictionsConverter)
        store(KEY_PLAYER_RESTRICTIONS_CONVERTER, method, sigs, members)
    }

    private fun isRestrictionsConverter(method: Method): Boolean =
        method.parameterCount == 1 &&
            method.parameterTypes[0].name == ES_RESTRICTIONS &&
            method.returnType.name == RESTRICTIONS &&
            java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveMftDisallowRestrictionHandler(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_MFT_DISALLOW_RESTRICTION_HANDLER, classLoader, ::isMftDisallowRestrictionHandler)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("mft_disallow"), StringMatchType.Equals)
                        paramCount(2)
                    }
                }.firstOrNull { isMftDisallowRestrictionHandler(it.getMethodInstance(classLoader)) }?.getMethodInstance(classLoader)
            }.getOrNull()
        store(KEY_MFT_DISALLOW_RESTRICTION_HANDLER, method, sigs, members)
    }

    private fun isMftDisallowRestrictionHandler(method: Method): Boolean =
        method.parameterCount == 2 &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveOptionalAbsentInstance(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        members: MutableMap<String, Any>
    ) {
        val instance = runCatching {
            val absentClassData = dexKit.findClass {
                matcher {
                    usingStrings(listOf("Optional.absent()"), StringMatchType.Equals)
                }
            }.singleOrNull() ?: return@runCatching null
            val absentClass = Class.forName(absentClassData.name, false, classLoader)
            val field = absentClass.declaredFields.firstOrNull { f ->
                java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == absentClass
            } ?: return@runCatching null
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
        if (instance != null) {
            members[KEY_OPTIONAL_ABSENT_INSTANCE] = instance
        }
    }

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
                val clazz = Class.forName(PLAYLIST_REQUEST_RESPONSE, false, classLoader)
                val field = clazz.getDeclaredField(IS_ON_DEMAND_IN_FREE_FIELD)
                dexKit.findMethod {
                    matcher {
                        declaredClass(PLAYLIST_REQUEST_RESPONSE)
                        paramCount(0)
                        returnType("boolean")
                        addUsingField(org.luckypray.dexkit.query.matchers.UsingFieldMatcher(field))
                    }
                }.mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                    .filter { isOnDemandInFreeGetter(it) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }

        if (methods.isNullOrEmpty()) {
            Log.w(TAG, "$KEY_ON_DEMAND_IN_FREE_GETTERS: no validated match")
            return
        }
        members[KEY_ON_DEMAND_IN_FREE_GETTERS] = methods
        sigs[KEY_ON_DEMAND_IN_FREE_GETTERS] = methods.joinToString("|") { signatureOf(it) }
    }

    private fun isOnDemandInFreeGetter(method: Method): Boolean =
        method.declaringClass.name == PLAYLIST_REQUEST_RESPONSE &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveSetShuffleCommandValue(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_SET_SHUFFLE_COMMAND_VALUE, classLoader, ::isSetShuffleCommandValue)
            ?: runCatching {
                Class.forName(AUTO_VALUE_SET_SHUFFLE_COMMAND_BUILDER, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isSetShuffleCommandValue(it) }
            }.getOrNull()
        store(KEY_SET_SHUFFLE_COMMAND_VALUE, method, sigs, members)
    }

    private fun isSetShuffleCommandValue(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_SET_SHUFFLE_COMMAND_BUILDER &&
            method.name == "value" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Boolean::class.javaPrimitiveType

    private fun resolvePlayerOptionsShuffle(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val getter = cachedMethod(cached, KEY_PLAYER_OPTIONS_SHUFFLE_GETTER, classLoader, ::isPlayerOptionsShuffleGetter)
            ?: runCatching {
                Class.forName(AUTO_VALUE_PLAYER_OPTIONS, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isPlayerOptionsShuffleGetter(it) }
            }.getOrNull()
        store(KEY_PLAYER_OPTIONS_SHUFFLE_GETTER, getter, sigs, members)

        val builderSetter = cachedMethod(cached, KEY_PLAYER_OPTIONS_BUILDER_SHUFFLE, classLoader, ::isPlayerOptionsBuilderShuffle)
            ?: runCatching {
                Class.forName(AUTO_VALUE_PLAYER_OPTIONS_BUILDER, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isPlayerOptionsBuilderShuffle(it) }
            }.getOrNull()
        store(KEY_PLAYER_OPTIONS_BUILDER_SHUFFLE, builderSetter, sigs, members)
    }

    private fun isPlayerOptionsShuffleGetter(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_PLAYER_OPTIONS &&
            method.name == "shufflingContext" &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType

    private fun isPlayerOptionsBuilderShuffle(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_PLAYER_OPTIONS_BUILDER &&
            method.name == "shufflingContext" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Boolean::class.javaPrimitiveType

    private fun resolvePreparePlayOptionsBuilderBuild(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_PREPARE_PLAY_OPTIONS_BUILDER_BUILD, classLoader, ::isPreparePlayOptionsBuilderBuild)
            ?: runCatching {
                Class.forName(AUTO_VALUE_PREPARE_PLAY_OPTIONS_BUILDER, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isPreparePlayOptionsBuilderBuild(it) }
            }.getOrNull()
        store(KEY_PREPARE_PLAY_OPTIONS_BUILDER_BUILD, method, sigs, members)
    }

    private fun isPreparePlayOptionsBuilderBuild(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_PREPARE_PLAY_OPTIONS_BUILDER &&
            method.name == "build" &&
            method.parameterCount == 0

    private fun resolvePlayerOptionOverridesBuilderShuffle(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_PLAYER_OPTION_OVERRIDES_BUILDER_SHUFFLE, classLoader, ::isPlayerOptionOverridesBuilderShuffle)
            ?: runCatching {
                Class.forName(AUTO_VALUE_PLAYER_OPTION_OVERRIDES_BUILDER, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isPlayerOptionOverridesBuilderShuffle(it) }
            }.getOrNull()
        store(KEY_PLAYER_OPTION_OVERRIDES_BUILDER_SHUFFLE, method, sigs, members)
    }

    private fun isPlayerOptionOverridesBuilderShuffle(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_PLAYER_OPTION_OVERRIDES_BUILDER &&
            method.name == "shufflingContext" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Boolean::class.javaObjectType

    private fun resolveEsContextPlayerOptionsShuffle(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val getter = cachedMethod(cached, KEY_ES_CONTEXT_PLAYER_OPTIONS_SHUFFLE_GETTER, classLoader, ::isEsContextPlayerOptionsShuffleGetter)
            ?: runCatching {
                val clazz = Class.forName(ES_CONTEXT_PLAYER_OPTIONS, false, classLoader)
                val field = clazz.getDeclaredField(SHUFFLING_CONTEXT_FIELD)
                dexKit.findMethod {
                    matcher {
                        declaredClass(ES_CONTEXT_PLAYER_OPTIONS)
                        paramCount(0)
                        returnType("boolean")
                        addUsingField(org.luckypray.dexkit.query.matchers.UsingFieldMatcher(field))
                    }
                }.mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                    .firstOrNull { isEsContextPlayerOptionsShuffleGetter(it) }
            }.getOrNull()
        store(KEY_ES_CONTEXT_PLAYER_OPTIONS_SHUFFLE_GETTER, getter, sigs, members)
    }

    private fun isEsContextPlayerOptionsShuffleGetter(method: Method): Boolean =
        method.declaringClass.name == ES_CONTEXT_PLAYER_OPTIONS &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolvePlayerOptionOverridesShuffleGetter(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_PLAYER_OPTION_OVERRIDES_SHUFFLE_GETTER, classLoader, ::isPlayerOptionOverridesShuffleGetter)
            ?: runCatching {
                Class.forName(AUTO_VALUE_PLAYER_OPTION_OVERRIDES, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isPlayerOptionOverridesShuffleGetter(it) }
            }.getOrNull()
        store(KEY_PLAYER_OPTION_OVERRIDES_SHUFFLE_GETTER, method, sigs, members)
    }

    private fun isPlayerOptionOverridesShuffleGetter(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_PLAYER_OPTION_OVERRIDES &&
            method.name == "shufflingContext" &&
            method.parameterCount == 0 &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolvePreparePlayOptionsSuppressionsGetter(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_PREPARE_PLAY_OPTIONS_SUPPRESSIONS_GETTER, classLoader, ::isPreparePlayOptionsSuppressionsGetter)
            ?: runCatching {
                Class.forName(AUTO_VALUE_PREPARE_PLAY_OPTIONS, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isPreparePlayOptionsSuppressionsGetter(it) }
            }.getOrNull()
        store(KEY_PREPARE_PLAY_OPTIONS_SUPPRESSIONS_GETTER, method, sigs, members)
    }

    private fun isPreparePlayOptionsSuppressionsGetter(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_PREPARE_PLAY_OPTIONS &&
            method.name == "suppressions" &&
            method.parameterCount == 0 &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveSetShuffleCommandCreate(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_SET_SHUFFLE_COMMAND_CREATE, classLoader, ::isSetShuffleCommandCreate)
            ?: runCatching {
                Class.forName(SET_SHUFFLE_COMMAND, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isSetShuffleCommandCreate(it) }
            }.getOrNull()
        store(KEY_SET_SHUFFLE_COMMAND_CREATE, method, sigs, members)
    }

    private fun isSetShuffleCommandCreate(method: Method): Boolean =
        method.declaringClass.name == SET_SHUFFLE_COMMAND &&
            method.name == "create" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
            java.lang.reflect.Modifier.isStatic(method.modifiers)

    private fun resolveSetShuffleCommandValueGetter(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_SET_SHUFFLE_COMMAND_VALUE_GETTER, classLoader, ::isSetShuffleCommandValueGetter)
            ?: runCatching {
                Class.forName(AUTO_VALUE_SET_SHUFFLE_COMMAND, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isSetShuffleCommandValueGetter(it) }
            }.getOrNull()
        store(KEY_SET_SHUFFLE_COMMAND_VALUE_GETTER, method, sigs, members)
    }

    private fun isSetShuffleCommandValueGetter(method: Method): Boolean =
        method.declaringClass.name == AUTO_VALUE_SET_SHUFFLE_COMMAND &&
            method.name == "value" &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers)

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

    private fun resolvePlaybackSettingsSetShuffle(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_PLAYBACK_SETTINGS_SET_SHUFFLE, classLoader, ::isPlaybackSettingsSetShuffle)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(
                            listOf("spotify.playback_settings.esperanto.proto.PlaybackSettings", "SetShuffleState"),
                            StringMatchType.Equals
                        )
                        paramTypes(listOf("java.lang.String", "boolean", "boolean", "boolean"))
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isPlaybackSettingsSetShuffle)
        store(KEY_PLAYBACK_SETTINGS_SET_SHUFFLE, method, sigs, members)
    }

    private fun isPlaybackSettingsSetShuffle(method: Method): Boolean =
        method.parameterCount == 4 &&
            method.parameterTypes[0] == String::class.java &&
            method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
            method.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
            method.parameterTypes[3] == Boolean::class.javaPrimitiveType

    private fun resolveShuffleStateParse(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_SHUFFLE_STATE_PARSE, classLoader, ::isShuffleStateParse)
            ?: runCatching {
                val clazz = Class.forName(
                    "com.spotify.playback_settings.esperanto.proto.PlaybackSettingsEsperantoService\$ShuffleState",
                    false,
                    classLoader
                )
                clazz.declaredMethods.firstOrNull { isShuffleStateParse(it) }
            }.getOrNull()
        store(KEY_SHUFFLE_STATE_PARSE, method, sigs, members)
    }

    private fun isShuffleStateParse(method: Method): Boolean =
        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == ByteArray::class.java &&
            method.declaringClass.isAssignableFrom(method.returnType)

    private fun resolveShuffleContextModes(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val modesMethod = cachedMethod(cached, KEY_SHUFFLE_CONTEXT_MODES_GETTER, classLoader, ::isShuffleContextModesGetter)
        val defaultMethod = cachedMethod(cached, KEY_SHUFFLE_CONTEXT_DEFAULT_GETTER, classLoader, ::isShuffleContextDefaultGetter)
        val enabledMethod = cachedMethod(cached, KEY_SHUFFLE_CONTEXT_ENABLED_GETTER, classLoader, ::isShuffleContextEnabledGetter)
        if (modesMethod != null && defaultMethod != null && enabledMethod != null) {
            val enumClass = defaultMethod.returnType
            val enumConstants = enumClass.enumConstants
            if (enumConstants != null && enumConstants.size >= 3) {
                members[KEY_SHUFFLE_ENUM_LINEAR] = enumConstants[0]
                members[KEY_SHUFFLE_ENUM_SHUFFLE] = enumConstants[1]
                members[KEY_SHUFFLE_ENUM_SMART_SHUFFLE] = enumConstants[2]
            }
            store(KEY_SHUFFLE_CONTEXT_MODES_GETTER, modesMethod, sigs, members)
            store(KEY_SHUFFLE_CONTEXT_DEFAULT_GETTER, defaultMethod, sigs, members)
            store(KEY_SHUFFLE_CONTEXT_ENABLED_GETTER, enabledMethod, sigs, members)
            return
        }

        val resolved = runCatching {
            val enumClassData = dexKit.findClass {
                matcher {
                    usingStrings(
                        listOf("Linear", "Shuffle", "SmartShuffle", "Loading"),
                        StringMatchType.Equals
                    )
                }
            }.singleOrNull() ?: return@runCatching null

            val enumClass = Class.forName(enumClassData.name, false, classLoader)
            val enumConstants = enumClass.enumConstants ?: return@runCatching null
            if (enumConstants.size < 3) return@runCatching null

            val initMethod = dexKit.findMethod {
                matcher {
                    paramTypes(listOf("java.util.Set", enumClass.name, "boolean"))
                    name("<init>")
                }
            }.singleOrNull() ?: return@runCatching null

            val holderClass = Class.forName(initMethod.className, false, classLoader)
            val modes = holderClass.declaredMethods.firstOrNull {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    Set::class.java.isAssignableFrom(it.returnType)
            } ?: return@runCatching null

            val def = holderClass.declaredMethods.firstOrNull {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == enumClass
            } ?: return@runCatching null

            val enabled = holderClass.declaredMethods.firstOrNull {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType)
            } ?: return@runCatching null

            ShuffleContextResolved(modes, def, enabled, enumConstants)
        }.getOrNull()

        if (resolved != null) {
            val linear = resolved.enums[0]
            val shuffle = resolved.enums[1]
            val smartShuffle = if (resolved.enums.size >= 3) resolved.enums[2] else null
            if (linear != null) members[KEY_SHUFFLE_ENUM_LINEAR] = linear
            if (shuffle != null) members[KEY_SHUFFLE_ENUM_SHUFFLE] = shuffle
            if (smartShuffle != null) members[KEY_SHUFFLE_ENUM_SMART_SHUFFLE] = smartShuffle
            store(KEY_SHUFFLE_CONTEXT_MODES_GETTER, resolved.modes, sigs, members)
            store(KEY_SHUFFLE_CONTEXT_DEFAULT_GETTER, resolved.defaultMode, sigs, members)
            store(KEY_SHUFFLE_CONTEXT_ENABLED_GETTER, resolved.enabled, sigs, members)
        }
    }

    private data class ShuffleContextResolved(
        val modes: Method,
        val defaultMode: Method,
        val enabled: Method,
        val enums: Array<*>
    )

    private fun isShuffleContextModesGetter(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            Set::class.java.isAssignableFrom(method.returnType)

    private fun isShuffleContextDefaultGetter(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType.isEnum

    private fun isShuffleContextEnabledGetter(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == Boolean::class.javaObjectType)

    private fun resolveContextCapabilityInit(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val ctor = cachedConstructor(cached, KEY_CONTEXT_CAPABILITY_INIT, classLoader, ::isContextCapabilityInit)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        paramTypes(listOf("java.lang.String", "java.lang.String", "boolean", "int", "boolean", "boolean"))
                        name("<init>")
                    }
                }.mapNotNull {
                    val clazz = runCatching { Class.forName(it.className, false, classLoader) }.getOrNull()
                    clazz?.declaredConstructors?.firstOrNull { c -> isContextCapabilityInit(c) }
                }.firstOrNull { c ->
                    c.declaringClass.declaredMethods.any { m ->
                        !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                            m.parameterCount == 0 &&
                            m.returnType == Boolean::class.javaPrimitiveType
                    }
                }
            }.getOrNull()

        if (ctor != null) {
            members[KEY_CONTEXT_CAPABILITY_INIT] = ctor
            sigs[KEY_CONTEXT_CAPABILITY_INIT] = signatureOf(ctor)
        }
    }

    private fun isContextCapabilityInit(ctor: Constructor<*>): Boolean {
        val types = ctor.parameterTypes
        return types.size == 6 &&
            types[0] == String::class.java &&
            types[1] == String::class.java &&
            types[2] == Boolean::class.javaPrimitiveType &&
            types[3] == Int::class.javaPrimitiveType &&
            types[4] == Boolean::class.javaPrimitiveType &&
            types[5] == Boolean::class.javaPrimitiveType
    }

    private fun signatureOf(method: Method): String =
        "${method.declaringClass.name}#${method.name}#${descriptorOf(method)}"

    private fun signatureOf(ctor: Constructor<*>): String =
        "${ctor.declaringClass.name}#<init>#${descriptorOf(ctor)}"

    private fun descriptorOf(method: Method): String = buildString {
        append('(')
        method.parameterTypes.forEach { append(descriptorOf(it)) }
        append(')')
        append(descriptorOf(method.returnType))
    }

    private fun descriptorOf(ctor: Constructor<*>): String = buildString {
        append('(')
        ctor.parameterTypes.forEach { append(descriptorOf(it)) }
        append(")V")
    }

    private fun cachedConstructor(
        cached: Map<String, String>?,
        key: String,
        classLoader: ClassLoader,
        validate: (Constructor<*>) -> Boolean
    ): Constructor<*>? {
        val entry = cached?.get(key) ?: return null
        val parts = entry.split("#")
        if (parts.size != 3) return null
        return runCatching {
            Class.forName(parts[0], false, classLoader)
                .declaredConstructors
                .firstOrNull { descriptorOf(it) == parts[2] }
        }.getOrNull()?.takeIf(validate)
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

    private fun resolveClientBaseCallSingle(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_CLIENT_BASE_CALL_SINGLE, classLoader, ::isClientBaseCallSingle)
            ?: runCatching {
                Class.forName(CLIENT_BASE, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isClientBaseCallSingle(it) }
            }.getOrNull()
        store(KEY_CLIENT_BASE_CALL_SINGLE, method, sigs, members)
    }

    private fun isClientBaseCallSingle(method: Method): Boolean =
        method.declaringClass.name == CLIENT_BASE &&
            method.name == "callSingle" &&
            method.parameterCount == 3 &&
            method.parameterTypes[0] == String::class.java &&
            method.parameterTypes[1] == String::class.java

    private fun resolveCoroutineClientBaseCallSingle(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_COROUTINE_CLIENT_BASE_CALL_SINGLE, classLoader, ::isCoroutineClientBaseCallSingle)
            ?: runCatching {
                Class.forName(COROUTINE_CLIENT_BASE, false, classLoader)
                    .declaredMethods
                    .firstOrNull { isCoroutineClientBaseCallSingle(it) }
            }.getOrNull()
        store(KEY_COROUTINE_CLIENT_BASE_CALL_SINGLE, method, sigs, members)
    }

    private fun isCoroutineClientBaseCallSingle(method: Method): Boolean =
        method.declaringClass.name == COROUTINE_CLIENT_BASE &&
            method.name == "callSingle" &&
            method.parameterCount == 4 &&
            method.parameterTypes[0] == String::class.java &&
            method.parameterTypes[1] == String::class.java

    private fun resolveEsperantoCall(
        className: String,
        methodName: String,
        paramCount: Int,
        key: String,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val validate: (Method) -> Boolean = { m ->
            m.declaringClass.name == className &&
                m.name == methodName &&
                m.parameterCount == paramCount &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == String::class.java
        }
        val method = cachedMethod(cached, key, classLoader, validate)
            ?: runCatching {
                Class.forName(className, false, classLoader).declaredMethods.firstOrNull(validate)
            }.getOrNull()
        store(key, method, sigs, members)
    }

    private fun resolveKotlinUnitClass(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val unitClass = cached?.get(KEY_KOTLIN_UNIT_CLASS)
            ?.let { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
            ?.takeIf(::isKotlinUnitClass)
            ?: runCatching { Class.forName("kotlin.Unit", false, classLoader) }
                .getOrNull()
                ?.takeIf(::isKotlinUnitClass)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        name("toString")
                        paramCount(0)
                        returnType("java.lang.String")
                        usingStrings(listOf("kotlin.Unit"), StringMatchType.Equals)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.declaredClass?.getInstance(classLoader) }.getOrNull() }
                    .filter(::isKotlinUnitClass)
                    .singleOrNull()
            }.getOrNull()
        storeClass(KEY_KOTLIN_UNIT_CLASS, unitClass, sigs, members)
    }

    private fun isKotlinUnitClass(cls: Class<*>): Boolean =
        java.lang.reflect.Modifier.isFinal(cls.modifiers) &&
            cls.superclass == Any::class.java &&
            cls.interfaces.isEmpty() &&
            cls.declaredFields.none { !java.lang.reflect.Modifier.isStatic(it.modifiers) } &&
            cls.declaredFields.count { f ->
                java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == cls
            } == 1 &&
            cls.declaredMethods.any { m ->
                m.name == "toString" &&
                    m.parameterCount == 0 &&
                    m.returnType == String::class.java
            }

    private fun resolveRcsMapLoader(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_RCS_MAP_LOADER, classLoader, ::isRcsMapLoader)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("http://go/rc-and-access-before-activation"), StringMatchType.Contains)
                        paramTypes(listOf("java.lang.String"))
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isRcsMapLoader)
        store(KEY_RCS_MAP_LOADER, method, sigs, members)
    }

    private fun isRcsMapLoader(method: Method): Boolean {
        if (java.lang.reflect.Modifier.isStatic(method.modifiers)) return false
        if (method.parameterCount != 1 || method.parameterTypes[0] != String::class.java) return false
        val ret = method.returnType
        if (ret.isPrimitive) return false
        val hasCtor = ret.declaredConstructors.any { ctor ->
            val p = ctor.parameterTypes
            p.size == 2 && !p[0].isPrimitive && Map::class.java.isAssignableFrom(p[1])
        }
        return hasCtor && ret.declaredFields.any { Map::class.java.isAssignableFrom(it.type) }
    }

    private fun resolveRcsPropertyLookup(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_RCS_PROPERTY_LOOKUP, classLoader, ::isRcsPropertyLookup)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("Not possible for UnAuthResolver"), StringMatchType.Equals)
                        paramTypes(listOf("java.lang.String", "java.lang.String"))
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isRcsPropertyLookup)
        store(KEY_RCS_PROPERTY_LOOKUP, method, sigs, members)
    }

    private fun isRcsPropertyLookup(method: Method): Boolean {
        if (java.lang.reflect.Modifier.isStatic(method.modifiers)) return false
        if (method.parameterCount != 2 || method.parameterTypes[0] != String::class.java || method.parameterTypes[1] != String::class.java) {
            return false
        }
        val ret = method.returnType
        return ret.declaredConstructors.any { ctor ->
            val p = ctor.parameterTypes
            p.size == 7 &&
                p[0] == String::class.java &&
                p[1] == String::class.java &&
                p[2] == Boolean::class.javaObjectType &&
                p[3] == Int::class.javaObjectType &&
                p[4] == String::class.java &&
                p[5] == Long::class.javaPrimitiveType &&
                p[6] == Long::class.javaPrimitiveType
        }
    }

    private fun resolveOkHttpNetwork(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val requestToString = cachedMethod(cached, KEY_OKHTTP_REQUEST_TO_STRING, classLoader, ::isOkHttpRequestToString)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("Request{method="), StringMatchType.Contains)
                        paramCount(0)
                        returnType("java.lang.String")
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isOkHttpRequestToString)
        store(KEY_OKHTTP_REQUEST_TO_STRING, requestToString, sigs, members)

        val requestClass = requestToString?.declaringClass ?: return
        val responseBuilderBuild = cachedMethod(cached, KEY_OKHTTP_RESPONSE_BUILDER_BUILD, classLoader) {
            isOkHttpResponseBuilderBuild(it, requestClass)
        } ?: runCatching {
            dexKit.findMethod {
                matcher {
                    usingStrings(listOf("protocol == null"), StringMatchType.Equals)
                    paramCount(0)
                }
            }.singleOrNull()?.getMethodInstance(classLoader)
        }.getOrNull()?.takeIf { isOkHttpResponseBuilderBuild(it, requestClass) }
        store(KEY_OKHTTP_RESPONSE_BUILDER_BUILD, responseBuilderBuild, sigs, members)

        val responseClass = responseBuilderBuild?.returnType ?: return
        val realCallChain = cachedMethod(cached, KEY_OKHTTP_REAL_CALL_CHAIN, classLoader) {
            isOkHttpRealCallChain(it, requestClass, responseClass)
        } ?: runCatching {
            dexKit.findMethod {
                matcher {
                    usingStrings(listOf("Canceled"), StringMatchType.Equals)
                    paramCount(0)
                    returnType(responseClass.name)
                }
            }.mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                .singleOrNull { isOkHttpRealCallChain(it, requestClass, responseClass) }
        }.getOrNull()?.takeIf { isOkHttpRealCallChain(it, requestClass, responseClass) }
        store(KEY_OKHTTP_REAL_CALL_CHAIN, realCallChain, sigs, members)
    }

    private fun isOkHttpRequestToString(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.name == "toString" &&
            method.parameterCount == 0 &&
            method.returnType == String::class.java &&
            method.declaringClass.declaredFields.any { f ->
                !f.type.isPrimitive && f.type.declaredMethods.any { m -> m.returnType == java.net.URI::class.java }
            }

    private fun isOkHttpResponseBuilderBuild(method: Method, requestClass: Class<*>): Boolean {
        if (java.lang.reflect.Modifier.isStatic(method.modifiers)) return false
        if (method.parameterCount != 0 || method.returnType.isPrimitive) return false
        val cls = method.declaringClass
        val hasZeroCtor = cls.declaredConstructors.any { it.parameterCount == 0 }
        val fields = cls.declaredFields
        return hasZeroCtor &&
            fields.any { it.type == requestClass } &&
            fields.any { it.type == Int::class.javaPrimitiveType } &&
            fields.any { it.type == String::class.java } &&
            fields.any { it.type.isEnum && it.type.enumConstants?.any { c -> c.toString() == "http/1.1" } == true }
    }

    private fun isOkHttpRealCallChain(method: Method, requestClass: Class<*>, responseClass: Class<*>): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType == responseClass &&
            method.declaringClass.declaredFields.any { it.type == requestClass }

    private fun resolveClientTokenInterceptor(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val requestClass = (members[KEY_OKHTTP_REQUEST_TO_STRING] as? Method)?.declaringClass ?: return
        val responseClass = (members[KEY_OKHTTP_RESPONSE_BUILDER_BUILD] as? Method)?.returnType ?: return
        val method = cachedMethod(cached, KEY_CLIENT_TOKEN_INTERCEPTOR, classLoader) {
            isClientTokenInterceptor(it, requestClass, responseClass)
        } ?: runCatching {
            dexKit.findMethod {
                matcher {
                    usingStrings(listOf("EXPIRED_CLIENTTOKEN"), StringMatchType.Equals)
                    paramCount(1)
                    returnType(responseClass.name)
                }
            }.mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                .singleOrNull { isClientTokenInterceptor(it, requestClass, responseClass) }
        }.getOrNull()?.takeIf { isClientTokenInterceptor(it, requestClass, responseClass) }
        store(KEY_CLIENT_TOKEN_INTERCEPTOR, method, sigs, members)
    }

    private fun isClientTokenInterceptor(
        method: Method,
        requestClass: Class<*>,
        responseClass: Class<*>
    ): Boolean {
        if (java.lang.reflect.Modifier.isStatic(method.modifiers) ||
            method.parameterCount != 1 ||
            method.returnType != responseClass ||
            method.declaringClass.interfaces.size != 1
        ) {
            return false
        }
        val chainClass = method.parameterTypes[0]
        return !chainClass.isPrimitive &&
            chainClass.declaredFields.any { it.type == requestClass } &&
            chainClass.declaredMethods.any {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == requestClass &&
                    it.returnType == responseClass
            }
    }

    private fun resolveRetrofitCreate(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_RETROFIT_CREATE, classLoader, ::isRetrofitCreate)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("API declarations must be interfaces"), StringMatchType.Contains)
                        paramCount(1)
                        paramTypes(listOf("java.lang.Class"))
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isRetrofitCreate)
        store(KEY_RETROFIT_CREATE, method, sigs, members)
    }

    private fun isRetrofitCreate(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Class::class.java &&
            method.returnType == Any::class.java

    private fun resolveContextMenuViewModelCtor(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val ctor = cachedConstructor(cached, KEY_CONTEXT_MENU_VIEW_MODEL_CTOR, classLoader, ::isContextMenuViewModelCtor)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("ContextMenuViewModel cannot contain items with duplicate itemResId"), StringMatchType.Contains)
                        name("<init>")
                    }
                }.mapNotNull {
                    val clazz = runCatching { Class.forName(it.className, false, classLoader) }.getOrNull()
                    clazz?.declaredConstructors?.firstOrNull { c -> isContextMenuViewModelCtor(c) }
                }.firstOrNull()
            }.getOrNull()

        if (ctor != null) {
            members[KEY_CONTEXT_MENU_VIEW_MODEL_CTOR] = ctor
            sigs[KEY_CONTEXT_MENU_VIEW_MODEL_CTOR] = signatureOf(ctor)
        }
    }

    private fun isContextMenuViewModelCtor(ctor: java.lang.reflect.Constructor<*>): Boolean =
        ctor.parameterCount >= 2 &&
            List::class.java.isAssignableFrom(ctor.parameterTypes[1])

    private fun resolveLyricsCapStatus(
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_LYRICS_CAP_STATUS_GETTER, classLoader, ::isLyricsCapStatusGetter)
            ?: runCatching {
                val clazz = Class.forName("com.spotify.lyrics.serviceretrofit.proto.v3.LyricsV3Response", false, classLoader)
                clazz.declaredMethods.firstOrNull { isLyricsCapStatusGetter(it) }
            }.getOrNull()
        store(KEY_LYRICS_CAP_STATUS_GETTER, method, sigs, members)
    }

    private fun isLyricsCapStatusGetter(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 &&
            method.returnType.isEnum &&
            method.returnType.enumConstants?.any { (it as? Enum<*>)?.name == "UNCAPPED" } == true

    private fun resolveLyricsConcreteBodyClass(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val responseBuilderBuild = members[KEY_OKHTTP_RESPONSE_BUILDER_BUILD] as? Method
        val responseBodyClass = responseBuilderBuild?.declaringClass?.declaredFields?.firstOrNull { f ->
            java.io.Closeable::class.java.isAssignableFrom(f.type) &&
                java.lang.reflect.Modifier.isAbstract(f.type.modifiers)
        }?.type
        val cachedClass = cached?.get(KEY_LYRICS_CONCRETE_BODY_CLASS)
            ?.let { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
            ?.takeIf { isLyricsConcreteBodyClass(it, responseBodyClass) }
        val resolved = cachedClass ?: runCatching {
            val abstractBody = responseBodyClass ?: return@runCatching null
            val fromFields = abstractBody.declaredFields.firstOrNull {
                it.type != abstractBody && abstractBody.isAssignableFrom(it.type)
            }?.type?.takeIf { isLyricsConcreteBodyClass(it, abstractBody) }
            if (fromFields != null) return@runCatching fromFields

            dexKit.findClass {
                matcher {
                    superClass(abstractBody.name)
                }
            }.mapNotNull { data ->
                runCatching { Class.forName(data.name, false, classLoader) }.getOrNull()
            }.firstOrNull { isLyricsConcreteBodyClass(it, abstractBody) }
        }.getOrNull()
        if (resolved == null) {
            Log.w(TAG, "$KEY_LYRICS_CONCRETE_BODY_CLASS: no validated match")
            return
        }
        members[KEY_LYRICS_CONCRETE_BODY_CLASS] = resolved
        sigs[KEY_LYRICS_CONCRETE_BODY_CLASS] = resolved.name
    }

    private fun isLyricsConcreteBodyClass(cls: Class<*>, responseBodyClass: Class<*>?): Boolean =
        !java.lang.reflect.Modifier.isAbstract(cls.modifiers) &&
            java.io.Closeable::class.java.isAssignableFrom(cls) &&
            (responseBodyClass == null || responseBodyClass.isAssignableFrom(cls)) &&
            cls.declaredConstructors.any { ctor ->
                ctor.parameterCount == 3 && ctor.parameterTypes[1] == Long::class.javaPrimitiveType
            }

    private fun resolveHomeChipsMapper(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_HOME_CHIPS_MAPPER, classLoader, ::isHomeChipsMapper)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("podcasts-chip", "music-chip"), StringMatchType.Equals)
                        paramTypes("java.util.List")
                        returnType("java.util.ArrayList")
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isHomeChipsMapper)
        store(KEY_HOME_CHIPS_MAPPER, method, sigs, members)
    }

    private fun isHomeChipsMapper(method: Method): Boolean =
        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == List::class.java &&
            method.returnType == java.util.ArrayList::class.java

    private fun resolveSearchChips(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val mapper = cachedMethod(cached, KEY_SEARCH_RESPONSE_MAPPER, classLoader, ::isSearchResponseMapper)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        paramTypes("com.spotify.searchview.proto.MainViewResponse")
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isSearchResponseMapper)
        store(KEY_SEARCH_RESPONSE_MAPPER, mapper, sigs, members)

        val ctor = cachedConstructor(cached, KEY_SEARCH_CHIPS_CTOR, classLoader, ::isSearchChipsCtor)
            ?: runCatching {
                if (mapper == null) return@runCatching null
                val responseClass = mapper.returnType
                for (c in responseClass.declaredConstructors) {
                    if (c.parameterCount >= 5) {
                        val paramType = c.parameterTypes[4]
                        val match = paramType.declaredConstructors.firstOrNull { isSearchChipsCtor(it) }
                        if (match != null) return@runCatching match
                    }
                }
                for (field in responseClass.declaredFields) {
                    val type = field.type
                    if (android.os.Parcelable::class.java.isAssignableFrom(type)) {
                        val match = type.declaredConstructors.firstOrNull { isSearchChipsCtor(it) }
                        if (match != null && type.declaredFields.any { it.type == java.util.ArrayList::class.java }) {
                            return@runCatching match
                        }
                    }
                }
                null
            }.getOrNull()?.takeIf(::isSearchChipsCtor)
        if (ctor != null) {
            members[KEY_SEARCH_CHIPS_CTOR] = ctor
            sigs[KEY_SEARCH_CHIPS_CTOR] = signatureOf(ctor)
        }
    }

    private fun isSearchResponseMapper(method: Method): Boolean =
        method.parameterCount == 1 &&
            method.parameterTypes[0].name == "com.spotify.searchview.proto.MainViewResponse"

    private fun isSearchChipsCtor(ctor: Constructor<*>): Boolean =
        ctor.parameterCount == 1 &&
            ctor.parameterTypes[0] == java.util.ArrayList::class.java

    private fun resolveVolumeKeyHandlers(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val activityHandler = cachedMethod(cached, KEY_ACTIVITY_VOLUME_KEY_HANDLER, classLoader, ::isActivityVolumeKeyHandler)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("volume_on_key_down_main_activity"), StringMatchType.Equals)
                    }
                }.singleOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isActivityVolumeKeyHandler)
        store(KEY_ACTIVITY_VOLUME_KEY_HANDLER, activityHandler, sigs, members)

        val volumeHandler = cachedMethod(cached, KEY_VOLUME_KEY_HANDLER, classLoader, ::isVolumeKeyHandler)
            ?: runCatching {
                val record = dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("volume_on_key_down_main_activity"), StringMatchType.Equals)
                    }
                }.singleOrNull() ?: return@runCatching null
                record.invokes.mapNotNull { data ->
                    runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                }.firstOrNull(::isVolumeKeyHandler)
            }.getOrNull()?.takeIf(::isVolumeKeyHandler)
        store(KEY_VOLUME_KEY_HANDLER, volumeHandler, sigs, members)
    }

    private fun isActivityVolumeKeyHandler(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 2 &&
            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
            method.parameterTypes[1].name == "android.view.KeyEvent" &&
            method.returnType == Boolean::class.javaPrimitiveType

    private fun isVolumeKeyHandler(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 3 &&
            method.parameterTypes[0].name == "android.view.KeyEvent" &&
            method.parameterTypes[1] == String::class.java &&
            method.returnType == Boolean::class.javaPrimitiveType

    private fun resolveSettingsRootFactory(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_SETTINGS_ROOT_FACTORY, classLoader, ::isSettingsRootFactory)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("accountPage", "aboutPage"), StringMatchType.Equals)
                        paramCount(0)
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isSettingsRootFactory)
        store(KEY_SETTINGS_ROOT_FACTORY, method, sigs, members)
    }

    private fun isSettingsRootFactory(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0

    private fun resolveNativeSettingsContracts(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val rootFactory = members[KEY_SETTINGS_ROOT_FACTORY] as? Method
        val rootRecord = if (rootFactory != null && (cached?.get(KEY_SETTINGS_SECTION_CTOR) == null || cached[KEY_SETTINGS_PERSISTENT_LIST_FACTORY] == null)) {
            runCatching {
                dexKit.findMethod {
                    matcher {
                        declaredClass(rootFactory.declaringClass)
                        name(rootFactory.name)
                        paramCount(0)
                    }
                }.firstOrNull()
            }.getOrNull()
        } else null

        val modelCtor = cachedConstructor(cached, KEY_SETTINGS_MODEL_CTOR, classLoader, ::isSettingsModelCtor)
            ?: rootFactory?.returnType?.declaredConstructors?.firstOrNull(::isSettingsModelCtor)
        storeCtor(KEY_SETTINGS_MODEL_CTOR, modelCtor, sigs, members)

        val sectionCtor = cachedConstructor(cached, KEY_SETTINGS_SECTION_CTOR, classLoader, ::isSettingsSectionCtor)
            ?: rootRecord?.invokes
                ?.filter { it.isConstructor && it.paramCount == 6 }
                ?.mapNotNull { runCatching { it.getConstructorInstance(classLoader) }.getOrNull() }
                ?.firstOrNull(::isSettingsSectionCtor)
        storeCtor(KEY_SETTINGS_SECTION_CTOR, sectionCtor, sigs, members)

        val itemsListClass = sectionCtor?.parameterTypes?.getOrNull(4)
        val listFactory = cachedMethod(cached, KEY_SETTINGS_PERSISTENT_LIST_FACTORY, classLoader) { m ->
            isPersistentListFactory(m, itemsListClass)
        } ?: if (rootRecord != null && itemsListClass != null) {
            rootRecord.invokes
                .filter { !it.isConstructor && it.paramCount == 1 }
                .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                .firstOrNull { isPersistentListFactory(it, itemsListClass) }
        } else null
        store(KEY_SETTINGS_PERSISTENT_LIST_FACTORY, listFactory, sigs, members)

        val localFilesItemClass = runCatching {
            Class.forName("com.spotify.localfiles.settings.localfiles.impl.LocalFilesLibrarySettingsItemFactoryImpl", false, classLoader)
        }.getOrNull()
        val localFilesCreateMethod = localFilesItemClass?.declaredMethods?.firstOrNull {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                !it.returnType.isPrimitive &&
                it.returnType != Any::class.java
        }

        val toggleItemCtor = cachedConstructor(cached, KEY_SETTINGS_TOGGLE_ITEM_CTOR, classLoader, ::isSettingsToggleItemCtor)
            ?: localFilesCreateMethod?.returnType?.declaredConstructors?.firstOrNull(::isSettingsToggleItemCtor)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        name("<init>")
                        paramCount(13)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getConstructorInstance(classLoader) }.getOrNull() }
                    .firstOrNull(::isSettingsToggleItemCtor)
            }.getOrNull()
        storeCtor(KEY_SETTINGS_TOGGLE_ITEM_CTOR, toggleItemCtor, sigs, members)

        val controlInterface = toggleItemCtor?.parameterTypes?.getOrNull(10)
        val flowInterface = toggleItemCtor?.parameterTypes?.getOrNull(7)

        val subtitleTextCtor = cachedConstructor(cached, KEY_SETTINGS_SUBTITLE_TEXT_CTOR, classLoader, ::isSubtitleTextCtor)
            ?: runCatching {
                dexKit.findClass {
                    matcher {
                        usingStrings(listOf("Paragraph overlap not allowed, end "), StringMatchType.Contains)
                    }
                }.mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .firstOrNull { CharSequence::class.java.isAssignableFrom(it) }
                    ?.declaredConstructors
                    ?.firstOrNull(::isSubtitleTextCtor)
            }.getOrNull()
        storeCtor(KEY_SETTINGS_SUBTITLE_TEXT_CTOR, subtitleTextCtor, sigs, members)

        val localFilesCreateRecords = if (localFilesItemClass != null && (cached?.get(KEY_SETTINGS_TOGGLE_CONTROL_CTOR) == null)) {
            runCatching {
                dexKit.findMethod {
                    matcher {
                        declaredClass(localFilesItemClass)
                        paramCount(0)
                    }
                }
            }.getOrNull().orEmpty()
        } else emptyList()

        val toggleControlCtor = cachedConstructor(cached, KEY_SETTINGS_TOGGLE_CONTROL_CTOR, classLoader) { ctor ->
            isToggleControlCtor(ctor, controlInterface)
        } ?: localFilesCreateRecords
            .flatMap { it.invokes }
            .filter { it.isConstructor && it.paramCount == 2 }
            .mapNotNull { runCatching { it.getConstructorInstance(classLoader) }.getOrNull() }
            .firstOrNull { isToggleControlCtor(it, controlInterface) }
            ?: if (controlInterface != null) {
                runCatching {
                    dexKit.findClass {
                        matcher {
                            interfaces {
                                add(controlInterface.name)
                            }
                        }
                    }.asSequence()
                        .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                        .flatMap { it.declaredConstructors.asSequence() }
                        .firstOrNull { isToggleControlCtor(it, controlInterface) }
                }.getOrNull()
            } else null
        storeCtor(KEY_SETTINGS_TOGGLE_CONTROL_CTOR, toggleControlCtor, sigs, members)

        val toggleStateFactoryInterface = toggleControlCtor?.parameterTypes?.getOrNull(1)
        val reactiveToggleCtor = cachedConstructor(cached, KEY_SETTINGS_REACTIVE_TOGGLE_CTOR, classLoader) { ctor ->
            isReactiveToggleCtor(ctor, toggleStateFactoryInterface, flowInterface)
        } ?: if (toggleStateFactoryInterface != null && flowInterface != null) {
            runCatching {
                dexKit.findClass {
                    matcher {
                        interfaces {
                            add(toggleStateFactoryInterface.name)
                        }
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull { isReactiveToggleCtor(it, toggleStateFactoryInterface, flowInterface) }
            }.getOrNull()
        } else null
        storeCtor(KEY_SETTINGS_REACTIVE_TOGGLE_CTOR, reactiveToggleCtor, sigs, members)

        val localFilesAccessorClass = runCatching {
            Class.forName("com.spotify.localfiles.settings.localfiles.impl.LocalFilesSettingsValueAccessor", false, classLoader)
        }.getOrNull()
        val successResultCtor = cachedConstructor(cached, KEY_SETTINGS_SUCCESS_RESULT_CTOR, classLoader, ::isSuccessResultCtor)
            ?: if (localFilesAccessorClass != null) {
                runCatching {
                    dexKit.findMethod {
                        matcher {
                            declaredClass(localFilesAccessorClass)
                            name("setValue")
                            paramCount(4)
                        }
                    }.flatMap { it.invokes }
                        .filter { it.isConstructor && it.paramCount == 1 }
                        .mapNotNull { runCatching { it.getConstructorInstance(classLoader) }.getOrNull() }
                        .firstOrNull(::isSuccessResultCtor)
                }.getOrNull()
            } else {
                runCatching {
                    dexKit.findMethod {
                        matcher {
                            name("setValue")
                            paramCount(4)
                        }
                    }.asSequence()
                        .flatMap { it.invokes.asSequence() }
                        .filter { it.isConstructor && it.paramCount == 1 }
                        .mapNotNull { runCatching { it.getConstructorInstance(classLoader) }.getOrNull() }
                        .firstOrNull(::isSuccessResultCtor)
                }.getOrNull()
            }
        storeCtor(KEY_SETTINGS_SUCCESS_RESULT_CTOR, successResultCtor, sigs, members)

        val stateFlowCtor = cachedConstructor(cached, KEY_SETTINGS_STATE_FLOW_CTOR, classLoader, ::isStateFlowCtor)
            ?: runCatching {
                dexKit.findClass {
                    matcher {
                        usingStrings(listOf("MutableStateFlow.resetReplayCache is not supported"), StringMatchType.Equals)
                    }
                }.mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .firstOrNull { flowInterface == null || flowInterface.isAssignableFrom(it) }
                    ?.declaredConstructors
                    ?.firstOrNull(::isStateFlowCtor)
            }.getOrNull()
        storeCtor(KEY_SETTINGS_STATE_FLOW_CTOR, stateFlowCtor, sigs, members)

        val stateFlowEmit = cachedMethod(cached, KEY_SETTINGS_STATE_FLOW_EMIT, classLoader) { m ->
            isStateFlowEmit(m, stateFlowCtor?.declaringClass)
        } ?: stateFlowCtor?.declaringClass?.declaredMethods?.firstOrNull { m ->
            isStateFlowEmit(m, stateFlowCtor.declaringClass)
        }
        store(KEY_SETTINGS_STATE_FLOW_EMIT, stateFlowEmit, sigs, members)

        val actionUriCtor = cachedConstructor(cached, KEY_SETTINGS_ACTION_URI_CTOR, classLoader, ::isActionUriCtor)
            ?: runCatching {
                dexKit.findClass {
                    matcher {
                        usingStrings(listOf("A non-empty URI must be provided."), StringMatchType.Equals)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull(::isActionUriCtor)
            }.getOrNull()
        storeCtor(KEY_SETTINGS_ACTION_URI_CTOR, actionUriCtor, sigs, members)

        val externalUrlCtor = cachedConstructor(cached, KEY_SETTINGS_EXTERNAL_URL_CTOR, classLoader, ::isExternalUrlCtor)
            ?: runCatching {
                dexKit.findClass {
                    matcher {
                        usingStrings(listOf("A non-empty URL must be provided."), StringMatchType.Equals)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull(::isExternalUrlCtor)
            }.getOrNull()
        storeCtor(KEY_SETTINGS_EXTERNAL_URL_CTOR, externalUrlCtor, sigs, members)

        val actionTargetInterface = actionUriCtor?.declaringClass?.interfaces?.singleOrNull()
        val actionControlCtor = cachedConstructor(cached, KEY_SETTINGS_ACTION_CONTROL_CTOR, classLoader) { ctor ->
            isActionControlCtor(ctor, controlInterface, actionTargetInterface)
        } ?: if (controlInterface != null && actionTargetInterface != null) {
            runCatching {
                dexKit.findClass {
                    matcher {
                        interfaces {
                            add(controlInterface.name)
                        }
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull { isActionControlCtor(it, controlInterface, actionTargetInterface) }
            }.getOrNull()
        } else null
        storeCtor(KEY_SETTINGS_ACTION_CONTROL_CTOR, actionControlCtor, sigs, members)

        val actionIdInterface = actionControlCtor?.parameterTypes?.getOrNull(0)
        val actionIdClass = cached?.get(KEY_SETTINGS_ACTION_ID_CLASS)?.let { clsName ->
            runCatching { Class.forName(clsName, false, classLoader) }.getOrNull()
                ?.takeIf { isZeroFieldMarkerImplClass(it, actionIdInterface) }
        } ?: if (actionIdInterface != null) {
            runCatching {
                dexKit.findClass {
                    matcher {
                        interfaces {
                            add(actionIdInterface.name)
                        }
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .firstOrNull { isZeroFieldMarkerImplClass(it, actionIdInterface) }
            }.getOrNull()
        } else null
        if (actionIdClass != null) {
            members[KEY_SETTINGS_ACTION_ID_CLASS] = actionIdClass
            sigs[KEY_SETTINGS_ACTION_ID_CLASS] = actionIdClass.name
        } else {
            Log.w(TAG, "$KEY_SETTINGS_ACTION_ID_CLASS: no validated match")
        }

        val noLogMarker = actionIdClass?.interfaces?.firstOrNull { it != actionIdInterface }
        val toggleIdInterface = toggleControlCtor?.parameterTypes?.getOrNull(0)
        val toggleIdCtor = cachedConstructor(cached, KEY_SETTINGS_TOGGLE_ID_CTOR, classLoader) { ctor ->
            isNoLogToggleIdCtor(ctor, toggleIdInterface, noLogMarker)
        } ?: if (toggleIdInterface != null && noLogMarker != null) {
            runCatching {
                dexKit.findClass {
                    matcher {
                        interfaces {
                            add(toggleIdInterface.name)
                        }
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull { isNoLogToggleIdCtor(it, toggleIdInterface, noLogMarker) }
            }.getOrNull()
        } else null
        storeCtor(KEY_SETTINGS_TOGGLE_ID_CTOR, toggleIdCtor, sigs, members)

        val toggleIdArgBase = toggleIdCtor?.parameterTypes?.getOrNull(0)
        val toggleIdArg = cached?.get(KEY_SETTINGS_TOGGLE_ID_ARG)?.let { clsName ->
            runCatching {
                val cls = Class.forName(clsName, false, classLoader)
                extractSingletonField(cls, toggleIdArgBase)
            }.getOrNull()
        } ?: if (toggleIdArgBase != null) {
            runCatching {
                val candidates = if (toggleIdArgBase.isInterface) {
                    dexKit.findClass { matcher { interfaces { add(toggleIdArgBase.name) } } }
                } else {
                    dexKit.findClass { matcher { superClass(toggleIdArgBase.name) } }
                }
                candidates.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .firstNotNullOfOrNull { extractSingletonField(it, toggleIdArgBase) }
            }.getOrNull()
        } else null
        if (toggleIdArg != null) {
            members[KEY_SETTINGS_TOGGLE_ID_ARG] = toggleIdArg
            sigs[KEY_SETTINGS_TOGGLE_ID_ARG] = toggleIdArg.javaClass.name
        } else {
            Log.w(TAG, "$KEY_SETTINGS_TOGGLE_ID_ARG: no validated match")
        }

        val routerNavigate = cachedMethod(cached, KEY_SETTINGS_ROUTER_NAVIGATE, classLoader, ::isRouterNavigateMethod)
            ?: runCatching {
                dexKit.findClass {
                    matcher {
                        usingStrings(
                            listOf("com.spotify.mobile.android.ui.action.view.SPOTIFY_URI", "is_internal_navigation"),
                            StringMatchType.Equals
                        )
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredMethods.asSequence() }
                    .firstOrNull(::isRouterNavigateMethod)
            }.getOrNull()
        store(KEY_SETTINGS_ROUTER_NAVIGATE, routerNavigate, sigs, members)

        val argsClass = runCatching {
            val cachedHost = cached?.get(KEY_SETTINGS_PAGE_HOST_CTOR)?.let {
                cachedConstructor(cached, KEY_SETTINGS_PAGE_HOST_CTOR, classLoader) { c -> c.parameterCount == 4 }
            }
            if (cachedHost != null) {
                cachedHost.parameterTypes[0]
            } else {
                dexKit.findMethod {
                    matcher {
                        name("writeToParcel")
                        usingStrings(listOf("SEARCH"), StringMatchType.Equals)
                        paramCount(2)
                    }
                }.mapNotNull { runCatching { it.getClassInstance(classLoader) }.getOrNull() }
                    .firstOrNull { isSettingsPageArgsClass(it) }
            }
        }.getOrNull()

        val pageHostCtor = cachedConstructor(cached, KEY_SETTINGS_PAGE_HOST_CTOR, classLoader) { ctor ->
            isSettingsPageHostCtor(ctor, argsClass)
        } ?: if (argsClass != null) {
            runCatching {
                dexKit.findClass {
                    matcher {
                        addFieldForType(argsClass)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull { isSettingsPageHostCtor(it, argsClass) }
            }.getOrNull()
        } else null
        storeCtor(KEY_SETTINGS_PAGE_HOST_CTOR, pageHostCtor, sigs, members)

        val baseTitleClass = modelCtor?.parameterTypes?.getOrNull(1)?.superclass
        val stringTitleCtor = cachedConstructor(cached, KEY_SETTINGS_STRING_TITLE_CTOR, classLoader) { ctor ->
            isStringTitleCtor(ctor, baseTitleClass)
        } ?: if (baseTitleClass != null) {
            runCatching {
                dexKit.findClass {
                    matcher {
                        superClass(baseTitleClass.name)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull { isStringTitleCtor(it, baseTitleClass) }
            }.getOrNull()
        } else null
        storeCtor(KEY_SETTINGS_STRING_TITLE_CTOR, stringTitleCtor, sigs, members)

        val headerPropCtor = cachedConstructor(cached, KEY_SETTINGS_HEADER_PROP_CTOR, classLoader) { ctor ->
            isHeaderPropCtor(ctor, baseTitleClass)
        } ?: if (pageHostCtor != null && baseTitleClass != null) {
            runCatching {
                val hostInitRecord = dexKit.findMethod {
                    matcher {
                        declaredClass(pageHostCtor.declaringClass)
                        name("<init>")
                        paramCount(4)
                    }
                }.firstOrNull()
                hostInitRecord?.invokes
                    ?.filter { it.isConstructor && it.paramCount == 1 }
                    ?.mapNotNull { runCatching { it.getConstructorInstance(classLoader) }.getOrNull() }
                    ?.firstOrNull { isHeaderPropCtor(it, baseTitleClass) }
            }.getOrNull()
        } else null
        storeCtor(KEY_SETTINGS_HEADER_PROP_CTOR, headerPropCtor, sigs, members)

        val sectionClass = sectionCtor?.declaringClass
        val titleResolver = cachedMethod(cached, KEY_SETTINGS_TITLE_RESOLVER, classLoader, ::isSettingsTitleResolver)
            ?: if (sectionClass != null) {
                runCatching {
                    val candidates = dexKit.findMethod {
                        matcher {
                            paramTypes(
                                android.content.Context::class.java,
                                Integer::class.java,
                                String::class.java
                            )
                            returnType(String::class.java)
                        }
                    }
                    val matched = candidates.firstOrNull { data ->
                        java.lang.reflect.Modifier.isStatic(data.modifiers) &&
                            data.callers.any { caller ->
                                caller.usingFields.any { it.field.declaredClassName == sectionClass.name }
                            }
                    } ?: candidates.singleOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    matched?.getMethodInstance(classLoader)
                }.getOrNull()?.takeIf(::isSettingsTitleResolver)
            } else null
        store(KEY_SETTINGS_TITLE_RESOLVER, titleResolver, sigs, members)

        val iconBaseType = modelCtor?.parameterTypes?.getOrNull(0)
        val iconLogoClass = cached?.get(KEY_SETTINGS_ICON_LOGO_CLASS)?.let { clsName ->
            runCatching { Class.forName(clsName, false, classLoader) }.getOrNull()
                ?.takeIf { isIconSubclass(it, iconBaseType) }
        } ?: if (iconBaseType != null) {
            runCatching {
                dexKit.findClass {
                    matcher {
                        superClass(iconBaseType.name)
                        usingStrings(listOf("Encore.Vector.SpotifyLogo24"), StringMatchType.Equals)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .firstOrNull { isIconSubclass(it, iconBaseType) }
            }.getOrNull()
        } else null
        storeClass(KEY_SETTINGS_ICON_LOGO_CLASS, iconLogoClass, sigs, members)

        val vectorBuilderClass = cached?.get(KEY_SETTINGS_VECTOR_BUILDER_CLASS)?.let { clsName ->
            runCatching { Class.forName(clsName, false, classLoader) }.getOrNull()
                ?.takeIf(::isVectorBuilderClass)
        } ?: runCatching {
            dexKit.findClass {
                matcher {
                    usingStrings(
                        listOf("ImageVector.Builder is single use, create a new instance to create a new ImageVector"),
                        StringMatchType.Equals
                    )
                }
            }.asSequence()
                .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                .firstOrNull(::isVectorBuilderClass)
        }.getOrNull()
        storeClass(KEY_SETTINGS_VECTOR_BUILDER_CLASS, vectorBuilderClass, sigs, members)

        val vectorPathCtor = cachedConstructor(cached, KEY_SETTINGS_VECTOR_PATH_CTOR, classLoader, ::isVectorPathCtor)
            ?: if (vectorBuilderClass != null) {
                runCatching {
                    val addMethod = vectorBuilderClass.declaredMethods.firstOrNull { m ->
                        java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                            m.parameterCount == 6 &&
                            m.parameterTypes[0] == vectorBuilderClass &&
                            List::class.java.isAssignableFrom(m.parameterTypes[1])
                    } ?: return@runCatching null
                    dexKit.findMethod {
                        matcher {
                            declaredClass(vectorBuilderClass)
                            name(addMethod.name)
                            paramCount(6)
                        }
                    }.flatMap { it.invokes }
                        .filter { it.isConstructor && it.paramCount == 14 }
                        .mapNotNull { runCatching { it.getConstructorInstance(classLoader) }.getOrNull() }
                        .firstOrNull(::isVectorPathCtor)
                }.getOrNull()
            } else null
        storeCtor(KEY_SETTINGS_VECTOR_PATH_CTOR, vectorPathCtor, sigs, members)

        val moveToCtor = cachedConstructor(cached, KEY_SETTINGS_PATH_MOVETO_CTOR, classLoader, ::isMoveToCtor)
            ?: runCatching {
                dexKit.findClass {
                    matcher {
                        usingStrings(listOf("MoveTo(x="), StringMatchType.Equals)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull(::isMoveToCtor)
            }.getOrNull()
        storeCtor(KEY_SETTINGS_PATH_MOVETO_CTOR, moveToCtor, sigs, members)

        val curveToCtor = cachedConstructor(cached, KEY_SETTINGS_PATH_CURVETO_CTOR, classLoader, ::isCurveToCtor)
            ?: runCatching {
                dexKit.findClass {
                    matcher {
                        usingStrings(listOf("CurveTo(x1="), StringMatchType.Equals)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .flatMap { it.declaredConstructors.asSequence() }
                    .firstOrNull(::isCurveToCtor)
            }.getOrNull()
        storeCtor(KEY_SETTINGS_PATH_CURVETO_CTOR, curveToCtor, sigs, members)

        val pathNodeBase = moveToCtor?.declaringClass?.superclass
        val pathCloseClass = cached?.get(KEY_SETTINGS_PATH_CLOSE_CLASS)?.let { clsName ->
            runCatching { Class.forName(clsName, false, classLoader) }.getOrNull()
                ?.takeIf { isPathCloseClass(it, pathNodeBase) }
        } ?: if (pathNodeBase != null) {
            runCatching {
                dexKit.findClass {
                    matcher {
                        superClass(pathNodeBase.name)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getInstance(classLoader) }.getOrNull() }
                    .firstOrNull { isPathCloseClass(it, pathNodeBase) }
            }.getOrNull()
        } else null
        storeClass(KEY_SETTINGS_PATH_CLOSE_CLASS, pathCloseClass, sigs, members)

        val rowParcelClass = cached?.get(KEY_SETTINGS_ROW_PARCEL_CLASS)?.let { clsName ->
            runCatching { Class.forName(clsName, false, classLoader) }.getOrNull()
                ?.takeIf { isSettingsRowParcelClass(it, iconBaseType) }
        } ?: if (iconBaseType != null) {
            runCatching {
                dexKit.findMethod {
                    matcher {
                        name("<init>")
                        paramTypes(String::class.java, String::class.java, iconBaseType)
                    }
                }.asSequence()
                    .mapNotNull { runCatching { it.getConstructorInstance(classLoader).declaringClass }.getOrNull() }
                    .firstOrNull { isSettingsRowParcelClass(it, iconBaseType) }
            }.getOrNull()
        } else null
        storeClass(KEY_SETTINGS_ROW_PARCEL_CLASS, rowParcelClass, sigs, members)
    }

    private fun storeClass(
        key: String,
        cls: Class<*>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        if (cls == null) {
            Log.w(TAG, "$key: no validated match")
            return
        }
        members[key] = cls
        sigs[key] = cls.name
    }

    private fun storeCtor(
        key: String,
        ctor: Constructor<*>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        if (ctor == null) {
            Log.w(TAG, "$key: no validated match")
            return
        }
        members[key] = ctor
        sigs[key] = signatureOf(ctor)
    }

    private fun isSettingsModelCtor(ctor: Constructor<*>): Boolean {
        val p = ctor.parameterTypes
        return ctor.parameterCount == 5 &&
            !p[0].isPrimitive &&
            !p[1].isInterface &&
            !p[1].isPrimitive &&
            List::class.java.isAssignableFrom(p[3]) &&
            List::class.java.isAssignableFrom(p[4])
    }

    private fun isSettingsSectionCtor(ctor: Constructor<*>): Boolean {
        val p = ctor.parameterTypes
        return ctor.parameterCount == 6 &&
            !p[0].isPrimitive &&
            p[1] == Integer::class.java &&
            p[2] == Integer::class.java &&
            p[3].isInterface &&
            p[4].isInterface &&
            p[5] == Int::class.javaPrimitiveType
    }

    private fun isPersistentListFactory(method: Method, itemsListClass: Class<*>?): Boolean =
        itemsListClass != null &&
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Array<Any>::class.java &&
            itemsListClass.isAssignableFrom(method.returnType)

    private fun isSettingsToggleItemCtor(ctor: Constructor<*>): Boolean {
        val p = ctor.parameterTypes
        return ctor.parameterCount == 13 &&
            p[0] == String::class.java &&
            p[1] == Integer::class.java &&
            p[2] == String::class.java &&
            p[3] == Integer::class.java &&
            p[5] == Integer::class.java &&
            p[7].isInterface &&
            p[8].isInterface &&
            p[10].isInterface &&
            p[11] == Map::class.java &&
            p[12] == Int::class.javaPrimitiveType
    }

    private fun isSubtitleTextCtor(ctor: Constructor<*>): Boolean =
        ctor.parameterCount == 1 &&
            ctor.parameterTypes[0] == String::class.java &&
            CharSequence::class.java.isAssignableFrom(ctor.declaringClass)

    private fun isToggleControlCtor(ctor: Constructor<*>, controlInterface: Class<*>?): Boolean {
        val p = ctor.parameterTypes
        return controlInterface != null &&
            controlInterface.isAssignableFrom(ctor.declaringClass) &&
            ctor.parameterCount == 2 &&
            p[0].isInterface &&
            p[1].isInterface
    }

    private fun isZeroFieldMarkerImplClass(cls: Class<*>, targetInterface: Class<*>?): Boolean {
        if (targetInterface == null || !targetInterface.isAssignableFrom(cls)) return false
        if (cls.interfaces.size != 2) return false
        if (cls.declaredFields.any { !java.lang.reflect.Modifier.isStatic(it.modifiers) }) return false
        val marker = cls.interfaces.first { it != targetInterface }
        return marker.methods.none { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
    }

    private fun isNoLogToggleIdCtor(
        ctor: Constructor<*>,
        toggleIdInterface: Class<*>?,
        noLogMarker: Class<*>?
    ): Boolean =
        toggleIdInterface != null &&
            noLogMarker != null &&
            toggleIdInterface.isAssignableFrom(ctor.declaringClass) &&
            noLogMarker.isAssignableFrom(ctor.declaringClass) &&
            ctor.parameterCount == 1 &&
            !ctor.parameterTypes[0].isPrimitive

    private fun extractSingletonField(cls: Class<*>, baseType: Class<*>?): Any? {
        if (baseType == null || !baseType.isAssignableFrom(cls)) return null
        val enumVal = cls.enumConstants?.firstOrNull()
        if (enumVal != null) return enumVal
        return cls.declaredFields.firstOrNull { f ->
            java.lang.reflect.Modifier.isStatic(f.modifiers) && cls.isAssignableFrom(f.type)
        }?.apply { isAccessible = true }?.get(null)
    }

    private fun isReactiveToggleCtor(
        ctor: Constructor<*>,
        toggleStateFactoryInterface: Class<*>?,
        flowInterface: Class<*>?
    ): Boolean {
        if (toggleStateFactoryInterface == null || flowInterface == null) return false
        if (!toggleStateFactoryInterface.isAssignableFrom(ctor.declaringClass)) return false
        if (ctor.parameterCount != 2) return false
        val p = ctor.parameterTypes
        if (p[0] != flowInterface || !p[1].isInterface) return false
        return p[1].methods.any { m ->
            !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 3 &&
                m.parameterTypes.all { it == Any::class.java } &&
                m.returnType == Any::class.java
        }
    }

    private fun isSuccessResultCtor(ctor: Constructor<*>): Boolean {
        val cls = ctor.declaringClass
        if (ctor.parameterCount != 1 || ctor.parameterTypes[0] != Any::class.java) return false
        if (cls.interfaces.size != 1) return false
        return cls.interfaces[0].methods.any {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == Any::class.java
        }
    }

    private fun isStateFlowCtor(ctor: Constructor<*>): Boolean =
        ctor.parameterCount == 1 && ctor.parameterTypes[0] == Any::class.java

    private fun isStateFlowEmit(method: Method, flowClass: Class<*>?): Boolean =
        flowClass != null &&
            method.declaringClass == flowClass &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.name != "equals" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Any::class.java &&
            method.returnType == Boolean::class.javaPrimitiveType

    private fun isActionUriCtor(ctor: Constructor<*>): Boolean =
        ctor.parameterCount == 2 &&
            ctor.parameterTypes[0] == String::class.java &&
            ctor.parameterTypes[1] == android.os.Bundle::class.java &&
            ctor.declaringClass.interfaces.size == 1

    private fun isExternalUrlCtor(ctor: Constructor<*>): Boolean =
        ctor.parameterCount == 2 &&
            ctor.parameterTypes[0] == String::class.java &&
            ctor.parameterTypes[1] == java.lang.Boolean.TYPE &&
            ctor.declaringClass.interfaces.size == 1

    private fun isActionControlCtor(
        ctor: Constructor<*>,
        controlInterface: Class<*>?,
        actionTargetInterface: Class<*>?
    ): Boolean =
        controlInterface != null &&
            actionTargetInterface != null &&
            controlInterface.isAssignableFrom(ctor.declaringClass) &&
            ctor.parameterCount == 2 &&
            ctor.parameterTypes[0].isInterface &&
            ctor.parameterTypes[1] == actionTargetInterface

    private fun isRouterNavigateMethod(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 3 &&
            method.parameterTypes[0] == String::class.java &&
            !method.parameterTypes[1].isPrimitive &&
            android.os.Parcelable::class.java.isAssignableFrom(method.parameterTypes[1]) &&
            method.parameterTypes[2] == android.os.Bundle::class.java &&
            method.returnType == Void.TYPE

    private fun isSettingsPageArgsClass(cls: Class<*>): Boolean {
        if (!android.os.Parcelable::class.java.isAssignableFrom(cls)) return false
        val fields = cls.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        return fields.size == 4 &&
            fields.any { !it.type.isPrimitive && java.io.Serializable::class.java.isAssignableFrom(it.type) && Comparable::class.java.isAssignableFrom(it.type) } &&
            fields.any { android.os.Parcelable::class.java.isAssignableFrom(it.type) } &&
            fields.any { it.type == String::class.java } &&
            fields.any { it.type == Int::class.javaPrimitiveType }
    }

    private fun isSettingsPageHostCtor(ctor: Constructor<*>, argsClass: Class<*>?): Boolean {
        val p = ctor.parameterTypes
        return ctor.parameterCount == 4 &&
            (argsClass == null || p[0] == argsClass) &&
            p[1].isInterface &&
            !java.lang.reflect.Modifier.isAbstract(ctor.declaringClass.modifiers)
    }

    private fun isStringTitleCtor(ctor: Constructor<*>, baseTitleClass: Class<*>?): Boolean =
        baseTitleClass != null &&
            ctor.declaringClass != baseTitleClass &&
            baseTitleClass.isAssignableFrom(ctor.declaringClass) &&
            ctor.parameterCount == 1 &&
            ctor.parameterTypes[0] == String::class.java

    private fun isHeaderPropCtor(ctor: Constructor<*>, baseTitleClass: Class<*>?): Boolean =
        baseTitleClass != null &&
            ctor.parameterCount == 1 &&
            ctor.parameterTypes[0] == baseTitleClass &&
            ctor.declaringClass.interfaces.size == 1

    private fun isSettingsTitleResolver(method: Method): Boolean =
        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 3 &&
            method.parameterTypes[0] == android.content.Context::class.java &&
            method.parameterTypes[1] == Integer::class.java &&
            method.parameterTypes[2] == String::class.java &&
            method.returnType == String::class.java

    private fun isIconSubclass(cls: Class<*>, iconBaseType: Class<*>?): Boolean =
        iconBaseType != null &&
            cls != iconBaseType &&
            iconBaseType.isAssignableFrom(cls) &&
            cls.declaredFields.any { f ->
                java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == cls
            }

    private fun isVectorBuilderClass(cls: Class<*>): Boolean =
        cls.declaredConstructors.any { c ->
            val p = c.parameterTypes
            p.size == 9 &&
                p[0] == String::class.java &&
                p[1] == Float::class.javaPrimitiveType &&
                p[5] == Long::class.javaPrimitiveType &&
                p[6] == Int::class.javaPrimitiveType &&
                p[7] == Boolean::class.javaPrimitiveType &&
                p[8] == Int::class.javaPrimitiveType
        }

    private fun isVectorPathCtor(ctor: Constructor<*>): Boolean {
        val p = ctor.parameterTypes
        return p.size == 14 &&
            p[0] == String::class.java &&
            List::class.java.isAssignableFrom(p[1]) &&
            p[2] == Int::class.javaPrimitiveType &&
            p[3] == p[5] &&
            !p[3].isPrimitive &&
            p[4] == Float::class.javaPrimitiveType &&
            p[7] == Float::class.javaPrimitiveType &&
            p[8] == Int::class.javaPrimitiveType &&
            p[9] == Int::class.javaPrimitiveType &&
            p[10] == Float::class.javaPrimitiveType
    }

    private fun isMoveToCtor(ctor: Constructor<*>): Boolean =
        ctor.parameterCount == 2 &&
            ctor.parameterTypes[0] == Float::class.javaPrimitiveType &&
            ctor.parameterTypes[1] == Float::class.javaPrimitiveType

    private fun isCurveToCtor(ctor: Constructor<*>): Boolean =
        ctor.parameterCount == 6 &&
            ctor.parameterTypes.all { it == Float::class.javaPrimitiveType }

    private fun isPathCloseClass(cls: Class<*>, baseClass: Class<*>?): Boolean =
        baseClass != null &&
            cls.superclass == baseClass &&
            cls.declaredFields.none { !java.lang.reflect.Modifier.isStatic(it.modifiers) } &&
            cls.declaredFields.any { f ->
                java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == cls
            }

    private fun isSettingsRowParcelClass(cls: Class<*>, iconBaseType: Class<*>?): Boolean {
        if (iconBaseType == null || !android.os.Parcelable::class.java.isAssignableFrom(cls)) return false
        val hasCtor = cls.declaredConstructors.any { c ->
            val p = c.parameterTypes
            p.size == 3 &&
                p[0] == String::class.java &&
                p[1] == String::class.java &&
                p[2] == iconBaseType
        }
        if (!hasCtor) return false
        val instanceFields = cls.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        return instanceFields.size == 3 &&
            instanceFields.count { it.type == String::class.java } == 2 &&
            instanceFields.count { it.type == iconBaseType } == 1
    }

    private fun resolveAppSignaturesMethod(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val method = cachedMethod(cached, KEY_APP_SIGNATURES_METHOD, classLoader, ::isAppSignaturesMethod)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("Failed to get the application signatures"), StringMatchType.Equals)
                        paramCount(0)
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isAppSignaturesMethod)
        store(KEY_APP_SIGNATURES_METHOD, method, sigs, members)
    }

    private fun isAppSignaturesMethod(method: Method): Boolean =
        !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0

    private fun resolveInstallerPackageMethod(
        dexKit: DexKitBridge,
        classLoader: ClassLoader,
        cached: Map<String, String>?,
        sigs: MutableMap<String, String>,
        members: MutableMap<String, Any>
    ) {
        val allowlistMethod = cachedMethod(cached, KEY_INSTALLER_ALLOWLIST_METHOD, classLoader, ::isInstallerAllowlistMethod)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("com.android.vending", "com.amazon.venezia"), StringMatchType.Contains)
                        paramCount(1)
                        returnType("boolean")
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isInstallerAllowlistMethod)
        store(KEY_INSTALLER_ALLOWLIST_METHOD, allowlistMethod, sigs, members)

        val declaringClass = allowlistMethod?.declaringClass
        val nameMethod = cachedMethod(cached, KEY_INSTALLER_PACKAGE_METHOD, classLoader, ::isInstallerPackageMethod)
            ?: declaringClass?.declaredMethods?.firstOrNull(::isInstallerPackageMethod)
            ?: runCatching {
                dexKit.findMethod {
                    matcher {
                        usingStrings(listOf("Failed to get installer package"), StringMatchType.Equals)
                        paramCount(2)
                        returnType("java.lang.String")
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()?.takeIf(::isInstallerPackageMethod)
        store(KEY_INSTALLER_PACKAGE_METHOD, nameMethod, sigs, members)
    }

    private fun isInstallerAllowlistMethod(method: Method): Boolean =
        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            method.parameterCount == 1

    private fun isInstallerPackageMethod(method: Method): Boolean =
        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.returnType == String::class.java &&
            (method.parameterCount == 1 || method.parameterCount == 2)

}
