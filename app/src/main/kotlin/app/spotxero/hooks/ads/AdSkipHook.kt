package app.spotxero.hooks.ads

import android.os.SystemClock
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.hooks.player.PlayerShuffleHook
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object AdSkipHook {

    private const val PLAYER_QUEUE_BUILDER = "com.spotify.player.model.PlayerQueue\$Builder"
    private const val AUTO_VALUE_PLAYER_STATE_BUILDER = "com.spotify.player.model.AutoValue_PlayerState\$Builder"
    private const val SET_QUEUE_COMMAND_BUILDER = "com.spotify.player.model.command.SetQueueCommand\$Builder"
    private const val AUTO_VALUE_PLAYER_QUEUE = "com.spotify.player.model.AutoValue_PlayerQueue"
    private const val AUTO_VALUE_PLAYER_STATE = "com.spotify.player.model.AutoValue_PlayerState"
    private const val PLAYER_QUEUE = "com.spotify.player.model.PlayerQueue"
    private const val PLAYER_STATE = "com.spotify.player.model.PlayerState"

    @Volatile
    private var lastSkipTimestamp: Long = 0L

    fun install(module: XposedModule, classLoader: ClassLoader? = null) {
        val cl = classLoader
            ?: DexKitResolver.get<Method>(DexKitResolver.KEY_IS_ADVERTISEMENT_CHECK)?.declaringClass?.classLoader
            ?: DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)?.declaringClass?.classLoader
            ?: return

        hookQueueBuilders(module, cl)
        hookAutoValueQueue(module, cl)
        hookAutoValueState(module, cl)
    }

    private fun hookQueueBuilders(module: XposedModule, cl: ClassLoader) {
        val playerQueueBuilderClass = runCatching { Class.forName(PLAYER_QUEUE_BUILDER, false, cl) }.getOrNull()
        val pqNextTracksMethod = playerQueueBuilderClass?.methods?.firstOrNull {
            it.name == "nextTracks" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == List::class.java
        }
        if (pqNextTracksMethod != null) {
            module.deoptimize(pqNextTracksMethod)
            module.hook(pqNextTracksMethod)
                .setId("player_queue_builder_next_tracks")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val list = chain.getArg(0) as? List<*> ?: return@intercept chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    val filtered = filterAdTracks(list)
                    if (filtered !== list) {
                        chain.proceed(arrayOf(filtered))
                    } else {
                        chain.proceed()
                    }
                }
        }

        val playerStateBuilderClass = runCatching { Class.forName(AUTO_VALUE_PLAYER_STATE_BUILDER, false, cl) }.getOrNull()
        val psNextTracksMethod = playerStateBuilderClass?.declaredMethods?.firstOrNull {
            it.name == "nextTracks" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == List::class.java
        }
        if (psNextTracksMethod != null) {
            module.deoptimize(psNextTracksMethod)
            module.hook(psNextTracksMethod)
                .setId("player_state_builder_next_tracks")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val list = chain.getArg(0) as? List<*> ?: return@intercept chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    val filtered = filterAdTracks(list)
                    if (filtered !== list) {
                        chain.proceed(arrayOf(filtered))
                    } else {
                        chain.proceed()
                    }
                }
        }

        val psAdBreakBuilderMethod = playerStateBuilderClass?.declaredMethods?.firstOrNull {
            it.name == "adBreakContext" && it.parameterCount == 1
        }
        if (psAdBreakBuilderMethod != null) {
            module.deoptimize(psAdBreakBuilderMethod)
            module.hook(psAdBreakBuilderMethod)
                .setId("player_state_builder_ad_break")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    chain.proceed(arrayOf(null))
                }
        }

        val setQueueBuilderClass = runCatching { Class.forName(SET_QUEUE_COMMAND_BUILDER, false, cl) }.getOrNull()
        val sqNextTracksMethod = setQueueBuilderClass?.methods?.firstOrNull {
            it.name == "nextTracks" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == List::class.java
        }
        if (sqNextTracksMethod != null) {
            module.deoptimize(sqNextTracksMethod)
            module.hook(sqNextTracksMethod)
                .setId("set_queue_command_builder_next_tracks")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val list = chain.getArg(0) as? List<*> ?: return@intercept chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept chain.proceed()
                    }
                    val filtered = filterAdTracks(list)
                    if (filtered !== list) {
                        chain.proceed(arrayOf(filtered))
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookAutoValueQueue(module: XposedModule, cl: ClassLoader) {
        val autoQueueClass = runCatching { Class.forName(AUTO_VALUE_PLAYER_QUEUE, false, cl) }.getOrNull()
        val autoQueueNextTracksMethod = autoQueueClass?.declaredMethods?.firstOrNull {
            it.name == "nextTracks" && it.parameterCount == 0
        }
        if (autoQueueNextTracksMethod != null) {
            module.deoptimize(autoQueueNextTracksMethod)
            module.hook(autoQueueNextTracksMethod)
                .setId("auto_value_player_queue_next_tracks")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed() as? List<*> ?: return@intercept chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    val filtered = filterAdTracks(result)
                    if (filtered === result) {
                        return@intercept result
                    }
                    toImmutableList(filtered, cl) ?: result
                }
        }
    }

    private fun hookAutoValueState(module: XposedModule, cl: ClassLoader) {
        val autoStateClass = runCatching { Class.forName(AUTO_VALUE_PLAYER_STATE, false, cl) }.getOrNull()
        val autoStateNextTracksMethod = autoStateClass?.declaredMethods?.firstOrNull {
            it.name == "nextTracks" && it.parameterCount == 0
        }
        if (autoStateNextTracksMethod != null) {
            module.deoptimize(autoStateNextTracksMethod)
            module.hook(autoStateNextTracksMethod)
                .setId("auto_value_player_state_next_tracks")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed() as? List<*> ?: return@intercept chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    val filtered = filterAdTracks(result)
                    if (filtered === result) {
                        return@intercept result
                    }
                    toImmutableList(filtered, cl) ?: result
                }
        }

        val autoStateAdBreakMethod = autoStateClass?.declaredMethods?.firstOrNull {
            it.name == "adBreakContext" && it.parameterCount == 0
        }
        if (autoStateAdBreakMethod != null) {
            module.deoptimize(autoStateAdBreakMethod)
            module.hook(autoStateAdBreakMethod)
                .setId("auto_value_player_state_ad_break")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        return@intercept result
                    }
                    getEmptyOptional(cl) ?: result
                }
        }

        val autoStateTrackMethod = autoStateClass?.declaredMethods?.firstOrNull {
            it.name == "track" && it.parameterCount == 0
        }
        if (autoStateTrackMethod != null) {
            module.deoptimize(autoStateTrackMethod)
            module.hook(autoStateTrackMethod)
                .setId("auto_value_player_state_track")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                        val track = extractTrackFromOptional(result)
                        if (isAdTrack(track)) {
                            dispatchSkipNext()
                        }
                    }
                    result
                }
        }
    }

    private fun isAdTrack(track: Any?): Boolean {
        if (track == null) return false
        val uri = runCatching {
            track.javaClass.getMethod("uri").invoke(track) as? String
        }.getOrNull()
        if (uri != null && (uri.startsWith("spotify:ad:") || uri.contains(":ad:"))) {
            return true
        }
        val provider = runCatching {
            track.javaClass.getMethod("provider").invoke(track) as? String
        }.getOrNull()
        if (provider == "ad") {
            return true
        }
        val metadata = runCatching {
            track.javaClass.getMethod("metadata").invoke(track) as? Map<*, *>
        }.getOrNull()
        if (metadata != null) {
            if (metadata["is_advertisement"] == "true") return true
            if (metadata["is_interruption"] == "true") return true
            if (metadata.containsKey("ad_id") || metadata.containsKey("ad.token")) return true
        }
        val isAdMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_IS_ADVERTISEMENT_CHECK)
        if (isAdMethod != null) {
            val result = runCatching { isAdMethod.invoke(null, track) as? Boolean }.getOrNull()
            if (result == true) return true
        }
        return false
    }

    private fun filterAdTracks(tracks: List<*>?): List<*> {
        if (tracks.isNullOrEmpty()) return tracks ?: emptyList<Any>()
        var hasAd = false
        for (item in tracks) {
            if (isAdTrack(item)) {
                hasAd = true
                break
            }
        }
        if (!hasAd) return tracks
        return tracks.filterNot { isAdTrack(it) }
    }

    private fun toImmutableList(list: List<*>, cl: ClassLoader): Any? {
        return runCatching {
            val playerQueueClass = Class.forName(PLAYER_QUEUE, false, cl)
            val builderMethod = playerQueueClass.getMethod("builder")
            val builder = builderMethod.invoke(null)
            val nextTracksMethod = builder.javaClass.methods.firstOrNull {
                it.name == "nextTracks" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == List::class.java
            }
            nextTracksMethod?.invoke(builder, list)
            val buildMethod = builder.javaClass.getMethod("build")
            val queue = buildMethod.invoke(builder)
            val getNextTracks = queue.javaClass.getMethod("nextTracks")
            getNextTracks.invoke(queue)
        }.getOrNull()
    }

    private fun getEmptyOptional(cl: ClassLoader): Any? {
        return runCatching {
            val playerStateClass = Class.forName(PLAYER_STATE, false, cl)
            val emptyField = playerStateClass.getField("EMPTY")
            val emptyState = emptyField.get(null)
            val method = emptyState.javaClass.getMethod("adBreakContext")
            method.invoke(emptyState)
        }.getOrNull()
    }

    private fun extractTrackFromOptional(optional: Any?): Any? {
        if (optional == null) return null
        val cls = optional.javaClass
        val isPresent = runCatching {
            cls.getMethod("isPresent").invoke(optional) as? Boolean
        }.getOrNull() ?: runCatching {
            cls.getMethod("c").invoke(optional) as? Boolean
        }.getOrNull() ?: false
        if (!isPresent) return null
        return runCatching {
            cls.getMethod("get").invoke(optional)
        }.getOrNull() ?: runCatching {
            cls.getMethod("b").invoke(optional)
        }.getOrNull()
    }

    private fun dispatchSkipNext() {
        val now = SystemClock.uptimeMillis()
        if (now - lastSkipTimestamp < 1000L) {
            return
        }
        lastSkipTimestamp = now
        val transport = PlayerShuffleHook.nativeTransport ?: return
        runCatching {
            val syncMethod = transport.javaClass.methods.firstOrNull {
                it.name == "callSync" &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == ByteArray::class.java
            } ?: transport.javaClass.interfaces.firstNotNullOfOrNull { iface ->
                iface.methods.firstOrNull {
                    it.name == "callSync" &&
                        it.parameterCount == 3 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == String::class.java &&
                        it.parameterTypes[2] == ByteArray::class.java
                }
            }
            if (syncMethod != null) {
                syncMethod.isAccessible = true
                syncMethod.invoke(
                    transport,
                    "spotify.player.esperanto.proto.ContextPlayer",
                    "SkipNext",
                    byteArrayOf()
                )
            }
        }
    }
}
