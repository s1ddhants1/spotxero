package app.spotxero.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.spotxero.SpotXeroApplication
import java.util.concurrent.ConcurrentHashMap

object PreferencesManager {

    private const val TAG = "SpotXero.Prefs"
    const val PREFS_NAME = "spotify_prefs"
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
    var isPremiumEnabled: Boolean = true
    var isCleanSharingEnabled: Boolean = true
    var isSpotifyAmoledEnabled: Boolean = false
    var isHidePodcastsEnabled: Boolean = false
    var isClientSpoofEnabled: Boolean = false

    var onPreferenceChangedListener: (() -> Unit)? = null

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    @Volatile
    private var localPrefs: SharedPreferences? = null

    @Volatile
    private var targetPrefs: SharedPreferences? = null

    private val inMemoryBooleans = ConcurrentHashMap<String, Boolean>()
    private val inMemoryStrings = ConcurrentHashMap<String, String>()
    private val inMemoryInts = ConcurrentHashMap<String, Int>()

    private fun resolveTargetContext(): Context? {
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null) as? Context
            if (currentApp != null) return@runCatching currentApp
            val at = atClass.getMethod("currentActivityThread").invoke(null)
            atClass.getMethod("getApplication").invoke(at) as? Context
        }.getOrNull()
    }

    fun initTarget(context: Context) {
        val targetContext = context.applicationContext ?: context
        runCatching {
            val tp = targetContext.getSharedPreferences(TARGET_PREFS_NAME, Context.MODE_PRIVATE)
            targetPrefs = tp
            if (tp.contains(PrefKeys.KEY_SHUFFLE_STATE)) {
                inMemoryBooleans[PrefKeys.KEY_SHUFFLE_STATE] = tp.getBoolean(PrefKeys.KEY_SHUFFLE_STATE, false)
            }
        }
    }

    private fun getTargetPrefs(): SharedPreferences? {
        val tp = targetPrefs
        if (tp != null) return tp
        val ctx = resolveTargetContext() ?: return null
        return runCatching {
            val target = ctx.getSharedPreferences(TARGET_PREFS_NAME, Context.MODE_PRIVATE)
            targetPrefs = target
            if (target.contains(PrefKeys.KEY_SHUFFLE_STATE)) {
                inMemoryBooleans[PrefKeys.KEY_SHUFFLE_STATE] = target.getBoolean(PrefKeys.KEY_SHUFFLE_STATE, false)
            }
            target
        }.getOrNull()
    }

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key != null && key != "pref_force_stop") {
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
        onPreferenceChangedListener = null
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
        isHidePodcastsEnabled = prefs.getBoolean(PrefKeys.KEY_HIDE_PODCASTS, false)
        inMemoryBooleans[PrefKeys.KEY_HIDE_PODCASTS] = isHidePodcastsEnabled
        isClientSpoofEnabled = prefs.getBoolean(PrefKeys.KEY_CLIENT_SPOOF, false)
        inMemoryBooleans[PrefKeys.KEY_CLIENT_SPOOF] = isClientSpoofEnabled
        isLauncherIconHidden = prefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false)
        inMemoryBooleans[KEY_HIDE_LAUNCHER_ICON] = isLauncherIconHidden
        val tp = getTargetPrefs()
        if (tp != null && tp.contains(PrefKeys.KEY_SHUFFLE_STATE)) {
            inMemoryBooleans[PrefKeys.KEY_SHUFFLE_STATE] = tp.getBoolean(PrefKeys.KEY_SHUFFLE_STATE, false)
        } else if (prefs.contains(PrefKeys.KEY_SHUFFLE_STATE)) {
            inMemoryBooleans[PrefKeys.KEY_SHUFFLE_STATE] = prefs.getBoolean(PrefKeys.KEY_SHUFFLE_STATE, false)
        }
    }

    fun isEnabled(key: String, default: Boolean = true): Boolean {
        inMemoryBooleans[key]?.let { return it }
        val tp = getTargetPrefs()
        if (tp != null && tp.contains(key)) {
            val v = tp.getBoolean(key, default)
            inMemoryBooleans[key] = v
            return v
        }
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

    fun getString(key: String, default: String = ""): String {
        inMemoryStrings[key]?.let { return it }
        val tp = getTargetPrefs()
        if (tp != null && tp.contains(key)) {
            val v = tp.getString(key, default) ?: default
            inMemoryStrings[key] = v
            return v
        }
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

    fun updateFieldDirectly(key: String, value: Boolean) {
        when (key) {
            PrefKeys.KEY_ENABLE_PREMIUM -> isPremiumEnabled = value
            PrefKeys.KEY_CLEAN_SHARING -> isCleanSharingEnabled = value
            PrefKeys.KEY_SPOTIFY_AMOLED -> isSpotifyAmoledEnabled = value
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
