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
        PreferencesManager.loadAll(application)
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
                val scope = service.scope
                val isInScope = scope.any { it in CommonUtils.SUPPORTED_PACKAGES }
                if (!isInScope && scope.isNotEmpty()) {
                    BannerEvaluationData(
                        titleRes = R.string.framework_lsposed_not_in_scope_title,
                        titleArgs = emptyList(),
                        descRes = R.string.framework_lsposed_not_in_scope_desc,
                        descArgs = emptyList(),
                        isInjectable = false
                    )
                } else {
                    val fwVersion = service.frameworkVersion
                    BannerEvaluationData(
                        titleRes = R.string.framework_active_title_dynamic,
                        titleArgs = listOf(frameworkName),
                        descRes = R.string.framework_active_desc,
                        descArgs = listOf(frameworkName, fwVersion),
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
                isModuleActive = isModuleActive,
                detectedFramework = frameworkName,
                isFrameworkConnected = isFrameworkConnected,
                isInjectable = eval.isInjectable,
                titleRes = eval.titleRes,
                titleArgs = eval.titleArgs,
                descRes = eval.descRes,
                descArgs = eval.descArgs,
                isPremiumEnabled = PreferencesManager.isPremiumEnabled,
                themeMode = PreferencesManager.appThemeMode,
                isDynamicColorEnabled = PreferencesManager.isDynamicColorEnabled,
                isAppAmoledEnabled = PreferencesManager.isAppAmoledEnabled,
                isLauncherIconHidden = AppUtils.isLauncherIconHidden(app),
                isCleanSharingEnabled = PreferencesManager.isCleanSharingEnabled,
                isSpotifyAmoledEnabled = PreferencesManager.isSpotifyAmoledEnabled,
                isHidePodcastsEnabled = PreferencesManager.isHidePodcastsEnabled
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
                app.getString(R.string.msg_opened_spotify_settings)
            }
            notifyEffect(DashboardUiEffect.ShowToast(msg))
        }
    }


    fun setPremiumEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PrefKeys.KEY_ENABLE_PREMIUM, enabled)
        PreferencesManager.isPremiumEnabled = enabled
        _uiState.update { it.copy(isPremiumEnabled = enabled) }
        refreshState()
    }

    fun setThemeMode(mode: PreferencesManager.ThemeMode) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PreferencesManager.KEY_APP_THEME_MODE, mode.name)
        PreferencesManager.appThemeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PreferencesManager.KEY_APP_DYNAMIC_COLOR, enabled)
        PreferencesManager.isDynamicColorEnabled = enabled
        _uiState.update { it.copy(isDynamicColorEnabled = enabled) }
    }

    fun setAppAmoled(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PreferencesManager.KEY_APP_AMOLED_THEME, enabled)
        PreferencesManager.isAppAmoledEnabled = enabled
        _uiState.update { it.copy(isAppAmoledEnabled = enabled) }
    }

    fun setLauncherIconHidden(enabled: Boolean) {
        val app = getApplication<Application>()
        AppUtils.setLauncherIconHidden(app, enabled)
        PreferencesManager.setBoolean(app, PreferencesManager.KEY_HIDE_LAUNCHER_ICON, enabled)
        PreferencesManager.isLauncherIconHidden = enabled
        _uiState.update { it.copy(isLauncherIconHidden = enabled) }
    }

    fun setCleanSharing(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PrefKeys.KEY_CLEAN_SHARING, enabled)
        PreferencesManager.isCleanSharingEnabled = enabled
        _uiState.update { it.copy(isCleanSharingEnabled = enabled) }
    }

    fun setSpotifyAmoled(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PrefKeys.KEY_SPOTIFY_AMOLED, enabled)
        PreferencesManager.isSpotifyAmoledEnabled = enabled
        _uiState.update { it.copy(isSpotifyAmoledEnabled = enabled) }
    }



    fun setHidePodcasts(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PrefKeys.KEY_HIDE_PODCASTS, enabled)
        PreferencesManager.isHidePodcastsEnabled = enabled
        _uiState.update { it.copy(isHidePodcastsEnabled = enabled) }
    }


    fun showSettingsScreen() {
        _uiState.update { it.copy(showSettings = true, settingsSubpage = null) }
    }

    fun selectSettingsSubpage(subpage: SettingsSubpage?) {
        _uiState.update { it.copy(settingsSubpage = subpage) }
    }

    fun handleBack(): Boolean {
        val current = _uiState.value
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

    fun openSpotify() = launchSpotify()

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
    val isInjectable: Boolean
)
