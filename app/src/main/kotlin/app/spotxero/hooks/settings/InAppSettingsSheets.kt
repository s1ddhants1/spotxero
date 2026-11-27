package app.spotxero.hooks.settings

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager

internal object InAppSettingsSheets {

    private val SPOTIFY_GREEN = Color.parseColor("#1ed760")
    private val SUBTITLE_GREY = Color.parseColor("#a7a7a7")
    private val UNSELECTED_GREY = Color.parseColor("#535353")

    fun seekDurationSeconds(): Int =
        PreferencesManager.getInt(PrefKeys.KEY_SEEK_DURATION_SECONDS, 10).coerceIn(5, 30)

    fun showSeekDurationBottomSheet(activity: Activity, onSelected: (Int) -> Unit = {}) {
        val durations = intArrayOf(5, 10, 15, 20, 25, 30)
        val selectedDuration = seekDurationSeconds()
        showModalSheet(
            activity = activity,
            title = "Seek duration",
            subtitle = "Seconds to skip when seeking"
        ) { container, dp, dismiss ->
            val radioIndicators = mutableMapOf<Int, ImageView>()
            for (d in durations) {
                val row = createSelectableRow(activity, dp)
                val label = TextView(activity).apply {
                    text = "$d seconds"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(label)

                val radioIndicator = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                    setImageDrawable(buildRadioDrawable(dp, d == selectedDuration))
                }
                radioIndicators[d] = radioIndicator
                row.addView(radioIndicator)

                row.setOnClickListener {
                    radioIndicators.forEach { (dur, indicator) ->
                        indicator.setImageDrawable(buildRadioDrawable(dp, dur == d))
                    }
                    PreferencesManager.setInt(activity, PrefKeys.KEY_SEEK_DURATION_SECONDS, d)
                    onSelected(d)
                    Toast.makeText(activity, "Seek duration: $d seconds", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                container.addView(row)
            }
        }
    }

    private fun showModalSheet(
        activity: Activity,
        title: String,
        subtitle: String,
        buildBody: (container: LinearLayout, dp: (Int) -> Int, dismiss: () -> Unit) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread {
            runCatching {
                val isAmoled = PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_AMOLED, false)
                val density = activity.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                val window = dialog.window ?: return@runCatching
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setDimAmount(0.6f)
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.attributes.windowAnimations = 0

                var isDismissing = false
                val rootLayout = FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val navBarBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0
                }

                val maxSheetHeight = (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()
                val sheetContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadii = floatArrayOf(
                            dp(28).toFloat(), dp(28).toFloat(),
                            dp(28).toFloat(), dp(28).toFloat(),
                            0f, 0f,
                            0f, 0f
                        )
                        setColor(if (isAmoled) Color.BLACK else Color.parseColor("#242424"))
                    }
                    setPadding(0, dp(12), 0, dp(16) + navBarBottom)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    )
                    isClickable = true
                }
                rootLayout.addView(sheetContainer)

                fun dismissWithAnimation() {
                    if (isDismissing) return
                    isDismissing = true
                    val targetY = if (sheetContainer.height > 0) sheetContainer.height.toFloat() else dp(450).toFloat()
                    sheetContainer.animate()
                        .translationY(targetY)
                        .setDuration(200)
                        .setInterpolator(AccelerateInterpolator(1.6f))
                        .withEndAction { dialog.dismiss() }
                        .start()
                }

                rootLayout.setOnClickListener { dismissWithAnimation() }
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismissWithAnimation()
                        true
                    } else false
                }

                sheetContainer.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(16)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(Color.parseColor("#5a5a5a"))
                    }
                })

                sheetContainer.addView(TextView(activity).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dp(24)
                        rightMargin = dp(24)
                        bottomMargin = dp(6)
                    }
                })

                sheetContainer.addView(TextView(activity).apply {
                    text = subtitle
                    setTextColor(SUBTITLE_GREY)
                    textSize = 14f
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dp(24)
                        rightMargin = dp(24)
                        bottomMargin = dp(12)
                    }
                })

                val scrollBody = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                val scrollView = ScrollView(activity).apply {
                    isVerticalScrollBarEnabled = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(scrollBody)
                }
                sheetContainer.addView(scrollView)

                buildBody(scrollBody, ::dp, ::dismissWithAnimation)

                sheetContainer.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        sheetContainer.viewTreeObserver.removeOnPreDrawListener(this)
                        if (sheetContainer.height > maxSheetHeight) {
                            val headerHeight = sheetContainer.height - scrollView.height
                            scrollView.layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                (maxSheetHeight - headerHeight).coerceAtLeast(dp(180))
                            )
                        }
                        val startY = if (sheetContainer.height > 0) sheetContainer.height.toFloat() else dp(450).toFloat()
                        sheetContainer.translationY = startY
                        sheetContainer.animate()
                            .translationY(0f)
                            .setDuration(280)
                            .setInterpolator(DecelerateInterpolator(2.0f))
                            .start()
                        return true
                    }
                })

                dialog.setContentView(rootLayout)
                dialog.show()
            }
        }
    }

    private fun createSelectableRow(activity: Activity, dp: (Int) -> Int): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setPadding(dp(24), 0, dp(24), 0)
            val outValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
        }
    }

    private fun buildRadioDrawable(dp: (Int) -> Int, selected: Boolean): Drawable {
        return if (!selected) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(20), dp(20))
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), UNSELECTED_GREY)
            }
        } else {
            val outer = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(20), dp(20))
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), SPOTIFY_GREEN)
            }
            val inner = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(10), dp(10))
                setColor(SPOTIFY_GREEN)
            }
            LayerDrawable(arrayOf(outer, inner)).apply {
                setLayerGravity(1, Gravity.CENTER)
                val inset = dp(5)
                setLayerInset(1, inset, inset, inset, inset)
            }
        }
    }
}
