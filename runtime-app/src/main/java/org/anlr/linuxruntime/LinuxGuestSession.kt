package org.anlr.linuxruntime

import java.io.File

/** Connects verified launch plans to the native PTY transport. */
internal object LinuxGuestSession {
    fun startShell(
        rootfs: File,
        backend: ExecutionBackend = ExecutionBackend.NATIVE_ARM64,
        backendExecutable: File? = null,
        rows: Int = 24,
        columns: Int = 80
    ): NativePty {
        val shell = File(rootfs, "bin/sh").canonicalFile
        check(shell.isFile) { "Debian shell /bin/sh is missing" }
        val plan = LinuxLaunchPlanner.plan(
            shell, rootfs, backend, backendExecutable,
            arguments = listOf("-i"),
            workingDirectory = rootfs
        )
        return NativePty.spawn(
            plan.executable.absolutePath, plan.arguments,
            plan.workingDirectory.absolutePath, rows, columns, plan.environment, inheritEnvironment = false
        )
    }

    fun startImportedApplication(
        importedExecutable: File,
        rootfs: File,
        backend: ExecutionBackend,
        backendExecutable: File? = null,
        arguments: List<String> = emptyList(),
        workingDirectory: File = importedExecutable.parentFile ?: rootfs,
        rows: Int = 24,
        columns: Int = 80
    ): NativePty {
        val plan = LinuxLaunchPlanner.plan(
            importedExecutable, rootfs, backend, backendExecutable,
            arguments, workingDirectory
        )
        return NativePty.spawn(
            plan.executable.absolutePath, plan.arguments,
            plan.workingDirectory.absolutePath, rows, columns, plan.environment, inheritEnvironment = false
        )
    }
}
