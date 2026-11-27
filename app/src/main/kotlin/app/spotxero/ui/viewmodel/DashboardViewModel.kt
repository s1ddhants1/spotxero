package app.spotxero.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.spotxero.R
import app.spotxero.SpotXeroApplication
import app.spotxero.core.LSPatchHelper
import app.spotxero.core.ModuleStatus
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.hooks.lyrics.PersistentLyricsCache
import app.spotxero.utils.AppUtils
import app.spotxero.utils.core.CommonUtils
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _effects = Channel<DashboardUiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    @Volatile
    private var cachedTargetStatus = LSPatchHelper.TargetStatus(
        isInstalled = false,
        isPatched = false,
        isModuleEmbedded = false
    )

    private val serviceListener = object : SpotXeroApplication.ServiceConnectionListener {
        override fun onServiceConnected(service: XposedService?) {
            viewModelScope.launch {
                ModuleStatus.checkStatus()
                refreshState()
            }
        }
    }

    init {
        LSPatchHelper.requestServicePush(application)
        PreferencesManager.init(application)
        ModuleStatus.checkStatus()

        refreshState()
        refreshTargetAndState()

        SpotXeroApplication.addConnectionListener(serviceListener)

        PreferencesManager.onPreferenceChangedListener = {
            viewModelScope.launch {
                PreferencesManager.loadAll(getApplication())
                refreshState()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        SpotXeroApplication.removeConnectionListener(serviceListener)
        PreferencesManager.onPreferenceChangedListener = null
    }

    fun refreshStatus() {
        LSPatchHelper.requestServicePush(getApplication())
        ModuleStatus.checkStatus()
        PreferencesManager.loadAll(getApplication())
        refreshTargetAndState()
    }

    private fun refreshTargetAndState() {
        viewModelScope.launch {
            val (installed, targetStatus) = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val installedVariants = scanInstalledSpotify(app)
                val targetPackages = installedVariants.map { it.packageName }.ifEmpty { CommonUtils.SUPPORTED_PACKAGES }
                val inspected = LSPatchHelper.inspectTargetApp(app, targetPackages)
                installedVariants to inspected
            }
            cachedTargetStatus = targetStatus
            SpotXeroApplication.updatePreferLSPatch(
                getApplication(),
                targetStatus.isInstalled && targetStatus.isPatched && !targetStatus.isModuleEmbedded && targetStatus.useManager != false
            )
            val active = targetStatus.packageName?.let { pkg ->
                installed.firstOrNull { it.packageName == pkg }
            } ?: installed.firstOrNull { it.packageName == CommonUtils.SPOTIFY_PACKAGE_NAME }
                ?: installed.firstOrNull()

            _uiState.update {
                it.copy(
                    installedSpotifyVariants = installed,
                    activeSpotify = active
                )
            }
            refreshState()
        }
    }

    fun refreshState() {
        val app = getApplication<Application>()
        val service = SpotXeroApplication.service
        val targetPackages = _uiState.value.installedSpotifyVariants.map { it.packageName }.ifEmpty { CommonUtils.SUPPORTED_PACKAGES }

        val eval = LSPatchHelper.evaluateFrameworkStatus(
            frameworkName = service?.frameworkName,
            frameworkVersion = service?.frameworkVersion,
            scope = service?.scope,
            isServiceBound = service != null,
            targetStatus = cachedTargetStatus,
            packageNames = targetPackages
        )

        _uiState.update { current ->
            current.copy(
                isFrameworkConnected = eval.isConnected,
                isInjectable = eval.isInjectable,
                canRequestScope = eval.canRequestScope,
                isIntegrated = eval.isIntegrated,
                frameworkName = eval.frameworkName,
                frameworkVersion = eval.frameworkVersion,
                titleRes = eval.titleRes,
                titleArgs = eval.titleArgs,
                descRes = eval.descRes,
                descArgs = eval.descArgs,
                isMasterEnabled = PreferencesManager.isMasterEnabled,
                isPremiumEnabled = PreferencesManager.isPremiumEnabled,
                themeMode = PreferencesManager.appThemeMode,
                isDynamicColorEnabled = PreferencesManager.isDynamicColorEnabled,
                isAppAmoledEnabled = PreferencesManager.isAppAmoledEnabled,
                isLauncherIconHidden = AppUtils.isLauncherIconHidden(app),
                isHapticFeedbackEnabled = PreferencesManager.enableHapticFeedback,
                isCleanSharingEnabled = PreferencesManager.isCleanSharingEnabled,
                isLyricsEnabled = PreferencesManager.isLyricsEnabled,
                isExternalLyricsEnabled = PreferencesManager.isExternalLyricsEnabled,
                isForceExternalLyricsEnabled = PreferencesManager.isForceExternalLyricsEnabled,
                isAiLyricsTranslationEnabled = PreferencesManager.isAiLyricsTranslationEnabled,
                isAiTranslationDualLine = PreferencesManager.isAiTranslationDualLine,
                aiTranslationProvider = PreferencesManager.aiTranslationProvider,
                aiTranslationApiKey = PreferencesManager.aiTranslationApiKey,
                aiTranslationBaseUrl = PreferencesManager.aiTranslationBaseUrl,
                aiTranslationModel = PreferencesManager.aiTranslationModel,
                aiTranslationTargetLang = PreferencesManager.aiTranslationTargetLang,
                aiTranslationCustomPrompt = PreferencesManager.aiTranslationCustomPrompt,
                aiTranslationTranslateMode = PreferencesManager.aiTranslationTranslateMode,
                aiTranslationDeeplApiKey = PreferencesManager.aiTranslationDeeplApiKey,
                aiTranslationDeeplFormality = PreferencesManager.aiTranslationDeeplFormality,
                lyricsProvidersOrder = parseLyricsProviders(PreferencesManager.lyricsProvidersOrder),
                isSpicylyricsEnabled = PreferencesManager.isSpicylyricsEnabled,
                isBetterlyricsEnabled = PreferencesManager.isBetterlyricsEnabled,
                isLrcredEnabled = PreferencesManager.isLrcredEnabled,
                isLrclibEnabled = PreferencesManager.isLrclibEnabled,
                isKugouEnabled = PreferencesManager.isKugouEnabled,
                isNeteaseEnabled = PreferencesManager.isNeteaseEnabled,
                isGeniusEnabled = PreferencesManager.isGeniusEnabled,
                isForceLocalPlaybackEnabled = PreferencesManager.isForceLocalPlaybackEnabled,
                isSpotifyAmoledEnabled = PreferencesManager.isSpotifyAmoledEnabled,
                isSpotifyMonetEnabled = PreferencesManager.isSpotifyMonetEnabled,
                isSpotifyRoundyUiEnabled = PreferencesManager.isSpotifyRoundyUiEnabled,
                isHidePodcastsEnabled = PreferencesManager.isHidePodcastsEnabled,
                isHideAudiobooksEnabled = PreferencesManager.isHideAudiobooksEnabled,
                isHidePremiumTabEnabled = PreferencesManager.isHidePremiumTabEnabled,
                isDoubleTapToSeekEnabled = PreferencesManager.isDoubleTapToSeekEnabled,
                seekDurationSeconds = PreferencesManager.seekDurationSeconds,
                isSeekProgressive = PreferencesManager.isSeekProgressive,
            )
        }
    }

    private fun scanInstalledSpotify(app: Application): List<InstalledSpotifyInfo> {
        val pm = app.packageManager
        val installed = mutableListOf<InstalledSpotifyInfo>()
        for (pkg in CommonUtils.SUPPORTED_PACKAGES) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val ver = info.versionName ?: "Unknown"
                installed.add(InstalledSpotifyInfo(pkg, ver))
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return installed
    }

    fun openInAppSettings() {
        val pkg = _uiState.value.activeSpotify?.packageName ?: CommonUtils.SPOTIFY_PACKAGE_NAME
        val app = getApplication<Application>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:spotxero")).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching {
            app.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!opened) {
            launchSpotify()
        }
    }

    fun launchSpotify() {
        val pkg = _uiState.value.activeSpotify?.packageName ?: CommonUtils.SPOTIFY_PACKAGE_NAME
        val app = getApplication<Application>()
        if (!AppUtils.openSpotify(app, pkg)) {
            notifyEffect(DashboardUiEffect.ShowToast(app.getString(R.string.msg_spotify_not_installed)))
        }
    }

    fun forceStopSpotify() {
        val pkg = _uiState.value.activeSpotify?.packageName ?: CommonUtils.SPOTIFY_PACKAGE_NAME
        val app = getApplication<Application>()
        viewModelScope.launch {
            val stopped = AppUtils.forceStopSpotify(app, pkg)
            val msg = if (stopped) {
                app.getString(R.string.msg_spotify_force_stopped)
            } else {
                app.getString(R.string.msg_spotify_not_running)
            }
            notifyEffect(DashboardUiEffect.ShowToast(msg))
        }
    }


    private inline fun updateBoolPref(
        key: String,
        value: Boolean,
        crossinline copyState: DashboardUiState.(Boolean) -> DashboardUiState
    ) {
        PreferencesManager.setBoolean(getApplication(), key, value)
        _uiState.update { it.copyState(value) }
    }

    private inline fun updateStringPref(
        key: String,
        value: String,
        clearLyricsCache: Boolean = false,
        crossinline copyState: DashboardUiState.(String) -> DashboardUiState
    ) {
        PreferencesManager.setString(getApplication(), key, value)
        if (clearLyricsCache) PersistentLyricsCache.clear()
        _uiState.update { it.copyState(value) }
    }

    fun setMasterEnabled(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_MASTER_ENABLED, enabled) { copy(isMasterEnabled = it) }

    fun setPremiumEnabled(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_ENABLE_PREMIUM, enabled) { copy(isPremiumEnabled = it) }

    fun setThemeMode(mode: PreferencesManager.ThemeMode) {
        PreferencesManager.setString(getApplication(), PreferencesManager.KEY_APP_THEME_MODE, mode.name)
        PreferencesManager.appThemeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) = updateBoolPref(PreferencesManager.KEY_APP_DYNAMIC_COLOR, enabled) { copy(isDynamicColorEnabled = it) }

    fun setAppAmoled(enabled: Boolean) = updateBoolPref(PreferencesManager.KEY_APP_AMOLED_THEME, enabled) { copy(isAppAmoledEnabled = it) }

    fun setLauncherIconHidden(enabled: Boolean) {
        val app = getApplication<Application>()
        AppUtils.setLauncherIconHidden(app, enabled)
        PreferencesManager.setBoolean(app, PreferencesManager.KEY_HIDE_LAUNCHER_ICON, enabled)
        _uiState.update { it.copy(isLauncherIconHidden = enabled) }
    }

    fun setHapticFeedback(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_ENABLE_HAPTIC_FEEDBACK, enabled) { copy(isHapticFeedbackEnabled = it) }

    fun setCleanSharing(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_CLEAN_SHARING, enabled) { copy(isCleanSharingEnabled = it) }

    fun setLyrics(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_UNCAPPED_LYRICS, enabled) { copy(isLyricsEnabled = it) }

    fun setForceLocalPlayback(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, enabled) { copy(isForceLocalPlaybackEnabled = it) }

    fun setSpotifyAmoled(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_SPOTIFY_AMOLED, enabled) { copy(isSpotifyAmoledEnabled = it) }

    fun setSpotifyMonet(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_SPOTIFY_MONET, enabled) { copy(isSpotifyMonetEnabled = it) }

    fun setSpotifyRoundyUi(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_SPOTIFY_ROUNDY_UI, enabled) { copy(isSpotifyRoundyUiEnabled = it) }

    fun setHidePodcasts(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_HIDE_PODCASTS, enabled) { copy(isHidePodcastsEnabled = it) }

    fun setHideAudiobooks(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_HIDE_AUDIOBOOKS, enabled) { copy(isHideAudiobooksEnabled = it) }

    fun setHidePremiumTab(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_HIDE_PREMIUM_TAB, enabled) { copy(isHidePremiumTabEnabled = it) }

    fun showLyricsSourcesScreen() {
        _uiState.update { it.copy(showLyricsSources = true, showAiLyricsTranslation = false) }
    }

    fun showAiLyricsTranslationScreen() {
        _uiState.update { it.copy(showAiLyricsTranslation = true) }
    }

    fun setAiTranslationProvider(provider: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_PROVIDER, provider) { copy(aiTranslationProvider = it) }

    fun setAiTranslationApiKey(key: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_API_KEY, key) { copy(aiTranslationApiKey = it) }

    fun setAiTranslationBaseUrl(url: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_BASE_URL, url, clearLyricsCache = true) { copy(aiTranslationBaseUrl = it) }

    fun setAiTranslationModel(model: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_MODEL, model) { copy(aiTranslationModel = it) }

    fun setAiTranslationTargetLang(lang: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_TARGET_LANG, lang) { copy(aiTranslationTargetLang = it) }

    fun setAiTranslationCustomPrompt(prompt: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_CUSTOM_PROMPT, prompt, clearLyricsCache = true) { copy(aiTranslationCustomPrompt = it) }

    fun setAiTranslationTranslateMode(mode: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_TRANSLATE_MODE, mode) { copy(aiTranslationTranslateMode = it) }

    fun setAiTranslationDeeplApiKey(key: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY, key) { copy(aiTranslationDeeplApiKey = it) }

    fun setAiLyricsTranslationEnabled(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED, enabled) { copy(isAiLyricsTranslationEnabled = it) }

    fun setAiTranslationDualLine(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_AI_TRANSLATION_DUAL_LINE, enabled) { copy(isAiTranslationDualLine = it) }

    fun setAiTranslationDeeplFormality(formality: String) = updateStringPref(PrefKeys.KEY_AI_TRANSLATION_DEEPL_FORMALITY, formality, clearLyricsCache = true) { copy(aiTranslationDeeplFormality = it) }

    fun setExternalLyrics(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_EXTERNAL_LYRICS, enabled) { copy(isExternalLyricsEnabled = it) }

    fun setForceExternalLyrics(enabled: Boolean) = updateBoolPref(PrefKeys.KEY_FORCE_EXTERNAL_LYRICS, enabled) { copy(isForceExternalLyricsEnabled = it) }

    fun setLyricsProviderEnabled(id: String, enabled: Boolean) {
        when (id) {
            "spicylyrics" -> updateBoolPref(PrefKeys.KEY_LYRICS_PROVIDER_SPICYLYRICS, enabled) { copy(isSpicylyricsEnabled = it) }
            "betterlyrics" -> updateBoolPref(PrefKeys.KEY_LYRICS_PROVIDER_BETTERLYRICS, enabled) { copy(isBetterlyricsEnabled = it) }
            "lrcred" -> updateBoolPref(PrefKeys.KEY_LYRICS_PROVIDER_LRCRED, enabled) { copy(isLrcredEnabled = it) }
            "lrclib" -> updateBoolPref(PrefKeys.KEY_LYRICS_PROVIDER_LRCLIB, enabled) { copy(isLrclibEnabled = it) }
            "kugou" -> updateBoolPref(PrefKeys.KEY_LYRICS_PROVIDER_KUGOU, enabled) { copy(isKugouEnabled = it) }
            "netease" -> updateBoolPref(PrefKeys.KEY_LYRICS_PROVIDER_NETEASE, enabled) { copy(isNeteaseEnabled = it) }
            "genius" -> updateBoolPref(PrefKeys.KEY_LYRICS_PROVIDER_GENIUS, enabled) { copy(isGeniusEnabled = it) }
        }
    }

    fun reorderLyricsProviders(newOrder: List<String>) =
        updateStringPref(PrefKeys.KEY_LYRICS_PROVIDERS_ORDER, newOrder.joinToString(",")) { copy(lyricsProvidersOrder = newOrder) }

    fun showSettingsScreen() {
        _uiState.update { it.copy(showSettings = true, settingsSubpage = null) }
    }

    fun selectSettingsSubpage(subpage: SettingsSubpage?) {
        _uiState.update { it.copy(settingsSubpage = subpage) }
    }

    fun handleBack(): Boolean {
        val current = _uiState.value
        if (current.showAiLyricsTranslation) {
            _uiState.update { it.copy(showAiLyricsTranslation = false) }
            return true
        }
        if (current.showLyricsSources) {
            _uiState.update { it.copy(showLyricsSources = false) }
            return true
        }
        if (current.settingsSubpage != null) {
            _uiState.update { it.copy(settingsSubpage = null) }
            return true
        }
        if (current.showSettings) {
            _uiState.update { it.copy(showSettings = false) }
            return true
        }
        return false
    }

    private fun parseLyricsProviders(raw: String): List<String> =
        PrefKeys.parseLyricsProviders(raw)

    fun setDoubleTapToSeekEnabled(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, enabled)
        refreshState()
    }

    fun setSeekDurationSeconds(seconds: Int) {
        PreferencesManager.setInt(getApplication(), PrefKeys.KEY_SEEK_DURATION_SECONDS, seconds)
        refreshState()
    }

    fun setSeekProgressive(progressive: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_SEEK_PROGRESSIVE, progressive)
        refreshState()
    }

    fun notifyEffect(effect: DashboardUiEffect) {
        viewModelScope.launch {
            _effects.send(effect)
        }
    }

    fun requestScope(packageName: String? = null) {
        val app = getApplication<Application>()
        val target = packageName ?: _uiState.value.activeSpotify?.packageName ?: CommonUtils.SPOTIFY_PACKAGE_NAME
        val service = SpotXeroApplication.service ?: return
        val isLSPatch = service.frameworkName.contains("LSPatch", ignoreCase = true)
        service.requestScope(listOf(target), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                viewModelScope.launch {
                    notifyEffect(DashboardUiEffect.ShowToast(app.getString(R.string.msg_scope_approved_restart)))
                    refreshStatus()
                }
            }

            override fun onScopeRequestFailed(message: String) {
                viewModelScope.launch {
                    if (isLSPatch) {
                        val intent = app.packageManager.getLaunchIntentForPackage("org.lsposed.lspatch")
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            app.startActivity(intent)
                        } else {
                            notifyEffect(DashboardUiEffect.ShowToast(app.getString(R.string.msg_lspatch_manager_not_found)))
                        }
                    } else {
                        notifyEffect(DashboardUiEffect.ShowToast(app.getString(R.string.msg_scope_request_failed)))
                    }
                    refreshState()
                }
            }
        })
    }
}
