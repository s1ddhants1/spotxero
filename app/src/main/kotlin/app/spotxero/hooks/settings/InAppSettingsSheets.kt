package app.spotxero.hooks.settings

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.spotxero.BuildConfig
import app.spotxero.core.DexKitSignatureCache
import app.spotxero.core.HookRegistry
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.hooks.lyrics.AiLyricsTranslator
import app.spotxero.hooks.lyrics.AiProviderGroup
import app.spotxero.hooks.lyrics.AiProviderPresets
import app.spotxero.hooks.lyrics.PersistentLyricsCache
import java.util.concurrent.Executors

internal object InAppSettingsSheets {

    private val SPOTIFY_GREEN = Color.parseColor("#1ed760")
    private val SUBTITLE_GREY = Color.parseColor("#a7a7a7")
    private val UNSELECTED_GREY = Color.parseColor("#535353")
    private val DIVIDER_GREY = Color.parseColor("#333333")
    private val CARD_GREY = Color.parseColor("#2e2e2e")
    private val executor = Executors.newSingleThreadExecutor()

    private val PROVIDER_LABELS = mapOf(
        "spicylyrics" to "SpicyLyrics",
        "betterlyrics" to "BetterLyrics",
        "lrcred" to "lrc.red",
        "lrclib" to "LRCLIB",
        "kugou" to "KuGou",
        "netease" to "NetEase Cloud Music",
        "genius" to "Genius"
    )

    private val PROVIDER_PREF_KEYS = mapOf(
        "spicylyrics" to PrefKeys.KEY_LYRICS_PROVIDER_SPICYLYRICS,
        "betterlyrics" to PrefKeys.KEY_LYRICS_PROVIDER_BETTERLYRICS,
        "lrcred" to PrefKeys.KEY_LYRICS_PROVIDER_LRCRED,
        "lrclib" to PrefKeys.KEY_LYRICS_PROVIDER_LRCLIB,
        "kugou" to PrefKeys.KEY_LYRICS_PROVIDER_KUGOU,
        "netease" to PrefKeys.KEY_LYRICS_PROVIDER_NETEASE,
        "genius" to PrefKeys.KEY_LYRICS_PROVIDER_GENIUS
    )

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

    fun showLyricsProvidersBottomSheet(activity: Activity) {
        showModalSheet(
            activity = activity,
            title = "Lyrics Providers",
            subtitle = "Toggle sources and adjust fallback priority"
        ) { container, dp, _ ->
            var order = PrefKeys.parseLyricsProviders(
                PreferencesManager.getString(PrefKeys.KEY_LYRICS_PROVIDERS_ORDER, PrefKeys.DEFAULT_LYRICS_PROVIDERS_ORDER)
            ).toMutableList()

            val listColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(listColumn)

            fun renderRows() {
                listColumn.removeAllViews()
                order.forEachIndexed { index, providerId ->
                    val prefKey = PROVIDER_PREF_KEYS[providerId] ?: return@forEachIndexed
                    val labelText = PROVIDER_LABELS[providerId] ?: providerId
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(24), dp(6), dp(20), dp(6))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(52)
                        )
                    }

                    val titleView = TextView(activity).apply {
                        text = "${index + 1}. $labelText"
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(titleView)

                    val upButton = createOrderButton(activity, dp, "▲", index > 0) {
                        val item = order.removeAt(index)
                        order.add(index - 1, item)
                        PreferencesManager.setString(activity, PrefKeys.KEY_LYRICS_PROVIDERS_ORDER, order.joinToString(","))
                        renderRows()
                    }
                    row.addView(upButton)

                    val downButton = createOrderButton(activity, dp, "▼", index < order.lastIndex) {
                        val item = order.removeAt(index)
                        order.add(index + 1, item)
                        PreferencesManager.setString(activity, PrefKeys.KEY_LYRICS_PROVIDERS_ORDER, order.joinToString(","))
                        renderRows()
                    }
                    row.addView(downButton)

                    val toggle = createSpotifySwitch(activity, PreferencesManager.isStoredEnabled(prefKey, true)) { checked ->
                        PreferencesManager.setBoolean(activity, prefKey, checked)
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { leftMargin = dp(8) }
                    }
                    row.addView(toggle)
                    listColumn.addView(row)
                }
            }

            renderRows()
        }
    }

    fun showAiTranslationBottomSheet(activity: Activity) {
        showModalSheet(
            activity = activity,
            title = "AI Lyrics Translation",
            subtitle = "Translation languages, providers, and models"
        ) { container, dp, _ ->
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(content)

            fun rebuild() {
                content.removeAllViews()
                val providerId = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_PROVIDER, "OpenRouter")
                val preset = AiProviderPresets.find(providerId) ?: AiProviderPresets.all.first()
                val isKeyless = preset.id == "DeepL (keyless)" || preset.id == "Google Translate"
                val isDeepL = preset.id == "DeepL"
                val isCustomEndpoint = preset.editableEndpoint || preset.group == AiProviderGroup.CUSTOM
                val currentModel = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_MODEL, PrefKeys.OpenRouterDefaultModel)
                val currentBaseUrl = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_BASE_URL, PrefKeys.OpenRouterDefaultBaseUrl)
                val currentLang = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_TARGET_LANG, "en")
                val currentMode = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_TRANSLATE_MODE, "Literal")
                val currentFormality = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_DEEPL_FORMALITY, "default")
                val currentPrompt = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_CUSTOM_PROMPT, "")
                val activeKey = if (isDeepL) {
                    PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY, "")
                } else {
                    PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_API_KEY, "")
                }

                content.addView(createSwitchRow(activity, dp, "Enable AI Translation", "Show translated lyrics", PreferencesManager.isStoredEnabled(PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED, false)) {
                    PreferencesManager.setBoolean(activity, PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED, it)
                })
                content.addView(createSwitchRow(activity, dp, "Dual-Line Lyrics", "Show original and translation together", PreferencesManager.isStoredEnabled(PrefKeys.KEY_AI_TRANSLATION_DUAL_LINE, true)) {
                    PreferencesManager.setBoolean(activity, PrefKeys.KEY_AI_TRANSLATION_DUAL_LINE, it)
                })

                content.addView(createValueRow(activity, dp, "Provider", preset.id) {
                    val labels = AiProviderPresets.all.map { it.id }.toTypedArray()
                    showChoiceDialog(activity, "Provider", labels, labels.indexOf(preset.id)) { idx ->
                        val chosen = AiProviderPresets.all[idx]
                        PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_PROVIDER, chosen.id)
                        if (chosen.endpoint.isNotBlank()) {
                            PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_BASE_URL, chosen.endpoint)
                        }
                        if (chosen.defaultModel.isNotBlank()) {
                            PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_MODEL, chosen.defaultModel)
                        }
                        rebuild()
                    }
                })

                if (!isKeyless) {
                    val masked = if (activeKey.isBlank()) "Not set" else "Configured (${activeKey.take(4)}...)"
                    content.addView(createValueRow(activity, dp, if (isDeepL) "DeepL API Key" else "API Key", masked) {
                        val keyPref = if (isDeepL) PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY else PrefKeys.KEY_AI_TRANSLATION_API_KEY
                        showTextInputDialog(activity, if (isDeepL) "DeepL API Key" else "API Key", activeKey, isSecret = true) { newVal ->
                            PreferencesManager.setString(activity, keyPref, newVal.trim())
                            rebuild()
                        }
                    })
                }

                if (preset.isAi) {
                    content.addView(createValueRow(activity, dp, "Model", currentModel.ifBlank { preset.defaultModel.ifBlank { "Not set" } }) {
                        showTextInputDialog(activity, "Translation Model", currentModel, isSecret = false) { newVal ->
                            PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_MODEL, newVal.trim())
                            rebuild()
                        }
                    })
                }

                if (isCustomEndpoint) {
                    content.addView(createValueRow(activity, dp, "Base URL", currentBaseUrl.ifBlank { "Not set" }) {
                        showTextInputDialog(activity, "Base URL", currentBaseUrl, isSecret = false) { newVal ->
                            PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_BASE_URL, newVal.trim())
                            PersistentLyricsCache.clear()
                            rebuild()
                        }
                    })
                }

                val langName = PrefKeys.LanguageCodeToName[currentLang] ?: currentLang
                content.addView(createValueRow(activity, dp, "Target Language", "$langName ($currentLang)") {
                    showLanguagePickerDialog(activity, currentLang) { code ->
                        PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_TARGET_LANG, code)
                        rebuild()
                    }
                })

                if (!isDeepL && preset.id != "DeepL (keyless)") {
                    val modeLabel = if (currentMode == "Transcribed") "Transcription" else "Translation"
                    content.addView(createValueRow(activity, dp, "Mode", modeLabel) {
                        val modes = arrayOf("Translation", "Transcription")
                        val values = arrayOf("Literal", "Transcribed")
                        showChoiceDialog(activity, "Translation Mode", modes, values.indexOf(currentMode).coerceAtLeast(0)) { idx ->
                            PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_TRANSLATE_MODE, values[idx])
                            rebuild()
                        }
                    })
                }

                if (isDeepL) {
                    content.addView(createValueRow(activity, dp, "Formality", currentFormality) {
                        val values = arrayOf("default", "more", "less")
                        val labels = arrayOf("Default", "More formal", "Less formal")
                        showChoiceDialog(activity, "DeepL Formality", labels, values.indexOf(currentFormality).coerceAtLeast(0)) { idx ->
                            PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_DEEPL_FORMALITY, values[idx])
                            PersistentLyricsCache.clear()
                            rebuild()
                        }
                    })
                }

                if (preset.isAi) {
                    val promptSummary = if (currentPrompt.isBlank()) "Default" else currentPrompt.take(40)
                    content.addView(createValueRow(activity, dp, "Custom Prompt", promptSummary) {
                        showTextInputDialog(activity, "Custom System Prompt", currentPrompt, isSecret = false, multiline = true) { newVal ->
                            PreferencesManager.setString(activity, PrefKeys.KEY_AI_TRANSLATION_CUSTOM_PROMPT, newVal)
                            PersistentLyricsCache.clear()
                            rebuild()
                        }
                    })
                }

                val statusText = TextView(activity).apply {
                    setTextColor(SUBTITLE_GREY)
                    textSize = 13f
                    setPadding(dp(24), dp(8), dp(24), dp(4))
                    visibility = View.GONE
                }
                content.addView(statusText)

                val testButton = createActionButton(activity, dp, "Test Connection", primary = true) {
                    val latestProvider = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_PROVIDER, "OpenRouter")
                    val latestKey = if (latestProvider == "DeepL") {
                        PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_DEEPL_API_KEY, "")
                    } else {
                        PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_API_KEY, "")
                    }
                    val latestBaseUrl = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_BASE_URL, PrefKeys.OpenRouterDefaultBaseUrl)
                    val latestModel = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_MODEL, PrefKeys.OpenRouterDefaultModel)
                    val latestLang = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_TARGET_LANG, "en")
                    val latestMode = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_TRANSLATE_MODE, "Literal")
                    val latestFormality = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_DEEPL_FORMALITY, "default")
                    val latestPrompt = PreferencesManager.getString(PrefKeys.KEY_AI_TRANSLATION_CUSTOM_PROMPT, "")

                    statusText.visibility = View.VISIBLE
                    statusText.setTextColor(SUBTITLE_GREY)
                    statusText.text = "Testing..."
                    executor.execute {
                        val result = AiLyricsTranslator.translate(
                            lines = listOf("Hello world"),
                            targetLanguage = latestLang,
                            apiKey = latestKey,
                            baseUrl = latestBaseUrl,
                            model = latestModel,
                            mode = latestMode,
                            provider = latestProvider,
                            deeplFormality = latestFormality,
                            customSystemPrompt = latestPrompt,
                            maxRetries = 1
                        )
                        activity.runOnUiThread {
                            result.onSuccess { lines ->
                                statusText.setTextColor(SPOTIFY_GREEN)
                                statusText.text = "Success: ${lines.firstOrNull().orEmpty()}"
                            }.onFailure { err ->
                                statusText.setTextColor(Color.parseColor("#ff6b6b"))
                                statusText.text = "Failed: ${err.message ?: "Unknown error"}"
                            }
                        }
                    }
                }
                content.addView(testButton)
            }

            rebuild()
        }
    }

    fun showDiagnosticsBottomSheet(activity: Activity, onRestart: () -> Unit) {
        val installed = HookRegistry.installedHookNames().sorted()
        val failed = HookRegistry.failedHookNames().sorted()
        val spotifyVersion = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(activity.packageName, 0)
            }
            "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode})"
        }.getOrDefault("Unknown")

        val summaryText = buildString {
            appendLine("SpotXero: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Spotify: $spotifyVersion")
            appendLine("Active hooks: ${installed.size}")
            append("Failed hooks: ${if (failed.isEmpty()) "None" else failed.joinToString()}")
        }

        showModalSheet(
            activity = activity,
            title = "SpotXero Diagnostics",
            subtitle = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        ) { container, dp, dismiss ->
            val infoCard = TextView(activity).apply {
                text = summaryText
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(CARD_GREY)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(24), dp(4), dp(24), dp(12))
                }
            }
            container.addView(infoCard)

            container.addView(createActionButton(activity, dp, "Clear Lyrics Cache", primary = false) {
                PersistentLyricsCache.clear()
                Toast.makeText(activity, "Lyrics cache cleared", Toast.LENGTH_SHORT).show()
            })

            container.addView(createActionButton(activity, dp, "Clear DexKit Signature Cache", primary = false) {
                val appInfo = HookRegistry.currentAppInfo() ?: activity.applicationInfo
                DexKitSignatureCache.clear(appInfo)
                Toast.makeText(activity, "DexKit cache cleared. Restart Spotify to re-resolve.", Toast.LENGTH_SHORT).show()
            })

            container.addView(createActionButton(activity, dp, "Copy Debug Info", primary = false) {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val full = "$summaryText\nInstalled: ${installed.joinToString()}"
                cm?.setPrimaryClip(ClipData.newPlainText("SpotXero Debug Info", full))
                Toast.makeText(activity, "Debug info copied", Toast.LENGTH_SHORT).show()
            })

            container.addView(createActionButton(activity, dp, "Restart Spotify", primary = true) {
                dismiss()
                onRestart()
            })
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

    private fun createValueRow(
        activity: Activity,
        dp: (Int) -> Int,
        title: String,
        value: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            val outValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
        }
        row.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
        })
        row.addView(TextView(activity).apply {
            text = value
            setTextColor(SUBTITLE_GREY)
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        })
        row.setOnClickListener { onClick() }
        return row
    }

    private fun createSwitchRow(
        activity: Activity,
        dp: (Int) -> Int,
        title: String,
        subtitle: String,
        initial: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(24), dp(10), dp(20), dp(10))
            val outValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
        })
        textCol.addView(TextView(activity).apply {
            text = subtitle
            setTextColor(SUBTITLE_GREY)
            textSize = 13f
        })
        row.addView(textCol)

        val toggle = createSpotifySwitch(activity, initial, onToggle)
        row.addView(toggle)
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        return row
    }

    private fun createSpotifySwitch(
        activity: Activity,
        initial: Boolean,
        onToggle: (Boolean) -> Unit
    ): Switch {
        return Switch(activity).apply {
            isChecked = initial
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.LTGRAY))
            trackTintList = ColorStateList(states, intArrayOf(SPOTIFY_GREEN, UNSELECTED_GREY))
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
    }

    private fun createOrderButton(
        activity: Activity,
        dp: (Int) -> Int,
        symbol: String,
        enabled: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = symbol
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(if (enabled) Color.WHITE else UNSELECTED_GREY)
            isEnabled = enabled
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                leftMargin = dp(4)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(CARD_GREY)
            }
            if (enabled) {
                setOnClickListener { onClick() }
            }
        }
    }

    private fun createActionButton(
        activity: Activity,
        dp: (Int) -> Int,
        label: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(24), dp(6), dp(24), dp(6))
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (primary) SPOTIFY_GREEN else DIVIDER_GREY)
            }
            isClickable = true
            setOnClickListener { onClick() }
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

    private fun showChoiceDialog(
        activity: Activity,
        title: String,
        items: Array<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                dialog.dismiss()
                onSelected(which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTextInputDialog(
        activity: Activity,
        title: String,
        initial: String,
        isSecret: Boolean,
        multiline: Boolean = false,
        onSaved: (String) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(activity).apply {
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(SUBTITLE_GREY)
            inputType = when {
                isSecret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 3
                maxLines = 6
            }
            setSelection(text.length)
        }
        val wrapper = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton("Save") { _, _ ->
                onSaved(input.text?.toString().orEmpty())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLanguagePickerDialog(
        activity: Activity,
        currentCode: String,
        onSelected: (String) -> Unit
    ) {
        val allEntries = PrefKeys.LanguageCodeToName.entries.toList()
        var filtered = allEntries
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val search = EditText(activity).apply {
            hint = "Search language"
            setTextColor(Color.WHITE)
            setHintTextColor(SUBTITLE_GREY)
        }
        root.addView(search)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (320 * density).toInt()
            )
            addView(listContainer)
        }
        root.addView(scroll)

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Target Language")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .create()

        fun renderList() {
            listContainer.removeAllViews()
            for ((code, name) in filtered) {
                val item = TextView(activity).apply {
                    text = if (code == currentCode) "✓ $name ($code)" else "$name ($code)"
                    setTextColor(if (code == currentCode) SPOTIFY_GREEN else Color.WHITE)
                    textSize = 15f
                    setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
                    setOnClickListener {
                        dialog.dismiss()
                        onSelected(code)
                    }
                }
                listContainer.addView(item)
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                filtered = if (q.isEmpty()) {
                    allEntries
                } else {
                    allEntries.filter { (code, name) ->
                        name.contains(q, ignoreCase = true) || code.contains(q, ignoreCase = true)
                    }
                }
                renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderList()
        dialog.show()
    }
}
