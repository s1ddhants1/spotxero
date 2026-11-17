package app.spotxero.core

import android.content.pm.ApplicationInfo
import app.spotxero.hooks.ads.AdResponseHook
import app.spotxero.hooks.ads.BrandAdsHook
import app.spotxero.hooks.ads.ContextMenuAdBlockHook
import app.spotxero.hooks.ads.NetworkAdBlockHook
import app.spotxero.hooks.ads.PendragonHook
import app.spotxero.hooks.ads.PlayerAdCardsHook
import app.spotxero.hooks.content.ContentFilterHook
import app.spotxero.hooks.lyrics.LyricsHook
import app.spotxero.hooks.navigation.HidePremiumTabHook
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

object HookRegistry {

    private data class HookEntry(
        val name: String,
        val isEnabled: () -> Boolean,
        val install: () -> Unit
    )

    fun resolveAndInstall(module: XposedModule, appInfo: ApplicationInfo, classLoader: ClassLoader) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_MASTER_ENABLED, true)) {
            return
        }

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
            if (signatures.isNotEmpty() && signatures != cachedSignatures) {
                DexKitSignatureCache.saveToFile(appInfo, versionCode, signatures)
            }
        }

        installAll(module)
    }

    fun installAll(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_MASTER_ENABLED, true)) {
            return
        }

        val hooks = listOf(
            HookEntry("CleanSharing", { PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true) }) { CleanSharingHook.install(module) },
            HookEntry("HidePremiumTab", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { HidePremiumTabHook.install(module) },
            HookEntry("AdResponse", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { AdResponseHook.install(module) },
            HookEntry("NetworkAdBlock", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { NetworkAdBlockHook.install(module) },
            HookEntry("BrandAds", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { BrandAdsHook.install(module) },
            HookEntry("PlayerAdCards", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { PlayerAdCardsHook.install(module) },
            HookEntry("ContextMenuAdBlock", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { ContextMenuAdBlockHook.install(module) },
            HookEntry("Pendragon", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { PendragonHook.install(module) },
            HookEntry("ContentFilter", {
                PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PODCASTS, false) ||
                    PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_AUDIOBOOKS, false)
            }) { ContentFilterHook.install(module) },
            HookEntry("SpotifyAmoled", { PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_AMOLED, false) }) { SpotifyAmoledHook.install(module) },
            HookEntry("SpotifyMonet", { PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_MONET, false) }) { SpotifyMonetHook.install(module) },
            HookEntry("SpotifyRoundyUI", { PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_ROUNDY_UI, false) }) { SpotifyRoundyUIHook.install(module) },
            HookEntry("DisableSmartShuffle", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { DisableSmartShuffleHook.install(module) },
            HookEntry("ProductState", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { ProductStateHook.install(module) },
            HookEntry("PlayerRestrictions", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { PlayerRestrictionsHook.install(module) },
            HookEntry("OnDemandInFree", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { OnDemandInFreeHook.install(module) },
            HookEntry("PlayerShuffle", { PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true) }) { PlayerShuffleHook.install(module) },
            HookEntry("ForceLocalPlayback", { PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true) }) { ForceLocalPlaybackHook.install(module) },
            HookEntry("Lyrics", { true }) { LyricsHook.install(module) },
        )

        for (hook in hooks) {
            if (hook.isEnabled()) {
                runCatching {
                    hook.install()
                }
            }
        }
    }
}
