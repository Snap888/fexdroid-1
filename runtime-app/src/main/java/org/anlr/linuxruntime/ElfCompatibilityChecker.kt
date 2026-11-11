package org.anlr.linuxruntime

import java.io.File

internal enum class ExecutionBackend { NATIVE_ARM64, FEX_X86_64, BOX64_X86_64 }

internal data class ElfCompatibilityReport(
    val compatible: Boolean,
    val backend: ExecutionBackend?,
    val reasons: List<String>,
    val missingLibraries: List<String>,
    val resolvedLibraries: Map<String, String>
)

/**
 * Conservative preflight. A positive result means required files were found,
 * not that symbol versions, relocations, syscalls or graphics APIs are supported.
 */
internal object ElfCompatibilityChecker {
    private val arm64LibraryDirs = listOf(
        "lib", "lib64", "usr/lib", "usr/lib64",
        "lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu"
    )
    private val x64LibraryDirs = listOf(
        "lib", "lib64", "usr/lib", "usr/lib64",
        "lib/x86_64-linux-gnu", "usr/lib/x86_64-linux-gnu"
    )

    fun check(
        elf: ElfInfo,
        rootfs: File,
        backend: ExecutionBackend,
        backendExecutable: File? = null,
        hostSupportsArm64: Boolean = true
    ): ElfCompatibilityReport {
        val reasons = ArrayList<String>()
        val missing = ArrayList<String>()
        val resolved = LinkedHashMap<String, String>()

        if (!rootfs.isDirectory) {
            reasons += "Linux dependency rootfs is not installed"
            return ElfCompatibilityReport(false, backend, reasons, emptyList(), emptyMap())
        }
        val canonicalRoot = rootfs.canonicalFile
        if (!elf.loadable || elf.type !in setOf(2, 3)) reasons += "ELF is not executable/shared-object type"

        val expected = when (backend) {
            ExecutionBackend.NATIVE_ARM64 -> {
                if (!hostSupportsArm64) reasons += "Host Android ABI does not support ARM64"
                if (elf.machine != 183 || elf.elfClass != 2 || elf.byteOrder != 1) {
                    reasons += "Native backend requires little-endian ELF64 AArch64"
                }
                arm64LibraryDirs
            }
            ExecutionBackend.FEX_X86_64, ExecutionBackend.BOX64_X86_64 -> {
                if (elf.machine != 62 || elf.elfClass != 2 || elf.byteOrder != 1) {
                    reasons += "Selected x86-64 backend requires little-endian ELF64 x86-64"
                }
                if (backendExecutable?.isFile != true || !backendExecutable.canExecute()) {
                    reasons += "Selected translation backend is not installed or executable"
                }
                x64LibraryDirs
            }
        }

        elf.interpreter?.let { interpreter ->
            val candidate = resolveGuestPath(canonicalRoot, interpreter)
            if (candidate == null || !candidate.isFile) {
                reasons += "ELF interpreter is missing or escapes dependency rootfs: $interpreter"
            } else {
                resolved["PT_INTERP"] = candidate.absolutePath
            }
        }

        for (library in elf.neededLibraries.distinct()) {
            if (library.isBlank() || library.contains('/') || library == "." || library == "..") {
                missing += library
                continue
            }
            val candidate = expected.asSequence()
                .mapNotNull { directory -> resolveGuestPath(canonicalRoot, "/$directory/$library") }
                .firstOrNull { it.isFile }
            if (candidate == null) missing += library
            else resolved[library] = candidate.absolutePath
        }
        if (missing.isNotEmpty()) reasons += "Missing shared libraries: ${missing.joinToString()}"
        return ElfCompatibilityReport(reasons.isEmpty(), backend, reasons, missing, resolved)
    }

    /**
     * Resolve an absolute guest path without allowing a symlink in the rootfs
     * to redirect the loader or a dependency outside the selected rootfs.
     */
    private fun resolveGuestPath(root: File, guestPath: String): File? {
        if (!guestPath.startsWith("/") || guestPath.contains('\\')) return null
        val relative = guestPath.removePrefix("/")
        if (relative.isBlank() || relative.split('/').any { it == "." || it == ".." || it.isBlank() }) return null
        val candidate = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it.toPath().startsWith(root.toPath()) }
    }
}
