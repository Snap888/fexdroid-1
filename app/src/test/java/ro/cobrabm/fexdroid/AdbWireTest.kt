package ro.cobrabm.fexdroid

import org.conscrypt.Conscrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.security.Provider
import javax.net.ssl.SSLSocket

class AdbWireTest {
    @Test
    fun spakePointsAreBoringSslOnes() {
        // Coordinates as listed in BoringSSL's spake25519.cc.
        assertEquals(BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"), Spake2.M.x)
        assertEquals(BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"), Spake2.M.y)
        assertEquals(BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"), Spake2.N.x)
        assertEquals(BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"), Spake2.N.y)
        // The base point has order l.
        assertEquals(Spake2.IDENTITY, Spake2.mul(Spake2.B, Spake2.L))
    }

    @Test
    fun spakeBothSidesAgree() {
        val a = "adb pair client\u0000".toByteArray(); val b = "adb pair server\u0000".toByteArray()
        val alice = Spake2(true, a, b, "123456".toByteArray())
        val bob = Spake2(false, b, a, "123456".toByteArray())
        assertArrayEquals(alice.finish(bob.message), bob.finish(alice.message))
        val mallory = Spake2(false, b, a, "654321".toByteArray())
        val alice2 = Spake2(true, a, b, "123456".toByteArray())
        assertFalse(alice2.finish(mallory.message).contentEquals(mallory.finish(alice2.message)))
    }

    @Test
    fun hkdfMatchesRfc5869Case3() {
        // RFC 5869 test case 3: no salt, no info.
        val ikm = ByteArray(22) { 0x0b }
        val okm = AdbWire.hkdf(ikm, ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun certificateAndPublicKey() {
        val key = AdbWire.newKey()
        val cert = AdbWire.selfSignedCert(key)
        cert.verify(cert.publicKey)
        assertEquals(524, java.util.Base64.getDecoder().decode(AdbWire.androidPublicKey(key)).size)
    }

    private val conscryptTls = object : AdbWire.Tls {
        override val provider: Provider = Conscrypt.newProvider()
        override fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray =
            Conscrypt.exportKeyingMaterial(socket, label, null, length)
    }

    /**
     * Against a real adbd (an emulator or phone): ADBWIRE_HOST, ADBWIRE_PAIR_PORT and ADBWIRE_CODE
     * from Wireless debugging › Pair device with pairing code, ADBWIRE_PORT for the connection.
     */
    @Test
    fun pairAndExecOnRealDevice() {
        val host = System.getenv("ADBWIRE_HOST")
        assumeTrue("ADBWIRE_HOST not set", host != null)
        val keyFile = java.io.File(System.getenv("ADBWIRE_KEY") ?: "build/adbwire-test.key")
        val key = if (keyFile.isFile) AdbWire.loadKey(keyFile.readBytes())
            else AdbWire.newKey().also { keyFile.writeBytes(it.encoded) }
        val wire = AdbWire(key, conscryptTls, "fexdroid-test@pc")
        System.getenv("ADBWIRE_CODE")?.let { wire.pair(host!!, System.getenv("ADBWIRE_PAIR_PORT").toInt(), it) }
        System.getenv("ADBWIRE_PORT")?.let {
            // Twice: PhantomFix opens a second connection right after the first one.
            repeat(2) { _ -> assertEquals("2000", wire.exec(host!!, it.toInt(), "id -u").trim()) } // shell
        }
    }
}
