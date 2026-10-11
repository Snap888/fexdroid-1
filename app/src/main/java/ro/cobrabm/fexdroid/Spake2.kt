package ro.cobrabm.fexdroid

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519, as BoringSSL implements it (crypto/curve25519/spake25519.cc,
 * Apache-2.0): Android's Wireless debugging pairing (adb pairing_auth) runs it with the
 * pairing code. Written from that description for fexdroid; plain BigInteger arithmetic,
 * which is slow (tens of milliseconds) but runs once per pairing.
 *
 * Details that must match BoringSSL exactly:
 * - the private scalar is a 64-byte random number reduced mod l, times 8 (clears the cofactor);
 * - the password scalar is SHA-512(password) mod l, plus the multiple of l (0..7) that makes it
 *   divisible by 8 ("password scalar hack", on by default and kept by adb);
 * - alice masks with M, bob with N; the key is SHA-512 over length-prefixed
 *   (alice name, bob name, alice msg, bob msg, shared point, SHA-512(password)).
 */
class Spake2(private val alice: Boolean, private val myName: ByteArray, private val theirName: ByteArray,
             password: ByteArray, random: SecureRandom = SecureRandom()) {
    private val privateKey: BigInteger
    private val passwordScalar: BigInteger
    private val passwordHash: ByteArray = sha512(password)

    /** Our 32-byte message (P* = private*B + password*(M or N)). */
    val message: ByteArray

    init {
        val r = ByteArray(64).also(random::nextBytes)
        privateKey = leInt(r).mod(L).shiftLeft(3)
        val s = leInt(passwordHash).mod(L)
        // The k in 0..7 with s + k*l divisible by 8 (l is odd, so it exists and is unique).
        val k = (0..7).first { s.add(L.multiply(BigInteger.valueOf(it.toLong()))).and(SEVEN).signum() == 0 }
        passwordScalar = s.add(L.multiply(BigInteger.valueOf(k.toLong())))
        val mask = mul(if (alice) M else N, passwordScalar)
        message = encode(add(mul(B, privateKey), mask))
    }

    /** The 64-byte shared key from the peer's message; throws if it is not a curve point. */
    fun finish(theirMessage: ByteArray): ByteArray {
        require(theirMessage.size == 32) { "SPAKE2 message must be 32 bytes" }
        val qStar = decode(theirMessage) ?: throw IllegalArgumentException("SPAKE2 peer point is not on the curve")
        val peerMask = mul(if (alice) N else M, passwordScalar)
        val q = add(qStar, neg(peerMask))
        val shared = encode(mul(q, privateKey))
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            var n = b.size.toLong()
            repeat(8) { sha.update((n and 0xff).toByte()); n = n shr 8 }
            sha.update(b)
        }
        if (alice) { put(myName); put(theirName); put(message); put(theirMessage) }
        else { put(theirName); put(myName); put(theirMessage); put(message) }
        put(shared)
        put(passwordHash)
        return sha.digest()
    }

    /** An affine point on edwards25519 (-x^2 + y^2 = 1 + d x^2 y^2). */
    data class Point(val x: BigInteger, val y: BigInteger)

    companion object {
        val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        /** Order of the prime-order subgroup. */
        val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val SEVEN = BigInteger.valueOf(7)
        val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)
        val B: Point = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"))!!
        // BoringSSL's points (spake25519.cc lists their coordinates and encodings).
        val M: Point = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        val N: Point = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!

        fun sha512(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(b)

        fun leInt(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** Complete twisted Edwards addition (a = -1): valid for every pair of points. */
        fun add(a: Point, b: Point): Point {
            val t = D.multiply(a.x).multiply(b.x).multiply(a.y).multiply(b.y).mod(P)
            val x = a.x.multiply(b.y).add(a.y.multiply(b.x)).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P)
            val y = a.y.multiply(b.y).add(a.x.multiply(b.x)).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P)
            return Point(x, y)
        }

        fun neg(a: Point) = Point(a.x.negate().mod(P), a.y)

        /** k*a for any non-negative k (no reduction mod l: the points may have a small-order part). */
        fun mul(a: Point, k: BigInteger): Point {
            var r = IDENTITY
            for (i in k.bitLength() - 1 downTo 0) {
                r = add(r, r)
                if (k.testBit(i)) r = add(r, a)
            }
            return r
        }

        fun encode(a: Point): ByteArray {
            val out = ByteArray(32)
            val y = a.y.toByteArray().reversedArray() // little-endian, maybe with a sign byte
            System.arraycopy(y, 0, out, 0, minOf(32, y.size))
            if (a.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        /** RFC 8032 point decoding; null when the bytes are not a point. */
        fun decode(b: ByteArray): Point? {
            if (b.size != 32) return null
            val sign = (b[31].toInt() shr 7) and 1
            val yb = b.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
            val y = leInt(yb)
            if (y >= P) return null
            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val x2 = u.multiply(v.modInverse(P)).mod(P)
            var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (x.multiply(x).subtract(x2).mod(P).signum() != 0) {
                x = x.multiply(SQRT_M1).mod(P)
                if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
            }
            if (x.signum() == 0 && sign == 1) return null
            if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
            return Point(x, y)
        }
    }
}
