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
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_UNCAPPED_LYRICS, true)) {
            return
        }

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
                !f.type.isPrimitive && f.type != requestClass && f.type != String::class.java && f.type != bProtoField.type
            }?.type ?: return
        val bBodyField = builderClass.declaredFields.firstOrNull { it.type == responseBodyClass }?.apply { isAccessible = true } ?: return

        val mediaTypeClass = runCatching { Class.forName("okhttp3.MediaType", false, builderClass.classLoader) }.getOrNull()
        val mediaTypeParse = mediaTypeClass?.declaredMethods?.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && it.returnType == mediaTypeClass
        }
        val jsonMediaType = mediaTypeParse?.invoke(null, "application/json; charset=utf-8")

        val createBodyMethod = responseBodyClass.declaredMethods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 2 &&
                (it.parameterTypes[0] == String::class.java || it.parameterTypes[1] == String::class.java) &&
                responseBodyClass.isAssignableFrom(it.returnType)
        }

        respBuild.isAccessible = true
        module.deoptimize(realCallChain)
        module.hook(realCallChain)
            .setId("lyrics_third_party_interceptor")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (!PreferencesManager.isEnabled(PrefKeys.KEY_EXTERNAL_LYRICS, false)) {
                    return@intercept chain.proceed()
                }

                val callObj = chain.getThisObject() ?: return@intercept chain.proceed()
                val request = runCatching { callRequestField.get(callObj) }.getOrNull() ?: return@intercept chain.proceed()
                val url = runCatching { urlField.get(request)?.toString() }.getOrNull().orEmpty()

                if (!url.contains("color-lyrics/v2/")) {
                    return@intercept chain.proceed()
                }

                val force = PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_EXTERNAL_LYRICS, false)
                if (force) {
                    val lyricsJson = LyricsManager.getLyricsJson()
                    if (lyricsJson != null) {
                        val body = if (createBodyMethod != null) {
                            if (createBodyMethod.parameterTypes[0] == String::class.java) {
                                createBodyMethod.invoke(null, lyricsJson, jsonMediaType)
                            } else {
                                createBodyMethod.invoke(null, jsonMediaType, lyricsJson)
                            }
                        } else null

                        if (body != null) {
                            val builder = builderCtor.newInstance()
                            bReqField.set(builder, request)
                            bProtoField.set(builder, http11)
                            bCodeField.setInt(builder, 200)
                            bMsgField.set(builder, "OK")
                            bBodyField.set(builder, body)
                            return@intercept respBuild.invoke(builder)
                        }
                    }
                }

                val originalResponse = chain.proceed()
                val statusCode = runCatching {
                    val codeField = originalResponse?.javaClass?.declaredFields?.firstOrNull {
                        it.type == Int::class.javaPrimitiveType
                    }?.apply { isAccessible = true }
                    codeField?.getInt(originalResponse)
                }.getOrNull() ?: 200

                if (statusCode != 200) {
                    val lyricsJson = LyricsManager.getLyricsJson()
                    if (lyricsJson != null) {
                        val body = if (createBodyMethod != null) {
                            if (createBodyMethod.parameterTypes[0] == String::class.java) {
                                createBodyMethod.invoke(null, lyricsJson, jsonMediaType)
                            } else {
                                createBodyMethod.invoke(null, jsonMediaType, lyricsJson)
                            }
                        } else null

                        if (body != null) {
                            val builder = builderCtor.newInstance()
                            bReqField.set(builder, request)
                            bProtoField.set(builder, http11)
                            bCodeField.setInt(builder, 200)
                            bMsgField.set(builder, "OK")
                            bBodyField.set(builder, body)
                            return@intercept respBuild.invoke(builder)
                        }
                    }
                }

                originalResponse
            }
    }
}
