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
        val selectedBackendExecutable = backendExecutable ?: when (backend) {
            ExecutionBackend.NATIVE_ARM64 -> null
            ExecutionBackend.FEX_X86_64 -> File(canonicalRoot, "usr/bin/FEX")
            ExecutionBackend.BOX64_X86_64 -> File(canonicalRoot, "usr/bin/box64")
        }
        val report = ElfCompatibilityChecker.check(
            elf, canonicalDependencyRoot, backend, selectedBackendExecutable
        )
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
                executable = requireNotNull(selectedBackendExecutable).canonicalFile
                val backendElf = ElfInspector.inspect(executable)
                    ?: error("Translation backend is not a valid ELF executable")
                val backendReport = ElfCompatibilityChecker.check(
                    backendElf, canonicalRoot, ExecutionBackend.NATIVE_ARM64
                )
                check(backendReport.compatible) {
                    "Translation backend is incompatible with ARM64 host rootfs: " + backendReport.reasons.joinToString("; ")
                }
                commandArguments = when (backend) {
                    ExecutionBackend.FEX_X86_64 -> {
                        val relative = binary.toPath().normalize().let { path ->
                            require(path.startsWith(canonicalDependencyRoot.toPath())) {
                                "FEX executable must be staged inside its x86-64 guest rootfs"
                            }
                            canonicalDependencyRoot.toPath().relativize(path).toString()
                        }
                        listOf("/" + relative.replace(File.separatorChar, '/')) + arguments
                    }
                    ExecutionBackend.BOX64_X86_64 -> listOf(binary.absolutePath) + arguments
                    else -> error("Unsupported translation backend")
                }
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
        if (backend == ExecutionBackend.FEX_X86_64) {
            val guestRoot = File(canonicalRoot, "usr/share/anlr/x86_64").canonicalFile
            check(guestRoot.isDirectory && guestRoot == canonicalDependencyRoot) {
                "ANLR FEX guest rootfs is missing or does not match the packaged guest path"
            }
            environment["FEX_ROOTFS"] = guestRoot.absolutePath
            val fexHome = File(canonicalRoot, "home/anlr/.fex-emu")
            fexHome.mkdirs()
            File(canonicalRoot, "home/anlr/.cache/fex-emu").mkdirs()
            environment["FEX_APP_CONFIG_LOCATION"] = fexHome.absolutePath + "/"
            environment["FEX_APP_DATA_LOCATION"] = fexHome.absolutePath + "/"
            environment["FEX_APP_CACHE_LOCATION"] = File(canonicalRoot, "home/anlr/.cache/fex-emu").absolutePath + "/"
        }
        if (backend == ExecutionBackend.BOX64_X86_64) {
            environment["BOX64_LD_LIBRARY_PATH"] = x64LibraryDirs
                .map { File(canonicalDependencyRoot, it) }
                .filter { it.isDirectory }
                .joinToString(":") { it.absolutePath }
            environment["BOX64_DYNAREC"] = "1"
            environment["BOX64_NOBANNER"] = "1"
        }
        if (backend == ExecutionBackend.NATIVE_ARM64) {
            val pathShim = File(canonicalRoot, "usr/lib/fexdroid/libfxpath.so")
            if (pathShim.isFile) environment["LD_PRELOAD"] = pathShim.absolutePath
        }
        return LinuxLaunchPlan(backend, executable, commandArguments, cwd, environment, report)
    }
}
