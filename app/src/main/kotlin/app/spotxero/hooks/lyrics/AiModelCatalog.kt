package app.spotxero.hooks.lyrics

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate

data class AiModelInfo(
    val id: String,
    val name: String = id,
    val inputUsdPerMTok: Double? = null,
    val outputUsdPerMTok: Double? = null,
    val contextLength: Int? = null,
    val tokensPerSecond: Double? = null,
) {
    val free: Boolean get() = (inputUsdPerMTok == 0.0 && outputUsdPerMTok == 0.0) || id.endsWith(":free")
}

data class ModelsDevCost(
    val input: Double? = null,
    val output: Double? = null,
)

data class ModelsDevModel(
    val name: String? = null,
    val cost: ModelsDevCost? = null,
    val contextLimit: Int? = null,
    val npmProvider: String? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    val status: String? = null,
    val expirationDate: String? = null,
)

data class ModelsDevProvider(
    val models: Map<String, ModelsDevModel> = emptyMap(),
)

object AiModelCatalog {
    private val cache = mutableMapOf<String, List<AiModelInfo>>()
    private val availableIds = mutableMapOf<String, Set<String>>()
    private val throughputCache = mutableMapOf<String, Double?>()
    private val lock = Mutex()
    private val modelLoadLock = Mutex()
    private var modelsDev: Map<String, ModelsDevProvider>? = null

    suspend fun models(
        preset: AiProviderPreset,
        apiKey: String,
        baseUrl: String,
        refresh: Boolean = false,
    ): Result<List<AiModelInfo>> = withContext(Dispatchers.IO) {
        modelLoadLock.withLock {
            val cacheKey = cacheKey(preset, apiKey, baseUrl)
            if (!refresh) lock.withLock { cache[cacheKey] }?.let { return@withLock Result.success(it) }
            try {
                val fromApi = if (preset.modelsEndpoint.isNotBlank()) fetchProviderModels(preset, apiKey, baseUrl, cacheKey) else null
                if (fromApi?.isFailure == true) lock.withLock { availableIds.remove(cacheKey) }
                val dev = preset.modelsDevId?.let { devProvider(it) }
                var result = fromApi?.getOrNull()?.let { priceFromModelsDev(it, dev, preset) }
                if (result.isNullOrEmpty() && (preset.id != "OpenRouter" || fromApi?.isSuccess != true)) {
                    result = dev?.let(::parseModelsDevModels)?.let { priceFromModelsDev(it, dev, preset) }
                }
                if (result.isNullOrEmpty() && preset.id == "OpenRouter" && fromApi?.isSuccess != true) {
                    result = filterAndSort(AiProviderPresets.openRouterOfflineModels)
                }
                if (result == null) {
                    return@withLock Result.failure(fromApi?.exceptionOrNull() ?: IllegalStateException("No model list available"))
                }
                lock.withLock { cache[cacheKey] = result }
                Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun cacheKey(
        preset: AiProviderPreset,
        apiKey: String,
        baseUrl: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(apiKey.trim().toByteArray(Charsets.UTF_8))
        val credentialHash = Base64.encodeToString(hash, Base64.NO_WRAP)
        return "${preset.id}|$baseUrl|$credentialHash"
    }

    suspend fun validatedModel(
        preset: AiProviderPreset,
        apiKey: String,
        baseUrl: String,
        savedModel: String,
    ): String {
        val effectiveBaseUrl =
            if (preset.id == "Gemini" && baseUrl == AiProviderPresets.LEGACY_GEMINI_ENDPOINT) {
                preset.endpoint
            } else {
                baseUrl
            }
        if (!preset.isAi || preset.editableEndpoint || effectiveBaseUrl != preset.endpoint) return savedModel
        val models = models(preset, apiKey, effectiveBaseUrl).getOrNull() ?: return savedModel
        val ids = lock.withLock { availableIds[cacheKey(preset, apiKey, effectiveBaseUrl)] } ?: return savedModel
        if (ids.isEmpty() || savedModel in ids) return savedModel
        val candidates = models.filter { it.id in ids }
        return defaultModel(preset, candidates, freeOnly = savedModel.endsWith(":free")) ?: savedModel
    }

    suspend fun throughput(modelId: String): Double? = withContext(Dispatchers.IO) {
        lock.withLock { if (throughputCache.containsKey(modelId)) return@withContext throughputCache[modelId] }
        val value = try {
            val response = executeHttpGet("https://openrouter.ai/api/v1/models/$modelId/endpoints", emptyMap(), 15_000)
            if (response.first in 200..299) parseOpenRouterThroughput(response.second) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        lock.withLock { throughputCache[modelId] = value }
        value
    }

    private fun fetchProviderModels(
        preset: AiProviderPreset,
        apiKey: String,
        baseUrl: String,
        cacheKey: String,
    ): Result<List<AiModelInfo>> {
        if (apiKey.isBlank() && !preset.publicModelList) return Result.failure(IllegalStateException("API key required"))
        val root = baseUrl.ifBlank { preset.endpoint }
        val url = AiProviderPreset(preset.id, preset.group, preset.protocol, root)
            .modelsEndpoint
            .let {
                if (preset.protocol == AiProtocol.ANTHROPIC) {
                    "$it?limit=1000"
                } else if (preset.protocol == AiProtocol.GEMINI) {
                    "$it?pageSize=1000"
                } else {
                    it
                }
            }

        return try {
            val headers = mutableMapOf<String, String>()
            if (preset.id == "OpenRouter" || url.contains("openrouter.ai")) {
                headers["HTTP-Referer"] = "https://github.com/s1ddhants1/SpotXero"
                headers["X-Title"] = "SpotXero"
            }
            if (apiKey.isNotBlank()) {
                when (preset.protocol) {
                    AiProtocol.ANTHROPIC -> {
                        headers["x-api-key"] = apiKey.trim()
                        headers["anthropic-version"] = "2023-06-01"
                    }
                    AiProtocol.GEMINI -> {
                        headers["x-goog-api-key"] = apiKey.trim()
                    }
                    AiProtocol.OPENAI -> {
                        headers["Authorization"] = "Bearer ${apiKey.trim()}"
                    }
                }
            }

            val (code, text) = executeHttpGet(url, headers, 20_000)
            if (code !in 200..299) {
                Result.failure(IllegalStateException("Model list failed ($code)"))
            } else {
                val rootObject = JSONObject(text)
                val entries = rootObject.optJSONArray(if (preset.protocol == AiProtocol.GEMINI) "models" else "data")
                val idKey = if (preset.protocol == AiProtocol.GEMINI) "name" else "id"
                val ids = mutableSetOf<String>()
                if (entries != null) {
                    for (i in 0 until entries.length()) {
                        val entry = entries.optJSONObject(i) ?: continue
                        val rawId = entry.optString(idKey, "")
                        if (rawId.isNotEmpty()) {
                            ids.add(rawId.removePrefix("models/"))
                        }
                    }
                }
                val incomplete = rootObject.optBoolean("has_more", false) ||
                    rootObject.optString("nextPageToken", "").isNotEmpty()

                lock.tryLock().let { hasLock ->
                    if (hasLock) {
                        try {
                            if (incomplete) availableIds.remove(cacheKey) else availableIds[cacheKey] = ids
                        } finally {
                            lock.unlock()
                        }
                    }
                }

                Result.success(
                    when (preset.protocol) {
                        AiProtocol.ANTHROPIC -> parseAnthropicModels(rootObject)
                        AiProtocol.GEMINI -> parseGeminiModels(rootObject)
                        AiProtocol.OPENAI -> parseOpenAiModels(rootObject, textOnly = preset.id == "OpenRouter")
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun devProvider(id: String): ModelsDevProvider? {
        val root = modelsDev ?: try {
            val (code, text) = executeHttpGet("https://models.dev/api.json", emptyMap(), 30_000)
            if (code !in 200..299 || text.isBlank()) return null
            parseModelsDevJson(text).also { modelsDev = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return root[id]
    }

    private fun priceFromModelsDev(
        models: List<AiModelInfo>,
        dev: ModelsDevProvider?,
        preset: AiProviderPreset,
    ): List<AiModelInfo> {
        val entries = dev?.models
        val sorted = models.filter { keep(it, entries, preset) }.map { model ->
            if (model.inputUsdPerMTok != null || entries == null) return@map model
            val cost = entries[model.id]?.cost ?: return@map model
            model.copy(
                inputUsdPerMTok = cost.input,
                outputUsdPerMTok = cost.output,
            )
        }
        return filterAndSort(sorted)
    }

    private fun keep(
        model: AiModelInfo,
        known: Map<String, ModelsDevModel>?,
        preset: AiProviderPreset,
    ): Boolean {
        if (known == null) return true
        val entry = known[model.id]
        if (entry != null && !modelsDevTextModel(entry)) return false
        return when {
            preset.mixedProtocols -> entry == null || implemented(entry)
            preset.group == AiProviderGroup.DIRECT && preset.protocol == AiProtocol.OPENAI -> entry != null
            else -> true
        }
    }

    fun parseOpenAiModels(root: JSONObject, textOnly: Boolean): List<AiModelInfo> {
        val result = mutableListOf<AiModelInfo>()
        val data = root.optJSONArray("data") ?: return result
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            if (!openAiTextModel(entry, requireMetadata = textOnly) || !activeModel(entry)) continue
            val id = entry.optString("id", "")
            if (id.isEmpty()) continue
            val pricing = entry.optJSONObject("pricing")
            val promptCost = if (pricing != null && pricing.has("prompt")) pricing.optDouble("prompt") * 1_000_000.0 else null
            val compCost = if (pricing != null && pricing.has("completion")) pricing.optDouble("completion") * 1_000_000.0 else null
            val ctxLength = if (entry.has("context_length")) entry.optInt("context_length") else null

            result.add(
                AiModelInfo(
                    id = id,
                    name = entry.optString("name", id),
                    inputUsdPerMTok = promptCost,
                    outputUsdPerMTok = compCost,
                    contextLength = ctxLength
                )
            )
        }
        return result
    }

    fun parseAnthropicModels(root: JSONObject): List<AiModelInfo> {
        val result = mutableListOf<AiModelInfo>()
        val data = root.optJSONArray("data") ?: return result
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val id = entry.optString("id", "")
            if (id.isEmpty()) continue
            result.add(AiModelInfo(id, entry.optString("display_name", id)))
        }
        return result
    }

    fun parseGeminiModels(root: JSONObject): List<AiModelInfo> {
        val result = mutableListOf<AiModelInfo>()
        val models = root.optJSONArray("models") ?: return result
        for (i in 0 until models.length()) {
            val entry = models.optJSONObject(i) ?: continue
            val methods = entry.optJSONArray("supportedGenerationMethods")
            var supportsGenerate = false
            if (methods != null) {
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") {
                        supportsGenerate = true
                        break
                    }
                }
            }
            if (!supportsGenerate) continue
            val rawName = entry.optString("name", "")
            val id = rawName.removePrefix("models/")
            if (id.isEmpty()) continue
            val ctx = if (entry.has("inputTokenLimit")) entry.optInt("inputTokenLimit") else null
            result.add(AiModelInfo(id, entry.optString("displayName", id), contextLength = ctx))
        }
        return result
    }

    private fun parseModelsDevModels(provider: ModelsDevProvider): List<AiModelInfo> {
        return provider.models.mapNotNull { (id, entry) ->
            if (!implemented(entry) || !modelsDevTextModel(entry)) return@mapNotNull null
            AiModelInfo(
                id = id,
                name = entry.name ?: id,
                inputUsdPerMTok = entry.cost?.input,
                outputUsdPerMTok = entry.cost?.output,
                contextLength = entry.contextLimit,
            )
        }
    }

    private fun parseModelsDevJson(text: String): Map<String, ModelsDevProvider> {
        val root = JSONObject(text)
        val result = mutableMapOf<String, ModelsDevProvider>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val providerKey = keys.next()
            val provObj = root.optJSONObject(providerKey) ?: continue
            val modelsObj = provObj.optJSONObject("models") ?: continue
            val modelMap = mutableMapOf<String, ModelsDevModel>()
            val modelKeys = modelsObj.keys()
            while (modelKeys.hasNext()) {
                val mKey = modelKeys.next()
                val mObj = modelsObj.optJSONObject(mKey) ?: continue
                val costObj = mObj.optJSONObject("cost")
                val cost = costObj?.let {
                    ModelsDevCost(
                        input = if (it.has("input")) it.optDouble("input") else null,
                        output = if (it.has("output")) it.optDouble("output") else null,
                    )
                }
                val limitObj = mObj.optJSONObject("limit")
                val ctx = limitObj?.optInt("context")
                val providerObj = mObj.optJSONObject("provider")
                val npm = if (providerObj?.has("npm") == true) providerObj.optString("npm") else null
                val status = if (mObj.has("status")) mObj.optString("status") else null
                val exp = if (mObj.has("expiration_date")) mObj.optString("expiration_date") else null

                modelMap[mKey] = ModelsDevModel(
                    name = mObj.optString("name", mKey),
                    cost = cost,
                    contextLimit = ctx,
                    npmProvider = npm,
                    status = status,
                    expirationDate = exp
                )
            }
            result[providerKey] = ModelsDevProvider(modelMap)
        }
        return result
    }

    fun parseOpenRouterThroughput(body: String): Double? {
        return runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return null
            val endpoints = data.optJSONArray("endpoints") ?: return null
            var maxThroughput: Double? = null
            for (i in 0 until endpoints.length()) {
                val ep = endpoints.optJSONObject(i) ?: continue
                val last30m = ep.opt("throughput_last_30m")
                val candidate: Double? = when (last30m) {
                    is JSONObject -> if (last30m.has("p50")) last30m.optDouble("p50") else null
                    is Number -> last30m.toDouble()
                    null -> null
                    else -> null
                }
                if (candidate != null) {
                    maxThroughput = maxOf(maxThroughput ?: 0.0, candidate)
                }
            }
            maxThroughput
        }.getOrNull()
    }

    private fun implemented(entry: ModelsDevModel): Boolean {
        val npm = entry.npmProvider ?: return true
        return npm == "@ai-sdk/anthropic" || npm == "@ai-sdk/openai-compatible"
    }

    fun defaultModel(
        preset: AiProviderPreset,
        models: List<AiModelInfo>,
        freeOnly: Boolean = false,
    ): String? {
        val candidates = filterAndSort(models).filter { !freeOnly || (it.free && it.id.endsWith(":free")) }
        val preferred = if (freeOnly) AiProviderPresets.FREE_LYRICS_AI_MODEL else preset.defaultModel
        return candidates.firstOrNull { it.id == preferred }?.id ?: candidates.firstOrNull()?.id
    }

    fun filterAndSort(models: List<AiModelInfo>): List<AiModelInfo> =
        models
            .filter { model ->
                val input = model.inputUsdPerMTok
                val output = model.outputUsdPerMTok
                !excludedId.containsMatchIn(model.id) &&
                    (input == null || (input.isFinite() && input in 0.0..1.0)) &&
                    (output == null || (output.isFinite() && output in 0.0..5.0))
            }.distinctBy { it.id }
            .sortedWith(
                compareByDescending<AiModelInfo> { it.free }
                    .thenBy { (it.inputUsdPerMTok ?: Double.MAX_VALUE) + (it.outputUsdPerMTok ?: Double.MAX_VALUE) }
                    .thenBy { it.id }
            )

    private val excludedId = Regex(
        "^~|preview|experimental|deprecated|:batch|embed|realtime|audio|tts|whisper|transcri|image|safeguard|prompt-guard|moderation",
        RegexOption.IGNORE_CASE,
    )

    private fun activeModel(entry: JSONObject): Boolean =
        activeModel(
            if (entry.has("status")) entry.optString("status") else null,
            if (entry.has("expiration_date")) entry.optString("expiration_date") else null
        )

    private fun activeModel(
        status: String?,
        expiration: String?,
    ): Boolean {
        if (status in listOf("deprecated", "preview", "alpha", "beta")) return false
        if (expiration == null) return true
        val today = LocalDate.now().toString()
        return expiration > today
    }

    private fun modelsDevTextModel(entry: ModelsDevModel): Boolean =
        activeModel(entry.status, entry.expirationDate) &&
            (entry.inputModalities?.contains("text") != false) &&
            (entry.outputModalities == null || entry.outputModalities == listOf("text"))

    private fun openAiTextModel(
        entry: JSONObject,
        requireMetadata: Boolean,
    ): Boolean {
        val arch = entry.optJSONObject("architecture")
        val inputModalities = arch?.optJSONArray("input_modalities")
        val outputModalities = arch?.optJSONArray("output_modalities")

        var hasInputText = false
        var hasOutputTextOnly = false

        if (inputModalities != null && inputModalities.length() > 0) {
            for (i in 0 until inputModalities.length()) {
                if (inputModalities.optString(i) == "text") hasInputText = true
            }
        }
        if (outputModalities != null && outputModalities.length() > 0) {
            if (outputModalities.length() == 1 && outputModalities.optString(0) == "text") {
                hasOutputTextOnly = true
            }
        }

        val endpoints = entry.optJSONArray("endpoints")
        if (endpoints != null && endpoints.length() > 0) {
            var hasChat = false
            for (i in 0 until endpoints.length()) {
                if (endpoints.optString(i).endsWith("/chat/completions")) {
                    hasChat = true
                    break
                }
            }
            if (!hasChat) return false
        }

        if (inputModalities == null && outputModalities == null) return !requireMetadata
        return hasInputText && hasOutputTextOnly
    }

    private fun executeHttpGet(urlString: String, headers: Map<String, String>, timeoutMs: Int): Pair<Int, String> {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "SpotXero/2.0 (Android; +https://github.com/s1ddhants1/SpotXero)")
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        }.orEmpty()
        return Pair(code, text)
    }
}
