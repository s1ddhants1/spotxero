package app.spotxero.ui.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.spotxero.R
import app.spotxero.SpotXeroApplication
import app.spotxero.core.ModuleStatus
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.hooks.lyrics.PersistentLyricsCache
import app.spotxero.utils.AppUtils
import app.spotxero.utils.core.CommonUtils
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _effects = Channel<DashboardUiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val serviceListener = object : SpotXeroApplication.ServiceConnectionListener {
        override fun onServiceConnected(service: XposedService?) {
            ModuleStatus.checkStatus()
            refreshState()
        }
    }

    init {
        PreferencesManager.init(application)
        ModuleStatus.checkStatus()

        detectSpotifyStatus()
        refreshState()

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
        ModuleStatus.checkStatus()
        PreferencesManager.loadAll(getApplication())
        detectSpotifyStatus()
        refreshState()
    }

    fun refreshState() {
        val app = getApplication<Application>()
        val isModuleActive = ModuleStatus.isModuleActive.value
        val frameworkName = ModuleStatus.frameworkName.value ?: "LSPosed"
        val service = SpotXeroApplication.service
        val name = service?.frameworkName?.takeIf { it.isNotBlank() } ?: frameworkName
        val version = service?.frameworkVersion.orEmpty()
        val isFrameworkConnected = isModuleActive || service != null
        val isInstalled = _uiState.value.installedSpotifyVariants.isNotEmpty()

        val eval = when {
            !isInstalled -> {
                BannerEvaluationData(
                    titleRes = R.string.framework_spotify_not_installed_title,
                    titleArgs = emptyList(),
                    descRes = R.string.framework_spotify_not_installed_desc,
                    descArgs = emptyList(),
                    isInjectable = false
                )
            }
            service != null -> {
                val currentScope = service.scope.orEmpty()
                val isLSPosed = name.contains("LSPosed", ignoreCase = true)
                val isInScope = if (isLSPosed) {
                    currentScope.any { it in CommonUtils.SUPPORTED_PACKAGES }
                } else {
                    currentScope.isEmpty() || currentScope.any { it in CommonUtils.SUPPORTED_PACKAGES }
                }

                if (!isInScope) {
                    BannerEvaluationData(
                        titleRes = R.string.framework_lsposed_not_in_scope_title,
                        titleArgs = emptyList(),
                        descRes = R.string.framework_lsposed_not_in_scope_desc,
                        descArgs = emptyList(),
                        isInjectable = false,
                        canRequestScope = true
                    )
                } else {
                    BannerEvaluationData(
                        titleRes = R.string.framework_active_title_dynamic,
                        titleArgs = listOf(name),
                        descRes = R.string.framework_active_desc,
                        descArgs = listOf(name, version),
                        isInjectable = true
                    )
                }
            }
            isModuleActive -> {
                BannerEvaluationData(
                    titleRes = R.string.framework_active_title_dynamic,
                    titleArgs = listOf(frameworkName),
                    descRes = R.string.framework_active_desc,
                    descArgs = listOf(frameworkName, "API 102"),
                    isInjectable = true
                )
            }
            else -> {
                BannerEvaluationData(
                    titleRes = R.string.framework_inactive_title,
                    titleArgs = emptyList(),
                    descRes = R.string.framework_inactive_desc,
                    descArgs = emptyList(),
                    isInjectable = false
                )
            }
        }

        _uiState.update { current ->
            current.copy(
                isFrameworkConnected = isFrameworkConnected,
                isInjectable = eval.isInjectable,
                canRequestScope = eval.canRequestScope,
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
                isDoubleTapToSeekEnabled = PreferencesManager.isDoubleTapToSeekEnabled,
                seekDurationSeconds = PreferencesManager.seekDurationSeconds,
                isSeekProgressive = PreferencesManager.isSeekProgressive,
                isClientSpoofEnabled = PreferencesManager.isClientSpoofEnabled
            )
        }
    }

    fun detectSpotifyStatus() {
        val pm = getApplication<Application>().packageManager
        val installed = mutableListOf<InstalledSpotifyInfo>()

        for (pkg in CommonUtils.SUPPORTED_PACKAGES) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val ver = info.versionName ?: "Unknown"
                val label = CommonUtils.getVariantLabel(getApplication(), pkg)
                installed.add(InstalledSpotifyInfo(pkg, ver, label))
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        val active = installed.firstOrNull { it.packageName == CommonUtils.SPOTIFY_PACKAGE_NAME }
            ?: installed.firstOrNull()

        _uiState.update {
            it.copy(
                installedSpotifyVariants = installed,
                activeSpotify = active
            )
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


    fun setMasterEnabled(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_MASTER_ENABLED, enabled)
        _uiState.update { it.copy(isMasterEnabled = enabled) }
    }

    fun setPremiumEnabled(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_ENABLE_PREMIUM, enabled)
        _uiState.update { it.copy(isPremiumEnabled = enabled) }
    }

    fun setThemeMode(mode: PreferencesManager.ThemeMode) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PreferencesManager.KEY_APP_THEME_MODE, mode.name)
        PreferencesManager.appThemeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PreferencesManager.KEY_APP_DYNAMIC_COLOR, enabled)
        _uiState.update { it.copy(isDynamicColorEnabled = enabled) }
    }

    fun setAppAmoled(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PreferencesManager.KEY_APP_AMOLED_THEME, enabled)
        _uiState.update { it.copy(isAppAmoledEnabled = enabled) }
    }

    fun setLauncherIconHidden(enabled: Boolean) {
        val app = getApplication<Application>()
        AppUtils.setLauncherIconHidden(app, enabled)
        PreferencesManager.setBoolean(app, PreferencesManager.KEY_HIDE_LAUNCHER_ICON, enabled)
        _uiState.update { it.copy(isLauncherIconHidden = enabled) }
    }

    fun setHapticFeedback(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_ENABLE_HAPTIC_FEEDBACK, enabled)
        _uiState.update { it.copy(isHapticFeedbackEnabled = enabled) }
    }

    fun setCleanSharing(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_CLEAN_SHARING, enabled)
        _uiState.update { it.copy(isCleanSharingEnabled = enabled) }
    }

    fun setLyrics(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_UNCAPPED_LYRICS, enabled)
        _uiState.update { it.copy(isLyricsEnabled = enabled) }
    }

    fun setForceLocalPlayback(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, enabled)
        _uiState.update { it.copy(isForceLocalPlaybackEnabled = enabled) }
    }

    fun setSpotifyAmoled(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_SPOTIFY_AMOLED, enabled)
        _uiState.update { it.copy(isSpotifyAmoledEnabled = enabled) }
    }

    fun setSpotifyMonet(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_SPOTIFY_MONET, enabled)
        _uiState.update { it.copy(isSpotifyMonetEnabled = enabled) }
    }

    fun setSpotifyRoundyUi(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_SPOTIFY_ROUNDY_UI, enabled)
        _uiState.update { it.copy(isSpotifyRoundyUiEnabled = enabled) }
    }

    fun setHidePodcasts(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_HIDE_PODCASTS, enabled)
        _uiState.update { it.copy(isHidePodcastsEnabled = enabled) }
    }

    fun setHideAudiobooks(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_HIDE_AUDIOBOOKS, enabled)
        _uiState.update { it.copy(isHideAudiobooksEnabled = enabled) }
    }


    fun showLyricsSourcesScreen() {
        _uiState.update { it.copy(showLyricsSources = true, showAiLyricsTranslation = false) }
    }

    fun showAiLyricsTranslationScreen() {
        _uiState.update { it.copy(showAiLyricsTranslation = true) }
    }

    fun setAiTranslationProvider(provider: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_PROVIDER, provider)
        _uiState.update { it.copy(aiTranslationProvider = provider) }
    }

    fun setAiTranslationApiKey(key: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_API_KEY, key)
        _uiState.update { it.copy(aiTranslationApiKey = key) }
    }

    fun setAiTranslationBaseUrl(url: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_BASE_URL, url)
        PersistentLyricsCache.clear()
        _uiState.update { it.copy(aiTranslationBaseUrl = url) }
    }

    fun setAiTranslationModel(model: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_MODEL, model)
        _uiState.update { it.copy(aiTranslationModel = model) }
    }

    fun setAiTranslationTargetLang(lang: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_TARGET_LANG, lang)
        _uiState.update { it.copy(aiTranslationTargetLang = lang) }
    }

    fun setAiTranslationCustomPrompt(prompt: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_CUSTOM_PROMPT, prompt)
        PersistentLyricsCache.clear()
        _uiState.update { it.copy(aiTranslationCustomPrompt = prompt) }
    }

    fun setAiTranslationTranslateMode(mode: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_TRANSLATE_MODE, mode)
        _uiState.update { it.copy(aiTranslationTranslateMode = mode) }
    }

    fun setAiTranslationDeeplApiKey(key: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY, key)
        _uiState.update { it.copy(aiTranslationDeeplApiKey = key) }
    }

    fun setAiLyricsTranslationEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED, enabled)
        _uiState.update { it.copy(isAiLyricsTranslationEnabled = enabled) }
    }

    fun setAiTranslationDualLine(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PrefKeys.KEY_AI_TRANSLATION_DUAL_LINE, enabled)
        _uiState.update { it.copy(isAiTranslationDualLine = enabled) }
    }

    fun setAiTranslationDeeplFormality(formality: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PrefKeys.KEY_AI_TRANSLATION_DEEPL_FORMALITY, formality)
        PersistentLyricsCache.clear()
        _uiState.update { it.copy(aiTranslationDeeplFormality = formality) }
    }


    fun setExternalLyrics(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_EXTERNAL_LYRICS, enabled)
        _uiState.update { it.copy(isExternalLyricsEnabled = enabled) }
    }

    fun setForceExternalLyrics(enabled: Boolean) {
        PreferencesManager.setBoolean(getApplication(), PrefKeys.KEY_FORCE_EXTERNAL_LYRICS, enabled)
        _uiState.update { it.copy(isForceExternalLyricsEnabled = enabled) }
    }

    fun setLyricsProviderEnabled(id: String, enabled: Boolean) {
        val app = getApplication<Application>()
        when (id) {
            "betterlyrics" -> {
                PreferencesManager.setBoolean(app, PrefKeys.KEY_LYRICS_PROVIDER_BETTERLYRICS, enabled)
                _uiState.update { it.copy(isBetterlyricsEnabled = enabled) }
            }
            "lrcred" -> {
                PreferencesManager.setBoolean(app, PrefKeys.KEY_LYRICS_PROVIDER_LRCRED, enabled)
                _uiState.update { it.copy(isLrcredEnabled = enabled) }
            }
            "lrclib" -> {
                PreferencesManager.setBoolean(app, PrefKeys.KEY_LYRICS_PROVIDER_LRCLIB, enabled)
                _uiState.update { it.copy(isLrclibEnabled = enabled) }
            }
            "kugou" -> {
                PreferencesManager.setBoolean(app, PrefKeys.KEY_LYRICS_PROVIDER_KUGOU, enabled)
                _uiState.update { it.copy(isKugouEnabled = enabled) }
            }
            "netease" -> {
                PreferencesManager.setBoolean(app, PrefKeys.KEY_LYRICS_PROVIDER_NETEASE, enabled)
                _uiState.update { it.copy(isNeteaseEnabled = enabled) }
            }
            "genius" -> {
                PreferencesManager.setBoolean(app, PrefKeys.KEY_LYRICS_PROVIDER_GENIUS, enabled)
                _uiState.update { it.copy(isGeniusEnabled = enabled) }
            }
        }
    }

    fun reorderLyricsProviders(newOrder: List<String>) {
        val orderStr = newOrder.joinToString(",")
        PreferencesManager.setString(getApplication(), PrefKeys.KEY_LYRICS_PROVIDERS_ORDER, orderStr)
        _uiState.update { it.copy(lyricsProvidersOrder = newOrder) }
    }

    fun setClientSpoof(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PrefKeys.KEY_CLIENT_SPOOF, enabled)
        PreferencesManager.isClientSpoofEnabled = enabled
        _uiState.update { it.copy(isClientSpoofEnabled = enabled) }
    }

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

    private fun parseLyricsProviders(raw: String): List<String> {
        val defaultList = listOf("betterlyrics", "lrcred", "lrclib", "kugou", "netease", "genius")
        val items = raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val result = items.filter { defaultList.contains(it) }.distinct().toMutableList()
        for (d in defaultList) {
            if (!result.contains(d)) result.add(d)
        }
        return result
    }

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
        val target = packageName ?: _uiState.value.activeSpotify?.packageName ?: CommonUtils.SPOTIFY_PACKAGE_NAME
        val service = SpotXeroApplication.service ?: return
        service.requestScope(listOf(target), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                refreshState()
            }

            override fun onScopeRequestFailed(message: String) {
                refreshState()
            }
        })
    }
}

private data class BannerEvaluationData(
    val titleRes: Int,
    val titleArgs: List<String>,
    val descRes: Int,
    val descArgs: List<String>,
    val isInjectable: Boolean,
    val canRequestScope: Boolean = false
)
