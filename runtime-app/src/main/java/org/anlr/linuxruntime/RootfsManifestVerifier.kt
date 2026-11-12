package org.anlr.linuxruntime

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class VerifiedRootfsManifest(
    val releaseId: String,
    val architecture: String,
    val distribution: String,
    val distributionVersion: String,
    val runtimeAbi: String,
    val archiveFormat: String,
    val downloadUrl: String,
    val archiveSizeBytes: Long,
    val expandedSizeBytes: Long,
    val archiveSha256: String,
    val manifestSha256: String,
    val keyId: String
)

/**
 * Verifies a detached Ed25519 signature over the exact manifest bytes.
 *
 * The trusted X.509 SubjectPublicKeyInfo key is injected by the application;
 * this class intentionally contains no fabricated/default trust anchor.
 */
internal object RootfsManifestVerifier {
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private val releaseIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val digestPattern = Regex("[a-fA-F0-9]{64}")

    fun verify(
        manifestBytes: ByteArray,
        signatureBase64: String,
        trustedPublicKeyBase64: String,
        expectedKeyId: String,
        maxArchiveSizeBytes: Long,
        maxExpandedSizeBytes: Long
    ): VerifiedRootfsManifest {
        require(manifestBytes.isNotEmpty() && manifestBytes.size <= MAX_MANIFEST_BYTES) {
            "Manifest size is invalid"
        }
        require(expectedKeyId.isNotBlank()) { "Trusted key ID is required" }
        require(maxArchiveSizeBytes > 0 && maxExpandedSizeBytes > 0) { "Rootfs size limits must be positive" }

        val signatureBytes = decodeBase64(signatureBase64, "signature")
        require(signatureBytes.size == 64) { "Ed25519 signature must be 64 bytes" }
        val publicKeyBytes = decodeBase64(trustedPublicKeyBase64, "public key")
        val publicKey = KeyFactory.getInstance("Ed25519", BouncyCastleProvider())
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider())
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        check(verifier.verify(signatureBytes)) { "Rootfs manifest signature is invalid" }

        // Parse only after authenticating the exact byte sequence.
        val json = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val schemaVersion = json.getInt("schemaVersion")
        require(schemaVersion == 1) { "Unsupported rootfs manifest schema" }
        val keyId = json.getString("keyId")
        require(keyId == expectedKeyId) { "Manifest was signed by an unexpected key ID" }

        val releaseId = json.getString("releaseId")
        require(releaseIdPattern.matches(releaseId)) { "Invalid release ID" }
        val architecture = json.getString("architecture")
        require(architecture == "aarch64") { "Only ARM64 rootfs is supported" }
        val distribution = json.optString("distribution", "Debian")
        require(distribution == "Debian" || distribution == "Ubuntu") {
            "Unsupported Linux distribution: $distribution"
        }
        val distributionVersion = json.optString("distributionVersion", if (distribution == "Debian") "Trixie" else "24.04")
        require(distributionVersion.isNotBlank() && distributionVersion.length <= 32) {
            "Invalid Linux distribution version"
        }
        val runtimeAbi = json.getString("runtimeAbi")
        require(runtimeAbi == "anlr-glibc-v1") { "Rootfs was not built for the ANLR Android ABI" }
        val archiveFormat = json.getString("archiveFormat")
        require(archiveFormat == "tar.gz") { "Unsupported rootfs archive format" }

        val url = json.getString("downloadUrl")
        val parsedUrl = URI(url)
        require(parsedUrl.scheme == "https" && !parsedUrl.host.isNullOrBlank() &&
            parsedUrl.userInfo == null && parsedUrl.fragment == null) {
            "Rootfs download URL must be a safe HTTPS URL"
        }

        val size = json.getLong("archiveSizeBytes")
        require(size in 1..maxArchiveSizeBytes) { "Rootfs archive size exceeds configured limit" }
        val expandedSize = json.getLong("expandedSizeBytes")
        require(expandedSize in 1..maxExpandedSizeBytes) { "Expanded rootfs size exceeds configured limit" }
        val archiveSha256 = json.getString("archiveSha256")
        require(digestPattern.matches(archiveSha256)) { "Invalid archive SHA-256" }

        val manifestDigest = MessageDigest.getInstance("SHA-256").digest(manifestBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return VerifiedRootfsManifest(
            releaseId = releaseId,
            architecture = architecture,
            distribution = distribution,
            distributionVersion = distributionVersion,
            runtimeAbi = runtimeAbi,
            archiveFormat = archiveFormat,
            downloadUrl = parsedUrl.toASCIIString(),
            archiveSizeBytes = size,
            expandedSizeBytes = expandedSize,
            archiveSha256 = archiveSha256.lowercase(),
            manifestSha256 = manifestDigest,
            keyId = keyId
        )
    }

    private fun decodeBase64(value: String, label: String): ByteArray =
        try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64 $label", error)
        }
}
