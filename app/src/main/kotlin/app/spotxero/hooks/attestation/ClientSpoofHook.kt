package app.spotxero.hooks.attestation

import android.net.Uri
import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ClientSpoofHook {

    private const val TAG = "SpotXero.ClientSpoof"

    private const val NATIVE_HTTP_CONNECTION = "com.spotify.core.http.NativeHttpConnection"
    private const val HTTP_REQUEST = "com.spotify.core.http.HttpRequest"
    private const val HTTP_OPTIONS = "com.spotify.core.http.HttpOptions"
    private const val HTTP_RESPONSE = "com.spotify.core.http.HttpResponse"
    private const val APPLICATION_SCOPE_CONFIG = "com.spotify.connectivity.ApplicationScopeConfiguration"

    private const val CLIENT_TOKEN_HOST = "clienttoken.spotify.com"
    private const val CLIENT_TOKEN_PATH = "/v1/clienttoken"
    private const val CLIENT_TOKEN_URL = "https://$CLIENT_TOKEN_HOST$CLIENT_TOKEN_PATH"

    // Web Player client ID and metadata - not subject to Play Integrity or Apple DeviceCheck
    private const val WEB_CLIENT_ID = "f6a40776580943a7bc5173125a1e8832"
    private const val WEB_CLIENT_VERSION = "1.2.10.35.g47464f56"
    private const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val WEB_ORIGIN = "https://open.spotify.com"
    private const val WEB_REFERER = "https://open.spotify.com/"
    private const val APP_PLATFORM_HEADER = "WebPlayer"

    private const val PROTOBUF_CONTENT_TYPE = "application/x-protobuf"
    private const val JSON_CONTENT_TYPE = "application/json"
    private const val RESPONSE_HEADERS = "SPT-Protocol: http/1.1\nContent-Type: $PROTOBUF_CONTENT_TYPE\n"

    private const val REQUEST_TYPE_CLIENT_DATA = 1
    private const val TRANSPORT_ERROR = 1

    private fun enabled() = PreferencesManager.isEnabled(PrefKeys.KEY_CLIENT_SPOOF, true)

    fun install(module: XposedModule) {
        val cl = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)?.declaringClass?.classLoader
        if (cl == null) {
            Log.w(TAG, "Cannot install ClientSpoofHook: target ClassLoader unresolved")
            return
        }
        Log.i(TAG, "Installing ClientSpoofHook (enabled=${enabled()})...")
        hookNativeHttpConnection(module, cl)
        hookPlayIntegrity(module)
    }

    private fun isClientTokenRequest(url: String?): Boolean {
        if (url == null) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.host == CLIENT_TOKEN_HOST && uri.path == CLIENT_TOKEN_PATH
    }

    private fun hookNativeHttpConnection(module: XposedModule, cl: ClassLoader) {
        val connClass = runCatching { Class.forName(NATIVE_HTTP_CONNECTION, false, cl) }.getOrNull()
        val reqClass = runCatching { Class.forName(HTTP_REQUEST, false, cl) }.getOrNull()
        val optClass = runCatching { Class.forName(HTTP_OPTIONS, false, cl) }.getOrNull()
        val respClass = runCatching { Class.forName(HTTP_RESPONSE, false, cl) }.getOrNull()

        if (connClass == null || reqClass == null || optClass == null || respClass == null) {
            Log.w(TAG, "Failed resolving NativeHttpConnection classes: connClass=$connClass, reqClass=$reqClass")
            return
        }

        val sendMethod = runCatching {
            connClass.getDeclaredMethod("send", reqClass, optClass)
        }.getOrNull()
        val getUrl = runCatching { reqClass.getMethod("getUrl") }.getOrNull()
        val getBody = runCatching { reqClass.getMethod("getBody") }.getOrNull()
        val onHeaders = runCatching { connClass.getMethod("onHeaders", respClass) }.getOrNull()
        val onBytes = runCatching {
            connClass.getMethod("onBytesAvailable", ByteArray::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
        val onComplete = runCatching {
            connClass.getMethod("onComplete", Boolean::class.javaPrimitiveType)
        }.getOrNull()
        val onError = runCatching {
            connClass.getMethod("onError", Int::class.javaPrimitiveType)
        }.getOrNull()
        val respCtor = runCatching {
            respClass.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
        }.getOrNull()

        if (sendMethod == null || getUrl == null || getBody == null || onHeaders == null ||
            onBytes == null || onComplete == null || onError == null || respCtor == null) {
            Log.w(TAG, "Failed resolving NativeHttpConnection methods: sendMethod=$sendMethod, getUrl=$getUrl, onHeaders=$onHeaders")
            return
        }

        Log.i(TAG, "Hooking NativeHttpConnection.send for client token interception")
        module.deoptimize(sendMethod)
        module.hook(sendMethod)
            .setId("client_spoof_token")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val url = runCatching { getUrl.invoke(chain.getArg(0)) as? String }.getOrNull()
                if (url != null && url.contains("clienttoken")) {
                    Log.i(TAG, "NativeHttpConnection.send detected clienttoken endpoint: $url (enabled=${enabled()})")
                }
                if (!enabled() || !isClientTokenRequest(url)) return@intercept chain.proceed()

                Log.i(TAG, ">>> INTERCEPTING NativeHttpConnection.send for: $url")
                val conn = chain.getThisObject()
                val req = chain.getArg(0)
                if (conn == null || req == null) return@intercept chain.proceed()

                val body = runCatching { getBody.invoke(req) as? ByteArray }.getOrNull() ?: ByteArray(0)
                val deviceId = (when (ClientTokenProto.requestType(body)) {
                    REQUEST_TYPE_CLIENT_DATA -> ClientTokenProto.extractDeviceId(body)
                    else -> null
                }) ?: UUID.randomUUID().toString()

                Log.i(TAG, "Intercepted deviceId: $deviceId, fetching Web API client token...")
                val response = runCatching { fetchWebToken(deviceId) }.getOrNull()
                if (response == null) {
                    Log.e(TAG, "fetchWebToken failed! Delivering onError callback to NativeHttpConnection")
                    failTokenRequest(conn, onError)
                    return@intercept null
                }

                Log.i(TAG, "<<< INJECTING Web API client token response (${response.size} bytes) directly into NativeHttpConnection")
                onHeaders.invoke(conn, respCtor.newInstance(200, url, RESPONSE_HEADERS))
                onBytes.invoke(conn, response, response.size)
                onComplete.invoke(conn, false)
                null
            }
    }

    private fun fetchWebToken(deviceId: String): ByteArray? {
        val payload = ClientTokenProto.buildWebJsonRequest(deviceId, WEB_CLIENT_ID, WEB_CLIENT_VERSION)
        Log.i(TAG, "fetchWebToken outbound: POST $CLIENT_TOKEN_URL (payload: ${String(payload, Charsets.UTF_8)})")
        val connection = URL(CLIENT_TOKEN_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", JSON_CONTENT_TYPE)
            connection.setRequestProperty("Accept", PROTOBUF_CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", WEB_USER_AGENT)
            connection.setRequestProperty("Origin", WEB_ORIGIN)
            connection.setRequestProperty("Referer", WEB_REFERER)
            connection.setRequestProperty("App-Platform", APP_PLATFORM_HEADER)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            val contentType = connection.contentType ?: ""
            Log.i(TAG, "fetchWebToken server response: HTTP $code (Content-Type: $contentType)")
            if (code != 200) return null

            val responseBytes = connection.inputStream.use { it.readBytes() }
            if (contentType.contains("protobuf") || (responseBytes.isNotEmpty() && responseBytes[0] == 0x08.toByte())) {
                Log.i(TAG, "fetchWebToken: successfully received binary protobuf token (${responseBytes.size} bytes)")
                responseBytes
            } else {
                val jsonStr = String(responseBytes, Charsets.UTF_8)
                Log.i(TAG, "fetchWebToken: received JSON response: $jsonStr")
                val json = runCatching { JSONObject(jsonStr) }.getOrNull()
                val granted = json?.optJSONObject("granted_token")
                val token = granted?.optString("token")
                if (!token.isNullOrEmpty()) {
                    val expires = granted.optInt("expires_after_seconds", 1216800)
                    val refresh = granted.optInt("refresh_after_seconds", 1209600)
                    Log.i(TAG, "fetchWebToken: parsed granted_token (expires: ${expires}s, refresh: ${refresh}s)")
                    ClientTokenProto.buildGrantedTokenResponse(token, expires, refresh)
                } else {
                    responseBytes
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetchWebToken network error: ${t.message}", t)
            null
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

        Log.i(TAG, "Hooking ApplicationScopeConfiguration.setClientId and setDefaultHTTPUserAgent")
        module.deoptimize(setClientId)
        module.hook(setClientId)
            .setId("client_spoof_client_id")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!enabled()) return@intercept chain.proceed()
                Log.i(TAG, "ApplicationScopeConfiguration.setClientId: intercepted arg=${chain.getArg(0)} -> replacing with $WEB_CLIENT_ID")
                chain.proceed(arrayOf<Any?>(WEB_CLIENT_ID))
            }

        module.deoptimize(setUserAgent)
        module.hook(setUserAgent)
            .setId("client_spoof_user_agent")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!enabled()) return@intercept chain.proceed()
                Log.i(TAG, "ApplicationScopeConfiguration.setDefaultHTTPUserAgent: intercepted arg=${chain.getArg(0)} -> replacing with $WEB_USER_AGENT")
                chain.proceed(arrayOf<Any?>(WEB_USER_AGENT))
            }
    }

    private fun hookPlayIntegrity(module: XposedModule) {
        val piInit = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAY_INTEGRITY_INIT)
        if (piInit != null) {
            Log.i(TAG, "Hooking Play Integrity init: $piInit")
            module.deoptimize(piInit)
            module.hook(piInit)
                .setId("client_spoof_play_integrity_init")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    Log.i(TAG, "Neutralized Play Integrity init call: ${piInit.name}")
                    null
                }
        }

        val pitSink = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAY_INTEGRITY_RESULT_SINK)
        if (pitSink != null) {
            Log.i(TAG, "Hooking Play Integrity result sink: $pitSink")
            module.deoptimize(pitSink)
            module.hook(pitSink)
                .setId("client_spoof_pit_sink")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled()) return@intercept chain.proceed()
                    Log.i(TAG, "Neutralized PitResult telemetry event: ${pitSink.name}")
                    null
                }
        }
    }
}
