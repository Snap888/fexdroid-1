package org.anlr.linuxruntime

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { RuntimeHome() } } }
    }
}

private data class Probe(val title: String, val value: String, val detail: String)

private fun inspectDevice(): List<Probe> {
    val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE).toString() }.getOrDefault("unknown")
    val kgsl = File("/dev/kgsl-3d0")
    val gpu = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_name")
        .firstNotNullOfOrNull { path ->
            runCatching { File(path).takeIf(File::canRead)?.readText()?.trim()?.takeIf(String::isNotBlank) }.getOrNull()
        } ?: "not exposed"
    val abis = Build.SUPPORTED_ABIS.joinToString()
    val ram = runCatching { File("/proc/meminfo").readLines().first { it.startsWith("MemTotal:") }.substringAfter(":").trim() }
        .getOrDefault("unknown")
    return listOf(
        Probe("Device", "${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"),
        Probe("CPU ABI", abis, "Runtime host architecture"),
        Probe("Memory pages", "$pageSize bytes", if (pageSize == "4096") "FEX 4 KB prerequisite appears satisfied" else "FEX compatibility must be investigated"),
        Probe("GPU", gpu, if (kgsl.exists()) "KGSL device node is visible; Vulkan still needs a real test" else "KGSL device node is not visible to this app"),
        Probe("RAM", ram, "Kernel-reported physical memory"),
        Probe("Storage", Environment.getDataDirectory().absolutePath, "Runtime payloads will use app-private storage")
    )
}

@Composable
private fun RuntimeHome() {
    var probes by remember { mutableStateOf(inspectDevice()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Linux Runtime", style = MaterialTheme.typography.headlineLarge)
        Text("Linux x86-64 applications on Android ARM64", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runtime status", style = MaterialTheme.typography.titleLarge)
                Text("Engine not installed", color = MaterialTheme.colorScheme.primary)
                Text("This independent app shell does not yet bundle FEX, Box64, a Linux rootfs or a graphics bridge. The engine installer is a separate milestone.")
            }
        }
        Text("Device compatibility", style = MaterialTheme.typography.titleLarge)
        probes.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = MaterialTheme.typography.labelLarge)
                    Text(item.value, style = MaterialTheme.typography.bodyLarge)
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { probes = inspectDevice() }) { Text("Recheck device") }
        }
        Text("Planned components", style = MaterialTheme.typography.titleLarge)
        Text("• Debian / Ubuntu ARM64 host userspace\n• x86-64 guest rootfs and FEX backend\n• Optional Box64 backend\n• Turnip / Vulkan capability detection\n• X11 and native Surface presentation\n• Audio, touch, mouse and gamepad routing\n• Per-app profiles, logs and compatibility reports")
        Text("Development build · not yet an executable Linux environment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
