package app.spotxero.hooks.attestation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import app.spotxero.core.DexKitResolver
import app.spotxero.utils.core.CommonUtils
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap

object PackageSignatureHook {

    private const val SPOTIFY_SIGNATURE_HEX =
        "308203773082025fa003020102020465cac4b1300d06092a864886f70d01010b0500306b310b3009060355040613025345311230100603550408130953746f636b686f6c6d311230100603550407130953746f636b686f6c6d3110300e060355040a130753706f746966793110300e060355040b1307416e64726f69643110300e0603550403130753706f746966793020170d3134303532343136353835375a180f32323838303330383136353835375a306b310b3009060355040613025345311230100603550408130953746f636b686f6c6d311230100603550407130953746f636b686f6c6d3110300e060355040a130753706f746966793110300e060355040b1307416e64726f69643110300e0603550403130753706f7469667930820122300d06092a864886f70d01010105000382010f003082010a028201010095fc6225772d8ad5f9f98d31a68fcd135ad75e26f1f1294305464bc57a066dd37492e91010253e74af7056be9b2651fc7aeda23c350fd2d6fd2c5fdff71f8b738daa97599aba27d9fd63a1d04829207adac5077ca61beea744de85bb51eb42b685fa19f75bb779c123603a7abb1c4cd7405c06c6e0ebe65504b489e04899ddfd7e3d287fb78aa00f71a21947d7b72087dadc9a1bdec55b9068e853efd25935f0e888a2251ea8bac7c2ce1ee65c6e4a7726c24799c01d0d0d8bcfa1d38024f40697d46f5a4979cd7a7411210aff6ac0729f2ce5742fa342f295389a4cdd0fe6875638f4baf42a4dacef5892bf57b52431ce0f01c666e64344088c27898e9b544d0203010001a321301f301d0603551d0e0416041431e834bd66fead7b26e5f2e6a5c0d96986856eee300d06092a864886f70d01010b050003820101001c6aa13d129f6b93aabdfb107be13ac80d8cef2f80bde2df8357d50c32731118983ce1e521fc61f6436d5401dace36f2ea2c7284a28f35cf588433b268a9dfd9dd5ea756a9619def03b4a640964f4c1ce789f044fc04614eb20d3c85650631991c87e1e1c46abf1777cbf5ea1a3872b9d2012af38ef737d125db93ef154c84c717d76e8c8862225a3a46bdf62f2657d69d17b887c7a9022c54c640342663518f10130ec3b9795773ca27a59bb9edb9fcd93f5f41bb9ef08daf2c64fbb221c185e36741ba1447f802e94276c7cda0e363f859694a549fffffe77e31c17159e300feb54accafe9c591b1149e0c6ccd2860958f1819d3179731d24f3de3f4934803"

    private const val SPOTIFY_SIGNATURE_SHA1 = "d6a6dced4a85f24204bf9505ccc1fce114cadb32"
    private const val PLAY_STORE_INSTALLER = "com.android.packageinstaller"
    private const val PLAY_STORE_REFERRER = "utm_medium=organic&utm_source=google-play"

    private val defaultLauncherUri: String by lazy {
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            "android-app://com.sec.android.app.launcher"
        } else {
            "android-app://com.android.launcher"
        }
    }

    private val HIDDEN_PACKAGES = setOf(
        "org.lsposed.lspatch",
        "org.lsposed.manager",
        "org.lsposed.jingmatrix",
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "io.github.huskydg.magisk",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "me.bmax.apatch",
        "org.frknkrc44.hma_oss",
        "com.tsng.hidemyapplist",
        "io.github.s1ddhants1.spotxero",
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.kingroot.kinguser",
        "com.kingo.root",
        "com.smedialink.oneclickroot",
        "com.zhiqupk.root.global",
        "com.alephzain.framaroot",
        "com.koushikdutta.rommanager",
        "com.koushikdutta.rommanager.license",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.ramdroid.appquarantine",
        "com.ramdroid.appquarantinepro",
        "com.android.vending.billing.inappbillingservice.coin",
        "com.android.vending.billing.inappbillingservice.luck",
        "com.chelpus.luckypatcher",
        "com.blackmartalpha",
        "org.blackmart.market",
        "com.allinone.free",
        "com.repodroid.app",
        "org.creeplays.hack",
        "com.baseappfull.fwd",
        "com.zmapp",
        "com.dv.marketmod.installer",
        "org.mobilism.android",
        "com.android.wp.net.log",
        "com.android.camera.update",
        "cc.madkite.freedom",
        "com.solohsu.android.edxp.manager",
        "org.meowcat.edxposed.manager",
        "com.xmodgame",
        "com.cih.game_cih",
        "com.charles.lpoqasert",
        "catch_.me_.if_.you_.can_",
        "com.devadvance.rootcloak",
        "com.devadvance.rootcloakplus",
        "de.robv.android.xposed.installer",
        "com.saurik.substrate",
        "com.zachspong.temprootremovejb",
        "com.amphoras.hidemyroot",
        "com.amphoras.hidemyrootadfree",
        "com.formyhm.hiderootpremium",
        "com.formyhm.hideroot"
    )

    private val cachedSignature: Signature by lazy { Signature(SPOTIFY_SIGNATURE_HEX) }
    private val cachedSignatureSha256: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(cachedSignature.toByteArray())
    }
    private val cachedSignatureSha1: ByteArray by lazy {
        MessageDigest.getInstance("SHA-1").digest(cachedSignature.toByteArray())
    }

    private val spotifySigningInfoSet = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val spotifyInstallSourceInfoSet = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    @Volatile
    private var realInstalledSignature: Signature? = null

    @Volatile
    private var canStripMetadata = false

    @Volatile
    private var cachedOriginApkPath: String? = null

    @Volatile
    private var isProxyInstalled = false

    @Volatile
    private var isFrameworkHooked = false

    @Volatile
    private var isAppSignaturesHooked = false

    @Volatile
    private var isInstallerHooked = false

    @Volatile
    private var isRavelinHooked = false

    fun markBootstrapComplete() {
        canStripMetadata = true
    }

    fun install(module: XposedModule, context: Context? = null) {
        if (context != null) {
            resolveOriginApkPath(context)
        }
        installPackageManagerProxy(context)
        if (!isFrameworkHooked) {
            hookPackageManager(module)
            hookOriginApkPaths(module)
            hookInstallSourceInfo(module)
            hookSigningInfo(module)
            hookActivityReferrer(module)
            hookIntentReferrer(module)
            hookSharedPreferences(module)
            isFrameworkHooked = true
        }
        if (!isAppSignaturesHooked) {
            hookAppSignaturesMethod(module)
        }
        if (!isInstallerHooked) {
            hookInstallerPackageMethod(module)
        }
        if (!isRavelinHooked) {
            hookRavelin(module)
        }
    }

    fun installPackageManagerProxy(context: Context? = null) {
        if (!isProxyInstalled) {
            runCatching {
                val atClass = Class.forName("android.app.ActivityThread")
                val getPkgManagerMethod = atClass.getDeclaredMethod("getPackageManager").apply { isAccessible = true }
                val realPM = getPkgManagerMethod.invoke(null) ?: return@runCatching
                val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager")
                val proxy = Proxy.newProxyInstance(
                    iPackageManagerClass.classLoader,
                    arrayOf(iPackageManagerClass),
                    PackageManagerInvocationHandler(realPM)
                )
                val sPackageManagerField = atClass.getDeclaredField("sPackageManager").apply { isAccessible = true }
                sPackageManagerField.set(null, proxy)

                val app = runCatching {
                    atClass.getDeclaredMethod("currentApplication").invoke(null) as? Application
                }.getOrNull()
                app?.let { applyProxyToContext(it) }
                isProxyInstalled = true
            }
        }
        context?.let { applyProxyToContext(it) }
    }

    fun applyProxyToContext(context: Context) {
        runCatching {
            val pm = context.packageManager ?: return
            val atClass = Class.forName("android.app.ActivityThread")
            val sPackageManagerField = atClass.getDeclaredField("sPackageManager").apply { isAccessible = true }
            val proxy = sPackageManagerField.get(null) ?: return
            val mPMField = pm.javaClass.getDeclaredField("mPM").apply { isAccessible = true }
            mPMField.set(pm, proxy)
        }
    }

    private class PackageManagerInvocationHandler(private val base: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val safeArgs = args ?: emptyArray()
            return when (method.name) {
                "getInstallerPackageName" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    if (isTargetPackage(pkgName)) {
                        PLAY_STORE_INSTALLER
                    } else {
                        val real = invokeBase(method, safeArgs)
                        if (real is String && isLspatchString(real)) PLAY_STORE_INSTALLER else real
                    }
                }
                "getInstallSourceInfo" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    val real = invokeBase(method, safeArgs)
                    if (isTargetPackage(pkgName) || (real != null && isLspatchInstallSource(real))) {
                        createSpoofedInstallSourceInfo(real)
                    } else {
                        real
                    }
                }
                "hasSigningCertificate" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    val cert = safeArgs.getOrNull(1) as? ByteArray
                    if (isTargetPackage(pkgName)) matchesSpotifyCert(cert) else invokeBase(method, safeArgs)
                }
                "hasUidSigningCertificate" -> {
                    val uid = safeArgs.getOrNull(0) as? Int
                    val cert = safeArgs.getOrNull(1) as? ByteArray
                    if (uid == android.os.Process.myUid()) matchesSpotifyCert(cert) else invokeBase(method, safeArgs)
                }
                "checkSignatures" -> {
                    val pkg1 = safeArgs.getOrNull(0) as? String
                    val pkg2 = safeArgs.getOrNull(1) as? String
                    if (isTargetPackage(pkg1) && isTargetPackage(pkg2)) {
                        PackageManager.SIGNATURE_MATCH
                    } else {
                        invokeBase(method, safeArgs)
                    }
                }
                "checkUidSignatures" -> {
                    val uid1 = safeArgs.getOrNull(0) as? Int
                    val uid2 = safeArgs.getOrNull(1) as? Int
                    val myUid = android.os.Process.myUid()
                    if (uid1 == myUid && uid2 == myUid) {
                        PackageManager.SIGNATURE_MATCH
                    } else {
                        invokeBase(method, safeArgs)
                    }
                }
                "getPackageInfo" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    if (isForbiddenPackage(pkgName)) return null
                    (invokeBase(method, safeArgs) as? PackageInfo)?.also { patchPackageInfo(pkgName, it) }
                }
                "getApplicationInfo" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    if (isForbiddenPackage(pkgName)) return null
                    (invokeBase(method, safeArgs) as? ApplicationInfo)?.also { sanitizeApplicationInfo(pkgName, it) }
                }
                "getInstalledPackages" -> filterParceledListSlice(invokeBase(method, safeArgs), true)
                "getInstalledApplications" -> filterParceledListSlice(invokeBase(method, safeArgs), false)
                "equals" -> safeArgs.getOrNull(0) === proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> base.toString()
                else -> invokeBase(method, safeArgs)
            }
        }

        private fun invokeBase(method: Method, args: Array<out Any?>): Any? {
            return try {
                method.invoke(base, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
    }

    private fun isForbiddenPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val lower = pkg.lowercase()
        return HIDDEN_PACKAGES.contains(lower) ||
            lower.contains("lspatch") ||
            lower.contains("lsposed") ||
            lower.contains("spotxero")
    }

    private fun isLspatchString(value: String?): Boolean {
        if (value == null) return false
        val lower = value.lowercase()
        return lower.contains("lspatch") || lower.contains("lsposed") || lower.contains("spotxero")
    }

    private fun isLspatchInstallSource(info: Any): Boolean {
        if (info !is InstallSourceInfo) return false
        val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { info.updateOwnerPackageName }.getOrNull()
        } else null
        return isLspatchString(info.installingPackageName) ||
            isLspatchString(info.initiatingPackageName) ||
            isLspatchString(info.originatingPackageName) ||
            isLspatchString(owner)
    }

    private fun createSpoofedInstallSourceInfo(real: Any?): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val isiClass = Class.forName("android.content.pm.InstallSourceInfo")
            val initSigningInfo = (real as? InstallSourceInfo)?.initiatingPackageSigningInfo

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val ctor6 = runCatching {
                    isiClass.getConstructor(
                        String::class.java,
                        SigningInfo::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                }.getOrNull()
                if (ctor6 != null) {
                    return@runCatching ctor6.newInstance(
                        PLAY_STORE_INSTALLER,
                        initSigningInfo,
                        null,
                        PLAY_STORE_INSTALLER,
                        PLAY_STORE_INSTALLER,
                        0
                    )
                }
            }

            val ctor4 = runCatching {
                isiClass.getConstructor(
                    String::class.java,
                    SigningInfo::class.java,
                    String::class.java,
                    String::class.java
                )
            }.getOrNull()
            ctor4?.newInstance(PLAY_STORE_INSTALLER, initSigningInfo, null, PLAY_STORE_INSTALLER)
                ?: real?.also { patchInstallSourceInfo(it) }
        }.getOrElse {
            real?.also { patchInstallSourceInfo(it) }
        }
    }

    private fun filterParceledListSlice(slice: Any?, isPackageInfo: Boolean): Any? {
        if (slice == null) return null
        return runCatching {
            val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: return slice
            val filteredList = ArrayList<Any?>(list.size)
            for (item in list) {
                when {
                    item == null -> continue
                    isPackageInfo && item is PackageInfo -> if (!isForbiddenPackage(item.packageName)) {
                        patchPackageInfo(item.packageName, item)
                        filteredList.add(item)
                    }
                    !isPackageInfo && item is ApplicationInfo -> if (!isForbiddenPackage(item.packageName)) {
                        sanitizeApplicationInfo(item.packageName, item)
                        filteredList.add(item)
                    }
                    else -> filteredList.add(item)
                }
            }
            slice.javaClass.getConstructor(List::class.java).newInstance(filteredList)
        }.getOrElse { slice }
    }

    private fun hookPackageManager(module: XposedModule) {
        val pmClass = runCatching { Class.forName("android.app.ApplicationPackageManager") }.getOrNull() ?: return

        fun hookInfoLookup(method: Method?, id: String, isPackageInfo: Boolean) {
            if (method == null) return
            module.deoptimize(method)
            module.hook(method)
                .setId(id)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkgName = chain.getArg(0) as? String
                    if (isForbiddenPackage(pkgName)) {
                        throw PackageManager.NameNotFoundException("Package not found: $pkgName")
                    }
                    val result = chain.proceed()
                    if (isPackageInfo) {
                        patchPackageInfo(pkgName, result as? PackageInfo)
                    } else {
                        sanitizeApplicationInfo(pkgName, result as? ApplicationInfo)
                    }
                    result
                }
        }

        fun hookInstalledList(method: Method?, id: String, isPackageInfo: Boolean) {
            if (method == null) return
            module.deoptimize(method)
            module.hook(method)
                .setId(id)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val raw = chain.proceed()
                    val list = raw as? List<*> ?: return@intercept raw
                    if (isPackageInfo) {
                        list.filterIsInstance<PackageInfo>()
                            .filter { !isForbiddenPackage(it.packageName) }
                            .onEach { patchPackageInfo(it.packageName, it) }
                    } else {
                        list.filterIsInstance<ApplicationInfo>()
                            .filter { !isForbiddenPackage(it.packageName) }
                            .onEach { sanitizeApplicationInfo(it.packageName, it) }
                    }
                }
        }

        hookInfoLookup(
            pmClass.declaredMethods.firstOrNull {
                it.name == "getPackageInfo" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            },
            "pm_get_package_info_int",
            isPackageInfo = true
        )
        hookInfoLookup(
            pmClass.declaredMethods.firstOrNull {
                it.name == "getApplicationInfo" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            },
            "pm_get_application_info_int",
            isPackageInfo = false
        )
        hookInstalledList(
            pmClass.declaredMethods.firstOrNull {
                it.name == "getInstalledPackages" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            },
            "pm_get_installed_packages",
            isPackageInfo = true
        )
        hookInstalledList(
            pmClass.declaredMethods.firstOrNull {
                it.name == "getInstalledApplications" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            },
            "pm_get_installed_applications",
            isPackageInfo = false
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hookInfoLookup(
                pmClass.declaredMethods.firstOrNull {
                    it.name == "getPackageInfo" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1].name == "android.content.pm.PackageManager\$PackageInfoFlags"
                },
                "pm_get_package_info_flags",
                isPackageInfo = true
            )
            hookInfoLookup(
                pmClass.declaredMethods.firstOrNull {
                    it.name == "getApplicationInfo" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1].name == "android.content.pm.PackageManager\$ApplicationInfoFlags"
                },
                "pm_get_application_info_flags",
                isPackageInfo = false
            )
            hookInstalledList(
                pmClass.declaredMethods.firstOrNull {
                    it.name == "getInstalledPackages" && it.parameterCount == 1 &&
                        it.parameterTypes[0].name == "android.content.pm.PackageManager\$PackageInfoFlags"
                },
                "pm_get_installed_packages_flags",
                isPackageInfo = true
            )
            hookInstalledList(
                pmClass.declaredMethods.firstOrNull {
                    it.name == "getInstalledApplications" && it.parameterCount == 1 &&
                        it.parameterTypes[0].name == "android.content.pm.PackageManager\$ApplicationInfoFlags"
                },
                "pm_get_installed_applications_flags",
                isPackageInfo = false
            )
        }

        pmClass.declaredMethods.firstOrNull {
            it.name == "getInstallerPackageName" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }?.let { method ->
            module.deoptimize(method)
            module.hook(method)
                .setId("pm_get_installer_package_name")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkgName = chain.getArg(0) as? String
                    if (isTargetPackage(pkgName)) {
                        PLAY_STORE_INSTALLER
                    } else {
                        val result = chain.proceed()
                        if (result is String && isLspatchString(result)) PLAY_STORE_INSTALLER else result
                    }
                }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pmClass.declaredMethods.firstOrNull {
                it.name == "getInstallSourceInfo" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }?.let { method ->
                module.deoptimize(method)
                module.hook(method)
                    .setId("pm_get_install_source_info")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkgName = chain.getArg(0) as? String
                        if (isForbiddenPackage(pkgName)) {
                            throw PackageManager.NameNotFoundException("Package not found: $pkgName")
                        }
                        val result = chain.proceed()
                        if (result != null && (isTargetPackage(pkgName) || isLspatchInstallSource(result))) {
                            patchInstallSourceInfo(result)
                            spotifyInstallSourceInfoSet.add(result)
                        }
                        result
                    }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pmClass.declaredMethods.firstOrNull {
                it.name == "hasSigningCertificate" && it.parameterCount == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == ByteArray::class.java &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }?.let { method ->
                module.deoptimize(method)
                module.hook(method)
                    .setId("pm_has_signing_certificate")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if (isTargetPackage(chain.getArg(0) as? String)) {
                            matchesSpotifyCert(chain.getArg(1) as? ByteArray)
                        } else {
                            chain.proceed()
                        }
                    }
            }

            pmClass.declaredMethods.firstOrNull {
                it.name == "hasSigningCertificate" && it.parameterCount == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == ByteArray::class.java &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }?.let { method ->
                module.deoptimize(method)
                module.hook(method)
                    .setId("pm_has_signing_certificate_uid")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        if ((chain.getArg(0) as? Int) == android.os.Process.myUid()) {
                            matchesSpotifyCert(chain.getArg(1) as? ByteArray)
                        } else {
                            chain.proceed()
                        }
                    }
            }
        }

        pmClass.declaredMethods.firstOrNull {
            it.name == "checkSignatures" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java
        }?.let { method ->
            module.deoptimize(method)
            module.hook(method)
                .setId("pm_check_signatures_pkg")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (isTargetPackage(chain.getArg(0) as? String) && isTargetPackage(chain.getArg(1) as? String)) {
                        PackageManager.SIGNATURE_MATCH
                    } else {
                        chain.proceed()
                    }
                }
        }

        pmClass.declaredMethods.firstOrNull {
            it.name == "checkSignatures" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType && it.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.let { method ->
            module.deoptimize(method)
            module.hook(method)
                .setId("pm_check_signatures_uid")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val myUid = android.os.Process.myUid()
                    if ((chain.getArg(0) as? Int) == myUid && (chain.getArg(1) as? Int) == myUid) {
                        PackageManager.SIGNATURE_MATCH
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookOriginApkPaths(module: XposedModule) {
        val targetClasses = listOfNotNull(
            ContextWrapper::class.java,
            runCatching { Class.forName("android.app.ContextImpl") }.getOrNull()
        )
        for (ctxClass in targetClasses) {
            val tag = if (ctxClass == ContextWrapper::class.java) "cw" else "ci"
            for (methodName in listOf("getPackageCodePath", "getPackageResourcePath")) {
                val method = ctxClass.declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0 && it.returnType == String::class.java
                } ?: continue
                module.deoptimize(method)
                module.hook(method)
                    .setId("origin_${tag}_$methodName")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed() as? String
                        val ctx = chain.getThisObject() as? Context
                        if (ctx != null && ctx.packageName == CommonUtils.SPOTIFY_PACKAGE_NAME) {
                            val originPath = resolveOriginApkPath(ctx)
                            if (originPath != null && File(originPath).isFile) {
                                return@intercept originPath
                            }
                        }
                        result
                    }
            }
        }
    }

    private fun hookInstallSourceInfo(module: XposedModule) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val clazz = runCatching { Class.forName("android.content.pm.InstallSourceInfo") }.getOrNull() ?: return

        fun hookPackageGetter(methodName: String, hookId: String, spoofValue: String?) {
            val method = clazz.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return
            module.deoptimize(method)
            module.hook(method)
                .setId(hookId)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifyInstallSourceInfoSet.contains(target)) {
                        spoofValue
                    } else {
                        val result = chain.proceed()
                        if (result is String && isLspatchString(result)) {
                            target?.let {
                                patchInstallSourceInfo(it)
                                spotifyInstallSourceInfoSet.add(it)
                            }
                            spoofValue
                        } else {
                            result
                        }
                    }
                }
        }

        hookPackageGetter("getInstallingPackageName", "install_source_installing", PLAY_STORE_INSTALLER)
        hookPackageGetter("getInitiatingPackageName", "install_source_initiating", PLAY_STORE_INSTALLER)
        hookPackageGetter("getOriginatingPackageName", "install_source_originating", null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hookPackageGetter("getUpdateOwnerPackageName", "install_source_update_owner", PLAY_STORE_INSTALLER)
            clazz.declaredMethods.firstOrNull { it.name == "getPackageSource" && it.parameterCount == 0 }?.let { method ->
                module.deoptimize(method)
                module.hook(method)
                    .setId("install_source_package_source")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val target = chain.getThisObject()
                        if (target != null && spotifyInstallSourceInfoSet.contains(target)) {
                            0
                        } else {
                            val result = chain.proceed()
                            if (result == 2) 0 else result
                        }
                    }
            }
        }
    }

    private fun patchInstallSourceInfo(info: Any?) {
        if (info == null) return
        runCatching {
            val clazz = info.javaClass
            fun setField(name: String, value: Any?) {
                runCatching {
                    clazz.getDeclaredField(name).apply {
                        isAccessible = true
                        set(info, value)
                    }
                }
            }
            setField("mInstallingPackageName", PLAY_STORE_INSTALLER)
            setField("mInitiatingPackageName", PLAY_STORE_INSTALLER)
            setField("mOriginatingPackageName", null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setField("mUpdateOwnerPackageName", PLAY_STORE_INSTALLER)
                runCatching {
                    clazz.getDeclaredField("mPackageSource").apply {
                        isAccessible = true
                        setInt(info, 0)
                    }
                }
            }
        }
    }

    private fun hookSigningInfo(module: XposedModule) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val clazz = runCatching { Class.forName("android.content.pm.SigningInfo") }.getOrNull() ?: return

        for ((methodName, hookId) in listOf(
            "getSigningCertificateHistory" to "signing_info_history",
            "getApkContentsSigners" to "signing_info_signers"
        )) {
            val method = clazz.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
            module.deoptimize(method)
            module.hook(method)
                .setId(hookId)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifySigningInfoSet.contains(target)) {
                        return@intercept arrayOf(cachedSignature)
                    }
                    val result = chain.proceed()
                    val array = result as? Array<*>
                    val firstSig = array?.firstOrNull() as? Signature
                    if (firstSig != null && isInstalledSpotifySignature(firstSig)) {
                        target?.let { spotifySigningInfoSet.add(it) }
                        return@intercept arrayOf(cachedSignature)
                    }
                    result
                }
        }

        for ((methodName, hookId) in listOf(
            "hasMultipleSigners" to "signing_info_multiple",
            "hasPastSigningCertificates" to "signing_info_past"
        )) {
            val method = clazz.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
            module.deoptimize(method)
            module.hook(method)
                .setId(hookId)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifySigningInfoSet.contains(target)) false else chain.proceed()
                }
        }
    }

    private fun hookActivityReferrer(module: XposedModule) {
        val activityClass = runCatching { Class.forName("android.app.Activity") }.getOrNull() ?: return
        val method = activityClass.declaredMethods.firstOrNull {
            it.name == "getReferrer" && it.parameterCount == 0
        } ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("activity_get_referrer")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed() as? Uri
                if (result == null || isLspatchString(result.toString())) Uri.parse(defaultLauncherUri) else result
            }
    }

    private fun hookIntentReferrer(module: XposedModule) {
        val intentClass = runCatching { Class.forName("android.content.Intent") }.getOrNull() ?: return
        for (method in intentClass.declaredMethods) {
            if (method.name == "getParcelableExtra" &&
                (method.parameterCount == 1 || method.parameterCount == 2) &&
                method.parameterTypes[0] == String::class.java
            ) {
                module.deoptimize(method)
                module.hook(method)
                    .setId("intent_get_parcelable_extra_${method.parameterCount}")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        if (chain.getArg(0) == Intent.EXTRA_REFERRER) {
                            val uri = result as? Uri
                            if (uri == null || isLspatchString(uri.toString())) {
                                return@intercept Uri.parse(defaultLauncherUri)
                            }
                        }
                        result
                    }
            } else if (method.name == "getStringExtra" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
            ) {
                module.deoptimize(method)
                module.hook(method)
                    .setId("intent_get_string_extra")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed() as? String
                        if (chain.getArg(0) == Intent.EXTRA_REFERRER_NAME && (result == null || isLspatchString(result))) {
                            return@intercept defaultLauncherUri
                        }
                        result
                    }
            }
        }
    }

    private fun sanitizePrefString(key: String?, value: String?): String? = when {
        key == "ADAPTIVE_AUTH_METADATA_REFERRER" ->
            if (value == null || isLspatchString(value)) defaultLauncherUri else value
        key == "registration_app_attribution_referrer" || key == "registration_install_attribution_referrer" ->
            if (value == null || isLspatchString(value)) PLAY_STORE_REFERRER else value
        value != null && isLspatchString(value) ->
            if (key?.contains("referrer", ignoreCase = true) == true) defaultLauncherUri else ""
        else -> value
    }

    private fun hookSharedPreferences(module: XposedModule) {
        val spClass = runCatching { Class.forName("android.app.SharedPreferencesImpl") }.getOrNull()
        spClass?.declaredMethods?.firstOrNull {
            it.name == "getString" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java
        }?.let { getStringMethod ->
            module.deoptimize(getStringMethod)
            module.hook(getStringMethod)
                .setId("sp_get_string")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    sanitizePrefString(chain.getArg(0) as? String, chain.proceed() as? String)
                }
        }

        val editorClass = runCatching { Class.forName("android.app.SharedPreferencesImpl\$EditorImpl") }.getOrNull()
        editorClass?.declaredMethods?.firstOrNull {
            it.name == "putString" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java
        }?.let { putStringMethod ->
            module.deoptimize(putStringMethod)
            module.hook(putStringMethod)
                .setId("sp_put_string")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val key = chain.getArg(0) as? String
                    val value = chain.getArg(1) as? String
                    val sanitizedValue = sanitizePrefString(key, value)
                    if (sanitizedValue != value) {
                        val args = chain.args.toTypedArray().also { it[1] = sanitizedValue }
                        chain.proceed(args)
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    private fun hookAppSignaturesMethod(module: XposedModule) {
        val method = DexKitResolver.get<Method>(DexKitResolver.KEY_APP_SIGNATURES_METHOD) ?: return
        module.deoptimize(method)
        module.hook(method)
            .setId("app_signatures_method_hook")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (result is String) SPOTIFY_SIGNATURE_SHA1 else result
            }
        isAppSignaturesHooked = true
    }

    private fun hookInstallerPackageMethod(module: XposedModule) {
        val nameMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_INSTALLER_PACKAGE_METHOD)
        if (nameMethod != null) {
            module.deoptimize(nameMethod)
            module.hook(nameMethod)
                .setId("installer_package_method_hook")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { PLAY_STORE_INSTALLER }
        }
        val allowlistMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_INSTALLER_ALLOWLIST_METHOD)
        if (allowlistMethod != null) {
            module.deoptimize(allowlistMethod)
            module.hook(allowlistMethod)
                .setId("installer_allowlist_method_hook")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { true }
        }
        isInstallerHooked = nameMethod != null || allowlistMethod != null
    }

    private fun hookRavelin(module: XposedModule) {
        val cl = DexKitResolver.get<Method>(DexKitResolver.KEY_APP_SIGNATURES_METHOD)?.declaringClass?.classLoader ?: return
        val rootClass = runCatching {
            Class.forName("com.ravelin.core.util.security.RootCheckerNative", false, cl)
        }.getOrNull()

        rootClass?.declaredMethods?.forEach { method ->
            if (method.returnType == Boolean::class.javaPrimitiveType) {
                module.deoptimize(method)
                module.hook(method)
                    .setId("ravelin_${method.name}")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { false }
            }
        }

        val devPropsClass = runCatching {
            Class.forName("com.ravelin.core.model.DeviceProperties", false, cl)
        }.getOrNull()

        devPropsClass?.declaredConstructors?.forEachIndexed { index, ctor ->
            module.deoptimize(ctor)
            module.hook(ctor)
                .setId("ravelin_dev_props_$index")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val args = chain.args
                    val paramTypes = ctor.parameterTypes
                    val offset = if (paramTypes.isNotEmpty() && paramTypes[0] == Int::class.javaPrimitiveType) 1 else 0
                    val newArgs = if (args.size >= 10 + offset) {
                        args.toTypedArray().also {
                            it[offset + 0] = java.lang.Boolean.FALSE
                            it[offset + 1] = java.lang.Boolean.FALSE
                            it[offset + 2] = java.lang.Boolean.FALSE
                            it[offset + 3] = "Google Play Store"
                            it[offset + 4] = java.lang.Boolean.TRUE
                            it[offset + 5] = java.lang.Boolean.FALSE
                            it[offset + 6] = java.lang.Boolean.FALSE
                            it[offset + 7] = java.lang.Boolean.FALSE
                            it[offset + 8] = java.lang.Boolean.FALSE
                            it[offset + 9] = java.lang.Boolean.FALSE
                        }
                    } else null

                    if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
                }
        }
        isRavelinHooked = true
    }

    private fun resolveOriginApkPath(context: Context): String? {
        cachedOriginApkPath?.let { if (File(it).isFile) return it }
        val appInfo = runCatching { context.applicationInfo }.getOrNull() ?: return null
        val source = appInfo.sourceDir
        if (!source.isNullOrEmpty() && source.contains("/lspatch/origin/") && File(source).isFile) {
            cachedOriginApkPath = source
            return source
        }
        val dataDir = appInfo.dataDir ?: return null
        val originDir = File(dataDir, "cache/lspatch/origin")
        val apk = originDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".apk") } ?: return null
        cachedOriginApkPath = apk.absolutePath
        return cachedOriginApkPath
    }

    private fun sanitizeApplicationInfo(packageName: String?, info: ApplicationInfo?) {
        if (info == null || !isTargetPackage(packageName ?: info.packageName)) return
        val originPath = cachedOriginApkPath
        if (originPath != null && File(originPath).isFile &&
            (info.sourceDir?.contains("/lspatch/") == false)
        ) {
            info.sourceDir = originPath
            info.publicSourceDir = originPath
        }
        if (canStripMetadata) {
            val meta = info.metaData
            if (meta != null && meta.containsKey("lspatch")) {
                info.metaData = Bundle(meta).apply { remove("lspatch") }
            }
        }
    }

    private fun replaceSigningDetails(signingInfo: Any) {
        runCatching {
            val detailsField = signingInfo.javaClass.getDeclaredField("mSigningDetails").apply { isAccessible = true }
            val details = detailsField.get(signingInfo) ?: return
            for (fieldName in listOf("mSignatures", "signatures", "mPastSigningCertificates")) {
                runCatching {
                    val f = details.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
                    val arr = f.get(details) as? Array<*> ?: return@runCatching
                    if (arr.isNotEmpty()) {
                        f.set(details, Array(arr.size) { cachedSignature })
                    }
                }
            }
        }
    }

    private fun patchPackageInfo(packageName: String?, info: PackageInfo?) {
        if (info == null || !isTargetPackage(packageName ?: info.packageName)) return
        if (realInstalledSignature == null) {
            @Suppress("DEPRECATION")
            val legacySig = info.signatures?.firstOrNull()
            val modernSig = if (legacySig == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()
                    ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()
            } else null
            (legacySig ?: modernSig)?.let { realInstalledSignature = it }
        }
        @Suppress("DEPRECATION")
        info.signatures = arrayOf(cachedSignature)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { signingInfo ->
                spotifySigningInfoSet.add(signingInfo)
                replaceSigningDetails(signingInfo)
            }
        }
        sanitizeApplicationInfo(packageName, info.applicationInfo)
    }

    private fun isInstalledSpotifySignature(sig: Signature): Boolean {
        val real = realInstalledSignature ?: return false
        return sig == real || sig.toByteArray().contentEquals(real.toByteArray())
    }

    private fun matchesSpotifyCert(cert: ByteArray?): Boolean {
        if (cert == null) return false
        return cert.contentEquals(cachedSignature.toByteArray()) ||
            cert.contentEquals(cachedSignatureSha256) ||
            cert.contentEquals(cachedSignatureSha1)
    }

    private fun isTargetPackage(pkg: String?): Boolean =
        pkg == CommonUtils.SPOTIFY_PACKAGE_NAME || pkg == "com.spotify.lite"
}
