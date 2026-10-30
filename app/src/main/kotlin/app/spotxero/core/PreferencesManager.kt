package app.spotxero.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.spotxero.SpotXeroApplication
import java.util.concurrent.ConcurrentHashMap

object PreferencesManager {

    private const val TAG = "SpotXero.Prefs"
    const val PREFS_NAME = "spotify_prefs"

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
    var isPremiumEnabled: Boolean = true
    var isCleanSharingEnabled: Boolean = true
    var isSpotifyAmoledEnabled: Boolean = false
    var isHidePremiumTabEnabled: Boolean = false
    var isBlockAdsEnabled: Boolean = true
    var isHideBrandAdsEnabled: Boolean = true
    var isHidePlayerAdsEnabled: Boolean = true
    var isHidePodcastsEnabled: Boolean = false
    var isDisableSmartShuffleEnabled: Boolean = true

    var onPreferenceChangedListener: (() -> Unit)? = null

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    @Volatile
    private var localPrefs: SharedPreferences? = null

    private val inMemoryBooleans = ConcurrentHashMap<String, Boolean>()
    private val inMemoryStrings = ConcurrentHashMap<String, String>()
    private val inMemoryInts = ConcurrentHashMap<String, Int>()

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key != null) {
            Log.d(TAG, "Preference changed: $key")
            updateMemoryCache(prefs, key)
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
            onPreferenceChangedListener?.invoke()
        }
    }

    fun initLocal(context: Context) {
        if (localPrefs != null) return
        val targetContext = context.applicationContext ?: context
        try {
            val lp = targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    fun getPrefs(context: Context? = null): SharedPreferences {
        if (context != null && localPrefs == null) {
            initLocal(context)
        }

        if (remotePrefs == null) {
            val service = SpotXeroApplication.service
            if (service != null) {
                try {
                    val rp = service.getRemotePreferences(PREFS_NAME)
                    bindRemote(rp)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed obtaining service remote preferences: ${t.message}")
                }
            }
        }

        remotePrefs?.let { return it }
        localPrefs?.let { return it }

        if (context != null) {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        throw IllegalStateException("No SharedPreferences source available")
    }

    fun bindServiceRemote(context: Context) {
        val service = SpotXeroApplication.service ?: return
        try {
            val rp = service.getRemotePreferences(PREFS_NAME)
            val lp = localPrefs ?: context.applicationContext
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (lp != null) {
                localPrefs = lp
                val remoteEditor = rp.edit()
                for ((k, v) in lp.all) {
                    when (v) {
                        is Boolean -> remoteEditor.putBoolean(k, v)
                        is String -> remoteEditor.putString(k, v)
                        is Int -> remoteEditor.putInt(k, v)
                        is Long -> remoteEditor.putLong(k, v)
                    }
                }
                remoteEditor.apply()
            }
            bindRemote(rp)
            if (lp != null) {
                val editor = lp.edit()
                for ((k, v) in inMemoryBooleans) editor.putBoolean(k, v)
                for ((k, v) in inMemoryStrings) editor.putString(k, v)
                for ((k, v) in inMemoryInts) editor.putInt(k, v)
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
        isPremiumEnabled = prefs.getBoolean(PrefKeys.KEY_ENABLE_PREMIUM, true)
        inMemoryBooleans[PrefKeys.KEY_ENABLE_PREMIUM] = isPremiumEnabled
        isCleanSharingEnabled = prefs.getBoolean(PrefKeys.KEY_CLEAN_SHARING, true)
        inMemoryBooleans[PrefKeys.KEY_CLEAN_SHARING] = isCleanSharingEnabled
        isSpotifyAmoledEnabled = prefs.getBoolean(PrefKeys.KEY_SPOTIFY_AMOLED, false)
        inMemoryBooleans[PrefKeys.KEY_SPOTIFY_AMOLED] = isSpotifyAmoledEnabled
        isHidePremiumTabEnabled = prefs.getBoolean(PrefKeys.KEY_HIDE_PREMIUM_TAB, false)
        inMemoryBooleans[PrefKeys.KEY_HIDE_PREMIUM_TAB] = isHidePremiumTabEnabled
        isBlockAdsEnabled = prefs.getBoolean(PrefKeys.KEY_BLOCK_ADS, true)
        inMemoryBooleans[PrefKeys.KEY_BLOCK_ADS] = isBlockAdsEnabled
        isHideBrandAdsEnabled = prefs.getBoolean(PrefKeys.KEY_HIDE_BRAND_ADS, true)
        inMemoryBooleans[PrefKeys.KEY_HIDE_BRAND_ADS] = isHideBrandAdsEnabled
        isHidePlayerAdsEnabled = prefs.getBoolean(PrefKeys.KEY_HIDE_PLAYER_ADS, true)
        inMemoryBooleans[PrefKeys.KEY_HIDE_PLAYER_ADS] = isHidePlayerAdsEnabled
        isHidePodcastsEnabled = prefs.getBoolean(PrefKeys.KEY_HIDE_PODCASTS, false)
        inMemoryBooleans[PrefKeys.KEY_HIDE_PODCASTS] = isHidePodcastsEnabled
        isDisableSmartShuffleEnabled = prefs.getBoolean(PrefKeys.KEY_DISABLE_SMART_SHUFFLE, true)
        inMemoryBooleans[PrefKeys.KEY_DISABLE_SMART_SHUFFLE] = isDisableSmartShuffleEnabled
        isLauncherIconHidden = prefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false)
        inMemoryBooleans[KEY_HIDE_LAUNCHER_ICON] = isLauncherIconHidden
    }

    fun isEnabled(key: String, default: Boolean = true): Boolean {
        inMemoryBooleans[key]?.let { return it }
        val value = try {
            remotePrefs?.getBoolean(key, default)
                ?: localPrefs?.getBoolean(key, default)
                ?: default
        } catch (_: Throwable) {
            default
        }
        inMemoryBooleans[key] = value
        return value
    }

    fun getString(key: String, default: String = ""): String {
        inMemoryStrings[key]?.let { return it }
        val value = try {
            remotePrefs?.getString(key, default)
                ?: localPrefs?.getString(key, default)
                ?: default
        } catch (_: Throwable) {
            default
        }
        inMemoryStrings[key] = value
        return value
    }

    fun setBoolean(context: Context? = null, key: String, value: Boolean) {
        inMemoryBooleans[key] = value
        try {
            getPrefs(context).edit().putBoolean(key, value).apply()
            updateFieldDirectly(key, value)
        } catch (t: Throwable) {
            Log.e(TAG, "Error writing boolean preference $key: ${t.message}", t)
        }
    }

    fun setString(context: Context? = null, key: String, value: String) {
        inMemoryStrings[key] = value
        try {
            getPrefs(context).edit().putString(key, value).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Error writing string preference $key: ${t.message}", t)
        }
    }

    fun setInt(context: Context? = null, key: String, value: Int) {
        inMemoryInts[key] = value
        try {
            getPrefs(context).edit().putInt(key, value).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Error writing int preference $key: ${t.message}", t)
        }
    }

    fun setLong(context: Context? = null, key: String, value: Long) {
        try {
            getPrefs(context).edit().putLong(key, value).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Error writing long preference $key: ${t.message}", t)
        }
    }

    fun requestForceStop(context: Context? = null) {
        setLong(context, PrefKeys.KEY_FORCE_STOP, System.currentTimeMillis())
    }

    fun updateFieldDirectly(key: String, value: Boolean) {
        when (key) {
            PrefKeys.KEY_ENABLE_PREMIUM -> isPremiumEnabled = value
            PrefKeys.KEY_CLEAN_SHARING -> isCleanSharingEnabled = value
            PrefKeys.KEY_SPOTIFY_AMOLED -> isSpotifyAmoledEnabled = value
            PrefKeys.KEY_HIDE_PREMIUM_TAB -> isHidePremiumTabEnabled = value
            PrefKeys.KEY_BLOCK_ADS -> isBlockAdsEnabled = value
            PrefKeys.KEY_HIDE_BRAND_ADS -> isHideBrandAdsEnabled = value
            PrefKeys.KEY_HIDE_PLAYER_ADS -> isHidePlayerAdsEnabled = value
            PrefKeys.KEY_HIDE_PODCASTS -> isHidePodcastsEnabled = value
            KEY_APP_DYNAMIC_COLOR -> isDynamicColorEnabled = value
            KEY_APP_AMOLED_THEME -> isAppAmoledEnabled = value
            KEY_HIDE_LAUNCHER_ICON -> isLauncherIconHidden = value
        }
    }

    private fun updateMemoryCache(prefs: SharedPreferences, key: String) {
        if (prefs.contains(key)) {
            val all = prefs.all
            when (val v = all[key]) {
                is Boolean -> {
                    inMemoryBooleans[key] = v
                    when (key) {
                        PrefKeys.KEY_ENABLE_PREMIUM -> isPremiumEnabled = v
                        PrefKeys.KEY_CLEAN_SHARING -> isCleanSharingEnabled = v
                        PrefKeys.KEY_SPOTIFY_AMOLED -> isSpotifyAmoledEnabled = v
                        PrefKeys.KEY_HIDE_PREMIUM_TAB -> isHidePremiumTabEnabled = v
                        PrefKeys.KEY_BLOCK_ADS -> isBlockAdsEnabled = v
                        PrefKeys.KEY_HIDE_BRAND_ADS -> isHideBrandAdsEnabled = v
                        PrefKeys.KEY_HIDE_PLAYER_ADS -> isHidePlayerAdsEnabled = v
                        PrefKeys.KEY_HIDE_PODCASTS -> isHidePodcastsEnabled = v
                        KEY_APP_DYNAMIC_COLOR -> isDynamicColorEnabled = v
                        KEY_APP_AMOLED_THEME -> isAppAmoledEnabled = v
                        KEY_HIDE_LAUNCHER_ICON -> isLauncherIconHidden = v
                    }
                }
                is String -> {
                    inMemoryStrings[key] = v
                    if (key == KEY_APP_THEME_MODE) {
                        appThemeMode = parseThemeMode(v)
                    }
                }
                is Int -> inMemoryInts[key] = v
            }
        }
    }

    private fun parseThemeMode(name: String?): ThemeMode = when (name?.uppercase()) {
        "LIGHT" -> ThemeMode.LIGHT
        "DARK" -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }
}
