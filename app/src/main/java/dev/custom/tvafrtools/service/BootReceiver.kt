package dev.custom.tvafrtools.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.custom.tvafrtools.adb.LocalAdbBootstrap
import dev.custom.tvafrtools.server.ServerClient
import dev.custom.tvafrtools.server.isDaemonOnlineSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Automatically starts the PrivilegedServer via the internal ADB client on BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON")) return
        val afterUpdate = intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!afterUpdate && !dev.custom.tvafrtools.config.AppPrefs(context).startOnBoot) return
        val pending = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var force = intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED
                repeat(3) {
                    if (!force && LocalAdbBootstrap.isSetupReady(appCtx)) return@launch
                    val result = LocalAdbBootstrap.autoStartPrivilegedDaemon(appCtx, forceRestart = force)
                    force = false
                    if (result.waitingForTvAuthPopup) return@launch
                    kotlinx.coroutines.delay(1000L)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
