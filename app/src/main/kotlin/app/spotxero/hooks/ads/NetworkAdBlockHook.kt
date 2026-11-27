package app.spotxero.hooks.ads

import android.net.Uri
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.hooks.network.OkHttpBridge
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object NetworkAdBlockHook {

    private const val NATIVE_HTTP_CONNECTION = "com.spotify.core.http.NativeHttpConnection"
    private const val HTTP_REQUEST = "com.spotify.core.http.HttpRequest"
    private const val HTTP_OPTIONS = "com.spotify.core.http.HttpOptions"
    private const val HTTP_RESPONSE = "com.spotify.core.http.HttpResponse"

    private val BLOCKED_HOSTS = setOf(
        "ads.spotify.com",
        "ads-fa.spotify.com",
        "adeventtracker.spotify.com",
        "adstudio.spotify.com",
        "aet.spotify.com",
        "pixel.spotify.com",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adswizz.com",
        "adnxs.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "moatads.com",
        "adsrvr.org",
        "rubiconproject.com",
        "amazon-adsystem.com",
        "imasdk.googleapis.com"
    )

    private val PROTO_BLOCKED_PATHS = listOf(
        "/ads/",
        "/ad-logic/",
        "/hpto/",
        "/home-ads/",
        "/commercial-hop/",
        "/ad-formats/",
        "/embeddedad/",
        "/embedded-ad/",
        "/premium-marketing/spotify.premiummarketing.v1.premiummarketingupsellservice/",
        "/upsell/v1/",
        "/capping-api/",
        "/sponsoredplaylist/",
        "/adpreview/",
        "/showcase/",
        "/brandads/"
    )

    private const val APP_OPEN_AD_PATH = "/appopen/v1/getad"
    private const val HERMES_CAPPING_URI = "hm://common-capping/update"

    fun install(module: XposedModule) {
        hookNativeHttpConnection(module)
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
            return
        }
        hookOkHttpRealCall(module)
    }

    private val AUTH_BYPASS_KEYWORDS = listOf(
        "/login",
        "/auth",
        "/token",
        "/oauth",
        "/session",
        "login5",
        "parental-control"
    )

    private val asyncExecutor = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "SpotXero-HttpInterceptor").apply { isDaemon = true }
    }

    private fun blockedStatusCode(rawUrl: String?): Int? {
        if (rawUrl.isNullOrBlank()) return null
        val lower = rawUrl.lowercase()
        if (AUTH_BYPASS_KEYWORDS.any { lower.contains(it) }) {
            return null
        }
        if (lower.startsWith(HERMES_CAPPING_URI)) {
            return 200
        }
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (BLOCKED_HOSTS.any { domain -> host == domain || host.endsWith(".$domain") }) {
            return 404
        }
        val path = if (lower.startsWith("hm://")) {
            lower.removePrefix("hm:/")
        } else {
            uri.path?.lowercase() ?: return null
        }
        if (path.startsWith(APP_OPEN_AD_PATH) || path.contains(APP_OPEN_AD_PATH)) {
            return 404
        }
        if (PROTO_BLOCKED_PATHS.any { prefix ->
            path.startsWith(prefix) || path.contains(prefix) || host == prefix.trim('/')
        }) {
            return 200
        }
        return null
    }

    private fun hookNativeHttpConnection(module: XposedModule) {
        val cl = DexKitResolver.require<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE).declaringClass.classLoader
        val connClass = Class.forName(NATIVE_HTTP_CONNECTION, false, cl)
        val reqClass = Class.forName(HTTP_REQUEST, false, cl)
        val optClass = Class.forName(HTTP_OPTIONS, false, cl)
        val respClass = Class.forName(HTTP_RESPONSE, false, cl)

        val sendMethod = connClass.getDeclaredMethod("send", reqClass, optClass)
        val getUrlMethod = reqClass.getMethod("getUrl")
        val onHeadersMethod = connClass.getMethod("onHeaders", respClass)
        val onCompleteMethod = connClass.getMethod("onComplete", Boolean::class.javaPrimitiveType)
        val respCtor = respClass.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)

        module.deoptimize(sendMethod)
        module.hook(sendMethod)
            .setId("native_http_ad_blocker")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val req = chain.getArg(0)
                val conn = chain.getThisObject()
                val url = req?.let { runCatching { getUrlMethod.invoke(it) as? String }.getOrNull() }
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    val code = blockedStatusCode(url)
                    if (code != null && url != null && conn != null) {
                        val response = respCtor.newInstance(
                            code,
                            url,
                            "SPT-Protocol: http/1.1\r\nContent-Length: 0\r\n\r\n"
                        )
                        asyncExecutor.execute {
                            runCatching {
                                onHeadersMethod.invoke(conn, response)
                                onCompleteMethod.invoke(conn, true)
                            }
                        }
                        return@intercept null
                    }
                }
                chain.proceed()
            }
    }

    private fun hookOkHttpRealCall(module: XposedModule) {
        OkHttpBridge.registerRealCallInterceptor(module, "network_ad_block") { request, url, factory, _ ->
            if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                return@registerRealCallInterceptor false to null
            }
            val code = blockedStatusCode(url) ?: return@registerRealCallInterceptor false to null
            val response = factory.buildResponse(
                request = request,
                code = code,
                message = if (code == 200) "OK" else "Blocked"
            )
            (response != null) to response
        }
    }
}
