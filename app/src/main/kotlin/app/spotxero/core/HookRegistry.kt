package app.spotxero.core

import android.content.pm.ApplicationInfo
import android.util.Log
import app.spotxero.hooks.ads.AdManagerHook
import app.spotxero.hooks.ads.AdSkipHook
import app.spotxero.hooks.ads.BrandAdsHook
import app.spotxero.hooks.ads.PlayerAdCardsHook
import app.spotxero.hooks.ads.ProductStateOverrideHook
import app.spotxero.hooks.content.HidePodcastsHook
import app.spotxero.hooks.navigation.HidePremiumTabHook
import app.spotxero.hooks.sharing.CleanSharingHook
import app.spotxero.hooks.theme.SpotifyAmoledHook
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge

object HookRegistry {

    private const val TAG = "SpotXero.Registry"

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
        if (cachedSignatures != null) {
            module.log(Log.INFO, TAG, "Using cached DexKit signatures for version $versionCode")
        }

        val startTime = System.currentTimeMillis()

        System.loadLibrary("dexkit")
        DexKitBridge.create(apkPath).use { dexKit ->
            module.log(Log.INFO, TAG, "DexKit bridge created in ${System.currentTimeMillis() - startTime}ms")

            val resolveStart = System.currentTimeMillis()
            val signatures = DexKitResolver.resolve(dexKit, classLoader, cachedSignatures)
            module.log(Log.INFO, TAG, "Resolved ${signatures.size} signatures in ${System.currentTimeMillis() - resolveStart}ms")

            if (signatures.isNotEmpty() && signatures != cachedSignatures) {
                DexKitSignatureCache.saveToFile(appInfo, versionCode, signatures)
                module.log(Log.INFO, TAG, "Saved ${signatures.size} signatures to cache")
            }
        }

        installAll(module)
    }

    private fun installAll(module: XposedModule) {
        val hooks = listOf(
            "CleanSharing" to { CleanSharingHook.install(module) },
            "HidePremiumTab" to { HidePremiumTabHook.install(module) },
            "ProductStateOverride" to { ProductStateOverrideHook.install(module) },
            "AdManager" to { AdManagerHook.install(module) },
            "AdSkip" to { AdSkipHook.install(module) },
            "BrandAds" to { BrandAdsHook.install(module) },
            "PlayerAdCards" to { PlayerAdCardsHook.install(module) },
            "HidePodcasts" to { HidePodcastsHook.install(module) },
            "SpotifyAmoled" to { SpotifyAmoledHook.install(module) },
        )

        var installed = 0
        for ((name, installer) in hooks) {
            runCatching {
                installer()
                installed++
            }.onFailure {
                module.log(Log.ERROR, TAG, "Failed installing $name hook: ${it.message}", it)
            }
        }
        module.log(Log.INFO, TAG, "Installed $installed/${hooks.size} hooks")
    }
}
