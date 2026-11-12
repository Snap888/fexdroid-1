package org.anlr.linuxruntime

import android.system.Os
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Owns the native Xvfb, PulseAudio and XFCE processes. All guest processes are launched through
 * the ANLR-patched ARM64 loader plan and have independent PTYs so their output remains observable.
 */
internal class AnlrDesktopSession(
    private val rootfs: File,
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit = {}
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val processes = mutableListOf<NativePty>()
    @Volatile private var xvfbPty: NativePty? = null
    @Volatile var shmid: Int = -1
        private set

    val shmSocket: String get() = File(rootfs, "tmp/.fxshm/sock").absolutePath
    val presentSocket: String get() = File(rootfs, "tmp/fxpresent.sock").absolutePath
    val pulseSocket: String get() = File(rootfs, "tmp/pulse/native").absolutePath
    val audioFifo: String get() = File(rootfs, "tmp/anlr-pulse.fifo").absolutePath

    private fun launch(path: String, args: List<String>, env: Map<String, String> = emptyMap(), drainOutput: Boolean = true): NativePty {
        check(!closed.get()) { "Desktop session is stopped" }
        val executable = File(rootfs, path.removePrefix("/"))
        check(executable.isFile && executable.canExecute()) { "Missing executable: $path" }
        val plan = LinuxLaunchPlanner.plan(
            executable, rootfs, ExecutionBackend.NATIVE_ARM64,
            arguments = args, workingDirectory = rootfs,
            dependencyRootfs = rootfs
        )
        val merged = plan.environment + mapOf(
            "DISPLAY" to ":0",
            "XDG_RUNTIME_DIR" to File(rootfs, "tmp").absolutePath,
            "PULSE_SERVER" to "unix:$pulseSocket"
        ) + env
        return NativePty.spawn(
            plan.executable.absolutePath, plan.arguments, plan.workingDirectory.absolutePath,
            rows = 40, columns = 120, environment = merged, inheritEnvironment = false
        ).also { process ->
            synchronized(processes) { processes += process }
            if (drainOutput) thread(name = "anlr-${File(path).name}-log", isDaemon = true) {
                val bytes = ByteArray(2048)
                try { while (!closed.get()) { val n = process.read(bytes); if (n <= 0) break; log("[${File(path).name}] ${String(bytes, 0, n, Charsets.UTF_8).trimEnd()}") } }
                catch (_: Exception) { }
            }
        }
    }

    /** Starts Xvfb and waits until the server publishes its SysV framebuffer id. */
    fun start(): Int {
        require(width in 320..7680 && height in 240..4320) { "Unsupported desktop resolution" }
        check(shmid < 0) { "Desktop session already started" }
        File(rootfs, "tmp/.X0-lock").delete()
        File(rootfs, "tmp/.X11-unix").mkdirs()
        File(rootfs, "tmp/.fxshm").mkdirs()
        File(rootfs, "tmp/pulse").mkdirs()
        val xvfb = launch(
            "/usr/bin/Xvfb",
            listOf(":0", "-screen", "0", "${width}x${height}x24", "-shmem", "-ac", "-nolisten", "tcp", "-pn"),
            mapOf("LIBGL_DRIVERS_PATH" to File(rootfs, "usr/lib/aarch64-linux-gnu/dri").absolutePath),
            drainOutput = false
        )
        xvfbPty = xvfb
        val ready = CountDownLatch(1)
        thread(name = "anlr-xvfb-output", isDaemon = true) {
            val buffer = ByteArray(4096)
            val pending = StringBuilder()
            try {
                while (!closed.get()) {
                    val n = xvfb.read(buffer)
                    if (n <= 0) break
                    pending.append(String(buffer, 0, n, Charsets.UTF_8))
                    while (true) {
                        val end = pending.indexOf("\n")
                        if (end < 0) break
                        val line = pending.substring(0, end).trim()
                        pending.delete(0, end + 1)
                        if (line.isNotEmpty()) log("[Xvfb] $line")
                        Regex("screen 0 shmid (\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                            shmid = it
                            ready.countDown()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!closed.get()) log("[Xvfb] output stopped: ${e.message}")
            } finally {
                ready.countDown()
            }
        }
        check(ready.await(20, TimeUnit.SECONDS) && shmid >= 0) {
            "Xvfb did not publish a shared-memory framebuffer (check fxshmd and Xvfb logs)"
        }
        check(File(rootfs, "usr/bin/xdpyinfo").canExecute()) { "xdpyinfo is missing" }
        return shmid
    }

    /** Starts PulseAudio's native socket and pipes PCM to the Android AAudio bridge. */
    fun startAudio(): String {
        val pulse = File(rootfs, "usr/bin/pulseaudio")
        check(pulse.canExecute()) { "PulseAudio is missing from the installed rootfs" }
        val fifo = File(audioFifo)
        fifo.delete()
        Os.mkfifo(fifo.absolutePath, 384)
        val audioStatus = AnlrAudioBridge.start(fifo.absolutePath)
        check(audioStatus.contains("-> AAudio")) { audioStatus }
        val moduleDir = rootfs.resolve("usr/lib").listFiles()
            ?.firstOrNull { it.name.startsWith("pulse-") }?.resolve("modules")
            ?: error("PulseAudio modules directory is missing")
        launch(
            "/usr/bin/pulseaudio",
            listOf(
                "-n", "--daemonize=no", "--exit-idle-time=-1", "--use-pid-file=no",
                "--disable-shm=yes", "--dl-search-path=${moduleDir.absolutePath}", "--log-target=stderr",
                "-L", "module-pipe-sink file=$audioFifo sink_name=android format=s16le rate=48000 channels=2",
                "-L", "module-native-protocol-unix auth-anonymous=1 socket=$pulseSocket"
            )
        )
        return audioStatus
    }

    /** Runs a D-Bus session whose lifetime is tied to startxfce4. */
    fun startXfce(): NativePty {
        check(shmid >= 0) { "Start Xvfb before XFCE4" }
        val dbus = File(rootfs, "usr/bin/dbus-launch")
        check(dbus.canExecute()) { "dbus-launch is missing from the installed rootfs" }
        check(File(rootfs, "usr/bin/startxfce4").canExecute()) { "startxfce4 is missing" }
        return launch(
            "/usr/bin/dbus-launch",
            listOf("--exit-with-session", "/usr/bin/startxfce4"),
            mapOf("DISPLAY" to ":0", "XDG_SESSION_TYPE" to "x11", "XDG_CURRENT_DESKTOP" to "XFCE")
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val all = synchronized(processes) { processes.toList().asReversed().also { processes.clear() } }
        all.forEach { p -> runCatching { p.sendSignal(15) } }
        all.forEach { p -> runCatching { p.close() } }
        runCatching { AnlrDisplayBridge.stop() }
        shmid = -1
        File(rootfs, "tmp/.X0-lock").delete()
        File(audioFifo).delete()
    }
}
