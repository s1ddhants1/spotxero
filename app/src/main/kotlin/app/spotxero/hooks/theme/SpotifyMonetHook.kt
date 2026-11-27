package app.spotxero.hooks.theme

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
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

    fun isEnabled(): Boolean =
        PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_MONET, false)

    private fun getMonetAccent(): Int =
        runCatching {
            val sysRes = Resources.getSystem()
            val id = sysRes.getIdentifier("system_accent1_200", "color", "android")
            if (id != 0) sysRes.getColor(id, null) else DEFAULT_SPOTIFY_GREEN
        }.getOrNull() ?: DEFAULT_SPOTIFY_GREEN

    private fun getMonetAccentPressed(): Int =
        runCatching {
            val sysRes = Resources.getSystem()
            val id = sysRes.getIdentifier("system_accent1_400", "color", "android")
            if (id != 0) sysRes.getColor(id, null) else DEFAULT_SPOTIFY_GREEN_DARK
        }.getOrNull() ?: DEFAULT_SPOTIFY_GREEN_DARK

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

    fun mapColor(color: Int): Int {
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
        if (!isEnabled()) return
        ThemeColorPipeline.installShared(module)
        hookColorFilters(module)
        hookColorStateList(module)
        hookColorParse(module)
        hookPaintSetArgb(module)
        hookViewsAndOverlays(module)
    }

    private fun hookColorFilters(module: XposedModule) {
        val porterDuffCtor = runCatching {
            PorterDuffColorFilter::class.java.getConstructor(Int::class.javaPrimitiveType, PorterDuff.Mode::class.java)
        }.getOrNull()
        if (porterDuffCtor != null) {
            module.deoptimize(porterDuffCtor)
            module.hook(porterDuffCtor)
                .setId("spotify_monet_porterduff_ctor")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isEnabled() && color != null && isSpotifyGreen(color)) {
                        chain.proceed(arrayOf(mapColor(color), chain.getArg(1)))
                    } else {
                        chain.proceed()
                    }
                    null
                }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val blendModeCtor = runCatching {
                BlendModeColorFilter::class.java.getConstructor(Int::class.javaPrimitiveType, BlendMode::class.java)
            }.getOrNull()
            if (blendModeCtor != null) {
                module.deoptimize(blendModeCtor)
                module.hook(blendModeCtor)
                    .setId("spotify_monet_blendmode_cf_ctor")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val color = chain.getArg(0) as? Int
                        if (isEnabled() && color != null && isSpotifyGreen(color)) {
                            chain.proceed(arrayOf(mapColor(color), chain.getArg(1)))
                        } else {
                            chain.proceed()
                        }
                        null
                    }
            }
        }
    }

    private fun hookColorStateList(module: XposedModule) {
        val method = runCatching {
            ColorStateList::class.java.getMethod("getColorForState", IntArray::class.java, Int::class.javaPrimitiveType)
        }.getOrNull() ?: return

        module.deoptimize(method)
        module.hook(method)
            .setId("spotify_monet_csl_getcolor")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@intercept result
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
                        else -> mapColor(originalColor)
                    }
                } else {
                    result
                }
            }
    }

    private fun hookColorParse(module: XposedModule) {
        val method = runCatching {
            Color::class.java.getMethod("parseColor", String::class.java)
        }.getOrNull() ?: return

        module.deoptimize(method)
        module.hook(method)
            .setId("spotify_monet_color_parse")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@intercept result
                val color = result as? Int ?: return@intercept result
                mapColor(color)
            }
    }

    private fun hookPaintSetArgb(module: XposedModule) {
        val paintSetArgb = runCatching {
            Paint::class.java.getMethod(
                "setARGB",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull() ?: return

        module.deoptimize(paintSetArgb)
        module.hook(paintSetArgb)
            .setId("spotify_monet_paint_set_argb")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val a = chain.getArg(0) as? Int
                val r = chain.getArg(1) as? Int
                val g = chain.getArg(2) as? Int
                val b = chain.getArg(3) as? Int
                if (isEnabled() && a != null && r != null && g != null && b != null) {
                    val color = Color.argb(a, r, g, b)
                    if (isSpotifyGreen(color)) {
                        val mapped = mapColor(color)
                        return@intercept chain.proceed(
                            arrayOf(
                                Color.alpha(mapped),
                                Color.red(mapped),
                                Color.green(mapped),
                                Color.blue(mapped)
                            )
                        )
                    }
                }
                chain.proceed()
            }
    }

    private fun hookViewsAndOverlays(module: XposedModule) {
        val attachMethod = runCatching {
            View::class.java.getDeclaredMethod("onAttachedToWindow")
        }.getOrNull() ?: return

        module.deoptimize(attachMethod)
        module.hook(attachMethod)
            .setId("spotify_monet_view_attached")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (!isEnabled()) return@intercept result

                val view = chain.getThisObject() as? View ?: return@intercept result
                val resName = if (view.id > 0) runCatching {
                    view.resources.getResourceEntryName(view.id).lowercase()
                }.getOrDefault("") else ""

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
