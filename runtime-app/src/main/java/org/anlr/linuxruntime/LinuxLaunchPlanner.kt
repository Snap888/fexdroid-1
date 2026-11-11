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
        workingDirectory: File = importedExecutable.parentFile ?: rootfs,
        dependencyRootfs: File = rootfs
    ): LinuxLaunchPlan {
        require(importedExecutable.isFile) { "Imported application is missing" }
        val canonicalRoot = rootfs.canonicalFile
        val binary = importedExecutable.canonicalFile
        val elf = ElfInspector.inspect(binary) ?: error("Imported file is not a supported ELF executable")
        val canonicalDependencyRoot = dependencyRootfs.canonicalFile
        val report = ElfCompatibilityChecker.check(elf, canonicalDependencyRoot, backend, backendExecutable)
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
                    check(binary.canExecute()) { "Static native ELF does not have execute permission" }
                    executable = binary
                    commandArguments = arguments
                } else {
                    val patchedInterpreter = runCatching { File(elf.interpreter).canonicalFile }.getOrNull()
                    executable = if (patchedInterpreter != null &&
                        patchedInterpreter.toPath().startsWith(canonicalRoot.toPath()) &&
                        patchedInterpreter.isFile
                    ) {
                        patchedInterpreter
                    } else {
                        File(canonicalRoot, elf.interpreter.removePrefix("/")).canonicalFile
                    }
                    check(executable.toPath().startsWith(canonicalRoot.toPath()) && executable.isFile) {
                        "ELF interpreter escapes rootfs or is missing"
                    }
                    val libraryPath = libraryDirs.map { File(canonicalRoot, it) }
                        .filter { it.isDirectory }.joinToString(":") { it.absolutePath }
                    commandArguments = listOf("--library-path", libraryPath, binary.absolutePath) + arguments
                }
            }
            ExecutionBackend.FEX_X86_64, ExecutionBackend.BOX64_X86_64 -> {
                executable = backendExecutable!!.canonicalFile
                val backendElf = ElfInspector.inspect(executable)
                    ?: error("Translation backend is not a valid ELF executable")
                val backendReport = ElfCompatibilityChecker.check(
                    backendElf, canonicalRoot, ExecutionBackend.NATIVE_ARM64
                )
                check(backendReport.compatible) {
                    "Translation backend is incompatible with ARM64 host rootfs: " + backendReport.reasons.joinToString("; ")
                }
                commandArguments = listOf(binary.absolutePath) + arguments
            }
        }
        val cwd = workingDirectory.canonicalFile
        require(cwd.isDirectory) { "Application working directory does not exist" }
        val environment = mutableMapOf(
            "HOME" to canonicalRoot.absolutePath,
            "PATH" to listOf("bin", "usr/bin", "sbin", "usr/sbin")
                .map { File(canonicalRoot, it) }
                .filter { it.isDirectory }.joinToString(":") { it.absolutePath },
            "LD_LIBRARY_PATH" to arm64LibraryDirs.map { File(canonicalRoot, it) }
                .filter { it.isDirectory }.joinToString(":") { it.absolutePath },
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "ANLR_ROOTFS" to canonicalRoot.absolutePath
        )
        environment["FXD_ROOT"] = canonicalRoot.absolutePath
        if (backend == ExecutionBackend.NATIVE_ARM64) {
            val pathShim = File(canonicalRoot, "usr/lib/fexdroid/libfxpath.so")
            if (pathShim.isFile) environment["LD_PRELOAD"] = pathShim.absolutePath
        }
        return LinuxLaunchPlan(backend, executable, commandArguments, cwd, environment, report)
    }
}
