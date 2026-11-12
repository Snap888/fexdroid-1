package org.anlr.linuxruntime

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class RootfsManifestVerifierTest {
    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider()).generateKeyPair()

    private fun manifest(architecture: String = "aarch64"): ByteArray =
        JSONObject()
            .put("schemaVersion", 1)
            .put("keyId", "anlr-test-key")
            .put("releaseId", "debian-arm64-test")
            .put("architecture", architecture)
            .put("runtimeAbi", "anlr-glibc-v1")
            .put("archiveFormat", "tar.gz")
            .put("downloadUrl", "https://downloads.example.org/debian-rootfs.tar.gz")
            .put("archiveSizeBytes", 1234)
            .put("expandedSizeBytes", 8192)
            .put("archiveSha256", "a".repeat(64))
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun signed(bytes: ByteArray, pair: KeyPair): String {
        val signer = Signature.getInstance("Ed25519", BouncyCastleProvider())
        signer.initSign(pair.private)
        signer.update(bytes)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    @Test fun verifiesSignatureBeforeReturningValidatedManifest() {
        val pair = keyPair()
        val bytes = manifest()
        val result = RootfsManifestVerifier.verify(
            bytes, signed(bytes, pair),
            Base64.getEncoder().encodeToString(pair.public.encoded),
            "anlr-test-key", 4096, 16384
        )
        assertEquals("debian-arm64-test", result.releaseId)
        assertEquals("aarch64", result.architecture)
        assertEquals("anlr-glibc-v1", result.runtimeAbi)
        assertEquals(1234L, result.archiveSizeBytes)
        assertEquals(8192L, result.expandedSizeBytes)
        assertEquals("a".repeat(64), result.archiveSha256)
    }

    @Test fun rejectsTamperingWrongKeyAndWrongArchitecture() {
        val pair = keyPair()
        val bytes = manifest()
        val signature = signed(bytes, pair)
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val changed = manifest().also { it[it.lastIndex] = if (it.last().toInt() == 0) 1.toByte() else 0.toByte() }
        assertThrows(IllegalStateException::class.java) {
            RootfsManifestVerifier.verify(changed, signature, publicKey, "anlr-test-key", 4096, 16384)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestVerifier.verify(bytes, signature, publicKey, "other-key", 4096, 16384)
        }
        val wrongArch = manifest("x86_64")
        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestVerifier.verify(
                wrongArch, signed(wrongArch, pair), publicKey, "anlr-test-key", 4096, 16384
            )
        }
    }

    @Test fun rejectsOversizedArchivesAndUnsafeUrls() {
        val pair = keyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val tooLarge = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestVerifier.verify(
                tooLarge, signed(tooLarge, pair), publicKey, "anlr-test-key", 100, 16384
            )
        }
        val unsafe = JSONObject(String(manifest(), Charsets.UTF_8))
            .put("downloadUrl", "http://downloads.example.org/rootfs.tar.gz")
            .toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestVerifier.verify(
                unsafe, signed(unsafe, pair), publicKey, "anlr-test-key", 4096, 16384
            )
        }
    }
    @Test fun rejectsExpandedSizeAboveConfiguredLimit() {
        val pair = keyPair()
        val oversized = JSONObject(String(manifest(), Charsets.UTF_8))
            .put("expandedSizeBytes", 20000).toString().toByteArray()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestVerifier.verify(
                oversized, signed(oversized, pair), publicKey, "anlr-test-key", 4096, 16384
            )
        }
    }

    @Test fun rejectsStockDebianRuntimeAbi() {
        val pair = keyPair()
        val bytes = JSONObject(String(manifest(), Charsets.UTF_8))
            .put("runtimeAbi", "debian-stock").toString().toByteArray()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestVerifier.verify(
                bytes, signed(bytes, pair), publicKey, "anlr-test-key", 4096, 16384
            )
        }
    }

}
