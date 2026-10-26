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
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            if (!ServerClient.isDaemonOnlineSync()) {
                LocalAdbBootstrap.autoStartPrivilegedDaemon(appCtx)
            }
        }
    }
}
