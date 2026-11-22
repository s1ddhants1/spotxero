package app.spotxero.hooks.theme

import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
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

    private fun isAmoledEnabled(): Boolean =
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

    private fun mapColor(color: Int): Int {
        if (!isAmoledEnabled()) return color
        return if (isDarkColor(color)) Color.BLACK else color
    }

    fun install(module: XposedModule) {
        if (!isAmoledEnabled()) {
            return
        }
        hookResources(module)
        hookTypedArray(module)
        hookDrawables(module)
        hookViews(module)
        hookPaint(module)
        hookCanvas(module)
    }

    private fun hookResources(module: XposedModule) {
        for (method in listOfNotNull(
            runCatching { Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching { Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java) }.getOrNull()
        )) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_amoled_res_color_${method.parameterTypes.size}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? Int)?.let { mapColor(it) } ?: result
                }
        }

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
                    if (csl != null && isAmoledEnabled() && isDarkColor(csl.defaultColor)) {
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
                    if (isAmoledEnabled() && result is ColorDrawable && isDarkColor(result.color)) {
                        result.color = Color.BLACK
                    }
                    result
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
                .setId("spotify_amoled_ta_get_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? Int)?.let { mapColor(it) } ?: result
                }
        }

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
                    if (csl != null && isAmoledEnabled() && isDarkColor(csl.defaultColor)) {
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
                    if (isAmoledEnabled() && result is ColorDrawable && isDarkColor(result.color)) {
                        result.color = Color.BLACK
                    }
                    result
                }
        }
    }

    private fun hookDrawables(module: XposedModule) {
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
                    if (isAmoledEnabled() && color != null && isDarkColor(color)) {
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
                    if (isAmoledEnabled() && color != null && isDarkColor(color)) {
                        chain.proceed(arrayOf(Color.BLACK))
                    } else {
                        chain.proceed()
                    }
                }
        }

        val gradientSetColor = runCatching {
            GradientDrawable::class.java.getMethod("setColor", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (gradientSetColor != null) {
            module.deoptimize(gradientSetColor)
            module.hook(gradientSetColor)
                .setId("spotify_amoled_gradient_set_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isAmoledEnabled() && color != null && isDarkColor(color)) {
                        chain.proceed(arrayOf(Color.BLACK))
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
                .setId("spotify_amoled_gradient_set_colors")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val colors = chain.getArg(0) as? IntArray
                    if (isAmoledEnabled() && colors != null) {
                        var changed = false
                        val newColors = colors.clone()
                        for (i in newColors.indices) {
                            if (isDarkColor(newColors[i])) {
                                newColors[i] = Color.BLACK
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

    private fun hookViews(module: XposedModule) {
        val setBgColorMethod = runCatching {
            View::class.java.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (setBgColorMethod != null) {
            module.deoptimize(setBgColorMethod)
            module.hook(setBgColorMethod)
                .setId("spotify_amoled_view_set_bg_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isAmoledEnabled() && color != null && isDarkColor(color)) {
                        chain.proceed(arrayOf(Color.BLACK))
                    } else {
                        chain.proceed()
                    }
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
                .setId("spotify_amoled_paint_set_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isAmoledEnabled() && color != null && isDarkColor(color)) {
                        chain.proceed(arrayOf(Color.BLACK))
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
                .setId("spotify_amoled_paint_set_color_long")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val colorLong = chain.getArg(0) as? Long
                    if (isAmoledEnabled() && colorLong != null) {
                        val argb = runCatching { Color.toArgb(colorLong) }.getOrNull()
                        if (argb != null && isDarkColor(argb)) {
                            chain.proceed(arrayOf(Color.pack(Color.BLACK)))
                        } else {
                            chain.proceed()
                        }
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookCanvas(module: XposedModule) {
        val canvasClasses = listOfNotNull(
            android.graphics.Canvas::class.java,
            runCatching { Class.forName("android.graphics.BaseRecordingCanvas") }.getOrNull(),
            runCatching { Class.forName("android.graphics.RecordingCanvas") }.getOrNull()
        )

        val hookedMethods = mutableSetOf<String>()

        for (clazz in canvasClasses) {
            for (method in clazz.declaredMethods) {
                val name = method.name
                val params = method.parameterTypes

                if (name in listOf("drawRect", "drawRoundRect", "drawPath", "drawPaint")) {
                    if (params.isNotEmpty() && params.last() == Paint::class.java) {
                        val key = "${clazz.name}.${method.name}(${params.joinToString { it.simpleName }})"
                        if (hookedMethods.add(key)) {
                            hookCanvasDrawWithPaint(module, method, key)
                        }
                    }
                } else if (name == "drawColor") {
                    val key = "${clazz.name}.${method.name}(${params.joinToString { it.simpleName }})"
                    if (hookedMethods.add(key)) {
                        hookCanvasDrawColor(module, method, key, params)
                    }
                }
            }
        }
    }

    private fun hookCanvasDrawWithPaint(module: XposedModule, method: Method, key: String) {
        runCatching {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_amoled_canvas_paint_${key.hashCode()}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val paint = chain.args.lastOrNull() as? Paint
                    if (isAmoledEnabled() && paint != null && isDarkColor(paint.color)) {
                        val oldColor = paint.color
                        paint.color = Color.BLACK
                        val result = chain.proceed()
                        paint.color = oldColor
                        result
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookCanvasDrawColor(module: XposedModule, method: Method, key: String, params: Array<Class<*>>) {
        if (params.isEmpty()) return
        val firstType = params[0]
        runCatching {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_amoled_canvas_color_${key.hashCode()}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!isAmoledEnabled()) return@intercept chain.proceed()
                    if (firstType == Int::class.javaPrimitiveType) {
                        val color = chain.getArg(0) as? Int
                        if (color != null && isDarkColor(color)) {
                            val newArgs = chain.args.toTypedArray()
                            newArgs[0] = Color.BLACK
                            return@intercept chain.proceed(newArgs)
                        }
                    } else if (firstType == Long::class.javaPrimitiveType) {
                        val colorLong = chain.getArg(0) as? Long
                        if (colorLong != null) {
                            val argb = runCatching { Color.toArgb(colorLong) }.getOrNull()
                            if (argb != null && isDarkColor(argb)) {
                                val newArgs = chain.args.toTypedArray()
                                newArgs[0] = Color.pack(Color.BLACK)
                                return@intercept chain.proceed(newArgs)
                            }
                        }
                    }
                    chain.proceed()
                }
        }
    }
}
