package app.spotxero.hooks.lyrics

import android.net.Uri
import android.util.Log
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class AiProtocol {
    OPENAI,
    ANTHROPIC,
    GEMINI
}

object AiLyricsTranslator {

    private const val TAG = "SpotXero.AiLyrics"
    private val translationCache = ConcurrentHashMap<String, List<String>>()

    fun translateLyrics(lyrics: ParsedLyrics, trackKey: String? = null): ParsedLyrics {
        Log.i(TAG, "translateLyrics called: enabled=${PreferencesManager.isAiLyricsTranslationEnabled}, lines=${lyrics.lines.size}, trackKey=$trackKey")
        if (!PreferencesManager.isAiLyricsTranslationEnabled) return lyrics
        if (lyrics.lines.isEmpty()) return lyrics

        val provider = PreferencesManager.aiTranslationProvider.ifBlank { "OpenRouter" }
        val apiKey = when (provider) {
            "DeepL" -> PreferencesManager.aiTranslationDeeplApiKey
            "DeepL (keyless)", "Google Translate" -> ""
            else -> PreferencesManager.aiTranslationApiKey
        }

        Log.i(TAG, "translateLyrics: provider=$provider, keyBlank=${apiKey.isBlank()}, targetLang=${PreferencesManager.aiTranslationTargetLang}, model=${PreferencesManager.aiTranslationModel}")
        if (apiKey.isBlank() && !isCustomOrKeyless(provider, PreferencesManager.aiTranslationBaseUrl)) {
            Log.w(TAG, "API key is blank for provider $provider, skipping translation")
            return lyrics
        }

        val targetLang = PreferencesManager.aiTranslationTargetLang.ifBlank { "en" }
        val mode = PreferencesManager.aiTranslationTranslateMode.ifBlank { "Literal" }
        val cacheKey = if (!trackKey.isNullOrBlank()) "$trackKey::$targetLang::$mode" else null

        if (cacheKey != null) {
            translationCache[cacheKey]?.let { cachedLines ->
                if (cachedLines.size == lyrics.lines.size) {
                    Log.i(TAG, "Returning cached translation (${cachedLines.size} lines)")
                    return applyTranslationToLyrics(lyrics, cachedLines, PreferencesManager.isAiTranslationDualLine)
                }
            }
        }

        val rawLines = lyrics.lines.map { it.words }
        val translated = try {
            val res = translate(
                lines = rawLines,
                targetLanguage = targetLang,
                apiKey = apiKey,
                baseUrl = PreferencesManager.aiTranslationBaseUrl,
                model = PreferencesManager.aiTranslationModel,
                mode = mode,
                provider = provider,
                deeplFormality = PreferencesManager.aiTranslationDeeplFormality,
                customSystemPrompt = PreferencesManager.aiTranslationCustomPrompt
            )
            Log.i(TAG, "translate result: isSuccess=${res.isSuccess}, error=${res.exceptionOrNull()?.message}")
            res.getOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "AI lyrics translation failed: ${t.message}", t)
            null
        }

        Log.i(TAG, "translated lines count: ${translated?.size} vs original ${lyrics.lines.size}")
        if (translated != null && translated.size == lyrics.lines.size) {
            if (cacheKey != null) {
                translationCache[cacheKey] = translated
            }
            Log.i(TAG, "Successfully applied translation to lyrics! Sample translated line 0: '${translated.firstOrNull()}'")
            return applyTranslationToLyrics(lyrics, translated, PreferencesManager.isAiTranslationDualLine)
        }

        Log.w(TAG, "Returning untranslated lyrics due to mismatch or failure")
        return lyrics
    }

    fun translate(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        provider: String = "OpenRouter",
        deeplFormality: String = "default",
        customSystemPrompt: String = "",
        maxRetries: Int = 3
    ): Result<List<String>> {
        if (lines.isEmpty() || lines.all { it.isBlank() }) {
            return Result.failure(Exception("Input text is empty"))
        }

        return when {
            provider.equals("DeepL", ignoreCase = true) -> {
                translateViaDeepL(lines, targetLanguage, apiKey, deeplFormality)
            }
            provider.equals("DeepL (keyless)", ignoreCase = true) -> {
                translateViaDeepLKeyless(lines, targetLanguage)
            }
            provider.equals("Google Translate", ignoreCase = true) -> {
                translateViaGoogle(lines, targetLanguage, mode)
            }
            else -> {
                translateViaAi(
                    lines = lines,
                    targetLanguage = targetLanguage,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    mode = mode,
                    provider = provider,
                    customSystemPrompt = customSystemPrompt,
                    maxRetries = maxRetries
                )
            }
        }
    }

    fun protocolFor(provider: String, baseUrl: String, model: String): AiProtocol {
        return when {
            provider.equals("Claude", ignoreCase = true) ||
                provider.equals("Anthropic", ignoreCase = true) ||
                provider.equals("Anthropic-compatible", ignoreCase = true) ||
                baseUrl.contains("anthropic.com") -> AiProtocol.ANTHROPIC

            provider.equals("Gemini", ignoreCase = true) ||
                provider.equals("Gemini-compatible", ignoreCase = true) ||
                (baseUrl.contains("generativelanguage.googleapis.com") && !baseUrl.contains("/openai/")) -> AiProtocol.GEMINI

            else -> AiProtocol.OPENAI
        }
    }

    fun isCustomOrKeyless(provider: String, baseUrl: String = ""): Boolean {
        return provider in listOf(
            "Custom",
            "OpenAI-compatible",
            "Anthropic-compatible",
            "Gemini-compatible",
            "DeepL (keyless)",
            "Google Translate"
        ) || (baseUrl.isNotBlank() &&
            !baseUrl.startsWith("https://openrouter.ai") &&
            !baseUrl.startsWith("https://api.openai.com") &&
            !baseUrl.startsWith("https://api.anthropic.com") &&
            !baseUrl.startsWith("https://generativelanguage.googleapis.com"))
    }

    private fun translateViaAi(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        provider: String,
        customSystemPrompt: String,
        maxRetries: Int
    ): Result<List<String>> {
        val lineCount = lines.size
        val effectiveSystemPrompt = customSystemPrompt.takeIf { it.isNotBlank() }
            ?.replace("{lineCount}", lineCount.toString())
            ?: defaultLyricsSystemPrompt(lineCount)

        val targetLangName = languageName(targetLanguage)
        val inputJsonArray = JSONArray()
        lines.forEach { inputJsonArray.put(it) }
        val inputJson = inputJsonArray.toString()

        val userPrompt = when (mode) {
            "Transcribed" -> {
                "Transliterate the `lines` JSON array phonetically into $targetLangName script. Do not translate meaning. Return exactly $lineCount output lines.\n\n{\"lines\":$inputJson}"
            }
            "Romanized" -> {
                "Romanize the `lines` JSON array into simple ASCII Latin script. Do not translate meaning. Return exactly $lineCount output lines.\n\n{\"lines\":$inputJson}"
            }
            else -> {
                "Translate the `lines` JSON array to $targetLangName for synced song lyrics. Keep line order, repeated hooks, names, punctuation, and empty lines. Return exactly $lineCount output lines.\n\n{\"lines\":$inputJson}"
            }
        }

        val schema = lyricsLinesSchema()
        val maxTokens = (lineCount * 192 + 2048).coerceAtLeast(768)
        val protocol = protocolFor(provider, baseUrl, model)

        var lastException: Throwable? = null

        repeat(maxRetries) { attempt ->
            try {
                val completionResult = when (protocol) {
                    AiProtocol.GEMINI -> {
                        requestGeminiCompatible(
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            model = model,
                            systemPrompt = effectiveSystemPrompt,
                            userPrompt = userPrompt,
                            schema = schema,
                            maxTokens = maxTokens
                        )
                    }
                    AiProtocol.ANTHROPIC -> {
                        requestAnthropicCompatible(
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            model = model,
                            systemPrompt = effectiveSystemPrompt,
                            userPrompt = userPrompt,
                            schema = schema,
                            maxTokens = maxTokens
                        )
                    }
                    AiProtocol.OPENAI -> {
                        requestOpenAiWithFallback(
                            provider = provider,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            model = model,
                            systemPrompt = effectiveSystemPrompt,
                            userPrompt = userPrompt,
                            schema = schema,
                            maxTokens = maxTokens
                        )
                    }
                }

                if (completionResult.isSuccess) {
                    val content = completionResult.getOrThrow()
                    val parsed = parseJsonLinePayload(content, lineCount)
                    if (parsed != null) {
                        val aligned = if (linesAreAligned(lines, parsed)) {
                            parsed
                        } else {
                            // Pad or truncate to match exact input line count
                            parsed.take(lineCount) + List((lineCount - parsed.size).coerceAtLeast(0)) { "" }
                        }
                        return Result.success(aligned)
                    } else {
                        throw Exception("AI response did not contain aligned lyric lines (length mismatch or unparseable JSON)")
                    }
                } else {
                    lastException = completionResult.exceptionOrNull()
                }
            } catch (e: Exception) {
                lastException = e
            }

            if (attempt < maxRetries - 1) {
                try {
                    Thread.sleep(1000L * (attempt + 1))
                } catch (_: InterruptedException) {}
            }
        }

        return Result.failure(lastException ?: Exception("AI translation failed after $maxRetries retries"))
    }

    private fun requestGeminiCompatible(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        schema: JSONObject,
        maxTokens: Int
    ): Result<String> {
        val effectiveModel = when (model.ifBlank { PrefKeys.GeminiDefaultModel }) {
            "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite" -> "gemini-3.5-flash-lite"
            else -> model.ifBlank { PrefKeys.GeminiDefaultModel }
        }
        val resolvedBase = when {
            baseUrl.isBlank() ||
                baseUrl == "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions" ||
                baseUrl.contains("gemini-flash-lite-latest") ||
                baseUrl.contains("gemini-2.5-flash") ||
                baseUrl == PrefKeys.LEGACY_GEMINI_ENDPOINT -> {
                PrefKeys.GeminiDefaultEndpoint
            }
            else -> baseUrl
        }

        val url = when {
            resolvedBase.contains("{model}") -> {
                resolvedBase.replace("{model}", effectiveModel)
            }
            effectiveModel.isNotBlank() -> {
                resolvedBase.replace(Regex("/models/[^/:]+(:generateContent)")) { match ->
                    "/models/$effectiveModel${match.groupValues[1]}"
                }
            }
            else -> resolvedBase
        }

        val body = JSONObject().apply {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "$systemPrompt\n\n$userPrompt")
                        })
                    })
                })
            }
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", maxTokens)
                put("responseMimeType", "application/json")
                put("responseSchema", geminiSchema(schema))
            })
        }

        val headers = mutableMapOf("Content-Type" to "application/json")
        val finalUrl = if (url.contains("key=")) {
            url
        } else {
            headers["x-goog-api-key"] = apiKey.trim()
            url
        }

        val resp = executeHttpPost(finalUrl, body.toString(), headers)
        Log.i(TAG, "Gemini request finalUrl=$finalUrl, effectiveModel=$effectiveModel, code=${resp.statusCode}, body length=${resp.body.length}")
        if (resp.statusCode in 200..299) {
            val extracted = extractGeminiContent(resp.body)
            Log.i(TAG, "Gemini extracted content: ${extracted?.take(150)}...")
            if (extracted != null) return Result.success(extracted)
            return Result.failure(Exception("Gemini returned empty content: ${resp.body.take(200)}"))
        }
        Log.w(TAG, "Gemini HTTP failed: ${resp.statusCode}: ${resp.body.take(300)}")
        return Result.failure(Exception("Gemini HTTP ${resp.statusCode}: ${resp.body.take(200)}"))
    }

    private fun requestAnthropicCompatible(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        schema: JSONObject,
        maxTokens: Int
    ): Result<String> {
        val effectiveModel = model.ifBlank { PrefKeys.AnthropicDefaultModel }
        val endpoint = baseUrl.ifBlank { PrefKeys.AnthropicDefaultEndpoint }
        val toolName = "return_lyrics_translation"

        val body = JSONObject().apply {
            put("model", effectiveModel)
            put("max_tokens", maxTokens)
            put("temperature", 0.1)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", toolName)
                    put("description", "Return the structured lyrics result.")
                    put("input_schema", schema)
                })
            })
            put("tool_choice", JSONObject().apply {
                put("type", "tool")
                put("name", toolName)
            })
        }

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "anthropic-version" to "2023-06-01"
        )
        if (apiKey.isNotBlank()) {
            headers["x-api-key"] = apiKey.trim()
        }

        val resp = executeHttpPost(endpoint, body.toString(), headers)
        if (resp.statusCode in 200..299) {
            val extracted = extractAnthropicContent(resp.body)
            if (extracted != null) return Result.success(extracted)
            return Result.failure(Exception("Anthropic returned empty content: ${resp.body.take(200)}"))
        }
        return Result.failure(Exception("Anthropic HTTP ${resp.statusCode}: ${resp.body.take(200)}"))
    }

    private fun requestOpenAiWithFallback(
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        schema: JSONObject,
        maxTokens: Int
    ): Result<String> {
        val endpoint = baseUrl.ifBlank { PrefKeys.OpenRouterDefaultBaseUrl }
        val effectiveModel = model.ifBlank { PrefKeys.OpenRouterDefaultModel }
        val isOpenRouter = provider.equals("OpenRouter", ignoreCase = true) || endpoint.contains("openrouter.ai")

        fun buildBody(responseFormat: JSONObject?, includeReasoning: Boolean = true): JSONObject {
            return JSONObject().apply {
                if (effectiveModel.isNotBlank()) put("model", effectiveModel)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                }
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", maxTokens)
                if (includeReasoning && isOpenRouter) {
                    put("reasoning", JSONObject().apply {
                        put("enabled", false)
                        put("exclude", true)
                    })
                }
                if (responseFormat != null) {
                    put("response_format", responseFormat)
                }
            }
        }

        val jsonSchemaFormat = JSONObject().apply {
            put("type", "json_schema")
            put("json_schema", JSONObject().apply {
                put("name", "lyrics_translation")
                put("strict", true)
                put("schema", schema)
            })
        }
        val jsonObjectFormat = JSONObject().apply {
            put("type", "json_object")
        }

        val bodies = listOf(
            buildBody(jsonSchemaFormat),
            buildBody(jsonObjectFormat),
            buildBody(null),
            buildBody(null, includeReasoning = false)
        )

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "HTTP-Referer" to "https://github.com/MetrolistGroup/Metrolist",
            "X-Title" to "Metrolist"
        )
        if (apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer ${apiKey.trim()}"
        }

        for ((index, body) in bodies.withIndex()) {
            val resp = executeHttpPost(endpoint, body.toString(), headers)
            if (resp.statusCode in 200..299) {
                val extracted = extractOpenAiContent(resp.body)
                if (extracted != null) return Result.success(extracted)
            } else if (index < bodies.lastIndex && (resp.statusCode == 400 || resp.statusCode == 422)) {
                Log.d(TAG, "OpenAI structured format attempt $index failed with HTTP ${resp.statusCode}, trying fallback format")
                continue
            } else {
                return Result.failure(Exception("OpenAI HTTP ${resp.statusCode}: ${resp.body.take(200)}"))
            }
        }

        return Result.failure(Exception("AI request failed across all structured format fallbacks"))
    }

    private fun translateViaDeepL(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        formality: String
    ): Result<List<String>> {
        return try {
            val endpoint = if (apiKey.endsWith(":fx")) {
                "https://api-free.deepl.com/v2/translate"
            } else {
                "https://api.deepl.com/v2/translate"
            }

            val targetCode = if (targetLanguage.substringBefore('-').equals("zh", ignoreCase = true)) {
                "ZH"
            } else {
                deepLTargetLanguage(targetLanguage)
            }

            val root = JSONObject().apply {
                val textArr = JSONArray()
                lines.forEach { textArr.put(it) }
                put("text", textArr)
                put("target_lang", targetCode)
                put("preserve_formatting", true)
                if (formality in listOf("more", "less")) {
                    put("formality", if (formality == "more") "prefer_more" else "prefer_less")
                }
            }

            val headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "DeepL-Auth-Key ${apiKey.trim()}"
            )

            val resp = executeHttpPost(endpoint, root.toString(), headers)
            if (resp.statusCode !in 200..299) {
                return Result.failure(Exception("DeepL HTTP ${resp.statusCode}: ${resp.body.take(200)}"))
            }

            val json = JSONObject(resp.body)
            val translations = json.optJSONArray("translations")
                ?: return Result.failure(Exception("No translations returned by DeepL"))

            val result = mutableListOf<String>()
            for (i in 0 until translations.length()) {
                result.add(translations.getJSONObject(i).optString("text", ""))
            }
            Result.success(result)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun translateViaDeepLKeyless(
        lines: List<String>,
        targetLanguage: String
    ): Result<List<String>> {
        return try {
            val nonBlank = lines.withIndex().filter { it.value.isNotBlank() }
            if (nonBlank.isEmpty()) return Result.success(lines)

            val translated = MutableList(lines.size) { "" }
            val batchSize = 30
            for (i in 0 until nonBlank.size step batchSize) {
                val batchEnd = (i + batchSize).coerceAtMost(nonBlank.size)
                val batch = nonBlank.subList(i, batchEnd)
                val textArr = JSONArray()
                batch.forEach { textArr.put(it.value) }

                val root = JSONObject().apply {
                    put("text", textArr)
                    put("target_lang", deepLTargetLanguage(targetLanguage))
                    put("usage_type", "translate")
                    put("app_information", JSONObject().apply {
                        put("app_version", "1.59.0")
                        put("app_build", "10059000")
                        put("app_name", "chromeExtension")
                        put("os", "macOS")
                        put("os_version", "10.15.7")
                        put("device", "MacIntel")
                        put("instance_id", "00000000-0000-0000-0000-000000000000")
                    })
                }

                val headers = mapOf(
                    "Origin" to "chrome-extension://cofdbpoegempjloogbagkncekinflcnj",
                    "Authorization" to "None",
                    "Content-Type" to "application/json"
                )

                val resp = executeHttpPost("https://oneshot-free.www.deepl.com/v1/translate", root.toString(), headers)
                if (resp.statusCode !in 200..299) {
                    throw Exception("DeepL keyless HTTP ${resp.statusCode}: ${resp.body.take(200)}")
                }

                val json = JSONObject(resp.body)
                val translations = json.optJSONArray("translations")
                    ?: throw Exception("DeepL keyless returned no translations")

                if (translations.length() != batch.size) {
                    throw Exception("DeepL returned ${translations.length()} lines, expected ${batch.size}")
                }

                batch.forEachIndexed { index, original ->
                    translated[original.index] = translations.getJSONObject(index).optString("text", "").trim()
                }
            }
            Result.success(translated)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun translateViaGoogle(
        lines: List<String>,
        targetLanguage: String,
        mode: String
    ): Result<List<String>> {
        return try {
            val nonBlank = lines.withIndex().filter { it.value.isNotBlank() }
            if (nonBlank.isEmpty()) return Result.success(lines)

            val translated = MutableList(lines.size) { "" }
            val batchSize = 40
            for (i in 0 until nonBlank.size step batchSize) {
                val batchEnd = (i + batchSize).coerceAtMost(nonBlank.size)
                val batch = nonBlank.subList(i, batchEnd)
                val markers = List((batch.size - 1).coerceAtLeast(0)) { "__METROLIST_LINE_${it + 1}__" }
                val input = batch.mapIndexed { index, item ->
                    item.value + markers.getOrNull(index)?.let { "\n$it\n" }.orEmpty()
                }.joinToString("")

                val (transText, romText) = translateGoogleText(input, targetLanguage)
                val splitTrans = splitByMarkers(transText, markers)
                    ?: batch.map { translateGoogleText(it.value, targetLanguage).first }

                val chosen = if (mode == "Romanized") {
                    val splitRom = romText?.let { splitByMarkers(it, markers) }
                    splitRom?.takeIf { it.size == batch.size } ?: splitTrans
                } else {
                    splitTrans
                }

                batch.forEachIndexed { index, original ->
                    translated[original.index] = chosen.getOrNull(index)?.trim().orEmpty()
                }
            }
            Result.success(translated)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun translateGoogleText(text: String, targetLanguage: String): Pair<String, String?> {
        val encoded = Uri.encode(text)
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLanguage&dt=t&dt=rm&q=$encoded"
        val responseBody = httpGet(url, emptyMap()) ?: throw Exception("Empty response from Google Translate")
        val root = JSONArray(responseBody)
        val firstArr = root.optJSONArray(0) ?: return Pair("", null)
        val translated = StringBuilder()
        var romanized: String? = null
        for (i in 0 until firstArr.length()) {
            val segment = firstArr.optJSONArray(i) ?: continue
            val part = segment.optString(0)
            if (!part.isNullOrEmpty() && part != "null") {
                translated.append(part)
            }
            if (segment.length() >= 3 && segment.isNull(0)) {
                val candidate = segment.optString(2).takeIf { it.isNotBlank() && it != "null" }
                    ?: segment.optString(3).takeIf { it.isNotBlank() && it != "null" }
                if (candidate != null && romanized == null) {
                    romanized = candidate
                }
            }
        }
        return Pair(translated.toString(), romanized)
    }

    private fun splitByMarkers(translated: String, markers: List<String>): List<String>? {
        if (markers.isEmpty()) return listOf(translated.trim())
        val parts = mutableListOf<String>()
        var remaining = translated
        for (marker in markers) {
            val markerIndex = remaining.indexOf(marker)
            if (markerIndex < 0) return null
            parts.add(remaining.substring(0, markerIndex).trim())
            remaining = remaining.substring(markerIndex + marker.length)
        }
        parts.add(remaining.trim())
        return parts
    }

    private fun extractGeminiContent(responseText: String): String? {
        return runCatching {
            val root = JSONObject(responseText)
            val candidates = root.optJSONArray("candidates") ?: return null
            val firstCandidate = candidates.optJSONObject(0) ?: return null
            val content = firstCandidate.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text", "")
                if (text.isNotEmpty()) {
                    sb.append(text)
                }
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun extractAnthropicContent(responseText: String): String? {
        return runCatching {
            val root = JSONObject(responseText)
            val content = root.optJSONArray("content") ?: return null
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "tool_use") {
                    val input = part.opt("input")
                    if (input != null) return input.toString().trim().takeIf { it.isNotEmpty() }
                }
            }
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    val text = part.optString("text", "")
                    if (text.isNotEmpty()) sb.append(text)
                }
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun extractOpenAiContent(responseText: String): String? {
        return runCatching {
            val root = JSONObject(responseText)
            val choices = root.optJSONArray("choices") ?: return null
            val firstChoice = choices.optJSONObject(0) ?: return null
            val message = firstChoice.optJSONObject("message") ?: return null

            val content = message.optString("content", "").trim()
            if (content.isNotEmpty()) return content

            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val firstCall = toolCalls.optJSONObject(0)
                val function = firstCall?.optJSONObject("function")
                val arguments = function?.optString("arguments", "")?.trim()
                if (!arguments.isNullOrEmpty()) return arguments
            }
            null
        }.getOrNull()
    }

    private fun parseJsonPayload(content: String, opening: Char, closing: Char): Any? {
        for (start in content.indices.filter { content[it] == opening }) {
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until content.length) {
                val char = content[index]
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> quoted = false
                    }
                } else {
                    when (char) {
                        '"' -> quoted = true
                        opening -> depth++
                        closing -> depth--
                    }
                    if (depth == 0) {
                        val substr = content.substring(start, index + 1)
                        val parsed = runCatching {
                            if (opening == '{') JSONObject(substr) else JSONArray(substr)
                        }.getOrNull()
                        if (parsed != null) return parsed
                        break
                    }
                }
            }
        }
        return null
    }

    private fun parseJsonLinePayload(content: String, expectedLines: Int): List<String>? {
        val json = parseJsonPayload(content, '{', '}') as? JSONObject
        val linesArray = json?.optJSONArray("lines")
        if (linesArray != null) {
            val lines = (0 until linesArray.length()).map { linesArray.optString(it) }
            Log.d(TAG, "parseJsonLinePayload from object: ${lines.size} lines (expected $expectedLines)")
            if (lines.isNotEmpty()) return lines
        }
        val arr = parseJsonPayload(content, '[', ']') as? JSONArray
        if (arr != null) {
            val lines = (0 until arr.length()).map { arr.optString(it) }
            Log.d(TAG, "parseJsonLinePayload from array: ${lines.size} lines (expected $expectedLines)")
            if (lines.isNotEmpty()) return lines
        }
        return null
    }

    private fun linesAreAligned(source: List<String>, output: List<String>): Boolean {
        return source.size == output.size && source.indices.all { source[it].isBlank() == output[it].isBlank() }
    }

    private fun lyricsLinesSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("lines", JSONObject().apply {
                    put("type", "array")
                    put("items", JSONObject().apply {
                        put("type", "string")
                    })
                })
            })
            put("required", JSONArray().apply { put("lines") })
            put("additionalProperties", false)
        }
    }

    private fun geminiSchema(schema: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = schema.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "additionalProperties") continue
            val value = schema.get(key)
            if (key == "type" && value is String) {
                result.put(key, value.uppercase())
            } else if (value is JSONObject) {
                result.put(key, geminiSchema(value))
            } else if (value is JSONArray) {
                val arr = JSONArray()
                for (i in 0 until value.length()) {
                    val item = value.get(i)
                    if (item is JSONObject) {
                        arr.put(geminiSchema(item))
                    } else if (item is String && item in setOf("object", "array", "string", "boolean", "number", "integer")) {
                        arr.put(item.uppercase())
                    } else {
                        arr.put(item)
                    }
                }
                result.put(key, arr)
            } else {
                result.put(key, value)
            }
        }
        return result
    }

    fun defaultLyricsSystemPrompt(lineCount: Int): String =
        "You are a lyrics localization engine. Return only JSON that matches the provided schema. " +
            "Do not explain, think aloud, use markdown, merge lines, split lines, or add notes. " +
            "Return exactly $lineCount strings in `lines`, preserving input line order. Empty input lines must stay empty. " +
            "Make translations natural for music listening while staying faithful to meaning, tense, names, punctuation, " +
            "and repeated hooks. " +
            "Preserve text already in the target language verbatim, including the target-language parts of mixed lines."

    fun languageName(code: String): String =
        when (code.lowercase()) {
            "en" -> "English"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            "bn" -> "Bengali"
            "pa" -> "Punjabi"
            "tr" -> "Turkish"
            "vi" -> "Vietnamese"
            "th" -> "Thai"
            "id" -> "Indonesian"
            "pl" -> "Polish"
            "nl" -> "Dutch"
            "sv" -> "Swedish"
            "uk" -> "Ukrainian"
            else -> PrefKeys.LanguageCodeToName[code] ?: code
        }

    fun deepLTargetLanguage(code: String): String =
        when (code.lowercase()) {
            "zh-cn", "zh-hans" -> "zh-Hans"
            "zh-tw", "zh-hant" -> "zh-Hant"
            "en" -> "en-US"
            "pt" -> "pt-BR"
            else -> code.uppercase().take(2)
        }

    private data class HttpResponse(val statusCode: Int, val body: String)

    private fun executeHttpPost(urlString: String, jsonBody: String, headers: Map<String, String>): HttpResponse {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 90000
        conn.doOutput = true
        conn.doInput = true
        conn.setRequestProperty("User-Agent", "SpotXero/2.0 (Android; +https://github.com/s1ddhants1/SpotXero)")
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(jsonBody)
            writer.flush()
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        }.orEmpty()

        return HttpResponse(code, responseText)
    }

    private fun httpGet(urlString: String, headers: Map<String, String>): String? {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "SpotXero/2.0 (Android; +https://github.com/s1ddhants1/SpotXero)")
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        }
    }

    private fun applyTranslationToLyrics(
        lyrics: ParsedLyrics,
        translatedLines: List<String>,
        dualLine: Boolean
    ): ParsedLyrics {
        val newLines = lyrics.lines.mapIndexed { index, line ->
            val trans = translatedLines.getOrNull(index)?.trim().orEmpty()
            val finalWords = if (trans.isNotBlank()) {
                if (dualLine) "${line.words}\n$trans" else trans
            } else {
                line.words
            }
            line.copy(words = finalWords)
        }
        return lyrics.copy(lines = newLines)
    }
}
