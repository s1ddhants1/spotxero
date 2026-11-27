package app.spotxero.core

import android.content.pm.ApplicationInfo
import android.util.Base64
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.spotxero.SpotXeroApplication
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File

object ModuleStatus {

    private val _isModuleActive = mutableStateOf(false)
    val isModuleActive: State<Boolean> = _isModuleActive

    private val _frameworkName = mutableStateOf<String?>(null)
    val frameworkName: State<String?> = _frameworkName

    fun checkStatus() {
        val service = SpotXeroApplication.service
        if (service != null) {
            _isModuleActive.value = true
            _frameworkName.value = service.frameworkName.takeIf { it.isNotBlank() } ?: detectFramework()
        } else {
            _isModuleActive.value = false
            _frameworkName.value = null
        }
    }

    fun isLspatch(
        module: XposedModule,
        appInfo: ApplicationInfo? = null,
        classLoader: ClassLoader? = null
    ): Boolean {
        val framework = runCatching { module.frameworkName }.getOrNull()
        if (!framework.isNullOrBlank() && framework.contains("lspatch", ignoreCase = true)) {
            return true
        }
        return detectFramework(classLoader, appInfo) == "LSPatch"
    }

    fun isLspatchIntegrated(
        module: XposedModule,
        appInfo: ApplicationInfo? = null,
        classLoader: ClassLoader? = null
    ): Boolean {
        if (!isLspatch(module, appInfo, classLoader)) {
            return false
        }
        val encodedConfig = appInfo?.metaData?.getString("lspatch")
        if (!encodedConfig.isNullOrBlank()) {
            val useManager = runCatching {
                val decoded = String(Base64.decode(encodedConfig, Base64.DEFAULT), Charsets.UTF_8)
                val json = JSONObject(decoded)
                if (json.has("useManager")) json.getBoolean("useManager") else null
            }.getOrNull()
            if (useManager != null) {
                return !useManager
            }
        }
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrNull() ?: return false
        return !maps.contains("org.lsposed.lspatch", ignoreCase = true)
    }

    fun detectFramework(classLoader: ClassLoader? = null, appInfo: ApplicationInfo? = null): String {
        if (appInfo?.metaData?.containsKey("lspatch") == true ||
            appInfo?.sourceDir?.contains("/lspatch/") == true ||
            appInfo?.publicSourceDir?.contains("/lspatch/") == true
        ) {
            return "LSPatch"
        }

        val knownClasses = listOf(
            "LSPatch" to listOf(
                "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub",
                "org.lsposed.lspatch.loader.LSPApplication",
                "org.lsposed.lspatch.loader.LSPatchDocumentsProvider",
                "org.lsposed.lspatch.share.remote.FrameworkInfo",
                "org.matrix.vector.impl.core.VectorServiceClient"
            ),
            "LSPosed" to listOf(
                "org.lsposed.lspd.service.ILSPManager",
                "org.lsposed.lspd.models.Module",
                "org.lsposed.lsposed.service.ILSPManager"
            ),
            "EdXposed" to listOf(
                "com.elderdrivers.riru.edxp.config.EdXpConfig",
                "org.meowcat.edxposed.manager.BuildConfig"
            ),
            "SandHook" to listOf(
                "com.swift.sandhook.SandHook"
            ),
            "TaiChi" to listOf(
                "me.weishu.exp.TaiChi"
            ),
            "VirtualXposed" to listOf(
                "io.va.exposed.XposedHelper"
            )
        )

        val targetLoader = classLoader ?: ModuleStatus::class.java.classLoader
        for ((name, classNames) in knownClasses) {
            for (className in classNames) {
                try {
                    Class.forName(className, false, targetLoader)
                    return name
                } catch (_: Throwable) {}
            }
        }

        try {
            File("/proc/self/maps").useLines { lines ->
                for (line in lines) {
                    val lower = line.lowercase()
                    when {
                        "lspatch" in lower -> return "LSPatch"
                        "lsposed" in lower -> return "LSPosed"
                        "edxp" in lower || "edxposed" in lower -> return "EdXposed"
                        "sandhook" in lower -> return "SandHook"
                        "taichi" in lower -> return "TaiChi"
                    }
                }
            }
        } catch (_: Throwable) {}

        if (System.getProperty("vxp") != null) {
            return "VirtualXposed"
        }

        return "Unknown"
    }
}
