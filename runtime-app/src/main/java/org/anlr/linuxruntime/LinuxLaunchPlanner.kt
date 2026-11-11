package org.anlr.linuxruntime

import java.io.File

internal data class LinuxLaunchPlan(
    val backend: ExecutionBackend,
    val executable: File,
    val arguments: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String>,
    val compatibility: ElfCompatibilityReport
)

/**
 * Converts an imported ELF plus a selected backend into an explicit process plan.
 * It never executes a file; callers must show the compatibility report and launch
 * only after user intent and runtime policy checks.
 */
internal object LinuxLaunchPlanner {
    private val arm64LibraryDirs = listOf(
        "lib", "usr/lib", "lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu"
    )
    private val x64LibraryDirs = listOf(
        "lib", "usr/lib", "lib/x86_64-linux-gnu", "usr/lib/x86_64-linux-gnu"
    )

    fun plan(
        importedExecutable: File,
        rootfs: File,
        backend: ExecutionBackend,
        backendExecutable: File? = null,
        arguments: List<String> = emptyList(),
        workingDirectory: File = importedExecutable.parentFile ?: rootfs
    ): LinuxLaunchPlan {
        require(importedExecutable.isFile) { "Imported application is missing" }
        val canonicalRoot = rootfs.canonicalFile
        val binary = importedExecutable.canonicalFile
        val elf = ElfInspector.inspect(binary) ?: error("Imported file is not a supported ELF executable")
        val report = ElfCompatibilityChecker.check(elf, canonicalRoot, backend, backendExecutable)
        check(report.compatible) { report.reasons.joinToString("; ") }

        val executable: File
        val commandArguments: List<String>
        val libraryDirs = when (backend) {
            ExecutionBackend.NATIVE_ARM64 -> arm64LibraryDirs
            ExecutionBackend.FEX_X86_64, ExecutionBackend.BOX64_X86_64 -> x64LibraryDirs
        }
        when (backend) {
            ExecutionBackend.NATIVE_ARM64 -> {
                if (elf.interpreter == null) {
                    executable = binary
                    commandArguments = arguments
                } else {
                    executable = File(canonicalRoot, elf.interpreter.removePrefix("/")).canonicalFile
                    check(executable.toPath().startsWith(canonicalRoot.toPath())) {
                        "ELF interpreter escapes rootfs"
                    }
                    val libraryPath = libraryDirs.map { File(canonicalRoot, it) }
                        .filter { it.isDirectory }.joinToString(":") { it.absolutePath }
                    commandArguments = listOf("--library-path", libraryPath, binary.absolutePath) + arguments
                }
            }
            ExecutionBackend.FEX_X86_64, ExecutionBackend.BOX64_X86_64 -> {
                executable = backendExecutable!!.canonicalFile
                commandArguments = listOf(binary.absolutePath) + arguments
            }
        }
        val cwd = workingDirectory.canonicalFile
        require(cwd.isDirectory) { "Application working directory does not exist" }
        val environment = mapOf(
            "HOME" to canonicalRoot.absolutePath,
            "PATH" to libraryDirs.map { File(canonicalRoot, "$it/bin") }
                .plus(File(canonicalRoot, "usr/bin"))
                .joinToString(":") { it.absolutePath },
            "LD_LIBRARY_PATH" to libraryDirs.map { File(canonicalRoot, it) }
                .filter { it.isDirectory }.joinToString(":") { it.absolutePath },
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "ANLR_ROOTFS" to canonicalRoot.absolutePath
        )
        return LinuxLaunchPlan(backend, executable, commandArguments, cwd, environment, report)
    }
}
