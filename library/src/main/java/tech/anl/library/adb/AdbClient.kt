package tech.anl.library.adb

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.conscrypt.Conscrypt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A wireless-debugging ADB client for this device, built on libadb-android. The RSA key and its
 * self-signed certificate persist in app-private storage, so a pairing survives app restarts (the
 * device remembers the key until the user revokes it or turns wireless debugging off and on).
 */
class AdbClient private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val keyDir = File(appContext.filesDir, "adb")
    private val keyFile = File(keyDir, "adbkey.pk8")
    private val certFile = File(keyDir, "adbkey.crt")
    private val lock = Mutex()

    private lateinit var privateKey: PrivateKey
    private lateinit var certificate: Certificate

    private val manager: AbsAdbConnectionManager by lazy {
        loadOrCreateKey()
        object : AbsAdbConnectionManager() {
            override fun getPrivateKey(): PrivateKey = this@AdbClient.privateKey
            override fun getCertificate(): Certificate = this@AdbClient.certificate
            override fun getDeviceName(): String = "ServerBox"
        }.apply {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    val isConnected: Boolean get() = runCatching { manager.isConnected }.getOrDefault(false)

    /**
     * Pairs with the "Pair device with pairing code" service. Returns whether the device accepted
     * the code. Only the pairing result decides success: errors from tearing down the pairing
     * connection afterwards are logged, never reported as a failed pairing.
     */
    suspend fun pair(host: String, port: Int, code: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val paired = try {
                manager.pair(host, port, code.trim())
            } catch (e: Exception) {
                // libadb throws from its own cleanup after a successful exchange on some devices;
                // only a failure before the peer accepted the key counts.
                if (e is InterruptedException) throw e
                Log.w(TAG, "pair($host:$port) threw", e)
                if (isPairingAcceptedError(e)) true else throw AdbException(describe(e, "Pairing failed"), e)
            }
            paired
        }
    }

    /** Connects to the wireless-debugging TLS port. Returns true when connected. */
    suspend fun connect(host: String, port: Int): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            if (manager.isConnected) return@withContext true
            val connected = try {
                manager.connect(host, port)
            } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                throw e
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                Log.w(TAG, "connect($host:$port) threw", e)
                // The connection may have come up before a secondary failure; trust the state.
                if (runCatching { manager.isConnected }.getOrDefault(false)) true
                else throw AdbException(describe(e, "Could not connect to wireless debugging"), e)
            }
            connected || manager.isConnected
        }
    }

    suspend fun disconnect() = lock.withLock {
        withContext(Dispatchers.IO) { runCatching { manager.disconnect() } }
        Unit
    }

    /**
     * Runs [cmd] through `sh` on the device as the shell user. Returns (exit code, combined output).
     * Exit codes come from a trailing marker since the plain shell service doesn't report them.
     */
    suspend fun shell(cmd: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        requireConnected()
        val marker = "__ANDLIN_EXIT_${Random().nextInt(Int.MAX_VALUE)}__"
        val stream = manager.openStream("shell:{ $cmd ; } 2>&1; echo $marker\$?")
        val output = stream.use { readAll(it) }
        val idx = output.lastIndexOf(marker)
        if (idx < 0) return@withContext -1 to output.trim()
        val code = output.substring(idx + marker.length).trim().takeWhile { it.isDigit() || it == '-' }.toIntOrNull() ?: -1
        code to output.substring(0, idx).trim()
    }

    /**
     * Installs (or updates, -r) [apk] by streaming it to `cmd package install -S <size>` over an
     * exec: service, so nothing has to be staged on shared storage.
     */
    suspend fun install(apk: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            requireConnected()
            val size = apk.length()
            val result = try {
                val stream = manager.openStream("exec:cmd package install -r -S $size")
                stream.use { s ->
                    s.openOutputStream().let { out ->
                        apk.inputStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var sent = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                sent += n
                                onProgress(sent, size)
                            }
                        }
                        out.flush()
                    }
                    readAll(s)
                }
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                Log.w(TAG, "streamed install failed, falling back to pm install from /data/local/tmp", e)
                null
            }
            if (result != null && result.contains("Success")) return@withContext true to result.trim()
            if (result != null && result.contains("Failure")) return@withContext false to result.trim()
            // Fallback: stage the APK, then install from the file.
            val staged = "/data/local/tmp/andlin-companion.apk"
            val upload = manager.openStream("exec:sh -c 'head -c $size > $staged'")
            upload.use { s ->
                val out = s.openOutputStream()
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.flush()
                readAll(s)
            }
            val (code, out) = shell("pm install -r $staged; rm -f $staged")
            (code == 0 && out.contains("Success")) to out
        }

    /** Grants a runtime or development permission. Returns (ok, output). */
    suspend fun grant(pkg: String, permission: String): Pair<Boolean, String> {
        val (code, out) = shell("pm grant $pkg $permission")
        return (code == 0) to out
    }

    /** Sets an app-op mode, e.g. appops set <pkg> MANAGE_EXTERNAL_STORAGE allow. */
    suspend fun setAppOp(pkg: String, op: String, mode: String = "allow"): Pair<Boolean, String> {
        val (code, out) = shell("appops set $pkg $op $mode")
        return (code == 0) to out
    }

    private fun requireConnected() {
        if (!isConnected) throw AdbException("Not connected to wireless debugging")
    }

    private fun readAll(stream: AdbStream): String {
        val bytes = ByteArrayOutputStream()
        val input = stream.openInputStream()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                bytes.write(buf, 0, n)
            }
        } catch (e: IOException) {
            // The remote side closing the stream surfaces as an IOException on some versions.
            if (!stream.isClosed) throw e
        }
        return bytes.toString("UTF-8")
    }

    private fun loadOrCreateKey() {
        installConscrypt()
        if (keyFile.exists() && certFile.exists()) {
            try {
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Stored ADB key unreadable, generating a new one", e)
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 30L * 365 * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=AndLin")
        val extensions = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pair.public).identifier))
        }
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(subject))
            set("key", CertificateX509Key(pair.public))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(subject))
            set("extensions", extensions)
        }
        val cert = X509CertImpl(info)
        cert.sign(pair.private, algorithm)
        keyDir.mkdirs()
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        privateKey = pair.private
        certificate = cert
    }

    companion object {
        private const val TAG = "AdbClient"

        @Volatile private var instance: AdbClient? = null

        fun get(context: Context): AdbClient =
            instance ?: synchronized(this) { instance ?: AdbClient(context).also { instance = it } }

        @Volatile private var conscryptInstalled = false

        /** Pairing needs TLS 1.3 exporters that only Conscrypt provides on older Android versions. */
        @Synchronized
        fun installConscrypt() {
            if (conscryptInstalled) return
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (e: Throwable) {
                Log.w(TAG, "Could not install Conscrypt", e)
            }
            conscryptInstalled = true
        }

        /**
         * Some devices close the pairing socket before libadb finishes reading the peer info,
         * after the key has already been accepted. Pairing results are still verified by the
         * subsequent connect, which fails with AdbPairingRequiredException if this was wrong.
         */
        private fun isPairingAcceptedError(e: Exception): Boolean {
            val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
            return e is java.io.EOFException && msg.isBlank() ||
                msg.contains("Socket closed", ignoreCase = true) && !msg.contains("handshake", ignoreCase = true)
        }

        private fun describe(e: Exception, prefix: String): String {
            val m = e.message ?: e.javaClass.simpleName
            return when {
                m.contains("ECONNREFUSED") || m.contains("Connection refused") ->
                    "$prefix: nothing is listening. Is wireless debugging still on?"
                m.contains("BAD_DECRYPT", ignoreCase = true) || m.contains("mismatch", ignoreCase = true) ||
                    m.contains("Unable to decrypt", ignoreCase = true) -> "$prefix: wrong pairing code."
                else -> "$prefix: $m"
            }
        }
    }
}
