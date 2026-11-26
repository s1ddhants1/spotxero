package app.spotxero.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import app.spotxero.R
import app.spotxero.SpotXeroApplication
import app.spotxero.utils.core.CommonUtils
import org.json.JSONObject
import org.lsposed.lspatch.IXposedServicePull
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

object LSPatchHelper {

    private const val TAG = "SpotXero.LSPatch"
    const val ACTION_REQUEST_PUSH = "org.lsposed.lspatch.action.REQUEST_PUSH"

    @Volatile
    private var lastRequestTime = 0L

    data class TargetStatus(
        val isInstalled: Boolean,
        val isPatched: Boolean,
        val isModuleEmbedded: Boolean,
        val useManager: Boolean? = null
    )

    data class BannerEvaluation(
        val isConnected: Boolean,
        val isInjectable: Boolean,
        val frameworkName: String,
        val frameworkVersion: String,
        val titleRes: Int,
        val titleArgs: List<String> = emptyList(),
        val descRes: Int,
        val descArgs: List<String> = emptyList(),
        val isIntegrated: Boolean = false,
        val canRequestScope: Boolean = false
    )

    fun requestServicePush(context: Context) {
        val currentFramework = SpotXeroApplication.service?.frameworkName.orEmpty()
        if (currentFramework.isNotEmpty() && !currentFramework.contains("LSPatch", ignoreCase = true)) {
            return
        }
        runCatching {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRequestTime < 3000L) {
                return
            }
            lastRequestTime = now

            val appContext = context.applicationContext ?: context
            val intent = Intent(ACTION_REQUEST_PUSH)
            val resolveInfos = appContext.packageManager.queryIntentServices(intent, 0)
            if (resolveInfos.isEmpty()) {
                return
            }
            for (info in resolveInfos) {
                val serviceIntent = Intent(intent).apply {
                    component = ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
                }
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        try {
                            val pullService = IXposedServicePull.Stub.asInterface(service)
                            pullService?.requestPush()
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed calling requestPush on $name", t)
                        } finally {
                            runCatching { appContext.unbindService(this) }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                runCatching {
                    appContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                }
            }
        }
    }

    fun inspectTargetApp(
        context: Context,
        packageNames: List<String> = CommonUtils.SUPPORTED_PACKAGES
    ): TargetStatus {
        val pm = context.packageManager
        for (pkg in packageNames) {
            val appInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(
                        pkg,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                }
            }.getOrNull() ?: continue

            val hasMeta = appInfo.metaData?.containsKey("lspatch") == true
            val hasFactory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appInfo.appComponentFactory?.contains("lspatch", ignoreCase = true) == true
            } else {
                false
            }

            var hasLspAsset = false
            var isEmbedded = false
            var useManager: Boolean? = null

            val apkPaths = mutableListOf<String>()
            appInfo.sourceDir?.let { apkPaths.add(it) }
            appInfo.splitSourceDirs?.let { apkPaths.addAll(it) }

            for (apkPath in apkPaths) {
                runCatching {
                    ZipFile(apkPath).use { zip ->
                        val configEntry = zip.getEntry("assets/lspatch/config.json")
                        val originEntry = zip.getEntry("assets/lspatch/origin.apk")
                        val metaEntry = zip.getEntry("assets/lspatch/metaloader.dex")
                        if (configEntry != null || originEntry != null || metaEntry != null) {
                            hasLspAsset = true
                        }

                        val moduleEntry = zip.getEntry("assets/lspatch/modules/app.spotxero.apk")
                            ?: zip.entries().asSequence().firstOrNull {
                                it.name.startsWith("assets/lspatch/modules/") &&
                                    it.name.contains("spotxero", ignoreCase = true)
                            }
                        if (moduleEntry != null) {
                            isEmbedded = true
                        }

                        if (configEntry != null && useManager == null) {
                            runCatching {
                                val configText = zip.getInputStream(configEntry).bufferedReader().readText()
                                val json = JSONObject(configText)
                                useManager = json.optBoolean("useManager", true)
                            }
                        }
                    }
                }
            }

            if (useManager == null && hasMeta) {
                runCatching {
                    val b64 = appInfo.metaData?.getString("lspatch")
                    if (!b64.isNullOrBlank()) {
                        val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
                        val json = JSONObject(jsonStr)
                        useManager = json.optBoolean("useManager", true)
                    }
                }
            }

            val isPatched = hasMeta || hasFactory || hasLspAsset
            return TargetStatus(
                isInstalled = true,
                isPatched = isPatched,
                isModuleEmbedded = isEmbedded,
                useManager = useManager
            )
        }

        return TargetStatus(
            isInstalled = false,
            isPatched = false,
            isModuleEmbedded = false,
            useManager = null
        )
    }

    fun isLSPatched(context: Context): Boolean {
        val appInfo = context.applicationInfo
        val hasMeta = appInfo.metaData?.containsKey("lspatch") == true
        val hasFactory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appInfo.appComponentFactory?.contains("lspatch", ignoreCase = true) == true
        } else false
        if (hasMeta || hasFactory) return true
        val pm = context.packageManager
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        return installer?.contains("lspatch", ignoreCase = true) == true
    }

    fun isIntegratedMode(
        context: Context? = null,
        remotePrefsAvailable: Boolean = false,
        targetStatusProvider: () -> TargetStatus = {
            if (context != null) inspectTargetApp(context) else TargetStatus(isInstalled = false, isPatched = false, isModuleEmbedded = false)
        }
    ): Boolean {
        if (context != null) {
            val metaResult = runCatching {
                val pm = context.packageManager
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(
                        context.packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                }
                val meta = appInfo.metaData
                val b64 = meta?.getString("lspatch")
                if (!b64.isNullOrBlank()) {
                    val jsonStr = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    if (json.has("useManager")) {
                        return@runCatching !json.optBoolean("useManager", true)
                    }
                }
                null
            }.getOrNull()
            if (metaResult != null) return metaResult
        }

        val status = targetStatusProvider()
        if (status.useManager != null) {
            return !status.useManager
        }
        if (status.isModuleEmbedded) {
            return true
        }

        return !remotePrefsAvailable
    }

    fun evaluateFrameworkStatus(
        context: Context,
        frameworkName: String?,
        frameworkVersion: String?,
        scope: List<String>?,
        isServiceBound: Boolean,
        packageNames: List<String> = CommonUtils.SUPPORTED_PACKAGES
    ): BannerEvaluation = evaluateFrameworkStatus(
        frameworkName = frameworkName,
        frameworkVersion = frameworkVersion,
        scope = scope,
        isServiceBound = isServiceBound,
        targetStatus = inspectTargetApp(context, packageNames),
        packageNames = packageNames
    )

    fun evaluateFrameworkStatus(
        context: Context,
        isModuleActive: Boolean,
        packageNames: List<String> = CommonUtils.SUPPORTED_PACKAGES
    ): BannerEvaluation = evaluateFrameworkStatus(
        isModuleActive = isModuleActive,
        targetStatus = inspectTargetApp(context, packageNames)
    )

    fun evaluateFrameworkStatus(
        isModuleActive: Boolean,
        targetStatus: TargetStatus
    ): BannerEvaluation {
        if (targetStatus.isInstalled && targetStatus.isPatched && (targetStatus.isModuleEmbedded || targetStatus.useManager == false)) {
            return BannerEvaluation(
                isConnected = true,
                isInjectable = true,
                frameworkName = "LSPatch",
                frameworkVersion = "(Embedded)",
                titleRes = R.string.framework_lspatch_embedded_active_title,
                descRes = R.string.framework_lspatch_embedded_active_desc,
                isIntegrated = true
            )
        }

        if (isModuleActive) {
            return BannerEvaluation(
                isConnected = true,
                isInjectable = true,
                frameworkName = "Xposed",
                frameworkVersion = "(Legacy)",
                titleRes = R.string.framework_active_title_dynamic,
                titleArgs = listOf("Xposed"),
                descRes = R.string.framework_active_desc,
                descArgs = listOf("Xposed", "(Legacy)"),
                isIntegrated = false
            )
        }

        if (!targetStatus.isInstalled) {
            return BannerEvaluation(
                isConnected = false,
                isInjectable = false,
                frameworkName = "",
                frameworkVersion = "",
                titleRes = R.string.framework_spotify_not_installed_title,
                descRes = R.string.framework_spotify_not_installed_desc,
                isIntegrated = false
            )
        }

        return BannerEvaluation(
            isConnected = false,
            isInjectable = false,
            frameworkName = "",
            frameworkVersion = "",
            titleRes = R.string.framework_inactive_title,
            descRes = R.string.framework_inactive_desc,
            isIntegrated = false
        )
    }

    fun evaluateFrameworkStatus(
        frameworkName: String?,
        frameworkVersion: String?,
        scope: List<String>?,
        isServiceBound: Boolean,
        targetStatus: TargetStatus,
        packageNames: List<String> = CommonUtils.SUPPORTED_PACKAGES
    ): BannerEvaluation {
        if (targetStatus.isInstalled && targetStatus.isPatched && (targetStatus.isModuleEmbedded || targetStatus.useManager == false)) {
            return BannerEvaluation(
                isConnected = true,
                isInjectable = true,
                frameworkName = "LSPatch",
                frameworkVersion = "(Embedded)",
                titleRes = R.string.framework_lspatch_embedded_active_title,
                descRes = R.string.framework_lspatch_embedded_active_desc,
                isIntegrated = true
            )
        }

        if (isServiceBound) {
            val name = frameworkName ?: "Xposed"
            val version = frameworkVersion ?: ""
            val isLSPatch = name.contains("LSPatch", ignoreCase = true)

            if (isLSPatch) {
                if (!targetStatus.isInstalled) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_spotify_not_installed_title,
                        descRes = R.string.framework_spotify_not_installed_desc,
                        isIntegrated = false
                    )
                }
                if (!targetStatus.isPatched) {
                    return BannerEvaluation(
                        isConnected = false,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_inactive_title,
                        descRes = R.string.framework_inactive_desc,
                        isIntegrated = false
                    )
                }

                val currentScope = scope ?: emptyList()
                val isInScope = currentScope.any { it in packageNames }

                if (!isInScope) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = R.string.framework_lspatch_not_in_scope_title,
                        descRes = R.string.framework_lspatch_not_in_scope_desc,
                        isIntegrated = false,
                        canRequestScope = true
                    )
                }

                return BannerEvaluation(
                    isConnected = true,
                    isInjectable = true,
                    frameworkName = name,
                    frameworkVersion = version,
                    titleRes = R.string.framework_active_title_dynamic,
                    titleArgs = listOf(name),
                    descRes = R.string.framework_active_desc,
                    descArgs = listOf(name, version),
                    isIntegrated = false
                )
            } else {
                val currentScope = scope ?: emptyList()
                val isLSPosed = name.contains("LSPosed", ignoreCase = true)
                val isInScope = if (isLSPosed) {
                    currentScope.any { it in packageNames }
                } else {
                    currentScope.isEmpty() || currentScope.any { it in packageNames }
                }

                if (!isInScope) {
                    return BannerEvaluation(
                        isConnected = true,
                        isInjectable = false,
                        frameworkName = name,
                        frameworkVersion = version,
                        titleRes = if (isLSPosed) R.string.framework_lsposed_not_in_scope_title else R.string.framework_lspatch_not_in_scope_title,
                        descRes = if (isLSPosed) R.string.framework_lsposed_not_in_scope_desc else R.string.framework_lspatch_not_in_scope_desc,
                        isIntegrated = false,
                        canRequestScope = isLSPosed
                    )
                }

                return BannerEvaluation(
                    isConnected = true,
                    isInjectable = true,
                    frameworkName = name,
                    frameworkVersion = version,
                    titleRes = R.string.framework_active_title_dynamic,
                    titleArgs = listOf(name),
                    descRes = R.string.framework_active_desc,
                    descArgs = listOf(name, version),
                    isIntegrated = false
                )
            }
        } else {
            return BannerEvaluation(
                isConnected = false,
                isInjectable = false,
                frameworkName = "",
                frameworkVersion = "",
                titleRes = R.string.framework_inactive_title,
                descRes = R.string.framework_inactive_desc,
                isIntegrated = false
            )
        }
    }
}
