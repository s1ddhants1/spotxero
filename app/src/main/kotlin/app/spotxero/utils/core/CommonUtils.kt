package app.spotxero.utils.core

import android.content.Context
import app.spotxero.R

object CommonUtils {
    const val SPOTIFY_PACKAGE_NAME = "com.spotify.music"

    val SUPPORTED_PACKAGES = listOf(
        "com.spotify.music",
        "com.spotify.lite"
    )

    fun getVariantLabel(context: Context, packageName: String): String {
        return when (packageName) {
            "com.spotify.music" -> context.getString(R.string.variant_official)
            "com.spotify.lite" -> context.getString(R.string.variant_lite)
            else -> {
                val parts = packageName.split(".")
                val best = if (parts.size >= 2) parts[1] else packageName
                best.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }
}
