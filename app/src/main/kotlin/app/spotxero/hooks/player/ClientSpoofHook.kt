package app.spotxero.hooks.player

import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

object ClientSpoofHook {

    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val IOS_USER_AGENT = "Spotify/9.1.94 iOS/27.2 (iPhone18,1)"
    private const val IOS_HARDWARE_MODEL = "iPhone18,1"
    private const val IOS_CLIENT_VERSION = "iphone-9.1.94.1929.g200011c"

    fun install(module: XposedModule, classLoader: ClassLoader) {
        installApplicationScopeHook(module, classLoader)
        installLoginConfigHook(module, classLoader)
    }

    private fun installApplicationScopeHook(module: XposedModule, classLoader: ClassLoader) {
        val classNames = listOf(
            "com.spotify.connectivity.ApplicationScopeConfiguration",
            "com.spotify.core.coreimpl.ApplicationScopeConfiguration"
        )

        for (className in classNames) {
            val appScopeClass = runCatching {
                classLoader.loadClass(className)
            }.getOrNull() ?: continue

            val setClientIdMethod = runCatching {
                appScopeClass.getDeclaredMethod("setClientId", String::class.java)
            }.getOrNull()

            if (setClientIdMethod != null) {
                module.deoptimize(setClientIdMethod)
                module.hook(setClientIdMethod)
                    .setId("client_spoof_app_scope_client_id_${appScopeClass.simpleName}")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (PreferencesManager.isEnabled(PrefKeys.KEY_SPOOF_CLIENT, false)) {
                            chain.args[0] = IOS_CLIENT_ID
                        }
                        chain.proceed()
                    }
            }

            val setUserAgentMethod = runCatching {
                appScopeClass.getDeclaredMethod("setDefaultHTTPUserAgent", String::class.java)
            }.getOrNull()

            if (setUserAgentMethod != null) {
                module.deoptimize(setUserAgentMethod)
                module.hook(setUserAgentMethod)
                    .setId("client_spoof_app_scope_user_agent_${appScopeClass.simpleName}")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (PreferencesManager.isEnabled(PrefKeys.KEY_SPOOF_CLIENT, false)) {
                            chain.args[0] = IOS_USER_AGENT
                        }
                        chain.proceed()
                    }
            }
        }
    }

    private fun installLoginConfigHook(module: XposedModule, classLoader: ClassLoader) {
        val loginConfigClass = runCatching {
            classLoader.loadClass("com.spotify.connectivity.auth.NativeLoginControllerConfiguration")
        }.getOrNull() ?: return

        val setClientIdMethod = runCatching {
            loginConfigClass.getDeclaredMethod("setClientID", String::class.java)
        }.getOrNull()

        if (setClientIdMethod != null) {
            module.deoptimize(setClientIdMethod)
            module.hook(setClientIdMethod)
                .setId("client_spoof_login_client_id")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_SPOOF_CLIENT, false)) {
                        chain.args[0] = IOS_CLIENT_ID
                    }
                    chain.proceed()
                }
        }

        val setHwModelMethod = runCatching {
            loginConfigClass.getDeclaredMethod("setDeviceHardwareModel", String::class.java)
        }.getOrNull()

        if (setHwModelMethod != null) {
            module.deoptimize(setHwModelMethod)
            module.hook(setHwModelMethod)
                .setId("client_spoof_login_hw_model")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_SPOOF_CLIENT, false)) {
                        chain.args[0] = IOS_HARDWARE_MODEL
                    }
                    chain.proceed()
                }
        }

        val setVersionLongMethod = runCatching {
            loginConfigClass.getDeclaredMethod("setVersionLong", String::class.java)
        }.getOrNull()

        if (setVersionLongMethod != null) {
            module.deoptimize(setVersionLongMethod)
            module.hook(setVersionLongMethod)
                .setId("client_spoof_login_version_long")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_SPOOF_CLIENT, false)) {
                        chain.args[0] = IOS_CLIENT_VERSION
                    }
                    chain.proceed()
                }
        }
    }
}
