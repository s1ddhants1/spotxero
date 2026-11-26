package app.spotxero.hooks.attestation

import android.app.Activity
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
    private var devPropsFalseFields: List<java.lang.reflect.Field> = emptyList()

    @Volatile
    private var devPropsTrueFields: List<java.lang.reflect.Field> = emptyList()

    @Volatile
    private var devPropsInstallSourceField: java.lang.reflect.Field? = null

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
            hookStubProxy(module)
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
            val name = method.name
            val safeArgs = args ?: emptyArray()

            when (name) {
                "getInstallerPackageName" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    if (isTargetPackage(pkgName)) {
                        return PLAY_STORE_INSTALLER
                    }
                    val real = invokeBase(method, safeArgs)
                    return if (real is String && isLspatchString(real)) PLAY_STORE_INSTALLER else real
                }
                "getInstallSourceInfo" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    val real = invokeBase(method, safeArgs)
                    if (isTargetPackage(pkgName)) {
                        return createSpoofedInstallSourceInfo(real)
                    }
                    if (real != null && isLspatchInstallSource(real)) {
                        return createSpoofedInstallSourceInfo(real)
                    }
                    return real
                }
                "hasSigningCertificate" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    val cert = safeArgs.getOrNull(1) as? ByteArray
                    if (isTargetPackage(pkgName)) {
                        return matchesSpotifyCert(cert)
                    }
                    return invokeBase(method, safeArgs)
                }
                "hasUidSigningCertificate" -> {
                    val uid = safeArgs.getOrNull(0) as? Int
                    val cert = safeArgs.getOrNull(1) as? ByteArray
                    if (uid == android.os.Process.myUid()) {
                        return matchesSpotifyCert(cert)
                    }
                    return invokeBase(method, safeArgs)
                }
                "checkSignatures" -> {
                    val pkg1 = safeArgs.getOrNull(0) as? String
                    val pkg2 = safeArgs.getOrNull(1) as? String
                    if (isTargetPackage(pkg1) && isTargetPackage(pkg2)) {
                        return PackageManager.SIGNATURE_MATCH
                    }
                    return invokeBase(method, safeArgs)
                }
                "checkUidSignatures" -> {
                    val uid1 = safeArgs.getOrNull(0) as? Int
                    val uid2 = safeArgs.getOrNull(1) as? Int
                    val myUid = android.os.Process.myUid()
                    if (uid1 == myUid && uid2 == myUid) {
                        return PackageManager.SIGNATURE_MATCH
                    }
                    return invokeBase(method, safeArgs)
                }
                "getPackageInfo" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    if (isForbiddenPackage(pkgName)) {
                        return null
                    }
                    val real = invokeBase(method, safeArgs) as? PackageInfo
                    patchPackageInfo(pkgName, real)
                    return real
                }
                "getApplicationInfo" -> {
                    val pkgName = safeArgs.getOrNull(0) as? String
                    if (isForbiddenPackage(pkgName)) {
                        return null
                    }
                    val real = invokeBase(method, safeArgs) as? ApplicationInfo
                    sanitizeApplicationInfo(pkgName, real)
                    return real
                }
                "getInstalledPackages" -> {
                    val real = invokeBase(method, safeArgs)
                    return filterParceledListSlice(real, true)
                }
                "getInstalledApplications" -> {
                    val real = invokeBase(method, safeArgs)
                    return filterParceledListSlice(real, false)
                }
                "equals" -> {
                    return safeArgs.getOrNull(0) === proxy
                }
                "hashCode" -> {
                    return System.identityHashCode(proxy)
                }
                "toString" -> {
                    return base.toString()
                }
                else -> {
                    return invokeBase(method, safeArgs)
                }
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
        if (info is InstallSourceInfo) {
            val inst = info.installingPackageName
            val init = info.initiatingPackageName
            val orig = info.originatingPackageName
            val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { info.updateOwnerPackageName }.getOrNull()
            } else null
            return isLspatchString(inst) || isLspatchString(init) || isLspatchString(orig) || isLspatchString(owner)
        }
        return false
    }

    private fun createSpoofedInstallSourceInfo(real: Any?): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val isiClass = Class.forName("android.content.pm.InstallSourceInfo")
            val realInfo = real as? InstallSourceInfo
            val initSigningInfo = realInfo?.initiatingPackageSigningInfo

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
            if (ctor4 != null) {
                return@runCatching ctor4.newInstance(
                    PLAY_STORE_INSTALLER,
                    initSigningInfo,
                    null,
                    PLAY_STORE_INSTALLER
                )
            }

            real?.also { patchInstallSourceInfo(it) }
        }.getOrElse {
            real?.also { patchInstallSourceInfo(it) }
        }
    }

    private fun filterParceledListSlice(slice: Any?, isPackageInfo: Boolean): Any? {
        if (slice == null) return null
        return runCatching {
            val getListMethod = slice.javaClass.getMethod("getList")
            val list = getListMethod.invoke(slice) as? List<*> ?: return slice
            val filteredList = ArrayList<Any?>()
            for (item in list) {
                if (item == null) continue
                if (isPackageInfo && item is PackageInfo) {
                    if (!isForbiddenPackage(item.packageName)) {
                        patchPackageInfo(item.packageName, item)
                        filteredList.add(item)
                    }
                } else if (!isPackageInfo && item is ApplicationInfo) {
                    if (!isForbiddenPackage(item.packageName)) {
                        sanitizeApplicationInfo(item.packageName, item)
                        filteredList.add(item)
                    }
                } else {
                    filteredList.add(item)
                }
            }
            val sliceCtor = slice.javaClass.getConstructor(List::class.java)
            sliceCtor.newInstance(filteredList)
        }.getOrElse { slice }
    }

    private fun hookPackageManager(module: XposedModule) {
        val pmClass = runCatching { Class.forName("android.app.ApplicationPackageManager") }.getOrNull() ?: return

        val getPkgInfoInt = pmClass.declaredMethods.firstOrNull {
            it.name == "getPackageInfo" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (getPkgInfoInt != null) {
            module.deoptimize(getPkgInfoInt)
            module.hook(getPkgInfoInt)
                .setId("pm_get_package_info_int")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkgName = chain.getArg(0) as? String
                    if (isForbiddenPackage(pkgName)) {
                        throw PackageManager.NameNotFoundException("Package not found: $pkgName")
                    }
                    val result = chain.proceed()
                    patchPackageInfo(pkgName, result as? PackageInfo)
                    result
                }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val getPkgInfoFlags = pmClass.declaredMethods.firstOrNull {
                it.name == "getPackageInfo" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1].name == "android.content.pm.PackageManager\$PackageInfoFlags"
            }
            if (getPkgInfoFlags != null) {
                module.deoptimize(getPkgInfoFlags)
                module.hook(getPkgInfoFlags)
                    .setId("pm_get_package_info_flags")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkgName = chain.getArg(0) as? String
                        if (isForbiddenPackage(pkgName)) {
                            throw PackageManager.NameNotFoundException("Package not found: $pkgName")
                        }
                        val result = chain.proceed()
                        patchPackageInfo(pkgName, result as? PackageInfo)
                        result
                    }
            }
        }

        val getInstaller = pmClass.declaredMethods.firstOrNull {
            it.name == "getInstallerPackageName" && it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }
        if (getInstaller != null) {
            module.deoptimize(getInstaller)
            module.hook(getInstaller)
                .setId("pm_get_installer_package_name")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkgName = chain.getArg(0) as? String
                    if (isTargetPackage(pkgName)) {
                        PLAY_STORE_INSTALLER
                    } else {
                        val result = chain.proceed()
                        if (result is String && isLspatchString(result)) {
                            PLAY_STORE_INSTALLER
                        } else {
                            result
                        }
                    }
                }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val getInstallSource = pmClass.declaredMethods.firstOrNull {
                it.name == "getInstallSourceInfo" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
            if (getInstallSource != null) {
                module.deoptimize(getInstallSource)
                module.hook(getInstallSource)
                    .setId("pm_get_install_source_info")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkgName = chain.getArg(0) as? String
                        if (isForbiddenPackage(pkgName)) {
                            throw PackageManager.NameNotFoundException("Package not found: $pkgName")
                        }
                        val result = chain.proceed()
                        if (result != null && isTargetPackage(pkgName)) {
                            patchInstallSourceInfo(result)
                            spotifyInstallSourceInfoSet.add(result)
                        } else if (result != null) {
                            runCatching {
                                val field = result.javaClass.getDeclaredField("mInstallingPackageName").apply { isAccessible = true }
                                val installing = field.get(result) as? String
                                if (isLspatchString(installing)) {
                                    patchInstallSourceInfo(result)
                                    spotifyInstallSourceInfoSet.add(result)
                                }
                            }
                        }
                        result
                    }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val hasSigningCert = pmClass.declaredMethods.firstOrNull {
                it.name == "hasSigningCertificate" && it.parameterCount == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == ByteArray::class.java &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
            if (hasSigningCert != null) {
                module.deoptimize(hasSigningCert)
                module.hook(hasSigningCert)
                    .setId("pm_has_signing_certificate")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkgName = chain.getArg(0) as? String
                        if (isTargetPackage(pkgName)) {
                            matchesSpotifyCert(chain.getArg(1) as? ByteArray)
                        } else {
                            chain.proceed()
                        }
                    }
            }

            val hasSigningCertUid = pmClass.declaredMethods.firstOrNull {
                it.name == "hasSigningCertificate" && it.parameterCount == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == ByteArray::class.java &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
            if (hasSigningCertUid != null) {
                module.deoptimize(hasSigningCertUid)
                module.hook(hasSigningCertUid)
                    .setId("pm_has_signing_certificate_uid")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val uid = chain.getArg(0) as? Int
                        if (uid == android.os.Process.myUid()) {
                            matchesSpotifyCert(chain.getArg(1) as? ByteArray)
                        } else {
                            chain.proceed()
                        }
                    }
            }
        }

        val checkSignaturesPkg = pmClass.declaredMethods.firstOrNull {
            it.name == "checkSignatures" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        }
        if (checkSignaturesPkg != null) {
            module.deoptimize(checkSignaturesPkg)
            module.hook(checkSignaturesPkg)
                .setId("pm_check_signatures_pkg")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg1 = chain.getArg(0) as? String
                    val pkg2 = chain.getArg(1) as? String
                    if (isTargetPackage(pkg1) && isTargetPackage(pkg2)) {
                        PackageManager.SIGNATURE_MATCH
                    } else {
                        chain.proceed()
                    }
                }
        }

        val checkSignaturesUid = pmClass.declaredMethods.firstOrNull {
            it.name == "checkSignatures" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (checkSignaturesUid != null) {
            module.deoptimize(checkSignaturesUid)
            module.hook(checkSignaturesUid)
                .setId("pm_check_signatures_uid")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val uid1 = chain.getArg(0) as? Int
                    val uid2 = chain.getArg(1) as? Int
                    val myUid = android.os.Process.myUid()
                    if (uid1 == myUid && uid2 == myUid) {
                        PackageManager.SIGNATURE_MATCH
                    } else {
                        chain.proceed()
                    }
                }
        }

        val getInstalledPkgs = pmClass.declaredMethods.firstOrNull {
            it.name == "getInstalledPackages" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (getInstalledPkgs != null) {
            module.deoptimize(getInstalledPkgs)
            module.hook(getInstalledPkgs)
                .setId("pm_get_installed_packages")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val list = chain.proceed() as? List<*> ?: return@intercept chain.proceed()
                    list.filterIsInstance<PackageInfo>()
                        .filter { !isForbiddenPackage(it.packageName) }
                        .onEach { patchPackageInfo(it.packageName, it) }
                }
        }

        val getInstalledApps = pmClass.declaredMethods.firstOrNull {
            it.name == "getInstalledApplications" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (getInstalledApps != null) {
            module.deoptimize(getInstalledApps)
            module.hook(getInstalledApps)
                .setId("pm_get_installed_applications")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val list = chain.proceed() as? List<*> ?: return@intercept chain.proceed()
                    list.filterIsInstance<ApplicationInfo>()
                        .filter { !isForbiddenPackage(it.packageName) }
                        .onEach { sanitizeApplicationInfo(it.packageName, it) }
                }
        }

        val getAppInfoInt = pmClass.declaredMethods.firstOrNull {
            it.name == "getApplicationInfo" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (getAppInfoInt != null) {
            module.deoptimize(getAppInfoInt)
            module.hook(getAppInfoInt)
                .setId("pm_get_application_info_int")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkgName = chain.getArg(0) as? String
                    if (isForbiddenPackage(pkgName)) {
                        throw PackageManager.NameNotFoundException("Package not found: $pkgName")
                    }
                    val result = chain.proceed() as? ApplicationInfo
                    sanitizeApplicationInfo(pkgName, result)
                    result
                }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val getAppInfoFlags = pmClass.declaredMethods.firstOrNull {
                it.name == "getApplicationInfo" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1].name == "android.content.pm.PackageManager\$ApplicationInfoFlags"
            }
            if (getAppInfoFlags != null) {
                module.deoptimize(getAppInfoFlags)
                module.hook(getAppInfoFlags)
                    .setId("pm_get_application_info_flags")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkgName = chain.getArg(0) as? String
                        if (isForbiddenPackage(pkgName)) {
                            throw PackageManager.NameNotFoundException("Package not found: $pkgName")
                        }
                        val result = chain.proceed() as? ApplicationInfo
                        sanitizeApplicationInfo(pkgName, result)
                        result
                    }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val getInstalledPkgsFlags = pmClass.declaredMethods.firstOrNull {
                it.name == "getInstalledPackages" && it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "android.content.pm.PackageManager\$PackageInfoFlags"
            }
            if (getInstalledPkgsFlags != null) {
                module.deoptimize(getInstalledPkgsFlags)
                module.hook(getInstalledPkgsFlags)
                    .setId("pm_get_installed_packages_flags")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val list = chain.proceed() as? List<*> ?: return@intercept chain.proceed()
                        list.filterIsInstance<PackageInfo>()
                            .filter { !isForbiddenPackage(it.packageName) }
                            .onEach { patchPackageInfo(it.packageName, it) }
                    }
            }

            val getInstalledAppsFlags = pmClass.declaredMethods.firstOrNull {
                it.name == "getInstalledApplications" && it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "android.content.pm.PackageManager\$ApplicationInfoFlags"
            }
            if (getInstalledAppsFlags != null) {
                module.deoptimize(getInstalledAppsFlags)
                module.hook(getInstalledAppsFlags)
                    .setId("pm_get_installed_applications_flags")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val list = chain.proceed() as? List<*> ?: return@intercept chain.proceed()
                        list.filterIsInstance<ApplicationInfo>().filter { !isForbiddenPackage(it.packageName) }
                    }
            }
        }
    }

    private fun hookStubProxy(module: XposedModule) {
        val stubProxyClass = runCatching { Class.forName("android.content.pm.IPackageManager\$Stub\$Proxy") }.getOrNull() ?: return
        val getInstaller = stubProxyClass.declaredMethods.firstOrNull {
            it.name == "getInstallerPackageName" && it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }
        if (getInstaller != null) {
            module.deoptimize(getInstaller)
            module.hook(getInstaller)
                .setId("stub_proxy_get_installer")
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
            val getInstallSource = stubProxyClass.declaredMethods.firstOrNull {
                it.name == "getInstallSourceInfo" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            if (getInstallSource != null) {
                module.deoptimize(getInstallSource)
                module.hook(getInstallSource)
                    .setId("stub_proxy_get_install_source")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkgName = chain.getArg(0) as? String
                        if (isForbiddenPackage(pkgName)) {
                            return@intercept null
                        }
                        val result = chain.proceed()
                        if (result != null && isTargetPackage(pkgName)) {
                            patchInstallSourceInfo(result)
                            spotifyInstallSourceInfoSet.add(result)
                        } else if (result != null) {
                            runCatching {
                                val field = result.javaClass.getDeclaredField("mInstallingPackageName").apply { isAccessible = true }
                                val installing = field.get(result) as? String
                                if (isLspatchString(installing)) {
                                    patchInstallSourceInfo(result)
                                    spotifyInstallSourceInfoSet.add(result)
                                }
                            }
                        }
                        result
                    }
            }
        }

        val hasSigningCert = stubProxyClass.declaredMethods.firstOrNull {
            it.name == "hasSigningCertificate" && it.parameterCount == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == ByteArray::class.java &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        }
        if (hasSigningCert != null) {
            module.deoptimize(hasSigningCert)
            module.hook(hasSigningCert)
                .setId("stub_proxy_has_signing_cert")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkgName = chain.getArg(0) as? String
                    if (isTargetPackage(pkgName)) {
                        matchesSpotifyCert(chain.getArg(1) as? ByteArray)
                    } else {
                        chain.proceed()
                    }
                }
        }

        val checkSigs = stubProxyClass.declaredMethods.firstOrNull {
            it.name == "checkSignatures" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        }
        if (checkSigs != null) {
            module.deoptimize(checkSigs)
            module.hook(checkSigs)
                .setId("stub_proxy_check_signatures")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg1 = chain.getArg(0) as? String
                    val pkg2 = chain.getArg(1) as? String
                    if (isTargetPackage(pkg1) && isTargetPackage(pkg2)) {
                        PackageManager.SIGNATURE_MATCH
                    } else {
                        chain.proceed()
                    }
                }
        }

        val getPkgInfo = stubProxyClass.declaredMethods.firstOrNull {
            it.name == "getPackageInfo" && it.parameterCount == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Long::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        }
        if (getPkgInfo != null) {
            module.deoptimize(getPkgInfo)
            module.hook(getPkgInfo)
                .setId("stub_proxy_get_package_info")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkgName = chain.getArg(0) as? String
                    if (isForbiddenPackage(pkgName)) {
                        return@intercept null
                    }
                    val result = chain.proceed()
                    patchPackageInfo(pkgName, result as? PackageInfo)
                    result
                }
        }

        val getAppInfo = stubProxyClass.declaredMethods.firstOrNull {
            it.name == "getApplicationInfo" && it.parameterCount == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Long::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        }
        if (getAppInfo != null) {
            module.deoptimize(getAppInfo)
            module.hook(getAppInfo)
                .setId("stub_proxy_get_application_info")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkgName = chain.getArg(0) as? String
                    if (isForbiddenPackage(pkgName)) {
                        return@intercept null
                    }
                    val result = chain.proceed() as? ApplicationInfo
                    sanitizeApplicationInfo(pkgName, result)
                    result
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
        val getInstalling = clazz.declaredMethods.firstOrNull { it.name == "getInstallingPackageName" && it.parameterCount == 0 }
        if (getInstalling != null) {
            module.deoptimize(getInstalling)
            module.hook(getInstalling)
                .setId("install_source_installing")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifyInstallSourceInfoSet.contains(target)) {
                        PLAY_STORE_INSTALLER
                    } else {
                        val result = chain.proceed()
                        if (result is String && isLspatchString(result)) {
                            target?.let {
                                patchInstallSourceInfo(it)
                                spotifyInstallSourceInfoSet.add(it)
                            }
                            PLAY_STORE_INSTALLER
                        } else {
                            result
                        }
                    }
                }
        }
        val getInitiating = clazz.declaredMethods.firstOrNull { it.name == "getInitiatingPackageName" && it.parameterCount == 0 }
        if (getInitiating != null) {
            module.deoptimize(getInitiating)
            module.hook(getInitiating)
                .setId("install_source_initiating")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifyInstallSourceInfoSet.contains(target)) {
                        PLAY_STORE_INSTALLER
                    } else {
                        val result = chain.proceed()
                        if (result is String && isLspatchString(result)) {
                            target?.let {
                                patchInstallSourceInfo(it)
                                spotifyInstallSourceInfoSet.add(it)
                            }
                            PLAY_STORE_INSTALLER
                        } else {
                            result
                        }
                    }
                }
        }
        val getOriginating = clazz.declaredMethods.firstOrNull { it.name == "getOriginatingPackageName" && it.parameterCount == 0 }
        if (getOriginating != null) {
            module.deoptimize(getOriginating)
            module.hook(getOriginating)
                .setId("install_source_originating")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifyInstallSourceInfoSet.contains(target)) {
                        null
                    } else {
                        val result = chain.proceed()
                        if (result is String && isLspatchString(result)) {
                            target?.let {
                                patchInstallSourceInfo(it)
                                spotifyInstallSourceInfoSet.add(it)
                            }
                            null
                        } else {
                            result
                        }
                    }
                }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val getUpdateOwner = clazz.declaredMethods.firstOrNull { it.name == "getUpdateOwnerPackageName" && it.parameterCount == 0 }
            if (getUpdateOwner != null) {
                module.deoptimize(getUpdateOwner)
                module.hook(getUpdateOwner)
                    .setId("install_source_update_owner")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val target = chain.getThisObject()
                        if (target != null && spotifyInstallSourceInfoSet.contains(target)) {
                            PLAY_STORE_INSTALLER
                        } else {
                            val result = chain.proceed()
                            if (result is String && isLspatchString(result)) {
                                target?.let {
                                    patchInstallSourceInfo(it)
                                    spotifyInstallSourceInfoSet.add(it)
                                }
                                PLAY_STORE_INSTALLER
                            } else {
                                result
                            }
                        }
                    }
            }
            val getSource = clazz.declaredMethods.firstOrNull { it.name == "getPackageSource" && it.parameterCount == 0 }
            if (getSource != null) {
                module.deoptimize(getSource)
                module.hook(getSource)
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
            runCatching {
                clazz.getDeclaredField("mInstallingPackageName").apply {
                    isAccessible = true
                    set(info, PLAY_STORE_INSTALLER)
                }
            }
            runCatching {
                clazz.getDeclaredField("mInitiatingPackageName").apply {
                    isAccessible = true
                    set(info, PLAY_STORE_INSTALLER)
                }
            }
            runCatching {
                clazz.getDeclaredField("mOriginatingPackageName").apply {
                    isAccessible = true
                    set(info, null)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    clazz.getDeclaredField("mUpdateOwnerPackageName").apply {
                        isAccessible = true
                        set(info, PLAY_STORE_INSTALLER)
                    }
                }
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
        val getHistory = clazz.declaredMethods.firstOrNull { it.name == "getSigningCertificateHistory" && it.parameterCount == 0 }
        if (getHistory != null) {
            module.deoptimize(getHistory)
            module.hook(getHistory)
                .setId("signing_info_history")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifySigningInfoSet.contains(target)) {
                        return@intercept arrayOf(cachedSignature)
                    }
                    val result = chain.proceed()
                    val array = result as? Array<*>
                    if (array != null && array.isNotEmpty()) {
                        val firstSig = array[0] as? Signature
                        if (firstSig != null && isInstalledSpotifySignature(firstSig)) {
                            target?.let { spotifySigningInfoSet.add(it) }
                            return@intercept arrayOf(cachedSignature)
                        }
                    }
                    result
                }
        }
        val getSigners = clazz.declaredMethods.firstOrNull { it.name == "getApkContentsSigners" && it.parameterCount == 0 }
        if (getSigners != null) {
            module.deoptimize(getSigners)
            module.hook(getSigners)
                .setId("signing_info_signers")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifySigningInfoSet.contains(target)) {
                        return@intercept arrayOf(cachedSignature)
                    }
                    val result = chain.proceed()
                    val array = result as? Array<*>
                    if (array != null && array.isNotEmpty()) {
                        val firstSig = array[0] as? Signature
                        if (firstSig != null && isInstalledSpotifySignature(firstSig)) {
                            target?.let { spotifySigningInfoSet.add(it) }
                            return@intercept arrayOf(cachedSignature)
                        }
                    }
                    result
                }
        }
        val hasMultiple = clazz.declaredMethods.firstOrNull { it.name == "hasMultipleSigners" && it.parameterCount == 0 }
        if (hasMultiple != null) {
            module.deoptimize(hasMultiple)
            module.hook(hasMultiple)
                .setId("signing_info_multiple")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifySigningInfoSet.contains(target)) {
                        false
                    } else {
                        chain.proceed()
                    }
                }
        }
        val hasPast = clazz.declaredMethods.firstOrNull { it.name == "hasPastSigningCertificates" && it.parameterCount == 0 }
        if (hasPast != null) {
            module.deoptimize(hasPast)
            module.hook(hasPast)
                .setId("signing_info_past")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val target = chain.getThisObject()
                    if (target != null && spotifySigningInfoSet.contains(target)) {
                        false
                    } else {
                        chain.proceed()
                    }
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
                if (result == null || isLspatchString(result.toString())) {
                    Uri.parse(defaultLauncherUri)
                } else {
                    result
                }
            }
    }

    private fun hookIntentReferrer(module: XposedModule) {
        val intentClass = runCatching { Class.forName("android.content.Intent") }.getOrNull() ?: return
        for (method in intentClass.declaredMethods) {
            if (method.name == "getParcelableExtra") {
                if (method.parameterCount == 1 && method.parameterTypes[0] == String::class.java) {
                    module.deoptimize(method)
                    module.hook(method)
                        .setId("intent_get_parcelable_extra_1")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val key = chain.getArg(0) as? String
                            val result = chain.proceed()
                            if (key == Intent.EXTRA_REFERRER) {
                                val uri = result as? Uri
                                if (uri == null || isLspatchString(uri.toString())) {
                                    return@intercept Uri.parse(defaultLauncherUri)
                                }
                            }
                            result
                        }
                } else if (method.parameterCount == 2 && method.parameterTypes[0] == String::class.java) {
                    module.deoptimize(method)
                    module.hook(method)
                        .setId("intent_get_parcelable_extra_2")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val key = chain.getArg(0) as? String
                            val result = chain.proceed()
                            if (key == Intent.EXTRA_REFERRER) {
                                val uri = result as? Uri
                                if (uri == null || isLspatchString(uri.toString())) {
                                    return@intercept Uri.parse(defaultLauncherUri)
                                }
                            }
                            result
                        }
                }
            } else if (method.name == "getStringExtra" && method.parameterCount == 1 && method.parameterTypes[0] == String::class.java) {
                module.deoptimize(method)
                module.hook(method)
                    .setId("intent_get_string_extra")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val key = chain.getArg(0) as? String
                        val result = chain.proceed() as? String
                        if (key == Intent.EXTRA_REFERRER_NAME) {
                            if (result == null || isLspatchString(result)) {
                                return@intercept defaultLauncherUri
                            }
                        }
                        result
                    }
            }
        }
    }

    private fun hookSharedPreferences(module: XposedModule) {
        val spClass = runCatching { Class.forName("android.app.SharedPreferencesImpl") }.getOrNull()
        if (spClass != null) {
            val getStringMethod = spClass.declaredMethods.firstOrNull {
                it.name == "getString" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
            if (getStringMethod != null) {
                module.deoptimize(getStringMethod)
                module.hook(getStringMethod)
                    .setId("sp_get_string")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val key = chain.getArg(0) as? String
                        val result = chain.proceed() as? String
                        when {
                            key == "ADAPTIVE_AUTH_METADATA_REFERRER" -> {
                                if (result == null || isLspatchString(result)) defaultLauncherUri else result
                            }
                            key == "registration_app_attribution_referrer" || key == "registration_install_attribution_referrer" -> {
                                if (result == null || isLspatchString(result)) PLAY_STORE_REFERRER else result
                            }
                            result != null && isLspatchString(result) -> {
                                if (key?.contains("referrer", ignoreCase = true) == true) defaultLauncherUri else ""
                            }
                            else -> result
                        }
                    }
            }
        }

        val editorClass = runCatching { Class.forName("android.app.SharedPreferencesImpl\$EditorImpl") }.getOrNull()
        if (editorClass != null) {
            val putStringMethod = editorClass.declaredMethods.firstOrNull {
                it.name == "putString" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
            if (putStringMethod != null) {
                module.deoptimize(putStringMethod)
                module.hook(putStringMethod)
                    .setId("sp_put_string")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val key = chain.getArg(0) as? String
                        val value = chain.getArg(1) as? String
                        val sanitizedValue = when {
                            key == "ADAPTIVE_AUTH_METADATA_REFERRER" -> {
                                if (value == null || isLspatchString(value)) defaultLauncherUri else value
                            }
                            key == "registration_app_attribution_referrer" || key == "registration_install_attribution_referrer" -> {
                                if (value == null || isLspatchString(value)) PLAY_STORE_REFERRER else value
                            }
                            value != null && isLspatchString(value) -> {
                                if (key?.contains("referrer", ignoreCase = true) == true) defaultLauncherUri else ""
                            }
                            else -> value
                        }
                        if (sanitizedValue != value) {
                            val args = chain.args.toTypedArray().also { it[1] = sanitizedValue }
                            chain.proceed(args)
                        } else {
                            chain.proceed()
                        }
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
                .intercept { chain -> PLAY_STORE_INSTALLER }
        }
        val allowlistMethod = DexKitResolver.get<Method>(DexKitResolver.KEY_INSTALLER_ALLOWLIST_METHOD)
        if (allowlistMethod != null) {
            module.deoptimize(allowlistMethod)
            module.hook(allowlistMethod)
                .setId("installer_allowlist_method_hook")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain -> true }
        }
        isInstallerHooked = nameMethod != null || allowlistMethod != null
    }

    private fun hookRavelin(module: XposedModule) {
        val cl = DexKitResolver.get<Method>(DexKitResolver.KEY_APP_SIGNATURES_METHOD)?.declaringClass?.classLoader ?: return
        val rootClass = runCatching {
            Class.forName("com.ravelin.core.util.security.RootCheckerNative", false, cl)
        }.getOrNull()

        if (rootClass != null) {
            rootClass.declaredMethods.forEach { method ->
                if (method.returnType == Boolean::class.javaPrimitiveType) {
                    module.deoptimize(method)
                    module.hook(method)
                        .setId("ravelin_${method.name}")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { _ -> false }
                }
            }
        }

        val devPropsClass = runCatching {
            Class.forName("com.ravelin.core.model.DeviceProperties", false, cl)
        }.getOrNull()

        if (devPropsClass != null) {
            resolveDevicePropertyFields(devPropsClass)
            devPropsClass.declaredConstructors.forEachIndexed { index, ctor ->
                module.deoptimize(ctor)
                module.hook(ctor)
                    .setId("ravelin_dev_props_$index")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val args = chain.args
                        val paramTypes = ctor.parameterTypes
                        val newArgs = if (paramTypes.isNotEmpty() && paramTypes[0] == Int::class.javaPrimitiveType) {
                            if (args.size >= 11) {
                                args.toTypedArray().also {
                                    it[1] = java.lang.Boolean.FALSE
                                    it[2] = java.lang.Boolean.FALSE
                                    it[3] = java.lang.Boolean.FALSE
                                    it[4] = "Google Play Store"
                                    it[5] = java.lang.Boolean.TRUE
                                    it[6] = java.lang.Boolean.FALSE
                                    it[7] = java.lang.Boolean.FALSE
                                    it[8] = java.lang.Boolean.FALSE
                                    it[9] = java.lang.Boolean.FALSE
                                    it[10] = java.lang.Boolean.FALSE
                                }
                            } else null
                        } else if (args.size >= 10) {
                            args.toTypedArray().also {
                                it[0] = java.lang.Boolean.FALSE
                                it[1] = java.lang.Boolean.FALSE
                                it[2] = java.lang.Boolean.FALSE
                                it[3] = "Google Play Store"
                                it[4] = java.lang.Boolean.TRUE
                                it[5] = java.lang.Boolean.FALSE
                                it[6] = java.lang.Boolean.FALSE
                                it[7] = java.lang.Boolean.FALSE
                                it[8] = java.lang.Boolean.FALSE
                                it[9] = java.lang.Boolean.FALSE
                            }
                        } else null

                        val result = if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
                        val instance = chain.getThisObject()
                        if (instance != null) {
                            sanitizeDeviceProperties(instance)
                        }
                        result
                    }
            }
        }
        isRavelinHooked = true
    }

    private fun resolveDevicePropertyFields(devPropsClass: Class<*>) {
        runCatching {
            val ctor = devPropsClass.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size >= 10 &&
                    p[0] == java.lang.Boolean::class.java &&
                    p[3] == String::class.java &&
                    p[p.size - 1] == java.lang.Boolean::class.java
            }?.apply { isAccessible = true } ?: return

            val pTypes = ctor.parameterTypes
            val allFields = devPropsClass.declaredFields.onEach { it.isAccessible = true }
            val boolFields = allFields.filter { it.type == java.lang.Boolean::class.java }
            val stringFields = allFields.filter { it.type == String::class.java }

            fun findBoolFieldAt(paramIndex: Int): java.lang.reflect.Field? {
                if (paramIndex !in pTypes.indices || pTypes[paramIndex] != java.lang.Boolean::class.java) return null
                val args = arrayOfNulls<Any>(pTypes.size)
                for (i in pTypes.indices) {
                    if (pTypes[i] == java.lang.Boolean::class.java) {
                        args[i] = if (i == paramIndex) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
                    }
                }
                val probe = ctor.newInstance(*args)
                return boolFields.firstOrNull { it.get(probe) == java.lang.Boolean.TRUE }
            }

            val falseIndices = buildList {
                addAll(listOf(0, 1, 2, 5, 6, 7, 8, 9))
                if (pTypes.size > 20) add(pTypes.size - 1)
            }
            devPropsFalseFields = falseIndices.mapNotNull { findBoolFieldAt(it) }
            devPropsTrueFields = listOfNotNull(findBoolFieldAt(4))

            val srcArgs = arrayOfNulls<Any>(pTypes.size)
            srcArgs[3] = "__spotxero_install_source__"
            val srcProbe = ctor.newInstance(*srcArgs)
            devPropsInstallSourceField = stringFields.firstOrNull { it.get(srcProbe) == "__spotxero_install_source__" }
        }
    }

    private fun sanitizeDeviceProperties(instance: Any) {
        runCatching {
            for (field in devPropsFalseFields) {
                field.set(instance, java.lang.Boolean.FALSE)
            }
            for (field in devPropsTrueFields) {
                field.set(instance, java.lang.Boolean.TRUE)
            }
            devPropsInstallSourceField?.set(instance, "Google Play Store")
        }
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
