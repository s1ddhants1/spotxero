package app.spotxero.core

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.spotxero.SpotXeroApplication
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

    fun detectFramework(): String {
        val knownClasses = listOf(
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

        for ((name, classNames) in knownClasses) {
            for (className in classNames) {
                try {
                    Class.forName(className)
                    return name
                } catch (_: Throwable) {}
            }
        }

        try {
            File("/proc/self/maps").useLines { lines ->
                for (line in lines) {
                    val lower = line.lowercase()
                    when {
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
