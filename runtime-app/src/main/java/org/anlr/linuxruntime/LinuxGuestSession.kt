package org.anlr.linuxruntime

import java.io.File
import java.security.MessageDigest

/** Connects verified launch plans to the native PTY transport. */
internal object LinuxGuestSession {
    fun startShell(
        rootfs: File,
        backend: ExecutionBackend = ExecutionBackend.NATIVE_ARM64,
        backendExecutable: File? = null,
        rows: Int = 24,
        columns: Int = 80,
        guestRootfs: File = if (backend == ExecutionBackend.NATIVE_ARM64) rootfs else File(rootfs, "usr/share/anlr/x86_64")
    ): NativePty {
        val shell = File(guestRootfs, "bin/sh").canonicalFile
        check(shell.isFile) { "Debian shell /bin/sh is missing" }
        val selectedBackendExecutable = backendExecutable ?: when (backend) {
            ExecutionBackend.NATIVE_ARM64 -> null
            ExecutionBackend.FEX_X86_64 -> File(rootfs, "usr/bin/FEX")
            ExecutionBackend.BOX64_X86_64 -> File(rootfs, "usr/bin/box64")
        }
        val plan = LinuxLaunchPlanner.plan(
            shell, rootfs, backend, selectedBackendExecutable,
            arguments = listOf("-i"),
            workingDirectory = guestRootfs,
            dependencyRootfs = guestRootfs
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
        columns: Int = 80,
        dependencyRootfs: File = if (backend == ExecutionBackend.NATIVE_ARM64) rootfs else File(rootfs, "usr/share/anlr/x86_64")
    ): NativePty {
        val executable = importedExecutable.canonicalFile
        require(executable.isFile) { "Imported application is missing" }
        val selectedRoot = dependencyRootfs.canonicalFile
        val stagedExecutable = if (backend == ExecutionBackend.NATIVE_ARM64) {
            executable
        } else {
            // FEX resolves absolute paths inside FEX_ROOTFS. Box64 reads the
            // executable through the host filesystem but uses guest libraries.
            val digestEngine = MessageDigest.getInstance("SHA-256")
            executable.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digestEngine.update(buffer, 0, count)
                }
            }
            val digest = digestEngine.digest().take(8)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val safeName = executable.name.replace(Regex("[^A-Za-z0-9._+-]"), "_").take(96)
                .ifBlank { "application" }
            val appDirectory = File(selectedRoot, "tmp/anlr-imports").apply {
                check(mkdirs() || isDirectory) { "Cannot create guest application staging directory" }
            }.canonicalFile
            check(appDirectory.toPath().startsWith(selectedRoot.toPath())) {
                "Guest application staging path escapes x86-64 rootfs"
            }
            val target = File(appDirectory, "$digest-$safeName")
            if (!target.isFile || target.length() != executable.length()) {
                executable.copyTo(target, overwrite = true)
            }
            check(target.setExecutable(true, false) || target.canExecute()) {
                "Cannot set executable permission on staged Linux application"
            }
            target
        }
        val selectedBackendExecutable = backendExecutable ?: when (backend) {
            ExecutionBackend.NATIVE_ARM64 -> null
            ExecutionBackend.FEX_X86_64 -> File(rootfs, "usr/bin/FEX")
            ExecutionBackend.BOX64_X86_64 -> File(rootfs, "usr/bin/box64")
        }
        val selectedWorkingDirectory = if (backend == ExecutionBackend.NATIVE_ARM64) {
            workingDirectory
        } else {
            stagedExecutable.parentFile ?: selectedRoot
        }
        val plan = LinuxLaunchPlanner.plan(
            stagedExecutable, rootfs, backend, selectedBackendExecutable,
            arguments, selectedWorkingDirectory, selectedRoot
        )
        return NativePty.spawn(
            plan.executable.absolutePath, plan.arguments,
            plan.workingDirectory.absolutePath, rows, columns, plan.environment, inheritEnvironment = false
        )
    }
}
