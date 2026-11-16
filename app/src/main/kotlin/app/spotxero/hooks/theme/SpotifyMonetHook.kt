package app.spotxero.hooks.theme

import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.View
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

object SpotifyMonetHook {

    private const val DEFAULT_SPOTIFY_GREEN = 0xFF1ED760.toInt()
    private const val DEFAULT_SPOTIFY_GREEN_DARK = 0xFF1DB954.toInt()

    private val colorCache = ConcurrentHashMap<Int, Int>()

    private fun isMonetEnabled(): Boolean =
        PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_MONET, false)

    private fun getMonetAccent(): Int {
        return runCatching {
            val sysRes = Resources.getSystem()
            val id = sysRes.getIdentifier("system_accent1_200", "color", "android")
            if (id != 0) sysRes.getColor(id, null) else DEFAULT_SPOTIFY_GREEN
        }.getOrNull() ?: DEFAULT_SPOTIFY_GREEN
    }

    private fun getMonetAccentPressed(): Int {
        return runCatching {
            val sysRes = Resources.getSystem()
            val id = sysRes.getIdentifier("system_accent1_400", "color", "android")
            if (id != 0) sysRes.getColor(id, null) else DEFAULT_SPOTIFY_GREEN_DARK
        }.getOrNull() ?: DEFAULT_SPOTIFY_GREEN_DARK
    }

    private fun isSpotifyGreen(color: Int): Boolean {
        if (color == DEFAULT_SPOTIFY_GREEN ||
            color == DEFAULT_SPOTIFY_GREEN_DARK ||
            color == 0xFF1AA34A.toInt() ||
            color == 0xFF169C46.toInt()
        ) {
            return true
        }
        val alpha = (color ushr 24) and 0xFF
        if (alpha < 0x30) return false
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return g > 110 && g > (r * 1.25).toInt() && g > (b * 1.25).toInt() && r < 100
    }

    private fun replaceGreenWithMonet(color: Int): Int {
        if (!isMonetEnabled()) return color
        colorCache[color]?.let { return it }

        val result = if (isSpotifyGreen(color)) {
            val alpha = Color.alpha(color)
            val accent = getMonetAccent()
            Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
        } else {
            color
        }

        colorCache[color] = result
        return result
    }

    fun install(module: XposedModule) {
        hookPorterDuff(module)
        hookColorStateList(module)
        hookColorParse(module)
        hookPaint(module)
        hookDrawables(module)
        hookResources(module)
        hookTypedArray(module)
        hookViewsAndOverlays(module)
    }

    private fun hookPorterDuff(module: XposedModule) {
        val ctor = runCatching {
            PorterDuffColorFilter::class.java.getConstructor(
                Int::class.javaPrimitiveType,
                PorterDuff.Mode::class.java
            )
        }.getOrNull()
        if (ctor != null) {
            module.deoptimize(ctor)
            module.hook(ctor)
                .setId("spotify_monet_porterduff_ctor")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isMonetEnabled() && color != null && isSpotifyGreen(color)) {
                        val newArgs = arrayOf(replaceGreenWithMonet(color), chain.getArg(1))
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
                    null
                }
        }
    }

    private fun hookColorStateList(module: XposedModule) {
        val method = runCatching {
            ColorStateList::class.java.getMethod(
                "getColorForState",
                IntArray::class.java,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()
        if (method != null) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_monet_csl_getcolor")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!isMonetEnabled()) return@intercept result
                    val originalColor = result as? Int ?: return@intercept result
                    val states = chain.getArg(0) as? IntArray ?: return@intercept result

                    val isPressed = states.contains(android.R.attr.state_pressed)
                    val isFocused = states.contains(android.R.attr.state_focused)
                    val isSelected = states.contains(android.R.attr.state_selected)

                    val accent = getMonetAccent()
                    val accentPressed = getMonetAccentPressed()

                    if (isSpotifyGreen(originalColor) || originalColor == accent) {
                        when {
                            isPressed || isFocused -> Color.argb(
                                77,
                                Color.red(accentPressed),
                                Color.green(accentPressed),
                                Color.blue(accentPressed)
                            )
                            isSelected -> accent
                            else -> replaceGreenWithMonet(originalColor)
                        }
                    } else {
                        result
                    }
                }
        }
    }

    private fun hookColorParse(module: XposedModule) {
        val method = runCatching {
            Color::class.java.getMethod("parseColor", String::class.java)
        }.getOrNull()
        if (method != null) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_monet_color_parse")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!isMonetEnabled()) return@intercept result
                    val color = result as? Int ?: return@intercept result
                    replaceGreenWithMonet(color)
                }
        }
    }

    private fun hookPaint(module: XposedModule) {
        val paintSetColor = runCatching {
            Paint::class.java.getMethod("setColor", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (paintSetColor != null) {
            module.deoptimize(paintSetColor)
            module.hook(paintSetColor)
                .setId("spotify_monet_paint_set_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isMonetEnabled() && color != null && isSpotifyGreen(color)) {
                        chain.proceed(arrayOf(replaceGreenWithMonet(color)))
                    } else {
                        chain.proceed()
                    }
                }
        }

        val paintSetColorLong = runCatching {
            Paint::class.java.getMethod("setColor", Long::class.javaPrimitiveType)
        }.getOrNull()
        if (paintSetColorLong != null) {
            module.deoptimize(paintSetColorLong)
            module.hook(paintSetColorLong)
                .setId("spotify_monet_paint_set_color_long")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val colorLong = chain.getArg(0) as? Long
                    if (isMonetEnabled() && colorLong != null) {
                        val argb = runCatching { Color.toArgb(colorLong) }.getOrNull()
                        if (argb != null && isSpotifyGreen(argb)) {
                            val newColor = replaceGreenWithMonet(argb)
                            chain.proceed(arrayOf(Color.pack(newColor)))
                        } else {
                            chain.proceed()
                        }
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookDrawables(module: XposedModule) {
        val gradientSetColor = runCatching {
            GradientDrawable::class.java.getMethod("setColor", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (gradientSetColor != null) {
            module.deoptimize(gradientSetColor)
            module.hook(gradientSetColor)
                .setId("spotify_monet_gradient_set_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isMonetEnabled() && color != null && isSpotifyGreen(color)) {
                        chain.proceed(arrayOf(replaceGreenWithMonet(color)))
                    } else {
                        chain.proceed()
                    }
                }
        }

        val gradientSetColors = runCatching {
            GradientDrawable::class.java.getMethod("setColors", IntArray::class.java)
        }.getOrNull()
        if (gradientSetColors != null) {
            module.deoptimize(gradientSetColors)
            module.hook(gradientSetColors)
                .setId("spotify_monet_gradient_set_colors")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val colors = chain.getArg(0) as? IntArray
                    if (isMonetEnabled() && colors != null) {
                        var changed = false
                        val newColors = colors.clone()
                        for (i in newColors.indices) {
                            if (isSpotifyGreen(newColors[i])) {
                                newColors[i] = replaceGreenWithMonet(newColors[i])
                                changed = true
                            }
                        }
                        if (changed) {
                            chain.proceed(arrayOf(newColors))
                        } else {
                            chain.proceed()
                        }
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookResources(module: XposedModule) {
        for (method in listOfNotNull(
            runCatching { Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching { Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java) }.getOrNull()
        )) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_monet_res_color_${method.parameterTypes.size}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? Int)?.let { replaceGreenWithMonet(it) } ?: result
                }
        }
    }

    private fun hookTypedArray(module: XposedModule) {
        val getColorMethod = runCatching {
            TypedArray::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
        if (getColorMethod != null) {
            module.deoptimize(getColorMethod)
            module.hook(getColorMethod)
                .setId("spotify_monet_ta_get_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? Int)?.let { replaceGreenWithMonet(it) } ?: result
                }
        }
    }

    private fun hookViewsAndOverlays(module: XposedModule) {
        val attachMethod = runCatching {
            View::class.java.getDeclaredMethod("onAttachedToWindow")
        }.getOrNull()
        if (attachMethod != null) {
            module.deoptimize(attachMethod)
            module.hook(attachMethod)
                .setId("spotify_monet_view_attached")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!isMonetEnabled()) return@intercept result

                    val view = chain.getThisObject() as? View ?: return@intercept result
                    val resName = runCatching {
                        view.resources.getResourceEntryName(view.id).lowercase()
                    }.getOrDefault("")

                    val isShadowOrFade = resName.contains("shadow") ||
                        resName.contains("fade") ||
                        resName.contains("gradient")

                    if (isShadowOrFade && view.javaClass.name == "android.view.View") {
                        view.visibility = View.GONE
                        view.layoutParams?.let {
                            it.width = 0
                            it.height = 0
                        }
                    }

                    if (view.javaClass.name.contains("RecyclerView")) {
                        view.isHorizontalFadingEdgeEnabled = false
                        view.isVerticalFadingEdgeEnabled = false
                    }

                    result
                }
        }
    }
}
