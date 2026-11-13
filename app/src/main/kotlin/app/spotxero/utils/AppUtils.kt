package app.spotxero.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import app.spotxero.SpotXeroApplication
import app.spotxero.SpotXeroModule
import app.spotxero.core.PreferencesManager
import app.spotxero.utils.core.CommonUtils
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object AppUtils {
    private const val TAG = "SpotXero"

    private inline fun <T> attempt(operation: String, silent: Boolean = false, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (!silent) Log.w(TAG, "Failed to $operation", e)
        null
    }

    fun isPackageRunning(context: Context, packageName: String): Boolean {
        val rootRunning = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof $packageName"))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor() == 0 && output.isNotEmpty()
        }.getOrNull()
        if (rootRunning != null) return rootRunning

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val procs = runCatching { am?.runningAppProcesses }.getOrNull()
        return procs?.any { it.processName == packageName || it.processName.startsWith("$packageName:") } == true
    }

    suspend fun forceStopPackage(
        context: Context,
        packageName: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean {
        var broadcastHandled = false
        withContext(ioDispatcher) {
            runCatching {
                withTimeoutOrNull(1500) {
                    suspendCancellableCoroutine { continuation ->
                        val intent = Intent(SpotXeroModule.ACTION_FORCE_STOP).apply {
                            setPackage(packageName)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        val resultReceiver = object : BroadcastReceiver() {
                            override fun onReceive(c: Context?, i: Intent?) {
                                if (continuation.isActive) {
                                    continuation.resume(resultCode == Activity.RESULT_OK)
                                }
                            }
                        }
                        context.sendOrderedBroadcast(
                            intent,
                            null,
                            resultReceiver,
                            null,
                            Activity.RESULT_CANCELED,
                            null,
                            null
                        )
                    }
                }
            }.getOrNull()?.let { broadcastHandled = it }
        }

        if (broadcastHandled) {
            delay(300)
            return true
        }

        return false
    }

    suspend fun forceStopSpotify(
        context: Context,
        packageName: String = CommonUtils.SPOTIFY_PACKAGE_NAME,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = forceStopPackage(context, packageName, ioDispatcher)

    fun openSpotify(
        context: Context,
        packageName: String = CommonUtils.SPOTIFY_PACKAGE_NAME
    ): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return attempt("launch $packageName") {
            context.startActivity(launchIntent)
            true
        } ?: false
    }

    fun isLauncherIconHidden(context: Context): Boolean {
        val alias = ComponentName(context, "${context.packageName}.ui.MainActivityAlias")
        return runCatching {
            context.packageManager.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(false)
    }

    fun setLauncherIconHidden(context: Context, hidden: Boolean) {
        val alias = ComponentName(context, "${context.packageName}.ui.MainActivityAlias")
        val status = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(alias, status, PackageManager.DONT_KILL_APP)
        }
    }
}
