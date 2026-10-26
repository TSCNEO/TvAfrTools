package dev.custom.tvafrtools.adb

import android.content.Context
import android.util.Base64
import dev.custom.tvafrtools.server.PrivilegedServer
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Clean-room Kotlin port of c4.a + V3.g + V3.Q from tvQuickActions:
 * Connects directly from inside the app to the local ADB daemon at 127.0.0.1:5555,
 * performs the ADB CNXN + AUTH_SIGNATURE / AUTH_RSAPUBLICKEY handshake,
 * and automatically executes:
 *   1. appops set <pkg> SYSTEM_ALERT_WINDOW allow
 *   2. settings put secure enabled_accessibility_services ...
 *   3. CLASSPATH=<apk> app_process /system/bin dev.custom.tvafrtools.server.PrivilegedServer &
 * Zero external PC or manual commands required!
 */
object LocalAdbBootstrap {
    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43

    private const val ADB_AUTH_TOKEN = 1
    private const val ADB_AUTH_SIGNATURE = 2
    private const val ADB_AUTH_RSAPUBLICKEY = 3

    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    data class BootstrapResult(
        val success: Boolean,
        val waitingForTvAuthPopup: Boolean = false,
        val message: String
    )

    fun autoStartPrivilegedDaemon(context: Context, port: Int = 5555): BootstrapResult {
        val apkPath = context.applicationInfo.sourceDir
        val pkg = context.packageName
        val accService = "$pkg/dev.custom.tvafrtools.service.TvAfrAccessibilityService"

        val shellCmd = buildString {
            append("appops set $pkg SYSTEM_ALERT_WINDOW allow; ")
            append("settings put secure enabled_accessibility_services $accService; ")
            append("settings put secure accessibility_enabled 1; ")
            append("pkill -f ${PrivilegedServer::class.java.name} 2>/dev/null || true; ")
            append("sleep 0.2; ")
            append("export CLASSPATH=$apkPath; nohup app_process /system/bin ${PrivilegedServer::class.java.name} </dev/null >/data/local/tmp/tvafr_daemon.log 2>&1 &")
        }

        return runLocalAdbShellCommand(context, "127.0.0.1", port, shellCmd)
    }

    fun runLocalAdbShellCommand(
        context: Context,
        host: String,
        port: Int,
        command: String
    ): BootstrapResult {
        val keyPair = getOrCreateRsaKeyPair(context)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 2500)
            socket.soTimeout = 6000
            socket.tcpNoDelay = true

            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // 1. Send CNXN packet
            val banner = "host::tvafrtools\u0000".toByteArray(Charsets.UTF_8)
            sendPacket(output, A_CNXN, 0x01000001, 1024 * 1024, banner)

            var sentSignature = false
            var sentPubKey = false

            while (true) {
                val pkt = readPacket(input) ?: return BootstrapResult(false, false, "Conexión ADB cerrada")
                when (pkt.command) {
                    A_AUTH -> {
                        if (pkt.arg0 == ADB_AUTH_TOKEN) {
                            if (!sentSignature) {
                                sentSignature = true
                                val sig = signAdbToken(keyPair, pkt.payload)
                                sendPacket(output, A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                            } else if (!sentPubKey) {
                                sentPubKey = true
                                val pubKeyBytes = encodeAndroidRsaPublicKey(keyPair.public as RSAPublicKey)
                                sendPacket(output, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubKeyBytes)
                                // Increase timeout while user accepts the "Allow USB debugging" popup on TV
                                socket.soTimeout = 20000
                            }
                        }
                    }
                    A_CNXN -> {
                        // Connected! Open shell stream
                        val dest = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
                        sendPacket(output, A_OPEN, 1, 0, dest)
                    }
                    A_OKAY -> {
                        // Command accepted and running
                        Thread.sleep(600L)
                        return BootstrapResult(
                            success = true,
                            waitingForTvAuthPopup = false,
                            message = "Demonio iniciado automáticamente vía ADB interno"
                        )
                    }
                    A_WRTE -> {
                        sendPacket(output, A_OKAY, 1, pkt.arg0, ByteArray(0))
                        return BootstrapResult(
                            success = true,
                            waitingForTvAuthPopup = false,
                            message = "Demonio iniciado automáticamente vía ADB interno"
                        )
                    }
                    A_CLSE -> {
                        return BootstrapResult(
                            success = true,
                            waitingForTvAuthPopup = false,
                            message = "Comando ADB ejecutado"
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            return BootstrapResult(
                success = false,
                waitingForTvAuthPopup = true,
                message = "ADB local ($host:$port): ${t.message ?: "Acepta el diálogo de Depuración USB en la TV"}"
            )
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private fun sendPacket(out: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        var checksum = 0
        for (b in payload) {
            checksum += (b.toInt() and 0xFF)
        }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(payload.size)
            putInt(checksum)
            putInt(command xor -1)
        }.array()
        out.write(header)
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    private fun readPacket(input: DataInputStream): AdbPacket? {
        val headerBytes = ByteArray(24)
        input.readFully(headerBytes)
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val len = buf.int
        buf.int // checksum
        buf.int // magic
        val payload = ByteArray(len.coerceIn(0, 1024 * 1024))
        if (len > 0) {
            input.readFully(payload)
        }
        return AdbPacket(command, arg0, arg1, payload)
    }

    private fun signAdbToken(keyPair: KeyPair, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        cipher.update(SHA1_DIGEST_INFO_PREFIX)
        return cipher.doFinal(token)
    }

    /**
     * Encodes an RSA 2048 public key into Android's RSAPublicKey binary struct + Base64 + " tvafr@tv\0"
     */
    private fun encodeAndroidRsaPublicKey(pub: RSAPublicKey): ByteArray {
        val modulusBytes = 2048 / 8
        val words = modulusBytes / 4 // 64
        val b = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(pub.modulus)
        val n0inv = b.subtract(pub.modulus.modInverse(b)).toLong() and 0xFFFFFFFFL

        val nWords = IntArray(words)
        val rrWords = IntArray(words)
        var modTmp = pub.modulus
        var rrTmp = rr
        for (i in 0 until words) {
            val dmN = modTmp.divideAndRemainder(b)
            modTmp = dmN[0]
            nWords[i] = dmN[1].toInt()

            val dmRR = rrTmp.divideAndRemainder(b)
            rrTmp = dmRR[0]
            rrWords[i] = dmRR[1].toInt()
        }

        val structBuf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        structBuf.putInt(words)
        structBuf.putInt(n0inv.toInt())
        for (w in nWords) structBuf.putInt(w)
        for (w in rrWords) structBuf.putInt(w)
        structBuf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(structBuf.array(), Base64.NO_WRAP)
        return "$b64 tvafr@tv\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun getOrCreateRsaKeyPair(context: Context): KeyPair {
        val prefs = context.getSharedPreferences("tvafr_adb_keys", Context.MODE_PRIVATE)
        val pubB64 = prefs.getString("pub", null)
        val privB64 = prefs.getString("priv", null)
        if (pubB64 != null && privB64 != null) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.DEFAULT)))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.DEFAULT)))
                return KeyPair(pub, priv)
            } catch (_: Throwable) {}
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        prefs.edit()
            .putString("pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString("priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    fun ensureServerRunningAsync(context: Context) {
        Thread {
            try {
                if (!dev.custom.tvafrtools.server.ServerClient.isServerRunning()) {
                    autoStartPrivilegedDaemon(context.applicationContext)
                }
            } catch (_: Throwable) {
            }
        }.start()
    }
}
