package app.spotxero.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.spotxero.SpotXeroApplication
import java.util.concurrent.ConcurrentHashMap

object PreferencesManager {

    private const val TAG = "SpotXero.Prefs"
    private const val TARGET_PREFS_NAME = "spotxero_target_prefs"

    const val KEY_APP_THEME_MODE = "app_theme_mode"
    const val KEY_APP_DYNAMIC_COLOR = "app_dynamic_color"
    const val KEY_APP_AMOLED_THEME = "app_amoled_theme"
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"

    enum class ThemeMode {
        SYSTEM, LIGHT, DARK
    }

    var appThemeMode: ThemeMode = ThemeMode.SYSTEM
    var isDynamicColorEnabled: Boolean = false
    var isAppAmoledEnabled: Boolean = false
    var isLauncherIconHidden: Boolean = false
    var isMasterEnabled: Boolean = true
    var isPremiumEnabled: Boolean = true
    var isLyricsEnabled: Boolean = true
    var isExternalLyricsEnabled: Boolean = false
    var isForceExternalLyricsEnabled: Boolean = false
    var isLyricsTranslationsEnabled: Boolean = true
    var isAiLyricsTranslationEnabled: Boolean = false
    var aiTranslationProvider: String = "OpenRouter"
    var aiTranslationApiKey: String = ""
    var aiTranslationBaseUrl: String = PrefKeys.OpenRouterDefaultBaseUrl
    var aiTranslationModel: String = PrefKeys.OpenRouterDefaultModel
    var aiTranslationTargetLang: String = "en"
    var aiTranslationCustomPrompt: String = ""
    var isAiTranslationDualLine: Boolean = true
    var aiTranslationTranslateMode: String = "Literal"
    var aiTranslationDeeplApiKey: String = ""
    var aiTranslationDeeplFormality: String = "default"
    var lyricsProvidersOrder: String = PrefKeys.DEFAULT_LYRICS_PROVIDERS_ORDER
    var isSpicylyricsEnabled: Boolean = true
    var isBetterlyricsEnabled: Boolean = true
    var isLrcredEnabled: Boolean = true
    var isLrclibEnabled: Boolean = true
    var isKugouEnabled: Boolean = true
    var isNeteaseEnabled: Boolean = true
    var isGeniusEnabled: Boolean = true
    var isForceLocalPlaybackEnabled: Boolean = true
    var isCleanSharingEnabled: Boolean = true
    var enableHapticFeedback: Boolean = true
    var isSpotifyAmoledEnabled: Boolean = false
    var isSpotifyMonetEnabled: Boolean = false
    var isSpotifyRoundyUiEnabled: Boolean = false
    var isHidePodcastsEnabled: Boolean = false
    var isHideAudiobooksEnabled: Boolean = false
    var isHidePremiumTabEnabled: Boolean = true
    var isDoubleTapToSeekEnabled: Boolean = true
    var seekDurationSeconds: Int = 10
    var isSeekProgressive: Boolean = false

    var onPreferenceChangedListener: (() -> Unit)? = null
    private val preferenceChangeCallbacks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnPreferenceChangedListener(listener: () -> Unit) {
        preferenceChangeCallbacks.add(listener)
    }

    fun removeOnPreferenceChangedListener(listener: () -> Unit) {
        preferenceChangeCallbacks.remove(listener)
    }

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    @Volatile
    private var localPrefs: SharedPreferences? = null

    @Volatile
    private var targetPrefs: SharedPreferences? = null

    private val inMemoryBooleans = ConcurrentHashMap<String, Boolean>()
    private val inMemoryStrings = ConcurrentHashMap<String, String>()
    private val inMemoryInts = ConcurrentHashMap<String, Int>()

    @Volatile
    var targetContext: Context? = null
        private set

    @Volatile
    private var cachedUserLoggedIn: Boolean? = null
    @Volatile
    private var lastUserLoggedInCheckMs: Long = 0L

    fun resolveTargetContext(): Context? {
        targetContext?.let { return it }
        val resolved = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null) as? Context
            if (currentApp != null) return@runCatching currentApp
            val at = atClass.getMethod("currentActivityThread").invoke(null)
            atClass.getMethod("getApplication").invoke(at) as? Context
        }.getOrNull()
        if (resolved != null) {
            targetContext = resolved
        }
        return resolved
    }

    fun initTarget(context: Context) {
        val target = context.applicationContext ?: context
        targetContext = target
        runCatching {
            val tp = target.getSharedPreferences(TARGET_PREFS_NAME, Context.MODE_PRIVATE)
            targetPrefs = tp
            for (key in tp.all.keys) {
                updateMemoryCache(tp, key)
            }
        }
    }

    private fun checkUserLoggedInInternal(): Boolean {
        val ctx = targetContext ?: resolveTargetContext() ?: return true
        return runCatching {
            val prefs = ctx.getSharedPreferences("spotify_preferences", Context.MODE_PRIVATE)
            val npvLoggedIn = prefs.getBoolean("com.spotify.proactiveplatforms.npvwidget.KEY_PREFS_LOGGED_IN", false)
            val notLoggedOut = !prefs.getBoolean("last_known_is_logged_out_status", true)
            val sessionStage = prefs.getString("deeplink.session.stage", null) == "LOGGED_IN"
            val signedInMilestone = prefs.getString("crashlytics_persistent_usage_milestone", null) == "signed_in"
            val hasEventOwner = !prefs.getString("event-sender-event-owner", null).isNullOrEmpty()

            npvLoggedIn || notLoggedOut || sessionStage || signedInMilestone || hasEventOwner
        }.getOrDefault(true)
    }

    fun isUserLoggedIn(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val cached = cachedUserLoggedIn
        if (cached != null && (now - lastUserLoggedInCheckMs) < 1000L) {
            return cached
        }
        val result = checkUserLoggedInInternal()
        cachedUserLoggedIn = result
        lastUserLoggedInCheckMs = now
        return result
    }

    private fun getTargetPrefs(): SharedPreferences? {
        val tp = targetPrefs
        if (tp != null) return tp
        val ctx = resolveTargetContext() ?: return null
        return runCatching {
            val target = ctx.getSharedPreferences(TARGET_PREFS_NAME, Context.MODE_PRIVATE)
            targetPrefs = target
            for (key in target.all.keys) {
                updateMemoryCache(target, key)
            }
            target
        }.getOrNull()
    }

    private val SECRET_KEYS = setOf(
        PrefKeys.KEY_AI_TRANSLATION_API_KEY,
        PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY
    )

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key != null && key != "pref_force_stop") {
            if (!updateMemoryCache(prefs, key)) return@OnSharedPreferenceChangeListener
            val lp = localPrefs
            if (lp != null && prefs != lp) {
                if (prefs.contains(key)) {
                    val all = prefs.all
                    when (val value = all[key]) {
                        is Boolean -> lp.edit().putBoolean(key, value).apply()
                        is String -> lp.edit().putString(key, value).apply()
                        is Int -> lp.edit().putInt(key, value).apply()
                        is Long -> lp.edit().putLong(key, value).apply()
                    }
                }
            }
            val tp = targetPrefs
            if (tp != null && prefs == remotePrefs && key !in SECRET_KEYS && prefs.contains(key)) {
                when (val value = prefs.all[key]) {
                    is Boolean -> tp.edit().putBoolean(key, value).apply()
                    is String -> tp.edit().putString(key, value).apply()
                    is Int -> tp.edit().putInt(key, value).apply()
                }
            }
            onPreferenceChangedListener?.invoke()
            for (listener in preferenceChangeCallbacks) {
                runCatching { listener.invoke() }
            }
        }
    }

    fun initLocal(context: Context) {
        if (localPrefs != null) return
        val targetContext = context.applicationContext ?: context
        try {
            val lp = targetContext.getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE)
            localPrefs = lp
            lp.registerOnSharedPreferenceChangeListener(changeListener)
            loadAllFromPrefs(lp)
            Log.i(TAG, "Initialized local SharedPreferences in ${targetContext.packageName}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed initializing local SharedPreferences: ${t.message}")
        }
    }

    fun init(context: Context) {
        initLocal(context)
        loadAll(context)
    }

    fun bindRemote(prefs: SharedPreferences) {
        remotePrefs?.unregisterOnSharedPreferenceChangeListener(changeListener)
        remotePrefs = prefs
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
        loadAllFromPrefs(prefs)
        Log.i(TAG, "Bound LibXposed Remote Preferences with live listener")
    }

    fun cleanup() {
        runCatching {
            remotePrefs?.unregisterOnSharedPreferenceChangeListener(changeListener)
        }
        runCatching {
            localPrefs?.unregisterOnSharedPreferenceChangeListener(changeListener)
        }
        remotePrefs = null
        localPrefs = null
        targetPrefs = null
        targetContext = null
        cachedUserLoggedIn = null
        lastUserLoggedInCheckMs = 0L
        onPreferenceChangedListener = null
        preferenceChangeCallbacks.clear()
        inMemoryBooleans.clear()
        inMemoryStrings.clear()
        inMemoryInts.clear()
        Log.i(TAG, "Cleaned up PreferencesManager resources")
    }

    fun getPrefs(context: Context? = null): SharedPreferences {
        if (context != null && localPrefs == null) {
            initLocal(context)
        }

        if (remotePrefs == null) {
            val service = SpotXeroApplication.service
            if (service != null) {
                try {
                    val rp = service.getRemotePreferences(PrefKeys.PREFS_NAME)
                    bindRemote(rp)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed obtaining service remote preferences: ${t.message}")
                }
            }
        }

        remotePrefs?.let { return it }
        localPrefs?.let { return it }

        if (context != null) {
            return context.getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE)
        }

        throw IllegalStateException("No SharedPreferences source available")
    }

    fun bindServiceRemote(context: Context) {
        val service = SpotXeroApplication.service ?: return
        try {
            val rp = service.getRemotePreferences(PrefKeys.PREFS_NAME)
            val lp = localPrefs ?: context.applicationContext
                ?.getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE)
            if (lp != null) {
                localPrefs = lp
                if (lp.contains("pref_force_stop")) {
                    lp.edit().remove("pref_force_stop").apply()
                }
                if (rp.contains("pref_force_stop")) {
                    rp.edit().remove("pref_force_stop").apply()
                }
                if (rp.all.isEmpty()) {
                    val remoteEditor = rp.edit()
                    for ((k, v) in lp.all) {
                        if (k == "pref_force_stop") continue
                        when (v) {
                            is Boolean -> remoteEditor.putBoolean(k, v)
                            is String -> remoteEditor.putString(k, v)
                            is Int -> remoteEditor.putInt(k, v)
                            is Long -> remoteEditor.putLong(k, v)
                        }
                    }
                    remoteEditor.apply()
                }
            }
            bindRemote(rp)
            if (lp != null) {
                val editor = lp.edit()
                for ((k, v) in inMemoryBooleans) editor.putBoolean(k, v)
                for ((k, v) in inMemoryStrings) editor.putString(k, v)
                editor.apply()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed binding service remote preferences: ${t.message}")
        }
    }

    fun loadAll(context: Context) {
        val prefs = getPrefs(context)
        loadAllFromPrefs(prefs)
    }

    private fun loadAllFromPrefs(prefs: SharedPreferences) {
        appThemeMode = parseThemeMode(prefs.getString(KEY_APP_THEME_MODE, null))
        isDynamicColorEnabled = prefs.getBoolean(KEY_APP_DYNAMIC_COLOR, false)
        isAppAmoledEnabled = prefs.getBoolean(KEY_APP_AMOLED_THEME, false)
        isMasterEnabled = prefs.getBoolean(PrefKeys.KEY_MASTER_ENABLED, true)
        inMemoryBooleans[PrefKeys.KEY_MASTER_ENABLED] = isMasterEnabled
        isPremiumEnabled = prefs.getBoolean(PrefKeys.KEY_ENABLE_PREMIUM, true)
        inMemoryBooleans[PrefKeys.KEY_ENABLE_PREMIUM] = isPremiumEnabled
        isLyricsEnabled = prefs.getBoolean(PrefKeys.KEY_UNCAPPED_LYRICS, true)
        inMemoryBooleans[PrefKeys.KEY_UNCAPPED_LYRICS] = isLyricsEnabled
        isExternalLyricsEnabled = prefs.getBoolean(PrefKeys.KEY_EXTERNAL_LYRICS, false)
        inMemoryBooleans[PrefKeys.KEY_EXTERNAL_LYRICS] = isExternalLyricsEnabled
        isForceExternalLyricsEnabled = prefs.getBoolean(PrefKeys.KEY_FORCE_EXTERNAL_LYRICS, false)
        inMemoryBooleans[PrefKeys.KEY_FORCE_EXTERNAL_LYRICS] = isForceExternalLyricsEnabled
        isLyricsTranslationsEnabled = prefs.getBoolean(PrefKeys.KEY_LYRICS_TRANSLATIONS, true)
        inMemoryBooleans[PrefKeys.KEY_LYRICS_TRANSLATIONS] = isLyricsTranslationsEnabled
        isAiLyricsTranslationEnabled = prefs.getBoolean(PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED, false)
        inMemoryBooleans[PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED] = isAiLyricsTranslationEnabled
        aiTranslationProvider = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_PROVIDER, "OpenRouter") ?: "OpenRouter"
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_PROVIDER] = aiTranslationProvider
        aiTranslationApiKey = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_API_KEY, "") ?: ""
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_API_KEY] = aiTranslationApiKey
        aiTranslationBaseUrl = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_BASE_URL, PrefKeys.OpenRouterDefaultBaseUrl) ?: PrefKeys.OpenRouterDefaultBaseUrl
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_BASE_URL] = aiTranslationBaseUrl
        aiTranslationModel = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_MODEL, PrefKeys.OpenRouterDefaultModel) ?: PrefKeys.OpenRouterDefaultModel
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_MODEL] = aiTranslationModel
        aiTranslationTargetLang = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_TARGET_LANG, "en") ?: "en"
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_TARGET_LANG] = aiTranslationTargetLang
        aiTranslationCustomPrompt = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_CUSTOM_PROMPT, "") ?: ""
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_CUSTOM_PROMPT] = aiTranslationCustomPrompt
        isAiTranslationDualLine = prefs.getBoolean(PrefKeys.KEY_AI_TRANSLATION_DUAL_LINE, true)
        inMemoryBooleans[PrefKeys.KEY_AI_TRANSLATION_DUAL_LINE] = isAiTranslationDualLine
        aiTranslationTranslateMode = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_TRANSLATE_MODE, "Literal") ?: "Literal"
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_TRANSLATE_MODE] = aiTranslationTranslateMode
        aiTranslationDeeplApiKey = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY, "") ?: ""
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY] = aiTranslationDeeplApiKey
        aiTranslationDeeplFormality = prefs.getString(PrefKeys.KEY_AI_TRANSLATION_DEEPL_FORMALITY, "default") ?: "default"
        inMemoryStrings[PrefKeys.KEY_AI_TRANSLATION_DEEPL_FORMALITY] = aiTranslationDeeplFormality
        lyricsProvidersOrder = prefs.getString(PrefKeys.KEY_LYRICS_PROVIDERS_ORDER, PrefKeys.DEFAULT_LYRICS_PROVIDERS_ORDER) ?: PrefKeys.DEFAULT_LYRICS_PROVIDERS_ORDER
        inMemoryStrings[PrefKeys.KEY_LYRICS_PROVIDERS_ORDER] = lyricsProvidersOrder
        isSpicylyricsEnabled = prefs.getBoolean(PrefKeys.KEY_LYRICS_PROVIDER_SPICYLYRICS, true)
        inMemoryBooleans[PrefKeys.KEY_LYRICS_PROVIDER_SPICYLYRICS] = isSpicylyricsEnabled
        isBetterlyricsEnabled = prefs.getBoolean(PrefKeys.KEY_LYRICS_PROVIDER_BETTERLYRICS, true)
        inMemoryBooleans[PrefKeys.KEY_LYRICS_PROVIDER_BETTERLYRICS] = isBetterlyricsEnabled
        isLrcredEnabled = prefs.getBoolean(PrefKeys.KEY_LYRICS_PROVIDER_LRCRED, true)
        inMemoryBooleans[PrefKeys.KEY_LYRICS_PROVIDER_LRCRED] = isLrcredEnabled
        isLrclibEnabled = prefs.getBoolean(PrefKeys.KEY_LYRICS_PROVIDER_LRCLIB, true)
        inMemoryBooleans[PrefKeys.KEY_LYRICS_PROVIDER_LRCLIB] = isLrclibEnabled
        isKugouEnabled = prefs.getBoolean(PrefKeys.KEY_LYRICS_PROVIDER_KUGOU, true)
        inMemoryBooleans[PrefKeys.KEY_LYRICS_PROVIDER_KUGOU] = isKugouEnabled
        isNeteaseEnabled = prefs.getBoolean(PrefKeys.KEY_LYRICS_PROVIDER_NETEASE, true)
        inMemoryBooleans[PrefKeys.KEY_LYRICS_PROVIDER_NETEASE] = isNeteaseEnabled
        isGeniusEnabled = prefs.getBoolean(PrefKeys.KEY_LYRICS_PROVIDER_GENIUS, true)
        inMemoryBooleans[PrefKeys.KEY_LYRICS_PROVIDER_GENIUS] = isGeniusEnabled
        isForceLocalPlaybackEnabled = prefs.getBoolean(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)
        inMemoryBooleans[PrefKeys.KEY_FORCE_LOCAL_PLAYBACK] = isForceLocalPlaybackEnabled
        isCleanSharingEnabled = prefs.getBoolean(PrefKeys.KEY_CLEAN_SHARING, true)
        inMemoryBooleans[PrefKeys.KEY_CLEAN_SHARING] = isCleanSharingEnabled
        enableHapticFeedback = prefs.getBoolean(PrefKeys.KEY_ENABLE_HAPTIC_FEEDBACK, true)
        inMemoryBooleans[PrefKeys.KEY_ENABLE_HAPTIC_FEEDBACK] = enableHapticFeedback
        isSpotifyAmoledEnabled = prefs.getBoolean(PrefKeys.KEY_SPOTIFY_AMOLED, false)
        inMemoryBooleans[PrefKeys.KEY_SPOTIFY_AMOLED] = isSpotifyAmoledEnabled
        isSpotifyMonetEnabled = prefs.getBoolean(PrefKeys.KEY_SPOTIFY_MONET, false)
        inMemoryBooleans[PrefKeys.KEY_SPOTIFY_MONET] = isSpotifyMonetEnabled
        isSpotifyRoundyUiEnabled = prefs.getBoolean(PrefKeys.KEY_SPOTIFY_ROUNDY_UI, false)
        inMemoryBooleans[PrefKeys.KEY_SPOTIFY_ROUNDY_UI] = isSpotifyRoundyUiEnabled
        isHidePodcastsEnabled = prefs.getBoolean(PrefKeys.KEY_HIDE_PODCASTS, false)
        inMemoryBooleans[PrefKeys.KEY_HIDE_PODCASTS] = isHidePodcastsEnabled
        isHideAudiobooksEnabled = prefs.getBoolean(PrefKeys.KEY_HIDE_AUDIOBOOKS, false)
        inMemoryBooleans[PrefKeys.KEY_HIDE_AUDIOBOOKS] = isHideAudiobooksEnabled
        isHidePremiumTabEnabled = prefs.getBoolean(PrefKeys.KEY_HIDE_PREMIUM_TAB, true)
        inMemoryBooleans[PrefKeys.KEY_HIDE_PREMIUM_TAB] = isHidePremiumTabEnabled
        isDoubleTapToSeekEnabled = prefs.getBoolean(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, true)
        inMemoryBooleans[PrefKeys.KEY_DOUBLE_TAP_TO_SEEK] = isDoubleTapToSeekEnabled
        seekDurationSeconds = prefs.getInt(PrefKeys.KEY_SEEK_DURATION_SECONDS, 10)
        inMemoryInts[PrefKeys.KEY_SEEK_DURATION_SECONDS] = seekDurationSeconds
        isSeekProgressive = prefs.getBoolean(PrefKeys.KEY_SEEK_PROGRESSIVE, false)
        inMemoryBooleans[PrefKeys.KEY_SEEK_PROGRESSIVE] = isSeekProgressive
        isLauncherIconHidden = prefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false)
        inMemoryBooleans[KEY_HIDE_LAUNCHER_ICON] = isLauncherIconHidden
        val tp = getTargetPrefs()
        if (tp != null) {
            for (key in tp.all.keys) {
                updateMemoryCache(tp, key)
            }
        } else if (prefs.contains(PrefKeys.KEY_SHUFFLE_STATE)) {
            inMemoryBooleans[PrefKeys.KEY_SHUFFLE_STATE] = prefs.getBoolean(PrefKeys.KEY_SHUFFLE_STATE, false)
        }
    }

    fun isEnabled(key: String, default: Boolean): Boolean {
        if (key != PrefKeys.KEY_MASTER_ENABLED &&
            key != PrefKeys.KEY_SHUFFLE_STATE &&
            key != KEY_APP_DYNAMIC_COLOR &&
            key != KEY_APP_AMOLED_THEME &&
            key != KEY_HIDE_LAUNCHER_ICON &&
            key != KEY_APP_THEME_MODE
        ) {
            val master = inMemoryBooleans[PrefKeys.KEY_MASTER_ENABLED] ?: isEnabledDirect(PrefKeys.KEY_MASTER_ENABLED, true)
            if (!master) return false
        }
        return isEnabledDirect(key, default)
    }

    fun isStoredEnabled(key: String, default: Boolean): Boolean = isEnabledDirect(key, default)

    private fun isEnabledDirect(key: String, default: Boolean): Boolean {
        val tp = getTargetPrefs()
        if (tp != null && tp.contains(key)) {
            val v = tp.getBoolean(key, default)
            inMemoryBooleans[key] = v
            return v
        }
        inMemoryBooleans[key]?.let { return it }
        val rp = remotePrefs
        if (rp != null && rp.contains(key)) {
            val v = rp.getBoolean(key, default)
            inMemoryBooleans[key] = v
            return v
        }
        val lp = localPrefs
        if (lp != null && lp.contains(key)) {
            val v = lp.getBoolean(key, default)
            inMemoryBooleans[key] = v
            return v
        }
        return default
    }

    fun setBoolean(context: Context? = null, key: String, value: Boolean) {
        inMemoryBooleans[key] = value
        updateFieldDirectly(key, value)
        val tp = getTargetPrefs()
        if (tp != null) {
            runCatching {
                tp.edit().putBoolean(key, value).commit()
            }
        }
        val rp = remotePrefs
        if (rp != null) {
            runCatching {
                rp.edit().putBoolean(key, value).apply()
            }
        }
        val prefs = runCatching { getPrefs(context) }.getOrNull()
        if (prefs != null && prefs != tp && prefs != rp) {
            runCatching {
                prefs.edit().putBoolean(key, value).apply()
            }
        }
    }

    fun getString(key: String, default: String): String {
        val tp = getTargetPrefs()
        if (tp != null && tp.contains(key)) {
            val v = tp.getString(key, default) ?: default
            inMemoryStrings[key] = v
            return v
        }
        inMemoryStrings[key]?.let { return it }
        val rp = remotePrefs
        if (rp != null && rp.contains(key)) {
            val v = rp.getString(key, default) ?: default
            inMemoryStrings[key] = v
            return v
        }
        val lp = localPrefs
        if (lp != null && lp.contains(key)) {
            val v = lp.getString(key, default) ?: default
            inMemoryStrings[key] = v
            return v
        }
        return default
    }

    fun setString(context: Context? = null, key: String, value: String) {
        inMemoryStrings[key] = value
        updateStringFieldDirectly(key, value)
        val tp = getTargetPrefs()
        val rp = remotePrefs
        if (tp != null && (key !in SECRET_KEYS || rp == null)) {
            runCatching {
                tp.edit().putString(key, value).commit()
            }
        }
        if (rp != null) {
            runCatching {
                rp.edit().putString(key, value).apply()
            }
        }
        val prefs = runCatching { getPrefs(context) }.getOrNull()
        if (prefs != null && prefs != tp && prefs != rp) {
            runCatching {
                prefs.edit().putString(key, value).apply()
            }
        }
    }

    fun getInt(key: String, default: Int): Int {
        val tp = getTargetPrefs()
        if (tp != null && tp.contains(key)) {
            val v = tp.getInt(key, default)
            inMemoryInts[key] = v
            return v
        }
        inMemoryInts[key]?.let { return it }
        val rp = remotePrefs
        if (rp != null && rp.contains(key)) {
            val v = rp.getInt(key, default)
            inMemoryInts[key] = v
            return v
        }
        val lp = localPrefs
        if (lp != null && lp.contains(key)) {
            val v = lp.getInt(key, default)
            inMemoryInts[key] = v
            return v
        }
        return default
    }

    fun setInt(context: Context? = null, key: String, value: Int) {
        inMemoryInts[key] = value
        updateIntFieldDirectly(key, value)
        val tp = getTargetPrefs()
        if (tp != null) {
            runCatching {
                tp.edit().putInt(key, value).commit()
            }
        }
        val rp = remotePrefs
        if (rp != null) {
            runCatching {
                rp.edit().putInt(key, value).apply()
            }
        }
        val prefs = runCatching { getPrefs(context) }.getOrNull()
        if (prefs != null && prefs != tp && prefs != rp) {
            runCatching {
                prefs.edit().putInt(key, value).apply()
            }
        }
    }

    fun updateIntFieldDirectly(key: String, value: Int) {
        when (key) {
            PrefKeys.KEY_SEEK_DURATION_SECONDS -> seekDurationSeconds = value
        }
    }

    fun updateStringFieldDirectly(key: String, value: String) {
        when (key) {
            PrefKeys.KEY_LYRICS_PROVIDERS_ORDER -> lyricsProvidersOrder = value
            KEY_APP_THEME_MODE -> appThemeMode = parseThemeMode(value)
            PrefKeys.KEY_AI_TRANSLATION_PROVIDER -> aiTranslationProvider = value
            PrefKeys.KEY_AI_TRANSLATION_API_KEY -> aiTranslationApiKey = value
            PrefKeys.KEY_AI_TRANSLATION_BASE_URL -> aiTranslationBaseUrl = value
            PrefKeys.KEY_AI_TRANSLATION_MODEL -> aiTranslationModel = value
            PrefKeys.KEY_AI_TRANSLATION_TARGET_LANG -> aiTranslationTargetLang = value
            PrefKeys.KEY_AI_TRANSLATION_CUSTOM_PROMPT -> aiTranslationCustomPrompt = value
            PrefKeys.KEY_AI_TRANSLATION_TRANSLATE_MODE -> aiTranslationTranslateMode = value
            PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY -> aiTranslationDeeplApiKey = value
            PrefKeys.KEY_AI_TRANSLATION_DEEPL_FORMALITY -> aiTranslationDeeplFormality = value
        }
    }

    fun updateFieldDirectly(key: String, value: Boolean) {
        when (key) {
            PrefKeys.KEY_MASTER_ENABLED -> isMasterEnabled = value
            PrefKeys.KEY_ENABLE_PREMIUM -> isPremiumEnabled = value
            PrefKeys.KEY_UNCAPPED_LYRICS -> isLyricsEnabled = value
            PrefKeys.KEY_EXTERNAL_LYRICS -> isExternalLyricsEnabled = value
            PrefKeys.KEY_FORCE_EXTERNAL_LYRICS -> isForceExternalLyricsEnabled = value
            PrefKeys.KEY_LYRICS_TRANSLATIONS -> isLyricsTranslationsEnabled = value
            PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED -> isAiLyricsTranslationEnabled = value
            PrefKeys.KEY_AI_TRANSLATION_DUAL_LINE -> isAiTranslationDualLine = value
            PrefKeys.KEY_LYRICS_PROVIDER_SPICYLYRICS -> isSpicylyricsEnabled = value
            PrefKeys.KEY_LYRICS_PROVIDER_BETTERLYRICS -> isBetterlyricsEnabled = value
            PrefKeys.KEY_LYRICS_PROVIDER_LRCRED -> isLrcredEnabled = value
            PrefKeys.KEY_LYRICS_PROVIDER_LRCLIB -> isLrclibEnabled = value
            PrefKeys.KEY_LYRICS_PROVIDER_KUGOU -> isKugouEnabled = value
            PrefKeys.KEY_LYRICS_PROVIDER_NETEASE -> isNeteaseEnabled = value
            PrefKeys.KEY_LYRICS_PROVIDER_GENIUS -> isGeniusEnabled = value
            PrefKeys.KEY_FORCE_LOCAL_PLAYBACK -> isForceLocalPlaybackEnabled = value
            PrefKeys.KEY_CLEAN_SHARING -> isCleanSharingEnabled = value
            PrefKeys.KEY_ENABLE_HAPTIC_FEEDBACK -> enableHapticFeedback = value
            PrefKeys.KEY_SPOTIFY_AMOLED -> isSpotifyAmoledEnabled = value
            PrefKeys.KEY_SPOTIFY_MONET -> isSpotifyMonetEnabled = value
            PrefKeys.KEY_SPOTIFY_ROUNDY_UI -> isSpotifyRoundyUiEnabled = value
            PrefKeys.KEY_HIDE_PODCASTS -> isHidePodcastsEnabled = value
            PrefKeys.KEY_HIDE_AUDIOBOOKS -> isHideAudiobooksEnabled = value
            PrefKeys.KEY_HIDE_PREMIUM_TAB -> isHidePremiumTabEnabled = value
            PrefKeys.KEY_DOUBLE_TAP_TO_SEEK -> isDoubleTapToSeekEnabled = value
            PrefKeys.KEY_SEEK_PROGRESSIVE -> isSeekProgressive = value
            KEY_APP_DYNAMIC_COLOR -> isDynamicColorEnabled = value
            KEY_APP_AMOLED_THEME -> isAppAmoledEnabled = value
            KEY_HIDE_LAUNCHER_ICON -> isLauncherIconHidden = value
        }
    }

    private fun updateMemoryCache(prefs: SharedPreferences, key: String): Boolean {
        if (!prefs.contains(key)) return false
        val value = prefs.all[key] ?: return false
        return when (value) {
            is Boolean -> (inMemoryBooleans.put(key, value) != value)
                .also { updateFieldDirectly(key, value) }
            is String -> (inMemoryStrings.put(key, value) != value)
                .also { updateStringFieldDirectly(key, value) }
            is Int -> (inMemoryInts.put(key, value) != value)
                .also { updateIntFieldDirectly(key, value) }
            else -> false
        }
    }

    private fun parseThemeMode(name: String?): ThemeMode = when (name?.uppercase()) {
        "LIGHT" -> ThemeMode.LIGHT
        "DARK" -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }
}
