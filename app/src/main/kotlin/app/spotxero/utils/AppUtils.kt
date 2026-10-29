package app.spotxero.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import app.spotxero.SpotXeroApplication
import app.spotxero.core.PreferencesManager
import app.spotxero.utils.core.CommonUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object AppUtils {
    private const val TAG = "SpotXero"

    private inline fun <T> attempt(operation: String, silent: Boolean = false, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (!silent) Log.w(TAG, "Failed to $operation", e)
        null
    }

    suspend fun forceStopPackage(
        context: Context,
        packageName: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean {
        val service = SpotXeroApplication.service
        val wasRunning = service?.runningTargets?.any { it.processName == packageName }
        if (service != null && wasRunning == false) {
            return true
        }

        PreferencesManager.requestForceStop(context)

        val stopped = withContext(ioDispatcher) {
            var isStopped = false
            for (i in 0 until 10) {
                delay(50)
                val stillRunning = SpotXeroApplication.service?.runningTargets?.any { it.processName == packageName }
                if (stillRunning == false) {
                    isStopped = true
                    break
                }
            }
            isStopped || (service != null && wasRunning == true)
        }
        if (stopped) return true

        attempt("open $packageName app settings") {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
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
