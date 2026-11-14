package app.spotxero.hooks.sharing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.spotxero.core.DexKitResolver
import app.spotxero.core.PrefKeys
import app.spotxero.core.PreferencesManager
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

object CleanSharingHook {

    fun install(module: XposedModule) {
        if (!PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
            return
        }

        hookOutboundSharing(module)
        hookInboundIntents(module)
    }

    private fun hookOutboundSharing(module: XposedModule) {
        val targets = buildList {
            DexKitResolver.get<Method>(DexKitResolver.KEY_SHARE_URL_BUILDER)
                ?.takeIf(::carriesUrl)
                ?.let(::add)
            DexKitResolver.get<List<Method>>(DexKitResolver.KEY_SHARE_RESPONSE_URLS)?.let(::addAll)
        }
        if (targets.isEmpty()) {
            return
        }

        for (method in targets) {
            val id = "clean_sharing_${method.declaringClass.simpleName}_${method.name}"
            module.deoptimize(method)
            module.hook(method)
                .setId(id)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
                        return@intercept result
                    }
                    SharingLinksUtil.sanitizeResult(result)
                }
        }
    }

    private fun hookInboundIntents(module: XposedModule) {
        val setIntentMethod = runCatching {
            Activity::class.java.getDeclaredMethod("setIntent", Intent::class.java)
        }.getOrNull()
        if (setIntentMethod != null) {
            module.deoptimize(setIntentMethod)
            module.hook(setIntentMethod)
                .setId("clean_sharing_inbound_set_intent")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
                        val intent = chain.getArg(0) as? Intent
                        sanitizeInboundIntent(intent)
                    }
                    chain.proceed()
                }
        }

        val onCreateMethod = runCatching {
            Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        }.getOrNull()
        if (onCreateMethod != null) {
            module.deoptimize(onCreateMethod)
            module.hook(onCreateMethod)
                .setId("clean_sharing_inbound_on_create")
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)) {
                        val activity = chain.getThisObject() as? Activity
                        sanitizeInboundIntent(activity?.intent)
                    }
                    chain.proceed()
                }
        }
    }

    private fun sanitizeInboundIntent(intent: Intent?) {
        val dataString = intent?.dataString ?: return
        val sanitized = SharingLinksUtil.sanitizeUrl(dataString) ?: return
        if (sanitized != dataString) {
            runCatching {
                intent.data = Uri.parse(sanitized)
            }
        }
    }

    private fun carriesUrl(method: Method): Boolean =
        method.returnType == String::class.java ||
            method.returnType.declaredFields.any { it.type == Any::class.java }
}
