package app.spotxero.hooks.theme

import android.content.res.Resources
import android.graphics.Color
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

object SpotifyAmoledHook {

    private val DARK_COLORS = setOf(
        0xFF121212.toInt(),
        0xFF181818.toInt(),
        0xFF191414.toInt(),
        0xFF212121.toInt(),
        0xFF242424.toInt(),
        0xFF282828.toInt(),
        0xFF2A2A2A.toInt(),
        0xFF333333.toInt()
    )

    fun install(module: XposedModule) {
        hookResourcesGetColor(module)
    }

    private fun hookResourcesGetColor(module: XposedModule) {
        val methodWithTheme = runCatching {
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java)
        }.getOrNull()

        if (methodWithTheme != null) {
            module.deoptimize(methodWithTheme)
            module.hook(methodWithTheme)
                .setId("spotify_amoled_get_color_theme")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_AMOLED, false)) {
                        return@intercept result
                    }
                    val color = result as? Int ?: return@intercept result
                    if (color in DARK_COLORS) Color.BLACK else color
                }
        }

        @Suppress("DEPRECATION")
        val methodDeprecated = runCatching {
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType)
        }.getOrNull()

        if (methodDeprecated != null) {
            module.deoptimize(methodDeprecated)
            module.hook(methodDeprecated)
                .setId("spotify_amoled_get_color_deprecated")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_AMOLED, false)) {
                        return@intercept result
                    }
                    val color = result as? Int ?: return@intercept result
                    if (color in DARK_COLORS) Color.BLACK else color
                }
        }
    }
}
