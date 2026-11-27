package app.spotxero.ui.component.lyrics

import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.spotxero.ui.component.settings.SettingsGroup
import app.spotxero.ui.component.settings.SettingsItem
import app.spotxero.ui.component.settings.SettingsSection
import app.spotxero.ui.component.settings.SettingsSwitchItem
import app.spotxero.ui.component.settings.SurfaceCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.core.PrefKeys
import app.spotxero.hooks.lyrics.AiLyricsTranslator
import app.spotxero.hooks.lyrics.AiModelCatalog
import app.spotxero.hooks.lyrics.AiModelInfo
import app.spotxero.hooks.lyrics.AiProviderGroup
import app.spotxero.hooks.lyrics.AiProviderPreset
import app.spotxero.hooks.lyrics.AiProviderPresets
import app.spotxero.hooks.lyrics.LyricsTranslationException
import app.spotxero.hooks.lyrics.LyricsTranslationFailure
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

fun isDirectLyricsTranslationProvider(provider: String): Boolean =
    provider == DEEPL_KEYLESS_PROVIDER || provider == GOOGLE_TRANSLATE_PROVIDER

val ALL_PRESETS: List<AiProviderPreset> get() = AiProviderPresets.all

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
    data class Failed(val failure: LyricsTranslationFailure?, val message: String) : ConnectionTestState
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
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }

    val currentKey = if (provider == DEEPL_PROVIDER) deeplApiKey else openRouterApiKey
    val isCustomEndpoint = currentPreset.group == AiProviderGroup.CUSTOM || currentPreset.editableEndpoint
    val modelsReady = openRouterApiKey.isNotBlank() || currentPreset.publicModelList || isCustomEndpoint

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
            apiKey = currentKey,
            baseUrl = openRouterBaseUrl.ifBlank { currentPreset.endpoint },
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSection(
            title = stringResource(R.string.ai_setup_title)
        ) {
            SettingsGroup {
                SettingsItem(
                    title = stringResource(R.string.ai_setup_title),
                    subtitle = stringResource(R.string.ai_setup_description),
                    showChevron = true,
                    onClick = { showSetupWizardDialog = true }
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.ai_runtime)
        ) {
            SettingsGroup {
                SettingsSwitchItem(
                    title = stringResource(R.string.ai_translation_enable),
                    subtitle = stringResource(R.string.ai_translation_enable_description),
                    checked = isAiTranslationEnabled,
                    onCheckedChange = onAiTranslationEnabledChange
                )
                SettingsItem(
                    title = stringResource(R.string.mode),
                    subtitle = stringResource(R.string.settings_translation_mode_summary),
                    valueLabel = when (translateMode) {
                        LITERAL_MODE -> stringResource(R.string.ai_translation_literal)
                        TRANSCRIBED_MODE -> stringResource(R.string.ai_translation_transcribed)
                        else -> translateMode
                    },
                    enabled = !directProvider,
                    showChevron = true,
                    onClick = { if (!directProvider) showTranslateModeDialog = true }
                )
                SettingsItem(
                    title = stringResource(R.string.ai_target_language),
                    valueLabel = PrefKeys.LanguageCodeToName[translateLanguage] ?: translateLanguage,
                    showChevron = true,
                    onClick = { showLanguageDialog = true }
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.ai_provider)
        ) {
            SettingsGroup {
                SettingsItem(
                    title = stringResource(R.string.ai_provider),
                    subtitle = stringResource(R.string.settings_translation_provider_summary),
                    valueLabel = lyricsAiProviderLabel(provider),
                    showChevron = true,
                    onClick = { showProviderDialog = true }
                )
            }
        }

        if (!directProvider) {
            SettingsSection(
                title = stringResource(R.string.ai_connection)
            ) {
                SettingsGroup {
                    if (provider == "OpenRouter") {
                        val uriHandler = LocalUriHandler.current
                        SurfaceCard(
                            onClick = {
                                if (openRouterApiKey.isNotBlank()) {
                                    onOpenRouterApiKeyChange("")
                                } else {
                                    runCatching { uriHandler.openUri("https://openrouter.ai/settings/keys") }
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.openrouter_sign_in_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = stringResource(
                                            if (openRouterApiKey.isNotBlank()) R.string.openrouter_signed_in_description
                                            else R.string.openrouter_sign_in_description
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
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
                    }

                    SettingsItem(
                        title = stringResource(if (provider == DEEPL_PROVIDER) R.string.ai_deepl_api_key else R.string.ai_api_key_label),
                        subtitle = stringResource(if (currentKey.isBlank()) R.string.required else R.string.configured),
                        valueLabel = if (currentKey.isNotBlank()) "••••••••" else "",
                        showChevron = true,
                        onClick = { showApiKeyDialog = true }
                    )

                    if (currentPreset.editableEndpoint) {
                        SettingsItem(
                            title = stringResource(R.string.ai_base_url),
                            subtitle = openRouterBaseUrl.ifBlank { currentPreset.endpoint },
                            valueLabel = openRouterBaseUrl.ifBlank { currentPreset.endpoint },
                            showChevron = true,
                            onClick = { showBaseUrlDialog = true }
                        )
                    }

                    if (provider == DEEPL_PROVIDER) {
                        SettingsItem(
                            title = stringResource(R.string.ai_deepl_formality),
                            subtitle = stringResource(R.string.settings_translation_formality_summary),
                            valueLabel = when (deeplFormality) {
                                "default" -> stringResource(R.string.ai_deepl_formality_default)
                                "more" -> stringResource(R.string.ai_deepl_formality_more)
                                "less" -> stringResource(R.string.ai_deepl_formality_less)
                                else -> deeplFormality
                            },
                            showChevron = true,
                            onClick = { showDeeplFormalityDialog = true }
                        )
                    }
                }
            }
        }

        if (currentPreset.isAi && !directProvider) {
            SettingsSection(
                title = stringResource(R.string.ai_models)
            ) {
                SettingsGroup {
                    SettingsItem(
                        title = stringResource(R.string.ai_translation_model),
                        subtitle = stringResource(R.string.settings_translation_model_summary),
                        valueLabel = openRouterModel.ifBlank { stringResource(R.string.ai_provider_default) },
                        enabled = modelsReady,
                        showChevron = true,
                        onClick = { if (modelsReady) showModelDialog = true }
                    )
                }
            }
        }

        val canEditPrompt = currentPreset.isAi && !directProvider && (openRouterApiKey.isNotBlank() || currentPreset.publicModelList || isCustomEndpoint)
        SettingsSection(
            title = stringResource(R.string.ai_prompt)
        ) {
            SettingsGroup {
                SettingsItem(
                    title = stringResource(R.string.ai_system_prompt_label),
                    subtitle = stringResource(
                        if (aiSystemPrompt.isBlank()) R.string.ai_default_json_array_prompt else R.string.configured
                    ),
                    valueLabel = if (aiSystemPrompt.isNotBlank()) stringResource(R.string.configured) else "",
                    enabled = canEditPrompt,
                    showChevron = true,
                    onClick = {
                        if (canEditPrompt) {
                            showSystemPromptDialog = true
                        }
                    }
                )
            }
        }

        if (!directProvider) {
            SettingsSection(
                title = stringResource(R.string.ai_test_connection)
            ) {
                SurfaceCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ai_test_connection_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        TextButton(
                            onClick = {
                                val canRun = when (provider) {
                                    DEEPL_PROVIDER -> deeplApiKey.isNotBlank()
                                    DEEPL_KEYLESS_PROVIDER, GOOGLE_TRANSLATE_PROVIDER -> true
                                    "Custom", "OpenAI-compatible", "Anthropic-compatible", "Gemini-compatible" -> true
                                    else -> openRouterApiKey.isNotBlank() || currentPreset.publicModelList || isCustomEndpoint
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
                                            val failure = (error as? LyricsTranslationException)?.failure
                                            testState = ConnectionTestState.Failed(failure, error.message ?: "Unknown error")
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            is ConnectionTestState.Success -> {
                                if (state.sample.isNotBlank()) {
                                    Text(
                                        stringResource(R.string.ai_test_translation_result, state.sample),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    Text(
                                        stringResource(R.string.ai_test_connection_success),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            is ConnectionTestState.Failed -> {
                                val failureLabel = when (state.failure) {
                                    LyricsTranslationFailure.AUTHENTICATION -> "Authentication failed: check your API key"
                                    LyricsTranslationFailure.CREDIT_LIMIT -> "Credit limit reached: quota/balance exhausted"
                                    LyricsTranslationFailure.RATE_LIMIT -> "Rate limit hit: please wait before retrying"
                                    LyricsTranslationFailure.FREE_DAILY_LIMIT -> "Free model daily quota exceeded"
                                    LyricsTranslationFailure.FREE_MINUTE_LIMIT -> "Free model minute rate limit hit"
                                    LyricsTranslationFailure.MODEL_BUSY -> "Free model busy: provider overloaded"
                                    LyricsTranslationFailure.MODEL_UNAVAILABLE -> "Model unavailable or decommissioned"
                                    LyricsTranslationFailure.TIMEOUT -> "Request timed out"
                                    LyricsTranslationFailure.NETWORK -> "Network error: cannot reach endpoint"
                                    LyricsTranslationFailure.INVALID_RESPONSE -> "Invalid response from provider"
                                    LyricsTranslationFailure.EMPTY_RESPONSE -> "Empty response received"
                                    LyricsTranslationFailure.INCOMPLETE_RESPONSE -> "Incomplete response received"
                                    LyricsTranslationFailure.DATA_POLICY -> "Data policy violation or content rejected"
                                    LyricsTranslationFailure.REQUEST_FAILED -> "Request failed"
                                    null -> null
                                }
                                val errorText = if (failureLabel != null) {
                                    "$failureLabel (${state.message})"
                                } else {
                                    state.message.ifBlank { stringResource(R.string.ai_test_connection_failed) }
                                }
                                Text(
                                    text = errorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            ConnectionTestState.MissingCredentials -> {
                                Text(
                                    stringResource(R.string.ai_test_missing_credentials),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
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
            Icon(painter = painterResource(id = R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PickerDialogScaffold(
    title: String,
    onDismiss: () -> Unit,
    trailingHeader: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
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
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                trailingHeader?.invoke()
            }
            content()
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
private fun ProviderPickerDialog(
    currentProvider: String,
    onSelect: (AiProviderPreset) -> Unit,
    onDismiss: () -> Unit
) {
    PickerDialogScaffold(
        title = stringResource(R.string.ai_provider),
        onDismiss = onDismiss,
    ) {
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
    }
}

private sealed interface ModelsState {
    data object Loading : ModelsState
    data class Loaded(val models: List<AiModelInfo>) : ModelsState
    data object Failed : ModelsState
}

@Composable
private fun ModelPickerDialog(
    currentPreset: AiProviderPreset,
    apiKey: String,
    baseUrl: String,
    currentModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val typed = query.trim()

    val state by produceState<ModelsState>(ModelsState.Loading, currentPreset.id, apiKey, baseUrl, reload) {
        value = ModelsState.Loading
        value = AiModelCatalog.models(currentPreset, apiKey, baseUrl, refresh = reload > 0).fold(
            onSuccess = { ModelsState.Loaded(it) },
            onFailure = { ModelsState.Failed }
        )
    }

    PickerDialogScaffold(
        title = stringResource(R.string.ai_translation_model),
        onDismiss = onDismiss,
        trailingHeader = {
            TextButton(onClick = { reload++ }) {
                Text(stringResource(R.string.refresh))
            }
        }
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.ai_model_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        )

        val models = (state as? ModelsState.Loaded)?.models.orEmpty()

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

            when (val current = state) {
                ModelsState.Loading -> {
                    items(5) {
                        AiModelSkeleton()
                    }
                }

                ModelsState.Failed -> {
                    item(key = "failed") {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(
                                    if (apiKey.isBlank() && !currentPreset.publicModelList) {
                                        R.string.ai_model_api_key_needed
                                    } else {
                                        R.string.ai_model_load_failed
                                    }
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { reload++ }) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                    }
                }

                is ModelsState.Loaded -> {
                    val filteredModels = if (typed.isEmpty()) {
                        current.models
                    } else {
                        current.models.filter {
                            it.id.contains(typed, ignoreCase = true) || it.name.contains(typed, ignoreCase = true)
                        }
                    }

                    if (filteredModels.isEmpty() && typed.isNotEmpty()) {
                        item(key = "empty_models") {
                            Text(
                                text = stringResource(R.string.ai_model_none_found),
                                style = MaterialTheme.typography.bodySmall,
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
                            AiModelDetails(modelInfo, currentPreset)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiModelSkeleton() {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(16.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
    }
}

@Composable
private fun AiModelDetails(
    info: AiModelInfo,
    preset: AiProviderPreset,
) {
    val speed by produceState<Double?>(null, info.id) {
        if (preset.id == "OpenRouter") value = AiModelCatalog.throughput(info.id)
    }
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
        val tps = (speed ?: info.tokensPerSecond)?.let { stringResource(R.string.ai_model_speed, it.roundToInt().toString()) }
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
private fun TranslateModePickerDialog(
    currentMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    PickerDialogScaffold(
        title = stringResource(R.string.mode),
        onDismiss = onDismiss,
    ) {
        listOf(
            Triple(LITERAL_MODE, R.string.ai_translation_literal, R.string.ai_translation_literal_desc),
            Triple(TRANSCRIBED_MODE, R.string.ai_translation_transcribed, R.string.ai_translation_transcribed_desc),
        ).forEach { (mode, titleRes, descRes) ->
            AiChoiceRow(
                selected = currentMode == mode,
                onClick = { onSelect(mode) }
            ) {
                Column {
                    Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    PickerDialogScaffold(
        title = stringResource(R.string.ai_deepl_formality),
        onDismiss = onDismiss,
    ) {
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

    PickerDialogScaffold(
        title = stringResource(R.string.ai_target_language),
        onDismiss = onDismiss,
    ) {
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
                                painter = painterResource(id = if (secretVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
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
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var model by rememberSaveable { mutableStateOf(currentModel.ifBlank { AiProviderPresets.FREE_LYRICS_AI_MODEL }) }
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
                        style = MaterialTheme.typography.bodySmall,
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
                        style = MaterialTheme.typography.bodySmall,
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
                        style = MaterialTheme.typography.bodySmall,
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
                        style = MaterialTheme.typography.bodySmall,
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
