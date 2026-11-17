package app.spotxero.hooks.lyrics

import android.media.MediaMetadata
import android.media.session.MediaSession
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.net.URI

object LyricsHook {

    fun install(module: XposedModule) {
        hookMediaSession(module)
        hookUncappedLyrics(module)
        hookThirdPartyLyrics(module)
    }

    private fun hookMediaSession(module: XposedModule) {
        val method = runCatching {
            MediaSession::class.java.getDeclaredMethod(
                "setMetadata",
                MediaMetadata::class.java
            )
        }.getOrNull() ?: return

        module.deoptimize(method)
        module.hook(method)
            .setId("lyrics_media_session_metadata")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val metadata = chain.getArg(0) as? MediaMetadata
                if (metadata != null) {
                    LyricsManager.updateCurrentTrack(metadata)
                }
                chain.proceed()
            }
    }

    private fun hookUncappedLyrics(module: XposedModule) {
        val capStatusGetter = DexKitResolver.get<Method>(DexKitResolver.KEY_LYRICS_CAP_STATUS_GETTER) ?: return
        val enumType = capStatusGetter.returnType
        val uncappedConstant = enumType.enumConstants?.firstOrNull {
            (it as? Enum<*>)?.name == "UNCAPPED"
        } ?: return

        module.deoptimize(capStatusGetter)
        module.hook(capStatusGetter)
            .setId("lyrics_uncapped_status")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (PreferencesManager.isEnabled(PrefKeys.KEY_UNCAPPED_LYRICS, true)) {
                    return@intercept uncappedConstant
                }
                chain.proceed()
            }
    }

    private fun hookThirdPartyLyrics(module: XposedModule) {
        val realCallChain = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_REAL_CALL_CHAIN) ?: return
        val reqToString = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_REQUEST_TO_STRING) ?: return
        val respBuild = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_RESPONSE_BUILDER_BUILD) ?: return

        val requestClass = reqToString.declaringClass
        val responseClass = respBuild.returnType
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
        val bReqField = builderClass.declaredFields.firstOrNull { it.type == requestClass }?.apply { isAccessible = true } ?: return
        val bCodeField = builderClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }?.apply { isAccessible = true } ?: return
        val bMsgField = builderClass.declaredFields.firstOrNull { it.type == String::class.java }?.apply { isAccessible = true } ?: return
        val bProtoField = builderClass.declaredFields.firstOrNull {
            it.type.isEnum && it.type.enumConstants?.any { c -> c.toString() == "http/1.1" } == true
        }?.apply { isAccessible = true } ?: return
        val http11 = bProtoField.type.enumConstants?.firstOrNull { it.toString() == "http/1.1" } ?: return

        val responseBodyClass = runCatching { Class.forName("okhttp3.ResponseBody", false, builderClass.classLoader) }.getOrNull()
            ?: builderClass.declaredFields.firstOrNull { f ->
                java.io.Closeable::class.java.isAssignableFrom(f.type) &&
                    java.lang.reflect.Modifier.isAbstract(f.type.modifiers) &&
                    f.type != requestClass &&
                    f.type != responseClass
            }?.type
            ?: responseClass.declaredMethods.firstOrNull { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 &&
                    java.io.Closeable::class.java.isAssignableFrom(m.returnType) &&
                    java.lang.reflect.Modifier.isAbstract(m.returnType.modifiers) &&
                    m.returnType != responseClass
            }?.returnType ?: return

        val bBodyField = builderClass.declaredFields.firstOrNull { it.type == responseBodyClass }?.apply { isAccessible = true } ?: return

        val companionObj = runCatching {
            responseBodyClass.declaredFields.firstOrNull {
                it.name == "Companion" || it.type.name.endsWith("\$Companion")
            }?.apply { isAccessible = true }?.get(null)
        }.getOrNull()

        val createBodyMethod = runCatching {
            if (companionObj != null) {
                companionObj.javaClass.declaredMethods.firstOrNull { m ->
                    m.parameterCount in 1..2 &&
                        m.parameterTypes.any { it == String::class.java } &&
                        responseBodyClass.isAssignableFrom(m.returnType)
                }?.apply { isAccessible = true }
            } else null
        }.getOrNull() ?: responseBodyClass.declaredMethods.firstOrNull { m ->
            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterCount in 1..2 &&
                m.parameterTypes.any { it == String::class.java } &&
                responseBodyClass.isAssignableFrom(m.returnType)
        }?.apply { isAccessible = true }

        val mediaTypeClass = runCatching { Class.forName("okhttp3.MediaType", false, builderClass.classLoader) }.getOrNull()
            ?: responseBodyClass.declaredMethods.firstOrNull { m ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 &&
                    !m.returnType.isPrimitive &&
                    m.returnType != String::class.java &&
                    !java.io.Closeable::class.java.isAssignableFrom(m.returnType)
            }?.returnType

        val jsonMediaType = runCatching {
            val mtClass = mediaTypeClass ?: return@runCatching null
            val staticParse = mtClass.declaredMethods.firstOrNull { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.returnType == mtClass
            }
            if (staticParse != null) {
                staticParse.invoke(null, "application/json; charset=utf-8")
            } else {
                val comp = mtClass.declaredFields.firstOrNull {
                    it.name == "Companion" || it.type.name.endsWith("\$Companion")
                }?.apply { isAccessible = true }?.get(null)
                val compParse = comp?.javaClass?.declaredMethods?.firstOrNull { m ->
                    m.parameterCount == 1 &&
                        m.parameterTypes[0] == String::class.java &&
                        m.returnType == mtClass
                }
                compParse?.invoke(comp, "application/json; charset=utf-8")
            }
        }.getOrNull()

        fun createBody(json: String): Any? {
            val method = createBodyMethod ?: return null
            val target = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else companionObj
            return runCatching {
                if (method.parameterCount == 1) {
                    method.invoke(target, json)
                } else {
                    if (method.parameterTypes[0] == String::class.java) {
                        method.invoke(target, json, jsonMediaType)
                    } else {
                        method.invoke(target, jsonMediaType, json)
                    }
                }
            }.getOrNull()
        }

        fun buildResponse(request: Any, body: Any): Any? {
            return runCatching {
                val builder = builderCtor.newInstance()
                bReqField.set(builder, request)
                bProtoField.set(builder, http11)
                bCodeField.setInt(builder, 200)
                bMsgField.set(builder, "OK")
                bBodyField.set(builder, body)

                val headerMethod = builder.javaClass.declaredMethods.firstOrNull { m ->
                    (m.name == "header" || m.name == "addHeader") &&
                        m.parameterCount == 2 &&
                        m.parameterTypes[0] == String::class.java &&
                        m.parameterTypes[1] == String::class.java
                }
                headerMethod?.invoke(builder, "content-type", "application/json; charset=utf-8")

                respBuild.invoke(builder)
            }.getOrNull()
        }

        respBuild.isAccessible = true
        module.deoptimize(realCallChain)
        module.hook(realCallChain)
            .setId("lyrics_third_party_interceptor")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val callObj = chain.getThisObject() ?: return@intercept chain.proceed()
                val request = runCatching { callRequestField.get(callObj) }.getOrNull() ?: return@intercept chain.proceed()
                val url = runCatching { urlField.get(request)?.toString() }.getOrNull().orEmpty()

                if (!url.contains("color-lyrics/")) {
                    return@intercept chain.proceed()
                }

                val externalEnabled = PreferencesManager.isEnabled(PrefKeys.KEY_EXTERNAL_LYRICS, false)
                val force = PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_EXTERNAL_LYRICS, false)

                val trackId = if (url.contains("/track/")) {
                    url.substringAfter("/track/").substringBefore("?").substringBefore("/")
                } else null

                if (externalEnabled && force) {
                    val lyricsJson = LyricsManager.getLyricsJson(trackId)
                    if (lyricsJson != null) {
                        val body = createBody(lyricsJson)
                        if (body != null) {
                            val response = buildResponse(request, body)
                            if (response != null) return@intercept response
                        }
                    }
                }

                val originalResponse = chain.proceed()
                val statusCode = runCatching {
                    val codeMethod = originalResponse?.javaClass?.declaredMethods?.firstOrNull {
                        !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                            it.parameterCount == 0 &&
                            it.returnType == Int::class.javaPrimitiveType
                    }
                    if (codeMethod != null) {
                        codeMethod.invoke(originalResponse) as? Int
                    } else {
                        val codeField = originalResponse?.javaClass?.declaredFields?.firstOrNull {
                            it.type == Int::class.javaPrimitiveType
                        }?.apply { isAccessible = true }
                        codeField?.getInt(originalResponse)
                    }
                }.getOrNull() ?: 200

                if (externalEnabled && statusCode != 200) {
                    val lyricsJson = LyricsManager.getLyricsJson(trackId)
                    if (lyricsJson != null) {
                        val body = createBody(lyricsJson)
                        if (body != null) {
                            val response = buildResponse(request, body)
                            if (response != null) {
                                runCatching { (originalResponse as? java.io.Closeable)?.close() }
                                return@intercept response
                            }
                        }
                    }
                }

                originalResponse
            }
    }
}
