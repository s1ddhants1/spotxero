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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.spotxero.R
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.hooks.lyrics.AiLyricsTranslator
import app.spotxero.ui.theme.AppIcons
import app.spotxero.ui.theme.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CUSTOM_PROVIDER = "Custom"
private const val DEEPL_PROVIDER = "DeepL"
private const val DEEPL_KEYLESS_PROVIDER = "DeepL (keyless)"
private const val GOOGLE_TRANSLATE_PROVIDER = "Google Translate"
private const val LITERAL_MODE = "Literal"
private const val TRANSCRIBED_MODE = "Transcribed"

enum class ProviderCategory(val titleRes: Int) {
    GATEWAYS(R.string.ai_provider_group_gateways),
    DIRECT(R.string.ai_provider_group_direct),
    CUSTOM(R.string.ai_provider_group_custom),
    SERVICES(R.string.ai_provider_group_services)
}

data class ProviderOption(
    val id: String,
    val name: String,
    val category: ProviderCategory,
    val defaultEndpoint: String,
    val defaultModel: String,
    val isAi: Boolean = true,
    val isKeyless: Boolean = false
)

private val ALL_PROVIDERS = listOf(
    // Gateways
    ProviderOption("OpenRouter", "OpenRouter", ProviderCategory.GATEWAYS, PrefKeys.OpenRouterDefaultBaseUrl, PrefKeys.OpenRouterDefaultModel),
    ProviderOption("Groq", "Groq", ProviderCategory.GATEWAYS, "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-20b"),

    // Direct providers
    ProviderOption("OpenAI", "OpenAI", ProviderCategory.DIRECT, "https://api.openai.com/v1/chat/completions", "gpt-6-luna"),
    ProviderOption("Claude", "Anthropic", ProviderCategory.DIRECT, PrefKeys.AnthropicDefaultEndpoint, PrefKeys.AnthropicDefaultModel),
    ProviderOption("Gemini", "Gemini", ProviderCategory.DIRECT, PrefKeys.GeminiDefaultEndpoint, PrefKeys.GeminiDefaultModel),
    ProviderOption("XAi", "xAI", ProviderCategory.DIRECT, "https://api.x.ai/v1/chat/completions", "grok-build-0.1"),
    ProviderOption("Mistral", "Mistral", ProviderCategory.DIRECT, "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest"),
    ProviderOption("Inception", "Inception", ProviderCategory.DIRECT, "https://api.inceptionlabs.ai/v1/chat/completions", "mercury-2.5"),
    ProviderOption("DeepSeek", "DeepSeek", ProviderCategory.DIRECT, "https://api.deepseek.com/v1/chat/completions", "deepseek-flash"),
    ProviderOption("Perplexity", "Perplexity", ProviderCategory.DIRECT, "https://api.perplexity.ai/chat/completions", "sonar"),

    // Custom
    ProviderOption(CUSTOM_PROVIDER, "Custom (OpenAI-compatible)", ProviderCategory.CUSTOM, "", ""),

    // Services
    ProviderOption(DEEPL_PROVIDER, "DeepL", ProviderCategory.SERVICES, "https://api.deepl.com/v2/translate", "", isAi = false),
    ProviderOption(DEEPL_KEYLESS_PROVIDER, "DeepL (keyless)", ProviderCategory.SERVICES, "https://oneshot-free.www.deepl.com/v1/translate", "", isAi = false, isKeyless = true),
    ProviderOption(GOOGLE_TRANSLATE_PROVIDER, "Google Translate", ProviderCategory.SERVICES, "https://translate.googleapis.com/translate_a/single", "", isAi = false, isKeyless = true),
)

private val MODELS_BY_PROVIDER = mapOf(
    "OpenRouter" to listOf(
        "qwen/qwen3.7-flash",
        "google/gemma-4-26b-a4b-it:free",
        "google/gemini-2.5-flash-lite",
        "inception/mercury-2.5-preview",
        "meta/muse-spark-1.3",
        "z-ai/glm-5.3-flash",
        "openai/gpt-oss-120b",
    ),
    "OpenAI" to listOf(
        "gpt-6-luna",
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-5.5-2026-04-23",
        "gpt-5.4-2026-03-05",
    ),
    "Claude" to listOf(
        "claude-haiku-5-5",
        "claude-3-5-haiku-latest",
        "claude-sonnet-5",
        "claude-opus-5",
    ),
    "Gemini" to listOf(
        "gemini-flash-lite-latest",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-pro-latest",
    ),
    "Perplexity" to listOf(
        "sonar",
        "sonar-pro",
        "sonar-reasoning-pro",
    ),
    "XAi" to listOf(
        "grok-build-0.1",
        "grok-4.3",
        "grok-4.6",
        "grok-4.1-fast",
    ),
    "Mistral" to listOf(
        "mistral-small-latest",
        "mistral-large-latest",
        "mistral-medium-latest",
    ),
    "Inception" to listOf(
        "mercury-2.5",
        "mercury-2",
    ),
    "DeepSeek" to listOf(
        "deepseek-flash",
        "deepseek-chat",
    ),
    "Groq" to listOf(
        "openai/gpt-oss-20b",
        "llama-3.3-70b-versatile",
    ),
)

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
        ALL_PROVIDERS.firstOrNull { it.id.equals(aiProvider, ignoreCase = true) }
            ?: ALL_PROVIDERS.first()
    }
    val isDirectService = !currentPreset.isAi

    val commonModels = remember(aiProvider) {
        MODELS_BY_PROVIDER[aiProvider] ?: emptyList()
    }

    var showSetupWizardDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showTriggerDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }

    val currentKey = if (aiProvider == DEEPL_PROVIDER) deeplApiKey else openRouterApiKey

    // Quick Setup Dialog
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
            },
            onDismiss = { showSetupWizardDialog = false }
        )
    }

    // Provider Picker Dialog
    if (showProviderDialog) {
        ProviderPickerDialog(
            currentProvider = aiProvider,
            onSelect = { option ->
                onAiProviderChange(option.id)
                if (option.defaultEndpoint.isNotBlank()) {
                    onOpenRouterBaseUrlChange(option.defaultEndpoint)
                }
                if (option.defaultModel.isNotBlank()) {
                    onOpenRouterModelChange(option.defaultModel)
                }
                if (!option.isAi) {
                    onTranslateModeChange(LITERAL_MODE)
                }
                showProviderDialog = false
            },
            onDismiss = { showProviderDialog = false }
        )
    }

    // Translation Trigger Dialog (Automatic vs Manual)
    if (showTriggerDialog) {
        TriggerPickerDialog(
            currentMode = activationMode,
            onSelect = { mode ->
                activationMode = mode
                PreferencesManager.aiTranslationActivationMode = mode
                showTriggerDialog = false
            },
            onDismiss = { showTriggerDialog = false }
        )
    }

    // Translation Mode Dialog (Translation vs Transcription)
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

    // Language Search & Picker Dialog
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

    // API Key Dialog
    if (showApiKeyDialog) {
        TextSettingDialog(
            title = stringResource(if (aiProvider == DEEPL_PROVIDER) R.string.ai_deepl_api_key else R.string.ai_api_key),
            initialValue = currentKey,
            isSecret = true,
            placeholder = stringResource(R.string.required),
            onDone = {
                if (aiProvider == DEEPL_PROVIDER) {
                    onDeeplApiKeyChange(it)
                } else {
                    onOpenRouterApiKeyChange(it)
                }
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    // Base URL Dialog
    if (showBaseUrlDialog) {
        TextSettingDialog(
            title = stringResource(R.string.ai_base_url),
            initialValue = openRouterBaseUrl,
            placeholder = currentPreset.defaultEndpoint,
            onDone = {
                onOpenRouterBaseUrlChange(it)
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false }
        )
    }

    // DeepL Formality Dialog
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

    // Searchable Model Dialog
    if (showModelDialog) {
        ModelPickerDialog(
            currentModel = openRouterModel,
            recommendedModels = commonModels,
            onSelect = {
                onOpenRouterModelChange(it)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }

    // System Prompt Dialog
    if (showSystemPromptDialog) {
        TextSettingDialog(
            title = stringResource(R.string.ai_system_prompt_label),
            initialValue = aiSystemPrompt.ifBlank { PrefKeys.DEFAULT_AI_SYSTEM_PROMPT },
            isMultiline = true,
            onDone = {
                onAiSystemPromptChange(if (it.isBlank() || it == PrefKeys.DEFAULT_AI_SYSTEM_PROMPT) "" else it)
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
                    enabled = !isDirectService,
                    onClick = { showTranslateModeDialog = true }
                ),
                MetrolistSettingsItem(
                    title = stringResource(R.string.ai_target_language),
                    valueLabel = PrefKeys.LanguageCodeToName[translateLanguage] ?: translateLanguage,
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
                    valueLabel = currentPreset.name,
                    onClick = { showProviderDialog = true }
                )
            )
        )

        // Group 4: Connection (when not a keyless direct service)
        if (!isDirectService) {
            MetrolistSettingsGroup(
                title = stringResource(R.string.ai_connection),
                items = buildList {
                    if (aiProvider == "OpenRouter") {
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
                            title = stringResource(if (aiProvider == DEEPL_PROVIDER) R.string.ai_deepl_api_key else R.string.ai_api_key),
                            description = stringResource(if (currentKey.isBlank()) R.string.required else R.string.configured),
                            valueLabel = if (currentKey.isNotBlank()) "••••••••" else "",
                            onClick = { showApiKeyDialog = true }
                        )
                    )

                    if (aiProvider == CUSTOM_PROVIDER || currentPreset.defaultEndpoint.isNotBlank()) {
                        add(
                            MetrolistSettingsItem(
                                title = stringResource(R.string.ai_base_url),
                                description = openRouterBaseUrl.ifBlank { currentPreset.defaultEndpoint.ifBlank { stringResource(R.string.not_set) } },
                                valueLabel = openRouterBaseUrl.ifBlank { currentPreset.defaultEndpoint.ifBlank { stringResource(R.string.not_set) } },
                                onClick = { showBaseUrlDialog = true }
                            )
                        )
                    }

                    if (aiProvider == DEEPL_PROVIDER) {
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
                                onClick = { showDeeplFormalityDialog = true }
                            )
                        )
                    }
                }
            )
        }

        // Group 5: Models (Only for AI providers)
        if (currentPreset.isAi) {
            val modelsReady = openRouterApiKey.isNotBlank() || currentPreset.isKeyless
            MetrolistSettingsGroup(
                title = stringResource(R.string.ai_models),
                items = listOf(
                    MetrolistSettingsItem(
                        title = stringResource(R.string.ai_translation_model),
                        description = stringResource(R.string.settings_translation_model_summary),
                        valueLabel = openRouterModel.ifBlank { stringResource(R.string.not_set) },
                        enabled = modelsReady,
                        onClick = { showModelDialog = true }
                    )
                )
            )

            // Group 6: Prompt
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
                        enabled = modelsReady,
                        onClick = { showSystemPromptDialog = true }
                    )
                )
            )
        }

        // Group 7: Connection Test (Matches Metrolist AiLyricsConnectionTestSetting)
        if (!isDirectService) {
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
                                        val canRun = when (aiProvider) {
                                            DEEPL_PROVIDER -> deeplApiKey.isNotBlank()
                                            DEEPL_KEYLESS_PROVIDER, GOOGLE_TRANSLATE_PROVIDER -> true
                                            CUSTOM_PROVIDER -> true
                                            else -> openRouterApiKey.isNotBlank()
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
                                                    apiKey = if (aiProvider == DEEPL_PROVIDER) deeplApiKey else openRouterApiKey,
                                                    baseUrl = openRouterBaseUrl.ifBlank { currentPreset.defaultEndpoint },
                                                    model = openRouterModel,
                                                    mode = translateMode,
                                                    provider = aiProvider,
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
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
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
                                        if (state.message.isNotBlank()) {
                                            Text(
                                                state.message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        }
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

@Composable
private fun AiDialogSurface(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
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
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProviderPickerDialog(
    currentProvider: String,
    onSelect: (ProviderOption) -> Unit,
    onDismiss: () -> Unit
) {
    AiDialogSurface(onDismiss = onDismiss) {
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
                ProviderCategory.entries.forEach { category ->
                    val options = ALL_PROVIDERS.filter { it.category == category }
                    if (options.isNotEmpty()) {
                        item(key = "cat_${category.name}") {
                            Text(
                                text = stringResource(category.titleRes),
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
                                    Text(option.name, style = MaterialTheme.typography.bodyLarge)
                                    if (option.defaultModel.isNotBlank()) {
                                        Text(
                                            option.defaultModel,
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
    currentModel: String,
    recommendedModels: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val typed = query.trim()

    val filteredModels = remember(recommendedModels, typed) {
        if (typed.isEmpty()) {
            recommendedModels
        } else {
            recommendedModels.filter { it.contains(typed, ignoreCase = true) }
        }
    }

    AiDialogSurface(onDismiss = onDismiss) {
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
                if (typed.isNotEmpty() && recommendedModels.none { it.equals(typed, ignoreCase = true) }) {
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

                items(filteredModels, key = { it }) { modelId ->
                    val isFree = modelId.endsWith(":free")
                    val displayName = modelId.substringAfterLast('/')
                    AiChoiceRow(
                        selected = currentModel == modelId,
                        onClick = { onSelect(modelId) }
                    ) {
                        Column {
                            Text(displayName, style = MaterialTheme.typography.bodyLarge)
                            if (displayName != modelId) {
                                Text(
                                    modelId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isFree) {
                                Text(
                                    stringResource(R.string.ai_model_free),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
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
private fun TriggerPickerDialog(
    currentMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AiDialogSurface(onDismiss = onDismiss) {
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
    AiDialogSurface(onDismiss = onDismiss) {
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
    AiDialogSurface(onDismiss = onDismiss) {
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

    AiDialogSurface(onDismiss = onDismiss) {
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

    AiDialogSurface(onDismiss = onDismiss) {
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
                TextButton(onClick = { onDone(textValue.trim()) }) {
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

    AiDialogSurface(onDismiss = onDismiss) {
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ai_setup_create_account))
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
                        label = { Text(stringResource(R.string.ai_api_key)) },
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
