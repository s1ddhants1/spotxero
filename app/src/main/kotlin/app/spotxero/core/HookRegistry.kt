package app.spotxero.core

import android.content.pm.ApplicationInfo
import android.util.Log
import app.spotxero.hooks.ads.AdResponseHook
import app.spotxero.hooks.ads.BrandAdsHook
import app.spotxero.hooks.ads.ContextMenuAdBlockHook
import app.spotxero.hooks.ads.NetworkAdBlockHook
import app.spotxero.hooks.ads.PendragonHook
import app.spotxero.hooks.ads.PlayerAdCardsHook
import app.spotxero.hooks.attestation.ClientTokenHook
import app.spotxero.hooks.content.ContentFilterHook
import app.spotxero.hooks.lyrics.LyricsHook
import app.spotxero.hooks.navigation.HidePremiumTabHook
import app.spotxero.hooks.player.ArtworkSeekHook
import app.spotxero.hooks.player.DisableSmartShuffleHook
import app.spotxero.hooks.player.OnDemandInFreeHook
import app.spotxero.hooks.player.PlayerRestrictionsHook
import app.spotxero.hooks.player.PlayerShuffleHook
import app.spotxero.hooks.player.ProductStateHook
import app.spotxero.hooks.sharing.CleanSharingHook
import app.spotxero.hooks.theme.SpotifyAmoledHook
import app.spotxero.hooks.theme.SpotifyMonetHook
import app.spotxero.hooks.theme.SpotifyRoundyUIHook
import app.spotxero.hooks.volume.ForceLocalPlaybackHook
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge

import java.util.Collections

object HookRegistry {

    private const val TAG = "SpotXero.HookRegistry"

    private data class HookEntry(
        val name: String,
        val isEnabled: () -> Boolean,
        val install: () -> Unit
    )

    @Volatile
    private var startupModule: XposedModule? = null

    @Volatile
    private var startupAppInfo: ApplicationInfo? = null

    @Volatile
    private var startupClassLoader: ClassLoader? = null

    @Volatile
    private var isResolved: Boolean = false

    private val installedHooks = Collections.synchronizedSet(mutableSetOf<String>())
    private val lock = Any()

    init {
        PreferencesManager.addOnPreferenceChangedListener {
            checkAndInstallPending()
        }
    }

    fun checkAndInstallPending() {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_MASTER_ENABLED, true)) {
            return
        }
        val module = startupModule ?: return
        val appInfo = startupAppInfo ?: return
        val classLoader = startupClassLoader ?: return

        synchronized(lock) {
            if (!isResolved) {
                resolveAndInstall(module, appInfo, classLoader)
            } else {
                installAll(module)
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            isResolved = false
            installedHooks.clear()
        }
    }

    fun resolveAndInstall(module: XposedModule, appInfo: ApplicationInfo, classLoader: ClassLoader) {
        startupModule = module
        startupAppInfo = appInfo
        startupClassLoader = classLoader

        if (!PreferencesManager.isEnabled(PrefKeys.KEY_MASTER_ENABLED, true)) {
            Log.i(TAG, "Master switch disabled; deferring hook resolution until enabled")
            return
        }

        synchronized(lock) {
            if (!isResolved) {
                val apkPath = appInfo.sourceDir
                val versionCode: Long = runCatching {
                    val pm = Class.forName("android.app.ActivityThread")
                        .getMethod("getPackageManager")
                        .invoke(null)
                    val method = pm.javaClass.methods.firstOrNull { it.name == "getPackageInfo" }
                    val userId = android.os.Process.myUid() / 100000
                    val pkgInfo = when (method?.parameterTypes?.size) {
                        3 -> method.invoke(pm, appInfo.packageName, 0, userId)
                        4 -> method.invoke(pm, appInfo.packageName, 0L, userId)
                        else -> null
                    } as? android.content.pm.PackageInfo
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        pkgInfo?.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pkgInfo?.versionCode?.toLong()
                    }
                }.getOrNull() ?: java.io.File(apkPath).lastModified()

                val cachedSignatures = DexKitSignatureCache.loadFromFile(appInfo, versionCode)

                System.loadLibrary("dexkit")
                DexKitBridge.create(apkPath).use { dexKit ->
                    val signatures = DexKitResolver.resolve(dexKit, classLoader, cachedSignatures)
                    if (signatures.isEmpty()) {
                        throw IllegalStateException(
                            "DexKit resolution produced no signatures for ${appInfo.packageName}"
                        )
                    }
                    if (signatures != cachedSignatures) {
                        DexKitSignatureCache.saveToFile(appInfo, versionCode, signatures)
                    }
                }
                isResolved = true
            }

            installAll(module)
        }
    }

    fun installAll(module: XposedModule) {
        startupModule = module

        if (!PreferencesManager.isEnabled(PrefKeys.KEY_MASTER_ENABLED, true)) {
            return
        }

        synchronized(lock) {
            val hooks = listOf(
                HookEntry("CleanSharing", { PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true) }) { CleanSharingHook.install(module) },
                HookEntry("HidePremiumTab", { true }) { HidePremiumTabHook.install(module) },
                HookEntry("AdResponse", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { AdResponseHook.install(module) },
                HookEntry("NetworkAdBlock", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { NetworkAdBlockHook.install(module) },
                HookEntry("BrandAds", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { BrandAdsHook.install(module) },
                HookEntry("PlayerAdCards", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { PlayerAdCardsHook.install(module) },
                HookEntry("ContextMenuAdBlock", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { ContextMenuAdBlockHook.install(module) },
                HookEntry("Pendragon", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { PendragonHook.install(module) },
                HookEntry("ContentFilter", { true }) { ContentFilterHook.install(module) },
                HookEntry("ClientToken", { true }) { ClientTokenHook.install(module, startupClassLoader) },
                HookEntry("SpotifyAmoled", { PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_AMOLED, false) }) { SpotifyAmoledHook.install(module) },
                HookEntry("SpotifyMonet", { PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_MONET, false) }) { SpotifyMonetHook.install(module) },
                HookEntry("SpotifyRoundyUI", { PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_ROUNDY_UI, false) }) { SpotifyRoundyUIHook.install(module) },
                HookEntry("DisableSmartShuffle", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { DisableSmartShuffleHook.install(module) },
                HookEntry("ProductState", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { ProductStateHook.install(module) },
                HookEntry("PlayerRestrictions", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { PlayerRestrictionsHook.install(module) },
                HookEntry("OnDemandInFree", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { OnDemandInFreeHook.install(module) },
                HookEntry("PlayerShuffle", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { PlayerShuffleHook.install(module) },
                HookEntry("ForceLocalPlayback", { PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true) }) { ForceLocalPlaybackHook.install(module) },
                HookEntry("ArtworkSeek", { true }) { ArtworkSeekHook.install(module) },
                HookEntry("Lyrics", { true }) { LyricsHook.install(module) },
            )

            val failures = mutableListOf<String>()
            for (hook in hooks) {
                if (hook.isEnabled() && installedHooks.add(hook.name)) {
                    try {
                        hook.install()
                        Log.i(TAG, "Installed hook: ${hook.name}")
                    } catch (t: Throwable) {
                        installedHooks.remove(hook.name)
                        failures += hook.name
                        Log.e(TAG, "Failed installing hook ${hook.name}", t)
                    }
                }
            }
            if (failures.isNotEmpty()) {
                throw IllegalStateException("Hook installation failed: ${failures.joinToString()}")
            }
        }
    }
}
