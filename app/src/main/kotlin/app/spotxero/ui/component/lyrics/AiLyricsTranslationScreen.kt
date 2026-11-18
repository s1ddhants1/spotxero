package app.spotxero.ui.component.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.spotxero.R
import app.spotxero.core.PrefKeys
import app.spotxero.hooks.lyrics.AiLyricsTranslator
import app.spotxero.ui.component.dialog.DefaultDialog
import app.spotxero.ui.component.dialog.EnumDialog
import app.spotxero.ui.component.dialog.TextFieldDialog
import app.spotxero.ui.theme.AppCardDefaults
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
private const val ROMANIZED_MODE = "Romanized"

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
    val isAi: Boolean = true
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
    ProviderOption(DEEPL_KEYLESS_PROVIDER, "DeepL (keyless)", ProviderCategory.SERVICES, "https://oneshot-free.www.deepl.com/v1/translate", "", isAi = false),
    ProviderOption(GOOGLE_TRANSLATE_PROVIDER, "Google Translate", ProviderCategory.SERVICES, "https://translate.googleapis.com/translate_a/single", "", isAi = false),
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

    val currentPreset = remember(aiProvider) {
        ALL_PROVIDERS.firstOrNull { it.id.equals(aiProvider, ignoreCase = true) }
            ?: ALL_PROVIDERS.first()
    }
    val isDirectService = !currentPreset.isAi

    val commonModels = remember(aiProvider) {
        MODELS_BY_PROVIDER[aiProvider] ?: emptyList()
    }

    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderHelpDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeHelpDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }

    val providerHelpText = mapOf(
        "OpenRouter" to stringResource(R.string.ai_provider_openrouter_help),
        "OpenAI" to stringResource(R.string.ai_provider_openai_help),
        "Claude" to stringResource(R.string.ai_provider_claude_help),
        "Gemini" to stringResource(R.string.ai_provider_gemini_help),
        "Perplexity" to stringResource(R.string.ai_provider_perplexity_help),
        "XAi" to stringResource(R.string.ai_provider_xai_help),
        "Mistral" to stringResource(R.string.ai_provider_mistral_help),
        "Inception" to stringResource(R.string.ai_provider_inception_help),
        DEEPL_PROVIDER to stringResource(R.string.ai_provider_deepl_help),
    )

    // Help Dialog
    if (showProviderHelpDialog) {
        DefaultDialog(
            onDismiss = { showProviderHelpDialog = false },
            icon = { Icon(AppIcons.Info, null) },
            title = { Text(text = stringResource(R.string.ai_provider_help)) },
            buttons = {
                TextButton(onClick = { showProviderHelpDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        ) {
            Column {
                providerHelpText.forEach { (provider, help) ->
                    if (help.isNotEmpty()) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val annotatedString = buildAnnotatedString {
                            append("$provider: ")
                            val urlRegex = "https?://[^\\s]+".toRegex()
                            val match = urlRegex.find(help)
                            if (match != null) {
                                val url = match.value
                                val beforeUrl = help.substring(0, match.range.first)
                                val afterUrl = help.substring(match.range.last + 1)

                                append(beforeUrl)
                                val linkStart = length
                                append(url)
                                val linkEnd = length
                                append(afterUrl)

                                addLink(
                                    LinkAnnotation.Url(
                                        url = url,
                                        styles = TextLinkStyles(
                                            style = SpanStyle(
                                                color = primaryColor,
                                                textDecoration = TextDecoration.Underline,
                                            ),
                                        ),
                                    ),
                                    start = linkStart,
                                    end = linkEnd,
                                )
                            } else {
                                append(help)
                            }
                        }

                        Text(
                            text = annotatedString,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.padding(vertical = AppSpacing.xxs),
                        )
                    }
                }
            }
        }
    }

    if (showTranslateModeHelpDialog) {
        DefaultDialog(
            onDismiss = { showTranslateModeHelpDialog = false },
            icon = { Icon(AppIcons.Info, null) },
            title = { Text(text = stringResource(R.string.ai_translation_mode)) },
            buttons = {
                TextButton(onClick = { showTranslateModeHelpDialog = false }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        ) {
            Column {
                Text(
                    text = "${stringResource(R.string.ai_translation_literal)}:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = AppSpacing.xs),
                )
                Text(
                    text = stringResource(R.string.ai_translation_literal_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AppSpacing.sm),
                )

                Text(
                    text = "${stringResource(R.string.ai_translation_transcribed)}:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ai_translation_transcribed_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AppSpacing.sm),
                )

                Text(
                    text = "${stringResource(R.string.ai_translation_romanized)}:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ai_translation_romanized_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Categorized Provider Dialog
    if (showProviderDialog) {
        ProviderPickerDialog(
            currentProvider = aiProvider,
            onSelect = { option ->
                onAiProviderChange(option.id)
                if (option.defaultEndpoint.isNotBlank()) {
                    onOpenRouterBaseUrlChange(option.defaultEndpoint)
                } else if (option.id == CUSTOM_PROVIDER) {
                    onOpenRouterBaseUrlChange("")
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

    // Translation Mode Dialog
    if (showTranslateModeDialog) {
        EnumDialog(
            onDismiss = { showTranslateModeDialog = false },
            onSelect = {
                onTranslateModeChange(it)
                showTranslateModeDialog = false
            },
            title = stringResource(R.string.ai_translation_mode),
            current = translateMode,
            values = listOf(LITERAL_MODE, TRANSCRIBED_MODE, ROMANIZED_MODE),
            valueText = {
                when (it) {
                    LITERAL_MODE -> stringResource(R.string.ai_translation_literal)
                    TRANSCRIBED_MODE -> stringResource(R.string.ai_translation_transcribed)
                    ROMANIZED_MODE -> stringResource(R.string.ai_translation_romanized)
                    else -> it
                }
            },
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
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_api_key)) },
            icon = { Icon(AppIcons.Key, null) },
            initialTextFieldValue = TextFieldValue(text = openRouterApiKey),
            onDone = {
                onOpenRouterApiKeyChange(it)
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false },
        )
    }

    // DeepL API Key Dialog
    if (showDeeplApiKeyDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_deepl_api_key)) },
            icon = { Icon(AppIcons.Key, null) },
            initialTextFieldValue = TextFieldValue(text = deeplApiKey),
            onDone = {
                onDeeplApiKeyChange(it)
                showDeeplApiKeyDialog = false
            },
            onDismiss = { showDeeplApiKeyDialog = false },
        )
    }

    // DeepL Formality Dialog
    if (showDeeplFormalityDialog) {
        EnumDialog(
            onDismiss = { showDeeplFormalityDialog = false },
            onSelect = {
                onDeeplFormalityChange(it)
                showDeeplFormalityDialog = false
            },
            title = stringResource(R.string.ai_deepl_formality),
            current = deeplFormality,
            values = listOf("default", "more", "less"),
            valueText = {
                when (it) {
                    "default" -> stringResource(R.string.ai_deepl_formality_default)
                    "more" -> stringResource(R.string.ai_deepl_formality_more)
                    "less" -> stringResource(R.string.ai_deepl_formality_less)
                    else -> it
                }
            },
        )
    }

    // Base URL Dialog
    if (showBaseUrlDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_base_url)) },
            icon = { Icon(AppIcons.Link, null) },
            initialTextFieldValue = TextFieldValue(text = openRouterBaseUrl),
            onDone = {
                onOpenRouterBaseUrlChange(it)
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false },
        )
    }

    // Searchable Model Dialog matching Metrolist
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

    // Multiline System Prompt Dialog
    if (showSystemPromptDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_system_prompt)) },
            icon = { Icon(AppIcons.Edit, null) },
            initialTextFieldValue = TextFieldValue(text = aiSystemPrompt.ifBlank { PrefKeys.DEFAULT_AI_SYSTEM_PROMPT }),
            singleLine = false,
            maxLines = 12,
            isInputValid = { true },
            onDone = {
                onAiSystemPromptChange(if (it.isBlank() || it == PrefKeys.DEFAULT_AI_SYSTEM_PROMPT) "" else it)
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false },
            extraContent = {
                if (aiSystemPrompt.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppSpacing.xs),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                onAiSystemPromptChange("")
                                showSystemPromptDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.ai_system_prompt_reset))
                        }
                    }
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
    ) {
        // Group 1: Runtime
        Material3SettingsGroup(
            title = stringResource(R.string.ai_runtime),
            items = listOf(
                Material3SettingsItem(
                    icon = AppIcons.PowerSettingsNew,
                    title = { Text(stringResource(R.string.ai_translation_enable)) },
                    description = { Text(stringResource(R.string.ai_translation_enable_description)) },
                    trailingContent = {
                        Switch(
                            checked = isAiTranslationEnabled,
                            onCheckedChange = onAiTranslationEnabledChange,
                            thumbContent = if (isAiTranslationEnabled) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            } else null
                        )
                    },
                    onClick = { onAiTranslationEnabledChange(!isAiTranslationEnabled) }
                ),
                Material3SettingsItem(
                    icon = AppIcons.Lyrics,
                    title = { Text(stringResource(R.string.ai_translation_dual_line)) },
                    description = { Text(stringResource(R.string.ai_translation_dual_line_description)) },
                    trailingContent = {
                        Switch(
                            checked = isAiTranslationDualLine,
                            onCheckedChange = onAiTranslationDualLineChange,
                            thumbContent = if (isAiTranslationDualLine) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            } else null
                        )
                    },
                    onClick = { onAiTranslationDualLineChange(!isAiTranslationDualLine) }
                ),
                Material3SettingsItem(
                    icon = AppIcons.Translate,
                    title = { Text(stringResource(R.string.ai_translation_mode)) },
                    description = {
                        Text(
                            if (isDirectService) {
                                stringResource(R.string.ai_translation_literal)
                            } else {
                                when (translateMode) {
                                    LITERAL_MODE -> stringResource(R.string.ai_translation_literal)
                                    TRANSCRIBED_MODE -> stringResource(R.string.ai_translation_transcribed)
                                    ROMANIZED_MODE -> stringResource(R.string.ai_translation_romanized)
                                    else -> translateMode
                                }
                            }
                        )
                    },
                    enabled = !isDirectService,
                    onClick = { showTranslateModeDialog = true },
                    trailingContent = {
                        IconButton(onClick = { showTranslateModeHelpDialog = true }) {
                            Icon(
                                AppIcons.Info,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                ),
                Material3SettingsItem(
                    icon = AppIcons.Language,
                    title = { Text(stringResource(R.string.ai_target_language)) },
                    description = { Text(PrefKeys.LanguageCodeToName[translateLanguage] ?: translateLanguage) },
                    onClick = { showLanguageDialog = true }
                )
            )
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Group 2: Provider
        Material3SettingsGroup(
            title = stringResource(R.string.ai_provider),
            items = listOfNotNull(
                Material3SettingsItem(
                    icon = AppIcons.Explore,
                    title = { Text(stringResource(R.string.ai_provider)) },
                    description = { Text(currentPreset.name) },
                    onClick = { showProviderDialog = true },
                    trailingContent = {
                        IconButton(onClick = { showProviderHelpDialog = true }) {
                            Icon(
                                AppIcons.Info,
                                contentDescription = stringResource(R.string.ai_provider_help),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                ),
                if (aiProvider == CUSTOM_PROVIDER) {
                    Material3SettingsItem(
                        icon = AppIcons.Link,
                        title = { Text(stringResource(R.string.ai_base_url)) },
                        description = { Text(openRouterBaseUrl.ifBlank { stringResource(R.string.not_set) }) },
                        onClick = { showBaseUrlDialog = true }
                    )
                } else null
            )
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Group 3: Connection
        Material3SettingsGroup(
            title = stringResource(R.string.ai_connection),
            items = buildList {
                when (aiProvider) {
                    DEEPL_PROVIDER -> {
                        add(
                            Material3SettingsItem(
                                icon = AppIcons.Key,
                                title = { Text(stringResource(R.string.ai_deepl_api_key)) },
                                description = {
                                    Text(
                                        if (deeplApiKey.isNotBlank()) {
                                            "•".repeat(minOf(deeplApiKey.length, 8))
                                        } else {
                                            stringResource(R.string.required)
                                        }
                                    )
                                },
                                onClick = { showDeeplApiKeyDialog = true }
                            )
                        )
                        add(
                            Material3SettingsItem(
                                icon = AppIcons.Tune,
                                title = { Text(stringResource(R.string.ai_deepl_formality)) },
                                description = {
                                    Text(
                                        when (deeplFormality) {
                                            "default" -> stringResource(R.string.ai_deepl_formality_default)
                                            "more" -> stringResource(R.string.ai_deepl_formality_more)
                                            "less" -> stringResource(R.string.ai_deepl_formality_less)
                                            else -> deeplFormality
                                        }
                                    )
                                },
                                onClick = { showDeeplFormalityDialog = true }
                            )
                        )
                    }
                    DEEPL_KEYLESS_PROVIDER, GOOGLE_TRANSLATE_PROVIDER -> {
                        add(
                            Material3SettingsItem(
                                icon = AppIcons.Key,
                                title = { Text(stringResource(R.string.ai_keyless_no_key_needed)) },
                                description = { Text(currentPreset.name) },
                                enabled = false,
                                onClick = null
                            )
                        )
                    }
                    else -> {
                        add(
                            Material3SettingsItem(
                                icon = AppIcons.Key,
                                title = { Text(stringResource(R.string.ai_api_key)) },
                                description = {
                                    Text(
                                        if (openRouterApiKey.isNotBlank()) {
                                            "•".repeat(minOf(openRouterApiKey.length, 8))
                                        } else {
                                            if (aiProvider == CUSTOM_PROVIDER) stringResource(R.string.not_set) else stringResource(R.string.required)
                                        }
                                    )
                                },
                                onClick = { showApiKeyDialog = true }
                            )
                        )
                        if (aiProvider != CUSTOM_PROVIDER && currentPreset.defaultEndpoint.isNotBlank()) {
                            add(
                                Material3SettingsItem(
                                    icon = AppIcons.Link,
                                    title = { Text(stringResource(R.string.ai_base_url)) },
                                    description = { Text(openRouterBaseUrl.ifBlank { currentPreset.defaultEndpoint }) },
                                    onClick = { showBaseUrlDialog = true }
                                )
                            )
                        }
                    }
                }
            }
        )

        // Group 4: Models (Only for AI providers)
        if (currentPreset.isAi) {
            Spacer(modifier = Modifier.height(AppSpacing.md))
            Material3SettingsGroup(
                title = stringResource(R.string.ai_models),
                items = listOf(
                    Material3SettingsItem(
                        icon = AppIcons.DiscoverTune,
                        title = { Text(stringResource(R.string.ai_model)) },
                        description = { Text(openRouterModel.ifBlank { stringResource(R.string.not_set) }) },
                        onClick = { showModelDialog = true }
                    )
                )
            )

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // Group 5: Prompt
            Material3SettingsGroup(
                title = stringResource(R.string.ai_prompt),
                items = listOf(
                    Material3SettingsItem(
                        icon = AppIcons.Edit,
                        title = { Text(stringResource(R.string.ai_system_prompt)) },
                        description = {
                            Text(
                                if (aiSystemPrompt.isNotBlank()) {
                                    aiSystemPrompt.take(65).let { if (aiSystemPrompt.length > 65) "$it…" else it }
                                } else {
                                    stringResource(R.string.ai_system_prompt_default)
                                }
                            )
                        },
                        onClick = { showSystemPromptDialog = true }
                    )
                )
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Group 6: Connection Test (Matches Metrolist AiLyricsConnectionTestSetting)
        Material3SettingsGroup(
            title = stringResource(R.string.ai_test_connection),
            items = listOf(
                Material3SettingsItem(
                    icon = AppIcons.PlayCircle,
                    title = { Text(stringResource(R.string.ai_test_connection)) },
                    description = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.ai_test_connection_description))

                            when (val state = testState) {
                                ConnectionTestState.Idle -> {}
                                ConnectionTestState.Testing -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            stringResource(R.string.ai_test_connection_running),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                is ConnectionTestState.Success -> {
                                    Text(
                                        stringResource(R.string.ai_test_connection_success),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    if (state.sample.isNotBlank()) {
                                        Text(
                                            stringResource(R.string.ai_test_translation_result, state.sample),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                is ConnectionTestState.Failed -> {
                                    Text(
                                        stringResource(R.string.ai_test_connection_failed),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp)
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
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    },
                    trailingContent = {
                        FilledTonalButton(
                            onClick = {
                                val canRun = when (aiProvider) {
                                    DEEPL_PROVIDER -> deeplApiKey.isNotBlank()
                                    DEEPL_KEYLESS_PROVIDER, GOOGLE_TRANSLATE_PROVIDER -> true
                                    CUSTOM_PROVIDER -> true
                                    else -> openRouterApiKey.isNotBlank()
                                }

                                if (!canRun) {
                                    testState = ConnectionTestState.MissingCredentials
                                    return@FilledTonalButton
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
                            enabled = testState != ConnectionTestState.Testing,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                stringResource(
                                    if (testState == ConnectionTestState.Testing)
                                        R.string.ai_test_connection_running
                                    else
                                        R.string.ai_test_connection
                                )
                            )
                        }
                    }
                )
            )
        )

        Spacer(modifier = Modifier.height(AppSpacing.xl))
    }
}

@Composable
private fun ProviderPickerDialog(
    currentProvider: String,
    onSelect: (ProviderOption) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_provider),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = selected,
                                            role = Role.RadioButton,
                                            onClick = { onSelect(option) }
                                        )
                                        .padding(horizontal = 24.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = null
                                    )
                                    Column(Modifier.weight(1f)) {
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
                        Text(stringResource(android.R.string.cancel))
                    }
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_model),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.ai_model_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    // Custom model ID option if user typed something not in list
                    if (typed.isNotEmpty() && recommendedModels.none { it.equals(typed, ignoreCase = true) }) {
                        item(key = "custom_typed_entry") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = currentModel == typed,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(typed) }
                                    )
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                RadioButton(
                                    selected = currentModel == typed,
                                    onClick = null
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.ai_model_custom_id),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        typed,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (filteredModels.isEmpty() && typed.isNotEmpty() && recommendedModels.none { it.equals(typed, ignoreCase = true) }) {
                        // User can still select the typed model above
                    } else if (filteredModels.isEmpty() && typed.isNotEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.ai_model_none_found),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        }
                    } else {
                        items(filteredModels, key = { it }) { modelId ->
                            val selected = modelId == currentModel
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(modelId) }
                                    )
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = null
                                )
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
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
                        Text(stringResource(android.R.string.cancel))
                    }
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
    var query by remember { mutableStateOf("") }
    val typed = query.trim()

    val sortedLanguages = remember {
        PrefKeys.LanguageCodeToName.toList().sortedBy { it.second }
    }

    val filtered = remember(typed, sortedLanguages) {
        if (typed.isEmpty()) {
            sortedLanguages
        } else {
            sortedLanguages.filter { (code, name) ->
                name.contains(typed, ignoreCase = true) || code.contains(typed, ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_target_language),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search language") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(filtered, key = { it.first }) { (code, name) ->
                        val selected = code == currentCode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(code) }
                                )
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null
                            )
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    }
}
