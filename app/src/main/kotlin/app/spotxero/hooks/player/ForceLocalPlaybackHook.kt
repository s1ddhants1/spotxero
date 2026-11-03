package app.spotxero.hooks.player

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object ForceLocalPlaybackHook {

    fun install(module: XposedModule, classLoader: ClassLoader) {
        installMediaSessionHook(module)
        installMediaSessionCompatHook(module, classLoader)
        installResolvedCallerHook(module)
    }

    private fun installMediaSessionHook(module: XposedModule) {
        val target = runCatching {
            MediaSession::class.java.getDeclaredMethod("setPlaybackToRemote", VolumeProvider::class.java)
        }.getOrNull() ?: return

        module.deoptimize(target)
        module.hook(target)
            .setId("force_local_playback_fwk")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)) {
                    return@intercept chain.proceed()
                }
                val session = chain.thisObject as? MediaSession ?: return@intercept chain.proceed()
                val attributes = AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build()
                session.setPlaybackToLocal(attributes)
                null
            }
    }

    private fun installMediaSessionCompatHook(module: XposedModule, classLoader: ClassLoader) {
        val candidates = listOf(
            "androidx.media3.session.legacy.MediaSessionCompat",
            "android.support.v4.media.session.MediaSessionCompat"
        )

        for (className in candidates) {
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: continue
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "setPlaybackToRemote" && it.parameterCount == 1
            } ?: continue

            val localMethod = clazz.declaredMethods.firstOrNull {
                it.name == "setPlaybackToLocal" && it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }

            module.deoptimize(method)
            module.hook(method)
                .setId("force_local_playback_compat_${clazz.simpleName}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)) {
                        return@intercept chain.proceed()
                    }
                    val sessionCompat = chain.thisObject
                    if (sessionCompat != null && localMethod != null) {
                        runCatching {
                            localMethod.invoke(sessionCompat, AudioManager.STREAM_MUSIC)
                        }
                    }
                    null
                }
        }
    }

    private fun installResolvedCallerHook(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_FORCE_LOCAL_PLAYBACK) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("force_local_playback_caller")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)) {
                    return@intercept chain.proceed()
                }
                chain.proceed()
            }
    }
}

