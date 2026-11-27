package app.spotxero.hooks.theme

import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object ThemeColorPipeline {

    @Volatile
    private var installed = false

    private val DRAW_PAINT_METHODS = setOf(
        "drawRect",
        "drawRoundRect",
        "drawCircle",
        "drawOval",
        "drawArc",
        "drawLine",
        "drawPath",
        "drawPaint"
    )

    fun reset() {
        installed = false
    }

    fun installShared(module: XposedModule) {
        if (installed) return
        installed = true
        hookResourcesGetColor(module)
        hookTypedArrayGetColor(module)
        hookGradientDrawables(module)
        hookPaintColors(module)
        hookCanvas(module)
    }

    fun transformColor(color: Int): Int {
        var result = color
        if (SpotifyAmoledHook.isEnabled()) {
            result = SpotifyAmoledHook.mapColor(result)
        }
        if (SpotifyMonetHook.isEnabled()) {
            result = SpotifyMonetHook.mapColor(result)
        }
        return result
    }

    private fun isAnyActive(): Boolean =
        SpotifyAmoledHook.isEnabled() || SpotifyMonetHook.isEnabled()

    private fun hookResourcesGetColor(module: XposedModule) {
        for (method in listOfNotNull(
            runCatching { Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType) }.getOrNull(),
            runCatching { Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java) }.getOrNull()
        )) {
            module.deoptimize(method)
            module.hook(method)
                .setId("theme_res_color_${method.parameterTypes.size}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? Int)?.let { transformColor(it) } ?: result
                }
        }
    }

    private fun hookTypedArrayGetColor(module: XposedModule) {
        val getColorMethod = runCatching {
            TypedArray::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull() ?: return

        module.deoptimize(getColorMethod)
        module.hook(getColorMethod)
            .setId("theme_ta_get_color")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                (result as? Int)?.let { transformColor(it) } ?: result
            }
    }

    private fun hookGradientDrawables(module: XposedModule) {
        val setColor = runCatching {
            GradientDrawable::class.java.getMethod("setColor", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (setColor != null) {
            module.deoptimize(setColor)
            module.hook(setColor)
                .setId("theme_gradient_set_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isAnyActive() && color != null) {
                        val mapped = transformColor(color)
                        if (mapped != color) {
                            return@intercept chain.proceed(arrayOf(mapped))
                        }
                    }
                    chain.proceed()
                }
        }

        val setColors = runCatching {
            GradientDrawable::class.java.getMethod("setColors", IntArray::class.java)
        }.getOrNull()
        if (setColors != null) {
            module.deoptimize(setColors)
            module.hook(setColors)
                .setId("theme_gradient_set_colors")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val colors = chain.getArg(0) as? IntArray
                    if (isAnyActive() && colors != null) {
                        var changed = false
                        val newColors = colors.clone()
                        for (i in newColors.indices) {
                            val mapped = transformColor(newColors[i])
                            if (mapped != newColors[i]) {
                                newColors[i] = mapped
                                changed = true
                            }
                        }
                        if (changed) {
                            return@intercept chain.proceed(arrayOf(newColors))
                        }
                    }
                    chain.proceed()
                }
        }
    }

    private fun hookPaintColors(module: XposedModule) {
        val paintSetColor = runCatching {
            Paint::class.java.getMethod("setColor", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (paintSetColor != null) {
            module.deoptimize(paintSetColor)
            module.hook(paintSetColor)
                .setId("theme_paint_set_color")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val color = chain.getArg(0) as? Int
                    if (isAnyActive() && color != null) {
                        val mapped = transformColor(color)
                        if (mapped != color) {
                            return@intercept chain.proceed(arrayOf(mapped))
                        }
                    }
                    chain.proceed()
                }
        }

        val paintSetColorLong = runCatching {
            Paint::class.java.getMethod("setColor", Long::class.javaPrimitiveType)
        }.getOrNull()
        if (paintSetColorLong != null) {
            module.deoptimize(paintSetColorLong)
            module.hook(paintSetColorLong)
                .setId("theme_paint_set_color_long")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val colorLong = chain.getArg(0) as? Long
                    if (isAnyActive() && colorLong != null) {
                        val argb = runCatching { Color.toArgb(colorLong) }.getOrNull()
                        if (argb != null) {
                            val mapped = transformColor(argb)
                            if (mapped != argb) {
                                return@intercept chain.proceed(arrayOf(Color.pack(mapped)))
                            }
                        }
                    }
                    chain.proceed()
                }
        }
    }

    private fun hookCanvas(module: XposedModule) {
        val canvasClasses = listOfNotNull(
            Canvas::class.java,
            runCatching { Class.forName("android.graphics.BaseRecordingCanvas") }.getOrNull(),
            runCatching { Class.forName("android.graphics.RecordingCanvas") }.getOrNull()
        )

        val hookedMethods = mutableSetOf<String>()

        for (clazz in canvasClasses) {
            for (method in clazz.declaredMethods) {
                val name = method.name
                val params = method.parameterTypes
                val key = "${clazz.name}.$name(${params.joinToString { it.simpleName }})"
                if (name in DRAW_PAINT_METHODS && params.isNotEmpty() && params.last() == Paint::class.java) {
                    if (hookedMethods.add(key)) {
                        hookCanvasDrawWithPaint(module, method, key)
                    }
                } else if (name == "drawColor" && hookedMethods.add(key)) {
                    hookCanvasDrawColor(module, method, key, params)
                }
            }
        }
    }

    private fun hookCanvasDrawWithPaint(module: XposedModule, method: Method, key: String) {
        runCatching {
            module.deoptimize(method)
            module.hook(method)
                .setId("theme_canvas_paint_${key.hashCode()}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val paint = chain.args.lastOrNull() as? Paint
                    if (isAnyActive() && paint != null && paint.shader == null) {
                        val oldColor = paint.color
                        val mapped = transformColor(oldColor)
                        if (mapped != oldColor) {
                            paint.color = mapped
                            return@intercept try {
                                chain.proceed()
                            } finally {
                                paint.color = oldColor
                            }
                        }
                    }
                    chain.proceed()
                }
        }
    }

    private fun hookCanvasDrawColor(module: XposedModule, method: Method, key: String, params: Array<Class<*>>) {
        if (params.isEmpty()) return
        val firstType = params[0]
        runCatching {
            module.deoptimize(method)
            module.hook(method)
                .setId("theme_canvas_color_${key.hashCode()}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!isAnyActive()) return@intercept chain.proceed()
                    when (firstType) {
                        Int::class.javaPrimitiveType -> {
                            val color = chain.getArg(0) as? Int
                            if (color != null) {
                                val mapped = transformColor(color)
                                if (mapped != color) {
                                    val newArgs = chain.args.toTypedArray()
                                    newArgs[0] = mapped
                                    return@intercept chain.proceed(newArgs)
                                }
                            }
                        }
                        Long::class.javaPrimitiveType -> {
                            val colorLong = chain.getArg(0) as? Long
                            val argb = colorLong?.let { runCatching { Color.toArgb(it) }.getOrNull() }
                            if (argb != null) {
                                val mapped = transformColor(argb)
                                if (mapped != argb) {
                                    val newArgs = chain.args.toTypedArray()
                                    newArgs[0] = Color.pack(mapped)
                                    return@intercept chain.proceed(newArgs)
                                }
                            }
                        }
                    }
                    chain.proceed()
                }
        }
    }
}
