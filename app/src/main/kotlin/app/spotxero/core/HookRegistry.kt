package app.spotxero.core

import android.content.pm.ApplicationInfo
import app.spotxero.hooks.ads.AdResponseHook
import app.spotxero.hooks.ads.AdSkipHook
import app.spotxero.hooks.ads.BrandAdsHook
import app.spotxero.hooks.ads.PlayerAdCardsHook
import app.spotxero.hooks.content.HidePodcastsHook
import app.spotxero.hooks.navigation.HidePremiumTabHook
import app.spotxero.hooks.player.DisableSmartShuffleHook
import app.spotxero.hooks.player.OnDemandInFreeHook
import app.spotxero.hooks.player.PlayerRestrictionsHook
import app.spotxero.hooks.player.PlayerShuffleHook
import app.spotxero.hooks.player.ProductStateHook
import app.spotxero.hooks.sharing.CleanSharingHook
import app.spotxero.hooks.theme.SpotifyAmoledHook
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge

object HookRegistry {

    fun resolveAndInstall(module: XposedModule, appInfo: ApplicationInfo, classLoader: ClassLoader) {
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

        installAll(module, classLoader)
    }

    private fun installAll(module: XposedModule, classLoader: ClassLoader) {
        val hooks = listOf(
            "CleanSharing" to { CleanSharingHook.install(module) },
            "HidePremiumTab" to { HidePremiumTabHook.install(module) },
            "AdSkip" to { AdSkipHook.install(module, classLoader) },
            "AdResponse" to { AdResponseHook.install(module) },
            "BrandAds" to { BrandAdsHook.install(module) },
            "PlayerAdCards" to { PlayerAdCardsHook.install(module) },
            "HidePodcasts" to { HidePodcastsHook.install(module) },
            "SpotifyAmoled" to { SpotifyAmoledHook.install(module) },
            "DisableSmartShuffle" to { DisableSmartShuffleHook.install(module) },
            "ProductState" to { ProductStateHook.install(module) },
            "PlayerRestrictions" to { PlayerRestrictionsHook.install(module) },
            "OnDemandInFree" to { OnDemandInFreeHook.install(module) },
            "PlayerShuffle" to { PlayerShuffleHook.install(module) },
        )

        for ((_, installer) in hooks) {
            runCatching {
                installer()
            }
        }
    }
}
