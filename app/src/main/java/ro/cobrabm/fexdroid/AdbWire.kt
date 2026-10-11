package ro.cobrabm.fexdroid

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * A small client for Android's Wireless debugging (adbd over TLS), enough to pair with a
 * pairing code and run a few shell commands. Used on the phone itself, over 127.0.0.1, to
 * switch off Android's child-process limit without a PC (PhantomFix.kt).
 *
 * Written for fexdroid from AOSP's adb sources (packages/modules/adb, Apache-2.0):
 * pairing_connection.cpp, pairing_auth.cpp, aes_128_gcm.cpp, tls_connection.cpp and the adb
 * wire protocol (protocol.txt). No Android API here, so it also runs in the JVM unit tests.
 *
 * [tls] supplies TLS 1.3 and the keying-material exporter the pairing needs (Conscrypt).
 */
class AdbWire(private val key: RSAPrivateCrtKey, private val tls: Tls, private val name: String = "fexdroid@phone") {

    interface Tls {
        val provider: Provider
        fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray
    }

    private val cert: X509Certificate = selfSignedCert(key)

    /** Pairs with the code shown under Wireless debugging › Pair device with pairing code. */
    fun pair(host: String, port: Int, code: String, timeoutMs: Int = 15_000) {
        tlsSocket(host, port, timeoutMs).use { s ->
            // adb appends 64 bytes exported from the TLS session to the code, so a pairing cannot
            // be relayed through a second connection.
            val exported = tls.exportKeyingMaterial(s, "adb-label\u0000", 64)
            val spake = Spake2(true, "adb pair client\u0000".toByteArray(), "adb pair server\u0000".toByteArray(),
                code.toByteArray() + exported)
            val out = s.outputStream
            val inp = DataInputStream(s.inputStream)
            writePacket(out, 0, spake.message)
            val theirMsg = readPacket(inp, 0)
            val gcm = PairingCipher(spake.finish(theirMsg))
            // PeerInfo: type 0 (RSA public key) + adb's public key line, padded to 8192 bytes.
            val info = ByteArray(8192)
            val pub = (androidPublicKey(key) + " " + name).toByteArray()
            System.arraycopy(pub, 0, info, 1, pub.size)
            writePacket(out, 1, gcm.encrypt(info))
            // A wrong code fails here: the device's answer does not decrypt (or it hangs up).
            val reply = try { readPacket(inp, 1) } catch (e: java.io.EOFException) {
                throw PairingRejected()
            }
            if (runCatching { gcm.decrypt(reply) }.isFailure) throw PairingRejected()
        }
    }

    class PairingRejected : Exception("pairing code rejected")

    /** Runs [command] through adbd's "exec:" service and returns what it printed. */
    fun exec(host: String, port: Int, command: String, timeoutMs: Int = 15_000): String {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), timeoutMs)
        plain.soTimeout = timeoutMs
        plain.use {
            writeMsg(plain.outputStream, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            val first = readMsg(DataInputStream(plain.inputStream))
            if (first.cmd != STLS) throw java.io.IOException("adbd answered ${cmdName(first.cmd)}, not STLS (not a TLS port?)")
            writeMsg(plain.outputStream, STLS, STLS_VERSION, 0, ByteArray(0))
            val s = context().socketFactory.createSocket(plain, host, port, true) as SSLSocket
            s.useClientMode = true
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.startHandshake()
            val inp = DataInputStream(s.inputStream)
            val out = s.outputStream
            var m = readMsg(inp)
            while (m.cmd != CNXN) {
                if (m.cmd == AUTH) throw java.io.IOException("adbd asked for AUTH: this key is not paired")
                m = readMsg(inp)
            }
            val localId = 1
            writeMsg(out, OPEN, localId, 0, "exec:$command\u0000".toByteArray())
            val result = ByteArrayOutputStream()
            var remoteId = 0
            while (true) {
                m = readMsg(inp)
                when (m.cmd) {
                    OKAY -> remoteId = m.arg0
                    WRTE -> { result.write(m.data); writeMsg(out, OKAY, localId, m.arg0, ByteArray(0)) }
                    CLSE -> { if (remoteId != 0) writeMsg(out, CLSE, localId, remoteId, ByteArray(0)); break }
                }
            }
            return result.toString(Charsets.UTF_8)
        }
    }

    private fun context(): SSLContext {
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(t: String?, i: Array<out Principal>?) = arrayOf("adb")
            override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?) = "adb"
            override fun getServerAliases(t: String?, i: Array<out Principal>?) = null
            override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?) = null
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = key
            override fun chooseEngineClientAlias(t: Array<out String>?, i: Array<out Principal>?, e: javax.net.ssl.SSLEngine?) = "adb"
        }
        // adbd's certificate is self-signed; pairing (or the paired key) is what authenticates.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        return SSLContext.getInstance("TLSv1.3", tls.provider).apply { init(arrayOf(km), arrayOf(trustAll), null) }
    }

    private fun tlsSocket(host: String, port: Int, timeoutMs: Int): SSLSocket {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), timeoutMs)
        plain.soTimeout = timeoutMs
        val s = context().socketFactory.createSocket(plain, host, port, true) as SSLSocket
        s.useClientMode = true
        s.enabledProtocols = arrayOf("TLSv1.3")
        s.startHandshake()
        return s
    }

    /** AES-128-GCM keyed by HKDF-SHA256 of the SPAKE2 key; the nonce is a per-direction counter. */
    private class PairingCipher(keyMaterial: ByteArray) {
        private val key = SecretKeySpec(hkdf(keyMaterial, "adb pairing_auth aes-128-gcm key".toByteArray(), 16), "AES")
        private var encSeq = 0L
        private var decSeq = 0L
        private fun nonce(seq: Long) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array()
        fun encrypt(b: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(encSeq++))); doFinal(b)
        }
        fun decrypt(b: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(decSeq++))); doFinal(b)
        }
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    companion object {
        private const val CNXN = 0x4e584e43
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val AUTH = 0x48545541
        private const val STLS = 0x534c5453
        private const val VERSION = 0x01000001
        private const val STLS_VERSION = 0x01000000
        private const val MAX_DATA = 1024 * 1024

        private fun cmdName(c: Int) = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c).array())

        /** Pairing packet: version 1, type, big-endian payload size, payload. */
        private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
            out.write(ByteBuffer.allocate(6).put(1).put(type.toByte()).putInt(payload.size).array())
            out.write(payload)
            out.flush()
        }

        private fun readPacket(inp: DataInputStream, type: Int): ByteArray {
            val version = inp.readUnsignedByte()
            val t = inp.readUnsignedByte()
            val size = inp.readInt()
            if (version != 1 || t != type || size <= 0 || size > 16384) throw java.io.IOException("bad pairing packet ($version, $t, $size)")
            return ByteArray(size).also(inp::readFully)
        }

        private fun writeMsg(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
            val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
            h.putInt(data.sumOf { it.toInt() and 0xff }) // checked by adbd only before version 0x01000001
            h.putInt(cmd.inv())
            out.write(h.array())
            out.write(data)
            out.flush()
        }

        private fun readMsg(inp: InputStream): Msg {
            val hb = ByteArray(24).also { DataInputStream(inp).readFully(it) }
            val h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int
            if (len < 0 || len > MAX_DATA) throw java.io.IOException("bad adb message length $len")
            return Msg(cmd, a0, a1, ByteArray(len).also { DataInputStream(inp).readFully(it) })
        }

        fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArrayOutputStream()
            var t = ByteArray(0)
            var i = 1
            while (out.size() < length) {
                mac.update(t); mac.update(info); mac.update(i++.toByte())
                t = mac.doFinal(); out.write(t)
            }
            return out.toByteArray().copyOf(length)
        }

        fun newKey(): RSAPrivateCrtKey =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as RSAPrivateCrtKey

        fun loadKey(pkcs8: ByteArray): RSAPrivateCrtKey =
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateCrtKey

        /**
         * adb's public key format (android_pubkey_encode), base64: modulus size in words, n0inv,
         * modulus and R^2 mod n little-endian, exponent.
         */
        fun androidPublicKey(k: RSAPrivateCrtKey): String {
            val n = k.modulus
            val words = 64
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
            val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
            val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(words).putInt(n0inv.toInt())
            b.put(le(n, 256)).put(le(rr, 256)).putInt(k.publicExponent.toInt())
            return java.util.Base64.getEncoder().encodeToString(b.array())
        }

        private fun le(v: BigInteger, size: Int): ByteArray {
            val be = v.toByteArray()
            val out = ByteArray(size)
            for (i in 0 until minOf(size, be.size)) out[i] = be[be.size - 1 - i]
            return out
        }

        /** A minimal self-signed X.509 v3 certificate for the key (adbd only looks at the key). */
        fun selfSignedCert(k: RSAPrivateCrtKey): X509Certificate {
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(k.modulus, k.publicExponent))
            val alg = der(0x30, der(0x06, oid(1, 2, 840, 113549, 1, 1, 11)) + byteArrayOf(0x05, 0x00))
            val name = der(0x30, der(0x31, der(0x30, der(0x06, oid(2, 5, 4, 3)) + der(0x0c, "fexdroid".toByteArray()))))
            val now = System.currentTimeMillis()
            val validity = der(0x30, utc(Date(now - 86_400_000L)) + utc(Date(now + 3650L * 86_400_000L)))
            val tbs = der(0x30,
                der(0xa0, der(0x02, byteArrayOf(2))) + der(0x02, byteArrayOf(1)) + alg + name + validity + name + pub.encoded)
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(k); update(tbs); sign() }
            val cert = der(0x30, tbs + alg + der(0x03, byteArrayOf(0) + sig))
            return CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
        }

        private fun utc(d: Date): ByteArray {
            // UTCTime covers 1950-2049; GeneralizedTime after that.
            val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d).toInt()
            val (tag, fmt) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
            return der(tag, SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d).toByteArray())
        }

        private fun oid(vararg parts: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(parts[0] * 40 + parts[1])
            for (p in parts.drop(2)) {
                val bytes = mutableListOf(p and 0x7f)
                var v = p shr 7
                while (v > 0) { bytes.add(0, (v and 0x7f) or 0x80); v = v shr 7 }
                bytes.forEach(out::write)
            }
            return out.toByteArray()
        }

        private fun der(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = content.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}
