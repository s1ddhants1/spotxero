package app.spotxero.hooks.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.spotxero.BuildConfig
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

object InAppSettingsHook {

    private const val TAG = "SpotXero.InAppSettings"
    private const val SPOTXERO_URI = "spotify:spotxero"
    private const val ABOUT_SETTINGS_URI = "spotify:settings:about"
    private const val PRIVATE_ROUTE_PREFIX = "spotify:spotxero:private:"
    private const val PENDING_ROUTE_TIMEOUT_MS = 10_000L
    private val routeToken = UUID.randomUUID().toString()
    private const val L2C1_DEFAULT_MASK = 2 or 16 or 4096 or 16384 or 65536
    private const val ND71_SECTION_DEFAULT_MASK = 253

    private data class ToggleSpec(
        val id: String,
        val key: String,
        val title: String,
        val subtitle: String? = null,
        val defaultValue: Boolean
    )

    private data class SectionSpec(
        val syntheticTitleId: Int?,
        val toggles: List<ToggleSpec> = emptyList(),
        val seekDurationAfterToggleId: String? = null
    )

    private const val SECTION_PLAYBACK = -0x70010001
    private const val SECTION_LYRICS = -0x70010002
    private const val SECTION_PRIVACY = -0x70010003
    private const val SECTION_CONTENT = -0x70010004
    private const val SECTION_APPEARANCE = -0x70010005

    private val SECTION_TITLE_MAP = mapOf(
        SECTION_PLAYBACK to "Playback",
        SECTION_LYRICS to "Lyrics",
        SECTION_PRIVACY to "Privacy",
        SECTION_CONTENT to "Content",
        SECTION_APPEARANCE to "Theme & Appearance"
    )

    private val SPOTXERO_SECTIONS = listOf(
        SectionSpec(
            syntheticTitleId = null,
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_master",
                    key = PrefKeys.KEY_MASTER_ENABLED,
                    title = "Enable Module",
                    defaultValue = true
                )
            )
        ),
        SectionSpec(
            syntheticTitleId = SECTION_PLAYBACK,
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_premium",
                    key = PrefKeys.KEY_ENABLE_PREMIUM,
                    title = "Unrestricted Playback",
                    subtitle = "Block ads and unlock shuffle",
                    defaultValue = true
                ),
                ToggleSpec(
                    id = "spotxero_local_volume",
                    key = PrefKeys.KEY_FORCE_LOCAL_PLAYBACK,
                    title = "Force Local Volume",
                    subtitle = "Lock media volume to device controls",
                    defaultValue = true
                ),
                ToggleSpec(
                    id = "spotxero_double_tap_seek",
                    key = PrefKeys.KEY_DOUBLE_TAP_TO_SEEK,
                    title = "Double-Tap Artwork to Seek",
                    subtitle = "Double-tap album art edges to seek",
                    defaultValue = true
                ),
                ToggleSpec(
                    id = "spotxero_progressive_seek",
                    key = PrefKeys.KEY_SEEK_PROGRESSIVE,
                    title = "Progressive seek",
                    subtitle = "Increase skip interval on rapid taps",
                    defaultValue = false
                )
            ),
            seekDurationAfterToggleId = "spotxero_double_tap_seek"
        ),
        SectionSpec(
            syntheticTitleId = SECTION_LYRICS,
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_uncapped_lyrics",
                    key = PrefKeys.KEY_UNCAPPED_LYRICS,
                    title = "Unlimited Lyrics",
                    subtitle = "Remove monthly lyrics viewing limit",
                    defaultValue = true
                ),
                ToggleSpec(
                    id = "spotxero_external_lyrics",
                    key = PrefKeys.KEY_EXTERNAL_LYRICS,
                    title = "Enable Third-Party Lyrics",
                    subtitle = "Fetch third-party lyrics when missing",
                    defaultValue = false
                ),
                ToggleSpec(
                    id = "spotxero_force_external_lyrics",
                    key = PrefKeys.KEY_FORCE_EXTERNAL_LYRICS,
                    title = "Force Third-Party Lyrics",
                    subtitle = "Prefer third-party lyrics over Spotify",
                    defaultValue = false
                )
            )
        ),
        SectionSpec(
            syntheticTitleId = SECTION_PRIVACY,
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_clean_sharing",
                    key = PrefKeys.KEY_CLEAN_SHARING,
                    title = "Clean Sharing Links",
                    subtitle = "Remove tracking parameters from shared links",
                    defaultValue = true
                )
            )
        ),
        SectionSpec(
            syntheticTitleId = SECTION_CONTENT,
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_hide_premium_tab",
                    key = PrefKeys.KEY_HIDE_PREMIUM_TAB,
                    title = "Hide Premium Tab",
                    subtitle = "Remove Premium from bottom navigation",
                    defaultValue = true
                ),
                ToggleSpec(
                    id = "spotxero_hide_podcasts",
                    key = PrefKeys.KEY_HIDE_PODCASTS,
                    title = "Hide Podcasts",
                    subtitle = "Remove podcasts from Home and Search",
                    defaultValue = false
                ),
                ToggleSpec(
                    id = "spotxero_hide_audiobooks",
                    key = PrefKeys.KEY_HIDE_AUDIOBOOKS,
                    title = "Hide Audiobooks",
                    subtitle = "Remove audiobooks from Home and Search",
                    defaultValue = false
                )
            )
        ),
        SectionSpec(
            syntheticTitleId = SECTION_APPEARANCE,
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_amoled",
                    key = PrefKeys.KEY_SPOTIFY_AMOLED,
                    title = "Spotify Pure Black (AMOLED)",
                    subtitle = "Pure black background for dark theme",
                    defaultValue = false
                ),
                ToggleSpec(
                    id = "spotxero_monet",
                    key = PrefKeys.KEY_SPOTIFY_MONET,
                    title = "Monet Dynamic Theme",
                    subtitle = "Match accent colors to system theme",
                    defaultValue = false
                ),
                ToggleSpec(
                    id = "spotxero_roundy_ui",
                    key = PrefKeys.KEY_SPOTIFY_ROUNDY_UI,
                    title = "RoundyUI",
                    subtitle = "Round artwork, buttons, and cards",
                    defaultValue = false
                )
            )
        )
    )

    private val pendingSpotXeroRouteAt = AtomicLong(0L)
    private val activeToggleFlows = CopyOnWriteArrayList<Pair<ToggleSpec, Any>>()
    private val hookedNewIntentClasses: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var moduleRef: XposedModule? = null

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    @Volatile
    private var cachedItemGroup: Any? = null

    @Volatile
    private var prefListenerRegistered = false

    private lateinit var rootFactory: Method
    private lateinit var modelCtor: Constructor<*>
    private lateinit var sectionCtor: Constructor<*>
    private lateinit var listFactory: Method
    private lateinit var toggleItemCtor: Constructor<*>
    private lateinit var subtitleTextCtor: Constructor<*>
    private lateinit var toggleControlCtor: Constructor<*>
    private lateinit var toggleIdCtor: Constructor<*>
    private lateinit var toggleIdArg: Any
    private lateinit var reactiveToggleCtor: Constructor<*>
    private lateinit var successResultCtor: Constructor<*>
    private lateinit var stateFlowCtor: Constructor<*>
    private lateinit var stateFlowEmit: Method
    private lateinit var actionUriCtor: Constructor<*>
    private lateinit var externalUrlCtor: Constructor<*>
    private lateinit var actionControlCtor: Constructor<*>
    private lateinit var actionIdClass: Class<*>
    private lateinit var routerNavigate: Method
    private lateinit var pageHostCtor: Constructor<*>
    private lateinit var stringTitleCtor: Constructor<*>
    private lateinit var headerPropCtor: Constructor<*>
    private lateinit var titleResolver: Method
    private lateinit var iconLogoClass: Class<*>
    private lateinit var vectorBuilderClass: Class<*>
    private lateinit var vectorPathCtor: Constructor<*>
    private lateinit var moveToCtor: Constructor<*>
    private lateinit var curveToCtor: Constructor<*>
    private lateinit var pathCloseInstance: Any
    private var spotxeroIconCache: Any? = null


    private lateinit var modelGroupField: Field
    private lateinit var modelHeaderField: Field
    private lateinit var modelSectionsField: Field
    private lateinit var sectionItemsField: Field
    private lateinit var toggleItemSubtitleField: Field
    private lateinit var subtitleProviderInterface: Class<*>
    private lateinit var subtitleProviderMethod: Method
    private lateinit var toggleCallbackInterface: Class<*>
    private lateinit var itemGroupMethod: Method

    private lateinit var kc71Interface: Class<*>
    private lateinit var kc71FactoryMethod: Method
    private lateinit var kc71ViewUriMethod: Method
    private lateinit var p3z0Interface: Class<*>
    private lateinit var p3z0GetMethod: Method
    private lateinit var sb71Interface: Class<*>
    private lateinit var sb71CreateMethod: Method
    private lateinit var viewUriField: Field
    private lateinit var hostPropsField: Field
    private lateinit var hostPropsUpdateMethod: Method

    fun install(module: XposedModule) {
        moduleRef = module
        hookedNewIntentClasses.clear()
        pendingSpotXeroRouteAt.set(0L)
        resolveContracts()
        registerPreferenceSync()

        module.deoptimize(titleResolver)
        module.hook(titleResolver).intercept { chain ->
            val id = chain.args[1] as? Int
            val mapped = if (id != null) SECTION_TITLE_MAP[id] else null
            mapped ?: chain.proceed()
        }

        val routeIndex = routerNavigate.parameterTypes.indexOfFirst { it == String::class.java }
        if (routeIndex >= 0) {
            val restartRoute = privateRoute("restart")
            val seekRoute = privateRoute("seek-duration")
            module.deoptimize(routerNavigate)
            module.hook(routerNavigate).intercept { chain ->
                when (val uri = chain.args[routeIndex] as? String) {
                    restartRoute -> {
                        restartSpotify()
                        null
                    }
                    seekRoute -> {
                        showSeekDurationBottomSheet()
                        null
                    }
                    else -> {
                        if (uri == SPOTXERO_URI || uri?.startsWith("$SPOTXERO_URI?") == true) {
                            markPendingSpotXeroRoute()
                            val modifiedArgs = chain.args.toTypedArray()
                            modifiedArgs[routeIndex] = ABOUT_SETTINGS_URI
                            chain.proceed(modifiedArgs)
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "Router navigate has no string route argument")
        }

        val onNewIntent = Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        module.deoptimize(onNewIntent)
        module.hook(onNewIntent).intercept { chain ->
            rewriteDeepLinkIfNeeded(chain.args[0] as? Intent)
            chain.proceed()
        }

        module.deoptimize(pageHostCtor)
        module.hook(pageHostCtor).intercept { chain ->
            val pageDef = chain.args[1]
            val viewUriObj = runCatching { kc71ViewUriMethod.invoke(pageDef) }.getOrNull()
            val viewUriStr = runCatching {
                viewUriField.get(viewUriObj) as? String ?: viewUriObj?.toString()
            }.getOrNull()
            val isSpotXero = viewUriStr == ABOUT_SETTINGS_URI && consumePendingSpotXeroRoute()

            val result = if (isSpotXero && pageDef != null) {
                val modifiedArgs = chain.args.toTypedArray()
                modifiedArgs[1] = wrapSpotXeroPageDefinition(pageDef)
                chain.proceed(modifiedArgs)
            } else {
                chain.proceed()
            }

            if (isSpotXero) {
                runCatching {
                    val host = chain.thisObject
                    val props = hostPropsField.get(host)
                    val titleProp = headerPropCtor.newInstance(stringTitleCtor.newInstance("SpotXero"))
                    hostPropsUpdateMethod.invoke(props, titleProp)
                }.onFailure {
                    Log.w(TAG, "Failed applying SpotXero settings title", it)
                }
            }
            result
        }

        module.deoptimize(rootFactory)
        module.hook(rootFactory).intercept { chain ->
            val result = chain.proceed() ?: return@intercept null
            runCatching { injectRootEntry(result) }
                .onFailure { Log.e(TAG, "Failed injecting SpotXero settings entry", it) }
            result
        }

        Log.i(TAG, "Installed native InAppSettingsHook")
    }

    fun onActivityCreated(activity: Activity) {
        currentActivity = WeakReference(activity)
        hookNewIntentOverride(activity.javaClass)
        rewriteDeepLinkIfNeeded(activity.intent)
    }

    fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    fun onActivityPaused(activity: Activity) {
        if (currentActivity?.get() == activity) {
            currentActivity = null
        }
    }

    fun onActivityDestroyed(activity: Activity) {
        if (currentActivity?.get() == activity) {
            currentActivity = null
        }
    }

    private fun rewriteDeepLinkIfNeeded(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data == SPOTXERO_URI || data.startsWith("$SPOTXERO_URI?")) {
            markPendingSpotXeroRoute()
            intent.data = Uri.parse(ABOUT_SETTINGS_URI)
        }
    }

    private fun hookNewIntentOverride(activityClass: Class<*>) {
        val module = moduleRef ?: return
        var current: Class<*>? = activityClass
        while (current != null && current != Activity::class.java) {
            val type = current
            if (hookedNewIntentClasses.add(type)) {
                val method = runCatching { type.getDeclaredMethod("onNewIntent", Intent::class.java) }.getOrNull()
                if (method != null) {
                    runCatching {
                        module.deoptimize(method)
                        module.hook(method).intercept { chain ->
                            rewriteDeepLinkIfNeeded(chain.args[0] as? Intent)
                            chain.proceed()
                        }
                    }.onFailure {
                        hookedNewIntentClasses.remove(type)
                        Log.w(TAG, "Failed hooking ${type.name}.onNewIntent", it)
                    }
                }
            }
            current = type.superclass
        }
    }

    private fun markPendingSpotXeroRoute() {
        pendingSpotXeroRouteAt.set(SystemClock.elapsedRealtime())
    }

    private fun consumePendingSpotXeroRoute(): Boolean {
        val markedAt = pendingSpotXeroRouteAt.get()
        if (markedAt == 0L) return false
        if (SystemClock.elapsedRealtime() - markedAt > PENDING_ROUTE_TIMEOUT_MS) {
            pendingSpotXeroRouteAt.compareAndSet(markedAt, 0L)
            return false
        }
        return pendingSpotXeroRouteAt.compareAndSet(markedAt, 0L)
    }

    private fun privateRoute(name: String): String = "$PRIVATE_ROUTE_PREFIX$name:$routeToken"

    private fun resolveContracts() {
        rootFactory = DexKitResolver.get<Method>(DexKitResolver.KEY_SETTINGS_ROOT_FACTORY)
            ?: error("Missing settings root factory resolver")
        modelCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_MODEL_CTOR)
            ?: error("Missing settings model constructor resolver")
        sectionCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_SECTION_CTOR)
            ?: error("Missing settings section constructor resolver")
        listFactory = DexKitResolver.get<Method>(DexKitResolver.KEY_SETTINGS_PERSISTENT_LIST_FACTORY)
            ?: error("Missing settings persistent list factory resolver")
        toggleItemCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_TOGGLE_ITEM_CTOR)
            ?: error("Missing settings toggle item constructor resolver")
        subtitleTextCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_SUBTITLE_TEXT_CTOR)
            ?: error("Missing settings subtitle text constructor resolver")
        toggleControlCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_TOGGLE_CONTROL_CTOR)
            ?: error("Missing settings toggle control constructor resolver")
        toggleIdCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_TOGGLE_ID_CTOR)
            ?: error("Missing settings toggle id constructor resolver")
        toggleIdArg = DexKitResolver.get<Any>(DexKitResolver.KEY_SETTINGS_TOGGLE_ID_ARG)
            ?: error("Missing settings toggle id enum arg resolver")
        reactiveToggleCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_REACTIVE_TOGGLE_CTOR)
            ?: error("Missing settings reactive toggle constructor resolver")
        successResultCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_SUCCESS_RESULT_CTOR)
            ?: error("Missing settings success result constructor resolver")
        stateFlowCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_STATE_FLOW_CTOR)
            ?: error("Missing settings state flow constructor resolver")
        stateFlowEmit = DexKitResolver.get<Method>(DexKitResolver.KEY_SETTINGS_STATE_FLOW_EMIT)
            ?: error("Missing settings state flow emit resolver")
        actionUriCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_ACTION_URI_CTOR)
            ?: error("Missing settings action uri constructor resolver")
        externalUrlCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_EXTERNAL_URL_CTOR)
            ?: error("Missing settings external url constructor resolver")
        actionControlCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_ACTION_CONTROL_CTOR)
            ?: error("Missing settings action control constructor resolver")
        actionIdClass = DexKitResolver.get<Class<*>>(DexKitResolver.KEY_SETTINGS_ACTION_ID_CLASS)
            ?: error("Missing settings action id class resolver")
        routerNavigate = DexKitResolver.get<Method>(DexKitResolver.KEY_SETTINGS_ROUTER_NAVIGATE)
            ?: error("Missing settings router navigate resolver")
        pageHostCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_PAGE_HOST_CTOR)
            ?: error("Missing settings page host constructor resolver")
        stringTitleCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_STRING_TITLE_CTOR)
            ?: error("Missing settings string title constructor resolver")
        headerPropCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_HEADER_PROP_CTOR)
            ?: error("Missing settings header prop constructor resolver")
        titleResolver = DexKitResolver.get<Method>(DexKitResolver.KEY_SETTINGS_TITLE_RESOLVER)
            ?: error("Missing settings title resolver")
        iconLogoClass = DexKitResolver.get<Class<*>>(DexKitResolver.KEY_SETTINGS_ICON_LOGO_CLASS)
            ?: error("Missing settings icon logo class resolver")
        vectorBuilderClass = DexKitResolver.get<Class<*>>(DexKitResolver.KEY_SETTINGS_VECTOR_BUILDER_CLASS)
            ?: error("Missing settings vector builder class resolver")
        vectorPathCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_VECTOR_PATH_CTOR)
            ?: error("Missing settings vector path constructor resolver")
        moveToCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_PATH_MOVETO_CTOR)
            ?: error("Missing settings path moveto constructor resolver")
        curveToCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_PATH_CURVETO_CTOR)
            ?: error("Missing settings path curveto constructor resolver")
        val pathCloseClass = DexKitResolver.get<Class<*>>(DexKitResolver.KEY_SETTINGS_PATH_CLOSE_CLASS)
            ?: error("Missing settings path close class resolver")
        pathCloseInstance = pathCloseClass.declaredFields.first {
            Modifier.isStatic(it.modifiers) && it.type == pathCloseClass
        }.apply { isAccessible = true }.get(null)
            ?: error("Missing path close singleton")

        val modelClass = modelCtor.declaringClass
        val r2yClass = modelCtor.parameterTypes[0]
        val xf31Class = modelCtor.parameterTypes[1]
        modelGroupField = modelClass.declaredFields.first { it.type == r2yClass }.apply { isAccessible = true }
        modelHeaderField = modelClass.declaredFields.first { it.type == xf31Class }.apply { isAccessible = true }
        val listFields = modelClass.declaredFields.filter { List::class.java.isAssignableFrom(it.type) }
        modelSectionsField = listFields.first().apply { isAccessible = true }

        val se80Class = sectionCtor.parameterTypes[4]
        sectionItemsField = sectionCtor.declaringClass.declaredFields.first { it.type == se80Class }.apply { isAccessible = true }

        val toggleItemClass = toggleItemCtor.declaringClass
        val subtitleTextClass = subtitleTextCtor.declaringClass
        toggleItemSubtitleField = toggleItemClass.declaredFields.firstOrNull { field ->
            field.type.isInterface && field.type.methods.any { m ->
                !Modifier.isStatic(m.modifiers) &&
                    !m.isDefault &&
                    m.parameterCount == 1 &&
                    m.returnType == subtitleTextClass
            }
        }?.apply { isAccessible = true } ?: error("Unable to locate subtitle provider field on settings item")
        subtitleProviderInterface = toggleItemSubtitleField.type
        subtitleProviderMethod = subtitleProviderInterface.methods.first { m ->
            !Modifier.isStatic(m.modifiers) && !m.isDefault && m.parameterCount == 1 && m.returnType == subtitleTextClass
        }

        toggleCallbackInterface = reactiveToggleCtor.parameterTypes[1]
        val g2gInterface = toggleItemCtor.parameterTypes[8]
        val la71Interface = toggleItemClass.interfaces.first()
        itemGroupMethod = la71Interface.methods.first { m ->
            !Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == g2gInterface
        }

        kc71Interface = pageHostCtor.parameterTypes[1]
        sb71Interface = rootFactory.declaringClass.interfaces.single()
        sb71CreateMethod = sb71Interface.methods.first {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == rootFactory.returnType
        }
        kc71FactoryMethod = kc71Interface.methods.firstOrNull { m ->
            !Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 0 &&
                m.returnType.isInterface &&
                m.returnType != Set::class.java &&
                m.returnType.methods.any { inner ->
                    !Modifier.isStatic(inner.modifiers) && inner.parameterCount == 0 && inner.returnType == Any::class.java
                }
        } ?: error("Unable to locate page factory provider method on settings definition")
        p3z0Interface = kc71FactoryMethod.returnType
        p3z0GetMethod = p3z0Interface.methods.first {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Any::class.java
        }

        val q5l1Class = actionUriCtor.declaringClass.declaredConstructors
            .firstOrNull { it.parameterCount == 2 && it.parameterTypes[0] != String::class.java && it.parameterTypes[1] == Bundle::class.java }
            ?.parameterTypes?.get(0)
            ?: kc71Interface.methods.first {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    !it.returnType.isInterface &&
                    !it.returnType.isEnum &&
                    !it.returnType.isPrimitive &&
                    it.returnType != String::class.java
            }.returnType

        kc71ViewUriMethod = kc71Interface.methods.first {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == q5l1Class
        }
        viewUriField = q5l1Class.declaredFields.first { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .apply { isAccessible = true }

        val propInterface = headerPropCtor.declaringClass.interfaces.single()
        val pageHostClass = pageHostCtor.declaringClass
        val propsClass = pageHostClass.declaredFields.first { field ->
            !Modifier.isStatic(field.modifiers) &&
                field.declaredAnnotations.isEmpty() &&
                field.type.declaredMethods.any { m ->
                    !Modifier.isStatic(m.modifiers) && m.parameterCount == 1 && m.parameterTypes[0] == propInterface && m.returnType == Void.TYPE
                }
        }.also { it.isAccessible = true }.let { field ->
            hostPropsField = field
            field.type
        }
        hostPropsUpdateMethod = propsClass.declaredMethods.first { m ->
            !Modifier.isStatic(m.modifiers) && m.parameterCount == 1 && m.parameterTypes[0] == propInterface && m.returnType == Void.TYPE
        }
    }

    private fun wrapSpotXeroPageDefinition(realKc71: Any): Any {
        return Proxy.newProxyInstance(
            kc71Interface.classLoader,
            arrayOf(kc71Interface)
        ) { proxy, method, args ->
            when {
                method == kc71FactoryMethod -> {
                    val realProvider = method.invoke(realKc71)
                    Proxy.newProxyInstance(
                        p3z0Interface.classLoader,
                        arrayOf(p3z0Interface)
                    ) { pProxy, pMethod, pArgs ->
                        when {
                            pMethod == p3z0GetMethod -> {
                                val realFactory = pMethod.invoke(realProvider)
                                Proxy.newProxyInstance(
                                    sb71Interface.classLoader,
                                    arrayOf(sb71Interface)
                                ) { fProxy, fMethod, fArgs ->
                                    when {
                                        fMethod == sb71CreateMethod -> {
                                            val template = fMethod.invoke(realFactory)
                                            if (template == null) {
                                                null
                                            } else {
                                                runCatching { buildSpotXeroPageModel(template) }
                                                    .onFailure {
                                                        Log.e(TAG, "Failed building SpotXero settings page", it)
                                                    }
                                                    .getOrDefault(template)
                                            }
                                        }
                                        fMethod.name == "equals" -> fArgs?.get(0) === fProxy
                                        fMethod.name == "hashCode" -> System.identityHashCode(fProxy)
                                        fMethod.name == "toString" -> "SpotXeroPageFactory"
                                        else -> fMethod.invoke(realFactory, *(fArgs ?: emptyArray()))
                                    }
                                }
                            }
                            pMethod.name == "equals" -> pArgs?.get(0) === pProxy
                            pMethod.name == "hashCode" -> System.identityHashCode(pProxy)
                            pMethod.name == "toString" -> "SpotXeroPageProvider"
                            else -> pMethod.invoke(realProvider, *(pArgs ?: emptyArray()))
                        }
                    }
                }
                method.name == "equals" -> args?.get(0) === proxy
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "SpotXeroPageDefinition"
                else -> method.invoke(realKc71, *(args ?: emptyArray()))
            }
        }
    }

    private fun buildSpotXeroPageModel(templateModel: Any): Any {
        val pageGroup = modelGroupField.get(templateModel)
        val headerPlaceholder = modelHeaderField.get(templateModel)
        val itemGroup = cachedItemGroup ?: extractItemGroup(templateModel)
            ?: error("Unable to resolve native settings item group")
        cachedItemGroup = itemGroup

        activeToggleFlows.clear()
        val standardSections = SPOTXERO_SECTIONS.map { section ->
            val rows = buildList {
                for (toggle in section.toggles) {
                    add(createToggleItem(toggle, itemGroup))
                    if (toggle.id == section.seekDurationAfterToggleId) {
                        add(createSeekDurationItem(itemGroup))
                    }
                }
            }
            val persistentItems = listFactory.invoke(null, rows.toTypedArray())
            sectionCtor.newInstance(
                pageGroup,
                section.syntheticTitleId,
                null,
                null,
                persistentItems,
                if (section.syntheticTitleId == null) ND71_SECTION_DEFAULT_MASK else 249
            )
        }

        val allSections = buildList {
            addAll(standardSections)
            val footerRows = buildList {
                val versionItem = runCatching { createVersionItem(templateModel, itemGroup) }
                    .onFailure { Log.w(TAG, "Failed composing native version row", it) }
                    .getOrNull()
                add(
                    versionItem
                        ?: createActionItem("spotxero_version", "SpotXero Version", BuildConfig.VERSION_NAME, SPOTXERO_URI, itemGroup)
                )
                add(createExternalLinkItem("spotxero_github", "GitHub", "https://github.com/s1ddhants1/SpotXero", itemGroup))
                add(createExternalLinkItem("spotxero_telegram", "Telegram Group", "https://t.me/SpotXero", itemGroup))
                add(createActionItem("spotxero_restart", "Restart Spotify", "Apply changes that need a fresh session", privateRoute("restart"), itemGroup))
            }
            val footerItems = listFactory.invoke(null, footerRows.toTypedArray())
            add(sectionCtor.newInstance(pageGroup, null, null, null, footerItems, ND71_SECTION_DEFAULT_MASK))
        }
        Log.i(TAG, "Built SpotXero page model with ${allSections.size} sections")

        return modelCtor.newInstance(pageGroup, headerPlaceholder, null, allSections, emptyList<Any>())
    }

    private fun createToggleItem(spec: ToggleSpec, itemGroup: Any): Any {
        val initial = PreferencesManager.isStoredEnabled(spec.key, spec.defaultValue)
        val stateFlow = stateFlowCtor.newInstance(initial)
        activeToggleFlows.add(spec to stateFlow)

        val visibilityFlow = stateFlowCtor.newInstance(java.lang.Boolean.TRUE)
        val callbackProxy = Proxy.newProxyInstance(
            toggleCallbackInterface.classLoader,
            arrayOf(toggleCallbackInterface)
        ) { proxy, method, args ->
            when {
                method.parameterCount == 3 -> {
                    val newValue = args?.get(1) as? Boolean
                    if (newValue != null) {
                        PreferencesManager.setBoolean(currentActivity?.get(), spec.key, newValue)
                        stateFlowEmit.invoke(stateFlow, newValue)
                        if (spec.key == PrefKeys.KEY_MASTER_ENABLED) {
                            syncToggleFlows()
                        }
                    }
                    successResultCtor.newInstance(Unit)
                }
                method.name == "equals" -> args?.get(0) === proxy
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "SpotXeroToggle(${spec.key})"
                else -> null
            }
        }

        val toggleId = toggleIdCtor.newInstance(toggleIdArg)
        val reactiveToggle = reactiveToggleCtor.newInstance(stateFlow, callbackProxy)
        val control = toggleControlCtor.newInstance(toggleId, reactiveToggle)
        val item = toggleItemCtor.newInstance(
            spec.id,
            null,
            spec.title,
            null,
            null,
            null,
            null,
            visibilityFlow,
            itemGroup,
            null,
            control,
            emptyMap<Any, Any>(),
            L2C1_DEFAULT_MASK
        )
        spec.subtitle?.let { toggleItemSubtitleField.set(item, createSubtitleProvider(it)) }
        return item
    }

    private fun createActionItem(
        id: String,
        title: String,
        subtitle: String,
        uri: String,
        itemGroup: Any
    ): Any {
        val visibilityFlow = stateFlowCtor.newInstance(java.lang.Boolean.TRUE)
        val actionId = allocateActionId()
        val actionUri = actionUriCtor.newInstance(uri, null)
        val control = actionControlCtor.newInstance(actionId, actionUri)
        val item = toggleItemCtor.newInstance(
            id,
            null,
            title,
            null,
            null,
            null,
            null,
            visibilityFlow,
            itemGroup,
            null,
            control,
            emptyMap<Any, Any>(),
            L2C1_DEFAULT_MASK
        )
        toggleItemSubtitleField.set(item, createSubtitleProvider(subtitle))
        return item
    }

    private fun createSeekDurationItem(itemGroup: Any): Any {
        val visibilityFlow = stateFlowCtor.newInstance(java.lang.Boolean.TRUE)
        val actionId = allocateActionId()
        val actionUri = actionUriCtor.newInstance(privateRoute("seek-duration"), null)
        val control = actionControlCtor.newInstance(actionId, actionUri)
        val item = toggleItemCtor.newInstance(
            "spotxero_seek_duration",
            null,
            "Seek duration",
            null,
            null,
            null,
            null,
            visibilityFlow,
            itemGroup,
            null,
            control,
            emptyMap<Any, Any>(),
            L2C1_DEFAULT_MASK
        )
        toggleItemSubtitleField.set(item, createSubtitleProvider("Seconds to skip when seeking"))
        return item
    }

    private fun createExternalLinkItem(
        id: String,
        title: String,
        url: String,
        itemGroup: Any
    ): Any {
        val visibilityFlow = stateFlowCtor.newInstance(java.lang.Boolean.TRUE)
        val actionId = allocateActionId()
        val externalUrl = externalUrlCtor.newInstance(url, false)
        val control = actionControlCtor.newInstance(actionId, externalUrl)
        return toggleItemCtor.newInstance(
            id,
            null,
            title,
            null,
            null,
            null,
            null,
            visibilityFlow,
            itemGroup,
            null,
            control,
            emptyMap<Any, Any>(),
            L2C1_DEFAULT_MASK
        )
    }

    private fun createVersionItem(templateModel: Any, itemGroup: Any): Any? {
        val sections = modelSectionsField.get(templateModel) as? List<*> ?: return null
        val nativeVersionItem = sections.asSequence()
            .filterNotNull()
            .flatMap { (sectionItemsField.get(it) as? List<*> ?: emptyList<Any>()).asSequence() }
            .filterNotNull()
            .firstOrNull { runCatching { it.javaClass.getMethod("getId").invoke(it) == "version" }.getOrDefault(false) }
            ?: return null

        val itemClass = nativeVersionItem.javaClass
        val fields = itemClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        val nativeProvider = fields.firstOrNull { p3z0Interface.isAssignableFrom(it.type) }?.get(nativeVersionItem) ?: return null
        val nativePresenter = p3z0GetMethod.invoke(nativeProvider) ?: return null

        val orj1Class = nativePresenter.javaClass
        val orj1Ctor = orj1Class.declaredConstructors.firstOrNull {
            it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0]) && it.parameterTypes[1].isInterface
        } ?: return null
        val nativeContext = orj1Class.declaredFields.firstNotNullOfOrNull {
            if (Context::class.java.isAssignableFrom(it.type)) it.apply { isAccessible = true }.get(nativePresenter) as? Context else null
        }
        val context = currentActivity?.get() ?: nativeContext ?: PreferencesManager.resolveTargetContext() ?: return null
        val versionProvider = createVersionProvider(orj1Ctor, nativePresenter, context) ?: return null
        val customPresenter = orj1Ctor.newInstance(context, versionProvider)
        val customProvider = Proxy.newProxyInstance(
            p3z0Interface.classLoader,
            arrayOf(p3z0Interface)
        ) { proxy, method, args ->
            when {
                method == p3z0GetMethod -> customPresenter
                method.name == "equals" -> args?.get(0) === proxy
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "SpotXeroVersionProvider"
                else -> null
            }
        }

        val jwoField = fields.firstOrNull {
            it.type != String::class.java &&
                !p3z0Interface.isAssignableFrom(it.type) &&
                it.type.declaredConstructors.any { c -> c.parameterCount == 5 && c.parameterTypes[1] == String::class.java }
        } ?: return null
        val jwoCtor = jwoField.type.declaredConstructors.firstOrNull {
            it.parameterCount == 5 && it.parameterTypes[1] == String::class.java && it.parameterTypes[4] == Int::class.javaPrimitiveType
        } ?: return null
        val spotxeroJwo = jwoCtor.newInstance(null, "SpotXero Version", null, null, 29)

        val kwoCtor = itemClass.declaredConstructors.firstOrNull { it.parameterCount == 6 } ?: return null
        val visibilityFlow = stateFlowCtor.newInstance(java.lang.Boolean.TRUE)
        val visibility = if (kwoCtor.parameterTypes[2].isInstance(visibilityFlow)) {
            visibilityFlow
        } else {
            fields.first { kwoCtor.parameterTypes[2].isAssignableFrom(it.type) }.get(nativeVersionItem)
        }
        val presenterFactory = fields.first { kwoCtor.parameterTypes[5].isAssignableFrom(it.type) }.get(nativeVersionItem)
        return kwoCtor.newInstance("spotxero_version", spotxeroJwo, visibility, itemGroup, customProvider, presenterFactory)
    }

    private fun createVersionProvider(presenterCtor: Constructor<*>, nativePresenter: Any, context: Context): Any? {
        val providerType = presenterCtor.parameterTypes[1]
        val providerField = presenterCtor.declaringClass.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == providerType
        }?.apply { isAccessible = true } ?: return null
        val nativeProvider = providerField.get(nativePresenter) ?: return null
        val providerClass = nativeProvider.javaClass
        val app = context.applicationContext as? Application ?: return null
        val sourceStringFields = providerClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .onEach { it.isAccessible = true }
        val sourceStrings = sourceStringFields.map { it.get(nativeProvider) }
        val providerCtor = providerClass.declaredConstructors.firstOrNull {
            it.parameterCount == 3 &&
                it.parameterTypes[0] == Application::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == String::class.java
        } ?: return null
        val provider = providerCtor.newInstance(app, sourceStrings.getOrNull(0), sourceStrings.getOrNull(1))
        providerClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .onEach { it.isAccessible = true }
            .zip(sourceStrings)
            .forEach { (field, value) -> field.set(provider, value) }
        val packageInfo = PackageInfo().apply { versionName = BuildConfig.VERSION_NAME }
        val versionField = providerClass.declaredFields.firstOrNull { field ->
            if (Modifier.isStatic(field.modifiers)) return@firstOrNull false
            val cached = runCatching { field.apply { isAccessible = true }.get(provider) }.getOrNull() ?: return@firstOrNull false
            runCatching { cached.javaClass.getMethod("getValue").invoke(cached) is PackageInfo }.getOrDefault(false)
        }?.apply { isAccessible = true } ?: return null
        val lazyCtor = versionField.type.declaredConstructors.firstOrNull { it.parameterCount == 1 } ?: return null
        val valueFactoryType = lazyCtor.parameterTypes[0]
        val valueFactory = Proxy.newProxyInstance(
            valueFactoryType.classLoader,
            arrayOf(valueFactoryType)
        ) { proxy, method, args ->
            when {
                method.name == "invoke" && method.parameterCount == 0 -> packageInfo
                method.name == "equals" -> args?.get(0) === proxy
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> BuildConfig.VERSION_NAME
                else -> null
            }
        }
        versionField.set(provider, lazyCtor.newInstance(valueFactory))
        return provider
    }

    private fun seekDurationSeconds(): Int =
        PreferencesManager.getInt(PrefKeys.KEY_SEEK_DURATION_SECONDS, 10).coerceIn(5, 30)

    private fun showSeekDurationBottomSheet() {
        val activity = currentActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            cycleSeekDuration()
            return
        }
        activity.runOnUiThread {
            runCatching {
                val durations = intArrayOf(5, 10, 15, 20, 25, 30)
                var selectedDuration = seekDurationSeconds()
                val isAmoled = PreferencesManager.isEnabled(PrefKeys.KEY_SPOTIFY_AMOLED, false)
                val density = activity.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val dialog = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

                val window = dialog.window ?: return@runCatching
                window.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                window.setDimAmount(0.6f)
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.attributes.windowAnimations = 0

                var isDismissing = false
                val rootLayout = android.widget.FrameLayout(activity).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val navBarBottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0
                }

                val sheetContainer = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadii = floatArrayOf(
                            dp(28).toFloat(), dp(28).toFloat(),
                            dp(28).toFloat(), dp(28).toFloat(),
                            0f, 0f,
                            0f, 0f
                        )
                        setColor(if (isAmoled) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#242424"))
                    }
                    background = bg
                    setPadding(0, dp(12), 0, dp(16) + navBarBottom)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                    )
                    isClickable = true
                }
                rootLayout.addView(sheetContainer)

                fun dismissWithAnimation() {
                    if (isDismissing) return
                    isDismissing = true
                    val targetY = if (sheetContainer.height > 0) sheetContainer.height.toFloat() else dp(450).toFloat()
                    sheetContainer.animate()
                        .translationY(targetY)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.AccelerateInterpolator(1.6f))
                        .withEndAction {
                            dialog.dismiss()
                        }
                        .start()
                }

                rootLayout.setOnClickListener {
                    dismissWithAnimation()
                }

                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                        dismissWithAnimation()
                        true
                    } else false
                }

                val handlePill = android.view.View(activity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(16)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(android.graphics.Color.parseColor("#5a5a5a"))
                    }
                }
                sheetContainer.addView(handlePill)

                val titleView = android.widget.TextView(activity).apply {
                    text = "Seek duration"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dp(24)
                        rightMargin = dp(24)
                        bottomMargin = dp(6)
                    }
                }
                sheetContainer.addView(titleView)

                val subtitleView = android.widget.TextView(activity).apply {
                    text = "Seconds to skip when seeking"
                    setTextColor(android.graphics.Color.parseColor("#a7a7a7"))
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dp(24)
                        rightMargin = dp(24)
                        bottomMargin = dp(16)
                    }
                }
                sheetContainer.addView(subtitleView)

                val spotifyGreen = android.graphics.Color.parseColor("#1ed760")
                val unselectedGrey = android.graphics.Color.parseColor("#535353")
                fun buildRadioDrawable(selected: Boolean): android.graphics.drawable.Drawable {
                    return if (!selected) {
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setSize(dp(20), dp(20))
                            setColor(android.graphics.Color.TRANSPARENT)
                            setStroke(dp(2), unselectedGrey)
                        }
                    } else {
                        val outer = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setSize(dp(20), dp(20))
                            setColor(android.graphics.Color.TRANSPARENT)
                            setStroke(dp(2), spotifyGreen)
                        }
                        val inner = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setSize(dp(10), dp(10))
                            setColor(spotifyGreen)
                        }
                        android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).apply {
                            setLayerGravity(1, android.view.Gravity.CENTER)
                            val inset = dp(5)
                            setLayerInset(1, inset, inset, inset, inset)
                        }
                    }
                }

                val radioIndicators = mutableMapOf<Int, android.widget.ImageView>()

                fun commitSelection(duration: Int) {
                    PreferencesManager.setInt(activity, PrefKeys.KEY_SEEK_DURATION_SECONDS, duration)
                    dismissWithAnimation()
                }

                for (d in durations) {
                    val row = android.widget.LinearLayout(activity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(52)
                        )
                        setPadding(dp(24), 0, dp(24), 0)
                        val outValue = android.util.TypedValue()
                        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                        setBackgroundResource(outValue.resourceId)
                        isClickable = true
                    }

                    val label = android.widget.TextView(activity).apply {
                        text = "$d seconds"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    }
                    row.addView(label)

                    val radioIndicator = android.widget.ImageView(activity).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20))
                        setImageDrawable(buildRadioDrawable(d == selectedDuration))
                    }
                    radioIndicators[d] = radioIndicator
                    row.addView(radioIndicator)

                    row.setOnClickListener {
                        selectedDuration = d
                        radioIndicators.forEach { (dur, indicator) ->
                            indicator.setImageDrawable(buildRadioDrawable(dur == d))
                        }
                        commitSelection(d)
                    }
                    sheetContainer.addView(row)
                }

                sheetContainer.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        sheetContainer.viewTreeObserver.removeOnPreDrawListener(this)
                        val startY = if (sheetContainer.height > 0) sheetContainer.height.toFloat() else dp(450).toFloat()
                        sheetContainer.translationY = startY
                        sheetContainer.animate()
                            .translationY(0f)
                            .setDuration(280)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(2.0f))
                            .start()
                        return true
                    }
                })

                dialog.setContentView(rootLayout)
                dialog.show()
            }.onFailure {
                cycleSeekDuration()
            }
        }
    }

    private fun cycleSeekDuration() {
        val current = seekDurationSeconds()
        val next = if (current >= 30) 5 else ((current / 5) + 1) * 5
        PreferencesManager.setInt(currentActivity?.get(), PrefKeys.KEY_SEEK_DURATION_SECONDS, next)
        currentActivity?.get()?.let { activity ->
            activity.runOnUiThread {
                Toast.makeText(activity, "$next seconds", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createSubtitleProvider(subtitleSupplier: () -> String): Any {
        return Proxy.newProxyInstance(
            subtitleProviderInterface.classLoader,
            arrayOf(subtitleProviderInterface)
        ) { proxy, method, args ->
            when {
                method == subtitleProviderMethod -> subtitleTextCtor.newInstance(subtitleSupplier())
                method.name == "equals" -> args?.get(0) === proxy
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> subtitleSupplier()
                else -> null
            }
        }
    }

    private fun createSubtitleProvider(subtitle: String): Any = createSubtitleProvider { subtitle }

    private fun syncToggleFlows() {
        for ((spec, flow) in activeToggleFlows) {
            runCatching {
                stateFlowEmit.invoke(flow, PreferencesManager.isStoredEnabled(spec.key, spec.defaultValue))
            }
        }
    }

    private fun registerPreferenceSync() {
        if (prefListenerRegistered) return
        prefListenerRegistered = true
        PreferencesManager.addOnPreferenceChangedListener {
            syncToggleFlows()
        }
    }

    private fun extractItemGroup(model: Any): Any? {
        val sections = modelSectionsField.get(model) as? List<*> ?: return null
        for (section in sections) {
            if (section == null) continue
            val items = sectionItemsField.get(section) as? List<*> ?: continue
            val first = items.firstOrNull { it != null } ?: continue
            val group = runCatching { itemGroupMethod.invoke(first) }.getOrNull()
            if (group != null) return group
        }
        return null
    }

    private fun injectRootEntry(result: Any) {
        if (cachedItemGroup == null) {
            cachedItemGroup = extractItemGroup(result)
        }
        val sections = modelSectionsField.get(result) as? List<*> ?: return
        val mainSection = sections.firstOrNull { section ->
            if (section == null) return@firstOrNull false
            val items = sectionItemsField.get(section) as? List<*> ?: return@firstOrNull false
            items.any { item ->
                item != null && runCatching { item.javaClass.getMethod("getId").invoke(item) == "accountPage" }.getOrDefault(false)
            }
        } ?: return

        val itemsList = sectionItemsField.get(mainSection) as? List<*> ?: return
        val items = itemsList.filterNotNull()
        if (items.any { runCatching { it.javaClass.getMethod("getId").invoke(it) == "spotxeroPage" }.getOrDefault(false) }) {
            return
        }

        val itemClass = items.first().javaClass
        val notificationsItem = items.firstOrNull {
            runCatching { it.javaClass.getMethod("getId").invoke(it) == "notificationsPage" }.getOrDefault(false)
        } ?: return

        val getIdMethod = itemClass.getMethod("getId")
        val jwoField = itemClass.declaredFields.firstOrNull { field ->
            field.isAccessible = true
            !Modifier.isStatic(field.modifiers) &&
                !field.type.isInterface &&
                field.type != String::class.java &&
                field.type.declaredConstructors.any { c -> c.parameterCount == 5 && c.parameterTypes[1] == String::class.java }
        } ?: return
        val notificationsJwo = jwoField.get(notificationsItem) ?: return

        var clickActionField: Field? = null
        var clickFnField: Field? = null
        var clickFnInterface: Class<*>? = null
        var clickInvokeMethod: Method? = null
        for (field in itemClass.declaredFields) {
            field.isAccessible = true
            val v = field.get(notificationsItem) ?: continue
            if (!field.type.isInterface) continue
            for (innerField in v.javaClass.declaredFields) {
                innerField.isAccessible = true
                if (!innerField.type.isInterface) continue
                val invokeMethod = innerField.type.methods.firstOrNull { m ->
                    !Modifier.isStatic(m.modifiers) && m.parameterCount == 1 && m.parameterTypes[0] == Any::class.java && m.returnType == Any::class.java
                } ?: continue
                clickActionField = field
                clickFnField = innerField
                clickFnInterface = innerField.type
                clickInvokeMethod = invokeMethod
                break
            }
            if (clickActionField != null) break
        }
        val cActionField = clickActionField ?: return
        val cFnField = clickFnField ?: return
        val cFnIface = clickFnInterface ?: return
        val cInvoke = clickInvokeMethod ?: return

        val clickActionObj = cActionField.get(notificationsItem) ?: return
        val clickFn = cFnField.get(clickActionObj) ?: return
        val sampleDescriptor = cInvoke.invoke(clickFn, notificationsJwo) ?: return

        val descriptorClass = sampleDescriptor.javaClass
        val descriptorCtor = descriptorClass.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size == 6 &&
                p[0] == Int::class.javaPrimitiveType &&
                List::class.java.isAssignableFrom(p[2]) &&
                p[4] == String::class.java &&
                p[5] == String::class.java
        } ?: return

        val accountIndex = items.indexOfFirst { getIdMethod.invoke(it) == "accountPage" }
        val targetRefItem = items.getOrNull(accountIndex) ?: notificationsItem
        val targetJwo = jwoField.get(targetRefItem) ?: notificationsJwo
        val targetActionObj = cActionField.get(targetRefItem) ?: clickActionObj
        val targetFnObj = if (targetActionObj.javaClass == clickActionObj.javaClass) cFnField.get(targetActionObj) else null
        val refDesc = if (targetFnObj != null) runCatching { cInvoke.invoke(targetFnObj, targetJwo) }.getOrNull() ?: sampleDescriptor else sampleDescriptor

        val intVal = descriptorClass.declaredFields.first { it.type == Int::class.javaPrimitiveType }
            .apply { isAccessible = true }.getInt(refDesc)
        val iconType = descriptorCtor.parameterTypes[3]
        val fallbackIcon = descriptorClass.declaredFields.first { it.type == iconType }
            .apply { isAccessible = true }.get(refDesc)
        val spotxeroIcon = runCatching { buildSpotXeroRowIcon(iconType) }
            .onFailure { Log.e(TAG, "Failed building SpotXero row icon: ${it.message}", it) }
            .getOrNull() ?: fallbackIcon
        val uriClass = descriptorCtor.parameterTypes[1]
        val spotxeroUri = uriClass.getConstructor(String::class.java).newInstance(SPOTXERO_URI)

        val spotxeroDescriptor = descriptorCtor.newInstance(
            intVal,
            spotxeroUri,
            emptyList<Any>(),
            spotxeroIcon,
            "SpotXero",
            "Customizations & Features"
        )

        val customClickFn = Proxy.newProxyInstance(
            cFnIface.classLoader,
            arrayOf(cFnIface)
        ) { proxy, method, args ->
            when {
                method == cInvoke -> spotxeroDescriptor
                method.name == "equals" -> args?.get(0) === proxy
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "SpotXeroDescriptorProvider"
                else -> null
            }
        }

        val customClickAction = clickActionObj.javaClass.getConstructor(cFnIface).newInstance(customClickFn)

        val jwoCtor = jwoField.type.declaredConstructors.first { lc ->
            lc.parameterCount == 5 && lc.parameterTypes[1] == String::class.java && lc.parameterTypes[4] == Int::class.javaPrimitiveType
        }
        val spotxeroJwo = jwoCtor.newInstance(null, "SpotXero", null, null, 29)

        val mainCtor = itemClass.declaredConstructors.first { it.parameterCount == 6 }
        val args6 = Array<Any?>(6) { null }
        val pTypes = mainCtor.parameterTypes
        for (i in pTypes.indices) {
            args6[i] = when {
                pTypes[i] == String::class.java -> "spotxeroPage"
                pTypes[i] == jwoField.type -> spotxeroJwo
                pTypes[i].isAssignableFrom(customClickAction.javaClass) -> customClickAction
                else -> itemClass.declaredFields.first { f ->
                    f.isAccessible = true
                    f.type == pTypes[i]
                }.get(notificationsItem)
            }
        }
        val spotxeroItem = mainCtor.newInstance(*args6)

        val mutableItems = items.toMutableList()
        val insertIndex = if (accountIndex != -1) accountIndex else 0
        mutableItems.add(insertIndex, spotxeroItem)
        val persistentList = listFactory.invoke(null, mutableItems.toTypedArray())
        sectionItemsField.set(mainSection, persistentList)
    }

    private fun buildSpotXeroRowIcon(r2yClass: Class<*>): Any {
        spotxeroIconCache?.let { return it }

        val n0yFields = r2yClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive
        }.onEach { it.isAccessible = true }
        val n0yClass = n0yFields.first().type
        val n0yCtor = n0yClass.declaredConstructors.first {
            it.parameterCount == 2 && it.parameterTypes[0] == it.parameterTypes[1]
        }
        val oc80Class = n0yCtor.parameterTypes[0]

        val templateLogo = iconLogoClass.declaredFields.first {
            Modifier.isStatic(it.modifiers) && it.type == iconLogoClass
        }.apply { isAccessible = true }.get(null)!!
        val templatePair = n0yFields.first().get(templateLogo)!!
        val oc80Field = n0yClass.declaredFields.first { it.type == oc80Class }.apply { isAccessible = true }
        val templateImage = oc80Field.get(templatePair)!!
        val rootGroupField = oc80Class.declaredFields.first { f ->
            !Modifier.isStatic(f.modifiers) && !f.type.isPrimitive && f.type != String::class.java
        }.apply { isAccessible = true }
        val templateRootGroup = rootGroupField.get(templateImage)!!
        val childrenField = templateRootGroup.javaClass.declaredFields.first { f ->
            !Modifier.isStatic(f.modifiers) && List::class.java.isAssignableFrom(f.type) && f.type != List::class.java
        }.apply { isAccessible = true }
        val templatePath = (childrenField.get(templateRootGroup) as List<*>).first {
            vectorPathCtor.declaringClass.isInstance(it)
        }!!
        val brushType = vectorPathCtor.parameterTypes[3]
        val blackBrush = vectorPathCtor.declaringClass.declaredFields.firstNotNullOf { f ->
            if (f.type == brushType) f.apply { isAccessible = true }.get(templatePath) else null
        }

        val vec16 = buildSpotXeroVector(16f, "SpotXero.Vector.Logo16", blackBrush, oc80Class)
        val vec24 = buildSpotXeroVector(24f, "SpotXero.Vector.Logo24", blackBrush, oc80Class)
        val n0yPair = n0yCtor.newInstance(vec16, vec24)

        val icon = allocateInstance(iconLogoClass)
        for (f in n0yFields) {
            f.set(icon, n0yPair)
        }
        spotxeroIconCache = icon
        return icon
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildSpotXeroVector(
        sizeDp: Float,
        name: String,
        strokeBrush: Any,
        oc80Class: Class<*>
    ): Any {
        val builderCtor = vectorBuilderClass.declaredConstructors.first { c ->
            val p = c.parameterTypes
            p.size == 9 && p[0] == String::class.java && p[1] == Float::class.javaPrimitiveType
        }
        val buildMethod = vectorBuilderClass.declaredMethods.first {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == oc80Class
        }
        val stackField = vectorBuilderClass.declaredFields.first {
            !Modifier.isStatic(it.modifiers) && ArrayList::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val groupClass = vectorBuilderClass.declaredFields.first { f ->
            !Modifier.isStatic(f.modifiers) &&
                !f.type.isPrimitive &&
                f.type != String::class.java &&
                !List::class.java.isAssignableFrom(f.type)
        }.type
        val groupCtor = groupClass.declaredConstructors.first { c ->
            val p = c.parameterTypes
            p.size == 10 && p[0] == String::class.java && List::class.java.isAssignableFrom(p[8]) && p[9] == Int::class.javaPrimitiveType
        }
        val groupChildrenField = groupClass.declaredFields.first {
            !Modifier.isStatic(it.modifiers) && ArrayList::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }

        val builder = builderCtor.newInstance(name, sizeDp, sizeDp, 108f, 108f, 0L, 0, false, 96)
        val stack = stackField.get(builder) as ArrayList<Any>

        val scaleGroup = groupCtor.newInstance("", 0f, 54f, 56f, 1.4f, 1.4f, 0f, 0f, emptyList<Any>(), 0)
        val rotateGroup = groupCtor.newInstance("", 8f, 54f, 54f, 1f, 1f, 0f, 0f, emptyList<Any>(), 0)
        stack.add(scaleGroup)
        stack.add(rotateGroup)

        val paths = groupChildrenField.get(rotateGroup) as ArrayList<Any>
        paths.add(
            vectorPathCtor.newInstance(
                "",
                arrayListOf(
                    moveToCtor.newInstance(28f, 35f),
                    curveToCtor.newInstance(42f, 42f, 66f, 50f, 80f, 57f)
                ),
                0,
                null,
                1f,
                strokeBrush,
                1f,
                7.5f,
                1,
                0,
                4f,
                0f,
                1f,
                0f
            )
        )
        paths.add(
            vectorPathCtor.newInstance(
                "",
                arrayListOf(
                    moveToCtor.newInstance(28f, 57f),
                    curveToCtor.newInstance(42f, 50f, 66f, 42f, 80f, 35f)
                ),
                0,
                null,
                1f,
                strokeBrush,
                1f,
                7.5f,
                1,
                0,
                4f,
                0f,
                1f,
                0f
            )
        )
        paths.add(
            vectorPathCtor.newInstance(
                "",
                arrayListOf(
                    moveToCtor.newInstance(41f, 75.5f),
                    curveToCtor.newInstance(41f, 70.53f, 46.82f, 66.5f, 54f, 66.5f),
                    curveToCtor.newInstance(61.18f, 66.5f, 67f, 70.53f, 67f, 75.5f),
                    curveToCtor.newInstance(67f, 80.47f, 61.18f, 84.5f, 54f, 84.5f),
                    curveToCtor.newInstance(46.82f, 84.5f, 41f, 80.47f, 41f, 75.5f),
                    pathCloseInstance
                ),
                0,
                null,
                1f,
                strokeBrush,
                1f,
                6f,
                1,
                1,
                4f,
                0f,
                1f,
                0f
            )
        )

        return buildMethod.invoke(builder)!!
    }

    private fun allocateActionId(): Any = allocateInstance(actionIdClass)

    private fun allocateInstance(cls: Class<*>): Any {
        for (ctor in cls.declaredConstructors.sortedBy { it.parameterCount }) {
            runCatching {
                ctor.isAccessible = true
                val args = Array<Any?>(ctor.parameterCount) { index ->
                    when (ctor.parameterTypes[index]) {
                        Int::class.javaPrimitiveType -> 0
                        Boolean::class.javaPrimitiveType -> false
                        Float::class.javaPrimitiveType -> 0f
                        Long::class.javaPrimitiveType -> 0L
                        Double::class.javaPrimitiveType -> 0.0
                        Short::class.javaPrimitiveType -> 0.toShort()
                        Byte::class.javaPrimitiveType -> 0.toByte()
                        Char::class.javaPrimitiveType -> '\u0000'
                        else -> null
                    }
                }
                return ctor.newInstance(*args)
            }
        }
        return allocateUnsafe(cls)
    }

    private fun allocateUnsafe(cls: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls) as Any
    }

    private fun restartSpotify() {
        val ctx = currentActivity?.get() ?: PreferencesManager.resolveTargetContext() ?: return
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
        ctx.startActivity(launchIntent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }, 300)
    }
}
