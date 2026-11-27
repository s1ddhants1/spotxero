package app.spotxero.hooks.lyrics

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.util.Log
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import app.spotxero.hooks.network.OkHttpBridge
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
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
        val prompt = PreferencesManager.aiTranslationCustomPrompt.trim()
        val baseUrl = PreferencesManager.aiTranslationBaseUrl.trim()
        val formality = PreferencesManager.aiTranslationDeeplFormality.ifBlank { "default" }
        val promptHash = if (prompt.isNotEmpty() || baseUrl.isNotEmpty()) {
            "$baseUrl|$prompt".hashCode().toString()
        } else {
            "default"
        }
        return "$trackId:${PreferencesManager.aiTranslationTargetLang}:${PreferencesManager.aiTranslationTranslateMode}:${PreferencesManager.isAiTranslationDualLine}:${PreferencesManager.aiTranslationProvider}:${PreferencesManager.aiTranslationModel}:$formality:$promptHash".lowercase()
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
        val capStatusGetter = DexKitResolver.require<Method>(DexKitResolver.KEY_LYRICS_CAP_STATUS_GETTER)
        val enumType = capStatusGetter.returnType
        val uncappedConstant = enumType.enumConstants?.firstOrNull {
            (it as? Enum<*>)?.name == "UNCAPPED"
        } ?: error("Missing UNCAPPED enum constant on ${enumType.name}")

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
        val factory = OkHttpBridge.getFactory()
        val responseClass = factory.responseClass
        val responseBodyClass = factory.responseBodyClass ?: return

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

        val concreteBodyClass = DexKitResolver.require<Class<*>>(DexKitResolver.KEY_LYRICS_CONCRETE_BODY_CLASS)

        try {
            val concreteBodyCtor = concreteBodyClass.declaredConstructors.firstOrNull {
                it.parameterCount == 3 && it.parameterTypes[1] == Long::class.javaPrimitiveType
            }?.apply { isAccessible = true }

            if (concreteBodyCtor == null) {
                Log.w(TAG, "Concrete response body constructor unresolved for ${concreteBodyClass.name}")
                return
            }

            val mediaTypeClass = concreteBodyCtor.parameterTypes.getOrNull(0)
            val bufferClass = concreteBodyCtor.parameterTypes.getOrNull(2)
            val mediaTypeCtor = mediaTypeClass?.declaredConstructors?.firstOrNull { it.parameterCount == 4 }?.apply { isAccessible = true }
            val bufferWrite = bufferClass?.declaredMethods?.firstOrNull {
                it.name == "write" && it.parameterCount == 1 && it.parameterTypes[0] == ByteArray::class.java
            }?.apply { isAccessible = true }

            fun createBuffer(): Any? {
                val fromCtor = runCatching {
                    bufferClass?.constructors?.firstOrNull()?.newInstance()
                        ?: bufferClass?.declaredConstructors?.firstOrNull()?.apply { isAccessible = true }?.newInstance()
                }.getOrNull()
                if (fromCtor != null) return fromCtor

                return runCatching {
                    val emptyBody = responseBodyClass.declaredFields.firstOrNull { it.type == concreteBodyClass }?.apply { isAccessible = true }?.get(null)
                    val bufField = emptyBody?.javaClass?.declaredFields?.firstOrNull { it.type == bufferClass }?.apply { isAccessible = true }
                    val template = bufField?.get(emptyBody) as? Cloneable
                    template?.javaClass?.getMethod("clone")?.invoke(template)
                }.getOrNull()
            }

            fun createBody(bytes: ByteArray, contentType: String): Any? {
                if (bufferWrite == null) return null
                val buffer = createBuffer() ?: return null
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

            fun buildLyricsResponse(request: Any, bytes: ByteArray): Any? {
                val body = createBody(bytes, "application/protobuf") ?: return null
                return factory.buildResponse(request, 200, "OK", body, "application/protobuf")
            }

            OkHttpBridge.registerRealCallInterceptor(module, "lyrics_third_party") { request, url, _, proceed ->
                if (!url.contains("color-lyrics/")) {
                    return@registerRealCallInterceptor false to null
                }

                val externalEnabled = PreferencesManager.isEnabled(PrefKeys.KEY_EXTERNAL_LYRICS, false)
                val force = PreferencesManager.isEnabled(PrefKeys.KEY_FORCE_EXTERNAL_LYRICS, false)
                val aiTranslationEnabled = PreferencesManager.isEnabled(PrefKeys.KEY_AI_LYRICS_TRANSLATION_ENABLED, false)

                val trackId = if (url.contains("/track/")) {
                    url.substringAfter("/track/").substringBefore("?").substringBefore("/")
                } else null

                if (aiTranslationEnabled && trackId != null) {
                    val stockCacheKey = getStockCacheKey(trackId)
                    val cachedStock = PersistentLyricsCache.getProtobuf(stockCacheKey) ?: stockTranslatedCache[stockCacheKey]
                    if (cachedStock != null) {
                        stockTranslatedCache[stockCacheKey] = cachedStock
                        buildLyricsResponse(request, cachedStock)?.let {
                            return@registerRealCallInterceptor true to it
                        }
                    }
                }

                if (externalEnabled && (force || aiTranslationEnabled)) {
                    val protoBytes = LyricsManager.getLyricsProto(trackId)
                    if (protoBytes != null) {
                        buildLyricsResponse(request, protoBytes)?.let {
                            return@registerRealCallInterceptor true to it
                        }
                    }
                }

                val originalResponse = proceed()
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

                if (statusCode == 200 && aiTranslationEnabled && trackId != null) {
                    val rawBytes = extractBodyBytes(originalResponse)
                    if (rawBytes != null && rawBytes.isNotEmpty()) {
                        val parsed = runCatching { LyricsProtobuf.parseColorLyricsResponse(rawBytes) }.getOrNull()
                        if (parsed != null && parsed.lyrics.lines.isNotEmpty()) {
                            val translatedLyrics = runCatching {
                                AiLyricsTranslator.translateLyrics(parsed.lyrics, trackKey = trackId)
                            }.getOrDefault(parsed.lyrics)

                            val hasTranslated = translatedLyrics !== parsed.lyrics &&
                                translatedLyrics.lines.isNotEmpty() &&
                                translatedLyrics.lines != parsed.lyrics.lines

                            if (hasTranslated) {
                                val targetLang = PreferencesManager.aiTranslationTargetLang.ifBlank { parsed.language }
                                val isRtl = LyricsProtobuf.isRtlLanguage(targetLang) || LyricsProtobuf.isRtlText(translatedLyrics.lines.joinToString(" ") { it.words })
                                val translatedProtoBytes = LyricsProtobuf.buildColorLyricsResponse(
                                    lyrics = translatedLyrics,
                                    providerName = parsed.provider,
                                    providerDisplayName = "${parsed.providerDisplayName} (Translated)",
                                    customColorData = parsed.colorDataBytes,
                                    backgroundColor = LyricsManager.currentTrackColor,
                                    hasVocalRemoval = parsed.hasVocalRemoval,
                                    language = targetLang,
                                    isRtl = isRtl
                                )

                                val stockCacheKey = getStockCacheKey(trackId)
                                stockTranslatedCache[stockCacheKey] = translatedProtoBytes
                                PersistentLyricsCache.putProtobuf(stockCacheKey, translatedProtoBytes)

                                buildLyricsResponse(request, translatedProtoBytes)?.let { replacement ->
                                    runCatching { (originalResponse as? java.io.Closeable)?.close() }
                                    return@registerRealCallInterceptor true to replacement
                                }
                            }
                        }

                        buildLyricsResponse(request, rawBytes)?.let { fallbackResp ->
                            runCatching { (originalResponse as? java.io.Closeable)?.close() }
                            return@registerRealCallInterceptor true to fallbackResp
                        }
                    }
                }

                if (externalEnabled && statusCode != 200) {
                    val protoBytes = LyricsManager.getLyricsProto(trackId)
                    if (protoBytes != null) {
                        buildLyricsResponse(request, protoBytes)?.let { response ->
                            runCatching { (originalResponse as? java.io.Closeable)?.close() }
                            return@registerRealCallInterceptor true to response
                        }
                    }
                }

                true to originalResponse
            }
            Log.i(TAG, "hookThirdPartyLyrics installed successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "hookThirdPartyLyrics failed: ${t.message}", t)
        }
    }
}
