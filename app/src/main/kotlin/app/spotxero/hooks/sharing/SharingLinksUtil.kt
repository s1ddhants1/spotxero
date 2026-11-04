package app.spotxero.hooks.sharing

import android.net.Uri
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object SharingLinksUtil {

    private val TRACKING_PARAMS = setOf(
        "si", "pi", "utm_source", "utm_medium", "utm_campaign",
        "utm_content", "utm_term", "utm_id", "context", "nd"
    )

    private val holderFields = ConcurrentHashMap<Class<*>, List<Field>>()

    fun sanitizeResult(result: Any?): Any? {
        if (result is String) return sanitizeUrl(result) ?: result
        if (result == null) return null
        return runCatching { sanitizeFields(result) }.getOrDefault(result)
    }

    fun sanitizeUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host ?: return url
        if (!host.endsWith("spotify.com") && !host.endsWith("spotify.link")) return url

        val paramNames = runCatching { uri.queryParameterNames }.getOrNull()
        if (paramNames.isNullOrEmpty()) return url

        val hasTracking = paramNames.any { it in TRACKING_PARAMS }
        if (!hasTracking) return url

        val builder = uri.buildUpon().clearQuery()
        for (name in paramNames) {
            if (name !in TRACKING_PARAMS) {
                builder.appendQueryParameter(name, uri.getQueryParameter(name))
            }
        }
        return builder.build().toString()
    }

    private fun sanitizeFields(holder: Any): Any {
        val fields = holderFields.computeIfAbsent(holder.javaClass) { type ->
            type.declaredFields
                .filter { it.type == Any::class.java }
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
