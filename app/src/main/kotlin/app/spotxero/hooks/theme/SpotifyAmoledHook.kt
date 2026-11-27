package app.spotxero.hooks.theme

import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import kotlin.math.abs

object SpotifyAmoledHook {

    private val DARK_COLORS = setOf(
        0xFF121212.toInt(),
        0xFF181818.toInt(),
        0xFF191414.toInt(),
        0xFF191919.toInt(),
        0xFF1F1F1F.toInt(),
        0xFF212121.toInt(),
        0xFF242424.toInt(),
        0xFF282828.toInt(),
        0xFF292929.toInt(),
        0xFF2A2A2A.toInt(),
        0xFF333333.toInt()
    )

    fun isEnabled(): Boolean =
        PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_AMOLED, false)

    private fun isDarkColor(color: Int): Boolean {
        if (color in DARK_COLORS) return true
        val alpha = (color ushr 24) and 0xFF
        if (alpha < 0x80) return false
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return r in 1..0x35 && g in 1..0x35 && b in 1..0x35 &&
            abs(r - g) <= 8 && abs(g - b) <= 8 && abs(r - b) <= 8
    }

    fun mapColor(color: Int): Int =
        if (isDarkColor(color)) Color.BLACK else color

    fun install(module: XposedModule) {
        if (!isEnabled()) return
        ThemeColorPipeline.installShared(module)
        hookResources(module)
        hookTypedArray(module)
        hookColorDrawables(module)
        hookViews(module)
    }

    private fun hookResources(module: XposedModule) {
        for (method in listOfNotNull(
            runCatching { Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching { Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType, Resources.Theme::class.java) }.getOrNull()
        )) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_amoled_res_csl_${method.parameterTypes.size}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val csl = result as? ColorStateList
                    if (csl != null && isEnabled() && isDarkColor(csl.defaultColor)) {
                        ColorStateList.valueOf(Color.BLACK)
                    } else {
                        result
                    }
                }
        }

        for (method in listOfNotNull(
            runCatching { Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching { Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType, Resources.Theme::class.java) }.getOrNull()
        )) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_amoled_res_drawable_${method.parameterTypes.size}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (isEnabled() && result is ColorDrawable && isDarkColor(result.color)) {
                        result.color = Color.BLACK
                    }
                    result
                }
        }
    }

    private fun hookTypedArray(module: XposedModule) {
        val getCslMethod = runCatching {
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (getCslMethod != null) {
            module.deoptimize(getCslMethod)
            module.hook(getCslMethod)
                .setId("spotify_amoled_ta_get_csl")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val csl = result as? ColorStateList
                    if (csl != null && isEnabled() && isDarkColor(csl.defaultColor)) {
                        ColorStateList.valueOf(Color.BLACK)
                    } else {
                        result
                    }
                }
        }

        val getDrawableMethod = runCatching {
            TypedArray::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (getDrawableMethod != null) {
            module.deoptimize(getDrawableMethod)
            module.hook(getDrawableMethod)
                .setId("spotify_amoled_ta_get_drawable")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (isEnabled() && result is ColorDrawable && isDarkColor(result.color)) {
                        result.color = Color.BLACK
                    }
                    result
                }
        }
    }

    private fun hookColorDrawables(module: XposedModule) {
        val colorDrawableCtor = runCatching {
            ColorDrawable::class.java.getConstructor(Int::class.javaPrimitiveType)
        }.getOrNull()
        if (colorDrawableCtor != null) {
            module.deoptimize(colorDrawableCtor)
            module.hook(colorDrawableCtor)
                .setId("spotify_amoled_colordrawable_ctor")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isEnabled() && color != null && isDarkColor(color)) {
                        chain.proceed(arrayOf(Color.BLACK))
                    } else {
                        chain.proceed()
                    }
                    null
                }
        }

        val setColorMethod = runCatching {
            ColorDrawable::class.java.getMethod("setColor", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (setColorMethod != null) {
            module.deoptimize(setColorMethod)
            module.hook(setColorMethod)
                .setId("spotify_amoled_colordrawable_set_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isEnabled() && color != null && isDarkColor(color)) {
                        chain.proceed(arrayOf(Color.BLACK))
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookViews(module: XposedModule) {
        val setBgColorMethod = runCatching {
            View::class.java.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return

        module.deoptimize(setBgColorMethod)
        module.hook(setBgColorMethod)
            .setId("spotify_amoled_view_set_bg_color")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val color = chain.getArg(0) as? Int
                if (isEnabled() && color != null && isDarkColor(color)) {
                    chain.proceed(arrayOf(Color.BLACK))
                } else {
                    chain.proceed()
                }
            }
    }
}
