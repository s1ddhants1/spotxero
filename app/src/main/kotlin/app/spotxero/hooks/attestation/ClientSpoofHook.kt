package app.spotxero.hooks.attestation

import android.os.Build
import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object ClientSpoofHook {

    private const val TAG = "SpotXero.ClientSpoof"
    private const val CLIENTTOKEN_MARKER = "clienttoken.spotify.com/v1/clienttoken"
    private const val CLIENTTOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val WEB_CLIENT_ID = "f6a40776580943a7bc5173125a1e8832"
    private const val WEB_CLIENT_VERSION = "1.2.10.35.g47464f56"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val DEVICE_ID_REGEX = "\"device_id\"\\s*:\\s*\"([0-9a-zA-Z]+)\""
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val TOKEN_REUSE_MS = 12L * 60L * 60L * 1000L

    private val deviceIdPattern = Pattern.compile(DEVICE_ID_REGEX)

    @Volatile private var cachedToken: ByteArray? = null
    @Volatile private var cachedDeviceId: String? = null
    @Volatile private var cachedAt: Long = 0L
    private val tokenLock = Any()

    private fun enabled() = PreferencesManager.isEnabled(PrefKeys.KEY_CLIENT_SPOOF, true)

    fun install(module: XposedModule) {
        installOutcomeSuppression(module)
        installClientTokenSeam(module)
    }

    private fun installOutcomeSuppression(module: XposedModule) {
        val emitters = DexKitResolver
            .get<List<Method>>(DexKitResolver.KEY_PLAY_INTEGRITY_OUTCOMES)
            .orEmpty()
        if (emitters.isEmpty()) {
            Log.i(TAG, "No Play Integrity outcome emitters resolved")
            return
        }
        emitters.forEach { emitter ->
            module.deoptimize(emitter)
            module.hook(emitter)
                .setId("client_spoof_outcome_${emitter.name}")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    Log.i(TAG, "Suppressed Play Integrity outcome report: ${emitter.name}")
                    null
                }
        }
        Log.i(TAG, "Suppressed ${emitters.size} Play Integrity outcome emitters")
    }

    private fun installClientTokenSeam(module: XposedModule) {
        val cl = DexKitResolver
            .get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)
            ?.declaringClass?.classLoader ?: return
        val connClass = runCatching { Class.forName("com.spotify.core.http.NativeHttpConnection", false, cl) }.getOrNull() ?: return
        val reqClass = runCatching { Class.forName("com.spotify.core.http.HttpRequest", false, cl) }.getOrNull() ?: return
        val optClass = runCatching { Class.forName("com.spotify.core.http.HttpOptions", false, cl) }.getOrNull() ?: return
        val respClass = runCatching { Class.forName("com.spotify.core.http.HttpResponse", false, cl) }.getOrNull() ?: return

        val sendMethod = runCatching { connClass.getDeclaredMethod("send", reqClass, optClass) }.getOrNull() ?: return
        val getUrlMethod = runCatching { reqClass.getMethod("getUrl") }.getOrNull() ?: return
        val getBodyMethod = runCatching { reqClass.getMethod("getBody") }.getOrNull() ?: return
        val onHeadersMethod = runCatching { connClass.getMethod("onHeaders", respClass) }.getOrNull() ?: return
        val onBytesMethod = runCatching {
            connClass.getMethod("onBytesAvailable", ByteArray::class.java, Int::class.javaPrimitiveType)
        }.getOrNull() ?: return
        val onCompleteMethod = runCatching { connClass.getMethod("onComplete", Boolean::class.javaPrimitiveType) }.getOrNull() ?: return
        val respCtor = runCatching {
            respClass.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
        }.getOrNull() ?: return

        module.deoptimize(sendMethod)
        module.hook(sendMethod)
            .setId("client_spoof_clienttoken_seam")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!enabled()) return@intercept chain.proceed()
                val request = chain.getArg(0)
                val connection = chain.getThisObject()
                if (request == null || connection == null) return@intercept chain.proceed()
                val url = runCatching { getUrlMethod.invoke(request) as? String }.getOrNull()
                if (url == null || !url.contains(CLIENTTOKEN_MARKER)) return@intercept chain.proceed()
                val body = runCatching { getBodyMethod.invoke(request) as? ByteArray }.getOrNull()
                val deviceId = extractDeviceId(body)
                if (deviceId == null) {
                    Log.i(TAG, "Clienttoken request missing parsable device id, using native flow")
                    return@intercept chain.proceed()
                }
                val token = obtainToken(deviceId)
                if (token == null) {
                    Log.i(TAG, "Web client token unavailable, using native flow")
                    return@intercept chain.proceed()
                }
                var committed = false
                val failure = runCatching {
                    onHeadersMethod.invoke(connection, respCtor.newInstance(200, url, "Content-Type: application/x-protobuf\n"))
                    committed = true
                    onBytesMethod.invoke(connection, token, token.size)
                    onCompleteMethod.invoke(connection, false)
                    Log.i(TAG, "Injected web client token (${token.size} bytes)")
                }.exceptionOrNull()
                if (committed) {
                    failure?.let { Log.w(TAG, "Web client token delivery incomplete", it) }
                    null
                } else {
                    failure?.let { Log.w(TAG, "Web client token delivery failed, using native flow", it) }
                    chain.proceed()
                }
            }
        Log.i(TAG, "Clienttoken seam installed")
    }

    private fun extractDeviceId(body: ByteArray?): String? {
        if (body == null || body.isEmpty()) return null
        val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: return null
        val matcher = deviceIdPattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun obtainToken(deviceId: String): ByteArray? {
        val fresh = cachedToken
        if (fresh != null && cachedDeviceId == deviceId && System.currentTimeMillis() - cachedAt < TOKEN_REUSE_MS) {
            return fresh
        }
        synchronized(tokenLock) {
            val cached = cachedToken
            if (cached != null && cachedDeviceId == deviceId && System.currentTimeMillis() - cachedAt < TOKEN_REUSE_MS) {
                return cached
            }
            val token = fetchWebToken(deviceId) ?: return null
            cachedToken = token
            cachedDeviceId = deviceId
            cachedAt = System.currentTimeMillis()
            return token
        }
    }

    private fun fetchWebToken(deviceId: String): ByteArray? {
        val payload = (
            "{\"client_data\":{\"client_version\":\"" + WEB_CLIENT_VERSION +
                "\",\"client_id\":\"" + WEB_CLIENT_ID +
                "\",\"js_sdk_data\":{\"device_brand\":\"unknown\",\"device_model\":\"unknown\",\"os\":\"android\",\"os_version\":\"" +
                Build.VERSION.RELEASE + "\",\"device_id\":\"" + deviceId + "\",\"device_type\":\"smartphone\"}}}"
            ).toByteArray(Charsets.UTF_8)
        return runCatching {
            val connection = URL(CLIENTTOKEN_URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/x-protobuf")
                connection.setRequestProperty("Origin", "https://open.spotify.com")
                connection.setRequestProperty("Referer", "https://open.spotify.com/")
                connection.setRequestProperty("App-Platform", "WebPlayer")
                connection.setRequestProperty("User-Agent", WEB_USER_AGENT)
                connection.outputStream.use { it.write(payload) }
                if (connection.responseCode != 200) return@runCatching null
                val contentType = connection.contentType ?: ""
                if (!contentType.contains("x-protobuf")) return@runCatching null
                val bytes = ByteArrayOutputStream().also { buffer ->
                    connection.inputStream.use { it.copyTo(buffer, 8192) }
                }.toByteArray()
                if (bytes.isEmpty()) null else bytes
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
