package app.spotxero.ui.component.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.core.PrefKeys
import app.spotxero.ui.component.dialog.DefaultDialog
import app.spotxero.ui.component.dialog.EnumDialog
import app.spotxero.ui.component.dialog.TextFieldDialog
import app.spotxero.ui.theme.AppIcons
import app.spotxero.ui.theme.AppSpacing

private const val CUSTOM_PROVIDER = "Custom"
private const val DEEPL_PROVIDER = "DeepL"
private const val CUSTOM_MODEL_ENTRY = "custom_input"
private const val LITERAL_MODE = "Literal"
private const val TRANSCRIBED_MODE = "Transcribed"

private val PROVIDER_ENDPOINTS = mapOf(
    "OpenRouter" to PrefKeys.OpenRouterDefaultBaseUrl,
    "OpenAI" to "https://api.openai.com/v1/chat/completions",
    "Perplexity" to "https://api.perplexity.ai/chat/completions",
    "Claude" to "https://api.anthropic.com/v1/messages",
    "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
    "XAi" to "https://api.x.ai/v1/chat/completions",
    "Mistral" to "https://api.mistral.ai/v1/chat/completions",
    "Inception" to "https://api.inceptionlabs.ai/v1/chat/completions",
    DEEPL_PROVIDER to "https://api.deepl.com/v2/translate",
    CUSTOM_PROVIDER to "",
)

private val MODELS_BY_PROVIDER = mapOf(
    "OpenRouter" to listOf(
        "inception/mercury-2.5-preview",
        "meta/muse-spark-1.3",
        "z-ai/glm-5.3-flash",
        "qwen/qwen3.8-flash",
        "~deepseek/deepseek-v4-flash-latest",
        "~openai/gpt-mini-latest",
        "openai/gpt-oss-120b",
        "~google/gemini-flash-latest",
    ),
    "OpenAI" to listOf(
        "gpt-5.6-sol",
        "gpt-5.6-terra",
        "gpt-5.6-luna",
        "gpt-5.5-2026-04-23",
        "gpt-5.4-2026-03-05",
    ),
    "Claude" to listOf(
        "claude-opus-5",
        "claude-sonnet-5",
        "claude-haiku-4-5-20251001",
        "claude-fable-5-1",
    ),
    "Gemini" to listOf(
        "gemini-flash-lite-latest",
        "gemini-pro-latest",
        "gemini-flash-latest",
        "gemini-3.8-flash",
    ),
    "Perplexity" to listOf(
        "sonar",
        "sonar-pro",
        "sonar-reasoning-pro",
    ),
    "XAi" to listOf(
        "grok-4.3",
        "grok-4.6",
        "grok-4.1-fast",
    ),
    "Mistral" to listOf(
        "mistral-large-latest",
        "mistral-medium-latest",
        "mistral-small-latest",
        "mistral-tiny-latest",
    ),
    "Inception" to listOf(
        "mercury-2",
    ),
    DEEPL_PROVIDER to listOf(),
    CUSTOM_PROVIDER to listOf(),
)

@Composable
fun AiLyricsTranslationScreen(
    aiProvider: String,
    openRouterApiKey: String,
    openRouterBaseUrl: String,
    openRouterModel: String,
    translateLanguage: String,
    translateMode: String,
    deeplApiKey: String,
    deeplFormality: String,
    aiSystemPrompt: String,
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
    val providerHelpText = mapOf(
        "OpenRouter" to stringResource(R.string.ai_provider_openrouter_help),
        "OpenAI" to stringResource(R.string.ai_provider_openai_help),
        "Perplexity" to stringResource(R.string.ai_provider_perplexity_help),
        "Claude" to stringResource(R.string.ai_provider_claude_help),
        "Gemini" to stringResource(R.string.ai_provider_gemini_help),
        "XAi" to stringResource(R.string.ai_provider_xai_help),
        "Mistral" to stringResource(R.string.ai_provider_mistral_help),
        "Inception" to stringResource(R.string.ai_provider_inception_help),
        DEEPL_PROVIDER to stringResource(R.string.ai_provider_deepl_help),
        CUSTOM_PROVIDER to "",
    )

    val commonModels = MODELS_BY_PROVIDER[aiProvider] ?: listOf()

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
    var showCustomModelInput by rememberSaveable { mutableStateOf(false) }
    var showSystemPromptDialog by rememberSaveable { mutableStateOf(false) }

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
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ai_translation_transcribed_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showProviderDialog) {
        EnumDialog(
            onDismiss = { showProviderDialog = false },
            onSelect = {
                onAiProviderChange(it)
                if (it != CUSTOM_PROVIDER && it != DEEPL_PROVIDER) {
                    onOpenRouterBaseUrlChange(PROVIDER_ENDPOINTS[it] ?: "")
                } else {
                    onOpenRouterBaseUrlChange("")
                }
                val modelsForProvider = MODELS_BY_PROVIDER[it] ?: listOf()
                onOpenRouterModelChange(
                    if (modelsForProvider.isNotEmpty()) {
                        modelsForProvider[0]
                    } else {
                        ""
                    }
                )
                showProviderDialog = false
            },
            title = stringResource(R.string.ai_provider),
            current = aiProvider,
            values = PROVIDER_ENDPOINTS.keys.toList(),
            valueText = { if (it == CUSTOM_PROVIDER) stringResource(R.string.ai_provider_custom) else it },
        )
    }

    if (showTranslateModeDialog) {
        EnumDialog(
            onDismiss = { showTranslateModeDialog = false },
            onSelect = {
                onTranslateModeChange(it)
                showTranslateModeDialog = false
            },
            title = stringResource(R.string.ai_translation_mode),
            current = translateMode,
            values = listOf(LITERAL_MODE, TRANSCRIBED_MODE),
            valueText = {
                when (it) {
                    LITERAL_MODE -> stringResource(R.string.ai_translation_literal)
                    TRANSCRIBED_MODE -> stringResource(R.string.ai_translation_transcribed)
                    else -> it
                }
            },
        )
    }

    if (showLanguageDialog) {
        EnumDialog(
            onDismiss = { showLanguageDialog = false },
            onSelect = {
                onTranslateLanguageChange(it)
                showLanguageDialog = false
            },
            title = stringResource(R.string.ai_target_language),
            current = translateLanguage,
            values = PrefKeys.LanguageCodeToName.keys.sortedBy { PrefKeys.LanguageCodeToName[it] },
            valueText = { PrefKeys.LanguageCodeToName[it] ?: it },
        )
    }

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

    if (showBaseUrlDialog && aiProvider == CUSTOM_PROVIDER) {
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

    if (showModelDialog) {
        EnumDialog(
            onDismiss = { showModelDialog = false },
            onSelect = {
                if (it == CUSTOM_MODEL_ENTRY) {
                    showCustomModelInput = true
                    showModelDialog = false
                } else {
                    onOpenRouterModelChange(it)
                    showModelDialog = false
                }
            },
            title = stringResource(R.string.ai_model),
            current = if (openRouterModel in commonModels) openRouterModel else CUSTOM_MODEL_ENTRY,
            values = commonModels + CUSTOM_MODEL_ENTRY,
            valueText = {
                if (it == CUSTOM_MODEL_ENTRY) stringResource(R.string.ai_provider_custom) else it
            },
        )
    }

    if (showCustomModelInput) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_model)) },
            icon = { Icon(AppIcons.DiscoverTune, null) },
            initialTextFieldValue = TextFieldValue(text = openRouterModel),
            onDone = {
                onOpenRouterModelChange(it)
                showCustomModelInput = false
            },
            onDismiss = { showCustomModelInput = false },
        )
    }

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
        Material3SettingsGroup(
            title = stringResource(R.string.ai_provider),
            items = listOfNotNull(
                Material3SettingsItem(
                    icon = AppIcons.Explore,
                    title = { Text(stringResource(R.string.ai_provider)) },
                    description = { Text(aiProvider) },
                    onClick = { showProviderDialog = true },
                    trailingContent = {
                        IconButton(onClick = { showProviderHelpDialog = true }) {
                            Icon(
                                AppIcons.Info,
                                contentDescription = stringResource(R.string.ai_provider_help),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                ),
                if (aiProvider == CUSTOM_PROVIDER) {
                    Material3SettingsItem(
                        icon = AppIcons.Link,
                        title = { Text(stringResource(R.string.ai_base_url)) },
                        description = { Text(openRouterBaseUrl.ifBlank { stringResource(R.string.not_set) }) },
                        onClick = { showBaseUrlDialog = true },
                    )
                } else {
                    null
                },
            ),
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))

        Material3SettingsGroup(
            title = stringResource(R.string.ai_setup_guide),
            items = buildList {
                if (aiProvider == DEEPL_PROVIDER) {
                    add(
                        Material3SettingsItem(
                            icon = AppIcons.Key,
                            title = { Text(stringResource(R.string.ai_deepl_api_key)) },
                            description = {
                                Text(
                                    if (deeplApiKey.isNotEmpty()) {
                                        "•".repeat(minOf(deeplApiKey.length, 8))
                                    } else {
                                        stringResource(R.string.not_set)
                                    },
                                )
                            },
                            onClick = { showDeeplApiKeyDialog = true },
                        ),
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
                                    },
                                )
                            },
                            onClick = { showDeeplFormalityDialog = true },
                        ),
                    )
                } else {
                    add(
                        Material3SettingsItem(
                            icon = AppIcons.Key,
                            title = { Text(stringResource(R.string.ai_api_key)) },
                            description = {
                                Text(
                                    if (openRouterApiKey.isNotEmpty()) {
                                        "•".repeat(minOf(openRouterApiKey.length, 8))
                                    } else {
                                        stringResource(R.string.not_set)
                                    },
                                )
                            },
                            onClick = { showApiKeyDialog = true },
                        ),
                    )
                    add(
                        Material3SettingsItem(
                            icon = AppIcons.DiscoverTune,
                            title = { Text(stringResource(R.string.ai_model)) },
                            description = { Text(openRouterModel.ifBlank { stringResource(R.string.not_set) }) },
                            onClick = { showModelDialog = true },
                        ),
                    )
                }
            },
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))

        Material3SettingsGroup(
            title = stringResource(R.string.ai_translation_mode),
            items = buildList {
                if (aiProvider != DEEPL_PROVIDER) {
                    add(
                        Material3SettingsItem(
                            icon = AppIcons.Translate,
                            title = { Text(stringResource(R.string.ai_translation_mode)) },
                            description = {
                                Text(
                                    when (translateMode) {
                                        LITERAL_MODE -> stringResource(R.string.ai_translation_literal)
                                        TRANSCRIBED_MODE -> stringResource(R.string.ai_translation_transcribed)
                                        else -> translateMode
                                    },
                                )
                            },
                            onClick = { showTranslateModeDialog = true },
                            trailingContent = {
                                IconButton(onClick = { showTranslateModeHelpDialog = true }) {
                                    Icon(
                                        AppIcons.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        ),
                    )
                    add(
                        Material3SettingsItem(
                            icon = AppIcons.Edit,
                            title = { Text(stringResource(R.string.ai_system_prompt)) },
                            description = {
                                Text(
                                    if (aiSystemPrompt.isNotBlank()) {
                                        aiSystemPrompt.take(60).let {
                                            if (aiSystemPrompt.length > 60) "$it…" else it
                                        }
                                    } else {
                                        stringResource(R.string.ai_system_prompt_default)
                                    },
                                )
                            },
                            onClick = { showSystemPromptDialog = true },
                        ),
                    )
                }
                add(
                    Material3SettingsItem(
                        icon = AppIcons.Language,
                        title = { Text(stringResource(R.string.ai_target_language)) },
                        description = { Text(PrefKeys.LanguageCodeToName[translateLanguage] ?: translateLanguage) },
                        onClick = { showLanguageDialog = true },
                    ),
                )
            },
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))
    }
}
