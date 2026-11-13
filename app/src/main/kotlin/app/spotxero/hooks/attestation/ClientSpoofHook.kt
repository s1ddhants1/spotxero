package app.spotxero.hooks.attestation

import android.net.Uri
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL

object ClientSpoofHook {

    private const val NATIVE_HTTP_CONNECTION = "com.spotify.core.http.NativeHttpConnection"
    private const val HTTP_REQUEST = "com.spotify.core.http.HttpRequest"
    private const val HTTP_OPTIONS = "com.spotify.core.http.HttpOptions"
    private const val HTTP_RESPONSE = "com.spotify.core.http.HttpResponse"
    private const val APPLICATION_SCOPE_CONFIG = "com.spotify.connectivity.ApplicationScopeConfiguration"

    private const val CLIENT_TOKEN_HOST = "clienttoken.spotify.com"
    private const val CLIENT_TOKEN_PATH = "/v1/clienttoken"
    private const val CLIENT_TOKEN_URL = "https://$CLIENT_TOKEN_HOST$CLIENT_TOKEN_PATH"

    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val IOS_CLIENT_VERSION = "iphone-9.0.58.558.g200011c"
    private const val IOS_HW_MACHINE = "iPhone16,1"
    private const val IOS_SYSTEM_VERSION = "17.7.2"
    private const val IOS_USER_AGENT = "Spotify/9.0.58.558 iOS/17.7.2 (iPhone16,1)"

    private const val PROTOBUF_CONTENT_TYPE = "application/x-protobuf"
    private const val RESPONSE_HEADERS = "SPT-Protocol: http/1.1\nContent-Type: $PROTOBUF_CONTENT_TYPE\n"

    private const val REQUEST_TYPE_CLIENT_DATA = 1
    private const val TRANSPORT_ERROR = 1

    private fun enabled() = PreferencesManager.isEnabled(PrefKeys.KEY_CLIENT_SPOOF, false)

    fun install(module: XposedModule) {
        val cl = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)?.declaringClass?.classLoader
            ?: return
        hookNativeHttpConnection(module, cl)
        hookApplicationScopeConfiguration(module, cl)
    }

    private fun isClientTokenRequest(url: String?): Boolean {
        if (url == null) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.host == CLIENT_TOKEN_HOST && uri.path == CLIENT_TOKEN_PATH
    }

    private fun hookNativeHttpConnection(module: XposedModule, cl: ClassLoader) {
        val connClass = runCatching { Class.forName(NATIVE_HTTP_CONNECTION, false, cl) }.getOrNull() ?: return
        val reqClass = runCatching { Class.forName(HTTP_REQUEST, false, cl) }.getOrNull() ?: return
        val optClass = runCatching { Class.forName(HTTP_OPTIONS, false, cl) }.getOrNull() ?: return
        val respClass = runCatching { Class.forName(HTTP_RESPONSE, false, cl) }.getOrNull() ?: return

        val sendMethod = runCatching {
            connClass.getDeclaredMethod("send", reqClass, optClass)
        }.getOrNull() ?: return
        val getUrl = runCatching { reqClass.getMethod("getUrl") }.getOrNull() ?: return
        val getBody = runCatching { reqClass.getMethod("getBody") }.getOrNull() ?: return
        val onHeaders = runCatching { connClass.getMethod("onHeaders", respClass) }.getOrNull() ?: return
        val onBytes = runCatching {
            connClass.getMethod("onBytesAvailable", ByteArray::class.java, Int::class.javaPrimitiveType)
        }.getOrNull() ?: return
        val onComplete = runCatching {
            connClass.getMethod("onComplete", Boolean::class.javaPrimitiveType)
        }.getOrNull() ?: return
        val onError = runCatching {
            connClass.getMethod("onError", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return
        val respCtor = runCatching {
            respClass.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
        }.getOrNull() ?: return

        module.deoptimize(sendMethod)
        module.hook(sendMethod)
            .setId("client_spoof_token")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val url = runCatching { getUrl.invoke(chain.getArg(0)) as? String }.getOrNull()
                if (!enabled() || !isClientTokenRequest(url)) return@intercept chain.proceed()
                val conn = chain.getThisObject()
                val req = chain.getArg(0)
                if (conn == null || req == null) return@intercept chain.proceed()
                val body = runCatching { getBody.invoke(req) as? ByteArray }.getOrNull() ?: ByteArray(0)
                val payload = when (ClientTokenProto.requestType(body)) {
                    REQUEST_TYPE_CLIENT_DATA -> {
                        val deviceId = ClientTokenProto.extractDeviceId(body)
                        if (deviceId == null) {
                            failTokenRequest(conn, onError)
                            return@intercept null
                        }
                        ClientTokenProto.buildIOSRequest(
                            deviceId,
                            IOS_CLIENT_VERSION,
                            IOS_CLIENT_ID,
                            IOS_HW_MACHINE,
                            IOS_SYSTEM_VERSION
                        )
                    }
                    else -> body
                }
                val response = runCatching { fetchToken(payload) }.getOrNull()
                if (response == null) {
                    failTokenRequest(conn, onError)
                    return@intercept null
                }
                onHeaders.invoke(conn, respCtor.newInstance(200, url, RESPONSE_HEADERS))
                onBytes.invoke(conn, response, response.size)
                onComplete.invoke(conn, false)
                null
            }
    }

    private fun fetchToken(payload: ByteArray): ByteArray? {
        val connection = URL(CLIENT_TOKEN_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", PROTOBUF_CONTENT_TYPE)
            connection.setRequestProperty("Accept", PROTOBUF_CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", IOS_USER_AGENT)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode != 200) return null
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun failTokenRequest(conn: Any, onError: Method) {
        runCatching { onError.invoke(conn, TRANSPORT_ERROR) }
    }

    private fun hookApplicationScopeConfiguration(module: XposedModule, cl: ClassLoader) {
        val configClass = runCatching { Class.forName(APPLICATION_SCOPE_CONFIG, false, cl) }.getOrNull() ?: return
        val setClientId = runCatching {
            configClass.getDeclaredMethod("setClientId", String::class.java)
        }.getOrNull() ?: return
        val setUserAgent = runCatching {
            configClass.getDeclaredMethod("setDefaultHTTPUserAgent", String::class.java)
        }.getOrNull() ?: return

        module.deoptimize(setClientId)
        module.hook(setClientId)
            .setId("client_spoof_client_id")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!enabled()) return@intercept chain.proceed()
                chain.proceed(arrayOf<Any?>(IOS_CLIENT_ID))
            }

        module.deoptimize(setUserAgent)
        module.hook(setUserAgent)
            .setId("client_spoof_user_agent")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!enabled()) return@intercept chain.proceed()
                chain.proceed(arrayOf<Any?>(IOS_USER_AGENT))
            }
    }
}
