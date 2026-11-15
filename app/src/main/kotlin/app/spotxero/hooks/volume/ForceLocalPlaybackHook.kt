package app.spotxero.hooks.volume

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

object ForceLocalPlaybackHook {

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_LOCAL_PLAYBACK, true)) {
            return
        }

        val method = runCatching {
            MediaSession::class.java.getDeclaredMethod(
                "setPlaybackToRemote",
                VolumeProvider::class.java
            )
        }.getOrNull() ?: return

        module.deoptimize(method)
        module.hook(method)
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
}
