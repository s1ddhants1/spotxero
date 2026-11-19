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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LyricsAiResult(
    val translatedLines: List<String>? = null,
    val romanizedLines: List<String>? = null,
    val romanizedWordsPerLine: List<List<String>?>? = null,
)

object AiLyricsTranslator {

    private const val TAG = "SpotXero.AiLyrics"

    private class FreeModelSession {
        val throttled = mutableMapOf<String, Long>()
        var lastBusyFailure: Throwable? = null
        var dailyFailure: Throwable? = null
        var dailyResetMillis = 0L
    }

    private val freeModelSessions = ConcurrentHashMap<String, FreeModelSession>()

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
        val dualLine = PreferencesManager.isAiTranslationDualLine
        val model = PreferencesManager.aiTranslationModel
        val formality = PreferencesManager.aiTranslationDeeplFormality.ifBlank { "default" }
        val prompt = PreferencesManager.aiTranslationCustomPrompt.trim()
        val baseUrl = PreferencesManager.aiTranslationBaseUrl.trim()
        val promptHash = if (prompt.isNotEmpty() || baseUrl.isNotEmpty()) {
            "$baseUrl|$prompt".hashCode().toString()
        } else {
            "default"
        }
        val cacheKey = if (!trackKey.isNullOrBlank()) "$trackKey::$targetLang::$mode::$provider::$model::$formality::$promptHash" else null

        if (cacheKey != null) {
            PersistentLyricsCache.getLines(cacheKey)?.let { cachedLines ->
                if (cachedLines.size == lyrics.lines.size) {
                    Log.i(TAG, "Returning persistent cached translation (${cachedLines.size} lines)")
                    return applyTranslationToLyrics(lyrics, cachedLines, dualLine)
                }
            }
        }

        val rawLines = lyrics.lines.map { it.words }
        val wordsPerLine: List<List<String>?>? = if (mode == "Romanized") {
            lyrics.lines.map { line ->
                if (line.syllables.isNotEmpty()) {
                    var offset = 0
                    val sylWords = mutableListOf<String>()
                    for (syl in line.syllables) {
                        val end = (offset + syl.numChars).coerceAtMost(line.words.length)
                        if (offset <= line.words.length) {
                            sylWords.add(line.words.substring(offset, end))
                            offset = end
                        }
                    }
                    if (offset < line.words.length) {
                        sylWords.add(line.words.substring(offset))
                    }
                    sylWords.takeIf { it.isNotEmpty() }
                } else null
            }
        } else null

        val translated = try {
            val res = translate(
                lines = rawLines,
                targetLanguage = targetLang,
                apiKey = apiKey,
                baseUrl = PreferencesManager.aiTranslationBaseUrl,
                model = model,
                mode = mode,
                provider = provider,
                deeplFormality = PreferencesManager.aiTranslationDeeplFormality,
                customSystemPrompt = PreferencesManager.aiTranslationCustomPrompt,
                wordsPerLine = wordsPerLine
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
                PersistentLyricsCache.putLines(cacheKey, translated)
            }
            Log.i(TAG, "Successfully applied translation to lyrics! Sample translated line 0: '${translated.firstOrNull()}'")
            return applyTranslationToLyrics(lyrics, translated, dualLine)
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
        wordsPerLine: List<List<String>?>? = null,
        maxRetries: Int = 3
    ): Result<List<String>> {
        if (lines.isEmpty() || lines.all { it.isBlank() }) {
            return Result.failure(LyricsTranslationException(LyricsTranslationFailure.EMPTY_RESPONSE, "Input text is empty"))
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
                    wordsPerLine = wordsPerLine,
                    maxRetries = maxRetries
                )
            }
        }
    }

    fun protocolFor(provider: String, baseUrl: String, model: String): AiProtocol {
        return AiProviderPresets.protocolFor(provider, baseUrl, model)
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

    private fun freeModelSession(apiKey: String): FreeModelSession =
        freeModelSessions.getOrPut(apiKey) { FreeModelSession() }

    private fun withFreeModelFallback(
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        request: (String) -> Result<String>,
    ): Result<String> {
        val preset = AiProviderPresets.find("OpenRouter") ?: return request(model)
        if (!provider.equals(preset.id, ignoreCase = true) || baseUrl.ifBlank { preset.endpoint } != preset.endpoint ||
            !model.endsWith(":free")
        ) {
            return request(model)
        }
        val session = freeModelSession(apiKey)
        synchronized(session) {
            val now = System.currentTimeMillis()
            session.dailyFailure?.takeIf { now < session.dailyResetMillis }?.let { return Result.failure(it) }
            session.dailyFailure = null
            val attempted = mutableSetOf<String>()
            var candidates: List<String>? = null
            var selected = model.takeUnless { (session.throttled[it] ?: 0) > now }
            while (true) {
                if (selected == null) {
                    if (candidates == null) {
                        candidates = listOf(
                            AiProviderPresets.FREE_LYRICS_AI_MODEL,
                            "google/gemma-4-31b-it:free",
                            "meta-llama/llama-3.3-70b-instruct:free",
                            "qwen/qwen-2.5-72b-instruct:free"
                        )
                    }
                    selected = candidates.firstOrNull {
                        it !in attempted && (session.throttled[it] ?: 0) <= System.currentTimeMillis()
                    }
                }
                val candidate = selected ?: return Result.failure(
                    session.lastBusyFailure ?: LyricsTranslationException(LyricsTranslationFailure.MODEL_BUSY, "Free models are busy")
                )
                attempted += candidate
                val result = request(candidate)
                if (result.isSuccess) {
                    return result
                }
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                val failure = (error as? LyricsTranslationException)?.failure
                    ?: LyricsTranslationFailure.REQUEST_FAILED
                when (failure) {
                    LyricsTranslationFailure.FREE_DAILY_LIMIT -> {
                        session.dailyFailure = error
                        session.dailyResetMillis = (error as? LyricsTranslationException)?.retryAtMillis
                            ?: ((now / 86_400_000 + 1) * 86_400_000)
                        return result
                    }
                    LyricsTranslationFailure.MODEL_BUSY -> {
                        session.throttled[candidate] = (error as? LyricsTranslationException)?.retryAtMillis ?: (now + 60_000)
                        session.lastBusyFailure = error
                        selected = null
                    }
                    else -> {
                        return result
                    }
                }
            }
        }
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
        wordsPerLine: List<List<String>?>? = null,
        maxRetries: Int = 3
    ): Result<List<String>> {
        val lineCount = lines.size
        val effectiveSystemPrompt = customSystemPrompt.takeIf { it.isNotBlank() }
            ?.replace("{lineCount}", lineCount.toString())
            ?: defaultLyricsSystemPrompt(lineCount)

        val targetLangName = languageName(targetLanguage)
        val hasWords = mode == "Romanized" && wordsPerLine != null && wordsPerLine.any { !it.isNullOrEmpty() }

        val inputJson = if (hasWords) {
            val arr = JSONArray()
            lines.forEachIndexed { i, line ->
                val obj = JSONObject().apply {
                    put("line", line)
                    val words = wordsPerLine.getOrNull(i)
                    if (!words.isNullOrEmpty()) {
                        val wArr = JSONArray()
                        words.forEach { wArr.put(it) }
                        put("words", wArr)
                    }
                }
                arr.put(obj)
            }
            arr.toString()
        } else {
            val inputJsonArray = JSONArray()
            lines.forEach { inputJsonArray.put(it) }
            inputJsonArray.toString()
        }

        val userPrompt = when {
            mode == "Transcribed" -> {
                "Transliterate the `lines` JSON array phonetically into $targetLangName script. Do not translate meaning. Return exactly $lineCount output lines.\n\n{\"lines\":$inputJson}"
            }
            mode == "Romanized" && hasWords -> {
                "Romanize each item in the `lines` JSON array into simple ASCII Latin script. Do not translate meaning. " +
                    "For items with a `words` array, preserve full line context for pronunciation, " +
                    "return `romanizedLine` for the full line, and return `romanizedWords` with exact 1-to-1 ASCII Latin " +
                    "romanization for each word in `words`. " +
                    "Return exactly $lineCount output items in `lines`.\n\n{\"lines\":$inputJson}"
            }
            mode == "Romanized" -> {
                "Romanize the `lines` JSON array into simple ASCII Latin script. Do not translate meaning. Return exactly $lineCount output lines.\n\n{\"lines\":$inputJson}"
            }
            else -> {
                "Translate the `lines` JSON array to $targetLangName for synced song lyrics. Keep line order, repeated hooks, names, punctuation, and empty lines. Return exactly $lineCount output lines.\n\n{\"lines\":$inputJson}"
            }
        }

        val schema = if (hasWords) lyricsRomanizedLinesWithWordsSchema() else lyricsLinesSchema()
        val schemaName = if (hasWords) "lyrics_romanization_words" else "lyrics_translation"
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
                            schemaName = schemaName,
                            schema = schema,
                            maxTokens = maxTokens
                        )
                    }
                    AiProtocol.OPENAI -> {
                        withFreeModelFallback(provider, apiKey, baseUrl, model) { selectedModel ->
                            requestOpenAiWithFallback(
                                provider = provider,
                                apiKey = apiKey,
                                baseUrl = baseUrl,
                                model = selectedModel,
                                systemPrompt = effectiveSystemPrompt,
                                userPrompt = userPrompt,
                                schema = schema,
                                maxTokens = maxTokens
                            )
                        }
                    }
                }

                if (completionResult.isSuccess) {
                    val content = completionResult.getOrThrow()
                    if (hasWords) {
                        val parsedWithWords = parseJsonRomanizedLinesWithWordsPayload(content, lineCount)
                        if (parsedWithWords != null) {
                            return Result.success(parsedWithWords.first)
                        }
                    }

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
                        throw LyricsTranslationException(
                            LyricsTranslationFailure.INVALID_RESPONSE,
                            "AI response did not contain aligned lyric lines (length mismatch or unparseable JSON)"
                        )
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

        return Result.failure(lastException ?: LyricsTranslationException(LyricsTranslationFailure.REQUEST_FAILED, "AI translation failed after $maxRetries retries"))
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
            if (extracted != null) return Result.success(extracted)
            return Result.failure(LyricsTranslationException(LyricsTranslationFailure.EMPTY_RESPONSE, "Gemini returned empty content: ${resp.body.take(200)}"))
        }
        val failure = classifyLyricsTranslationHttpFailure(resp.statusCode, resp.body, resp.headers)
        return Result.failure(LyricsTranslationException(failure, "Gemini HTTP ${resp.statusCode}: ${resp.body.take(200)}", resp.statusCode, parseRetryAtMillis(resp.headers)))
    }

    private fun requestAnthropicCompatible(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        schemaName: String,
        schema: JSONObject,
        maxTokens: Int
    ): Result<String> {
        val effectiveModel = model.ifBlank { PrefKeys.AnthropicDefaultModel }
        val endpoint = baseUrl.ifBlank { PrefKeys.AnthropicDefaultEndpoint }
        val toolName = "return_$schemaName"

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
            return Result.failure(LyricsTranslationException(LyricsTranslationFailure.EMPTY_RESPONSE, "Anthropic returned empty content: ${resp.body.take(200)}"))
        }
        val failure = classifyLyricsTranslationHttpFailure(resp.statusCode, resp.body, resp.headers)
        return Result.failure(LyricsTranslationException(failure, "Anthropic HTTP ${resp.statusCode}: ${resp.body.take(200)}", resp.statusCode, parseRetryAtMillis(resp.headers)))
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
                val failure = classifyLyricsTranslationHttpFailure(resp.statusCode, resp.body, resp.headers)
                return Result.failure(LyricsTranslationException(failure, "OpenAI HTTP ${resp.statusCode}: ${resp.body.take(200)}", resp.statusCode, parseRetryAtMillis(resp.headers)))
            }
        }

        return Result.failure(LyricsTranslationException(LyricsTranslationFailure.INVALID_RESPONSE, "AI request failed across all structured format fallbacks"))
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
                val failure = classifyLyricsTranslationHttpFailure(resp.statusCode, resp.body, resp.headers)
                return Result.failure(LyricsTranslationException(failure, "DeepL HTTP ${resp.statusCode}: ${resp.body.take(200)}", resp.statusCode))
            }

            val json = JSONObject(resp.body)
            val translations = json.optJSONArray("translations")
                ?: return Result.failure(LyricsTranslationException(LyricsTranslationFailure.EMPTY_RESPONSE, "No translations returned by DeepL"))

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
            val maxBatchCharacters = 1_400
            val instanceId = UUID.randomUUID().toString()

            var cursor = 0
            while (cursor < nonBlank.size) {
                val batch = mutableListOf<IndexedValue<String>>()
                var characters = 0
                while (cursor < nonBlank.size) {
                    val candidate = nonBlank[cursor]
                    if (candidate.value.length > maxBatchCharacters) {
                        if (batch.isEmpty()) {
                            batch.add(candidate)
                            cursor++
                        }
                        break
                    }
                    if (batch.isNotEmpty() && characters + candidate.value.length > maxBatchCharacters) {
                        break
                    }
                    batch.add(candidate)
                    characters += candidate.value.length
                    cursor++
                }

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
                        put("instance_id", instanceId)
                    })
                }

                val headers = mapOf(
                    "Origin" to "chrome-extension://cofdbpoegempjloogbagkncekinflcnj",
                    "Authorization" to "None",
                    "Content-Type" to "application/json"
                )

                val resp = executeHttpPost("https://oneshot-free.www.deepl.com/v1/translate", root.toString(), headers)
                if (resp.statusCode !in 200..299) {
                    val failure = classifyLyricsTranslationHttpFailure(resp.statusCode, resp.body, resp.headers)
                    throw LyricsTranslationException(failure, "DeepL keyless HTTP ${resp.statusCode}: ${resp.body.take(200)}", resp.statusCode)
                }

                val json = JSONObject(resp.body)
                val translations = json.optJSONArray("translations")
                    ?: throw LyricsTranslationException(LyricsTranslationFailure.INVALID_RESPONSE, "DeepL keyless returned no translations")

                if (translations.length() != batch.size) {
                    throw LyricsTranslationException(LyricsTranslationFailure.INVALID_RESPONSE, "DeepL returned ${translations.length()} lines, expected ${batch.size}")
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
            val maxBatchCharacters = 1_200
            var cursor = 0
            while (cursor < nonBlank.size) {
                val batch = mutableListOf<IndexedValue<String>>()
                var characters = 0
                while (cursor < nonBlank.size) {
                    val candidate = nonBlank[cursor]
                    if (candidate.value.length > maxBatchCharacters) {
                        if (batch.isEmpty()) {
                            batch.add(candidate)
                            cursor++
                        }
                        break
                    }
                    if (batch.isNotEmpty() && characters + candidate.value.length > maxBatchCharacters) {
                        break
                    }
                    batch.add(candidate)
                    characters += candidate.value.length
                    cursor++
                }

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
        val postData = "client=gtx&sl=auto&tl=${Uri.encode(targetLanguage)}&dt=t&dt=rm&q=${Uri.encode(text)}"
        val url = "https://translate.googleapis.com/translate_a/single"
        val headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8")
        val resp = executeHttpPost(url, postData, headers)
        if (resp.statusCode !in 200..299) {
            val failure = classifyLyricsTranslationHttpFailure(resp.statusCode, resp.body, resp.headers)
            throw LyricsTranslationException(failure, "Google Translate HTTP ${resp.statusCode}: ${resp.body.take(200)}", resp.statusCode)
        }
        val root = JSONArray(resp.body)
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

    private fun parseJsonRomanizedLinesWithWordsPayload(
        content: String,
        expectedLines: Int,
    ): Pair<List<String>, List<List<String>?>>? {
        val json = parseJsonPayload(content, '{', '}') as? JSONObject ?: return null
        val linesArray = json.optJSONArray("lines") ?: return null
        val parsedLines = mutableListOf<String>()
        val parsedWords = mutableListOf<List<String>?>()

        for (i in 0 until linesArray.length()) {
            val elem = linesArray.opt(i)
            if (elem is JSONObject) {
                val line = elem.optString("romanizedLine").takeIf { it.isNotEmpty() }
                    ?: elem.optString("line", "")
                val wArr = elem.optJSONArray("romanizedWords") ?: elem.optJSONArray("words")
                val words = if (wArr != null) {
                    (0 until wArr.length()).map { wArr.optString(it, "") }
                } else null
                parsedLines.add(line)
                parsedWords.add(words)
            } else if (elem is String) {
                parsedLines.add(elem)
                parsedWords.add(null)
            } else {
                parsedLines.add("")
                parsedWords.add(null)
            }
        }
        if (parsedLines.size != expectedLines) return null
        return Pair(parsedLines, parsedWords)
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

    private fun lyricsRomanizedLinesWithWordsSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("lines", JSONObject().apply {
                    put("type", "array")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("romanizedLine", JSONObject().apply { put("type", "string") })
                            put("romanizedWords", JSONObject().apply {
                                put("type", "array")
                                put("items", JSONObject().apply { put("type", "string") })
                            })
                        })
                        put("required", JSONArray().apply {
                            put("romanizedLine")
                            put("romanizedWords")
                        })
                        put("additionalProperties", false)
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

    private data class HttpResponse(val statusCode: Int, val body: String, val headers: Map<String, List<String>> = emptyMap())

    private fun executeHttpPost(urlString: String, jsonOrFormData: String, headers: Map<String, String>): HttpResponse {
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
            writer.write(jsonOrFormData)
            writer.flush()
        }

        val code = conn.responseCode
        val responseHeaders = conn.headerFields ?: emptyMap()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        }.orEmpty()

        return HttpResponse(code, responseText, responseHeaders)
    }

    private fun applyTranslationToLyrics(
        lyrics: ParsedLyrics,
        translatedLines: List<String>,
        dualLine: Boolean
    ): ParsedLyrics {
        val newLines = lyrics.lines.mapIndexed { index, line ->
            val trans = translatedLines.getOrNull(index)?.trim().orEmpty()
            if (trans.isNotBlank()) {
                if (dualLine) {
                    line.copy(words = "${line.words}\n$trans")
                } else {
                    line.copy(words = trans, syllables = emptyList())
                }
            } else {
                line
            }
        }
        return lyrics.copy(lines = newLines)
    }
}
