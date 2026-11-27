package app.spotxero.hooks.volume

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.view.KeyEvent
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object ForceLocalPlaybackHook {

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)) {
            return
        }

        val sessionMethod = runCatching {
            MediaSession::class.java.getDeclaredMethod(
                "setPlaybackToRemote",
                VolumeProvider::class.java
            )
        }.getOrNull()

        if (sessionMethod != null) {
            module.deoptimize(sessionMethod)
            module.hook(sessionMethod)
                .setId("force_local_playback")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)) {
                        val session = chain.getThisObject() as? MediaSession
                        if (session != null) {
                            val audioAttributes = AudioAttributes.Builder()
                                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                                .build()
                            session.setPlaybackToLocal(audioAttributes)
                            return@intercept null
                        }
                    }
                    chain.proceed()
                }
        }

        val activityHandler = DexKitResolver.require<Method>(DexKitResolver.KEY_ACTIVITY_VOLUME_KEY_HANDLER)
        module.deoptimize(activityHandler)
        module.hook(activityHandler)
            .setId("force_local_activity_volume")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)) {
                    val keyCode = chain.getArg(0) as? Int ?: (chain.getArg(1) as? KeyEvent)?.keyCode ?: -1
                    if (isVolumeKey(keyCode)) {
                        return@intercept false
                    }
                }
                chain.proceed()
            }

        val volumeHandler = DexKitResolver.require<Method>(DexKitResolver.KEY_VOLUME_KEY_HANDLER)
        module.deoptimize(volumeHandler)
        module.hook(volumeHandler)
            .setId("force_local_volume_handler")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)) {
                    val keyEvent = chain.getArg(0) as? KeyEvent
                    if (keyEvent != null && isVolumeKey(keyEvent.keyCode)) {
                        return@intercept false
                    }
                }
                chain.proceed()
            }
    }

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
}
