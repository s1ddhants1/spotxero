package app.spotxero.hooks.lyrics

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

object AiLyricsTranslator {

    private const val TAG = "SpotXero.AiLyrics"
    private val translationCache = ConcurrentHashMap<String, List<String>>()

    fun translateLyrics(lyrics: ParsedLyrics, trackKey: String? = null): ParsedLyrics {
        if (!PreferencesManager.isAiLyricsTranslationEnabled) return lyrics
        if (lyrics.lines.isEmpty()) return lyrics

        val provider = PreferencesManager.aiTranslationProvider.ifBlank { "OpenRouter" }
        val apiKey = if (provider == "DeepL") PreferencesManager.aiTranslationDeeplApiKey else PreferencesManager.aiTranslationApiKey
        if (apiKey.isBlank() && provider != "Custom") return lyrics

        val targetLang = PreferencesManager.aiTranslationTargetLang.ifBlank { "en" }
        val mode = PreferencesManager.aiTranslationTranslateMode.ifBlank { "Literal" }
        val cacheKey = if (!trackKey.isNullOrBlank()) "$trackKey::$targetLang::$mode" else null

        if (cacheKey != null) {
            translationCache[cacheKey]?.let { cachedLines ->
                if (cachedLines.size == lyrics.lines.size) {
                    return applyTranslationToLyrics(lyrics, cachedLines, PreferencesManager.isAiTranslationDualLine)
                }
            }
        }

        val rawLines = lyrics.lines.map { it.words }
        val translated = try {
            translate(
                lines = rawLines,
                targetLanguage = targetLang,
                apiKey = apiKey,
                baseUrl = PreferencesManager.aiTranslationBaseUrl,
                model = PreferencesManager.aiTranslationModel,
                mode = mode,
                provider = provider,
                deeplFormality = PreferencesManager.aiTranslationDeeplFormality,
                customSystemPrompt = PreferencesManager.aiTranslationCustomPrompt
            ).getOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "AI lyrics translation failed: ${t.message}")
            null
        }

        if (translated != null && translated.size == lyrics.lines.size) {
            if (cacheKey != null) {
                translationCache[cacheKey] = translated
            }
            return applyTranslationToLyrics(lyrics, translated, PreferencesManager.isAiTranslationDualLine)
        }

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

        if (provider == "DeepL") {
            return translateViaDeepL(lines, targetLanguage, apiKey, deeplFormality)
        }

        val effectiveBaseUrl = when {
            baseUrl.isNotBlank() -> baseUrl
            provider == "OpenAI" -> "https://api.openai.com/v1/chat/completions"
            provider == "Claude" -> "https://api.anthropic.com/v1/messages"
            provider == "Gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
            provider == "Perplexity" -> "https://api.perplexity.ai/chat/completions"
            provider == "XAi" -> "https://api.x.ai/v1/chat/completions"
            provider == "Mistral" -> "https://api.mistral.ai/v1/chat/completions"
            provider == "Inception" -> "https://api.inceptionlabs.ai/v1/chat/completions"
            else -> PrefKeys.OpenRouterDefaultBaseUrl
        }

        repeat(maxRetries) { attempt ->
            try {
                val body = buildTranslationRequest(
                    lines = lines,
                    targetLanguage = targetLanguage,
                    model = model,
                    mode = mode,
                    customSystemPrompt = customSystemPrompt,
                    baseUrl = effectiveBaseUrl
                )

                val headers = mutableMapOf(
                    "Content-Type" to "application/json",
                    "HTTP-Referer" to "https://github.com/MetrolistGroup/Metrolist",
                    "X-Title" to "Metrolist"
                )
                if (apiKey.isNotBlank()) {
                    headers["Authorization"] = "Bearer ${apiKey.trim()}"
                }

                val endpoint = if (!effectiveBaseUrl.contains("/chat/completions") && !effectiveBaseUrl.endsWith("/messages")) {
                    "${effectiveBaseUrl.trimEnd('/')}/chat/completions"
                } else {
                    effectiveBaseUrl
                }

                val responseBody = httpPost(endpoint, body.toString(), headers)
                    ?: throw Exception("Empty response from AI server")

                val respJson = JSONObject(responseBody)
                if (respJson.has("error")) {
                    val errMsg = respJson.optJSONObject("error")?.optString("message") ?: "API Error"
                    return Result.failure(Exception("Translation failed: $errMsg"))
                }

                val choices = respJson.optJSONArray("choices")
                val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                return parseTranslationContent(content, lines.size)
            } catch (e: Exception) {
                if (attempt == maxRetries - 1) return Result.failure(e)
                try {
                    Thread.sleep(1000L * (attempt + 1))
                } catch (_: InterruptedException) {}
            }
        }

        return Result.failure(Exception("Max retries exceeded"))
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

            val root = JSONObject().apply {
                val textArr = JSONArray()
                lines.forEach { textArr.put(it) }
                put("text", textArr)
                put("target_lang", targetLanguage.uppercase().take(2))
                if (formality in listOf("more", "less")) {
                    put("formality", if (formality == "more") "prefer_more" else "prefer_less")
                }
            }

            val headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "DeepL-Auth-Key ${apiKey.trim()}"
            )

            val responseBody = httpPost(endpoint, root.toString(), headers)
                ?: throw Exception("Empty response from DeepL")

            val json = JSONObject(responseBody)
            val translations = json.optJSONArray("translations")
                ?: throw Exception("No translations returned by DeepL")

            val result = mutableListOf<String>()
            for (i in 0 until translations.length()) {
                result.add(translations.getJSONObject(i).optString("text", ""))
            }
            Result.success(result)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun buildTranslationRequest(
        lines: List<String>,
        targetLanguage: String,
        model: String,
        mode: String,
        customSystemPrompt: String,
        baseUrl: String
    ): JSONObject {
        val lineCount = lines.size
        val text = lines.joinToString("\n")

        val systemPrompt = customSystemPrompt.takeIf { it.isNotBlank() }
            ?.replace("{lineCount}", lineCount.toString())
            ?: PrefKeys.DEFAULT_AI_SYSTEM_PROMPT.replace("{lineCount}", lineCount.toString())

        val userPrompt = when (mode) {
            "Romanized" -> {
                """Romanize/transliterate the following $lineCount lines into simple Latin script using ONLY basic English letters (a-z, A-Z).

CRITICAL REQUIREMENTS:
- Use ONLY simple ASCII characters (a-z, A-Z, 0-9, basic punctuation)
- NO special characters like ā, ī, ū, ñ, ç, etc.
- NO diacritics or accent marks
- If text is already in Latin script, return it UNCHANGED
- For non-Latin scripts (Hindi, Chinese, Japanese, Korean, Cyrillic, etc.), provide simple romanization
- DO NOT translate meaning, only convert script to simple English letters
- Keep all punctuation and formatting
- Preserve line-by-line structure exactly

Input ($lineCount lines):
$text

Output MUST be a JSON object {"lines": [...]} with EXACTLY $lineCount strings using ONLY simple ASCII characters."""
            }

            "Transcribed" -> {
                """Transcribe/transliterate the following $lineCount lines phonetically into $targetLanguage script.

CRITICAL REQUIREMENTS:
- Convert the SOUND/PRONUNCIATION of the original text into $targetLanguage script
- DO NOT translate the meaning - only represent how the original words SOUND
- Use the native script of $targetLanguage (e.g., Devanagari for Hindi, Hangul for Korean, etc.)
- Preserve the original pronunciation as closely as possible in the target script
- Keep punctuation and formatting
- Preserve line-by-line structure exactly
- If text is already in $targetLanguage script, return it UNCHANGED

Input ($lineCount lines):
$text

Output MUST be a JSON object {"lines": [...]} with EXACTLY $lineCount strings in $targetLanguage script."""
            }

            else -> {
                """Translate the following $lineCount lines to $targetLanguage.

IMPORTANT:
- Provide natural, accurate translation
- Maintain poetic flow and meaning
- Keep punctuation appropriate for target language
- Preserve line-by-line structure exactly
- For song lyrics, prioritize singability

Input ($lineCount lines):
$text

Output MUST be a JSON object {"lines": [...]} with EXACTLY $lineCount strings."""
            }
        }

        val root = JSONObject().apply {
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

            if (model.isNotBlank()) {
                put("model", model)
            }

            if (!baseUrl.contains("api.openai.com")) {
                put("temperature", 0.3)
                put("max_tokens", lineCount * 100)
            }

            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })

            if (baseUrl.contains("openrouter.ai")) {
                put("provider", JSONObject().apply {
                    put("require_parameters", true)
                })
            }
        }

        return root
    }

    private fun parseTranslationContent(content: String, expectedLineCount: Int): Result<List<String>> {
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val bracketed = cleaned
            .substringAfter('[', "")
            .substringBeforeLast(']', "")
            .takeIf { it.isNotEmpty() }
            ?.let { "[$it]" }

        val candidates = listOfNotNull(content.trim(), cleaned, bracketed)
        for (candidate in candidates) {
            val lines = extractLinesFromJson(candidate)
            if (lines != null) {
                val padded = lines.take(expectedLineCount) + List((expectedLineCount - lines.size).coerceAtLeast(0)) { "" }
                return Result.success(padded)
            }
        }

        val fallbackLines = cleaned.lines()
            .filter { it.isNotBlank() }
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }

        if (fallbackLines.isNotEmpty()) {
            val padded = fallbackLines.take(expectedLineCount) + List((expectedLineCount - fallbackLines.size).coerceAtLeast(0)) { "" }
            return Result.success(padded)
        }

        return Result.failure(Exception("Failed to parse translation JSON"))
    }

    private fun extractLinesFromJson(jsonStr: String): List<String>? {
        return try {
            if (jsonStr.startsWith("{")) {
                val obj = JSONObject(jsonStr)
                val linesArray = obj.optJSONArray("lines")
                if (linesArray != null) {
                    val result = mutableListOf<String>()
                    for (i in 0 until linesArray.length()) {
                        result.add(linesArray.optString(i))
                    }
                    return result
                }
            } else if (jsonStr.startsWith("[")) {
                val array = JSONArray(jsonStr)
                val result = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    result.add(array.optString(i))
                }
                return result
            }
            null
        } catch (_: Throwable) {
            null
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

    private fun httpPost(urlString: String, jsonBody: String, headers: Map<String, String>): String? {
        return try {
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
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "HTTP POST error: ${t.message}")
            throw t
        }
    }
}
