package app.spotxero.hooks.lyrics

import org.json.JSONArray
import org.json.JSONObject

enum class LyricsTranslationFailure {
    DATA_POLICY,
    AUTHENTICATION,
    CREDIT_LIMIT,
    RATE_LIMIT,
    FREE_DAILY_LIMIT,
    FREE_MINUTE_LIMIT,
    MODEL_BUSY,
    MODEL_UNAVAILABLE,
    NETWORK,
    EMPTY_RESPONSE,
    INVALID_RESPONSE,
    INCOMPLETE_RESPONSE,
    TIMEOUT,
    REQUEST_FAILED
}

class LyricsTranslationException(
    val failure: LyricsTranslationFailure,
    message: String,
    val statusCode: Int? = null,
    val retryAtMillis: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause)

fun classifyLyricsTranslationHttpFailure(
    statusCode: Int,
    responseBody: String,
    headers: Map<String, List<String>> = emptyMap()
): LyricsTranslationFailure {
    val root = runCatching { JSONObject(responseBody) }.getOrNull()
    val choices = root?.optJSONArray("choices")
    val firstChoice = choices?.optJSONObject(0)
    val error = root?.optJSONObject("error") ?: firstChoice?.optJSONObject("error")
    val metadata = error?.optJSONObject("metadata")
    val type = metadata?.optString("error_type")?.takeIf { it.isNotEmpty() }
        ?: error?.optString("error_type")?.takeIf { it.isNotEmpty() }
        ?: error?.optString("type")?.takeIf { it.isNotEmpty() }
        ?: root?.optString("type")?.takeIf { it.isNotEmpty() }
    val message = error?.optString("message", "")?.lowercase() ?: ""
    val code = error?.optInt("code", statusCode) ?: statusCode

    return when {
        "data policy" in message || "privacy settings" in message -> {
            LyricsTranslationFailure.DATA_POLICY
        }

        type.equals("authentication", ignoreCase = true) || code == 401 -> {
            LyricsTranslationFailure.AUTHENTICATION
        }

        type.equals("payment_required", ignoreCase = true) || code == 402 -> {
            LyricsTranslationFailure.CREDIT_LIMIT
        }

        type.equals("rate_limit_exceeded", ignoreCase = true) ||
            type.equals("FreeUsageLimitError", ignoreCase = true) ||
            code == 429 -> {
            when {
                "free-models-per-day" in message || "daily" in message || "per day" in message ||
                    "free usage limit" in message || type.equals("FreeUsageLimitError", ignoreCase = true) -> {
                    LyricsTranslationFailure.FREE_DAILY_LIMIT
                }

                "free-models-per-min" in message || "per minute" in message -> {
                    LyricsTranslationFailure.FREE_MINUTE_LIMIT
                }

                headers.keys.any { it.startsWith("X-RateLimit-", ignoreCase = true) } -> {
                    LyricsTranslationFailure.RATE_LIMIT
                }

                "upstream" in message || metadata?.has("provider_name") == true || metadata?.has("provider_code") == true -> {
                    LyricsTranslationFailure.MODEL_BUSY
                }

                else -> {
                    LyricsTranslationFailure.RATE_LIMIT
                }
            }
        }

        type in setOf("not_found", "provider_unavailable", "provider_overloaded") || code in setOf(404, 502, 503) -> {
            LyricsTranslationFailure.MODEL_UNAVAILABLE
        }

        type.equals("timeout", ignoreCase = true) || code == 408 || code == 504 -> {
            LyricsTranslationFailure.TIMEOUT
        }

        else -> {
            LyricsTranslationFailure.REQUEST_FAILED
        }
    }
}

fun parseRetryAtMillis(headers: Map<String, List<String>>, now: Long = System.currentTimeMillis()): Long? {
    val resetHeader = headers.entries.firstOrNull { it.key.equals("X-RateLimit-Reset", ignoreCase = true) }?.value?.firstOrNull()
    val reset = resetHeader?.toLongOrNull()?.let { if (it < 1_000_000_000_000L) it * 1_000L else it }
    if (reset != null && reset > now) return reset

    val retryAfterHeader = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull()
    val retryAfter = retryAfterHeader?.toLongOrNull()?.takeIf { it in 1..86_400 }
    if (retryAfter != null) return now + retryAfter * 1_000L

    return null
}

fun extractErrorMessage(statusCode: Int, responseBody: String): String {
    val clean = runCatching {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
        val firstChoice = choices?.optJSONObject(0)
        val error = root.optJSONObject("error") ?: firstChoice?.optJSONObject("error")
        error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: root.optString("message").takeIf { it.isNotBlank() }
            ?: root.optString("error").takeIf { it.isNotBlank() }
    }.getOrNull()
    return clean ?: responseBody.take(200)
}
