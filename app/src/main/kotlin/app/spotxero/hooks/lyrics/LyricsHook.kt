package app.spotxero.hooks.lyrics

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.net.URI
import java.util.Collections
import java.util.LinkedHashMap

object LyricsHook {

    private const val TAG = "SpotXero.LyricsHook"
    private const val MAX_STOCK_CACHE = 30

    private val stockTranslatedCache: MutableMap<String, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(MAX_STOCK_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
                return size > MAX_STOCK_CACHE
            }
        }
    )

    private fun getStockCacheKey(trackId: String): String {
        return "$trackId:${PreferencesManager.aiTranslationTargetLang}:${PreferencesManager.aiTranslationTranslateMode}:${PreferencesManager.isAiTranslationDualLine}:${PreferencesManager.aiTranslationProvider}:${PreferencesManager.aiTranslationModel}".lowercase()
    }

    fun install(module: XposedModule) {
        Log.i(TAG, "Installing lyrics hooks")
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
        Log.i(TAG, "hookMediaSession installed")
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
        Log.i(TAG, "hookUncappedLyrics installed")
    }

    private fun hookThirdPartyLyrics(module: XposedModule) {
        val realCallChain = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_REAL_CALL_CHAIN)
        val reqToString = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_REQUEST_TO_STRING)
        val respBuild = DexKitResolver.get<Method>(DexKitResolver.KEY_OKHTTP_RESPONSE_BUILDER_BUILD)

        Log.i(TAG, "hookThirdPartyLyrics: realCallChain=$realCallChain, reqToString=$reqToString, respBuild=$respBuild")
        if (realCallChain == null || reqToString == null || respBuild == null) return

        val requestClass = reqToString.declaringClass
        val responseClass = respBuild.returnType
        val callRequestField = realCallChain.declaringClass.declaredFields.firstOrNull {
            it.type == requestClass
        }?.apply { isAccessible = true }
        val urlField = requestClass.declaredFields.firstOrNull { f ->
            !f.type.isPrimitive && f.type.declaredMethods.any { m -> m.returnType == URI::class.java }
        }?.apply { isAccessible = true }

        Log.i(TAG, "hookThirdPartyLyrics: callRequestField=$callRequestField, urlField=$urlField")
        if (callRequestField == null || urlField == null) return

        val builderClass = respBuild.declaringClass
        val builderCtor = runCatching {
            builderClass.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull()
        val bReqField = builderClass.declaredFields.firstOrNull { it.type == requestClass }?.apply { isAccessible = true }
        val bCodeField = builderClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }?.apply { isAccessible = true }
        val bMsgField = builderClass.declaredFields.firstOrNull { it.type == String::class.java }?.apply { isAccessible = true }
        val bProtoField = builderClass.declaredFields.firstOrNull {
            it.type.isEnum && it.type.enumConstants?.any { c -> c.toString() == "http/1.1" } == true
        }?.apply { isAccessible = true }
        val http11 = bProtoField?.type?.enumConstants?.firstOrNull { it.toString() == "http/1.1" }

        Log.i(TAG, "hookThirdPartyLyrics: builderCtor=$builderCtor, bReqField=$bReqField, http11=$http11")
        if (builderCtor == null || bReqField == null || bCodeField == null || bMsgField == null || bProtoField == null || http11 == null) return

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
            }?.returnType

        val bBodyField = builderClass.declaredFields.firstOrNull { it.type == responseBodyClass }?.apply { isAccessible = true }
        Log.i(TAG, "hookThirdPartyLyrics: responseBodyClass=$responseBodyClass, bBodyField=$bBodyField")
        if (responseBodyClass == null || bBodyField == null) return

        val respBodyMethod = responseClass.declaredMethods.firstOrNull {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                responseBodyClass.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true }

        val respBodyField = responseClass.declaredFields.firstOrNull {
            responseBodyClass.isAssignableFrom(it.type)
        }?.apply { isAccessible = true }

        val respBytesMethod = runCatching {
            responseBodyClass.getMethod("bytes")
        }.getOrNull() ?: responseBodyClass.declaredMethods.firstOrNull {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == ByteArray::class.java
        }?.apply { isAccessible = true }

        val respByteStreamMethod = runCatching {
            responseBodyClass.getMethod("byteStream")
        }.getOrNull() ?: responseBodyClass.declaredMethods.firstOrNull {
            !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                java.io.InputStream::class.java.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true }

        fun extractBodyBytes(responseObj: Any?): ByteArray? {
            if (responseObj == null) return null
            val bodyObj = runCatching {
                respBodyMethod?.invoke(responseObj) ?: respBodyField?.get(responseObj)
            }.getOrNull() ?: return null

            return runCatching {
                if (respBytesMethod != null) {
                    respBytesMethod.invoke(bodyObj) as? ByteArray
                } else if (respByteStreamMethod != null) {
                    (respByteStreamMethod.invoke(bodyObj) as? java.io.InputStream)?.use { it.readBytes() }
                } else null
            }.getOrNull()
        }

        try {
            val concreteBodyClass = responseBodyClass.declaredFields.firstOrNull {
                it.type != responseBodyClass && responseBodyClass.isAssignableFrom(it.type)
            }?.type ?: runCatching { Class.forName("p.hi31", false, builderClass.classLoader) }.getOrNull()

            Log.i(TAG, "concreteBodyClass: $concreteBodyClass")

            val concreteBodyCtor = concreteBodyClass?.declaredConstructors?.firstOrNull()?.apply { isAccessible = true }
            val mediaTypeClass = concreteBodyCtor?.parameterTypes?.getOrNull(0)
            val bufferClass = concreteBodyCtor?.parameterTypes?.getOrNull(2)

            Log.i(TAG, "mediaTypeClass: $mediaTypeClass, bufferClass: $bufferClass")

            val mediaTypeCtor = mediaTypeClass?.declaredConstructors?.firstOrNull { it.parameterCount == 4 }?.apply { isAccessible = true }
            val bufferWrite = bufferClass?.declaredMethods?.firstOrNull {
                it.name == "write" && it.parameterCount == 1 && it.parameterTypes[0] == ByteArray::class.java
            }?.apply { isAccessible = true }

            val bHeadersField = builderClass.declaredFields.firstOrNull { f ->
                !f.type.isPrimitive && !f.type.name.startsWith("java.") &&
                    f.type != requestClass && f.type != responseClass && f.type != responseBodyClass &&
                    runCatching {
                        f.type.declaredMethods.any { m ->
                            m.parameterCount == 2 && m.parameterTypes[0] == String::class.java && m.parameterTypes[1] == String::class.java
                        }
                    }.getOrDefault(false)
            }?.apply { isAccessible = true }

            val headerAddMethod = runCatching {
                bHeadersField?.type?.declaredMethods?.firstOrNull {
                    it.parameterCount == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java
                }?.apply { isAccessible = true }
            }.getOrNull()

            fun createBuffer(): Any? {
                val fromCtor = runCatching {
                    bufferClass?.constructors?.firstOrNull()?.newInstance()
                        ?: bufferClass?.declaredConstructors?.firstOrNull()?.apply { isAccessible = true }?.newInstance()
                }.getOrNull()
                if (fromCtor != null) return fromCtor

                val fromNew = runCatching { bufferClass?.newInstance() }.getOrNull()
                if (fromNew != null) return fromNew

                return runCatching {
                    val emptyBody = responseBodyClass.declaredFields.firstOrNull { it.type == concreteBodyClass }?.apply { isAccessible = true }?.get(null)
                    val bufField = emptyBody?.javaClass?.declaredFields?.firstOrNull { it.type == bufferClass }?.apply { isAccessible = true }
                    val template = bufField?.get(emptyBody) as? Cloneable
                    template?.javaClass?.getMethod("clone")?.invoke(template)
                }.getOrNull()
            }

            Log.i(TAG, "hookThirdPartyLyrics: concreteBodyCtor=$concreteBodyCtor, bufferWrite=$bufferWrite, bHeadersField=${bHeadersField?.name}, headerAddMethod=${headerAddMethod?.name}")

            fun createBody(bytes: ByteArray, contentType: String): Any? {
                if (concreteBodyCtor != null && bufferWrite != null) {
                    val buffer = createBuffer()
                    if (buffer == null) {
                        Log.w(TAG, "createBuffer returned null")
                        return null
                    }
                    return runCatching {
                        val mediaType = runCatching {
                            if (contentType.contains("protobuf")) {
                                mediaTypeCtor?.newInstance(
                                    "application/protobuf",
                                    "application",
                                    "protobuf",
                                    emptyArray<String>()
                                )
                            } else {
                                mediaTypeCtor?.newInstance(
                                    "application/json; charset=utf-8",
                                    "application",
                                    "json",
                                    arrayOf("charset", "utf-8")
                                )
                            }
                        }.getOrNull()
                        bufferWrite.invoke(buffer, bytes)
                        concreteBodyCtor.newInstance(mediaType, bytes.size.toLong(), buffer)
                    }.onFailure { Log.w(TAG, "concrete createBody failed: ${it.message}", it) }.getOrNull()
                }
                return null
            }

            fun buildResponse(request: Any, body: Any, contentType: String): Any? {
                return runCatching {
                    val builder = builderCtor.newInstance()
                    bReqField.set(builder, request)
                    bProtoField.set(builder, http11)
                    bCodeField.setInt(builder, 200)
                    bMsgField.set(builder, "OK")
                    bBodyField.set(builder, body)

                    if (bHeadersField != null && headerAddMethod != null) {
                        val headersObj = bHeadersField.get(builder)
                        if (headersObj != null) {
                            headerAddMethod.invoke(headersObj, "content-type", contentType)
                        }
                    }

                    respBuild.invoke(builder)
                }.onFailure { Log.w(TAG, "buildResponse failed: ${it.message}", it) }.getOrNull()
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

                    if (url.contains("color-lyrics/") || url.contains("lyrics")) {
                        Log.i(TAG, "Intercepted candidate lyrics request: $url")
                    }

                    if (!url.contains("color-lyrics/")) {
                        return@intercept chain.proceed()
                    }

                    val externalEnabled = PreferencesManager.isEnabled(PrefKeys.KEY_EXTERNAL_LYRICS, false)
                    val force = PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_EXTERNAL_LYRICS, false)
                    val aiTranslationEnabled = PreferencesManager.isAiLyricsTranslationEnabled

                    val trackId = if (url.contains("/track/")) {
                        url.substringAfter("/track/").substringBefore("?").substringBefore("/")
                    } else null

                    Log.i(TAG, "Color-lyrics requested: trackId=$trackId, externalEnabled=$externalEnabled, force=$force, aiTranslationEnabled=$aiTranslationEnabled")

                    // 1. Check cached translated stock response
                    if (aiTranslationEnabled && trackId != null) {
                        val stockCacheKey = getStockCacheKey(trackId)
                        val cachedStock = PersistentLyricsCache.getProtobuf(stockCacheKey) ?: stockTranslatedCache[stockCacheKey]
                        if (cachedStock != null) {
                            stockTranslatedCache[stockCacheKey] = cachedStock
                            val body = createBody(cachedStock, "application/protobuf")
                            if (body != null) {
                                val response = buildResponse(request, body, "application/protobuf")
                                if (response != null) {
                                    Log.i(TAG, "===> INJECTED CACHED TRANSLATED STOCK LYRICS FOR $trackId <===")
                                    return@intercept response
                                }
                            }
                        }
                    }

                    // 2. Try third-party lyrics if external is enabled and force or ai translation is active
                    if (externalEnabled && (force || aiTranslationEnabled)) {
                        val protoBytes = LyricsManager.getLyricsProto(trackId)
                        Log.i(TAG, "LyricsManager proto returned: ${if (protoBytes != null) "${protoBytes.size} bytes" else "null"}")
                        if (protoBytes != null) {
                            val body = createBody(protoBytes, "application/protobuf")
                            Log.i(TAG, "createBody returned: $body")
                            if (body != null) {
                                val response = buildResponse(request, body, "application/protobuf")
                                Log.i(TAG, "buildResponse returned: $response")
                                if (response != null) {
                                    Log.i(TAG, "===> INJECTED THIRD-PARTY PROTOBUF LYRICS FOR $trackId <===")
                                    return@intercept response
                                }
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

                    Log.d(TAG, "Original color-lyrics response code: $statusCode for $trackId")

                    // 3. If Spotify returned 200 and AI translation is enabled, translate stock lyrics!
                    if (statusCode == 200 && aiTranslationEnabled && trackId != null) {
                        val rawBytes = extractBodyBytes(originalResponse)
                        if (rawBytes != null && rawBytes.isNotEmpty()) {
                            val parsed = runCatching { LyricsProtobuf.parseColorLyricsResponse(rawBytes) }.getOrNull()
                            if (parsed != null && parsed.lyrics.lines.isNotEmpty()) {
                                Log.i(TAG, "Stock Spotify lyrics parsed: ${parsed.lyrics.lines.size} lines from ${parsed.providerDisplayName}. Translating...")
                                val translatedLyrics = runCatching {
                                    AiLyricsTranslator.translateLyrics(parsed.lyrics, trackKey = trackId)
                                }.getOrDefault(parsed.lyrics)

                                val hasTranslated = translatedLyrics !== parsed.lyrics &&
                                    translatedLyrics.lines.isNotEmpty() &&
                                    translatedLyrics.lines != parsed.lyrics.lines

                                if (hasTranslated) {
                                    val translatedProtoBytes = LyricsProtobuf.buildColorLyricsResponse(
                                        lyrics = translatedLyrics,
                                        providerName = parsed.provider,
                                        providerDisplayName = "${parsed.providerDisplayName} (Translated)",
                                        customColorData = parsed.colorDataBytes,
                                        hasVocalRemoval = parsed.hasVocalRemoval
                                    )

                                    val stockCacheKey = getStockCacheKey(trackId)
                                    stockTranslatedCache[stockCacheKey] = translatedProtoBytes
                                    PersistentLyricsCache.putProtobuf(stockCacheKey, translatedProtoBytes)

                                    val newBody = createBody(translatedProtoBytes, "application/protobuf")
                                    if (newBody != null) {
                                        val replacement = buildResponse(request, newBody, "application/protobuf")
                                        if (replacement != null) {
                                            runCatching { (originalResponse as? java.io.Closeable)?.close() }
                                            Log.i(TAG, "===> INJECTED TRANSLATED STOCK SPOTIFY PROTOBUF LYRICS FOR $trackId <===")
                                            return@intercept replacement
                                        }
                                    }
                                }
                            }

                            // Reconstruct response from consumed rawBytes if translation didn't replace
                            val fallbackBody = createBody(rawBytes, "application/protobuf")
                            if (fallbackBody != null) {
                                val fallbackResp = buildResponse(request, fallbackBody, "application/protobuf")
                                if (fallbackResp != null) {
                                    runCatching { (originalResponse as? java.io.Closeable)?.close() }
                                    return@intercept fallbackResp
                                }
                            }
                        }
                    }

                    // 4. Fallback if Spotify returned non-200 and external lyrics is enabled
                    if (externalEnabled && statusCode != 200) {
                        val protoBytes = LyricsManager.getLyricsProto(trackId)
                        if (protoBytes != null) {
                            val body = createBody(protoBytes, "application/protobuf")
                            if (body != null) {
                                val response = buildResponse(request, body, "application/protobuf")
                                if (response != null) {
                                    runCatching { (originalResponse as? java.io.Closeable)?.close() }
                                    Log.i(TAG, "===> INJECTED THIRD-PARTY FALLBACK PROTOBUF LYRICS FOR $trackId <===")
                                    return@intercept response
                                }
                            }
                        }
                    }

                    originalResponse
                }
            Log.i(TAG, "hookThirdPartyLyrics installed successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "hookThirdPartyLyrics failed: ${t.message}", t)
        }
    }
}
