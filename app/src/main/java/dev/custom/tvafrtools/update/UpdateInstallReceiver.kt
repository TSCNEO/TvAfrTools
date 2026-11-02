package dev.custom.tvafrtools.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receives the status broadcast of a [PackageInstaller] session started by
 * [UpdateManager.installViaPackageInstaller].
 *
 * Because the session is created for our own package (setAppPackageName), the
 * system shows the regular "update this app" confirmation instead of the
 * "install unknown app" flow, so Play Protect does not flag the build as coming
 * from an untrusted publisher.
 *
 * The receiver is declared `exported="false"` in the manifest: the PendingIntent
 * handed to the installer runs with our own identity, so the system can still
 * deliver to it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        Log.i(TAG, "install status=$status pkg=$packageName msg=$statusMessage")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = confirmationIntent(intent)
                if (confirmation == null) {
                    dispatch(false, "El instalador no devolvió la pantalla de confirmación.", true)
                    return
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmation)
                    dispatch(true, "Instalando: confirma la actualización en pantalla.", false)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to show install confirmation", e)
                    dispatch(false, "No se pudo abrir la confirmación de instalación: ${e.message}", true)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                dispatch(true, "✓ ¡Actualización instalada con éxito!", true)
                relaunchApp(context)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                dispatch(false, "Instalación cancelada.", true)
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                dispatch(false, "Instalación bloqueada por el sistema o Play Protect.", true)
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                dispatch(false, "Conflicto de firma: el APK no coincide con la app instalada.", true)
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                dispatch(false, "El APK no es compatible con este dispositivo.", true)
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                dispatch(false, "APK inválido o corrupto${if (statusMessage.isBlank()) "." else ": $statusMessage"}", true)
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                dispatch(false, "Almacenamiento insuficiente para instalar la actualización.", true)
            }

            else -> {
                val detail = statusMessage.ifBlank { "código $status" }
                dispatch(false, "Fallo de instalación: $detail", true)
            }
        }
    }

    /** Restarts the app a moment after a successful self-update so the new APK is loaded. */
    private fun relaunchApp(context: Context) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                val pm = context.packageManager
                val launchIntent = pm.getLeanbackLaunchIntentForPackage(context.packageName)
                    ?: pm.getLaunchIntentForPackage(context.packageName)
                    ?: return@postDelayed
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to relaunch after update", e)
            }
        }, 1500L)
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"

        /** Called on the main thread with (success, user-facing message, isFinal). */
        @Volatile
        var listener: ((Boolean, String, Boolean) -> Unit)? = null

        fun dispatch(success: Boolean, message: String, isFinal: Boolean) {
            val cb = listener ?: return
            cb(success, message, isFinal)
            // A final status ends the install flow: drop the reference so a finishing
            // activity is not kept alive by the companion object.
            if (isFinal) listener = null
        }
    }
}
