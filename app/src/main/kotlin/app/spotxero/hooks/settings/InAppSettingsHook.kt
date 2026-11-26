package app.spotxero.hooks.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.spotxero.BuildConfig
import app.spotxero.R
import app.spotxero.core.DexKitResolver
import app.spotxero.core.HookRegistry
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
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
    private const val SHORTCUT_ID = "spotxero_settings"
    private const val EXTRA_SHORTCUT = "spotxero_shortcut"
    private const val PENDING_ROUTE_TIMEOUT_MS = 60_000L
    private val routeToken = UUID.randomUUID().toString()
    private const val L2C1_DEFAULT_MASK = 2 or 16 or 4096 or 16384 or 65536
    private const val ND71_HEADERLESS_MASK = 253
    private const val ND71_TITLED_MASK = 249

    private data class ToggleSpec(
        val id: String,
        val key: String,
        val titleRes: Int,
        val fallbackTitle: String,
        val subtitleRes: Int? = null,
        val fallbackSubtitle: String? = null,
        val defaultValue: Boolean,
        val dependsOnKey: String? = null,
        val requiresRestart: Boolean = false
    )

    private data class SectionSpec(
        val syntheticTitleId: Int?,
        val titleRes: Int? = null,
        val fallbackTitle: String? = null,
        val toggles: List<ToggleSpec> = emptyList(),
        val seekDurationAfterToggleId: String? = null,
        val includeLyricsDialogs: Boolean = false
    )

    private data class ToggleBinding(
        val spec: ToggleSpec,
        val valueFlow: Any,
        val visibilityFlow: Any
    )

    private data class VisibilityBinding(
        val visibilityFlow: Any,
        val isVisible: () -> Boolean
    )

    private const val SECTION_PLAYBACK = -0x70010001
    private const val SECTION_LYRICS = -0x70010002
    private const val SECTION_PRIVACY = -0x70010003
    private const val SECTION_CONTENT = -0x70010004
    private const val SECTION_APPEARANCE = -0x70010005

    private val SPOTXERO_SECTIONS = listOf(
        SectionSpec(
            syntheticTitleId = null,
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_master",
                    key = PrefKeys.KEY_MASTER_ENABLED,
                    titleRes = R.string.feature_master_switch_title,
                    fallbackTitle = "Enable Module",
                    defaultValue = true,
                    requiresRestart = true
                )
            )
        ),
        SectionSpec(
            syntheticTitleId = SECTION_PLAYBACK,
            titleRes = R.string.category_playback,
            fallbackTitle = "Playback",
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_premium",
                    key = PrefKeys.KEY_ENABLE_PREMIUM,
                    titleRes = R.string.pref_unrestricted_playback_title,
                    fallbackTitle = "Unrestricted Playback",
                    subtitleRes = R.string.pref_unrestricted_playback_desc,
                    fallbackSubtitle = "Block ads and unlock shuffle",
                    defaultValue = true,
                    requiresRestart = true
                ),
                ToggleSpec(
                    id = "spotxero_local_volume",
                    key = PrefKeys.KEY_FORCE_LOCAL_PLAYBACK,
                    titleRes = R.string.feature_force_local_playback_title,
                    fallbackTitle = "Force Local Volume",
                    subtitleRes = R.string.feature_force_local_playback_desc,
                    fallbackSubtitle = "Lock media volume to device controls",
                    defaultValue = true
                ),
                ToggleSpec(
                    id = "spotxero_double_tap_seek",
                    key = PrefKeys.KEY_DOUBLE_TAP_TO_SEEK,
                    titleRes = R.string.feature_double_tap_to_seek_title,
                    fallbackTitle = "Double-Tap Artwork to Seek",
                    subtitleRes = R.string.feature_double_tap_to_seek_desc,
                    fallbackSubtitle = "Double-tap album art edges to seek",
                    defaultValue = true
                ),
                ToggleSpec(
                    id = "spotxero_progressive_seek",
                    key = PrefKeys.KEY_SEEK_PROGRESSIVE,
                    titleRes = R.string.seek_seconds_addup,
                    fallbackTitle = "Progressive seek",
                    subtitleRes = R.string.seek_seconds_addup_desc,
                    fallbackSubtitle = "Increase skip interval on rapid taps",
                    defaultValue = false,
                    dependsOnKey = PrefKeys.KEY_DOUBLE_TAP_TO_SEEK
                )
            ),
            seekDurationAfterToggleId = "spotxero_double_tap_seek"
        ),
        SectionSpec(
            syntheticTitleId = SECTION_LYRICS,
            titleRes = R.string.category_lyrics,
            fallbackTitle = "Lyrics",
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_uncapped_lyrics",
                    key = PrefKeys.KEY_UNCAPPED_LYRICS,
                    titleRes = R.string.feature_uncapped_lyrics_title,
                    fallbackTitle = "Unlimited Lyrics",
                    subtitleRes = R.string.feature_uncapped_lyrics_desc,
                    fallbackSubtitle = "Remove monthly lyrics viewing limit",
                    defaultValue = true
                ),
                ToggleSpec(
                    id = "spotxero_external_lyrics",
                    key = PrefKeys.KEY_EXTERNAL_LYRICS,
                    titleRes = R.string.pref_external_lyrics_title,
                    fallbackTitle = "Enable Third-Party Lyrics",
                    subtitleRes = R.string.pref_external_lyrics_desc,
                    fallbackSubtitle = "Fetch third-party lyrics when missing",
                    defaultValue = false
                ),
                ToggleSpec(
                    id = "spotxero_force_external_lyrics",
                    key = PrefKeys.KEY_FORCE_EXTERNAL_LYRICS,
                    titleRes = R.string.pref_force_external_lyrics_title,
                    fallbackTitle = "Force Third-Party Lyrics",
                    subtitleRes = R.string.pref_force_external_lyrics_desc,
                    fallbackSubtitle = "Prefer third-party lyrics over Spotify",
                    defaultValue = false,
                    dependsOnKey = PrefKeys.KEY_EXTERNAL_LYRICS
                )
            ),
            includeLyricsDialogs = true
        ),
        SectionSpec(
            syntheticTitleId = SECTION_PRIVACY,
            titleRes = R.string.category_privacy,
            fallbackTitle = "Privacy",
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_clean_sharing",
                    key = PrefKeys.KEY_CLEAN_SHARING,
                    titleRes = R.string.feature_clean_sharing_title,
                    fallbackTitle = "Clean Sharing Links",
                    subtitleRes = R.string.feature_clean_sharing_desc,
                    fallbackSubtitle = "Remove tracking parameters from shared links",
                    defaultValue = true
                )
            )
        ),
        SectionSpec(
            syntheticTitleId = SECTION_CONTENT,
            titleRes = R.string.category_content,
            fallbackTitle = "Content",
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_hide_premium_tab",
                    key = PrefKeys.KEY_HIDE_PREMIUM_TAB,
                    titleRes = R.string.feature_hide_premium_tab_title,
                    fallbackTitle = "Hide Premium Tab",
                    subtitleRes = R.string.feature_hide_premium_tab_desc,
                    fallbackSubtitle = "Remove Premium from bottom navigation",
                    defaultValue = true,
                    requiresRestart = true
                ),
                ToggleSpec(
                    id = "spotxero_hide_podcasts",
                    key = PrefKeys.KEY_HIDE_PODCASTS,
                    titleRes = R.string.feature_hide_podcasts_title,
                    fallbackTitle = "Hide Podcasts",
                    subtitleRes = R.string.feature_hide_podcasts_desc,
                    fallbackSubtitle = "Remove podcasts from Home and Search",
                    defaultValue = false
                ),
                ToggleSpec(
                    id = "spotxero_hide_audiobooks",
                    key = PrefKeys.KEY_HIDE_AUDIOBOOKS,
                    titleRes = R.string.feature_hide_audiobooks_title,
                    fallbackTitle = "Hide Audiobooks",
                    subtitleRes = R.string.feature_hide_audiobooks_desc,
                    fallbackSubtitle = "Remove audiobooks from Home and Search",
                    defaultValue = false
                )
            )
        ),
        SectionSpec(
            syntheticTitleId = SECTION_APPEARANCE,
            titleRes = R.string.category_theme,
            fallbackTitle = "Theme & Appearance",
            toggles = listOf(
                ToggleSpec(
                    id = "spotxero_amoled",
                    key = PrefKeys.KEY_SPOTIFY_AMOLED,
                    titleRes = R.string.feature_spotify_amoled_title,
                    fallbackTitle = "Spotify Pure Black (AMOLED)",
                    subtitleRes = R.string.feature_spotify_amoled_desc,
                    fallbackSubtitle = "Pure black background for dark theme",
                    defaultValue = false,
                    requiresRestart = true
                ),
                ToggleSpec(
                    id = "spotxero_monet",
                    key = PrefKeys.KEY_SPOTIFY_MONET,
                    titleRes = R.string.feature_spotify_monet_title,
                    fallbackTitle = "Monet Dynamic Theme",
                    subtitleRes = R.string.feature_spotify_monet_desc,
                    fallbackSubtitle = "Match accent colors to system theme",
                    defaultValue = false,
                    requiresRestart = true
                ),
                ToggleSpec(
                    id = "spotxero_roundy_ui",
                    key = PrefKeys.KEY_SPOTIFY_ROUNDY_UI,
                    titleRes = R.string.feature_spotify_roundy_ui_title,
                    fallbackTitle = "RoundyUI",
                    subtitleRes = R.string.feature_spotify_roundy_ui_desc,
                    fallbackSubtitle = "Round artwork, buttons, and cards",
                    defaultValue = false,
                    requiresRestart = true
                )
            )
        )
    )

    private val pendingSpotXeroRouteAt = AtomicLong(0L)
    private val activeToggleBindings = CopyOnWriteArrayList<ToggleBinding>()
    private val activeVisibilityBindings = CopyOnWriteArrayList<VisibilityBinding>()
    private val hookedNewIntentClasses: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var moduleRef: XposedModule? = null

    @Volatile
    private var moduleResources: Resources? = null

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    @Volatile
    private var cachedItemGroup: Any? = null

    @Volatile
    private var prefListenerRegistered = false

    @Volatile
    private var isInstalled = false

    @Volatile
    private var shortcutRegistered = false

    @Volatile
    private var shortcutRemoved = false

    @Volatile
    private var shortcutSyncing = false

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
    private lateinit var titleResolver: Method

    private var stringTitleCtor: Constructor<*>? = null
    private var headerPropCtor: Constructor<*>? = null
    private var iconLogoClass: Class<*>? = null
    private var vectorBuilderClass: Class<*>? = null
    private var vectorPathCtor: Constructor<*>? = null
    private var moveToCtor: Constructor<*>? = null
    private var curveToCtor: Constructor<*>? = null
    private var pathCloseInstance: Any? = null
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
    private var hostPropsField: Field? = null
    private var hostPropsUpdateMethod: Method? = null

    fun reset() {
        isInstalled = false
        shortcutRegistered = false
        shortcutRemoved = false
        shortcutSyncing = false
        pendingSpotXeroRouteAt.set(0L)
    }

    fun install(module: XposedModule) {
        isInstalled = true
        shortcutRemoved = false
        moduleRef = module
        hookedNewIntentClasses.clear()
        pendingSpotXeroRouteAt.set(0L)
        resolveContracts()
        registerPreferenceSync()

        module.deoptimize(titleResolver)
        module.hook(titleResolver)
            .setId("inapp_settings_title_resolver")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val id = chain.args[1] as? Int
                val section = if (id != null) SPOTXERO_SECTIONS.firstOrNull { it.syntheticTitleId == id } else null
                if (section != null && section.fallbackTitle != null) {
                    str(section.titleRes, section.fallbackTitle)
                } else {
                    chain.proceed()
                }
            }

        val routeIndex = routerNavigate.parameterTypes.indexOfFirst { it == String::class.java }
        if (routeIndex >= 0) {
            val restartRoute = privateRoute("restart")
            val seekRoute = privateRoute("seek-duration")
            val lyricsProvidersRoute = privateRoute("lyrics-providers")
            val aiTranslationRoute = privateRoute("ai-translation")
            val diagnosticsRoute = privateRoute("diagnostics")

            module.deoptimize(routerNavigate)
            module.hook(routerNavigate)
                .setId("inapp_settings_router_navigate")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    when (val uri = chain.args[routeIndex] as? String) {
                        restartRoute -> {
                            restartSpotify()
                            null
                        }
                        seekRoute -> {
                            withActiveActivity(
                                onActive = { InAppSettingsSheets.showSeekDurationBottomSheet(it) },
                                onMissing = { cycleSeekDuration() }
                            )
                            null
                        }
                        lyricsProvidersRoute -> {
                            withActiveActivity(
                                onActive = { InAppSettingsSheets.showLyricsProvidersBottomSheet(it) }
                            )
                            null
                        }
                        aiTranslationRoute -> {
                            withActiveActivity(
                                onActive = { InAppSettingsSheets.showAiTranslationBottomSheet(it) }
                            )
                            null
                        }
                        diagnosticsRoute -> {
                            withActiveActivity(
                                onActive = { InAppSettingsSheets.showDiagnosticsBottomSheet(it, ::restartSpotify) }
                            )
                            null
                        }
                        ABOUT_SETTINGS_URI -> {
                            pendingSpotXeroRouteAt.set(0L)
                            chain.proceed()
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
        module.hook(onNewIntent)
            .setId("inapp_settings_activity_new_intent")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                rewriteDeepLinkIfNeeded(chain.args[0] as? Intent)
                chain.proceed()
            }

        module.deoptimize(pageHostCtor)
        module.hook(pageHostCtor)
            .setId("inapp_settings_page_host_ctor")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
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
                    val hField = hostPropsField
                    val hMethod = hostPropsUpdateMethod
                    val hPropCtor = headerPropCtor
                    val sTitleCtor = stringTitleCtor
                    if (hField != null && hMethod != null && hPropCtor != null && sTitleCtor != null) {
                        runCatching {
                            val host = chain.thisObject
                            val props = hField.get(host)
                            val titleProp = hPropCtor.newInstance(sTitleCtor.newInstance("SpotXero"))
                            hMethod.invoke(props, titleProp)
                        }.onFailure {
                            Log.w(TAG, "Failed applying SpotXero settings title", it)
                        }
                    }
                }
                result
            }

        module.deoptimize(rootFactory)
        module.hook(rootFactory)
            .setId("inapp_settings_root_factory")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed() ?: return@intercept null
                runCatching { injectRootEntry(result) }
                    .onFailure { Log.e(TAG, "Failed injecting SpotXero settings entry", it) }
                result
            }

        installShortcutHooks(module)
        PreferencesManager.resolveTargetContext()?.let { ensureLauncherShortcut(it) }

        Log.i(TAG, "Installed native InAppSettingsHook")
    }

    fun onActivityCreated(activity: Activity) {
        if (!isInstalled) {
            removeLauncherShortcut(activity)
            return
        }
        currentActivity = WeakReference(activity)
        ensureModuleResources(activity)
        ensureLauncherShortcut(activity)
        hookNewIntentOverride(activity.javaClass)
        rewriteDeepLinkIfNeeded(activity.intent)
    }

    fun onActivityResumed(activity: Activity) {
        if (!isInstalled) return
        currentActivity = WeakReference(activity)
        ensureModuleResources(activity)
    }

    fun onActivityPaused(activity: Activity) {
    }

    fun onActivityDestroyed(activity: Activity) {
        if (currentActivity?.get() == activity) {
            currentActivity = null
        }
    }

    private fun ensureModuleResources(context: Context) {
        if (moduleResources != null) return
        val modInfo = runCatching { moduleRef?.moduleApplicationInfo }.getOrNull() ?: return
        moduleResources = runCatching {
            context.packageManager.getResourcesForApplication(modInfo)
        }.getOrNull()
    }

    private fun str(resId: Int?, fallback: String, vararg args: Any): String {
        if (resId != null) {
            val res = moduleResources
            if (res != null) {
                runCatching {
                    return if (args.isEmpty()) res.getString(resId) else res.getString(resId, *args)
                }
            }
        }
        return fallback
    }

    private fun withActiveActivity(onActive: (Activity) -> Unit, onMissing: () -> Unit = {}) {
        val activity = currentActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            onMissing()
            return
        }
        onActive(activity)
    }

    private fun rewriteDeepLinkIfNeeded(intent: Intent?) {
        if (intent == null) return
        val data = intent.dataString
        val isShortcutIntent = runCatching { intent.getStringExtra(EXTRA_SHORTCUT) == SHORTCUT_ID }.getOrDefault(false)
        if (isShortcutIntent || data == SPOTXERO_URI || data?.startsWith("$SPOTXERO_URI?") == true) {
            markPendingSpotXeroRoute()
            intent.removeExtra(EXTRA_SHORTCUT)
            intent.data = Uri.parse(ABOUT_SETTINGS_URI)
        }
    }

    private fun installShortcutHooks(module: XposedModule) {
        runCatching {
            val setDynamic = ShortcutManager::class.java.getDeclaredMethod("setDynamicShortcuts", List::class.java)
            module.deoptimize(setDynamic)
            module.hook(setDynamic)
                .setId("inapp_settings_shortcut_set_dynamic")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!isInstalled || shortcutSyncing) return@intercept chain.proceed()
                    val sm = chain.thisObject as? ShortcutManager ?: return@intercept chain.proceed()
                    val incoming = (chain.args[0] as? List<*>)?.filterIsInstance<ShortcutInfo>() ?: return@intercept chain.proceed()
                    val ctx = currentActivity?.get() ?: PreferencesManager.resolveTargetContext()
                    val ours = ctx?.let { buildLauncherShortcut(it) } ?: return@intercept chain.proceed()
                    val maxDynamic = (sm.maxShortcutCountPerActivity - sm.manifestShortcuts.size).coerceAtLeast(0)
                    if (maxDynamic == 0) return@intercept chain.proceed()
                    val filtered = incoming.filter { it.id != SHORTCUT_ID }.take(maxDynamic - 1)
                    val modifiedArgs = chain.args.toTypedArray()
                    modifiedArgs[0] = listOf(ours) + filtered
                    chain.proceed(modifiedArgs)
                }

            val removeAll = ShortcutManager::class.java.getDeclaredMethod("removeAllDynamicShortcuts")
            module.deoptimize(removeAll)
            module.hook(removeAll)
                .setId("inapp_settings_shortcut_remove_all")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!isInstalled) return@intercept result
                    val ctx = currentActivity?.get() ?: PreferencesManager.resolveTargetContext()
                    if (ctx != null) {
                        shortcutRegistered = false
                        ensureLauncherShortcut(ctx)
                    }
                    result
                }
        }.onFailure {
            Log.w(TAG, "Failed installing ShortcutManager hooks", it)
        }
    }

    fun removeLauncherShortcut(context: Context) {
        if (isInstalled || shortcutRemoved) return
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        shortcutRemoved = true
        runCatching {
            if (sm.dynamicShortcuts.any { it.id == SHORTCUT_ID }) {
                sm.removeDynamicShortcuts(listOf(SHORTCUT_ID))
            }
        }.onFailure {
            Log.w(TAG, "Failed removing launcher shortcut", it)
        }
    }

    private fun ensureLauncherShortcut(context: Context) {
        if (!isInstalled || shortcutRegistered) return
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        val maxDynamic = (sm.maxShortcutCountPerActivity - sm.manifestShortcuts.size).coerceAtLeast(0)
        if (maxDynamic == 0) return
        val shortcut = buildLauncherShortcut(context) ?: return
        shortcutSyncing = true
        try {
            val currentDynamic = sm.dynamicShortcuts
            val hasOurs = currentDynamic.any { it.id == SHORTCUT_ID }
            if (hasOurs) {
                sm.updateShortcuts(listOf(shortcut))
            } else if (currentDynamic.size < maxDynamic) {
                sm.addDynamicShortcuts(listOf(shortcut))
            } else {
                sm.setDynamicShortcuts(listOf(shortcut) + currentDynamic.take(maxDynamic - 1))
            }
            shortcutRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed registering SpotXero launcher shortcut", t)
        } finally {
            shortcutSyncing = false
        }
    }

    private fun buildLauncherShortcut(context: Context): ShortcutInfo? {
        val launcherComponent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
            ?: return null
        val launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SPOTXERO_URI)).apply {
            setPackage(context.packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(EXTRA_SHORTCUT, SHORTCUT_ID)
        }
        return ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setActivity(launcherComponent)
            .setRank(0)
            .setShortLabel(str(R.string.app_name, "SpotXero"))
            .setLongLabel("SpotXero Settings")
            .setIcon(buildShortcutIcon(context))
            .setIntent(launchIntent)
            .build()
    }

    private fun buildShortcutIcon(context: Context): Icon {
        val sizePx = (108f * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val modRes = moduleResources
        val drawable = modRes?.let { runCatching { it.getDrawable(R.drawable.ic_spotxero, null) }.getOrNull() }
        if (drawable != null) {
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
        } else {
            canvas.drawColor(Color.parseColor("#121212"))
            val scale = sizePx / 108f
            canvas.save()
            canvas.translate(54f * scale, 54f * scale)
            canvas.rotate(8f)
            canvas.scale(0.49f * scale, 0.49f * scale)
            canvas.translate(-54f, -54f)
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1ED760")
                style = Paint.Style.STROKE
                strokeWidth = 7.5f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val p1 = Path().apply {
                moveTo(28f, 35f)
                cubicTo(42f, 42f, 66f, 50f, 80f, 57f)
            }
            val p2 = Path().apply {
                moveTo(28f, 57f)
                cubicTo(42f, 50f, 66f, 42f, 80f, 35f)
            }
            val dotPaint = Paint(strokePaint).apply {
                strokeWidth = 6f
            }
            val p3 = Path().apply {
                moveTo(41f, 75.5f)
                cubicTo(41f, 70.53f, 46.82f, 66.5f, 54f, 66.5f)
                cubicTo(61.18f, 66.5f, 67f, 70.53f, 67f, 75.5f)
                cubicTo(67f, 80.47f, 61.18f, 84.5f, 54f, 84.5f)
                cubicTo(46.82f, 84.5f, 41f, 80.47f, 41f, 75.5f)
                close()
            }
            canvas.drawPath(p1, strokePaint)
            canvas.drawPath(p2, strokePaint)
            canvas.drawPath(p3, dotPaint)
            canvas.restore()
        }
        return Icon.createWithAdaptiveBitmap(bitmap)
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
                        module.hook(method)
                            .setId("inapp_settings_new_intent_${type.name}")
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
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
        titleResolver = DexKitResolver.get<Method>(DexKitResolver.KEY_SETTINGS_TITLE_RESOLVER)
            ?: error("Missing settings title resolver")

        stringTitleCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_STRING_TITLE_CTOR)
        headerPropCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_HEADER_PROP_CTOR)
        iconLogoClass = DexKitResolver.get<Class<*>>(DexKitResolver.KEY_SETTINGS_ICON_LOGO_CLASS)
        vectorBuilderClass = DexKitResolver.get<Class<*>>(DexKitResolver.KEY_SETTINGS_VECTOR_BUILDER_CLASS)
        vectorPathCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_VECTOR_PATH_CTOR)
        moveToCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_PATH_MOVETO_CTOR)
        curveToCtor = DexKitResolver.get<Constructor<*>>(DexKitResolver.KEY_SETTINGS_PATH_CURVETO_CTOR)
        pathCloseInstance = DexKitResolver.get<Class<*>>(DexKitResolver.KEY_SETTINGS_PATH_CLOSE_CLASS)
            ?.declaredFields
            ?.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == it.declaringClass }
            ?.apply { isAccessible = true }
            ?.get(null)

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

        val propInterface = headerPropCtor?.declaringClass?.interfaces?.singleOrNull()
        if (propInterface != null) {
            val pageHostClass = pageHostCtor.declaringClass
            val propsField = pageHostClass.declaredFields.firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) &&
                    field.declaredAnnotations.isEmpty() &&
                    field.type.declaredMethods.any { m ->
                        !Modifier.isStatic(m.modifiers) && m.parameterCount == 1 && m.parameterTypes[0] == propInterface && m.returnType == Void.TYPE
                    }
            }?.apply { isAccessible = true }
            hostPropsField = propsField
            hostPropsUpdateMethod = propsField?.type?.declaredMethods?.firstOrNull { m ->
                !Modifier.isStatic(m.modifiers) && m.parameterCount == 1 && m.parameterTypes[0] == propInterface && m.returnType == Void.TYPE
            }
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

    private fun extractLiveSectionMask(templateModel: Any, headerless: Boolean): Int {
        val sections = modelSectionsField.get(templateModel) as? List<*> ?: emptyList<Any>()
        val sectionClass = sectionCtor.declaringClass
        val titleField = sectionClass.declaredFields.firstOrNull { !Modifier.isStatic(it.modifiers) && it.type == Integer::class.java }
            ?.apply { isAccessible = true }
        val maskField = sectionClass.declaredFields.lastOrNull { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            ?.apply { isAccessible = true }
        if (titleField != null && maskField != null) {
            val match = sections.filterNotNull().firstOrNull { sec ->
                val hasNoTitle = titleField.get(sec) == null
                hasNoTitle == headerless
            }
            if (match != null) {
                return runCatching { maskField.getInt(match) }.getOrElse {
                    if (headerless) ND71_HEADERLESS_MASK else ND71_TITLED_MASK
                }
            }
        }
        return if (headerless) ND71_HEADERLESS_MASK else ND71_TITLED_MASK
    }

    private fun extractLiveItemMask(templateModel: Any): Int {
        val sections = modelSectionsField.get(templateModel) as? List<*> ?: emptyList<Any>()
        val toggleClass = toggleItemCtor.declaringClass
        val maskField = toggleClass.declaredFields.lastOrNull { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            ?.apply { isAccessible = true } ?: return L2C1_DEFAULT_MASK
        for (section in sections) {
            if (section == null) continue
            val items = sectionItemsField.get(section) as? List<*> ?: continue
            val sample = items.firstOrNull { toggleClass.isInstance(it) } ?: continue
            return runCatching { maskField.getInt(sample) }.getOrDefault(L2C1_DEFAULT_MASK)
        }
        return L2C1_DEFAULT_MASK
    }

    private fun buildSpotXeroPageModel(templateModel: Any): Any {
        val pageGroup = modelGroupField.get(templateModel)
        val headerPlaceholder = modelHeaderField.get(templateModel)
        val itemGroup = cachedItemGroup ?: extractItemGroup(templateModel)
            ?: error("Unable to resolve native settings item group")
        cachedItemGroup = itemGroup

        val headerlessMask = extractLiveSectionMask(templateModel, headerless = true)
        val titledMask = extractLiveSectionMask(templateModel, headerless = false)
        val itemMask = extractLiveItemMask(templateModel)

        activeToggleBindings.clear()
        activeVisibilityBindings.clear()

        val standardSections = SPOTXERO_SECTIONS.map { section ->
            val rows = buildList {
                for (toggle in section.toggles) {
                    add(createToggleItem(toggle, itemGroup, itemMask))
                    if (toggle.id == section.seekDurationAfterToggleId) {
                        add(createSeekDurationItem(itemGroup, itemMask))
                    }
                }
                if (section.includeLyricsDialogs) {
                    add(createLyricsProvidersItem(itemGroup, itemMask))
                    add(createAiTranslationItem(itemGroup, itemMask))
                }
            }
            val persistentItems = listFactory.invoke(null, rows.toTypedArray())
            sectionCtor.newInstance(
                pageGroup,
                section.syntheticTitleId,
                null,
                null,
                persistentItems,
                if (section.syntheticTitleId == null) headerlessMask else titledMask
            )
        }

        val allSections = buildList {
            addAll(standardSections)
            val footerRows = buildList {
                val installedCount = HookRegistry.installedHookNames().size
                val failedCount = HookRegistry.failedHookNames().size
                val versionSubtitle = if (failedCount == 0) {
                    "${BuildConfig.VERSION_NAME} • $installedCount hooks active"
                } else {
                    "${BuildConfig.VERSION_NAME} • $failedCount failed"
                }
                add(
                    createActionItem(
                        id = "spotxero_version",
                        title = "SpotXero Diagnostics",
                        subtitle = versionSubtitle,
                        uri = privateRoute("diagnostics"),
                        itemGroup = itemGroup,
                        itemMask = itemMask
                    )
                )
                add(createExternalLinkItem("spotxero_github", "GitHub", "https://github.com/s1ddhants1/SpotXero", itemGroup, itemMask))
                add(createExternalLinkItem("spotxero_telegram", "Telegram Group", "https://t.me/SpotXero", itemGroup, itemMask))
                add(createActionItem("spotxero_restart", "Restart Spotify", "Apply changes that need a fresh session", privateRoute("restart"), itemGroup, itemMask))
            }
            val footerItems = listFactory.invoke(null, footerRows.toTypedArray())
            add(sectionCtor.newInstance(pageGroup, null, null, null, footerItems, headerlessMask))
        }
        Log.i(TAG, "Built SpotXero page model with ${allSections.size} sections")

        return modelCtor.newInstance(pageGroup, headerPlaceholder, null, allSections, emptyList<Any>())
    }

    private fun isRowVisible(dependsOnKey: String?, isMaster: Boolean = false): Boolean {
        if (isMaster) return true
        if (!PreferencesManager.isStoredEnabled(PrefKeys.KEY_MASTER_ENABLED, true)) return false
        return when (dependsOnKey) {
            null -> true
            PrefKeys.KEY_DOUBLE_TAP_TO_SEEK -> PreferencesManager.isStoredEnabled(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK, true)
            PrefKeys.KEY_EXTERNAL_LYRICS -> PreferencesManager.isStoredEnabled(PrefKeys.KEY_EXTERNAL_LYRICS, false)
            else -> PreferencesManager.isStoredEnabled(dependsOnKey, false)
        }
    }

    private fun createToggleItem(spec: ToggleSpec, itemGroup: Any, itemMask: Int): Any {
        val initial = PreferencesManager.isStoredEnabled(spec.key, spec.defaultValue)
        val stateFlow = stateFlowCtor.newInstance(initial)
        val isMaster = spec.key == PrefKeys.KEY_MASTER_ENABLED
        val visibilityFlow = stateFlowCtor.newInstance(isRowVisible(spec.dependsOnKey, isMaster))
        activeToggleBindings.add(ToggleBinding(spec, stateFlow, visibilityFlow))

        val callbackProxy = Proxy.newProxyInstance(
            toggleCallbackInterface.classLoader,
            arrayOf(toggleCallbackInterface)
        ) { proxy, method, args ->
            when {
                method.parameterCount == 3 -> {
                    val newValue = args?.get(1) as? Boolean
                    if (newValue != null) {
                        val activity = currentActivity?.get()
                        PreferencesManager.setBoolean(activity, spec.key, newValue)
                        stateFlowEmit.invoke(stateFlow, newValue)
                        syncToggleFlows()
                        if (spec.requiresRestart && activity != null) {
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Restart Spotify to apply changes", Toast.LENGTH_SHORT).show()
                            }
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
            str(spec.titleRes, spec.fallbackTitle),
            null,
            null,
            null,
            null,
            visibilityFlow,
            itemGroup,
            null,
            control,
            emptyMap<Any, Any>(),
            itemMask
        )
        val subtitle = spec.fallbackSubtitle?.let { str(spec.subtitleRes, it) }
        subtitle?.let { text -> toggleItemSubtitleField.set(item, createSubtitleProvider { text }) }
        return item
    }

    private fun createActionItem(
        id: String,
        title: String,
        subtitle: String,
        uri: String,
        itemGroup: Any,
        itemMask: Int,
        visibilityFlow: Any = stateFlowCtor.newInstance(java.lang.Boolean.TRUE)
    ): Any = createActionItemWithSupplier(id, title, { subtitle }, uri, itemGroup, itemMask, visibilityFlow)

    private fun createActionItemWithSupplier(
        id: String,
        title: String,
        subtitleSupplier: () -> String,
        uri: String,
        itemGroup: Any,
        itemMask: Int,
        visibilityFlow: Any = stateFlowCtor.newInstance(java.lang.Boolean.TRUE)
    ): Any {
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
            itemMask
        )
        toggleItemSubtitleField.set(item, createSubtitleProvider(subtitleSupplier))
        return item
    }

    private fun createSeekDurationItem(itemGroup: Any, itemMask: Int): Any {
        val visibilityFlow = stateFlowCtor.newInstance(isRowVisible(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK))
        activeVisibilityBindings.add(
            VisibilityBinding(visibilityFlow) { isRowVisible(PrefKeys.KEY_DOUBLE_TAP_TO_SEEK) }
        )
        return createActionItemWithSupplier(
            id = "spotxero_seek_duration",
            title = str(R.string.seek_duration, "Seek duration"),
            subtitleSupplier = {
                val secs = InAppSettingsSheets.seekDurationSeconds()
                str(R.string.settings_seconds_value, "$secs seconds", secs)
            },
            uri = privateRoute("seek-duration"),
            itemGroup = itemGroup,
            itemMask = itemMask,
            visibilityFlow = visibilityFlow
        )
    }

    private fun createLyricsProvidersItem(itemGroup: Any, itemMask: Int): Any {
        val visibilityFlow = stateFlowCtor.newInstance(isRowVisible(PrefKeys.KEY_EXTERNAL_LYRICS))
        activeVisibilityBindings.add(
            VisibilityBinding(visibilityFlow) { isRowVisible(PrefKeys.KEY_EXTERNAL_LYRICS) }
        )
        return createActionItem(
            id = "spotxero_lyrics_providers",
            title = str(R.string.feature_external_lyrics_title, "Third-Party Lyrics"),
            subtitle = str(R.string.feature_external_lyrics_desc, "Providers and fallback priority"),
            uri = privateRoute("lyrics-providers"),
            itemGroup = itemGroup,
            itemMask = itemMask,
            visibilityFlow = visibilityFlow
        )
    }

    private fun createAiTranslationItem(itemGroup: Any, itemMask: Int): Any {
        val visibilityFlow = stateFlowCtor.newInstance(isRowVisible(null))
        activeVisibilityBindings.add(
            VisibilityBinding(visibilityFlow) { isRowVisible(null) }
        )
        return createActionItem(
            id = "spotxero_ai_translation",
            title = str(R.string.title_ai_lyrics_translation, "AI Lyrics Translation"),
            subtitle = str(R.string.ai_lyrics_translation_desc, "Translation languages, providers and models"),
            uri = privateRoute("ai-translation"),
            itemGroup = itemGroup,
            itemMask = itemMask,
            visibilityFlow = visibilityFlow
        )
    }

    private fun createExternalLinkItem(
        id: String,
        title: String,
        url: String,
        itemGroup: Any,
        itemMask: Int
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
            itemMask
        )
    }

    private fun cycleSeekDuration() {
        val current = InAppSettingsSheets.seekDurationSeconds()
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

    private fun syncToggleFlows() {
        for (binding in activeToggleBindings) {
            runCatching {
                stateFlowEmit.invoke(
                    binding.valueFlow,
                    PreferencesManager.isStoredEnabled(binding.spec.key, binding.spec.defaultValue)
                )
                stateFlowEmit.invoke(
                    binding.visibilityFlow,
                    isRowVisible(binding.spec.dependsOnKey, binding.spec.key == PrefKeys.KEY_MASTER_ENABLED)
                )
            }
        }
        for (binding in activeVisibilityBindings) {
            runCatching {
                stateFlowEmit.invoke(binding.visibilityFlow, binding.isVisible())
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
            .onFailure { Log.w(TAG, "Using fallback SpotXero row icon: ${it.message}") }
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

    private fun buildSpotXeroRowIcon(r2yClass: Class<*>): Any? {
        spotxeroIconCache?.let { return it }
        val logoClass = iconLogoClass ?: return null
        val builderClass = vectorBuilderClass ?: return null
        val pathCtor = vectorPathCtor ?: return null
        val moveCtor = moveToCtor ?: return null
        val curveCtor = curveToCtor ?: return null
        val closeInstance = pathCloseInstance ?: return null

        val n0yFields = r2yClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive
        }.onEach { it.isAccessible = true }
        val n0yClass = n0yFields.first().type
        val n0yCtor = n0yClass.declaredConstructors.first {
            it.parameterCount == 2 && it.parameterTypes[0] == it.parameterTypes[1]
        }
        val oc80Class = n0yCtor.parameterTypes[0]

        val templateLogo = logoClass.declaredFields.first {
            Modifier.isStatic(it.modifiers) && it.type == logoClass
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
            pathCtor.declaringClass.isInstance(it)
        }!!
        val brushType = pathCtor.parameterTypes[3]
        val blackBrush = pathCtor.declaringClass.declaredFields.firstNotNullOf { f ->
            if (f.type == brushType) f.apply { isAccessible = true }.get(templatePath) else null
        }

        val vec16 = buildSpotXeroVector(16f, "SpotXero.Vector.Logo16", blackBrush, oc80Class, builderClass, pathCtor, moveCtor, curveCtor, closeInstance)
        val vec24 = buildSpotXeroVector(24f, "SpotXero.Vector.Logo24", blackBrush, oc80Class, builderClass, pathCtor, moveCtor, curveCtor, closeInstance)
        val n0yPair = n0yCtor.newInstance(vec16, vec24)

        val icon = allocateInstance(logoClass)
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
        oc80Class: Class<*>,
        builderClass: Class<*>,
        pathCtor: Constructor<*>,
        moveCtor: Constructor<*>,
        curveCtor: Constructor<*>,
        closeInstance: Any
    ): Any {
        val builderCtor = builderClass.declaredConstructors.first { c ->
            val p = c.parameterTypes
            p.size == 9 && p[0] == String::class.java && p[1] == Float::class.javaPrimitiveType
        }
        val buildMethod = builderClass.declaredMethods.first {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == oc80Class
        }
        val stackField = builderClass.declaredFields.first {
            !Modifier.isStatic(it.modifiers) && ArrayList::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val groupClass = builderClass.declaredFields.first { f ->
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
            pathCtor.newInstance(
                "",
                arrayListOf(
                    moveCtor.newInstance(28f, 35f),
                    curveCtor.newInstance(42f, 42f, 66f, 50f, 80f, 57f)
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
            pathCtor.newInstance(
                "",
                arrayListOf(
                    moveCtor.newInstance(28f, 57f),
                    curveCtor.newInstance(42f, 50f, 66f, 42f, 80f, 35f)
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
            pathCtor.newInstance(
                "",
                arrayListOf(
                    moveCtor.newInstance(41f, 75.5f),
                    curveCtor.newInstance(41f, 70.53f, 46.82f, 66.5f, 54f, 66.5f),
                    curveCtor.newInstance(61.18f, 66.5f, 67f, 70.53f, 67f, 75.5f),
                    curveCtor.newInstance(67f, 80.47f, 61.18f, 84.5f, 54f, 84.5f),
                    curveCtor.newInstance(46.82f, 84.5f, 41f, 80.47f, 41f, 75.5f),
                    closeInstance
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
