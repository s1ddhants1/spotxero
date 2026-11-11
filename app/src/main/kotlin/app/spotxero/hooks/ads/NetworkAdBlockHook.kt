package app.spotxero.hooks.ads

import android.net.Uri
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URI

object NetworkAdBlockHook {

    private const val NATIVE_HTTP_CONNECTION = "com.spotify.core.http.NativeHttpConnection"
    private const val HTTP_REQUEST = "com.spotify.core.http.HttpRequest"
    private const val HTTP_OPTIONS = "com.spotify.core.http.HttpOptions"
    private const val HTTP_RESPONSE = "com.spotify.core.http.HttpResponse"

    private val BLOCKED_HOSTS = setOf(
        "ads.spotify.com",
        "ads-fa.spotify.com",
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
        "/pendragon/com.spotify.pendragon.v1.clientmessageservice/",
        "/premium-marketing/spotify.premiummarketing.v1.premiummarketingupsellservice/",
        "/upsell/v1/",
        "/capping-api/",
        "/sponsoredplaylist/",
        "/adpreview/"
    )

    private const val APP_OPEN_AD_PATH = "/appopen/v1/getad"
    private const val HERMES_CAPPING_URI = "hm://common-capping/update"

    fun install(module: XposedModule) {
        hookNativeHttpConnection(module)
        hookOkHttpRealCall(module)
    }

    private fun blockedStatusCode(rawUrl: String?): Int? {
        if (rawUrl.isNullOrBlank()) return null
        val lower = rawUrl.lowercase()
        if (lower.startsWith(HERMES_CAPPING_URI)) {
            return 200
        }
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (BLOCKED_HOSTS.any { domain -> host == domain || host.endsWith(".$domain") }) {
            return 404
        }
        val path = uri.path?.lowercase() ?: return null
        if (path.startsWith(APP_OPEN_AD_PATH) || path.contains(APP_OPEN_AD_PATH)) {
            return 404
        }
        if (PROTO_BLOCKED_PATHS.any { prefix -> path.startsWith(prefix) || path.contains(prefix) }) {
            return 200
        }
        return null
    }

    private fun hookNativeHttpConnection(module: XposedModule) {
        val cl = DexKitResolver.get<Method>(DexKitResolver.KEY_CLIENT_BASE_CALL_SINGLE)?.declaringClass?.classLoader ?: return
        val connClass = runCatching { Class.forName(NATIVE_HTTP_CONNECTION, false, cl) }.getOrNull() ?: return
        val reqClass = runCatching { Class.forName(HTTP_REQUEST, false, cl) }.getOrNull() ?: return
        val optClass = runCatching { Class.forName(HTTP_OPTIONS, false, cl) }.getOrNull() ?: return
        val respClass = runCatching { Class.forName(HTTP_RESPONSE, false, cl) }.getOrNull() ?: return

        val sendMethod = runCatching {
            connClass.getDeclaredMethod("send", reqClass, optClass)
        }.getOrNull() ?: return
        val getUrlMethod = runCatching { reqClass.getMethod("getUrl") }.getOrNull() ?: return
        val onHeadersMethod = runCatching { connClass.getMethod("onHeaders", respClass) }.getOrNull() ?: return
        val onCompleteMethod = runCatching { connClass.getMethod("onComplete", Boolean::class.javaPrimitiveType) }.getOrNull() ?: return
        val respCtor = runCatching {
            respClass.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
        }.getOrNull() ?: return

        module.deoptimize(sendMethod)
        module.hook(sendMethod)
            .setId("native_http_ad_blocker")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    val req = chain.getArg(0)
                    val conn = chain.getThisObject()
                    if (req != null && conn != null) {
                        val url = runCatching { getUrlMethod.invoke(req) as? String }.getOrNull()
                        val code = blockedStatusCode(url)
                        if (code != null && url != null) {
                            val response = respCtor.newInstance(code, url, "SPT-Protocol: http/1.1\n")
                            onHeadersMethod.invoke(conn, response)
                            onCompleteMethod.invoke(conn, false)
                            return@intercept null
                        }
                    }
                }
                chain.proceed()
            }
    }

    private fun hookOkHttpRealCall(module: XposedModule) {
        val realCallChain = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_REAL_CALL_CHAIN) ?: return
        val reqToString = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_REQUEST_TO_STRING) ?: return
        val respBuild = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_RESPONSE_BUILDER_BUILD) ?: return

        val requestClass = reqToString.declaringClass
        val callRequestField = realCallChain.declaringClass.declaredFields.firstOrNull {
            it.type == requestClass
        }?.apply { isAccessible = true } ?: return
        val urlField = requestClass.declaredFields.firstOrNull { f ->
            !f.type.isPrimitive && f.type.declaredMethods.any { m -> m.returnType == URI::class.java }
        }?.apply { isAccessible = true } ?: return

        val builderClass = respBuild.declaringClass
        val builderCtor = runCatching {
            builderClass.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull() ?: return
        val bReqField: Field = builderClass.declaredFields.firstOrNull { it.type == requestClass }?.apply { isAccessible = true } ?: return
        val bCodeField: Field = builderClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }?.apply { isAccessible = true } ?: return
        val bMsgField: Field = builderClass.declaredFields.firstOrNull { it.type == String::class.java }?.apply { isAccessible = true } ?: return
        val bProtoField: Field = builderClass.declaredFields.firstOrNull {
            it.type.isEnum && it.type.enumConstants?.any { c -> c.toString() == "http/1.1" } == true
        }?.apply { isAccessible = true } ?: return
        val http11 = bProtoField.type.enumConstants?.firstOrNull { it.toString() == "http/1.1" } ?: return

        respBuild.isAccessible = true
        module.deoptimize(realCallChain)
        module.hook(realCallChain)
            .setId("okhttp_ad_blocker")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                    val callObj = chain.getThisObject()
                    val request = callObj?.let { runCatching { callRequestField.get(it) }.getOrNull() }
                    val url = request?.let { runCatching { urlField.get(it)?.toString() }.getOrNull() }
                    val code = blockedStatusCode(url)
                    if (code != null && request != null) {
                        val builder = builderCtor.newInstance()
                        bReqField.set(builder, request)
                        bProtoField.set(builder, http11)
                        bCodeField.setInt(builder, code)
                        bMsgField.set(builder, if (code == 200) "OK" else "Blocked")
                        return@intercept respBuild.invoke(builder)
                    }
                }
                chain.proceed()
            }
    }
}
