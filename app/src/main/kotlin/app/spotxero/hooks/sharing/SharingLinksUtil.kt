package app.spotxero.hooks.sharing

import android.net.Uri
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object SharingLinksUtil {

    private val KNOWN_UTM_PARAMS = setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "utm_source_platform",
        "utm_creative",
        "utm_campaign_id"
    )

    private val SPOTIFY_URL_REGEX = Regex(
        """https?://(?:[a-zA-Z0-9.-]+\.)?spotify\.(?:com|link)/[^\s<>\"'`]*""",
        RegexOption.IGNORE_CASE
    )

    private val holderFields = ConcurrentHashMap<Class<*>, List<Field>>()

    fun sanitizeResult(result: Any?): Any? {
        if (result is String) return sanitizeUrl(result) ?: result
        if (result == null) return null
        return runCatching { sanitizeFields(result) }.getOrDefault(result)
    }

    fun isTrackingParam(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "si" || lower == "pi" || lower in KNOWN_UTM_PARAMS || lower.startsWith("utm_")
    }

    private fun isSpotifyHost(host: String?): Boolean {
        if (host == null) return false
        val lowerHost = host.lowercase()
        return lowerHost == "open.spotify.com" ||
            lowerHost.endsWith(".spotify.com") ||
            lowerHost == "spotify.com" ||
            lowerHost.endsWith(".spotify.link") ||
            lowerHost == "spotify.link"
    }

    fun sanitizeUrl(textOrUrl: String?): String? {
        if (textOrUrl.isNullOrEmpty()) return textOrUrl

        if (!textOrUrl.contains(' ') && (textOrUrl.startsWith("http://", ignoreCase = true) || textOrUrl.startsWith("https://", ignoreCase = true))) {
            return sanitizeSingleUri(textOrUrl)
        }

        if (SPOTIFY_URL_REGEX.containsMatchIn(textOrUrl)) {
            return SPOTIFY_URL_REGEX.replace(textOrUrl) { matchResult ->
                val raw = matchResult.value
                val trimmed = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                val suffix = raw.substring(trimmed.length)
                sanitizeSingleUri(trimmed) + suffix
            }
        }

        if (!textOrUrl.contains(' ') && (textOrUrl.startsWith("open.spotify.com/", ignoreCase = true) || textOrUrl.startsWith("spotify.link/", ignoreCase = true))) {
            val sanitized = sanitizeSingleUri("https://$textOrUrl")
            return if (sanitized.startsWith("https://", ignoreCase = true)) {
                sanitized.substring(8)
            } else {
                sanitized
            }
        }

        return textOrUrl
    }

    fun sanitizeSingleUri(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        if (!isSpotifyHost(uri.host)) return url

        val paramNames = runCatching { uri.queryParameterNames }.getOrNull()
        if (paramNames.isNullOrEmpty()) return url

        val hasTracking = paramNames.any { isTrackingParam(it) }
        if (!hasTracking) return url

        val builder = uri.buildUpon().clearQuery()
        for (name in paramNames) {
            if (!isTrackingParam(name)) {
                val values = runCatching { uri.getQueryParameters(name) }.getOrNull()
                if (values.isNullOrEmpty()) {
                    builder.appendQueryParameter(name, "")
                } else {
                    for (value in values) {
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
        }
        return builder.build().toString()
    }

    private fun sanitizeFields(holder: Any): Any {
        val fields = holderFields.computeIfAbsent(holder.javaClass) { type ->
            type.declaredFields
                .filter { it.type == Any::class.java || it.type == String::class.java }
                .onEach { it.isAccessible = true }
        }
        for (field in fields) {
            val value = field.get(holder) as? String ?: continue
            val sanitized = sanitizeUrl(value) ?: continue
            if (sanitized != value) {
                runCatching { field.set(holder, sanitized) }
            }
        }
        return holder
    }
}
