package app.spotxero.ui.component.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.hooks.lyrics.AiLyricsTranslator
import app.spotxero.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val CUSTOM_PROVIDER = "Custom"
private const val DEEPL_PROVIDER = "DeepL"
private const val DEEPL_KEYLESS_PROVIDER = "DeepL (keyless)"
private const val GOOGLE_TRANSLATE_PROVIDER = "Google Translate"
private const val LITERAL_MODE = "Literal"
private const val TRANSCRIBED_MODE = "Transcribed"

enum class AiProviderGroup(val titleRes: Int) {
    GATEWAY(R.string.ai_provider_group_gateways),
    DIRECT(R.string.ai_provider_group_direct),
    CUSTOM(R.string.ai_provider_group_custom),
    SERVICE(R.string.ai_provider_group_services)
}

data class AiProviderPreset(
    val id: String,
    val group: AiProviderGroup,
    val endpoint: String = "",
    val defaultModel: String = "",
    val editableEndpoint: Boolean = false,
    val supportsOAuth: Boolean = false,
    val publicModelList: Boolean = false,
) {
    val isAi: Boolean get() = group != AiProviderGroup.SERVICE
}

fun isDirectLyricsTranslationProvider(provider: String): Boolean =
    provider == DEEPL_KEYLESS_PROVIDER || provider == GOOGLE_TRANSLATE_PROVIDER

val ALL_PRESETS = listOf(
    // Gateways
    AiProviderPreset(
        id = "OpenRouter",
        group = AiProviderGroup.GATEWAY,
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "qwen/qwen3.7-flash",
        supportsOAuth = true,
        publicModelList = true
    ),
    AiProviderPreset(
        id = "OpenCodeZen",
        group = AiProviderGroup.GATEWAY,
        endpoint = "https://opencode.ai/zen/v1/chat/completions",
        defaultModel = "deepseek-v4-flash",
        publicModelList = true
    ),
    AiProviderPreset(
        id = "OpenCodeGo",
        group = AiProviderGroup.GATEWAY,
        endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
        defaultModel = "glm-5.3-flash",
        publicModelList = true
    ),
    AiProviderPreset(
        id = "Groq",
        group = AiProviderGroup.GATEWAY,
        endpoint = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "openai/gpt-oss-20b"
    ),

    // Direct providers
    AiProviderPreset(
        id = "OpenAI",
        group = AiProviderGroup.DIRECT,
        endpoint = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-6-luna"
    ),
    AiProviderPreset(
        id = "Anthropic",
        group = AiProviderGroup.DIRECT,
        endpoint = "https://api.anthropic.com/v1/messages",
        defaultModel = "claude-haiku-5-5"
    ),
    AiProviderPreset(
        id = "Gemini",
        group = AiProviderGroup.DIRECT,
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent",
        defaultModel = "gemini-flash-lite-latest"
    ),
    AiProviderPreset(
        id = "XAi",
        group = AiProviderGroup.DIRECT,
        endpoint = "https://api.x.ai/v1/chat/completions",
        defaultModel = "grok-build-0.1"
    ),
    AiProviderPreset(
        id = "Mistral",
        group = AiProviderGroup.DIRECT,
        endpoint = "https://api.mistral.ai/v1/chat/completions",
        defaultModel = "mistral-small-latest"
    ),
    AiProviderPreset(
        id = "Inception",
        group = AiProviderGroup.DIRECT,
        endpoint = "https://api.inceptionlabs.ai/v1/chat/completions",
        defaultModel = "mercury-2.5",
        publicModelList = true
    ),
    AiProviderPreset(
        id = "DeepSeek",
        group = AiProviderGroup.DIRECT,
        endpoint = "https://api.deepseek.com/v1/chat/completions",
        defaultModel = "deepseek-flash"
    ),
    AiProviderPreset(
        id = "Perplexity",
        group = AiProviderGroup.DIRECT,
        endpoint = "https://api.perplexity.ai/chat/completions",
        defaultModel = "sonar"
    ),

    // Custom
    AiProviderPreset(
        id = "OpenAI-compatible",
        group = AiProviderGroup.CUSTOM,
        endpoint = "https://api.openai.com/v1/chat/completions",
        editableEndpoint = true
    ),
    AiProviderPreset(
        id = "Anthropic-compatible",
        group = AiProviderGroup.CUSTOM,
        endpoint = "https://api.anthropic.com/v1/messages",
        editableEndpoint = true
    ),
    AiProviderPreset(
        id = "Gemini-compatible",
        group = AiProviderGroup.CUSTOM,
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent",
        editableEndpoint = true
    ),

    // Services
    AiProviderPreset(
        id = DEEPL_PROVIDER,
        group = AiProviderGroup.SERVICE,
        endpoint = "https://api.deepl.com/v2/translate"
    ),
    AiProviderPreset(
        id = DEEPL_KEYLESS_PROVIDER,
        group = AiProviderGroup.SERVICE,
        endpoint = "https://oneshot-free.www.deepl.com/v1/translate"
    ),
    AiProviderPreset(
        id = GOOGLE_TRANSLATE_PROVIDER,
        group = AiProviderGroup.SERVICE,
        endpoint = "https://translate.googleapis.com/translate_a/single"
    )
)

@Composable
fun lyricsAiProviderLabel(provider: String): String =
    when (provider) {
        "OpenRouter" -> stringResource(R.string.brand_openrouter)
        "OpenAI" -> stringResource(R.string.brand_openai)
        "OpenAI-compatible", CUSTOM_PROVIDER -> stringResource(R.string.ai_provider_compatible, stringResource(R.string.brand_openai))
        "Anthropic", "Claude" -> stringResource(R.string.brand_anthropic)
        "Anthropic-compatible" -> stringResource(R.string.ai_provider_compatible, stringResource(R.string.brand_anthropic))
        "Gemini" -> stringResource(R.string.brand_gemini)
        "Gemini-compatible" -> stringResource(R.string.ai_provider_compatible, stringResource(R.string.brand_gemini))
        "Perplexity" -> stringResource(R.string.brand_perplexity)
        "Mistral" -> stringResource(R.string.brand_mistral)
        "XAi" -> stringResource(R.string.brand_xai)
        "OpenCodeZen" -> stringResource(R.string.brand_opencode_zen)
        "OpenCodeGo" -> stringResource(R.string.brand_opencode_go)
        "Groq" -> stringResource(R.string.brand_groq)
        "DeepSeek" -> stringResource(R.string.brand_deepseek)
        "Inception" -> stringResource(R.string.brand_inception)
        "DeepL" -> stringResource(R.string.brand_deepl)
        "DeepL (keyless)" -> stringResource(R.string.ai_provider_keyless, stringResource(R.string.brand_deepl))
        "Google Translate" -> stringResource(R.string.brand_google_translate)
        else -> provider
    }

data class AiModelInfo(
    val id: String,
    val name: String = id,
    val inputUsdPerMTok: Double? = null,
    val outputUsdPerMTok: Double? = null,
    val tokensPerSecond: Double? = null,
) {
    val free: Boolean get() = (inputUsdPerMTok == 0.0 && outputUsdPerMTok == 0.0) || id.endsWith(":free")
}

private val MODELS_BY_PRESET = mapOf(
    "OpenRouter" to listOf(
        AiModelInfo("google/gemma-4-26b-a4b-it:free", "google/gemma-4-26b-a4b-it:free", 0.0, 0.0),
        AiModelInfo("google/gemma-4-31b-it:free", "google/gemma-4-31b-it:free", 0.0, 0.0),
        AiModelInfo("qwen/qwen3.7-flash", "qwen/qwen3.7-flash", 0.03, 0.13),
        AiModelInfo("google/gemini-2.5-flash-lite", "google/gemini-2.5-flash-lite", 0.1, 0.4),
        AiModelInfo("inception/mercury-2.5-preview", "inception/mercury-2.5-preview", 0.05, 0.20),
        AiModelInfo("meta/muse-spark-1.3", "meta/muse-spark-1.3", 0.08, 0.30),
        AiModelInfo("z-ai/glm-5.3-flash", "z-ai/glm-5.3-flash", 0.04, 0.15),
        AiModelInfo("openai/gpt-oss-120b", "openai/gpt-oss-120b", 0.15, 0.60),
    ),
    "OpenCodeZen" to listOf(
        AiModelInfo("deepseek-v4-flash", "deepseek-v4-flash", 0.02, 0.08),
        AiModelInfo("qwen-2.5-coder-32b", "qwen-2.5-coder-32b", 0.03, 0.12),
    ),
    "OpenCodeGo" to listOf(
        AiModelInfo("glm-5.3-flash", "glm-5.3-flash", 0.02, 0.08),
        AiModelInfo("deepseek-v4-flash", "deepseek-v4-flash", 0.02, 0.08),
    ),
    "OpenAI" to listOf(
        AiModelInfo("gpt-6-luna", "gpt-6-luna", 0.20, 0.80),
        AiModelInfo("gpt-5.6-sol", "gpt-5.6-sol", 0.50, 2.00),
        AiModelInfo("gpt-5.6-terra", "gpt-5.6-terra", 0.25, 1.00),
        AiModelInfo("gpt-5.5-2026-04-23", "gpt-5.5-2026-04-23", 0.30, 1.20),
        AiModelInfo("gpt-5.4-2026-03-05", "gpt-5.4-2026-03-05", 0.15, 0.60),
    ),
    "Anthropic" to listOf(
        AiModelInfo("claude-haiku-5-5", "claude-haiku-5-5", 0.25, 1.25),
        AiModelInfo("claude-3-5-haiku-latest", "claude-3-5-haiku-latest", 0.25, 1.25),
        AiModelInfo("claude-sonnet-5", "claude-sonnet-5", 3.00, 15.00),
        AiModelInfo("claude-opus-5", "claude-opus-5", 15.00, 75.00),
    ),
    "Gemini" to listOf(
        AiModelInfo("gemini-flash-lite-latest", "gemini-flash-lite-latest", 0.075, 0.30),
        AiModelInfo("gemini-2.5-flash", "gemini-2.5-flash", 0.10, 0.40),
        AiModelInfo("gemini-2.5-flash-lite", "gemini-2.5-flash-lite", 0.075, 0.30),
        AiModelInfo("gemini-pro-latest", "gemini-pro-latest", 1.25, 5.00),
    ),
    "Perplexity" to listOf(
        AiModelInfo("sonar", "sonar", 1.00, 1.00),
        AiModelInfo("sonar-pro", "sonar-pro", 3.00, 15.00),
        AiModelInfo("sonar-reasoning-pro", "sonar-reasoning-pro", 2.00, 8.00),
    ),
    "XAi" to listOf(
        AiModelInfo("grok-build-0.1", "grok-build-0.1", 0.50, 2.00),
        AiModelInfo("grok-4.3", "grok-4.3", 1.00, 4.00),
        AiModelInfo("grok-4.6", "grok-4.6", 2.00, 8.00),
        AiModelInfo("grok-4.1-fast", "grok-4.1-fast", 0.30, 1.20),
    ),
    "Mistral" to listOf(
        AiModelInfo("mistral-small-latest", "mistral-small-latest", 0.20, 0.60),
        AiModelInfo("mistral-large-latest", "mistral-large-latest", 2.00, 6.00),
        AiModelInfo("mistral-medium-latest", "mistral-medium-latest", 0.60, 1.80),
    ),
    "Inception" to listOf(
        AiModelInfo("mercury-2.5", "mercury-2.5", 0.05, 0.20),
        AiModelInfo("mercury-2", "mercury-2", 0.04, 0.15),
    ),
    "DeepSeek" to listOf(
        AiModelInfo("deepseek-flash", "deepseek-flash", 0.07, 0.28),
        AiModelInfo("deepseek-chat", "deepseek-chat", 0.14, 0.56),
    ),
    "Groq" to listOf(
        AiModelInfo("openai/gpt-oss-20b", "openai/gpt-oss-20b", 0.05, 0.20),
        AiModelInfo("llama-3.3-70b-versatile", "llama-3.3-70b-versatile", 0.59, 0.79),
    ),
)

internal fun formatUsd(value: Double): String {
    val digits = if (value >= 1.0) 2 else 3
    val scale = 10.0.pow(digits).roundToLong()
    val scaled = (value * scale).roundToLong()
    return "\$${scaled / scale}.${(scaled % scale).toString().padStart(digits, '0')}"
}

private sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(val sample: String) : ConnectionTestState
    data class Failed(val message: String) : ConnectionTestState
    data object MissingCredentials : ConnectionTestState
}

@Composable
fun AiLyricsTranslationScreen(
    isAiTranslationEnabled: Boolean,
    isAiTranslationDualLine: Boolean,
    aiProvider: String,
    openRouterApiKey: String,
    openRouterBaseUrl: String,
    openRouterModel: String,
    translateLanguage: String,
    translateMode: String,
    deeplApiKey: String,
    deeplFormality: String,
    aiSystemPrompt: String,
    onAiTranslationEnabledChange: (Boolean) -> Unit,
    onAiTranslationDualLineChange: (Boolean) -> Unit,
    onAiProviderChange: (String) -> Unit,
    onOpenRouterApiKeyChange: (String) -> Unit,
    onOpenRouterBaseUrlChange: (String) -> Unit,
    onOpenRouterModelChange: (String) -> Unit,
    onTranslateLanguageChange: (String) -> Unit,
    onTranslateModeChange: (String) -> Unit,
    onDeeplApiKeyChange: (String) -> Unit,
    onDeeplFormalityChange: (String) -> Unit,
    onAiSystemPromptChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var testState by remember { mutableStateOf<ConnectionTestState>(ConnectionTestState.Idle) }

    var activationMode by rememberSaveable {
        mutableStateOf(PreferencesManager.aiTranslationActivationMode)
    }

    val currentPreset = remember(aiProvider) {
        ALL_PRESETS.firstOrNull { it.id.equals(aiProvider, ignoreCase = true) }
            ?: if (aiProvider == CUSTOM_PROVIDER) ALL_PRESETS.first { it.id == "OpenAI-compatible" }
            else if (aiProvider.equals("Claude", ignoreCase = true)) ALL_PRESETS.first { it.id == "Anthropic" }
            else ALL_PRESETS.first()
    }
    val provider = currentPreset.id
    val directProvider = isDirectLyricsTranslationProvider(provider)

    var showSetupWizardDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showTriggerDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }

    val currentKey = if (provider == DEEPL_PROVIDER) deeplApiKey else openRouterApiKey
    val modelsReady = openRouterApiKey.isNotBlank() || currentPreset.publicModelList

    // Dialogs
    if (showSetupWizardDialog) {
        AiTranslationSetupDialog(
            currentApiKey = openRouterApiKey,
            currentModel = openRouterModel,
            currentLanguage = translateLanguage,
            onComplete = { newKey, newModel, newLang ->
                onAiProviderChange("OpenRouter")
                onOpenRouterApiKeyChange(newKey)
                onOpenRouterModelChange(newModel)
                onTranslateLanguageChange(newLang)
                onAiTranslationEnabledChange(true)
                showSetupWizardDialog = false
            },
            onDismiss = { showSetupWizardDialog = false }
        )
    }

    if (showProviderDialog) {
        ProviderPickerDialog(
            currentProvider = provider,
            onSelect = { selected ->
                onAiProviderChange(selected.id)
                if (selected.endpoint.isNotBlank()) {
                    onOpenRouterBaseUrlChange(selected.endpoint)
                }
                if (selected.defaultModel.isNotBlank()) {
                    onOpenRouterModelChange(selected.defaultModel)
                }
                if (isDirectLyricsTranslationProvider(selected.id)) {
                    onTranslateModeChange(LITERAL_MODE)
                }
                showProviderDialog = false
            },
            onDismiss = { showProviderDialog = false }
        )
    }

    if (showTriggerDialog) {
        TriggerPickerDialog(
            currentMode = activationMode,
            onSelect = {
                activationMode = it
                PreferencesManager.aiTranslationActivationMode = it
                showTriggerDialog = false
            },
            onDismiss = { showTriggerDialog = false }
        )
    }

    if (showTranslateModeDialog) {
        TranslateModePickerDialog(
            currentMode = translateMode,
            onSelect = {
                onTranslateModeChange(it)
                showTranslateModeDialog = false
            },
            onDismiss = { showTranslateModeDialog = false }
        )
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            currentCode = translateLanguage,
            onSelect = {
                onTranslateLanguageChange(it)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showApiKeyDialog) {
        TextSettingDialog(
            title = stringResource(if (provider == DEEPL_PROVIDER) R.string.ai_deepl_api_key else R.string.ai_api_key_label),
            initialValue = currentKey,
            isSecret = true,
            placeholder = stringResource(R.string.required),
            onDone = {
                if (provider == DEEPL_PROVIDER) {
                    onDeeplApiKeyChange(it)
                } else {
                    onOpenRouterApiKeyChange(it)
                }
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showBaseUrlDialog) {
        TextSettingDialog(
            title = stringResource(R.string.ai_base_url),
            initialValue = openRouterBaseUrl,
            placeholder = currentPreset.endpoint,
            onDone = {
                onOpenRouterBaseUrlChange(it)
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false }
        )
    }

    if (showDeeplFormalityDialog) {
        FormalityPickerDialog(
            currentFormality = deeplFormality,
            onSelect = {
                onDeeplFormalityChange(it)
                showDeeplFormalityDialog = false
            },
            onDismiss = { showDeeplFormalityDialog = false }
        )
    }

    if (showModelDialog) {
        ModelPickerDialog(
            currentPreset = currentPreset,
            currentModel = openRouterModel,
            onSelect = {
                onOpenRouterModelChange(it)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }

    if (showSystemPromptDialog) {
        TextSettingDialog(
            title = stringResource(R.string.ai_system_prompt_label),
            initialValue = aiSystemPrompt,
            isMultiline = true,
            placeholder = stringResource(R.string.ai_default_json_array_prompt),
            onDone = {
                onAiSystemPromptChange(it)
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        // Group 1: Setup
        MetrolistSettingsGroup(
            title = stringResource(R.string.ai_setup_title),
            topPadding = 8.dp,
            items = listOf(
                MetrolistSettingsItem(
                    title = stringResource(R.string.ai_setup_title),
                    description = stringResource(R.string.ai_setup_description),
                    showChevron = true,
                    onClick = { showSetupWizardDialog = true }
                )
            )
        )

        // Group 2: Runtime
        MetrolistSettingsGroup(
            title = stringResource(R.string.ai_runtime),
            items = listOf(
                MetrolistSettingsItem(
                    title = stringResource(R.string.ai_translation_enable),
                    description = stringResource(R.string.ai_translation_enable_description),
                    isSwitch = true,
                    checked = isAiTranslationEnabled,
                    onCheckedChange = onAiTranslationEnabledChange
                ),
                MetrolistSettingsItem(
                    title = stringResource(R.string.ai_translation_trigger),
                    description = stringResource(
                        if (activationMode == "Manual") R.string.ai_translation_trigger_manual_description
                        else R.string.ai_translation_trigger_automatic_description
                    ),
                    valueLabel = stringResource(
                        if (activationMode == "Manual") R.string.ai_trigger_manual
                        else R.string.ai_trigger_automatic
                    ),
                    showChevron = true,
                    onClick = { showTriggerDialog = true }
                ),
                MetrolistSettingsItem(
                    title = stringResource(R.string.mode),
                    description = stringResource(R.string.settings_translation_mode_summary),
                    valueLabel = when (translateMode) {
                        LITERAL_MODE -> stringResource(R.string.ai_translation_literal)
                        TRANSCRIBED_MODE -> stringResource(R.string.ai_translation_transcribed)
                        else -> translateMode
                    },
                    enabled = !directProvider,
                    showChevron = true,
                    onClick = { if (!directProvider) showTranslateModeDialog = true }
                ),
                MetrolistSettingsItem(
                    title = stringResource(R.string.ai_target_language),
                    valueLabel = PrefKeys.LanguageCodeToName[translateLanguage] ?: translateLanguage,
                    showChevron = true,
                    onClick = { showLanguageDialog = true }
                )
            )
        )

        // Group 3: Provider
        MetrolistSettingsGroup(
            title = stringResource(R.string.ai_provider),
            items = listOf(
                MetrolistSettingsItem(
                    title = stringResource(R.string.ai_provider),
                    description = stringResource(R.string.settings_translation_provider_summary),
                    valueLabel = lyricsAiProviderLabel(provider),
                    showChevron = true,
                    onClick = { showProviderDialog = true }
                )
            )
        )

        // Group 4: Connection (when not a keyless direct service)
        if (!directProvider) {
            MetrolistSettingsGroup(
                title = stringResource(R.string.ai_connection),
                items = buildList {
                    if (provider == "OpenRouter") {
                        add(
                            MetrolistSettingsItem(
                                title = stringResource(R.string.openrouter_sign_in_title),
                                customContent = {
                                    val uriHandler = LocalUriHandler.current
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (openRouterApiKey.isNotBlank()) {
                                                    onOpenRouterApiKeyChange("")
                                                } else {
                                                    runCatching { uriHandler.openUri("https://openrouter.ai/settings/keys") }
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.openrouter_sign_in_title),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            Text(
                                                text = stringResource(
                                                    if (openRouterApiKey.isNotBlank()) R.string.openrouter_signed_in_description
                                                    else R.string.openrouter_sign_in_description
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            text = stringResource(
                                                if (openRouterApiKey.isNotBlank()) R.string.disconnect_cast else R.string.sign_in
                                            ),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                        )
                                    }
                                }
                            )
                        )
                    }

                    add(
                        MetrolistSettingsItem(
                            title = stringResource(if (provider == DEEPL_PROVIDER) R.string.ai_deepl_api_key else R.string.ai_api_key_label),
                            description = stringResource(if (currentKey.isBlank()) R.string.required else R.string.configured),
                            valueLabel = if (currentKey.isNotBlank()) "••••••••" else "",
                            showChevron = true,
                            onClick = { showApiKeyDialog = true }
                        )
                    )

                    if (currentPreset.editableEndpoint) {
                        add(
                            MetrolistSettingsItem(
                                title = stringResource(R.string.ai_base_url),
                                description = openRouterBaseUrl.ifBlank { currentPreset.endpoint },
                                valueLabel = openRouterBaseUrl.ifBlank { currentPreset.endpoint },
                                showChevron = true,
                                onClick = { showBaseUrlDialog = true }
                            )
                        )
                    }

                    if (provider == DEEPL_PROVIDER) {
                        add(
                            MetrolistSettingsItem(
                                title = stringResource(R.string.ai_deepl_formality),
                                description = stringResource(R.string.settings_translation_formality_summary),
                                valueLabel = when (deeplFormality) {
                                    "default" -> stringResource(R.string.ai_deepl_formality_default)
                                    "more" -> stringResource(R.string.ai_deepl_formality_more)
                                    "less" -> stringResource(R.string.ai_deepl_formality_less)
                                    else -> deeplFormality
                                },
                                showChevron = true,
                                onClick = { showDeeplFormalityDialog = true }
                            )
                        )
                    }
                }
            )
        }

        // Group 5: Models (Only for AI providers, when not direct service)
        if (currentPreset.isAi && !directProvider) {
            MetrolistSettingsGroup(
                title = stringResource(R.string.ai_models),
                items = listOf(
                    MetrolistSettingsItem(
                        title = stringResource(R.string.ai_translation_model),
                        description = stringResource(R.string.settings_translation_model_summary),
                        valueLabel = openRouterModel.ifBlank { stringResource(R.string.ai_provider_default) },
                        enabled = modelsReady,
                        showChevron = true,
                        onClick = { if (modelsReady) showModelDialog = true }
                    )
                )
            )
        }

        // Group 6: Prompt (Always present in Metrolist)
        MetrolistSettingsGroup(
            title = stringResource(R.string.ai_prompt),
            description = stringResource(R.string.ai_prompt_line_count_description),
            items = listOf(
                MetrolistSettingsItem(
                    title = stringResource(R.string.ai_system_prompt_label),
                    description = stringResource(
                        if (aiSystemPrompt.isBlank()) R.string.ai_default_json_array_prompt else R.string.configured
                    ),
                    valueLabel = if (aiSystemPrompt.isNotBlank()) stringResource(R.string.configured) else "",
                    enabled = currentPreset.isAi && !directProvider && (openRouterApiKey.isNotBlank() || currentPreset.publicModelList),
                    showChevron = true,
                    onClick = {
                        if (currentPreset.isAi && !directProvider && (openRouterApiKey.isNotBlank() || currentPreset.publicModelList)) {
                            showSystemPromptDialog = true
                        }
                    }
                )
            )
        )

        // Group 7: Connection Test (Matches Metrolist AiLyricsConnectionTestSetting)
        if (!directProvider) {
            MetrolistSettingsGroup(
                title = stringResource(R.string.ai_test_connection),
                items = listOf(
                    MetrolistSettingsItem(
                        title = stringResource(R.string.ai_test_connection),
                        customContent = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.ai_test_connection_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                TextButton(
                                    onClick = {
                                        val canRun = when (provider) {
                                            DEEPL_PROVIDER -> deeplApiKey.isNotBlank()
                                            DEEPL_KEYLESS_PROVIDER, GOOGLE_TRANSLATE_PROVIDER -> true
                                            else -> openRouterApiKey.isNotBlank() || currentPreset.publicModelList
                                        }

                                        if (!canRun) {
                                            testState = ConnectionTestState.MissingCredentials
                                            return@TextButton
                                        }

                                        testState = ConnectionTestState.Testing
                                        coroutineScope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                AiLyricsTranslator.translate(
                                                    lines = listOf("Hello"),
                                                    targetLanguage = translateLanguage,
                                                    apiKey = if (provider == DEEPL_PROVIDER) deeplApiKey else openRouterApiKey,
                                                    baseUrl = openRouterBaseUrl.ifBlank { currentPreset.endpoint },
                                                    model = openRouterModel,
                                                    mode = translateMode,
                                                    provider = provider,
                                                    deeplFormality = deeplFormality,
                                                    customSystemPrompt = aiSystemPrompt,
                                                    maxRetries = 1
                                                )
                                            }

                                            result.fold(
                                                onSuccess = { translatedLines ->
                                                    val sample = translatedLines.firstOrNull().orEmpty().trim()
                                                    testState = ConnectionTestState.Success(sample)
                                                },
                                                onFailure = { error ->
                                                    testState = ConnectionTestState.Failed(error.message ?: "Unknown error")
                                                }
                                            )
                                        }
                                    },
                                    enabled = testState != ConnectionTestState.Testing
                                ) {
                                    Text(
                                        stringResource(
                                            if (testState == ConnectionTestState.Testing) R.string.ai_test_connection_running
                                            else R.string.ai_test_connection
                                        )
                                    )
                                }

                                when (val state = testState) {
                                    ConnectionTestState.Idle -> {}
                                    ConnectionTestState.Testing -> {
                                        Text(
                                            stringResource(R.string.ai_test_connection_running),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    is ConnectionTestState.Success -> {
                                        if (state.sample.isNotBlank()) {
                                            Text(
                                                stringResource(R.string.ai_test_translation_result, state.sample),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        } else {
                                            Text(
                                                stringResource(R.string.ai_test_connection_success),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    is ConnectionTestState.Failed -> {
                                        Text(
                                            stringResource(R.string.ai_test_connection_failed),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    ConnectionTestState.MissingCredentials -> {
                                        Text(
                                            stringResource(R.string.ai_test_missing_credentials),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    )
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialogSurface(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            content()
        }
    }
}

@Composable
private fun AiChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f)) { content() }
        if (selected) {
            Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProviderPickerDialog(
    currentProvider: String,
    onSelect: (AiProviderPreset) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsDialogSurface(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_provider),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                AiProviderGroup.entries.forEach { group ->
                    val options = ALL_PRESETS.filter { it.group == group }
                    if (options.isNotEmpty()) {
                        item(key = "group_${group.name}") {
                            Text(
                                text = stringResource(group.titleRes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(options, key = { it.id }) { option ->
                            val selected = option.id.equals(currentProvider, ignoreCase = true)
                            AiChoiceRow(
                                selected = selected,
                                onClick = { onSelect(option) }
                            ) {
                                Column {
                                    Text(lyricsAiProviderLabel(option.id), style = MaterialTheme.typography.bodyLarge)
                                    val subText = when {
                                        option.group == AiProviderGroup.SERVICE -> {
                                            if (option.id == DEEPL_PROVIDER) stringResource(R.string.ai_deepl_api_key)
                                            else stringResource(R.string.ai_keyless_no_key_needed)
                                        }
                                        option.editableEndpoint -> stringResource(R.string.ai_base_url)
                                        option.defaultModel.isNotBlank() -> option.defaultModel
                                        else -> null
                                    }
                                    if (subText != null) {
                                        Text(
                                            text = subText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    currentPreset: AiProviderPreset,
    currentModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val typed = query.trim()

    val models = remember(currentPreset.id) {
        MODELS_BY_PRESET[currentPreset.id] ?: emptyList()
    }

    val filteredModels = remember(models, typed) {
        if (typed.isEmpty()) {
            models
        } else {
            models.filter { it.id.contains(typed, ignoreCase = true) || it.name.contains(typed, ignoreCase = true) }
        }
    }

    SettingsDialogSurface(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ai_translation_model),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.ai_model_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                if (typed.isNotEmpty() && models.none { it.id.equals(typed, ignoreCase = true) }) {
                    item(key = "custom_typed_entry") {
                        AiChoiceRow(
                            selected = currentModel == typed,
                            onClick = { onSelect(typed) }
                        ) {
                            Text(
                                "${stringResource(R.string.ai_model_custom_id)}: $typed",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                if (filteredModels.isEmpty() && typed.isNotEmpty()) {
                    item(key = "empty_models") {
                        Text(
                            text = stringResource(R.string.ai_model_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                        )
                    }
                }

                items(filteredModels, key = { it.id }) { modelInfo ->
                    AiChoiceRow(
                        selected = currentModel == modelInfo.id,
                        onClick = { onSelect(modelInfo.id) }
                    ) {
                        AiModelDetails(modelInfo)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun AiModelDetails(
    info: AiModelInfo,
) {
    Column {
        val displayName = info.name.substringAfterLast('/')
        Text(displayName, style = MaterialTheme.typography.bodyLarge)
        if (displayName != info.id) {
            Text(
                text = info.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val price = when {
            info.free -> stringResource(R.string.ai_model_free)
            info.inputUsdPerMTok != null && info.outputUsdPerMTok != null -> {
                stringResource(R.string.ai_model_price, formatUsd(info.inputUsdPerMTok), formatUsd(info.outputUsdPerMTok))
            }
            else -> null
        }
        val tps = info.tokensPerSecond?.let { stringResource(R.string.ai_model_speed, it.roundToInt().toString()) }
        listOfNotNull(price, tps).takeIf { it.isNotEmpty() }?.let {
            Text(
                text = it.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TriggerPickerDialog(
    currentMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsDialogSurface(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_translation_trigger),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            AiChoiceRow(
                selected = currentMode != "Manual",
                onClick = { onSelect("Automatic") }
            ) {
                Column {
                    Text(stringResource(R.string.ai_trigger_automatic), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.ai_translation_trigger_automatic_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AiChoiceRow(
                selected = currentMode == "Manual",
                onClick = { onSelect("Manual") }
            ) {
                Column {
                    Text(stringResource(R.string.ai_trigger_manual), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.ai_translation_trigger_manual_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun TranslateModePickerDialog(
    currentMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsDialogSurface(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.mode),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            AiChoiceRow(
                selected = currentMode == LITERAL_MODE,
                onClick = { onSelect(LITERAL_MODE) }
            ) {
                Column {
                    Text(stringResource(R.string.ai_translation_literal), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.ai_translation_literal_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AiChoiceRow(
                selected = currentMode == TRANSCRIBED_MODE,
                onClick = { onSelect(TRANSCRIBED_MODE) }
            ) {
                Column {
                    Text(stringResource(R.string.ai_translation_transcribed), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.ai_translation_transcribed_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun FormalityPickerDialog(
    currentFormality: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsDialogSurface(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_deepl_formality),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            listOf(
                "default" to stringResource(R.string.ai_deepl_formality_default),
                "more" to stringResource(R.string.ai_deepl_formality_more),
                "less" to stringResource(R.string.ai_deepl_formality_less)
            ).forEach { (code, label) ->
                AiChoiceRow(
                    selected = currentFormality == code,
                    onClick = { onSelect(code) }
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    currentCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val trimmed = searchQuery.trim()

    val filteredLanguages = remember(trimmed) {
        if (trimmed.isEmpty()) {
            PrefKeys.LanguageCodeToName.toList()
        } else {
            PrefKeys.LanguageCodeToName.filter { (code, name) ->
                code.contains(trimmed, ignoreCase = true) || name.contains(trimmed, ignoreCase = true)
            }.toList()
        }
    }

    SettingsDialogSurface(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_target_language),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.action_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                items(filteredLanguages, key = { it.first }) { (code, name) ->
                    AiChoiceRow(
                        selected = currentCode.equals(code, ignoreCase = true),
                        onClick = { onSelect(code) }
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun TextSettingDialog(
    title: String,
    initialValue: String,
    placeholder: String? = null,
    isSecret: Boolean = false,
    isMultiline: Boolean = false,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var textValue by remember { mutableStateOf(initialValue) }
    var secretVisible by remember { mutableStateOf(false) }

    SettingsDialogSurface(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)

            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = placeholder?.let { { Text(it) } },
                visualTransformation = if (isSecret && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = !isMultiline,
                minLines = if (isMultiline) 4 else 1,
                maxLines = if (isMultiline) 12 else 1,
                trailingIcon = if (isSecret) {
                    {
                        IconButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                imageVector = if (secretVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                } else null
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = { onDone(textValue.trim()) },
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}

@Composable
private fun AiTranslationSetupDialog(
    currentApiKey: String,
    currentModel: String,
    currentLanguage: String,
    onComplete: (apiKey: String, model: String, language: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var apiKey by rememberSaveable { mutableStateOf(currentApiKey) }
    var model by rememberSaveable { mutableStateOf(currentModel.ifBlank { "google/gemma-4-26b-a4b-it:free" }) }
    var language by rememberSaveable { mutableStateOf(currentLanguage) }
    val uriHandler = LocalUriHandler.current

    SettingsDialogSurface(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_setup_step, step + 1, 3),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            when (step) {
                0 -> {
                    Text(
                        text = stringResource(R.string.ai_setup_account),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.ai_setup_account_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.ai_setup_limits, 20, 50, 10, 1000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            runCatching { uriHandler.openUri("https://openrouter.ai/sign-up") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.ai_setup_create_account))
                    }
                    TextButton(
                        onClick = {
                            runCatching { uriHandler.openUri("https://openrouter.ai/docs/api_reference/limits") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.ai_setup_view_limits))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { step = 1 }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                }
                1 -> {
                    Text(
                        text = stringResource(R.string.ai_setup_sign_in),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.openrouter_sign_in_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.ai_api_key_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { step = 0 }) {
                            Text(stringResource(R.string.btn_back))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { step = 2 },
                            enabled = apiKey.isNotBlank()
                        ) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                }
                2 -> {
                    Text(
                        text = stringResource(R.string.ai_setup_configure),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.ai_setup_model_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(stringResource(R.string.ai_translation_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${stringResource(R.string.ai_target_language)}: ${PrefKeys.LanguageCodeToName[language] ?: language}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.ai_setup_verify_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { step = 1 }) {
                            Text(stringResource(R.string.btn_back))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onComplete(apiKey, model, language)
                                onDismiss()
                            }
                        ) {
                            Text(stringResource(R.string.done))
                        }
                    }
                }
            }
        }
    }
}
