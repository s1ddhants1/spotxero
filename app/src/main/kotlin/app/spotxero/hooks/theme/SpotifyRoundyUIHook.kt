package app.spotxero.hooks.theme

import android.content.res.Resources
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

object SpotifyRoundyUIHook {

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            Resources.getSystem().displayMetrics
        )
    }

    private val radiusLarge by lazy { dpToPx(28f) }
    private val radiusFull by lazy { dpToPx(100f) }

    private val fullOutlineProvider by lazy {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusLarge)
            }
        }
    }

    private val sheetOutlineProvider by lazy {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height + radiusLarge.toInt(), radiusLarge)
            }
        }
    }

    private fun isRoundyUiEnabled(): Boolean =
        PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_ROUNDY_UI, false)

    fun install(module: XposedModule) {
        if (!isRoundyUiEnabled()) {
            return
        }
        hookViewAttach(module)
        hookImageViewSetDrawable(module)
        hookGradientDrawable(module)
        hookBottomSheet(module)
        hookMaterialShapeDrawable(module)
    }

    private fun hookViewAttach(module: XposedModule) {
        val method = runCatching {
            View::class.java.getDeclaredMethod("onAttachedToWindow")
        }.getOrNull()
        if (method != null) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_roundy_view_attach")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (isRoundyUiEnabled()) {
                        (chain.getThisObject() as? View)?.let { applyRoundingIfTarget(it) }
                    }
                    result
                }
        }
    }

    private fun hookImageViewSetDrawable(module: XposedModule) {
        val method = runCatching {
            ImageView::class.java.getMethod("setImageDrawable", Drawable::class.java)
        }.getOrNull()
        if (method != null) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_roundy_imageview_drawable")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (isRoundyUiEnabled()) {
                        (chain.getThisObject() as? ImageView)?.let { applyRoundingIfTarget(it) }
                    }
                    result
                }
        }
    }

    private fun hookGradientDrawable(module: XposedModule) {
        val method = runCatching {
            GradientDrawable::class.java.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
        }.getOrNull()
        if (method != null) {
            module.deoptimize(method)
            module.hook(method)
                .setId("spotify_roundy_gradient_corner_radius")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val original = chain.getArg(0) as? Float
                    if (isRoundyUiEnabled() && original != null) {
                        val newRadius = if (original > dpToPx(15f)) radiusFull else radiusLarge
                        chain.proceed(arrayOf(newRadius))
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookBottomSheet(module: XposedModule) {
        val coordinatorClass = runCatching {
            Class.forName("androidx.coordinatorlayout.widget.CoordinatorLayout")
        }.getOrNull()
        val bsbClass = runCatching {
            Class.forName("com.google.android.material.bottomsheet.BottomSheetBehavior")
        }.getOrNull()

        if (coordinatorClass != null && bsbClass != null) {
            val method = runCatching {
                bsbClass.getMethod("onLayoutChild", coordinatorClass, View::class.java, Int::class.javaPrimitiveType)
            }.getOrNull()

            if (method != null) {
                module.deoptimize(method)
                module.hook(method)
                    .setId("spotify_roundy_bottomsheet_layout")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (isRoundyUiEnabled()) {
                            val view = chain.getArg(1) as? View
                            if (view != null) {
                                view.clipToOutline = true
                                view.outlineProvider = sheetOutlineProvider
                            }
                        }
                        result
                    }
            }
        }
    }

    private fun hookMaterialShapeDrawable(module: XposedModule) {
        val msdClass = runCatching {
            Class.forName("com.google.android.material.shape.MaterialShapeDrawable")
        }.getOrNull() ?: return

        val setInterpolationMethod = runCatching {
            msdClass.getMethod("setInterpolation", Float::class.javaPrimitiveType)
        }.getOrNull()
        val setCornerSizeMethod = runCatching {
            msdClass.getMethod("setCornerSize", Float::class.javaPrimitiveType)
        }.getOrNull()

        if (setInterpolationMethod != null && setCornerSizeMethod != null) {
            module.deoptimize(setInterpolationMethod)
            module.hook(setInterpolationMethod)
                .setId("spotify_roundy_material_shape_interpolation")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (isRoundyUiEnabled()) {
                        runCatching {
                            setCornerSizeMethod.invoke(chain.getThisObject(), radiusLarge)
                        }
                    }
                    result
                }
        }
    }

    private fun applyRoundingIfTarget(view: View) {
        val resName = runCatching { view.resources.getResourceEntryName(view.id).lowercase() }.getOrDefault("")
        val className = view.javaClass.name.lowercase()

        val isAvatar = className.contains("faceview") || resName.contains("face")
        if (isAvatar) return

        val isImage = view is ImageView || (className.contains("imageview") && !isAvatar)
        val isCoverOrHeader = resName.contains("header") ||
            resName.contains("cover") ||
            resName.contains("art") ||
            resName.contains("entity")
        val isSheet = className.contains("bottomsheet") || resName.contains("sheet") || resName.contains("queue")
        val isCard = className.contains("card") || resName.contains("tile")
        val isSearchBar = resName == "browse_search_bar_container" || resName.contains("search")
        val isCat = resName == "seek_frame" || resName.contains("seek")

        val isTextContainerRow = (resName.contains("row") || resName.contains("item")) && !isImage && !isSheet
        if (isTextContainerRow) {
            view.clipToOutline = false
            return
        }

        val shouldRound = isImage || isCoverOrHeader || isSheet || isCard || isSearchBar || isCat
        if (shouldRound) {
            view.clipToOutline = true
            view.outlineProvider = if (isSheet) sheetOutlineProvider else fullOutlineProvider
        }
    }
}
